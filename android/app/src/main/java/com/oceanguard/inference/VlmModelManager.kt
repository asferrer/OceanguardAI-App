package com.oceanguard.ai.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the VLM (Gemma 3n) model lifecycle: availability check and
 * on-demand download from Google Drive.
 *
 * The model (~3.5 GB) is too large to bundle in the APK. On first launch
 * (or after a cache clear) the user is prompted to download it.
 *
 * Google Drive large-file downloads require special handling:
 * 1. Primary URL: drive.usercontent.google.com (new, most reliable)
 * 2. Fallback: drive.google.com/uc with cookie-based virus scan bypass
 *
 * Download progress is exposed via [downloadState] so both the UI and
 * [VlmDownloadService] can observe it.
 */
class VlmModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VlmModelManager"

        // Google Drive file ID — update when uploading a new model version
        // Folder: https://drive.google.com/drive/folders/1ILUqTbIGs05qlWa8vpFzpgtMDJ9u7KRx
        private const val DRIVE_FILE_ID = "1ixGSjr3La4JAifnIgCmZgR80RhIztDTl"

        /** Expected model filenames (same order as OceanGuardInference). */
        val MODEL_FILENAMES = listOf(
            "gemma-3n-E2B-it-int4.task",
            "gemma-3n-E2B-it-int4.litertlm",
        )

        /** Approximate model size for progress estimation when Content-Length is unavailable. */
        private const val ESTIMATED_SIZE_BYTES = 3_500_000_000L

        /** Files smaller than this are likely HTML error pages, not the real model. */
        private const val MIN_VALID_SIZE_BYTES = 100_000_000L // 100 MB

        private const val BUFFER_SIZE = 8 * 1024 * 1024 // 8 MB
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private val _downloadState = MutableStateFlow<VlmDownloadState>(VlmDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    @Volatile
    private var cancelled = false

    /** Directory where the model is stored (app-private external). */
    fun getModelDirectory(): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    /**
     * Check whether any recognised VLM model file exists in app storage.
     * This is a fast file-system check — no model loading.
     */
    fun isModelAvailable(): Boolean {
        val modelsDir = getModelDirectory()
        for (filename in MODEL_FILENAMES) {
            val f = File(modelsDir, filename)
            if (f.exists() && f.length() > MIN_VALID_SIZE_BYTES) return true
        }
        for (filename in MODEL_FILENAMES) {
            val f = File(context.filesDir, "models/$filename")
            if (f.exists() && f.length() > MIN_VALID_SIZE_BYTES) return true
        }
        return false
    }

    /** Returns human-readable size (e.g. "3.5 GB"). */
    fun getModelSizeLabel(): String = "~3.5 GB"

    /**
     * Download the VLM model from Google Drive to app-private storage.
     *
     * Uses the new drive.usercontent.google.com endpoint which is more
     * reliable for large files than the legacy uc?export=download URL.
     *
     * @throws VlmDownloadException on unrecoverable errors
     */
    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        if (isModelAvailable()) {
            _downloadState.value = VlmDownloadState.Complete
            return@withContext
        }

        cancelled = false
        val targetFile = File(getModelDirectory(), MODEL_FILENAMES[1]) // .litertlm
        val tempFile = File(getModelDirectory(), "${MODEL_FILENAMES[1]}.tmp")

        // Install a CookieManager so Google Drive virus-scan cookies are handled
        val previousHandler = CookieHandler.getDefault()
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(cookieManager)

        try {
            _downloadState.value = VlmDownloadState.Preparing

            // Delete any previous corrupt/partial temp files
            if (tempFile.exists() && tempFile.length() < MIN_VALID_SIZE_BYTES) {
                tempFile.delete()
            }

            // Try primary URL first, then fallback
            val urls = listOf(
                buildPrimaryUrl(),
                buildFallbackUrl(),
            )

            var lastError: Exception? = null
            for (url in urls) {
                if (cancelled) break
                try {
                    Log.i(TAG, "Attempting download from: $url")
                    downloadFromDrive(url, targetFile, tempFile)
                    if (cancelled) {
                        _downloadState.value = VlmDownloadState.Idle
                    }
                    return@withContext // Success
                } catch (e: VlmDownloadException) {
                    Log.w(TAG, "Download attempt failed: ${e.message}")
                    lastError = e
                    tempFile.delete() // Clean up corrupt file before retry
                }
            }

            throw lastError ?: VlmDownloadException("All download URLs failed")

        } catch (e: VlmDownloadException) {
            _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed")
            throw e
        } catch (e: Exception) {
            if (cancelled) {
                _downloadState.value = VlmDownloadState.Idle
            } else {
                Log.e(TAG, "Download failed", e)
                _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed")
                throw VlmDownloadException("Download failed: ${e.message}", e)
            }
        } finally {
            CookieHandler.setDefault(previousHandler)
        }
    }

    private suspend fun downloadFromDrive(
        url: String,
        targetFile: File,
        tempFile: File,
    ) = withContext(Dispatchers.IO) {
        val connection = openConnection(url, 0L)
        val responseCode = connection.responseCode

        Log.i(TAG, "Response: $responseCode, Content-Type: ${connection.contentType}, Content-Length: ${connection.contentLengthLong}")

        if (responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw VlmDownloadException("HTTP $responseCode: ${connection.responseMessage}")
        }

        // Check if Google returned an HTML page instead of the file
        val contentType = connection.contentType ?: ""
        if (contentType.contains("text/html", ignoreCase = true)) {
            // Read HTML to extract the actual download link
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            Log.w(TAG, "Got HTML response (${html.length} chars), extracting download URL...")

            val directUrl = extractDownloadUrl(html)
            if (directUrl != null) {
                Log.i(TAG, "Extracted direct URL, retrying download...")
                downloadDirect(directUrl, targetFile, tempFile)
            } else {
                throw VlmDownloadException("Google Drive returned HTML confirmation page and no download URL could be extracted")
            }
            return@withContext
        }

        // We got a binary response — this is the actual file
        val contentLength = connection.contentLengthLong
        val totalBytes = if (contentLength > 0) contentLength else ESTIMATED_SIZE_BYTES

        streamToFile(connection, tempFile, 0L, totalBytes, false)

        if (cancelled) return@withContext

        validateAndInstall(tempFile, targetFile)
    }

    /** Download from a resolved direct URL (after HTML extraction). */
    private suspend fun downloadDirect(
        url: String,
        targetFile: File,
        tempFile: File,
    ) = withContext(Dispatchers.IO) {
        val connection = openConnection(url, 0L)
        val responseCode = connection.responseCode

        if (responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw VlmDownloadException("HTTP $responseCode on direct download")
        }

        val contentType = connection.contentType ?: ""
        if (contentType.contains("text/html", ignoreCase = true)) {
            connection.disconnect()
            throw VlmDownloadException("Still getting HTML after redirect — Google Drive may be blocking the download")
        }

        val contentLength = connection.contentLengthLong
        val totalBytes = if (contentLength > 0) contentLength else ESTIMATED_SIZE_BYTES

        streamToFile(connection, tempFile, 0L, totalBytes, false)

        if (cancelled) return@withContext

        validateAndInstall(tempFile, targetFile)
    }

    /**
     * Extract the actual download URL from a Google Drive virus-scan HTML page.
     * The form typically contains an action URL with a confirm token.
     */
    private fun extractDownloadUrl(html: String): String? {
        // Pattern 1: form action with download URL
        val formPattern = Regex("""action="(https://drive\.usercontent\.google\.com/download[^"]*?)"""")
        formPattern.find(html)?.let { match ->
            return match.groupValues[1].replace("&amp;", "&")
        }

        // Pattern 2: direct link in the page
        val linkPattern = Regex("""href="(/uc\?export=download[^"]*?)"""")
        linkPattern.find(html)?.let { match ->
            return "https://drive.google.com${match.groupValues[1].replace("&amp;", "&")}"
        }

        // Pattern 3: id and export in a download form (newer Google Drive pages)
        val uuidPattern = Regex("""name="uuid" value="([^"]+)"""")
        val uuid = uuidPattern.find(html)?.groupValues?.get(1)
        if (uuid != null) {
            return "https://drive.usercontent.google.com/download?id=$DRIVE_FILE_ID&export=download&confirm=t&uuid=$uuid"
        }

        return null
    }

    private fun validateAndInstall(tempFile: File, targetFile: File) {
        // Validate file size — if it's tiny, it's probably an error page
        if (tempFile.length() < MIN_VALID_SIZE_BYTES) {
            val preview = tempFile.readText().take(200)
            tempFile.delete()
            throw VlmDownloadException("Downloaded file too small (${tempFile.length()} bytes). Content: $preview")
        }

        _downloadState.value = VlmDownloadState.Installing
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        _downloadState.value = VlmDownloadState.Complete
        Log.i(TAG, "Download complete: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
    }

    private fun openConnection(url: String, rangeStart: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        // Pretend to be a browser so Google Drive serves the file directly
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (rangeStart > 0) {
            connection.setRequestProperty("Range", "bytes=$rangeStart-")
        }
        return connection
    }

    private suspend fun streamToFile(
        connection: HttpURLConnection,
        tempFile: File,
        existingBytes: Long,
        totalBytes: Long,
        append: Boolean,
    ) = withContext(Dispatchers.IO) {
        connection.inputStream.buffered(BUFFER_SIZE).use { input ->
            java.io.FileOutputStream(tempFile, append).buffered(BUFFER_SIZE).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var downloaded = existingBytes

                while (isActive && !cancelled) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    val progress = if (totalBytes > 0) {
                        (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else 0f

                    _downloadState.value = VlmDownloadState.Downloading(
                        progress = progress,
                        downloadedBytes = downloaded,
                        totalBytes = totalBytes,
                    )
                }
            }
        }
        connection.disconnect()
    }

    fun cancelDownload() {
        cancelled = true
        _downloadState.value = VlmDownloadState.Idle
    }

    fun resetState() {
        _downloadState.value = if (isModelAvailable()) {
            VlmDownloadState.Complete
        } else {
            VlmDownloadState.Idle
        }
    }

    /** New Google Drive download endpoint (2024+), most reliable for large files. */
    private fun buildPrimaryUrl(): String =
        "https://drive.usercontent.google.com/download?id=$DRIVE_FILE_ID&export=download&confirm=t"

    /** Legacy endpoint as fallback. */
    private fun buildFallbackUrl(): String =
        "https://drive.google.com/uc?export=download&confirm=t&id=$DRIVE_FILE_ID"
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

sealed class VlmDownloadState {
    /** Initial / cancelled. */
    data object Idle : VlmDownloadState()

    /** Resolving URL, checking resume data. */
    data object Preparing : VlmDownloadState()

    /** Downloading with progress. */
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : VlmDownloadState()

    /** Renaming temp file → final file. */
    data object Installing : VlmDownloadState()

    /** Model file is ready. */
    data object Complete : VlmDownloadState()

    /** Download failed. */
    data class Error(val message: String) : VlmDownloadState()
}

class VlmDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
