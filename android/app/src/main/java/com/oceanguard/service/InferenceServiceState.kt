package com.oceanguard.ai.service

import android.net.Uri
import com.oceanguard.ai.inference.AnalysisResult

/**
 * App-scoped state for the [InferenceService].
 *
 * Shared between the service (writes) and the UI layer (reads) via a
 * MutableStateFlow hosted in [com.oceanguard.ai.OceanGuardApp].
 *
 * This is separate from [com.oceanguard.ai.inference.AnalysisState] which
 * only tracks a single image in the orchestrator. InferenceServiceState
 * additionally tracks batch progress, video progress, and queue lifecycle.
 */
sealed class InferenceServiceState {

    /** No inference work is active. */
    data object Idle : InferenceServiceState()

    /** A single image is being processed. */
    data class SingleRunning(
        val uri: Uri,
        val queueInfo: QueueInfo = QueueInfo(),
    ) : InferenceServiceState()

    /** Single image processing completed. The session has been saved by the service. */
    data class SingleComplete(
        val uri: Uri,
        val result: AnalysisResult,
        val sessionId: Long = 0,
        /** True when the image was automatically enqueued for contribution (consent given). */
        val contributionQueued: Boolean = false,
        /** URI of the pre-rendered annotated thumbnail (bboxes baked in). Null when no debris was detected. */
        val annotatedUri: String? = null,
    ) : InferenceServiceState()

    /** A batch of images is being processed sequentially. */
    data class BatchRunning(
        val allUris: List<Uri>,
        val currentIndex: Int,
        val completedItems: List<BatchItemResult>,
        val queueInfo: QueueInfo = QueueInfo(),
    ) : InferenceServiceState()

    /** All images in the batch have been processed. Sessions saved by the service. */
    data class BatchComplete(
        val allUris: List<Uri>,
        val results: List<BatchItemResult>,
        /** Number of images automatically enqueued for contribution (consent given). */
        val contributionQueuedCount: Int = 0,
    ) : InferenceServiceState()

    /** A video is being processed frame by frame. */
    data class VideoRunning(
        val uri: Uri,
        val currentFrame: Int,
        val totalFrames: Int,
        val elapsedTimeMs: Long,
        val estimatedRemainingMs: Long,
        val queueInfo: QueueInfo = QueueInfo(),
    ) : InferenceServiceState()

    /** Video processing completed. The analysis has been saved by the service. */
    data class VideoComplete(
        val analysisId: Long,
        val outputVideoUri: String?,
        val uniqueDebrisCount: Int,
        val processingTimeMs: Long,
    ) : InferenceServiceState()

    /** An unrecoverable error occurred in the service. */
    data class Error(val message: String) : InferenceServiceState()
}

/**
 * Info about the job queue, attached to running states so the UI can
 * show "Job 2/5 — 3 queued" style indicators.
 */
data class QueueInfo(
    /** ID of the current job (for deep-link matching). */
    val currentJobId: Long = 0,
    /** Number of jobs still waiting after the current one. */
    val pendingCount: Int = 0,
    /** Navigation route for notification deep-link. */
    val deepLinkRoute: String = "home",
)

/** Result of processing a single image within a batch job. */
sealed class BatchItemResult {
    abstract val uri: Uri

    data class Done(
        override val uri: Uri,
        val result: AnalysisResult,
        val sessionId: Long = 0,
        val annotatedUri: String? = null,
        val contributionQueued: Boolean = false,
    ) : BatchItemResult()

    data class Failed(
        override val uri: Uri,
        val message: String,
    ) : BatchItemResult()
}
