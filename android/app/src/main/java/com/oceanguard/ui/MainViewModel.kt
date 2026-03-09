package com.oceanguard.ai.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.DetectionRepository
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.DetectionStatistics
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.inference.AnalysisState
import com.oceanguard.ai.inference.DetectionOrchestrator
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.service.InferenceService
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.utils.BitmapAnnotator
import com.oceanguard.ai.utils.ExifLocationExtractor
import com.oceanguard.ai.utils.ImagePersistence
import com.oceanguard.ai.utils.LocationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// UI state machine
// ---------------------------------------------------------------------------

/**
 * Sealed class representing every distinct state the main detection screen
 * can occupy.  The ViewModel exposes this as a [StateFlow] so the UI can
 * react to transitions without polling.
 *
 * State flow:
 *
 *   Idle
 *     -> ModelLoading  (model not yet warmed up)
 *     -> Detecting     (frame/image sent to inference pipeline)
 *     -> DetectionsReady (raw object detections available)
 *     -> AnalyzingDeep  (Gemma 3n generating natural-language analysis)
 *     -> AnalysisComplete (full result ready for display and persistence)
 *     -> Error         (unrecoverable failure at any stage)
 *
 * Calling [MainViewModel.resetState] always returns to [Idle].
 */
sealed class UiState {

    /** No active task. The screen shows the capture / gallery entry points. */
    data object Idle : UiState()

    /**
     * The on-device model is being loaded or warmed up.
     *
     * @param progress Human-readable progress description, e.g.
     *   "Loading base model… (1 / 3)" or "Applying LoRA adapter…"
     */
    data class ModelLoading(val progress: String) : UiState()

    /**
     * An image has been submitted to the RT-DETRv2 pipeline.
     * The UI should show an indeterminate progress indicator.
     */
    data object Detecting : UiState()

    /**
     * Object-level detections are available.  The UI can render bounding
     * boxes immediately while the deep analysis runs in the background.
     *
     * @param detections List of detected debris objects with bounding boxes,
     *   material classification, and confidence scores.
     */
    data class DetectionsReady(val detections: List<DetectionResult>) : UiState()

    /**
     * Gemma 3n is generating the ecosystem health analysis.
     * The UI should keep showing the detection overlays and a progress bar.
     *
     * @param detections Same list passed from [DetectionsReady] so the UI
     *   does not need to cache it separately.
     */
    data class AnalyzingDeep(val detections: List<DetectionResult>) : UiState()

    /**
     * The full pipeline has completed.  The result is ready for display
     * and has been (or is being) persisted to Room.
     *
     * @param result Complete analysis including health score, material
     *   breakdown, recommendations, and processing metrics.
     */
    data class AnalysisComplete(val result: AnalysisResult) : UiState()

    /**
     * An unrecoverable error occurred.  The message is suitable for
     * display in a Snackbar or error card.
     *
     * @param message Localised or developer-readable error description.
     */
    data class Error(val message: String) : UiState()
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * MainViewModel — coordinates the detection pipeline and session persistence.
 *
 * Inference is delegated to [InferenceService] (a Foreground Service) so
 * processing survives app backgrounding. The ViewModel observes the
 * app-scoped [InferenceServiceState] and [AnalysisState] to keep the UI
 * in sync with the service's progress.
 */
class MainViewModel(
    private val appContext: Context,
    private val orchestrator: DetectionOrchestrator,
    private val repository: DetectionRepository,
    val settingsRepository: SettingsRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val app = appContext.applicationContext as OceanGuardApp

    // -----------------------------------------------------------------------
    // UI state
    // -----------------------------------------------------------------------

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)

    /**
     * Current UI state.  Collect this in a Composable with
     * [androidx.lifecycle.compose.collectAsStateWithLifecycle] to avoid
     * collecting from a stopped lifecycle.
     */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _capturedImageUri = MutableStateFlow<Uri?>(null)

    /**
     * URI of the image currently being analysed. Set when [analyzeImage] is
     * called and cleared on [resetState]. The ResultsScreen observes this to
     * display the captured photo while the pipeline runs.
     */
    val capturedImageUri: StateFlow<Uri?> = _capturedImageUri.asStateFlow()

    private val _batchUris = MutableStateFlow<List<Uri>>(emptyList())

    /**
     * URIs selected for batch processing. Set by the multi-picker on HomeScreen
     * and consumed by BatchResultsScreen.
     */
    val batchUris: StateFlow<List<Uri>> = _batchUris.asStateFlow()

