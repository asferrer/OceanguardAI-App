package com.oceanguard.ai.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.ReportGenerationState
import com.oceanguard.ai.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service that keeps a persistent notification
 * while report generation runs in [OceanGuardApp.applicationScope].
 *
 * The service does NOT perform the actual work — it only observes
 * [OceanGuardApp.reportGenerationState] and mirrors the current phase
 * into the notification bar. This keeps the process alive even if the
 * user navigates away from the app.
 */
class ReportGenerationService : LifecycleService() {

    companion object {
        private const val TAG = "ReportGenService"
        private const val NOTIFICATION_ID = 1002

        fun startIntent(context: Context): Intent =
            Intent(context, ReportGenerationService::class.java)
    }

    private lateinit var app: OceanGuardApp
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        app = application as OceanGuardApp
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.report_notif_loading_model)),
        )

        // Acquire wake lock to keep CPU running when screen is off or app is backgrounded
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OceanGuard:ReportWakeLock")
                .also { it.acquire(60 * 60 * 1000L) } // 60-min safety timeout
        }

        lifecycleScope.launch {
            app.reportGenerationState.collectLatest { state ->
                when (state) {
                    is ReportGenerationState.LoadingModel -> {
                        updateNotification(getString(R.string.report_notif_loading_model))
                    }
                    is ReportGenerationState.VerifyingDetections -> {
                        updateNotification(getString(R.string.report_notif_generating))
                    }
                    is ReportGenerationState.Generating -> {
                        updateNotification(getString(R.string.report_notif_generating))
                    }
                    is ReportGenerationState.StreamingText -> {
                        updateNotification(getString(R.string.report_notif_generating))
                    }
                    is ReportGenerationState.Complete -> {
                        updateNotification(getString(R.string.report_notif_complete))
                        stopSelf()
                    }
                    is ReportGenerationState.Error -> {
                        updateNotification(getString(R.string.report_notif_error))
                        stopSelf()
                    }
                    is ReportGenerationState.Idle -> {
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        // Reuse InferenceService channel (already created at app start)
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

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, InferenceService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.report_notif_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)

        val state = app.reportGenerationState.value
        if (state is ReportGenerationState.LoadingModel ||
            state is ReportGenerationState.Generating
        ) {
            builder.setProgress(0, 0, true)
        }

        val notification = builder.build()
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot update notification: permission denied", e)
        }
    }
}
