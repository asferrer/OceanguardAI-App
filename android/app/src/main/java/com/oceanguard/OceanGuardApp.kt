package com.oceanguard.ai

import android.app.Application
import android.util.Log
import com.oceanguard.ai.data.DetectionRepository
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.OceanGuardDatabase
import com.oceanguard.ai.data.VideoAnalysisDao
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.TourDemoDataManager
import com.oceanguard.ai.data.collection.AchievementChecker
import com.oceanguard.ai.data.collection.CollectionRepository
import com.oceanguard.ai.data.collection.DexBackfill
import com.oceanguard.ai.inference.DetectionOrchestrator
import com.oceanguard.ai.inference.OceanGuardInference
import com.oceanguard.ai.inference.RTDETRInference
import com.oceanguard.ai.inference.ReportGenerator
import com.oceanguard.ai.inference.VideoProcessor
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.inference.ZoneReportInput
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.service.ReportGenerationService
import com.oceanguard.ai.service.VlmDownloadService
import com.oceanguard.ai.utils.LocationProvider
import com.oceanguard.ai.utils.PhotonGeocoderClient
import com.oceanguard.ai.utils.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Model loading state (shared with UI)
// ---------------------------------------------------------------------------

enum class ModelStatus { NotLoaded, Loading, WarmingUp, Ready, Error, Standby }

data class ModelLoadingState(
    val rtdetr: ModelStatus = ModelStatus.NotLoaded,
    val gemma: ModelStatus = ModelStatus.Standby,
) {
    /** Detection is ready when RT-DETR is loaded. VLM loads on demand for reports. */
    val allReady: Boolean get() = rtdetr == ModelStatus.Ready
}

// ---------------------------------------------------------------------------
// Report generation state (shared with UI, survives Activity recreation)
// ---------------------------------------------------------------------------

sealed class ReportGenerationState {
    data object Idle : ReportGenerationState()
    data object LoadingModel : ReportGenerationState()
    data object Generating : ReportGenerationState()
    data class Complete(val report: GeneratedReport) : ReportGenerationState()
    data class Error(val message: String) : ReportGenerationState()
}

/**
 * OceanGuard AI Application Class
 *
 * Initializes all inference engines and database during app startup.
 * Models are loaded once and reused for all subsequent inferences.
 */
class OceanGuardApp : Application() {

