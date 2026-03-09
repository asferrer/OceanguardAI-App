package com.oceanguard.ai.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a persistent notification while the VLM
 * model downloads from Google Drive.
 *
 * Like [ReportGenerationService], this service only observes state — the
 * actual download runs in [OceanGuardApp.applicationScope] via
 * [com.oceanguard.ai.inference.VlmModelManager].
 */
class VlmDownloadService : LifecycleService() {

    companion object {
        private const val TAG = "VlmDownloadService"
        private const val NOTIFICATION_ID = 1003
        const val ACTION_CANCEL = "com.oceanguard.ai.CANCEL_VLM_DOWNLOAD"

        fun startIntent(context: Context): Intent =
            Intent(context, VlmDownloadService::class.java)
    }

    private lateinit var app: OceanGuardApp
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        app = application as OceanGuardApp
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_CANCEL) {
            app.vlmModelManager.cancelDownload()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.vlm_download_notif_preparing), 0, false),
        )

        lifecycleScope.launch {
            app.vlmModelManager.downloadState.collectLatest { state ->
                when (state) {
                    is VlmDownloadState.Idle -> stopSelf()
                    is VlmDownloadState.Preparing -> {
                        updateNotification(
                            getString(R.string.vlm_download_notif_preparing),
                            progress = 0,
                            indeterminate = true,
                        )
                    }
                    is VlmDownloadState.Downloading -> {
                        val percent = (state.progress * 100).toInt()
                        val mbDownloaded = state.downloadedBytes / (1024 * 1024)
                        val mbTotal = state.totalBytes / (1024 * 1024)
                        updateNotification(
                            getString(R.string.vlm_download_notif_progress, mbDownloaded, mbTotal),
                            progress = percent,
                            indeterminate = false,
                        )
                    }
                    is VlmDownloadState.Installing -> {
                        updateNotification(
                            getString(R.string.vlm_download_notif_installing),
                            progress = 100,
                            indeterminate = true,
                        )
                    }
                    is VlmDownloadState.Complete -> {
                        updateNotification(
                            getString(R.string.vlm_download_notif_complete),
                            progress = 100,
                            indeterminate = false,
                        )
                        stopSelf()
                    }
                    is VlmDownloadState.Error -> {
                        updateNotification(
                            getString(R.string.vlm_download_notif_error),
                            progress = 0,
                            indeterminate = false,
                        )
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun ensureNotificationChannel() {
        val existing = notificationManager.getNotificationChannel(InferenceService.CHANNEL_ID)
        if (existing == null) {
            val channel = android.app.NotificationChannel(
                InferenceService.CHANNEL_ID,
                "OceanGuard Analysis",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress of marine debris analysis"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        contentText: String,
        progress: Int,
        indeterminate: Boolean,
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = Intent(this, VlmDownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, InferenceService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.vlm_download_notif_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.common_cancel),
                cancelPendingIntent,
            )

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else if (progress > 0) {
            builder.setProgress(100, progress, false)
        }

        val notification = builder.build()
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun updateNotification(text: String, progress: Int, indeterminate: Boolean) {
        try {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(text, progress, indeterminate),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot update notification: permission denied", e)
        }
    }
}
