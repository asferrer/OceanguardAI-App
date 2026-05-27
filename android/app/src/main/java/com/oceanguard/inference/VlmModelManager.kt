package com.oceanguard.ai.inference

import android.content.Context
import android.util.Log
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.model.BestModel
import com.oceanguard.ai.utils.ModelUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the VLM model lifecycle: availability checks and on-demand downloads
 * from HuggingFace (Apache 2.0, no auth required).
 *
 * Three tiers of models:
 * - **Fast text** (~1.0 GB): Qwen3.5-1.5B-Q4_K_M.gguf — snappy reports, ~15–20 tok/s
 * - **Quality text** (~1.9 GB): Qwen3.5-3B-Q4_K_M.gguf — better coherence, ~8–12 tok/s
 * - **Vision** (~1.6 GB total): Qwen3.5-2B-Q4_K_M.gguf + mmproj — image-aware reports
 *
 * Download state is exposed via [downloadState] for UI and [VlmDownloadService].
 */
class VlmModelManager(
    private val context: Context,
    private val settings: SettingsRepository? = null,
) {

    companion object {
        private const val TAG = "VlmModelManager"

        // HuggingFace direct-download base (Apache 2.0 models, no auth)
        private const val HF_BASE = "https://huggingface.co"

        // Text model URLs per tier (string literals — const val cannot reference enum properties)
        // Qwen3.5 family: 0.8B / 2B / 4B / 9B. No 1.5B or 3B variants exist.
        private const val TEXT_FAST_URL =
            "$HF_BASE/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
        private const val TEXT_BALANCED_URL =
            "$HF_BASE/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"
        private const val TEXT_QUALITY_URL =
            "$HF_BASE/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"

        // Gemma 4 E2B — Apache 2.0, LiteRT-LM (.litertlm) for on-device inference
        // GGUF variant removed: LiteRT-LM is 1.65x faster on Exynos 2200 CPU
        private const val GEMMA4_MMPROJ_URL =
            "$HF_BASE/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/mmproj-gemma-4-e2b-it-f16.gguf"

        // Vision model URLs — Qwen (2B + mmproj)
        private const val VISION_MODEL_URL =
            "$HF_BASE/unsloth/Qwen3.5-2B-GGUF/resolve/main/${LlamaVisionEngine.QWEN_MODEL_FILENAME}"
        private const val MMPROJ_URL =
            "$HF_BASE/unsloth/Qwen3.5-2B-GGUF/resolve/main/${LlamaVisionEngine.QWEN_MMPROJ_FILENAME}"

        // Gemma 4 filenames
        const val GEMMA4_MMPROJ_FILENAME = "mmproj-gemma-4-e2b-it-f16.gguf"
        private const val GEMMA4_LITERTLM_URL =
            "$HF_BASE/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val GEMMA4_LITERTLM_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val MIN_GEMMA4_LITERTLM_BYTES = 2_000_000_000L // 2 GB (~2.58 GB)

        // Gemma 4 — OceanGuard fine-tuned variant (LiteRT-LM, pending upstream converter support)
        const val GEMMA4_LITERTLM_FINETUNED_FILENAME = "gemma-4-E2B-it-oceanguard.litertlm"
        private const val GEMMA4_LITERTLM_FINETUNED_URL =
            "$HF_BASE/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/resolve/main/gemma-4-E2B-it-oceanguard.litertlm"

        // OceanGuard fine-tuned GGUF — exp12_vision_lora merged + Q4_K_M, served via llama.cpp
        // +205% mAP@0.5 vs base on the OceanGuard marine debris held-out eval (n=200).
        const val OCEANGUARD_GGUF_FILENAME = "gemma-4-E2B-it-oceanguard-Q4_K_M.gguf"
        private const val OCEANGUARD_GGUF_URL =
            "$HF_BASE/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/resolve/main/gemma-4-E2B-it-oceanguard-Q4_K_M.gguf"
        private const val MIN_OCEANGUARD_GGUF_BYTES = 3_000_000_000L // 3.0 GB minimum (actual 3.42 GB Q4_K_M, large 262k vocab)

        // --- BioDex species pack (M4) ---
        // TODO(M4 assets): create this HF repo and upload the exported encoder +
        // built index/catalog/raster + sprites. Until then the app uses the demo
        // SampleSpeciesData fallback (see OceanGuardApp.buildSpeciesIdentifier).
        private const val SPECIES_REPO = "$HF_BASE/asferrer/oceanguard-biodex/resolve/main"
        // filename -> minimum valid bytes (guards against HF HTML error pages).
        private val SPECIES_ASSETS = listOf(
            "clip_vitb32.onnx" to 40_000_000L,        // ~88 MB OpenCLIP ViT-B/32
            "species_index_v1.bin" to 1_000L,          // small (prototypes + trailer)
            "species_catalog_v1.json" to 100L,
            "meow_raster_v1.bin" to 1_000_000L,        // ~2 MB
            "ecoregion_hierarchy_v1.json" to 100L,
        )

        // Minimum valid file sizes (small files = error HTML pages from HF)
        private val MIN_TEXT_BYTES = mapOf(
            TextModelTier.FAST          to   400_000_000L, //  400 MB — Qwen3.5-0.8B (~533 MB)
            TextModelTier.BALANCED      to   900_000_000L, //  900 MB — Qwen3.5-2B  (~1.1 GB)
            TextModelTier.QUALITY       to 2_200_000_000L, // 2.2 GB — Qwen3.5-4B  (~2.74 GB)
            TextModelTier.GEMMA4_E2B    to MIN_GEMMA4_LITERTLM_BYTES, // 2 GB — Gemma 4 E2B (~2.58 GB)
            TextModelTier.OCEANGUARD_FT to MIN_OCEANGUARD_GGUF_BYTES, // 3.0 GB — Q4_K_M GGUF (actual 3.42 GB)
        )
        private const val MIN_VISION_MODEL_BYTES = 500_000_000L // 500 MB — 2B
        private const val MIN_MMPROJ_BYTES       =  50_000_000L //  50 MB — mmproj
        private const val MIN_GEMMA4_MMPROJ_BYTES = 500_000_000L // 500 MB — Gemma 4 mmproj (~986 MB)

        private const val BUFFER_SIZE        = 8 * 1024 * 1024 // 8 MB
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS    = 60_000
    }

    private val _downloadState = MutableStateFlow<VlmDownloadState>(VlmDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    @Volatile
    private var cancelled = false

    /** Latest manifest best-model entry. Populated by [refreshBestModelFromManifest]. */
    @Volatile
    private var cachedBestModel: BestModel? = null

    // -----------------------------------------------------------------------
    // Manifest-aware finetuned URL/filename helpers
    // -----------------------------------------------------------------------

    /**
     * Refreshes [cachedBestModel] from the network or DataStore-cached manifest.
     * Call once at startup (e.g. from [OceanGuardApp]) and again after a fresh manifest check.
     */
    suspend fun refreshBestModelFromManifest(
        checker: ModelUpdateChecker,
        force: Boolean = false,
    ): BestModel? {
        if (!force && cachedBestModel != null) return cachedBestModel
        val manifest = checker.fetchManifestCached() ?: return cachedBestModel
        cachedBestModel = manifest.bestModel
        return cachedBestModel
    }

    /** Dynamic finetuned filename from manifest, with hardcoded fallback for offline use. */
    fun finetunedFilename(): String =
        cachedBestModel?.filename ?: GEMMA4_LITERTLM_FINETUNED_FILENAME

    /** Dynamic finetuned download URL from manifest, with hardcoded fallback for offline use. */
    fun finetunedUrl(): String =
        cachedBestModel?.url ?: GEMMA4_LITERTLM_FINETUNED_URL

    /**
     * Latest known best-model version from the cached manifest (e.g. "1.0.0").
     * Returns null if no manifest has been fetched yet.
     */
    fun bestModelVersion(): String? = cachedBestModel?.version

    // -----------------------------------------------------------------------
    // File paths
    // -----------------------------------------------------------------------

    /** App-private external storage directory for all models. */
    fun getModelDirectory(): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    /** Absolute path to the text model file for [tier] and [variant]. */
    fun getTextModelPath(
        tier: TextModelTier = TextModelTier.FAST,
        variant: TextModelVariant = TextModelVariant.BASE,
    ): String {
        val effectiveVariant = tier.variantFallback(variant)
        val filename = if (tier == TextModelTier.GEMMA4_E2B && effectiveVariant == TextModelVariant.FINETUNED)
            finetunedFilename()
        else
            tier.filenameFor(effectiveVariant)
        return File(getModelDirectory(), filename).absolutePath
    }

    /** Absolute path to the Qwen vision LLM GGUF file. */
    fun getVisionModelPath(): String =
        File(getModelDirectory(), LlamaVisionEngine.QWEN_MODEL_FILENAME).absolutePath

    /** Absolute path to the Qwen CLIP mmproj GGUF file. */
    fun getMmprojPath(): String =
        File(getModelDirectory(), LlamaVisionEngine.QWEN_MMPROJ_FILENAME).absolutePath

    /** Absolute path to the Gemma 4 mmproj GGUF file. */
    fun getGemma4MmprojPath(): String =
        File(getModelDirectory(), GEMMA4_MMPROJ_FILENAME).absolutePath

    // -----------------------------------------------------------------------
    // Availability checks
    // -----------------------------------------------------------------------

    /** True if the text model for [tier] and [variant] is present and has a valid size. */
    fun isModelAvailable(
        tier: TextModelTier = TextModelTier.FAST,
        variant: TextModelVariant = TextModelVariant.BASE,
    ): Boolean {
        val effectiveVariant = tier.variantFallback(variant)
        val filename = if (tier == TextModelTier.GEMMA4_E2B && effectiveVariant == TextModelVariant.FINETUNED)
            finetunedFilename()
        else
            tier.filenameFor(effectiveVariant)
        val f = File(getModelDirectory(), filename)
        val minBytes = if (effectiveVariant == TextModelVariant.FINETUNED)
            MIN_GEMMA4_LITERTLM_BYTES
        else
            MIN_TEXT_BYTES[tier] ?: 50_000_000L
        return f.exists() && f.length() > minBytes
    }

    /** True if any text tier/variant is downloaded (fast check for report button). */
    fun isAnyTextModelAvailable(): Boolean =
        TextModelTier.entries.any { tier ->
            TextModelVariant.entries.any { variant -> isModelAvailable(tier, variant) }
        }

    /**
     * True if the Qwen 2B vision model + mmproj are present AND native vision is compiled.
     */
    fun isVisionModelAvailable(): Boolean {
        if (!LlamaCppBridge.nativeIsVisionSupported()) return false
        val modelDir = getModelDirectory()
        val model  = File(modelDir, LlamaVisionEngine.QWEN_MODEL_FILENAME)
        val mmproj = File(modelDir, LlamaVisionEngine.QWEN_MMPROJ_FILENAME)
        return model.exists()  && model.length()  > MIN_VISION_MODEL_BYTES
            && mmproj.exists() && mmproj.length() > MIN_MMPROJ_BYTES
    }

    /**
     * True if the Gemma 4 E2B .litertlm is available (vision is built-in, no mmproj needed).
     */
    fun isGemma4VisionAvailable(): Boolean = isLiteRTModelAvailable()

    /** True if the Gemma 4 mmproj is downloaded (text model checked via isModelAvailable). */
    fun isGemma4MmprojAvailable(): Boolean {
        val mmproj = File(getModelDirectory(), GEMMA4_MMPROJ_FILENAME)
        return mmproj.exists() && mmproj.length() > MIN_GEMMA4_MMPROJ_BYTES
    }

    // -----------------------------------------------------------------------
    // Human-readable labels for download dialogs
    // -----------------------------------------------------------------------

    fun getModelSizeLabel(tier: TextModelTier = TextModelTier.FAST): String = tier.sizeLabel

    fun getVisionModelSizeLabel(): String = "~1.6 GB"

    fun getGemma4MmprojSizeLabel(): String = "~986 MB"

    // -----------------------------------------------------------------------
    // Downloads
    // -----------------------------------------------------------------------

    /**
     * Download the text model for [tier] and [variant] from HuggingFace.
     * No-op if the file is already present and valid.
     * For FINETUNED variant on non-supporting tiers, silently falls back to BASE.
     */
    suspend fun downloadModel(
        tier: TextModelTier = TextModelTier.FAST,
        variant: TextModelVariant = TextModelVariant.BASE,
    ) = withContext(Dispatchers.IO) {
            val effectiveVariant = tier.variantFallback(variant)
            if (isModelAvailable(tier, effectiveVariant)) {
                _downloadState.value = VlmDownloadState.Complete
                return@withContext
            }
            cancelled = false
            val isFinetuned = tier == TextModelTier.GEMMA4_E2B && effectiveVariant == TextModelVariant.FINETUNED
            val filename = if (isFinetuned) finetunedFilename() else tier.filenameFor(effectiveVariant)
            val url = when {
                isFinetuned                          -> finetunedUrl()
                tier == TextModelTier.OCEANGUARD_FT  -> OCEANGUARD_GGUF_URL
                tier == TextModelTier.GEMMA4_E2B     -> GEMMA4_LITERTLM_URL
                tier == TextModelTier.FAST           -> TEXT_FAST_URL
                tier == TextModelTier.BALANCED       -> TEXT_BALANCED_URL
                else                                 -> TEXT_QUALITY_URL
            }
            val target = File(getModelDirectory(), filename)
            val minBytes = if (effectiveVariant == TextModelVariant.FINETUNED)
                MIN_GEMMA4_LITERTLM_BYTES
            else
                MIN_TEXT_BYTES[tier] ?: 50_000_000L
            try {
                downloadFile(url = url, target = target, minValidBytes = minBytes, filename = filename)
                if (isFinetuned) {
                    val version = cachedBestModel?.version ?: ""
                    if (version.isNotBlank()) settings?.setInstalledFinetunedVersion(version)
                }
            } catch (e: VlmDownloadException) {
                _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed")
                throw e
            }
        }

    /**
     * Download both Qwen vision model files (2B + mmproj) from HuggingFace.
     * Progress is emitted per file; completes only when both are valid.
     */
    suspend fun downloadVisionModel() = withContext(Dispatchers.IO) {
        if (isVisionModelAvailable()) {
            _downloadState.value = VlmDownloadState.Complete
            return@withContext
        }
        cancelled = false
        val modelDir = getModelDirectory()

        try {
            val model2b = File(modelDir, LlamaVisionEngine.QWEN_MODEL_FILENAME)
            if (!model2b.exists() || model2b.length() < MIN_VISION_MODEL_BYTES) {
                downloadFile(
                    url          = VISION_MODEL_URL,
                    target       = model2b,
                    minValidBytes = MIN_VISION_MODEL_BYTES,
                    filename     = LlamaVisionEngine.QWEN_MODEL_FILENAME,
                )
                if (cancelled) return@withContext
            }

            val mmproj = File(modelDir, LlamaVisionEngine.QWEN_MMPROJ_FILENAME)
            if (!mmproj.exists() || mmproj.length() < MIN_MMPROJ_BYTES) {
                downloadFile(
                    url          = MMPROJ_URL,
                    target       = mmproj,
                    minValidBytes = MIN_MMPROJ_BYTES,
                    filename     = LlamaVisionEngine.QWEN_MMPROJ_FILENAME,
                )
            }
        } catch (e: VlmDownloadException) {
            _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed")
            throw e
        }
    }

    /**
     * Download the Gemma 4 mmproj file from HuggingFace.
     * The text model is downloaded via [downloadModel] with [TextModelTier.GEMMA4_E2B].
     */
    suspend fun downloadGemma4Mmproj() = withContext(Dispatchers.IO) {
        if (isGemma4MmprojAvailable()) {
            _downloadState.value = VlmDownloadState.Complete
            return@withContext
        }
        cancelled = false
        try {
            val target = File(getModelDirectory(), GEMMA4_MMPROJ_FILENAME)
            downloadFile(
                url           = GEMMA4_MMPROJ_URL,
                target        = target,
                minValidBytes = MIN_GEMMA4_MMPROJ_BYTES,
                filename      = GEMMA4_MMPROJ_FILENAME,
            )
        } catch (e: VlmDownloadException) {
            _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed")
            throw e
        }
    }

    // -----------------------------------------------------------------------
    // LiteRT-LM benchmark spike
    // -----------------------------------------------------------------------

    /** Check if the Gemma 4 E2B .litertlm model is downloaded and valid for [variant]. */
    fun isLiteRTModelAvailable(variant: TextModelVariant = TextModelVariant.BASE): Boolean =
        isModelAvailable(TextModelTier.GEMMA4_E2B, variant)

    /** Absolute path to the .litertlm model file for [variant] (may not exist yet). */
    fun getLiteRTModelPath(variant: TextModelVariant = TextModelVariant.BASE): String =
        getTextModelPath(TextModelTier.GEMMA4_E2B, variant)

    /** Download Gemma 4 E2B .litertlm from HuggingFace (~2.58 GB). */
    suspend fun downloadLiteRTModel(variant: TextModelVariant = TextModelVariant.BASE) =
        downloadModel(TextModelTier.GEMMA4_E2B, variant)

    // -----------------------------------------------------------------------
    // BioDex species pack (M4)
    // -----------------------------------------------------------------------

    /** Directory holding the downloadable BioDex assets (`models/species/`). */
    fun getSpeciesDirectory(): File = File(getModelDirectory(), "species").also { it.mkdirs() }

    /** True once the encoder + index + catalog are present (raster is optional). */
    fun isSpeciesPackAvailable(): Boolean {
        val dir = getSpeciesDirectory()
        return File(dir, "clip_vitb32.onnx").exists() &&
            File(dir, "species_index_v1.bin").exists() &&
            File(dir, "species_catalog_v1.json").exists()
    }

    /**
     * Download the BioDex species pack (encoder + index + catalog + raster) from
     * HuggingFace into [getSpeciesDirectory]. Reuses [downloadFile] + [downloadState].
     * Skips files already present and large enough. Until the HF repo exists this
     * surfaces a clear download error; the app keeps working via the demo fallback.
     */
    suspend fun downloadSpeciesPack() = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            val dir = getSpeciesDirectory()
            for ((name, minBytes) in SPECIES_ASSETS) {
                if (cancelled) break
                val target = File(dir, name)
                if (target.exists() && target.length() >= minBytes) continue
                downloadFile(url = "$SPECIES_REPO/$name", target = target, minValidBytes = minBytes, filename = name)
            }
            _downloadState.value = VlmDownloadState.Complete
        } catch (e: VlmDownloadException) {
            _downloadState.value = VlmDownloadState.Error(e.message ?: "Species pack download failed")
            throw e
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private suspend fun downloadFile(
        url: String,
        target: File,
        minValidBytes: Long,
        filename: String,
    ) = withContext(Dispatchers.IO) {
        val temp = File(target.parent, "$filename.tmp")
        if (temp.exists() && temp.length() < minValidBytes) temp.delete()

        _downloadState.value = VlmDownloadState.Preparing
        Log.i(TAG, "Downloading $filename from $url")

        val connection = openConnection(url)
        try {
            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw VlmDownloadException("HTTP $responseCode downloading $filename")
            }

            if (connection.contentType?.contains("text/html", ignoreCase = true) == true) {
                throw VlmDownloadException("Server returned HTML for $filename — check URL")
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (contentLength > 0) contentLength else minValidBytes * 2

            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                java.io.FileOutputStream(temp, false).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L

                    // Throttle UI emissions to avoid recomposing the Home tree
                    // ~325 times per download. Emit at most 5 Hz OR whenever the
                    // integer percent changes — whichever happens first. The
                    // final-byte emit happens after the loop so 100% is never
                    // lost to throttling.
                    var lastEmitMs = 0L
                    var lastProgressPct = -1
                    val emitMinIntervalMs = 200L

                    while (isActive && !cancelled) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        val progress = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                        val progressPct = (progress * 100f).toInt()
                        if (now - lastEmitMs >= emitMinIntervalMs || progressPct != lastProgressPct) {
                            _downloadState.value = VlmDownloadState.Downloading(
                                progress       = progress,
                                downloadedBytes = downloaded,
                                totalBytes     = totalBytes,
                                currentFile    = filename,
                            )
                            lastEmitMs = now
                            lastProgressPct = progressPct
                        }
                    }

                    // Final emit so the UI never freezes on the second-to-last
                    // throttled snapshot before transitioning to Installing.
                    _downloadState.value = VlmDownloadState.Downloading(
                        progress       = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f),
                        downloadedBytes = downloaded,
                        totalBytes     = totalBytes,
                        currentFile    = filename,
                    )
                }
            }
        } finally {
            connection.disconnect()
        }

        if (cancelled) {
            temp.delete()
            _downloadState.value = VlmDownloadState.Idle
            return@withContext
        }

        if (temp.length() < minValidBytes) {
            temp.delete()
            throw VlmDownloadException("Downloaded $filename is too small (${temp.length()} bytes)")
        }

        _downloadState.value = VlmDownloadState.Installing
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        _downloadState.value = VlmDownloadState.Complete
        Log.i(TAG, "$filename downloaded: ${target.length()} bytes → ${target.absolutePath}")
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout    = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "OceanGuardAI/1.0")
        return conn
    }

    fun cancelDownload() {
        cancelled = true
        _downloadState.value = VlmDownloadState.Idle
    }

    fun resetState() {
        _downloadState.value = if (isAnyTextModelAvailable()) {
            VlmDownloadState.Complete
        } else {
            VlmDownloadState.Idle
        }
    }
}

// ---------------------------------------------------------------------------
// Download state
// ---------------------------------------------------------------------------

sealed class VlmDownloadState {
    data object Idle : VlmDownloadState()
    data object Preparing : VlmDownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFile: String = "",
    ) : VlmDownloadState()
    data object Installing : VlmDownloadState()
    data object Complete : VlmDownloadState()
    data class Error(val message: String) : VlmDownloadState()
}

class VlmDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
