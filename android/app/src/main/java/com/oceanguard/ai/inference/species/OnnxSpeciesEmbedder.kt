package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.sqrt

/**
 * ONNX Runtime Mobile implementation of [SpeciesEmbedder].
 *
 * Runs an exported OpenCLIP ViT-B/32 (or MobileCLIP-S2) ONNX model via
 * ONNX Runtime for Android. Uses the NNAPI Execution Provider if available,
 * falling back to CPU (XNNPACK). GpuDelegate is NOT used — same reasoning as
 * [com.oceanguard.ai.inference.RTDETRInference]: Exynos Xclipse 920 raises a
 * NoClassDefFoundError for GpuDelegateFactory$Options.
 *
 * ## Preprocessing (CLIP canonical):
 *   1. Resize + center-crop to 224×224.
 *   2. Normalize: mean=[0.481, 0.457, 0.408], std=[0.268, 0.261, 0.275]
 *      (OpenCLIP LAION-2B statistics).
 *   3. Layout: NCHW float32 [1, 3, 224, 224].
 *
 * ## Postprocessing:
 *   1. Extract output tensor (shape [1, 512]).
 *   2. L2-normalise the 512-dim vector before returning.
 *
 * @param modelFile  Path to the downloaded `.onnx` model file.
 */
class OnnxSpeciesEmbedder(private val modelFile: File) : SpeciesEmbedder {

    companion object {
        private const val TAG = "OnnxSpeciesEmbedder"
        private const val INPUT_SIZE = 224
        private const val INPUT_NAME = "pixel_values"   // matches the exported ONNX model
        private const val OUTPUT_NAME = "image_features"

        // CLIP LAION-2B normalization constants
        private val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f,  0.40821073f)
        private val CLIP_STD  = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private var ortEnv: OrtEnvironment? = null
    @Volatile private var ortSession: OrtSession? = null

    /**
     * Serializes [initialize] so a concurrent first [embed] and a background
     * [prewarm] never each create a native [OrtSession]. The ~352 MB session +
     * NNAPI graph compile must happen exactly once and be reused across every
     * identify / batch image — a second session would double native RAM and
     * recompile the graph. Cheap to take: held only for the one-time load.
     */
    private val initMutex = Mutex()

    /** Ready once the model file is present; the ORT session is loaded lazily on
     *  the first [embed] call (on Dispatchers.IO), so no suspend init is needed
     *  at construction time. */
    override val isReady: Boolean
        get() = modelFile.exists()