    companion object {
        private const val TAG = "OceanGuardApp"

        /** Keep VLM loaded for 2 minutes after last use to avoid reloading on consecutive reports. */
        private const val VLM_RETAIN_MS = 120_000L
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vlmReleaseJob: Job? = null

    // Inference engines
    lateinit var oceanGuardInference: OceanGuardInference
        private set
    lateinit var rtdetrInference: RTDETRInference
        private set
    lateinit var detectionOrchestrator: DetectionOrchestrator
        private set

    // Database
    val database: OceanGuardDatabase by lazy {
        OceanGuardDatabase.getInstance(this)
    }

    // Collection / Gamification
    val collectionRepository: CollectionRepository by lazy {
        CollectionRepository(database.marineDexDao(), database.achievementDao())
    }
    val achievementChecker: AchievementChecker by lazy {
        AchievementChecker(
            collectionRepo = collectionRepository,
            achievementDao = database.achievementDao(),
            marineDexDao = database.marineDexDao(),
            sessionDao = database.detectionSessionDao(),
        )
    }

    val repository: DetectionRepository by lazy {
        DetectionRepository(database.detectionSessionDao(), achievementChecker)
    }

    val videoAnalysisDao: VideoAnalysisDao by lazy {
        database.videoAnalysisDao()
    }

    // Settings
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    // Location
    val locationProvider: LocationProvider by lazy {
        LocationProvider(this)
    }

    // Geocoding (shared singleton for LRU cache)
    val geocoder: PhotonGeocoderClient by lazy { PhotonGeocoderClient() }

    // Update checker
    val updateChecker: UpdateChecker by lazy { UpdateChecker(settingsRepository) }

    // VLM model download manager
    val vlmModelManager: VlmModelManager by lazy { VlmModelManager(this) }

    // Tour demo data manager
    val tourDemoDataManager: TourDemoDataManager by lazy {
        TourDemoDataManager(
            repository = repository,
            settingsRepository = settingsRepository,
            reportDao = database.generatedReportDao(),
            marineDexDao = database.marineDexDao(),
            achievementDao = database.achievementDao(),
        )
    }

    /**
     * Run demo-data cleanup in [applicationScope] so it survives Activity /
     * composable lifecycle (e.g. navigation away from SettingsScreen).
     */
    fun launchDemoCleanup() {
        applicationScope.launch { tourDemoDataManager.cleanup() }
    }

    /** Convenience: only cleans up if all guided-tour screens are complete. */
    fun launchDemoCleanupIfComplete() {
        applicationScope.launch { tourDemoDataManager.cleanupIfAllToursComplete() }
    }

    /**
     * Reset all tours and re-inject demo data in [applicationScope] so it
     * survives the navigation away from SettingsScreen.
     */
    fun launchTourReset() {
        applicationScope.launch {
            settingsRepository.resetAllTours()
            tourDemoDataManager.ensureDemoDataIfNeeded()
        }
    }

    // Model loading state — tracks loading + warm-up progress for UI
    val modelLoadingState = MutableStateFlow(ModelLoadingState())

    // Report generation state — survives Activity recreation via applicationScope
    val reportGenerationState = MutableStateFlow<ReportGenerationState>(ReportGenerationState.Idle)

    // Inference service state — shared between InferenceService and UI layer
    val inferenceServiceState = MutableStateFlow<InferenceServiceState>(InferenceServiceState.Idle)

    // Current VideoProcessor reference — set by InferenceService during video jobs
    // so the UI can subscribe to live frame updates for real-time preview.
    val currentVideoProcessor = MutableStateFlow<VideoProcessor?>(null)

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "OceanGuard AI starting...")

        // Initialize inference engines
        oceanGuardInference = OceanGuardInference(this)
        // Select model based on user preference (INT8 is now Erf-free, safe on Exynos 2200)
        val precision = settingsRepository.getDetectorPrecisionSync()
        val detectorModel = if (precision == "int8") {
            RTDETRInference.MODEL_PATH_INT8
        } else {
            RTDETRInference.MODEL_PATH_FP16
        }
        Log.i(TAG, "Detector model: $detectorModel (precision=$precision)")
        rtdetrInference = RTDETRInference(this, detectorModel)
        detectionOrchestrator = DetectionOrchestrator(this, rtdetrInference, oceanGuardInference)

        // Seed achievements and backfill MarineDex from existing sessions
        applicationScope.launch {
            try {
                collectionRepository.ensureAchievementsSeeded()
                val backfillDone = settingsRepository.dexBackfillComplete.first()
                if (!backfillDone) {
                    DexBackfill.backfillFromExistingSessions(
                        database.detectionSessionDao(), collectionRepository
                    )
                    settingsRepository.setDexBackfillComplete(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "MarineDex seed/backfill failed (non-fatal)", e)
            }
            // Inject demo data for guided tours if DB is empty
            tourDemoDataManager.ensureDemoDataIfNeeded()
        }

        // Load and warm-up models asynchronously (parallel)
        applicationScope.launch {
            try {
                modelLoadingState.update { it.copy(rtdetr = ModelStatus.Loading) }
                Log.i(TAG, "Loading RT-DETRv2 detector...")
                val startTime = System.currentTimeMillis()
                rtdetrInference.initialize()
                Log.i(TAG, "RT-DETRv2 loaded in ${System.currentTimeMillis() - startTime}ms")

                modelLoadingState.update { it.copy(rtdetr = ModelStatus.WarmingUp) }
                rtdetrInference.warmUp()
                modelLoadingState.update { it.copy(rtdetr = ModelStatus.Ready) }
            } catch (e: Exception) {
                Log.e(TAG, "RT-DETRv2 initialization failed (non-fatal)", e)
                modelLoadingState.update { it.copy(rtdetr = ModelStatus.Error) }
            }
        }

        // VLM (Gemma 3n) is NOT loaded at startup — it initializes on demand
        // when the user generates a report, saving battery and RAM.
        Log.i(TAG, "VLM deferred: will load on demand for report generation")
    }

