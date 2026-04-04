package com.oceanguard.ai.utils

import android.util.Base64
import com.oceanguard.ai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Uploads files to the OceanGuard research NAS via WebDAV PUT.
 *
 * Credentials are embedded at build time from local.properties → BuildConfig.
 * The NAS user has write-only permissions — even if credentials are extracted
 * from the APK, an attacker cannot read or delete the dataset.
 */
object WebDavUploadClient {

    private val baseUrl: String
        get() = BuildConfig.CONTRIB_URL.let { if (it.endsWith("/")) it else "$it/" }

    private val authHeader: String = "Basic " + Base64.encodeToString(
        "${BuildConfig.CONTRIB_USER}:${BuildConfig.CONTRIB_PASS}".toByteArray(),
        Base64.NO_WRAP,
    )

    /** True when build-time credentials are configured (non-empty URL + user). */
    val isConfigured: Boolean
        get() = BuildConfig.CONTRIB_URL.isNotBlank() && BuildConfig.CONTRIB_USER.isNotBlank()

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
