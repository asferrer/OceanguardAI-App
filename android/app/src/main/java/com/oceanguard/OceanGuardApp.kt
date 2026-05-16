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
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.inference.Gemma4VisionDetector
import com.oceanguard.ai.inference.LlamaTextEngine
import com.oceanguard.ai.inference.LlamaVisionEngine
import com.oceanguard.ai.inference.ObjectDetector
import com.oceanguard.ai.inference.RTDETRInference
import com.oceanguard.ai.inference.ReportAudience
import com.oceanguard.ai.inference.ReportGenerator
import com.oceanguard.ai.inference.ToolReportGenerator
import com.oceanguard.ai.inference.ReportValidator
import com.oceanguard.ai.inference.Gemma4PromptFormatter
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.inference.VlmProvider
import com.oceanguard.ai.inference.VideoProcessor
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.inference.VlmTextEngine
import com.oceanguard.ai.inference.LiteRTTextEngine
import com.oceanguard.ai.inference.ZoneReportInput
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.service.ReportGenerationService
import com.oceanguard.ai.service.VlmDownloadService
import com.oceanguard.ai.data.contribution.ContributionQueueDao
import com.oceanguard.ai.data.contribution.ContributionRepository
import com.oceanguard.ai.utils.ImagePreprocessor
import com.oceanguard.ai.utils.LocationProvider
import com.oceanguard.ai.utils.PhotonGeocoderClient
import com.oceanguard.ai.utils.ModelUpdateChecker
import com.oceanguard.ai.utils.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Model loading state (shared with UI)
// ---------------------------------------------------------------------------

enum class ModelStatus { NotLoaded, Loading, WarmingUp, Ready, Error, Standby }

data class ModelLoadingState(
    val rtdetr: ModelStatus = ModelStatus.NotLoaded,
    val gemma4Vision: ModelStatus = ModelStatus.Standby, // Gemma 4 vision detector
    val qwenText: ModelStatus = ModelStatus.Standby,     // text reports (1.5B or 3B)
    val qwenVision: ModelStatus = ModelStatus.Standby,   // vision analysis (2B + mmproj)
    val activeTierName: String = "",                      // e.g. "Fast (1.5B)"
) {
    /** Detection is ready when at least one detector is loaded. */
    val allReady: Boolean get() = rtdetr == ModelStatus.Ready || gemma4Vision == ModelStatus.Ready
}

// ---------------------------------------------------------------------------
// Report generation state (shared with UI, survives Activity recreation)
// ---------------------------------------------------------------------------

sealed class ReportGenerationState {
    data object Idle : ReportGenerationState()
    data object LoadingModel : ReportGenerationState()
    data object Generating : ReportGenerationState()
    /**
     * The model has issued a tool call and we are about to run the Kotlin
     * function backing it. Lets the UI announce "Querying debris summary…"
     * during the otherwise-silent PHASE 1 of the agentic loop.
     */
    data class ToolExecuting(
        val toolName: String,
        val sequence: Int,
    ) : ReportGenerationState()
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

        /**
         * Keep VLM loaded for 30 minutes after last use so consecutive reports
         * within the same diving session don't pay the 5-30 s reload cost.
         * `onTrimMemory` / `onLowMemory` still release the engine eagerly if
         * the OS reports real memory pressure, so the long retain is bounded
         * by available RAM rather than by a fixed timer.
         */
        private const val VLM_RETAIN_MS = 30L * 60L * 1000L

        // REPORT_TIER is now dynamic — see bestAvailableTier()
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var vlmReleaseJob: Job? = null

    // Inference engines
    lateinit var rtdetrInference: RTDETRInference
        private set
    lateinit var detectionOrchestrator: DetectionOrchestrator
        private set

    // Shared LiteRT-LM engine for Gemma 4 — reused by both detector and report generation.
    // Avoids loading two ~2.6 GB copies of the same model into RAM.
    val sharedLiteRTEngine: LiteRTTextEngine by lazy {
        val cacheDir = java.io.File(vlmModelManager.getModelDirectory(), "litert-cache")
            .apply { mkdirs() }.absolutePath
        LiteRTTextEngine(cacheDir)
    }

    // Gemma 4 Vision detector — uses the shared LiteRTTextEngine for object detection via box_2d
    val gemma4Detector: Gemma4VisionDetector by lazy {
        Gemma4VisionDetector(sharedLiteRTEngine)
    }