    /**
     * Launch report generation in applicationScope so it survives Activity
     * recreation. The report is auto-saved to Room on completion.
     */
    fun launchReportGeneration(sessions: List<DetectionSession>, language: String) {
        val currentState = reportGenerationState.value
        if (currentState is ReportGenerationState.Generating ||
            currentState is ReportGenerationState.LoadingModel) return
        vlmReleaseJob?.cancel() // Cancel pending release — we need the model
        startForegroundReportService()
        applicationScope.launch {
            try {
                // Step 1: Load VLM on demand (skip if already loaded from previous report)
                if (!oceanGuardInference.isModelReady()) {
                    reportGenerationState.value = ReportGenerationState.LoadingModel
                    modelLoadingState.update { it.copy(gemma = ModelStatus.Loading) }
                    Log.i(TAG, "Loading Gemma 3n VLM on demand for report...")
                    val startTime = System.currentTimeMillis()
                    oceanGuardInference.initialize()
                    Log.i(TAG, "VLM loaded in ${System.currentTimeMillis() - startTime}ms")
                    modelLoadingState.update { it.copy(gemma = ModelStatus.WarmingUp) }
                    oceanGuardInference.warmUp()
                    modelLoadingState.update { it.copy(gemma = ModelStatus.Ready) }
                } else {
                    Log.i(TAG, "VLM already loaded — skipping initialization")
                }

                // Step 2: Generate AI report
                reportGenerationState.value = ReportGenerationState.Generating
                val generator = ReportGenerator(oceanGuardInference)
                val reportText = generator.generateReport(sessions, language)
                val report = GeneratedReport(
                    text = reportText,
                    language = language,
                    sessionCount = sessions.size,
                    usedAi = true,
                )
                val id = database.generatedReportDao().insert(report)
                reportGenerationState.value =
                    ReportGenerationState.Complete(report.copy(id = id))
                Log.i(TAG, "Report generated and saved (id=$id)")
            } catch (e: Exception) {
                Log.e(TAG, "Report generation failed", e)
                modelLoadingState.update { it.copy(gemma = ModelStatus.Error) }
                reportGenerationState.value =
                    ReportGenerationState.Error(e.message ?: "Unknown error")
            } finally {
                scheduleVlmRelease()
            }
        }
    }

