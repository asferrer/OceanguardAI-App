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
import kotlin.math.max
import kotlin.math.min

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
class RTDETRInference(
    private val context: Context,
    private val modelPath: String = MODEL_PATH_FP16,
) {

    companion object {
        private const val TAG = "RTDETRInference"
        const val MODEL_PATH_FP16 = "models/rtdetrv2_detector.tflite"
        const val MODEL_PATH_INT8 = "models/rtdetrv2_detector_int8.tflite"
        const val INPUT_SIZE = 640
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.5f
        private const val NMS_IOU_THRESHOLD = 0.5f
        private const val MAX_DETECTIONS = 100

        // Model architecture constants (from training config rtdetrv2_r50vd_densea_v9_focal.yml)
        private const val NUM_QUERIES = 150
        private const val NUM_CLASSES = 8

        // 8 classes matching densea_detection_v3.yml training dataset (0-indexed)
        val CLASS_NAMES = arrayOf(
            "Bottle", "Can", "Fishing_Net", "Glove", "Mask",
            "Metal_Debris", "Plastic_Debris", "Tire"
        )
    }

    private var interpreter: Interpreter? = null
    private var nnapiDelegate: NnApiDelegate? = null

    @Volatile
    private var isInitialized = false

    // Pre-allocated reusable buffers to avoid GC pressure during continuous detection
    private var reusableInputBuffer: ByteBuffer? = null
    private var reusablePixelArray: IntArray? = null
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
     * NNAPI → CPU (XNNPACK, 8 threads for Exynos 2200 big.LITTLE).
     *
     * GPU delegate is skipped — Exynos Xclipse 920 (AMD RDNA2) doesn't provide
     * `GpuDelegateFactory$Options`, causing a NoClassDefFoundError every time.
     *
     * NNAPI claims 0 explicit nodes on Exynos 2200 for this model, but benchmarks
     * show it's still ~28% faster than CPU-only (NNAPI=5.9s vs XNNPACK=7.7s),
     * likely due to NNAPI optimizing the execution schedule across big.LITTLE cores.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        try {
            val precision = if (modelPath.contains("int8")) "INT8" else "FP16"
            Log.i(TAG, "Initializing RT-DETRv2 model ($precision): $modelPath")

            val model = loadModelFile()
            val numCores = Runtime.getRuntime().availableProcessors()
            Log.i(TAG, "Available CPU cores: $numCores")

            // NNAPI delegate — even though it claims 0 nodes explicitly, it improves
            // scheduling on Exynos 2200 big.LITTLE by ~28% vs CPU-only XNNPACK.
            try {
                nnapiDelegate = NnApiDelegate(
                    NnApiDelegate.Options().apply {
                        setAllowFp16(true)
                        setUseNnapiCpu(false)
                    }
                )
                val options = Interpreter.Options().apply {
                    addDelegate(nnapiDelegate)
                    setNumThreads(numCores.coerceAtMost(8))
                }
                interpreter = Interpreter(model, options)
                activeDelegateName = "NNAPI+XNNPACK"
                Log.i(TAG, "RT-DETRv2 initialized with NNAPI+XNNPACK ($numCores threads)")
            } catch (e: Throwable) {
                Log.w(TAG, "NNAPI delegate failed, falling back to CPU", e)
                nnapiDelegate?.close()
                nnapiDelegate = null

                // CPU fallback with max threads + XNNPACK
                val options = Interpreter.Options().apply {
                    setNumThreads(numCores.coerceAtMost(8))
                    setUseXNNPACK(true)
                }
                interpreter = Interpreter(model, options)
                activeDelegateName = "CPU/XNNPACK"
                Log.i(TAG, "RT-DETRv2 initialized with CPU ($numCores threads, XNNPACK)")
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
            reusablePixelArray = IntArray(INPUT_SIZE * INPUT_SIZE)
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
     * Run object detection on a bitmap.
     *
     * @param bitmap Input image (will be resized to 640x640)
     * @param confidenceThreshold Minimum score to keep a detection (0.0–1.0)
     * @return List of detected objects with bounding boxes
     */
    suspend fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
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

            val pixels = reusablePixelArray ?: IntArray(INPUT_SIZE * INPUT_SIZE)

            safeBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (pixel in pixels) {
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
            }
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

        val pixels = reusablePixelArray ?: IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            // Normalize to [0, 1] — matches training transform ToDtype(scale=True)
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }

        if (resized != bitmap) {
            resized.recycle()
        }

        inputBuffer.rewind()
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

        return nms(candidates).take(MAX_DETECTIONS)
    }

    /**
     * Per-class Non-Maximum Suppression.
     *
     * Groups detections by class, then for each class keeps only the highest-
     * confidence detection when two boxes overlap above [NMS_IOU_THRESHOLD].
     */
    private fun nms(detections: List<DetectionResult>): List<DetectionResult> {
        val kept = mutableListOf<DetectionResult>()
        val byClass = detections.groupBy { it.classId }

        for ((_, dets) in byClass) {
            val sorted = dets.sortedByDescending { it.confidence }
            val suppressed = BooleanArray(sorted.size)

            for (i in sorted.indices) {
                if (suppressed[i]) continue
                kept.add(sorted[i])
                for (j in i + 1 until sorted.size) {
                    if (suppressed[j]) continue
                    if (iou(sorted[i], sorted[j]) > NMS_IOU_THRESHOLD) {
                        suppressed[j] = true
                    }
                }
            }
        }

        return kept.sortedByDescending { it.confidence }
    }

    /** Intersection-over-Union between two detections (xyxy normalized coords). */
    private fun iou(a: DetectionResult, b: DetectionResult): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val intersection = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun sigmoid(x: Float): Float = 1.0f / (1.0f + exp(-x))

    /**
     * Load TFLite model from assets.
     */
    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Run dummy inferences to warm up the delegate and CPU caches.
     * The first inference compiles the NNAPI/XNNPACK graph (slow);
     * subsequent runs measure steady-state latency.
     */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
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
    fun isReady(): Boolean = isInitialized

    /**
     * Release resources.
     */
    fun release() {
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

/**
 * Detection result from RT-DETRv2.
 */
data class DetectionResult(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val classId: Int,
    val className: String,
    val confidence: Float
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f
    val centerY: Float get() = (y1 + y2) / 2f
}

class RTDETRInitException(message: String, cause: Throwable? = null) : Exception(message, cause)