    /**
     * Returns the active detector for **single-shot deep analysis**, based on user settings.
     * "rtdetr" → RT-DETRv2 (~30 ms, fast), "gemma4" → Gemma 4 Vision (~4-10 s, deep).
     *
     * When the user selects Gemma 4 but the model is not downloaded yet, this falls
     * back to RT-DETRv2 so the camera capture pipeline keeps working — the canonical
     * taxonomy is the same on both paths.
     *
     * **DO NOT use this in real-time loops** (live camera, video processing). Gemma 4
     * Vision collapses those to ~0.05 FPS on Exynos 2200. Use [rtdetrInference] directly.
     */
    fun getActiveDetector(): ObjectDetector {
        return when (settingsRepository.getDetectorModeSync()) {
            DetectorType.GEMMA4_VISION.key -> {
                val variant = activeVariant()
                val available = vlmModelManager.isModelAvailable(TextModelTier.GEMMA4_E2B, variant)
                    || vlmModelManager.isModelAvailable(TextModelTier.GEMMA4_E2B, TextModelVariant.BASE)
                if (available) {
                    gemma4Detector
                } else {
                    Log.w(TAG, "Gemma 4 Vision selected but not downloaded — falling back to RT-DETRv2")
                    rtdetrInference
                }
            }
            else -> rtdetrInference
        }
    }

    /**
     * Switches the orchestrator's detector to match the current setting.
     * Call after the user changes the detector mode in Settings.
     */
    fun applyDetectorMode() {
        val active = getActiveDetector()
        detectionOrchestrator.detector = active
        Log.i(TAG, "Detector switched to: ${active.displayName}")
    }

    // Text engine — one at a time, tier-aware. LlamaTextEngine for Qwen, LiteRTTextEngine for Gemma 4.
    @Volatile private var _vlmTextEngine: VlmTextEngine? = null
    @Volatile private var _vlmTextEngineTier: TextModelTier? = null

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

    val modelUpdateChecker: ModelUpdateChecker by lazy { ModelUpdateChecker(settingsRepository) }

    val vlmModelManager: VlmModelManager by lazy { VlmModelManager(this, settingsRepository) }

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

    // -----------------------------------------------------------------------
    // Volume-key shutter trigger
    // -----------------------------------------------------------------------
    //
    // CameraScreen toggles [consumeVolumeKeysForCapture] while it is mounted
    // so MainActivity.dispatchKeyEvent knows to intercept VOLUME_UP/DOWN as a
    // shutter trigger instead of letting the system change the audio volume.
    // When the flag is on, each KEY_DOWN emits an event on [volumeShutterRequests]
    // which CameraScreen collects to fire a capture identical to tapping the
    // on-screen shutter.
    private val _volumeShutterRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val volumeShutterRequests: SharedFlow<Unit> = _volumeShutterRequests.asSharedFlow()

    @Volatile
    var consumeVolumeKeysForCapture: Boolean = false

    fun requestVolumeShutter() {
        _volumeShutterRequests.tryEmit(Unit)
    }

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
        detectionOrchestrator = DetectionOrchestrator(this, getActiveDetector())

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

        // Preload the text model in the background so the first "Generate
        // report" tap doesn't pay a cold-start (5-30 s) and the user never sees
        // the "Loading model…" banner for an already-installed model. Runs at
        // low OS thread priority so it doesn't compete with the foreground UI
        // or the RT-DETR warm-up that's already in flight.
        applicationScope.launch {
            try {
                // Let the RT-DETR loader settle first so the two big native
                // initialisations don't fight for IO at the same moment.
                delay(1_500L)
                val tier = bestAvailableTier()
                if (!isTextModelAvailable(tier)) {
                    Log.i(TAG, "Text VLM preload skipped — model not downloaded (tier=${tier.displayName})")
                    return@launch
                }
                val tid = android.os.Process.myTid()
                val originalPriority = runCatching { android.os.Process.getThreadPriority(tid) }
                    .getOrDefault(android.os.Process.THREAD_PRIORITY_DEFAULT)
                runCatching {
                    android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)
                }
                try {
                    Log.i(TAG, "Preloading text VLM in background: ${tier.displayName}")
                    val startMs = System.currentTimeMillis()
                    // silentPreload = true so the report UI doesn't briefly
                    // flash "Loading model…" at app start.
                    loadTextEngineIfNeeded(forceTier = tier, silentPreload = true)
                    Log.i(TAG, "Text VLM preload complete in ${System.currentTimeMillis() - startMs}ms")
                } finally {
                    runCatching { android.os.Process.setThreadPriority(tid, originalPriority) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Text VLM preload failed (non-fatal): ${e.message}", e)
            }
        }

        // If Gemma 4 Vision is the selected detector, try to initialize it
        if (settingsRepository.getDetectorModeSync() == DetectorType.GEMMA4_VISION.key) {
            applicationScope.launch {
                initializeGemma4DetectorIfAvailable()
            }
        }
    }

