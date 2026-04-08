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
import com.oceanguard.ai.inference.LlamaTextEngine
import com.oceanguard.ai.inference.LlamaVisionEngine
import com.oceanguard.ai.inference.RTDETRInference
import com.oceanguard.ai.inference.ReportAudience
import com.oceanguard.ai.inference.ReportGenerator
import com.oceanguard.ai.inference.ReportValidator
import com.oceanguard.ai.inference.Gemma4PromptFormatter
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.VlmProvider
import com.oceanguard.ai.inference.VideoProcessor
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.inference.ZoneReportInput
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.service.ReportGenerationService
import com.oceanguard.ai.service.VlmDownloadService
import com.oceanguard.ai.data.contribution.ContributionQueueDao
import com.oceanguard.ai.data.contribution.ContributionRepository
import com.oceanguard.ai.utils.ImagePreprocessor
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
    val qwenText: ModelStatus = ModelStatus.Standby,    // text reports (1.5B or 3B)
    val qwenVision: ModelStatus = ModelStatus.Standby,  // vision analysis (2B + mmproj)
    val activeTierName: String = "",                     // e.g. "Fast (1.5B)"
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
    /** VLM is verifying detections image-by-image before report generation. */
    data class VerifyingDetections(val verified: Int, val total: Int) : ReportGenerationState()
    data object Generating : ReportGenerationState()
    data class StreamingText(
        val partialText: String,
        val tokenCount: Int = 0,
        val tokensPerSec: Float = 0f,
        val maxTokens: Int = 0,
    ) : ReportGenerationState()
    data class Complete(val report: GeneratedReport) : ReportGenerationState()
    data class Error(val message: String) : ReportGenerationState()
}

/**
 * Tracks token generation metrics for the streaming UI.
 *
 * Estimates token count from the partial text length (1 token ~ 4 chars for
 * most GGUF models). Computes a rolling tokens/second rate.
 */
class GenerationTracker(val maxTokens: Int) {
    private val startMs = System.currentTimeMillis()
    private var lastTokenCount = 0

    fun snapshot(partialText: String): ReportGenerationState.StreamingText {
        val estimatedTokens = partialText.length / 4
        val elapsedSec = (System.currentTimeMillis() - startMs) / 1000f
        val tokPerSec = if (elapsedSec > 0.5f) estimatedTokens / elapsedSec else 0f
        lastTokenCount = estimatedTokens
        return ReportGenerationState.StreamingText(
            partialText  = partialText,
            tokenCount   = estimatedTokens,
            tokensPerSec = tokPerSec,
            maxTokens    = maxTokens,
        )
    }
}

/**
 * OceanGuard AI Application Class
 *
 * Initializes all inference engines and database at startup.
 * VLM engines load on demand and are auto-released after 2-minute idle.
 */
class OceanGuardApp : Application() {

