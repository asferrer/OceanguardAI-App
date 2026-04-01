package com.oceanguard.ai.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * RT-DETRv2 inference wrapper for fast object detection (~30ms).
 *
 * Uses TensorFlow Lite with GPU delegate for real-time debris detection.
 * The TFLite model exports raw outputs (no postprocessor baked in):
 *   - pred_logits: [1, NUM_QUERIES, NUM_CLASSES] raw logits (apply sigmoid)
 *   - pred_boxes:  [1, NUM_QUERIES, 4] cxcywh normalized [0,1]
 *
 * Postprocessing (sigmoid, threshold filtering, cxcywh→xyxy conversion)
 * is performed in Kotlin for maximum TFLite compatibility.
 */
/**
 * Delegate strategy for TFLite interpreter initialization.
 * Use [XNNPACK_ONLY] to benchmark CPU-only vs [NNAPI_PREFERRED] which adds
 * big.LITTLE scheduling on Exynos 2200 (~28% speedup historically).
 */
enum class DelegateStrategy {
    NNAPI_PREFERRED,  // NNAPI → XNNPACK fallback (default, best on Exynos 2200)
    XNNPACK_ONLY,     // Pure XNNPACK CPU (for benchmarking)
}

class RTDETRInference(
    private val context: Context,
    private val modelPath: String = MODEL_PATH_FP16,
    private val delegateStrategy: DelegateStrategy = DelegateStrategy.NNAPI_PREFERRED,
) : ObjectDetector {

    companion object {
        private const val TAG = "RTDETRInference"
        const val MODEL_PATH_FP16 = "models/rtdetrv2_detector.tflite"
        const val MODEL_PATH_INT8 = "models/rtdetrv2_detector_int8.tflite"
        const val INPUT_SIZE = 640
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.5f
        private const val NMS_IOU_THRESHOLD = 0.5f
        private const val MAX_DETECTIONS = 100

        // Optimal thread count: 4 big cores only (1x Cortex-X2 + 3x Cortex-A710).
        // LITTLE cores (A510) are 3-4x slower and become bottlenecks when XNNPACK
        // distributes work uniformly across all 8 cores.
        private const val OPTIMAL_THREAD_COUNT = 4

        // Model architecture constants (from training config rtdetrv2_r50vd_densea_v9_focal.yml)
        private const val NUM_QUERIES = 150
        private const val NUM_CLASSES = 8

        val CLASS_NAMES = DebrisClasses.NAMES
    }

    override val displayName: String
        get() = "RT-DETRv2 ${if (modelPath.contains("int8")) "INT8" else "FP16"}"

    override val inputSize: Int = INPUT_SIZE

    private var interpreter: Interpreter? = null
    private var nnapiDelegate: NnApiDelegate? = null

    @Volatile
    private var isInitialized = false

    // Pre-allocated reusable buffers to avoid GC pressure during continuous detection
    private var reusableInputBuffer: ByteBuffer? = null
    private var reusablePixelArray: IntArray? = null
    // Flat FloatArray for RGB normalisation — avoids 1.2M ByteBuffer.putFloat() JNI calls
    // by converting the pixel loop to array writes + one FloatBuffer.put(FloatArray) bulk call.
    private var reusableFloatArray: FloatArray? = null
    private var reusableLogitsBuffer: Array<Array<FloatArray>>? = null
    private var reusableBoxesBuffer: Array<Array<FloatArray>>? = null

    // Cached output tensor index mapping (auto-detected once at init)
    private var cachedLogitsIdx = -1
    private var cachedBoxesIdx = -1

    // Which delegate is currently active (for logging)
    @Volatile
    private var activeDelegateName = "unknown"

    /**
     * Initialize TFLite interpreter with delegate fallback chain:
     * NNAPI → CPU (XNNPACK, 4 threads on big cores for Exynos 2200).
     *
     * GPU delegate is skipped — Exynos Xclipse 920 (AMD RDNA2) doesn't provide
     * `GpuDelegateFactory$Options`, causing a NoClassDefFoundError every time.
     *
     * NNAPI claims 0 explicit nodes on Exynos 2200 for this model, but benchmarks
     * show it's still ~28% faster than CPU-only (NNAPI=5.9s vs XNNPACK=7.7s),
     * likely due to NNAPI optimizing the execution schedule across big.LITTLE cores.
     */
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        try {
            val precision = if (modelPath.contains("int8")) "INT8" else "FP16"
            Log.i(TAG, "Initializing RT-DETRv2 model ($precision): $modelPath")

            val model = loadModelFile()
            val numCores = Runtime.getRuntime().availableProcessors()
            Log.i(TAG, "Available CPU cores: $numCores, using $OPTIMAL_THREAD_COUNT (big cores only), strategy=$delegateStrategy")

            when (delegateStrategy) {
                DelegateStrategy.NNAPI_PREFERRED -> initWithNnapi(model)
                DelegateStrategy.XNNPACK_ONLY -> initWithXnnpack(model)
            }

            // Log actual tensor shapes for debugging
            val interp = interpreter!!
            Log.i(TAG, "Input tensor:  ${interp.getInputTensor(0).shape().contentToString()}")
            for (i in 0 until interp.outputTensorCount) {
                Log.i(TAG, "Output tensor $i: ${interp.getOutputTensor(i).shape().contentToString()}")
            }

            // Cache output tensor index mapping (onnx2tf may swap logits/boxes)
            val out0Shape = interp.getOutputTensor(0).shape()
            if (out0Shape.last() == 4) {
                cachedBoxesIdx = 0; cachedLogitsIdx = 1
            } else {
                cachedLogitsIdx = 0; cachedBoxesIdx = 1
            }
            Log.i(TAG, "Output mapping: logits=output[$cachedLogitsIdx], boxes=output[$cachedBoxesIdx]")

            // Pre-allocate reusable buffers
            reusableInputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            reusablePixelArray  = IntArray(INPUT_SIZE * INPUT_SIZE)
            reusableFloatArray  = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
            reusableLogitsBuffer = Array(1) { Array(NUM_QUERIES) { FloatArray(NUM_CLASSES) } }
            reusableBoxesBuffer = Array(1) { Array(NUM_QUERIES) { FloatArray(4) } }

            isInitialized = true
            Log.i(TAG, "RT-DETRv2 ready (delegate=$activeDelegateName, buffers pre-allocated)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RT-DETRv2", e)
            throw RTDETRInitException("RT-DETRv2 initialization failed: ${e.message}", e)
        }
    }

    /**
     * Initialize with NNAPI delegate (preferred on Exynos 2200 for big.LITTLE scheduling).
     * Falls back to XNNPACK if NNAPI fails.
     */
    private fun initWithNnapi(model: MappedByteBuffer) {
        // Use a local var so nnapiDelegate is only assigned after the interpreter succeeds,
        // guaranteeing the delegate is always closed if Interpreter() throws.
        var delegate: NnApiDelegate? = null
        try {
            delegate = NnApiDelegate(
                NnApiDelegate.Options().apply {
                    setAllowFp16(true)
                    setUseNnapiCpu(false)
                }
            )
            val options = Interpreter.Options().apply {
                addDelegate(delegate)
                setNumThreads(OPTIMAL_THREAD_COUNT)
            }
            interpreter = Interpreter(model, options)
            nnapiDelegate = delegate   // assign only after successful init
            activeDelegateName = "NNAPI+XNNPACK"
            Log.i(TAG, "Initialized with NNAPI+XNNPACK ($OPTIMAL_THREAD_COUNT threads)")
        } catch (e: Throwable) {
            Log.w(TAG, "NNAPI failed, falling back to XNNPACK-only", e)
            delegate?.close()
            nnapiDelegate = null
            initWithXnnpack(model)
        }
    }

    /**
     * Initialize with pure XNNPACK delegate (CPU-only, big cores).
     * Useful for benchmarking against NNAPI.
     */
    private fun initWithXnnpack(model: MappedByteBuffer) {
        val options = Interpreter.Options().apply {
            setNumThreads(OPTIMAL_THREAD_COUNT)
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(model, options)
        activeDelegateName = "CPU/XNNPACK"
        Log.i(TAG, "Initialized with XNNPACK ($OPTIMAL_THREAD_COUNT threads)")
    }

    /**
     * Run object detection on a bitmap.
     *
     * @param bitmap Input image (will be resized to 640x640)
     * @param confidenceThreshold Minimum score to keep a detection (0.0–1.0)
     * @return List of detected objects with bounding boxes
     */
    override suspend fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float,
    ): List<DetectionResult> = withContext(Dispatchers.Default) {
        val interp = interpreter ?: throw IllegalStateException("RT-DETRv2 not initialized")

        try {
            val startTime = System.currentTimeMillis()

            // Preprocess: resize to 640x640 and normalize to [0,1]
            val inputBuffer = preprocessImage(bitmap)

            // Use pre-allocated output buffers, clear them first
            val logitsBuffer = reusableLogitsBuffer ?: Array(1) { Array(NUM_QUERIES) { FloatArray(NUM_CLASSES) } }
            val boxesBuffer = reusableBoxesBuffer ?: Array(1) { Array(NUM_QUERIES) { FloatArray(4) } }

            val outputMap = mapOf(
                cachedLogitsIdx to logitsBuffer,
                cachedBoxesIdx to boxesBuffer
            )

            // Run inference with multiple outputs
            interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

            val inferenceTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "RT-DETRv2 inference: ${inferenceTime}ms")

            // Post-process: sigmoid, threshold, cxcywh→xyxy, NMS
            val results = postProcess(
                logitsBuffer[0], boxesBuffer[0],
                bitmap.width, bitmap.height,
                confidenceThreshold,
            )

            Log.i(TAG, "Detected ${results.size} objects in ${inferenceTime}ms")
            results

        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            emptyList()
        }
    }

    /**
     * Run detection on a bitmap for live detection mode.
     * Uses pre-allocated buffers to minimize GC pressure.
     * The bitmap is resized to INPUT_SIZE (640) if needed — the TFLite model
     * has a fixed input shape and cannot accept other resolutions.
     *
     * @param bitmap Input bitmap (will be resized to 640x640 if needed)
     * @return List of detected objects with normalized bounding boxes
     */
    suspend fun detectPreResized(
        bitmap: Bitmap,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    ): List<DetectionResult> = withContext(Dispatchers.Default) {
        val interp = interpreter ?: throw IllegalStateException("RT-DETRv2 not initialized")

        try {
            val startTime = System.currentTimeMillis()

            // Safety: TFLite model has fixed input [1, 640, 640, 3]. Resize if needed.
            val safeBitmap = if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
                Log.w(TAG, "detectPreResized: bitmap ${bitmap.width}x${bitmap.height} != " +
                    "${INPUT_SIZE}x${INPUT_SIZE}, resizing internally")
                Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            } else {
                bitmap
            }

            val inputBuffer = reusableInputBuffer?.also { it.clear() }
                ?: ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
                    order(ByteOrder.nativeOrder())
                }

            val pixels    = reusablePixelArray ?: IntArray(INPUT_SIZE * INPUT_SIZE)
            val floatData = reusableFloatArray  ?: FloatArray(INPUT_SIZE * INPUT_SIZE * 3)

            safeBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            var idx = 0
            for (pixel in pixels) {
                floatData[idx++] = ((pixel shr 16) and 0xFF) * (1f / 255f)
                floatData[idx++] = ((pixel shr 8)  and 0xFF) * (1f / 255f)
                floatData[idx++] = (pixel          and 0xFF) * (1f / 255f)
            }
            inputBuffer.asFloatBuffer().put(floatData)
            inputBuffer.rewind()

            val logitsBuffer = reusableLogitsBuffer ?: Array(1) { Array(NUM_QUERIES) { FloatArray(NUM_CLASSES) } }
            val boxesBuffer = reusableBoxesBuffer ?: Array(1) { Array(NUM_QUERIES) { FloatArray(4) } }

            val outputMap = mapOf(
                cachedLogitsIdx to logitsBuffer,
                cachedBoxesIdx to boxesBuffer
            )

            interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

            if (safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }

            val inferenceTime = System.currentTimeMillis() - startTime

            val results = postProcess(
                logitsBuffer[0], boxesBuffer[0],
                INPUT_SIZE, INPUT_SIZE,
                confidenceThreshold,
            )

            Log.d(TAG, "RT-DETRv2 live: ${results.size} detections in ${inferenceTime}ms")
            results

        } catch (e: Exception) {
            Log.e(TAG, "Live detection failed: ${e.message}. " +
                "Bitmap: ${bitmap.width}x${bitmap.height}, expected: ${INPUT_SIZE}x${INPUT_SIZE}", e)
            emptyList()
        }
    }

    /**
     * Preprocess image for RT-DETR: resize to 640x640, normalize to [0,1].
     * Output format: NHWC [1, 640, 640, 3] float32 (matches TFLite convention).
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // Use pre-allocated buffer when available
        val inputBuffer = if (reusableInputBuffer != null) {
            reusableInputBuffer!!.also { it.clear() }
        } else {
            ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
        }

        val pixels    = reusablePixelArray  ?: IntArray(INPUT_SIZE * INPUT_SIZE)
        val floatData = reusableFloatArray  ?: FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // Convert ARGB packed pixels → interleaved RGB float [0,1].
        // Writing to a FloatArray then calling FloatBuffer.put(FloatArray) replaces
        // 1,228,800 ByteBuffer.putFloat() JNI round-trips with a single bulk native copy.
        // Multiplication by (1f/255f) avoids per-pixel division on ARM.
        var idx = 0
        for (pixel in pixels) {
            floatData[idx++] = ((pixel shr 16) and 0xFF) * (1f / 255f) // R
            floatData[idx++] = ((pixel shr 8)  and 0xFF) * (1f / 255f) // G
            floatData[idx++] = (pixel          and 0xFF) * (1f / 255f) // B
        }
        inputBuffer.asFloatBuffer().put(floatData)
        // ByteBuffer position is unaffected by the FloatBuffer view; rewind() is a no-op
        // but kept for clarity in case future code checks position.
        inputBuffer.rewind()

        if (resized != bitmap) {
            resized.recycle()
        }

        return inputBuffer
    }

    /**
     * Post-process raw model outputs (Kotlin reimplementation of RTDETRPostProcessor).
     *
     * Mirrors the PyTorch postprocessor with use_focal_loss=True:
     * 1. Apply sigmoid to raw logits to get class scores
     * 2. Filter by confidence threshold
     * 3. Convert boxes from cxcywh to xyxy (kept normalized [0,1] for UI scaling)
     * 4. Per-class NMS to remove overlapping detections (keep highest confidence)
     * 5. Sort by confidence, take top MAX_DETECTIONS
     */
    private fun postProcess(
        logits: Array<FloatArray>,   // [NUM_QUERIES, NUM_CLASSES] raw logits
        boxes: Array<FloatArray>,    // [NUM_QUERIES, 4] cxcywh normalized [0,1]
        originalWidth: Int,
        originalHeight: Int,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    ): List<DetectionResult> {
        val candidates = mutableListOf<DetectionResult>()

        for (q in logits.indices) {
            for (c in logits[q].indices) {
                if (c >= CLASS_NAMES.size) continue
                val raw = logits[q][c]
                if (raw < -5f) continue
                val score = sigmoid(raw)
                if (score < confidenceThreshold) continue

                val cx = boxes[q][0]
                val cy = boxes[q][1]
                val w = boxes[q][2]
                val h = boxes[q][3]

                val x1 = (cx - w / 2f).coerceIn(0f, 1f)
                val y1 = (cy - h / 2f).coerceIn(0f, 1f)
                val x2 = (cx + w / 2f).coerceIn(0f, 1f)
                val y2 = (cy + h / 2f).coerceIn(0f, 1f)

                candidates.add(
                    DetectionResult(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        classId = c,
                        className = CLASS_NAMES[c],
                        confidence = score,
                    )
                )
            }
        }

        return DetectionNms.apply(candidates, NMS_IOU_THRESHOLD, MAX_DETECTIONS)
    }

    private fun sigmoid(x: Float): Float = 1.0f / (1.0f + exp(-x))

    /**
     * Load TFLite model from assets.
     * Both AssetFileDescriptor and FileInputStream are closed after mapping to avoid FD leaks.
     */
    private fun loadModelFile(): MappedByteBuffer {
        return context.assets.openFd(modelPath).use { afd ->
            FileInputStream(afd.fileDescriptor).use { inputStream ->
                inputStream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    /**
     * Run dummy inferences to warm up the delegate and CPU caches.
     * The first inference compiles the NNAPI/XNNPACK graph (slow);
     * subsequent runs measure steady-state latency.
     */
    override suspend fun warmUp() = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext
        val dummy = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        try {
            // Run 1: graph compilation (NNAPI or XNNPACK JIT)
            val t0 = System.currentTimeMillis()
            detect(dummy)
            val compileMs = System.currentTimeMillis() - t0
            Log.i(TAG, "Warm-up run 1 (graph compile): ${compileMs}ms [delegate=$activeDelegateName]")

            // Runs 2-3: measure steady-state latency
            val steadyTimes = mutableListOf<Long>()
            repeat(2) {
                val t = System.currentTimeMillis()
                detect(dummy)
                steadyTimes.add(System.currentTimeMillis() - t)
            }
            val avgSteady = steadyTimes.average().toLong()
            Log.i(TAG, "Warm-up runs 2-3: ${steadyTimes.joinToString()}ms, avg=${avgSteady}ms [delegate=$activeDelegateName]")
            Log.i(TAG, "RT-DETRv2 warm-up complete. Compile=${compileMs}ms, steady=${avgSteady}ms")
        } catch (e: Exception) {
            Log.w(TAG, "RT-DETRv2 warm-up failed (non-fatal)", e)
        } finally {
            dummy.recycle()
        }
    }

    /**
     * Check if model is ready.
     */
    override fun isReady(): Boolean = isInitialized

    /**
     * Release resources.
     */
    override fun release() {
        interpreter?.close()
        interpreter = null
        nnapiDelegate?.close()
        nnapiDelegate = null
        reusableInputBuffer = null
        reusablePixelArray = null
        reusableLogitsBuffer = null
        reusableBoxesBuffer = null
        isInitialized = false
        Log.i(TAG, "RT-DETRv2 resources released")
    }
}

class RTDETRInitException(message: String, cause: Throwable? = null) : Exception(message, cause)
