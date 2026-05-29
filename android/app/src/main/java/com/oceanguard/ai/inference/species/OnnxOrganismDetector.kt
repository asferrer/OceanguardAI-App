package com.oceanguard.ai.inference.species

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import android.util.Log
import com.oceanguard.ai.data.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet

/**
 * Class-agnostic marine organism detector backed by a YOLO26-N ONNX model
 * exported by `finetune/detector/train_detector.py` + `yolo export`.
 *
 * Replaces the slow Gemma-4 VLM `box_2d` localiser in [OrganismLocator]: the
 * VLM cold call is ~9 s on Exynos 2200, this NNAPI ONNX path targets ~20-30 ms
 * end-to-end per the SOTA research recipe.
 *
 * Why ONNX Runtime Mobile and not TFLite for this detector:
 *  - YOLO26 trains and exports cleanly to ONNX opset 14 with no NMS embedded.
 *  - The species RAG path already loads ORT for [OnnxSpeciesEmbedder]; reusing
 *    the runtime means one less JNI surface and one less Java dep to ship.
 *  - The onnx2tf → INT8 TFLite path had a dep conflict (numpy / jax / TF) on
 *    our Blackwell-cu128 training rig; a clean ORT FP32 deploys today and we
 *    can quantise later in an isolated env.
 *
 * Contract (verified post-train):
 *   input  `images`:  [1, 3, 416, 416] float32 (NCHW), values [0,1].
 *   output `output0`: [1, 300, 6]      float32 (x1, y1, x2, y2, conf, cls)
 *                     Coordinates are in INPUT pixels (0..416), NOT normalised.
 *                     The 300 rows are sorted by confidence descending.
 *                     YOLO26's end-to-end head is NMS-free → no NMS in Kotlin.
 *
 * @param modelFile Absolute path to the exported `.onnx` file.
 * @param useNnapi  When true (default) attempts NNAPI EP first and falls back
 *                  to CPU on failure (mirrors [OnnxSpeciesEmbedder]).
 */