    /** Store batch URIs from the multi-picker before navigating to batch screen. */
    fun setBatchUris(uris: List<Uri>) {
        _batchUris.value = uris
    }

    // -----------------------------------------------------------------------
    // State observation — reconnects UI to in-progress or completed work
    // -----------------------------------------------------------------------

    init {
        // Observe the orchestrator's analysis state for progressive UI updates
        // (real-time pipeline stage transitions while the service is running).
        viewModelScope.launch {
            orchestrator.analysisState.collectLatest { analysisState ->
                // Only map when the service is actively processing a single image
                val serviceState = app.inferenceServiceState.value
                if (serviceState is InferenceServiceState.SingleRunning) {
                    _uiState.value = mapAnalysisState(analysisState)
                }
            }
        }

        // Observe service-level state for reconnection after backgrounding.
        // When the user returns to the app, the ViewModel is recreated and this
        // collector immediately receives the latest service state.
        viewModelScope.launch {
            app.inferenceServiceState.collectLatest { serviceState ->
                when (serviceState) {
                    is InferenceServiceState.SingleRunning -> {
                        _capturedImageUri.value = serviceState.uri
                        // Map the current orchestrator state for progressive UI
                        _uiState.value = mapAnalysisState(orchestrator.analysisState.value)
                    }
                    is InferenceServiceState.SingleComplete -> {
                        _capturedImageUri.value = serviceState.uri
                        _uiState.value = UiState.AnalysisComplete(serviceState.result)
                    }
                    is InferenceServiceState.Error -> {
                        _uiState.value = UiState.Error(serviceState.message)
                    }
                    // Idle, BatchRunning, BatchComplete — handled by BatchResultsScreen directly
                    else -> {}
                }
            }
        }
    }

    private fun mapAnalysisState(analysisState: AnalysisState): UiState = when (analysisState) {
        is AnalysisState.Idle -> UiState.Idle
        is AnalysisState.LoadingImage -> UiState.ModelLoading("Loading image...")
        is AnalysisState.Detecting -> UiState.Detecting
        is AnalysisState.DetectionsReady -> UiState.DetectionsReady(analysisState.detections)
        is AnalysisState.AnalyzingDeep -> UiState.AnalyzingDeep(analysisState.detections)
        is AnalysisState.Complete -> UiState.AnalysisComplete(analysisState.result)
        is AnalysisState.Error -> UiState.Error(analysisState.message)
    }

    // -----------------------------------------------------------------------
    // Persistent data exposed to the UI
    // -----------------------------------------------------------------------

    /**
     * All detection sessions ordered by timestamp descending.
     * Backed by a Room [kotlinx.coroutines.flow.Flow] so the list updates
     * automatically when a new session is saved.
     */
    val allSessions: Flow<List<DetectionSession>> = repository.allSessions

    /**
     * Total number of detection sessions ever recorded.
     */
    val totalSessions: Flow<Int> = repository.totalSessionCount

    /**
     * Average ecosystem health score across all sessions, or null when no
     * sessions exist.
     */
    val avgHealthScore: Flow<Float?> = repository.averageHealthScore

    /**
     * Aggregate statistics across all sessions (material breakdown, hotspots, etc.).
     */
    val statistics: Flow<DetectionStatistics> = repository.statistics

    // -----------------------------------------------------------------------
    // Pipeline entry point
    // -----------------------------------------------------------------------

    /**
     * Triggers the full two-stage detection pipeline for the supplied image
     * via the [InferenceService] foreground service.
     *
     * The service runs inference in a lifecycle-aware scope that survives
     * app backgrounding, and auto-saves the result to Room.
     *
     * @param imageUri Content URI of the image to analyse.
     */
    fun analyzeImage(imageUri: Uri) {
        _capturedImageUri.value = imageUri
        _uiState.value = UiState.Detecting
        val intent = InferenceService.singleImageIntent(appContext, imageUri)
        ContextCompat.startForegroundService(appContext, intent)
    }