    companion object {
        private const val TAG = "OceanGuardApp"

        /** Keep VLM loaded 2 min after last use to avoid reloading on consecutive reports. */
        private const val VLM_RETAIN_MS = 120_000L

        // REPORT_TIER is now dynamic — see bestAvailableTier()
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var vlmReleaseJob: Job? = null

    // Inference engines
    lateinit var rtdetrInference: RTDETRInference
        private set
    lateinit var detectionOrchestrator: DetectionOrchestrator
        private set

    // Text engine — one at a time, tier-aware. Access via getOrCreateTextEngine().
    @Volatile private var _vlmTextEngine: LlamaTextEngine? = null

    // Vision engine — lazy, provider-aware. Recreated when VLM provider changes.
    @Volatile private var _vlmVisionEngine: LlamaVisionEngine? = null
    val vlmVisionEngine: LlamaVisionEngine
        get() = _vlmVisionEngine ?: createVisionEngine().also { _vlmVisionEngine = it }

    private fun createVisionEngine(): LlamaVisionEngine {
        val provider = VlmProvider.fromKey(settingsRepository.getVlmProviderSync())
        return when (provider) {
            VlmProvider.GEMMA4 -> LlamaVisionEngine(
                formatter    = Gemma4PromptFormatter,
                displayLabel = "Gemma 4 E2B + mmproj",
            )
            else -> LlamaVisionEngine()
        }
    }

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
            marineDexDao   = database.marineDexDao(),
            sessionDao     = database.detectionSessionDao(),
        )
    }

    val repository: DetectionRepository by lazy {
        DetectionRepository(database.detectionSessionDao(), achievementChecker)
    }

    val videoAnalysisDao: VideoAnalysisDao by lazy {
        database.videoAnalysisDao()
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    val locationProvider: LocationProvider by lazy {
        LocationProvider(this)
    }

    val geocoder: PhotonGeocoderClient by lazy { PhotonGeocoderClient() }

    val updateChecker: UpdateChecker by lazy { UpdateChecker(settingsRepository) }

    val vlmModelManager: VlmModelManager by lazy { VlmModelManager(this) }

    val imagePreprocessor: ImagePreprocessor by lazy { ImagePreprocessor(this) }

    // Research contribution
    val contributionQueueDao: ContributionQueueDao by lazy { database.contributionQueueDao() }
    val contributionRepository: ContributionRepository by lazy {
        ContributionRepository(contributionQueueDao, settingsRepository, this)
    }
    /** Emitted by [DataContributionWorker] after an upload cycle. Consumed once by the UI. */
    val contributionUploadResult = MutableStateFlow<com.oceanguard.ai.service.ContributionUploadResult?>(null)

    val tourDemoDataManager: TourDemoDataManager by lazy {
        TourDemoDataManager(
            repository        = repository,
            settingsRepository = settingsRepository,
            reportDao         = database.generatedReportDao(),
            marineDexDao      = database.marineDexDao(),
            achievementDao    = database.achievementDao(),
        )
    }

    fun launchDemoCleanup() {
        applicationScope.launch { tourDemoDataManager.cleanup() }
    }

    fun launchDemoCleanupIfComplete() {
        applicationScope.launch { tourDemoDataManager.cleanupIfAllToursComplete() }
    }

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

    // Inference service state
    val inferenceServiceState = MutableStateFlow<InferenceServiceState>(InferenceServiceState.Idle)

    val currentVideoProcessor = MutableStateFlow<VideoProcessor?>(null)

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "OceanGuard AI starting...")

        val precision = settingsRepository.getDetectorPrecisionSync()
        val detectorModel = if (precision == "int8") {
            RTDETRInference.MODEL_PATH_INT8
        } else {
            RTDETRInference.MODEL_PATH_FP16
        }
        Log.i(TAG, "Detector model: $detectorModel (precision=$precision)")
        rtdetrInference = RTDETRInference(this, detectorModel)
        detectionOrchestrator = DetectionOrchestrator(this, rtdetrInference, null)

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
            tourDemoDataManager.ensureDemoDataIfNeeded()
        }

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

        Log.i(TAG, "VLM deferred: will load on demand for report generation")
    }

    // -----------------------------------------------------------------------
    // Text engine management (always Quality 3B)
    // -----------------------------------------------------------------------

    /**
     * Best available text tier respecting user preference and selected provider.
     * Tries the user-selected tier first; falls back to highest available quality
     * within the same provider family.
     */
    private fun bestAvailableTier(): TextModelTier {
        val provider = VlmProvider.fromKey(settingsRepository.getVlmProviderSync())
        val preferred = TextModelTier.fromKey(settingsRepository.getVlmModelTierSync())

        // If the preferred tier matches the selected provider and is available, use it
        if (preferred.provider == provider && vlmModelManager.isModelAvailable(preferred)) {
            return preferred
        }

        // Fall back to the best available tier within the selected provider
        val providerTiers = TextModelTier.forProvider(provider)
        val available = providerTiers.lastOrNull { vlmModelManager.isModelAvailable(it) }
        if (available != null) return available

        // Ultimate fallback: any provider, any tier
        return when {
            vlmModelManager.isModelAvailable(TextModelTier.QUALITY)  -> TextModelTier.QUALITY
            vlmModelManager.isModelAvailable(TextModelTier.BALANCED) -> TextModelTier.BALANCED
            else -> TextModelTier.FAST
        }
    }

    /**
     * Loads the best available text tier if not already ready.
     * Sets [ReportGenerationState.LoadingModel] while loading.
     * Must be called from a coroutine inside [applicationScope].
     */
    private suspend fun loadTextEngineIfNeeded(): LlamaTextEngine {
        val tier = bestAvailableTier()
        val existing = _vlmTextEngine
        val engine = if (existing != null && existing.tier == tier) existing
                     else {
                         existing?.release()
                         LlamaTextEngine(tier).also { _vlmTextEngine = it }
                     }
        if (!engine.isReady()) {
            reportGenerationState.value = ReportGenerationState.LoadingModel
            modelLoadingState.update {
                it.copy(qwenText = ModelStatus.Loading, activeTierName = tier.displayName)
            }
            Log.i(TAG, "Loading ${tier.displayName} on demand...")
            engine.initialize(vlmModelManager.getTextModelPath(tier))
            modelLoadingState.update { it.copy(qwenText = ModelStatus.WarmingUp) }
            engine.warmUp()
            modelLoadingState.update { it.copy(qwenText = ModelStatus.Ready) }
        } else {
            Log.i(TAG, "${tier.displayName} already loaded — skipping")
        }
        return engine
    }

    /**
     * Loads the vision engine (2B + mmproj) if not already ready.
     * Sets [ReportGenerationState.LoadingModel] while loading.
     */
    private suspend fun loadVisionEngineIfNeeded() {
        if (vlmVisionEngine.isReady()) {
            Log.i(TAG, "Vision engine already loaded — skipping")
            return
        }
        reportGenerationState.value = ReportGenerationState.LoadingModel
        modelLoadingState.update { it.copy(qwenVision = ModelStatus.Loading, activeTierName = "Vision (2B)") }
        Log.i(TAG, "Loading vision engine (2B + mmproj)...")
        vlmVisionEngine.initialize(
            modelPath  = vlmModelManager.getVisionModelPath(),
            mmprojPath = vlmModelManager.getMmprojPath(),
        )
        modelLoadingState.update { it.copy(qwenVision = ModelStatus.WarmingUp) }
        vlmVisionEngine.warmUp()
        modelLoadingState.update { it.copy(qwenVision = ModelStatus.Ready) }
    }

    private fun getOrCreateTextEngine(tier: TextModelTier): LlamaTextEngine {
        val existing = _vlmTextEngine
        if (existing != null && existing.tier == tier) return existing
        existing?.release()
        modelLoadingState.update { it.copy(qwenText = ModelStatus.Standby, activeTierName = "") }
        return LlamaTextEngine(tier).also { _vlmTextEngine = it }
    }

    /** Expose best available tier for UI display. */
    fun getBestAvailableTier(): TextModelTier = bestAvailableTier()

    // -----------------------------------------------------------------------
    // Public API — launch report generation
    // -----------------------------------------------------------------------

    /**
     * Launch report generation in applicationScope so it survives Activity recreation.
     * The report is auto-saved to Room on completion.
     *
     * If the vision model (2B + mmproj) is available: runs two-phase pipeline —
     * visual verification of every image, then report generation with the same model.
     * Fallback: text-only report when vision model is not downloaded.
     */
    fun launchReportGeneration(sessions: List<DetectionSession>, language: String) {
        val currentState = reportGenerationState.value
        if (currentState is ReportGenerationState.Generating ||
            currentState is ReportGenerationState.LoadingModel ||
            currentState is ReportGenerationState.VerifyingDetections) return
        vlmReleaseJob?.cancel()
        startForegroundReportService()
        applicationScope.launch {
            val audience = ReportAudience.fromKey(settingsRepository.getReportAudienceSync())
            try {
                if (vlmModelManager.isVisionModelAvailable()) {
                    // ── Two-phase: verify images + generate report with same 2B model ─
                    releaseTextEngineNow()
                    loadVisionEngineIfNeeded()

                    val candidateCount = sessions.size
                    reportGenerationState.value = ReportGenerationState.VerifyingDetections(0, candidateCount)
                    val generator = ReportGenerator(vlmVisionEngine, imagePreprocessor)
                    val verifications = generator.verifyDetections(
                        sessions     = sessions,
                        visionEngine = vlmVisionEngine,
                        maxImages    = candidateCount,
                        onProgress   = { verified, total ->
                            reportGenerationState.value = ReportGenerationState.VerifyingDetections(verified, total)
                        },
                    )
                    reportGenerationState.value = ReportGenerationState.Generating
                    val tracker = GenerationTracker(maxTokens = 6144)
                    val reportText = generator.generateVerifiedReportStreaming(
                        sessions      = sessions,
                        verifications = verifications,
                        language      = language,
                        audience      = audience,
                    ) { partial ->
                        reportGenerationState.value = tracker.snapshot(partial)
                    }
                    val sessionIdsCsv = sessions.joinToString(",") { it.id.toString() }
                    val report = GeneratedReport(
                        text         = reportText,
                        language     = language,
                        sessionCount = sessions.size,
                        usedAi       = true,
                        audience     = audience.name.lowercase(),
                        sessionIds   = sessionIdsCsv,
                    )
                    val id = database.generatedReportDao().insert(report)
                    validateAndPersist(id, reportText, sessions, language)
                    reportGenerationState.value = ReportGenerationState.Complete(report.copy(id = id))
                    Log.i(TAG, "Verified report saved (id=$id, verifications=${verifications.size})")
                } else {
                    // ── Fallback: text-only report ----
                    val engine = loadTextEngineIfNeeded()
                    reportGenerationState.value = ReportGenerationState.Generating
                    val tracker2 = GenerationTracker(maxTokens = 6144)
                    val generator = ReportGenerator(engine)
                    val reportText = generator.generateReportStreaming(sessions, language, audience) { partial ->
                        reportGenerationState.value = tracker2.snapshot(partial)
                    }
                    val sessionIdsCsv2 = sessions.joinToString(",") { it.id.toString() }
                    val report = GeneratedReport(
                        text         = reportText,
                        language     = language,
                        sessionCount = sessions.size,
                        usedAi       = true,
                        audience     = audience.name.lowercase(),
                        sessionIds   = sessionIdsCsv2,
                    )
                    val id = database.generatedReportDao().insert(report)
                    validateAndPersist(id, reportText, sessions, language)
                    reportGenerationState.value = ReportGenerationState.Complete(report.copy(id = id))
                    Log.i(TAG, "Text-only report saved (id=$id)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Report generation failed", e)
                modelLoadingState.update { it.copy(qwenVision = ModelStatus.Error, qwenText = ModelStatus.Error) }
                reportGenerationState.value = ReportGenerationState.Error(e.message ?: "Unknown error")
            } finally {
                scheduleVlmRelease()
            }
        }
    }

    /**
     * Launch zone-based report generation.
     * Delegates to [launchVerifiedZoneReportGeneration] when the vision model is available;
     * falls back to text-only when it is not.
     */
    fun launchZoneReportGeneration(input: ZoneReportInput, language: String) {
        if (vlmModelManager.isVisionModelAvailable()) {
            launchVerifiedZoneReportGeneration(input, language)
            return
        }
        val currentState = reportGenerationState.value
        if (currentState is ReportGenerationState.Generating ||
            currentState is ReportGenerationState.LoadingModel) return
        vlmReleaseJob?.cancel()
        startForegroundReportService()
        applicationScope.launch {
            val audience = ReportAudience.fromKey(settingsRepository.getReportAudienceSync())
            try {
                val engine = loadTextEngineIfNeeded()
                reportGenerationState.value = ReportGenerationState.Generating
                val tracker = GenerationTracker(maxTokens = 6144)
                val generator = ReportGenerator(engine)
                val reportText = generator.generateZoneReportStreaming(input, language, audience) { partial ->
                    reportGenerationState.value = tracker.snapshot(partial)
                }
                val zoneSessionIds = input.sessions.joinToString(",") { it.id.toString() }
                val report = GeneratedReport(
                    text             = reportText,
                    language         = language,
                    sessionCount     = input.sessions.size,
                    usedAi           = true,
                    locationName     = input.locationName,
                    centroidLat      = input.centroidLat,
                    centroidLon      = input.centroidLon,
                    dateRangeStartMs = input.dateRangeStartMs,
                    dateRangeEndMs   = input.dateRangeEndMs,
                    audience         = audience.name.lowercase(),
                    sessionIds       = zoneSessionIds,
                )
                val id = database.generatedReportDao().insert(report)
                validateAndPersist(id, reportText, input.sessions, language)
                reportGenerationState.value = ReportGenerationState.Complete(report.copy(id = id))
                Log.i(TAG, "Zone report saved (id=$id)")
            } catch (e: Exception) {
                Log.e(TAG, "Zone report generation failed", e)
                modelLoadingState.update { it.copy(qwenText = ModelStatus.Error) }
                reportGenerationState.value = ReportGenerationState.Error(e.message ?: "Unknown error")
            } finally {
                scheduleVlmRelease()
            }
        }
    }

    /**
     * Two-phase verified zone report using a single model (Qwen3.5-2B Vision):
     *
     * **Phase 1 — Visual audit**
     * Loads the vision model once and verifies detections in ALL filtered sessions
     * by looking at each field photograph. Produces [VlmVerificationResult] per session
     * including confirmed classes, false positives, material inconsistencies, and site context.
     *
     * **Phase 2 — Report generation (same model, text mode)**
     * Uses the already-loaded vision model in text-only mode to generate the full zone report,
     * enriched with the per-session verification data. No model reload between phases.
     * Peak RAM: ~1.6 GB (2B GGUF + mmproj). Safe on S22 Ultra.
     */
    fun launchVerifiedZoneReportGeneration(input: ZoneReportInput, language: String) {
        val currentState = reportGenerationState.value
        if (currentState is ReportGenerationState.Generating ||
            currentState is ReportGenerationState.LoadingModel ||
            currentState is ReportGenerationState.VerifyingDetections) return
        vlmReleaseJob?.cancel()
        startForegroundReportService()
        applicationScope.launch {
            val audience = ReportAudience.fromKey(settingsRepository.getReportAudienceSync())
            try {
                releaseTextEngineNow()
                loadVisionEngineIfNeeded()

                // ── Phase 1: Visual verification (ALL sessions) ──────────────
                val candidateCount = input.sessions.size
                reportGenerationState.value = ReportGenerationState.VerifyingDetections(0, candidateCount)

                val generator = ReportGenerator(vlmVisionEngine, imagePreprocessor)
                val verifications = generator.verifyDetections(
                    sessions     = input.sessions,
                    visionEngine = vlmVisionEngine,
                    maxImages    = candidateCount,
                    onProgress   = { verified, total ->
                        reportGenerationState.value = ReportGenerationState.VerifyingDetections(verified, total)
                    },
                )
                Log.i(TAG, "Verification complete: ${verifications.size}/${candidateCount} sessions verified")

                // ── Phase 2: Report generation (same 2B model, text mode) ----
                reportGenerationState.value = ReportGenerationState.Generating
                val tracker = GenerationTracker(maxTokens = 6144)
                val reportText = generator.generateVerifiedZoneReportStreaming(
                    input         = input,
                    verifications = verifications,
                    language      = language,
                    audience      = audience,
                ) { partial ->
                    reportGenerationState.value = tracker.snapshot(partial)
                }

                val verifiedZoneSessionIds = input.sessions.joinToString(",") { it.id.toString() }
                val report = GeneratedReport(
                    text             = reportText,
                    language         = language,
                    sessionCount     = input.sessions.size,
                    usedAi           = true,
                    locationName     = input.locationName,
                    centroidLat      = input.centroidLat,
                    centroidLon      = input.centroidLon,
                    dateRangeStartMs = input.dateRangeStartMs,
                    dateRangeEndMs   = input.dateRangeEndMs,
                    audience         = audience.name.lowercase(),
                    sessionIds       = verifiedZoneSessionIds,
                )
                val id = database.generatedReportDao().insert(report)
                validateAndPersist(id, reportText, input.sessions, language)
                reportGenerationState.value = ReportGenerationState.Complete(report.copy(id = id))
                Log.i(TAG, "Verified zone report saved (id=$id, verifications=${verifications.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Verified zone report generation failed", e)
                modelLoadingState.update { it.copy(qwenVision = ModelStatus.Error) }
                reportGenerationState.value = ReportGenerationState.Error(e.message ?: "Unknown error")
            } finally {
                scheduleVlmRelease()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Post-generation validation
    // -----------------------------------------------------------------------

    /**
     * Validates the generated report against source session data and persists
     * the validation score + details alongside the report in Room.
     * Runs on IO — safe to call from any coroutine scope.
     */
    private suspend fun validateAndPersist(
        reportId: Long,
        reportText: String,
        sessions: List<DetectionSession>,
        language: String = "en",
    ) {
        try {
            val validation = ReportValidator.validate(reportText, sessions, language)
            val confidence = ReportValidator.computeConfidenceScore(validation, sessions)
            database.generatedReportDao().updateValidation(
                id = reportId,
                score = confidence,
                details = validation.toJsonString(),
            )
            Log.i(TAG, "Report $reportId validated: confidence=$confidence, ${validation.toSummaryString()}")
        } catch (e: Exception) {
            Log.w(TAG, "Report validation failed for id=$reportId", e)
        }
    }

    // -----------------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------------

    /** Launch text model download for [tier]. Default is the user's selected tier. */
    fun launchVlmDownload(tier: TextModelTier = TextModelTier.FAST) {
        startForegroundDownloadService()
        applicationScope.launch {
            try {
                vlmModelManager.downloadModel(tier)
            } catch (e: Exception) {
                Log.e(TAG, "VLM download failed (tier=${tier.displayName})", e)
            }
        }
    }

    fun launchVisionModelDownload() {
        startForegroundDownloadService()
        applicationScope.launch {
            try {
                vlmModelManager.downloadVisionModel()
            } catch (e: Exception) {
                Log.e(TAG, "Vision model download failed", e)
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

    // -----------------------------------------------------------------------
    // VLM lifecycle
    // -----------------------------------------------------------------------

    /**
     * Schedule VLM release after [VLM_RETAIN_MS]. Cancels on new generation
     * to allow model reuse across consecutive reports (saves 15–45s reload time).
     */
    private fun scheduleVlmRelease() {
        vlmReleaseJob?.cancel()
        vlmReleaseJob = applicationScope.launch {
            delay(VLM_RETAIN_MS)
            releaseAllVlmNow()
        }
    }

    private fun releaseTextEngineNow() {
        vlmReleaseJob?.cancel()
        val engine = _vlmTextEngine
        if (engine?.isReady() == true) {
            engine.release()
            Log.i(TAG, "Text engine force-released")
        }
        modelLoadingState.update { it.copy(qwenText = ModelStatus.Standby, activeTierName = "") }
    }

    private fun releaseAllVlmNow() {
        val engine = _vlmTextEngine
        if (engine?.isReady() == true) {
            engine.release()
            Log.i(TAG, "Text VLM auto-released after ${VLM_RETAIN_MS / 1000}s idle")
        }
        val vision = _vlmVisionEngine
        if (vision?.isReady() == true) {
            vision.release()
            Log.i(TAG, "Vision VLM auto-released after ${VLM_RETAIN_MS / 1000}s idle")
        }
        _vlmVisionEngine = null // Allow recreation with updated provider on next access
        modelLoadingState.update {
            it.copy(qwenText = ModelStatus.Standby, qwenVision = ModelStatus.Standby, activeTierName = "")
        }
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
        Log.w(TAG, "Low memory warning — releasing VLMs")
        releaseAllVlmNow()
        System.gc()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL, TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure (level: $level) — releasing VLMs")
                releaseAllVlmNow()
                System.gc()
            }
            TRIM_MEMORY_MODERATE, TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.i(TAG, "Moderate memory pressure (level: $level) — scheduling early VLM release")
                scheduleVlmRelease()
            }
        }
    }
}