    /**
     * Load the ONNX model and initialise the ORT session **once**. Idempotent:
     * concurrent callers (a background [prewarm] racing the first [embed]) share
     * the single session created under [initMutex]; later calls short-circuit.
     * Must be called from a background dispatcher before the first [embed] call.
     *
     * @throws IllegalStateException if [modelFile] does not exist.
     * @throws ai.onnxruntime.OrtException on ONNX Runtime errors.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (ortSession != null) return@withContext
            check(modelFile.exists()) {
                "Species encoder not found at ${modelFile.absolutePath}. Download it first."
            }
            // Reuse the process-wide singleton environment (never per-session).
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // NNAPI EP with fp16 relaxation: fp32→fp16 on the accelerator is a
                // sizeable speedup on the Exynos NPU/GPU and visually lossless for
                // a CLIP embedding (we L2-normalise the output anyway). Falls back
                // to CPU automatically when NNAPI is unavailable.
                runCatching { addNnapi(EnumSet.of(NNAPIFlags.USE_FP16)) }
                    .onFailure {
                        Log.w(TAG, "NNAPI fp16 flag unavailable, plain NNAPI: ${it.message}")
                        addNnapi()
                    }
                setIntraOpNumThreads(4)   // 4 big cores for the CPU-fallback path
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val startMs = System.currentTimeMillis()
            ortEnv = env
            ortSession = env.createSession(modelFile.absolutePath, opts)
            Log.i(TAG, "ORT session created in ${System.currentTimeMillis() - startMs}ms")
        }
    }

    /**
     * Eagerly load the session **and** run one dummy 1×3×224×224 inference so the
     * NNAPI graph compile (and any kernel JIT) happens ahead of the first real
     * [embed]. Intended to be launched off the UI thread when BioDex opens / the
     * app is idle, so the first identify does not pay the multi-second session
     * load + compile. Idempotent and exception-safe: any failure is logged and
     * swallowed (the lazy path in [embed] still works).
     */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        try {
            if (!isReady) {
                Log.d(TAG, "prewarm skipped — model file not present")
                return@withContext
            }
            initialize()
            val session = ortSession ?: return@withContext
            val env = ortEnv ?: return@withContext
            val dummy = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)   // zeros — content irrelevant
            val startMs = System.currentTimeMillis()
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(dummy),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { tensor ->
                session.run(mapOf(INPUT_NAME to tensor)).close()
            }
            Log.i(TAG, "Encoder pre-warmed (NNAPI compile) in ${System.currentTimeMillis() - startMs}ms")
        } catch (e: Exception) {
            Log.w(TAG, "Encoder prewarm failed (non-fatal): ${e.message}")
        }
    }

    override suspend fun embed(bitmap: Bitmap): FloatArray = withContext(Dispatchers.IO) {
        if (ortSession == null) initialize()   // lazy one-time session load (mutex-guarded)
        val session = ortSession ?: error("ORT session is null")
        val env = ortEnv ?: error("ORT environment is null")

        val preprocessed = preprocess(bitmap)
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(preprocessed),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )

        inputTensor.use { tensor ->
            val results = session.run(mapOf(INPUT_NAME to tensor))
            results.use { output ->
                val rawFeatures = (output[OUTPUT_NAME].get().value as Array<*>)[0] as FloatArray
                l2Normalize(rawFeatures)
            }
        }
    }

    override fun close() {
        ortSession?.close()
        ortEnv?.close()
        ortSession = null
        ortEnv = null
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Resize-to-[INPUT_SIZE] (when needed) + NCHW float32 + CLIP normalisation.
     *
     * Fast-path: when the caller already produced an [INPUT_SIZE]×[INPUT_SIZE]
     * bitmap (the normal path through [RagCropPreparer]) we skip the redundant
     * crop + resize and read pixels directly. That avoids a second centre-crop
     * that used to chop the long-axis flanks of elongated organisms and a
     * needless bilinear pass. The else branch keeps back-compat with arbitrary
     * input sizes (e.g. the legacy centre-square fallback).
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val scaled: Bitmap
        val cropped: Bitmap?
        if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            scaled = bitmap
            cropped = null
        } else {
            val size = minOf(bitmap.width, bitmap.height)
            val xOff = (bitmap.width - size) / 2
            val yOff = (bitmap.height - size) / 2
            cropped = Bitmap.createBitmap(bitmap, xOff, yOff, size, size)
            scaled = Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)
        }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val tensor = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8)  and 0xFF) / 255f
            val b = ( pixel         and 0xFF) / 255f
            tensor[i]                              = (r - CLIP_MEAN[0]) / CLIP_STD[0]
            tensor[INPUT_SIZE * INPUT_SIZE + i]    = (g - CLIP_MEAN[1]) / CLIP_STD[1]
            tensor[2 * INPUT_SIZE * INPUT_SIZE + i]= (b - CLIP_MEAN[2]) / CLIP_STD[2]
        }

        // Only recycle bitmaps we allocated; never recycle the caller's input.
        if (cropped != null && cropped !== bitmap) cropped.recycle()
        if (scaled !== bitmap && scaled !== cropped) scaled.recycle()
        return tensor
    }

    /** In-place L2-normalisation. Returns [v] for chaining. */
    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
        return v
    }
}
