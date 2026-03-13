package com.oceanguard.ai.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.google.gson.JsonParser
import com.oceanguard.ai.BuildConfig
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
)

/**
 * Checks GitHub Releases API for newer app versions.
 * Rate-limited to one check per 24 hours. Fails silently on network errors.
 */
class UpdateChecker(private val settings: SettingsRepository) {

    private companion object {
        const val TAG = "UpdateChecker"
        const val CHECK_INTERVAL_MS = 24 * 3600 * 1000L
        const val RELEASES_URL =
            "https://api.github.com/repos/asferrer/OceanguardAI/releases/latest"
        const val DOWNLOAD_URL =
            "https://github.com/asferrer/OceanguardAI/releases/latest/download/OceanGuard-AI-latest.apk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateInfo? {
        val lastCheck = settings.lastUpdateCheckMs.first()
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return null

        val info = runCatching { fetchLatestRelease() }
            .onFailure { Log.w(TAG, "Update check failed: ${it.message}") }
            .getOrNull() ?: return null

        settings.setLastUpdateCheckMs(System.currentTimeMillis())

        val currentVersion = BuildConfig.VERSION_NAME
            .removeSuffix("-debug")
        if (info.versionName == currentVersion) return null

        val skipped = settings.skippedVersion.first()
        if (info.versionName == skipped) return null

        return info
    }

    fun downloadApk(context: Context, info: UpdateInfo) {
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("OceanGuard AI v${info.versionName}")
            .setDescription(context.getString(R.string.update_downloading))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "OceanGuard-AI-${info.versionName}.apk",
            )
            .setMimeType("application/vnd.android.package-archive")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(context, context.getString(R.string.update_download_started), Toast.LENGTH_SHORT).show()
    }

    private suspend fun fetchLatestRelease(): UpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body?.string() ?: error("Empty body")
            val json = JsonParser.parseString(body).asJsonObject
            val tagName = json.get("tag_name").asString.removePrefix("v")
            val notes = json.get("body")?.asString ?: ""
            UpdateInfo(tagName, notes, DOWNLOAD_URL)
        }
    }
}
