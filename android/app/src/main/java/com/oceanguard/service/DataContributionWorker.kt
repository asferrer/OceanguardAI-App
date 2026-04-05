package com.oceanguard.ai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.utils.WebDavUploadClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WorkManager CoroutineWorker that uploads pending contribution images to the NAS.
 * Uses build-time credentials from BuildConfig via [WebDavUploadClient].
 *
 * Processes items **one at a time** to avoid OOM on large queues.
 * Shows a progress notification during upload.
 * On failure: WorkManager retries with exponential backoff (configured at enqueue time).
 * Items exceeding [MAX_RETRIES] are marked FAILED and skipped.
 */
class DataContributionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ContribWorker"
        private const val MAX_RETRIES = 5
        private const val PURGE_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        private const val CHANNEL_ID = "oceanguard_contribution"
        private const val NOTIFICATION_ID = 9001
    }

    override suspend fun doWork(): Result {
        if (!WebDavUploadClient.isConfigured) return Result.failure()

        val app = applicationContext as OceanGuardApp
        val dao = app.contributionQueueDao
        val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)

        // Fail items that exceeded max retries before processing
        dao.failExceededRetries(MAX_RETRIES)

        // Purge completed/failed items older than 30 days
        dao.purgeOlderThan(System.currentTimeMillis() - PURGE_AGE_MS)

        // Count total pending for progress tracking
        val totalPending = dao.getPendingCount()
        if (totalPending == 0) return Result.success()

        ensureNotificationChannel()
        setForeground(buildForegroundInfo(0, totalPending))

        var uploaded = 0
        var anyFailed = false

        // Process one item at a time to avoid loading all images into memory
        while (true) {
            val item = dao.getNextPending() ?: break

            val imageFile = File(item.imageUri.removePrefix("file://"))
            if (!imageFile.exists()) {
                Log.w(TAG, "Image file missing, marking FAILED: ${item.imageUri}")
                dao.updateStatus(item.id, "FAILED")
                continue
            }

            setForeground(buildForegroundInfo(uploaded, totalPending))

            val yearMonth = monthFmt.format(Date(item.createdAt))
            val ok = try {
                WebDavUploadClient.upload(
                    yearMonth = yearMonth,
                    fileName = imageFile.name,
                    imageBytes = imageFile.readBytes(),
                    jsonBytes = item.annotationsJson.toByteArray(Charsets.UTF_8),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed for ${imageFile.name}", e)
                false
            }

            if (ok) {
                dao.updateStatus(item.id, "DONE")
                uploaded++
                Log.i(TAG, "Uploaded ${imageFile.name}")
                // Brief pause between uploads to avoid overwhelming the NAS
                kotlinx.coroutines.delay(300)
            } else {
                // Keep as PENDING, just increment retry count — failExceededRetries()
                // at the start of next run will mark it FAILED if max retries reached.
                dao.incrementRetry(item.id)
                Log.w(TAG, "Upload failed for ${imageFile.name} (attempt ${item.retryCount + 1}/$MAX_RETRIES)")
                anyFailed = true
                break
            }
        }

        // Check contribution achievements based on total successful uploads
        val totalDone = dao.getDoneCount()
        app.achievementChecker.checkContributionCount(totalDone)

        // Notify UI of completion
        app.contributionUploadResult.value = ContributionUploadResult(uploaded, anyFailed)

        return if (anyFailed) Result.retry() else Result.success()
    }

    private fun buildForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val text = applicationContext.getString(
            R.string.contribute_upload_progress, current + 1, total
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.contribute_upload_title))
            .setContentText(text)
            .setProgress(total, current, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureNotificationChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.contribute_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.contribute_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }
}

/** Result emitted after a contribution upload cycle. Consumed once by the UI. */
data class ContributionUploadResult(val uploadedCount: Int, val hadFailures: Boolean)