    /**
     * Pre-warms the Gemma 4 vision detector in the background when:
     *  - it is the user-selected detector, AND
     *  - the model file is downloaded, AND
     *  - it is not already loading / loaded.
     *
     * Idempotent and non-blocking. Designed to be called from
     * `LaunchedEffect(Unit)` inside CameraScreen so the first capture does not
     * pay the 5-10 s cold-start cost while the user is already framing a shot
     * underwater. Safe to invoke even when none of the conditions hold — it
     * silently returns.
     */
    fun prewarmGemma4DetectorIfAvailable() {
        if (settingsRepository.getDetectorModeSync() != DetectorType.GEMMA4_VISION.key) return
        val currentStatus = modelLoadingState.value.gemma4Vision
        if (currentStatus != ModelStatus.NotLoaded && currentStatus != ModelStatus.Standby) return
        val g4Variant = TextModelTier.GEMMA4_E2B.variantFallback(activeVariant())
        if (!vlmModelManager.isModelAvailable(TextModelTier.GEMMA4_E2B, g4Variant)
            && !vlmModelManager.isModelAvailable(TextModelTier.GEMMA4_E2B, TextModelVariant.BASE)) return
        Log.i(TAG, "Pre-warming Gemma 4 vision detector in background")
        applicationScope.launch { initializeGemma4DetectorIfAvailable() }
    }

    /**
     * Initializes the Gemma 4 vision detector if the model is downloaded.
     * Does not block -- emits state updates via [modelLoadingState].
     */
    suspend fun initializeGemma4DetectorIfAvailable() {
        if (gemma4Detector.isReady()) {
            modelLoadingState.update { it.copy(gemma4Vision = ModelStatus.Ready) }
            return
        }
        val tier = TextModelTier.GEMMA4_E2B
        val variant = tier.variantFallback(activeVariant())
        // Accept finetuned if present, fall back to BASE automatically
        val effectiveVariant = if (vlmModelManager.isModelAvailable(tier, variant)) variant
                               else TextModelVariant.BASE
        if (!vlmModelManager.isModelAvailable(tier, effectiveVariant)) {
            Log.i(TAG, "Gemma 4 E2B model not downloaded -- detector not available")
            modelLoadingState.update { it.copy(gemma4Vision = ModelStatus.NotLoaded) }
            return
        }
        try {
            modelLoadingState.update { it.copy(gemma4Vision = ModelStatus.Loading) }
            val modelPath = vlmModelManager.getTextModelPath(tier, effectiveVariant)
            Log.i(TAG, "Loading Gemma 4 vision detector: $modelPath")
            gemma4Detector.textEngine.initialize(modelPath)
            modelLoadingState.update { it.copy(gemma4Vision = ModelStatus.WarmingUp) }
            gemma4Detector.warmUp()
            modelLoadingState.update { it.copy(gemma4Vision = ModelStatus.Ready) }
            applyDetectorMode()
            Log.i(TAG, "Gemma 4 vision detector ready")
        } catch (e: Exception) {
            Log.e(TAG, "Gemma 4 vision detector init failed", e)
            modelLoadingState.update { it.copy(gemma4Vision = ModelStatus.Error) }
        }
    }

    // -----------------------------------------------------------------------
    // Text engine management (always Quality 3B)
    // -----------------------------------------------------------------------

