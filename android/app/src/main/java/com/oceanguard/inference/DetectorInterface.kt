package com.oceanguard.ai.inference

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Common contract for all object detection models (PicoDet-S, RT-DETRv2, etc.).
 *
 * Implementations MUST be thread-safe for concurrent access from
 * [LiveDetectionManager] (camera frames) and [InferenceService][com.oceanguard.ai.service.InferenceService] (batch).
 */
interface ObjectDetector {
    /** Human-readable name for logs and UI (e.g. "PicoDet-S 320", "RT-DETRv2 FP16"). */
    val displayName: String

    /** Model input resolution in pixels (e.g. 320, 640). */
    val inputSize: Int

    /** Whether the model is loaded and ready for inference. */
    fun isReady(): Boolean

    /** Load model, allocate buffers, configure delegates. */
    suspend fun initialize()

    /** Run dummy inferences to warm up JIT/graph compilation. */
    suspend fun warmUp()

    /**
     * Detect objects in a bitmap.
     * @param bitmap Input image (will be resized to [inputSize] internally).
     * @param confidenceThreshold Minimum score to keep a detection.
     * @return Detections with normalized [0,1] bounding boxes.
     */
    suspend fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = 0.5f,
    ): List<DetectionResult>

    /** Release all native resources (interpreter, delegates, buffers). */
    fun release()
}

/** Enumerates available detector models for the settings UI. */
enum class DetectorType(val key: String, val displayLabel: String) {
    PICODET_S("picodet", "PicoDet-S (Fast, ~1 MB)"),
    RT_DETR_V2("rtdetr", "RT-DETRv2 (Accurate, 83 MB)"),
}

/** Canonical class names shared by all detectors (must match training labels). */
object DebrisClasses {
    val NAMES = arrayOf(
        "Bottle", "Can", "Fishing_Net", "Glove", "Mask",
        "Metal_Debris", "Plastic_Debris", "Tire",
    )
    const val COUNT = 8
}

// -------------------------------------------------------------------------
// Detection result (shared output type for all detectors)
// -------------------------------------------------------------------------

/** Single detection with normalized [0,1] bounding box coordinates. */
data class DetectionResult(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val classId: Int,
    val className: String,
    val confidence: Float,
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f
    val centerY: Float get() = (y1 + y2) / 2f
}

// -------------------------------------------------------------------------
// Shared per-class NMS
// -------------------------------------------------------------------------

/** Per-class Non-Maximum Suppression reusable by any [ObjectDetector]. */
object DetectionNms {

    /**
     * Keep highest-confidence detections, suppressing overlaps per class.
     *
     * @param detections Candidate detections (any class mix).
     * @param iouThreshold IoU above which the lower-scored box is suppressed.
     * @param maxDetections Hard cap on returned detections.
     */
    fun apply(
        detections: List<DetectionResult>,
        iouThreshold: Float = 0.5f,
        maxDetections: Int = 100,
    ): List<DetectionResult> {
        val kept = mutableListOf<DetectionResult>()

        for ((_, classDets) in detections.groupBy { it.classId }) {
            val sorted = classDets.sortedByDescending { it.confidence }
            val suppressed = BooleanArray(sorted.size)

            for (i in sorted.indices) {
                if (suppressed[i]) continue
                kept.add(sorted[i])
                for (j in i + 1 until sorted.size) {
                    if (suppressed[j]) continue
                    if (iou(sorted[i], sorted[j]) > iouThreshold) {
                        suppressed[j] = true
                    }
                }
            }
        }

        return kept.sortedByDescending { it.confidence }.take(maxDetections)
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
}
