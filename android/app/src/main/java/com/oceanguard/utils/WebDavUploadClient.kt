package com.oceanguard.ai.utils

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Uploads files to a Synology NAS (or any WebDAV server) via HTTP PUT.
 *
 * Synology WebDAV setup:
 *   - Enable WebDAV HTTPS in DSM → Control Panel → File Services → WebDAV (port 5006)
 *   - Create a dedicated user (e.g. "oceanguard-app") with R/W access to one shared folder
 *   - Use Basic Auth: username="oceanguard-app", password=token
 *   - URL example: https://yourname.synology.me:5006/oceanguard-dataset/
 */
class WebDavUploadClient(
    private val baseUrl: String,
    token: String,
) {
    // Synology WebDAV uses HTTP Basic Auth
    private val authHeader = "Basic " + Base64.encodeToString(
        "oceanguard-app:$token".toByteArray(),
        Base64.NO_WRAP,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads the JPEG image and its COCO JSON annotation to {baseUrl}/{yearMonth}/.
     * Returns true only if both uploads succeed.
     */
    suspend fun upload(
        yearMonth: String,
        fileName: String,
        imageBytes: ByteArray,
        jsonBytes: ByteArray,
    ): Boolean {
        mkcolSilent("$baseUrl$yearMonth/")
        val imageOk = put("$baseUrl$yearMonth/$fileName", imageBytes, "image/jpeg")
        val jsonOk = put(
            "$baseUrl$yearMonth/${fileName.removeSuffix(".jpg")}.json",
            jsonBytes,
            "application/json",
        )
        return imageOk && jsonOk
    }

    /** Creates a WebDAV collection (directory). Silently ignores 405 if already exists. */
    private suspend fun mkcolSilent(url: String) = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader)
                    .method("MKCOL", "".toRequestBody())
                    .build()
            ).execute().close()
        }
    }

    private suspend fun put(url: String, body: ByteArray, mime: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", authHeader)
                        .put(body.toRequestBody(mime.toMediaType()))
                        .build()
                ).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
}