    /**
     * Best available text tier respecting user preference and selected provider.
     * Tries the user-selected tier first; falls back to highest available quality
     * within the same provider family.
     */
    /** Check if a tier has a usable model on disk for the active variant. */
    private fun isTextModelAvailable(
        tier: TextModelTier,
        variant: TextModelVariant = activeVariant(),
    ): Boolean = vlmModelManager.isModelAvailable(tier, variant)

    /** Returns the variant currently selected by the user. */
    private fun activeVariant(): TextModelVariant =
        TextModelVariant.fromKey(settingsRepository.getVlmModelVariantSync())

    private fun bestAvailableTier(): TextModelTier {
        val provider = VlmProvider.fromKey(settingsRepository.getVlmProviderSync())
        val preferred = TextModelTier.fromKey(settingsRepository.getVlmModelTierSync())
        val variant = activeVariant()

        // If the preferred tier matches the selected provider and is available, use it
        // For FINETUNED: try preferred+finetuned, fall back to preferred+BASE if finetuned absent
        if (preferred.provider == provider) {
            if (isTextModelAvailable(preferred, variant)) return preferred
            // Variant-specific fallback: FINETUNED not present → try BASE same tier
            if (variant == TextModelVariant.FINETUNED && isTextModelAvailable(preferred, TextModelVariant.BASE)) {
                Log.w(TAG, "FINETUNED variant not downloaded — falling back to BASE (tier=${preferred.displayName})")
                return preferred
            }
        }

        // Fall back to the best available tier within the selected provider (BASE only for fallback)
        val providerTiers = TextModelTier.forProvider(provider)
        val available = providerTiers.lastOrNull { isTextModelAvailable(it, TextModelVariant.BASE) }
        if (available != null) return available

        // Ultimate fallback: Gemma 4 (LiteRT-LM, default) first, then Qwen by size.
        return when {
            vlmModelManager.isModelAvailable(TextModelTier.GEMMA4_E2B) -> TextModelTier.GEMMA4_E2B
            vlmModelManager.isModelAvailable(TextModelTier.QUALITY)    -> TextModelTier.QUALITY
            vlmModelManager.isModelAvailable(TextModelTier.BALANCED)   -> TextModelTier.BALANCED
            else -> TextModelTier.FAST
        }
    }

    /**
     * Loads the best available text tier (or the one passed in [forceTier]) if
     * not already ready. Sets [ReportGenerationState.LoadingModel] while
     * loading, unless [silentPreload] is true (used by the background preload
     * at app start, which must not show the "Loading model…" UI banner).
     * Must be called from a coroutine inside [applicationScope].
     */
    private suspend fun loadTextEngineIfNeeded(
        forceTier: TextModelTier? = null,
        silentPreload: Boolean = false,
    ): VlmTextEngine {
        val tier = forceTier ?: bestAvailableTier()
        val existing = _vlmTextEngine
        val engine = if (existing != null && _vlmTextEngineTier == tier) existing
                     else {
                         // Don't release the shared LiteRT engine if it's backing the active detector
                         if (existing != null && !(existing === sharedLiteRTEngine && isSharedEngineUsedByDetector())) {
                             existing.release()
                         }
                         createTextEngine(tier).also {
                             _vlmTextEngine = it
                             _vlmTextEngineTier = tier
                         }
                     }
        if (!engine.isReady()) {
            if (!silentPreload) {
                reportGenerationState.value = ReportGenerationState.LoadingModel
            }
            modelLoadingState.update {
                it.copy(qwenText = ModelStatus.Loading, activeTierName = tier.displayName)
            }
            // Both GGUF and .litertlm files live at `tier.filename` (or variant filename) inside models dir.
            val resolvedVariant = tier.variantFallback(activeVariant())
            val modelPath = vlmModelManager.getTextModelPath(tier, resolvedVariant)
            Log.i(TAG, "Loading ${engine.displayName} ${if (silentPreload) "(preload)" else "on demand"}...")
            engine.initialize(modelPath)
            modelLoadingState.update { it.copy(qwenText = ModelStatus.WarmingUp) }
            engine.warmUp()
            modelLoadingState.update { it.copy(qwenText = ModelStatus.Ready) }
        } else {
            Log.i(TAG, "${engine.displayName} already loaded — skipping")
        }
        return engine
    }

