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
 * additionally tracks batch progress and service-level lifecycle.
 */
sealed class InferenceServiceState {

    /** No inference work is active. */
    data object Idle : InferenceServiceState()

    /** A single image is being processed. */
    data class SingleRunning(val uri: Uri) : InferenceServiceState()

    /** Single image processing completed. The session has been saved by the service. */
    data class SingleComplete(
        val uri: Uri,
        val result: AnalysisResult,
    ) : InferenceServiceState()

    /** A batch of images is being processed sequentially. */
    data class BatchRunning(
        val allUris: List<Uri>,
        val currentIndex: Int,
        val completedItems: List<BatchItemResult>,
    ) : InferenceServiceState()

    /** All images in the batch have been processed. Sessions saved by the service. */
    data class BatchComplete(
        val allUris: List<Uri>,
        val results: List<BatchItemResult>,
    ) : InferenceServiceState()

    /** An unrecoverable error occurred in the service. */
    data class Error(val message: String) : InferenceServiceState()
}

/** Result of processing a single image within a batch job. */
sealed class BatchItemResult {
    abstract val uri: Uri

    data class Done(
        override val uri: Uri,
        val result: AnalysisResult,
        val sessionId: Long = 0,
        val annotatedUri: String? = null,
    ) : BatchItemResult()

    data class Failed(
        override val uri: Uri,
        val message: String,
    ) : BatchItemResult()
}
