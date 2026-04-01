package com.oceanguard.ai.inference

import android.content.Context
import android.util.Log
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
class VlmModelManager(private val context: Context) {

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

        // Vision model URLs (2B + mmproj)
        private const val VISION_MODEL_URL =
            "$HF_BASE/unsloth/Qwen3.5-2B-GGUF/resolve/main/${LlamaVisionEngine.MODEL_FILENAME}"
        private const val MMPROJ_URL =
            "$HF_BASE/unsloth/Qwen3.5-2B-GGUF/resolve/main/${LlamaVisionEngine.MMPROJ_FILENAME}"

        // Minimum valid file sizes (small files = error HTML pages from HF)
        private val MIN_TEXT_BYTES = mapOf(
            TextModelTier.FAST     to   400_000_000L, //  400 MB — Qwen3.5-0.8B (~533 MB)
            TextModelTier.BALANCED to   900_000_000L, //  900 MB — Qwen3.5-2B  (~1.1 GB)
            TextModelTier.QUALITY  to 2_200_000_000L, // 2.2 GB — Qwen3.5-4B  (~2.74 GB)
        )
        private const val MIN_VISION_MODEL_BYTES = 500_000_000L // 500 MB — 2B
        private const val MIN_MMPROJ_BYTES       =  50_000_000L //  50 MB — mmproj

        private const val BUFFER_SIZE        = 8 * 1024 * 1024 // 8 MB
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS    = 60_000
    }

    private val _downloadState = MutableStateFlow<VlmDownloadState>(VlmDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    @Volatile
    private var cancelled = false

    // -----------------------------------------------------------------------
    // File paths
    // -----------------------------------------------------------------------

    /** App-private external storage directory for all models. */
    fun getModelDirectory(): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    /** Absolute path to the text GGUF file for [tier]. */
    fun getTextModelPath(tier: TextModelTier = TextModelTier.FAST): String =
        File(getModelDirectory(), tier.filename).absolutePath

    /** Absolute path to the vision LLM GGUF file. */
    fun getVisionModelPath(): String =
        File(getModelDirectory(), LlamaVisionEngine.MODEL_FILENAME).absolutePath

    /** Absolute path to the CLIP mmproj GGUF file. */
    fun getMmprojPath(): String =
        File(getModelDirectory(), LlamaVisionEngine.MMPROJ_FILENAME).absolutePath

    // -----------------------------------------------------------------------
    // Availability checks
    // -----------------------------------------------------------------------

    /** True if the text model for [tier] is present and has a valid size. */
    fun isModelAvailable(tier: TextModelTier = TextModelTier.FAST): Boolean {
        val f = File(getModelDirectory(), tier.filename)
        val minBytes = MIN_TEXT_BYTES[tier] ?: 50_000_000L
        return f.exists() && f.length() > minBytes
    }

    /** True if any text tier is downloaded (fast check for report button). */
    fun isAnyTextModelAvailable(): Boolean =
        isModelAvailable(TextModelTier.FAST) ||
        isModelAvailable(TextModelTier.BALANCED) ||
        isModelAvailable(TextModelTier.QUALITY)

    /**
     * True if both the 2B vision model and the mmproj file are present AND the native
     * library was compiled with vision support ([LlamaCppBridge.nativeIsVisionSupported]).
     * When the native vision bridge is a stub (current build), returns false so the app
     * falls back to text-only report generation using the 2B model as a text tier.
     */
    fun isVisionModelAvailable(): Boolean {
        if (!LlamaCppBridge.nativeIsVisionSupported()) return false
        val modelDir = getModelDirectory()
        val model  = File(modelDir, LlamaVisionEngine.MODEL_FILENAME)
        val mmproj = File(modelDir, LlamaVisionEngine.MMPROJ_FILENAME)
        return model.exists()  && model.length()  > MIN_VISION_MODEL_BYTES
            && mmproj.exists() && mmproj.length() > MIN_MMPROJ_BYTES
    }

    // -----------------------------------------------------------------------
    // Human-readable labels for download dialogs
    // -----------------------------------------------------------------------

    fun getModelSizeLabel(tier: TextModelTier = TextModelTier.FAST): String = tier.sizeLabel

    fun getVisionModelSizeLabel(): String = "~1.6 GB"

    // -----------------------------------------------------------------------
    // Downloads
    // -----------------------------------------------------------------------

    /**
     * Download the text model for [tier] from HuggingFace.
     * No-op if the file is already present and valid.
     */
    suspend fun downloadModel(tier: TextModelTier = TextModelTier.FAST) =
        withContext(Dispatchers.IO) {
            if (isModelAvailable(tier)) {
                _downloadState.value = VlmDownloadState.Complete
                return@withContext
            }
            cancelled = false
            val url = when (tier) {
                TextModelTier.FAST     -> TEXT_FAST_URL
                TextModelTier.BALANCED -> TEXT_BALANCED_URL
                TextModelTier.QUALITY  -> TEXT_QUALITY_URL
            }
            val target = File(getModelDirectory(), tier.filename)
            val minBytes = MIN_TEXT_BYTES[tier] ?: 50_000_000L
            try {
                downloadFile(url = url, target = target, minValidBytes = minBytes, filename = tier.filename)
            } catch (e: VlmDownloadException) {
                _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed")
                throw e
            }
        }

    /**
     * Download both vision model files (2B + mmproj) from HuggingFace.
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
            val model2b = File(modelDir, LlamaVisionEngine.MODEL_FILENAME)
            if (!model2b.exists() || model2b.length() < MIN_VISION_MODEL_BYTES) {
                downloadFile(
                    url          = VISION_MODEL_URL,
                    target       = model2b,
                    minValidBytes = MIN_VISION_MODEL_BYTES,
                    filename     = LlamaVisionEngine.MODEL_FILENAME,
                )
                if (cancelled) return@withContext
            }

            val mmproj = File(modelDir, LlamaVisionEngine.MMPROJ_FILENAME)
            if (!mmproj.exists() || mmproj.length() < MIN_MMPROJ_BYTES) {
                downloadFile(
                    url          = MMPROJ_URL,
                    target       = mmproj,
                    minValidBytes = MIN_MMPROJ_BYTES,
                    filename     = LlamaVisionEngine.MMPROJ_FILENAME,
                )
            }
        } catch (e: VlmDownloadException) {
            _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed")
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

                    while (isActive && !cancelled) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        _downloadState.value = VlmDownloadState.Downloading(
                            progress       = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f),
                            downloadedBytes = downloaded,
                            totalBytes     = totalBytes,
                            currentFile    = filename,
                        )
                    }
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