    /**
     * Starts batch processing for multiple images via the foreground service.
     */
    fun startBatchInference(uris: List<Uri>) {
        _batchUris.value = uris
        val intent = InferenceService.batchIntent(appContext, uris)
        ContextCompat.startForegroundService(appContext, intent)
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /**
     * Persists the completed analysis as a [DetectionSession] in Room.
     *
     * In the normal flow, the [InferenceService] saves sessions automatically.
     * This method is retained for manual save from the UI (e.g. Save button)
     * when the service did not handle persistence.
     */
    fun saveSession(
        imageUri: String,
        result: AnalysisResult,
        location: Location?,
    ) {
        viewModelScope.launch {
            try {
                // Render bounding boxes onto the image and save as annotated thumbnail
                val annotatedUri = if (result.rtdetrDetections.isNotEmpty() && imageUri.isNotEmpty()) {
                    try {
                        BitmapAnnotator.annotateAndSave(
                            context = appContext,
                            imageUri = Uri.parse(imageUri),
                            detections = result.rtdetrDetections,
                        )
                    } catch (e: Exception) {
                        null // Non-fatal: save session without annotated image
                    }
                } else null

                // Persist source image to internal storage so URI survives app restart
                val persistedUri = if (imageUri.isNotEmpty()) {
                    ImagePersistence.persistImage(appContext, Uri.parse(imageUri))
                } else imageUri

                val session = DetectionSession(
                    imageUri = persistedUri,
                    thumbnailUri = annotatedUri,
                    debrisList = result.vlmAnalysis.debrisList,
                    totalCount = result.totalDebrisCount,
                    healthScore = result.healthScore,
                    location = location,
                    imageQuality = result.vlmAnalysis.imageQuality,
                    processingTimeMs = result.processingTimeMs,
                )
                repository.saveSession(session)
            } catch (exception: Exception) {
                // Persistence failure is non-fatal — the user already sees the
                // result.  Surface as a transient error state only if the UI
                // is still showing AnalysisComplete.
                if (_uiState.value is UiState.AnalysisComplete) {
                    _uiState.value = UiState.Error(
                        "Could not save session: ${exception.message}"
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Session queries
    // -----------------------------------------------------------------------

    /**
     * Fetch a single session by ID. Returns null if not found.
     */
    suspend fun getSession(id: Long): DetectionSession? = repository.getSession(id)

    /**
     * Resolves the best location for a session: prefers EXIF GPS from the image,
     * falls back to device GPS. Returns null if neither source yields a fix.
     */
    suspend fun resolveLocation(imageUri: String? = null): Location? {
        if (imageUri != null) {
            val exif = try {
                ExifLocationExtractor.extract(appContext, android.net.Uri.parse(imageUri))
            } catch (_: Exception) { null }
            if (exif != null) return exif
        }
        return locationProvider.getLastKnownLocation()
    }

    // -----------------------------------------------------------------------
    // Session deletion
    // -----------------------------------------------------------------------

    /**
     * Delete a single session from the database.
     */
    fun deleteSession(session: DetectionSession) {
        viewModelScope.launch {
            repository.deleteSession(session)
        }
    }

    // -----------------------------------------------------------------------
    // Batch session operations (multi-select)
    // -----------------------------------------------------------------------

    fun updateSessionsTimestamp(ids: List<Long>, newTimestamp: java.util.Date) {
        viewModelScope.launch { repository.updateSessionsTimestamp(ids, newTimestamp) }
    }

    fun updateSessionsLocation(ids: List<Long>, location: Location) {
        viewModelScope.launch { repository.updateSessionsLocation(ids, location) }
    }

    fun deleteSessions(ids: List<Long>) {
        viewModelScope.launch { repository.deleteSessions(ids) }
    }

    /**
     * Delete all sessions from the database. Irreversible.
     */
    fun clearAllSessions() {
        viewModelScope.launch {
            repository.clearAllSessions()
        }
    }

    /**
     * Delete all data: sessions, MarineDex entries, and achievements.
     * Re-seeds empty achievements so the user starts completely fresh.
     */
    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAllSessions()
            app.collectionRepository.resetAll()
            settingsRepository.setDexBackfillComplete(false)
        }
    }

    // -----------------------------------------------------------------------
    // Navigation / lifecycle helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the UI to [UiState.Idle].
     *
     * Call this when the user navigates back to the capture screen, dismisses
     * an error dialog, or explicitly starts a new detection.
     */
    fun resetState() {
        _uiState.value = UiState.Idle
        _capturedImageUri.value = null
        // Clear service state so the init collector doesn't re-emit a stale result
        app.inferenceServiceState.value = InferenceServiceState.Idle
        orchestrator.reset()
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        // Do NOT reset the orchestrator here — InferenceService owns the
        // inference lifecycle. Clearing the ViewModel on Activity recreation
        // must not kill an in-progress background inference.
    }
}