class OnnxOrganismDetector(
    private val modelFile: File,
    private val useNnapi: Boolean = true,
) {
    companion object {
        private const val TAG = "OnnxOrganismDetector"

        const val INPUT_SIZE = 416
        const val INPUT_NAME = "images"
        const val OUTPUT_NAME = "output0"

        // YOLO26 end-to-end head emits 300 top-k boxes (NMS-free).
        private const val NUM_BOXES = 300
        private const val OUT_COLS = 6

        // Tuned for RECALL — the RAG already filters low-confidence crops by
        // cosine score; we'd rather feed it an extra crop than miss the true
        // organism. Started at 0.25 but first device runs returned 0 boxes
        // for an obvious clownfish; lowering to 0.10 to surface anything the
        // model is actually predicting (Python sanity reported conf ≥ 0.90).
        private const val DEFAULT_CONF_THRESHOLD = 0.10f
    }

    /**
     * Detection in normalised image coordinates ([0,1] of the **original**
     * frame, not the 416 input). Converts back to pixel BoundingBox on demand.
     */
    data class Detection(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val confidence: Float,
    ) {
        fun toBoundingBoxPx(imgW: Int, imgH: Int): BoundingBox {
            val x = ((cx - w / 2f) * imgW).coerceAtLeast(0f)
            val y = ((cy - h / 2f) * imgH).coerceAtLeast(0f)
            val pw = (w * imgW).coerceAtMost(imgW - x)
            val ph = (h * imgH).coerceAtMost(imgH - y)
            return BoundingBox(x = x, y = y, width = pw, height = ph)
        }
    }

    private val mutex = Mutex()
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /**
     * Whether [detect] can be called. Only requires the .onnx file to exist;
     * the ORT session is created lazily on first detect()/prewarm() — keeping
     * isReady tied to `ortSession != null` deadlocks the caller (the
     * [OrganismLocator] gates detect() on isReady, but detect() is the only
     * site that initialises the session).
     */
    val isReady: Boolean get() = modelFile.exists()

    /** Build the ORT session once, NNAPI EP preferred, CPU fallback. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (ortSession != null) return@withContext
        mutex.withLock {
            if (ortSession != null) return@withLock
            check(modelFile.exists()) {
                "Detector model not found at ${modelFile.absolutePath}"
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            if (useNnapi) {
                try {
                    // Plain NNAPI (no USE_FP16): the YOLO26 detection head is
                    // sensitive to FP16 rounding on Mali drivers — first device
                    // run produced 0 boxes for a clownfish that Python scored at
                    // 0.99. Drop the flag and let NNAPI keep FP32 throughout.
                    opts.addNnapi(EnumSet.noneOf(NNAPIFlags::class.java))
                    Log.i(TAG, "NNAPI EP attached (FP32)")
                } catch (e: Throwable) {
                    Log.w(TAG, "NNAPI EP unavailable: ${e.message}")
                }
            }
            val t0 = System.currentTimeMillis()
            ortEnv = env
            ortSession = env.createSession(modelFile.absolutePath, opts)
            Log.i(TAG, "ORT session created in ${System.currentTimeMillis() - t0}ms")
        }
    }

    /** Pre-warm: triggers NNAPI compile with a zero forward. */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        try {
            initialize()
            val session = ortSession ?: return@withContext
            val env = ortEnv ?: return@withContext
            val dummy = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
            val t0 = System.currentTimeMillis()
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(dummy),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { tensor ->
                session.run(mapOf(INPUT_NAME to tensor)).close()
            }
            Log.i(TAG, "Detector pre-warmed (NNAPI compile) in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Throwable) {
            Log.w(TAG, "prewarm failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Run inference and return surviving detections in descending-confidence
     * order, in NORMALISED [0,1] coordinates of the original frame.
     */
    suspend fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = DEFAULT_CONF_THRESHOLD,
    ): List<Detection> = withContext(Dispatchers.IO) {
        initialize()
        val session = ortSession ?: error("ORT session null after initialize()")
        val env = ortEnv ?: error("ORT env null after initialize()")

        val preprocessed = preprocess(bitmap)
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(preprocessed),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        ).use { tensor ->
            val results = session.run(mapOf(INPUT_NAME to tensor))
            results.use { output ->
                val raw = output[OUTPUT_NAME].get().value as Array<*>
                @Suppress("UNCHECKED_CAST")
                val rows = raw[0] as Array<FloatArray>
                parseOutput(rows, confidenceThreshold)
            }
        }
    }

    fun close() {
        ortSession?.close()
        ortEnv?.close()
        ortSession = null
        ortEnv = null
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Uniform resize to [INPUT_SIZE], NCHW float32, [0,1] normalisation.
     * NOTE: no letterbox padding for v1 — RagCropPreparer upstream already
     * produced a sensible square crop in most paths. If letterbox is needed
     * later we'd compose the inverse transform in [parseOutput] to recover
     * the un-padded bbox in the original frame.
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // NCHW: 3 planes (R, G, B) each INPUT_SIZE*INPUT_SIZE floats.
        val plane = INPUT_SIZE * INPUT_SIZE
        val tensor = FloatArray(3 * plane)
        val inv255 = 1.0f / 255.0f
        for (i in pixels.indices) {
            val p = pixels[i]
            tensor[i] = ((p shr 16) and 0xFF) * inv255
            tensor[plane + i] = ((p shr 8) and 0xFF) * inv255
            tensor[2 * plane + i] = (p and 0xFF) * inv255
        }
        if (scaled !== bitmap) scaled.recycle()
        return tensor
    }

    /**
     * Decode YOLO26 `[300, 6]` end-to-end output. Coords are in INPUT pixels
     * (0..[INPUT_SIZE]), divide by [INPUT_SIZE] to recover normalised xyxy.
     * No NMS needed: YOLO26 trains the head NMS-free, the rows are already
     * filtered + sorted in the model.
     */
    private fun parseOutput(
        rows: Array<FloatArray>,
        confidenceThreshold: Float,
    ): List<Detection> {
        val inv = 1.0f / INPUT_SIZE.toFloat()
        val out = ArrayList<Detection>(rows.size / 4)
        var topConf = -1f
        for (r in rows) {
            if (r[4] > topConf) topConf = r[4]
            val conf = r[4]
            if (conf < confidenceThreshold) continue
            val x1 = (r[0] * inv).coerceIn(0f, 1f)
            val y1 = (r[1] * inv).coerceIn(0f, 1f)
            val x2 = (r[2] * inv).coerceIn(0f, 1f)
            val y2 = (r[3] * inv).coerceIn(0f, 1f)
            val w = (x2 - x1).coerceAtLeast(0f)
            val h = (y2 - y1).coerceAtLeast(0f)
            if (w <= 0f || h <= 0f) continue
            out.add(Detection(
                cx = x1 + w / 2f,
                cy = y1 + h / 2f,
                w  = w,
                h  = h,
                confidence = conf,
            ))
        }
        Log.d(TAG, "decode: ${out.size} boxes above $confidenceThreshold (topConf=$topConf, rows=${rows.size})")
        return out
    }
}
