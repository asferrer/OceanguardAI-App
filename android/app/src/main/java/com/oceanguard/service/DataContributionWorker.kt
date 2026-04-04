package com.oceanguard.ai.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.utils.WebDavUploadClient
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WorkManager CoroutineWorker that uploads pending contribution images to the NAS.
 * Triggered by ContributionRepository.scheduleWorker() after enqueueing new items.
 *
 * On failure: WorkManager retries with exponential backoff (configured at enqueue time).
 */
class DataContributionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as OceanGuardApp
        val nasUrl = app.settingsRepository.contributeNasUrl.first().trim()
            .let { if (it.endsWith("/")) it else "$it/" }
        val token = app.contributionTokenStore.getToken()

        if (nasUrl.isEmpty() || token.isEmpty()) return Result.failure()

        val client = WebDavUploadClient(nasUrl, token)
        val pending = app.contributionQueueDao.getPending()
        if (pending.isEmpty()) return Result.success()

        var allOk = true
        val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)

        for (item in pending) {
            val imageFile = File(item.imageUri.removePrefix("file://"))
            if (!imageFile.exists()) {
                app.contributionQueueDao.updateStatus(item.id, "FAILED")
                continue
            }
            val yearMonth = monthFmt.format(Date(item.createdAt))
            val ok = client.upload(
                yearMonth = yearMonth,
                fileName = imageFile.name,
                imageBytes = imageFile.readBytes(),
                jsonBytes = item.annotationsJson.toByteArray(Charsets.UTF_8),
            )
            app.contributionQueueDao.updateStatus(item.id, if (ok) "DONE" else "FAILED")
            if (!ok) allOk = false
        }

        // Check contribution achievements based on total successful uploads
        val totalDone = app.contributionQueueDao.getDoneCount()
        app.achievementChecker.checkContributionCount(totalDone)

        return if (allOk) Result.success() else Result.retry()
    }
}