    /**
     * Build the ordered list of tiers to try for a report. First the best
     * available (matching the user's preference), then the smaller fallback
     * if it is distinct AND downloaded. Used by both single-session and zone
     * report generation to recover from OOM / timeout / init crash without
     * burdening the user with a manual retry.
     */
    private fun reportRetryTiers(): List<TextModelTier> {
        val primary = bestAvailableTier()
        val fallback = primary.smallerFallback()
        return if (fallback != primary && vlmModelManager.isModelAvailable(fallback)) {
            listOf(primary, fallback)
        } else {
            listOf(primary)
        }
    }

    /** Create the right engine implementation based on the tier's backend. */
    private fun createTextEngine(tier: TextModelTier): VlmTextEngine =
        if (tier.provider.usesLiteRT) {
            Log.i(TAG, "Reusing shared LiteRT-LM engine for ${tier.displayName}")
            sharedLiteRTEngine
        } else {
            Log.i(TAG, "Using llama.cpp backend for ${tier.displayName}")
            LlamaTextEngine(tier)
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

    private fun getOrCreateTextEngine(tier: TextModelTier): VlmTextEngine {
        val existing = _vlmTextEngine
        if (existing != null && _vlmTextEngineTier == tier) return existing
        if (existing != null && !(existing === sharedLiteRTEngine && isSharedEngineUsedByDetector())) {
            existing.release()
        }
        modelLoadingState.update { it.copy(qwenText = ModelStatus.Standby, activeTierName = "") }
        return createTextEngine(tier).also {
            _vlmTextEngine = it
            _vlmTextEngineTier = tier
        }
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
            currentState is ReportGenerationState.LoadingModel) return
        vlmReleaseJob?.cancel()
        startForegroundReportService()
        applicationScope.launch {
            val audience = ReportAudience.fromKey(settingsRepository.getReportAudienceSync())
            val tiers = reportRetryTiers()
            var lastError: Throwable? = null
            for ((attempt, tier) in tiers.withIndex()) {
                try {
                    val engine = loadTextEngineIfNeeded(forceTier = tier)
                    reportGenerationState.value = ReportGenerationState.Generating
                    val tracker = GenerationTracker(maxTokens = 6144)
                    val toolCounter = java.util.concurrent.atomic.AtomicInteger(0)
                    val reportText = if (engine is LiteRTTextEngine) {
                        Log.i(TAG, "Using tool-calling report path (LiteRT-LM / Gemma 4) tier=${tier.displayName}")
                        ToolReportGenerator(engine).generateReportWithToolsStreaming(
                            sessions = sessions,
                            language = language,
                            audience = audience,
                            onPartialResult = { partial -> reportGenerationState.value = tracker.snapshot(partial) },
                            onToolCallStarted = { name ->
                                val seq = toolCounter.incrementAndGet()
                                Log.d(TAG, "Tool call: $name (#$seq)")
                                // Surface "Querying <tool>…" to the UI so the user sees
                                // progress during the agentic PHASE-1, before report
                                // tokens start streaming.
                                reportGenerationState.value =
                                    ReportGenerationState.ToolExecuting(name, seq)
                            },
                        )
                    } else {
                        val generator = ReportGenerator(engine)
                        generator.generateReportStreaming(sessions, language, audience) { partial ->
                            reportGenerationState.value = tracker.snapshot(partial)
                        }
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
                    Log.i(TAG, "Report saved (id=$id, tier=${tier.displayName}, attempt=${attempt + 1})")
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Report attempt ${attempt + 1}/${tiers.size} with ${tier.displayName} failed: ${e.message}", e)
                    if (attempt < tiers.lastIndex) {
                        // Backoff before falling back to the smaller tier so any
                        // partial state from the failed engine clears cleanly.
                        delay(1_500L * (attempt + 1))
                    }
                }
            }
            if (lastError != null) {
                Log.e(TAG, "Report generation failed after ${tiers.size} attempt(s)", lastError)
                modelLoadingState.update { it.copy(qwenText = ModelStatus.Error) }
                reportGenerationState.value = ReportGenerationState.Error(lastError.message ?: "Unknown error")
            }
            scheduleVlmRelease()
        }
    }

    /**
     * Launch zone-based report generation (text-only, no image verification).
     */
    fun launchZoneReportGeneration(input: ZoneReportInput, language: String) {
        val currentState = reportGenerationState.value
        if (currentState is ReportGenerationState.Generating ||
            currentState is ReportGenerationState.LoadingModel) return
        vlmReleaseJob?.cancel()
        startForegroundReportService()
        applicationScope.launch {
            val audience = ReportAudience.fromKey(settingsRepository.getReportAudienceSync())
            val tiers = reportRetryTiers()
            var lastError: Throwable? = null
            for ((attempt, tier) in tiers.withIndex()) {
                try {
                    val engine = loadTextEngineIfNeeded(forceTier = tier)
                    reportGenerationState.value = ReportGenerationState.Generating
                    val tracker = GenerationTracker(maxTokens = 6144)
                    val toolCounter = java.util.concurrent.atomic.AtomicInteger(0)
                    val reportText = if (engine is LiteRTTextEngine) {
                        Log.i(TAG, "Using tool-calling zone report path (LiteRT-LM / Gemma 4) tier=${tier.displayName}")
                        ToolReportGenerator(engine).generateZoneReportWithToolsStreaming(
                            input = input,
                            language = language,
                            audience = audience,
                            onPartialResult = { partial -> reportGenerationState.value = tracker.snapshot(partial) },
                            onToolCallStarted = { name ->
                                val seq = toolCounter.incrementAndGet()
                                Log.d(TAG, "Tool call: $name (#$seq)")
                                reportGenerationState.value =
                                    ReportGenerationState.ToolExecuting(name, seq)
                            },
                        )
                    } else {
                        val generator = ReportGenerator(engine)
                        generator.generateZoneReportStreaming(input, language, audience) { partial ->
                            reportGenerationState.value = tracker.snapshot(partial)
                        }
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
                    Log.i(TAG, "Zone report saved (id=$id, tier=${tier.displayName}, attempt=${attempt + 1})")
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Zone report attempt ${attempt + 1}/${tiers.size} with ${tier.displayName} failed: ${e.message}", e)
                    if (attempt < tiers.lastIndex) {
                        delay(1_500L * (attempt + 1))
                    }
                }
            }
            if (lastError != null) {
                Log.e(TAG, "Zone report generation failed after ${tiers.size} attempt(s)", lastError)
                modelLoadingState.update { it.copy(qwenText = ModelStatus.Error) }
                reportGenerationState.value = ReportGenerationState.Error(lastError.message ?: "Unknown error")
            }
            scheduleVlmRelease()
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
            if (validation.failCount > 0 || confidence < 70) {
                Log.w(TAG, "Report $reportId LOW QUALITY: confidence=$confidence, ${validation.toSummaryString()} -- review prompt/tool enforcement")
            } else {
                Log.i(TAG, "Report $reportId validated: confidence=$confidence, ${validation.toSummaryString()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Report validation failed for id=$reportId", e)
        }
    }

    // -----------------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------------

    /** Launch text model download for [tier] and [variant]. */
    fun launchVlmDownload(
        tier: TextModelTier = TextModelTier.FAST,
        variant: TextModelVariant = TextModelVariant.BASE,
    ) {
        startForegroundDownloadService()
        applicationScope.launch {
            try {
                vlmModelManager.downloadModel(tier, variant)
                // After successful download, initialize Gemma 4 if it is the active detector
                if (tier == TextModelTier.GEMMA4_E2B &&
                    settingsRepository.getDetectorModeSync() == DetectorType.GEMMA4_VISION.key
                ) {
                    initializeGemma4DetectorIfAvailable()
                }
            } catch (e: Exception) {
                Log.e(TAG, "VLM download failed (tier=${tier.displayName}, variant=${variant.key})", e)
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

    /** True when the shared LiteRT engine is backing the active detector -- must not be released. */
    private fun isSharedEngineUsedByDetector(): Boolean =
        settingsRepository.getDetectorModeSync() == DetectorType.GEMMA4_VISION.key &&
            sharedLiteRTEngine.isReady()

    private fun releaseAllVlmNow() {
        val engine = _vlmTextEngine
        if (engine?.isReady() == true) {
            if (engine === sharedLiteRTEngine && isSharedEngineUsedByDetector()) {
                Log.i(TAG, "Shared LiteRT engine kept alive (active detector)")
            } else {
                engine.release()
                Log.i(TAG, "Text VLM auto-released after ${VLM_RETAIN_MS / 1000}s idle")
            }
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