    /**
     * Launch zone-based report generation with temporal evolution analysis.
     * The report is auto-saved to Room with location metadata.
     */
    fun launchZoneReportGeneration(input: ZoneReportInput, language: String) {
        val currentState = reportGenerationState.value
        if (currentState is ReportGenerationState.Generating ||
            currentState is ReportGenerationState.LoadingModel) return
        vlmReleaseJob?.cancel() // Cancel pending release — we need the model
        startForegroundReportService()
        applicationScope.launch {
            try {
                // Step 1: Load VLM on demand (skip if already loaded from previous report)
                if (!oceanGuardInference.isModelReady()) {
                    reportGenerationState.value = ReportGenerationState.LoadingModel
                    modelLoadingState.update { it.copy(gemma = ModelStatus.Loading) }
                    Log.i(TAG, "Loading Gemma 3n VLM on demand for zone report...")
                    val startTime = System.currentTimeMillis()
                    oceanGuardInference.initialize()
                    Log.i(TAG, "VLM loaded in ${System.currentTimeMillis() - startTime}ms")
                    modelLoadingState.update { it.copy(gemma = ModelStatus.WarmingUp) }
                    oceanGuardInference.warmUp()
                    modelLoadingState.update { it.copy(gemma = ModelStatus.Ready) }
                } else {
                    Log.i(TAG, "VLM already loaded — skipping initialization")
                }

                // Step 2: Generate AI report
                reportGenerationState.value = ReportGenerationState.Generating
                val generator = ReportGenerator(oceanGuardInference)
                val reportText = generator.generateZoneReport(input, language)
                val report = GeneratedReport(
                    text = reportText,
                    language = language,
                    sessionCount = input.sessions.size,
                    usedAi = true,
                    locationName = input.locationName,
                    centroidLat = input.centroidLat,
                    centroidLon = input.centroidLon,
                    dateRangeStartMs = input.dateRangeStartMs,
                    dateRangeEndMs = input.dateRangeEndMs,
                )
                val id = database.generatedReportDao().insert(report)
                reportGenerationState.value =
                    ReportGenerationState.Complete(report.copy(id = id))
                Log.i(TAG, "Zone report generated and saved (id=$id)")
            } catch (e: Exception) {
                Log.e(TAG, "Zone report generation failed", e)
                modelLoadingState.update { it.copy(gemma = ModelStatus.Error) }
                reportGenerationState.value =
                    ReportGenerationState.Error(e.message ?: "Unknown error")
            } finally {
                scheduleVlmRelease()
            }
        }
    }

    /**
     * Launch VLM model download in applicationScope so it survives
     * Activity recreation. Progress is tracked via [vlmModelManager.downloadState].
     */
    fun launchVlmDownload() {
        startForegroundDownloadService()
        applicationScope.launch {
            try {
                vlmModelManager.downloadModel()
            } catch (e: Exception) {
                Log.e(TAG, "VLM download failed", e)
            }
        }
    }

    private fun startForegroundDownloadService() {
        try {
            val intent = VlmDownloadService.startIntent(this)
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start VLM download service (non-fatal)", e)
        }
    }

    /**
     * Schedule VLM release after [VLM_RETAIN_MS]. If the user generates
     * another report within the window, the timer is cancelled and the
     * model is reused — saving 15-45s of reload time.
     */
    private fun scheduleVlmRelease() {
        vlmReleaseJob?.cancel()
        vlmReleaseJob = applicationScope.launch {
            delay(VLM_RETAIN_MS)
            try {
                if (oceanGuardInference.isModelReady()) {
                    oceanGuardInference.release()
                    Log.i(TAG, "VLM auto-released after ${VLM_RETAIN_MS / 1000}s idle")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing VLM", e)
            }
            modelLoadingState.update { it.copy(gemma = ModelStatus.Standby) }
        }
    }

    /** Force-release VLM immediately (called on memory pressure). */
    private fun releaseVlmNow() {
        vlmReleaseJob?.cancel()
        try {
            if (oceanGuardInference.isModelReady()) {
                oceanGuardInference.release()
                Log.i(TAG, "VLM force-released (memory pressure)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VLM", e)
        }
        modelLoadingState.update { it.copy(gemma = ModelStatus.Standby) }
    }

    private fun startForegroundReportService() {
        try {
            val intent = ReportGenerationService.startIntent(this)
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start report foreground service (non-fatal)", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory warning - clearing caches")
        try {
            if (oceanGuardInference.isModelReady()) {
                oceanGuardInference.clearCache()
            }
            System.gc()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL, TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure (level: $level) — releasing VLM")
                releaseVlmNow()
                System.gc()
            }
            TRIM_MEMORY_MODERATE, TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.i(TAG, "Moderate memory pressure (level: $level)")
                try {
                    if (oceanGuardInference.isModelReady()) {
                        oceanGuardInference.clearCache()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during memory trim", e)
                }
            }
        }
    }
}
