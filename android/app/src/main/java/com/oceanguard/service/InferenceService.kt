package com.oceanguard.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.inference.AnalysisState
import com.oceanguard.ai.ui.MainActivity
import com.oceanguard.ai.utils.BitmapAnnotator
import com.oceanguard.ai.utils.ExifLocationExtractor
import com.oceanguard.ai.utils.ImagePersistence
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Foreground Service that runs inference in the background.
 *
 * Keeps the process alive with a persistent notification while RT-DETRv2
 * and Gemma 3n process images. Supports both single-image and batch modes.
 *
 * Results are saved autonomously via [com.oceanguard.ai.data.DetectionRepository]
 * so they persist even if the user never returns to the app.
 *
 * State is shared with the UI via [OceanGuardApp.inferenceServiceState].
 */
class InferenceService : LifecycleService() {

    companion object {
        private const val TAG = "InferenceService"
        const val CHANNEL_ID = "oceanguard_inference"
        private const val NOTIFICATION_ID = 1001

        /** Per-item timeout: VLM takes ~6min on CPU, allow 10min for safety. */
        private const val BATCH_ITEM_TIMEOUT_MS = 10L * 60 * 1000
        /** Per-item timeout when VLM is disabled (RT-DETRv2 only: ~6s). */
        private const val BATCH_ITEM_TIMEOUT_NO_VLM_MS = 2L * 60 * 1000

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_URI_LIST = "uri_list"
        const val ACTION_CANCEL = "com.oceanguard.ai.CANCEL_INFERENCE"

        fun singleImageIntent(context: Context, uri: Uri): Intent =
            Intent(context, InferenceService::class.java).apply {
                putExtra(EXTRA_MODE, "single")
                putExtra(EXTRA_URI, uri.toString())
            }

        fun batchIntent(context: Context, uris: List<Uri>): Intent =
            Intent(context, InferenceService::class.java).apply {
                putExtra(EXTRA_MODE, "batch")
                putStringArrayListExtra(EXTRA_URI_LIST, ArrayList(uris.map { it.toString() }))
            }
    }

    private lateinit var app: OceanGuardApp
    private lateinit var notificationManager: NotificationManager
    private var inferenceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as OceanGuardApp
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_CANCEL) {
            cancelInference()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground immediately (must happen within 5s of startForegroundService)
        startForeground(NOTIFICATION_ID, buildNotification("Starting analysis..."))

        // Cancel any previous job before starting a new one
        inferenceJob?.cancel()

        when (mode) {
            "single" -> {
                val uriString = intent.getStringExtra(EXTRA_URI) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val uri = Uri.parse(uriString)
                app.inferenceServiceState.value = InferenceServiceState.SingleRunning(uri)
                launchSingleInference(uri)
            }
            "batch" -> {
                val uriStrings = intent.getStringArrayListExtra(EXTRA_URI_LIST) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val uris = uriStrings.map { Uri.parse(it) }
                app.inferenceServiceState.value =
                    InferenceServiceState.BatchRunning(uris, 0, emptyList())
                launchBatchInference(uris)
            }
        }

        return START_NOT_STICKY
    }

    // -----------------------------------------------------------------------
    // Single image inference
    // -----------------------------------------------------------------------

    private fun launchSingleInference(uri: Uri) {
        inferenceJob = lifecycleScope.launch {
            // Collect orchestrator state changes for notification updates
            val stateCollector = launch {
                app.detectionOrchestrator.analysisState.collectLatest { state ->
                    val text = analysisStateToNotificationText(state) ?: return@collectLatest
                    updateNotification(text)
                }
            }

            try {
                val skipVLM = !app.settingsRepository.vlmEnabled.first()
                val threshold = app.settingsRepository.confidenceThreshold.first()
                val result = app.detectionOrchestrator.analyzeImage(
                    uri, skipVLM = skipVLM, confidenceThreshold = threshold
                )

                // Persist source image to internal storage so URI survives app restart
                val persistedUri = ImagePersistence.persistImage(applicationContext, uri)

                // Auto-save: prefer EXIF GPS from photo, fall back to device location
                val exifLocation = try { ExifLocationExtractor.extract(applicationContext, uri) } catch (_: Exception) { null }
                val deviceLocation = try { app.locationProvider.getLastKnownLocation() } catch (_: Exception) { null }
                val location = exifLocation ?: deviceLocation
                val annotatedUri = tryAnnotate(uri, result.rtdetrDetections)

                val session = DetectionSession(
                    imageUri = persistedUri,
                    thumbnailUri = annotatedUri,
                    debrisList = result.vlmAnalysis.debrisList,
                    totalCount = result.totalDebrisCount,
                    healthScore = result.healthScore,
                    location = location,
                    imageQuality = result.vlmAnalysis.imageQuality,
                    processingTimeMs = result.processingTimeMs,
                )
                app.repository.saveSession(session)

                app.inferenceServiceState.value = InferenceServiceState.SingleComplete(uri, result)
                updateNotification("Analysis complete! ${result.totalDebrisCount} debris items found.")
                Log.i(TAG, "Single inference complete: ${result.totalDebrisCount} debris in ${result.processingTimeMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Single inference failed", e)
                app.inferenceServiceState.value =
                    InferenceServiceState.Error(e.message ?: "Unknown error")
                updateNotification("Analysis failed.")
            } finally {
                stateCollector.cancel()
                stopSelf()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Batch inference
    // -----------------------------------------------------------------------

    private fun launchBatchInference(uris: List<Uri>) {
        inferenceJob = lifecycleScope.launch {
            val results = mutableListOf<BatchItemResult>()
            val skipVLM = !app.settingsRepository.vlmEnabled.first()
            val threshold = app.settingsRepository.confidenceThreshold.first()
            val timeoutMs = if (skipVLM) BATCH_ITEM_TIMEOUT_NO_VLM_MS else BATCH_ITEM_TIMEOUT_MS

            for ((index, uri) in uris.withIndex()) {
                updateNotification("Image ${index + 1}/${uris.size}: analyzing...")
                app.inferenceServiceState.value =
                    InferenceServiceState.BatchRunning(uris, index, results.toList())

                try {
                    val result = withTimeout(timeoutMs) {
                        app.detectionOrchestrator.analyzeImage(
                            uri, skipVLM = skipVLM, confidenceThreshold = threshold
                        )
                    }

                    // Persist source image to internal storage so URI survives app restart
                    val persistedUri = ImagePersistence.persistImage(applicationContext, uri)

                    // Prefer EXIF GPS from the photo, fall back to device location
                    val exifLocation = try { ExifLocationExtractor.extract(applicationContext, uri) } catch (_: Exception) { null }
                    val deviceLocation = try { app.locationProvider.getLastKnownLocation() } catch (_: Exception) { null }
                    val location = exifLocation ?: deviceLocation
                    val annotatedUri = tryAnnotate(uri, result.rtdetrDetections)

                    val session = DetectionSession(
                        imageUri = persistedUri,
                        thumbnailUri = annotatedUri,
                        debrisList = result.vlmAnalysis.debrisList,
                        totalCount = result.totalDebrisCount,
                        healthScore = result.healthScore,
                        location = location,
                        imageQuality = result.vlmAnalysis.imageQuality,
                        processingTimeMs = result.processingTimeMs,
                    )
                    val sessionId = app.repository.saveSession(session)

                    results.add(BatchItemResult.Done(uri, result, sessionId, annotatedUri))
                    updateNotification("Image ${index + 1}/${uris.size} done. ${result.totalDebrisCount} debris.")
                    Log.i(TAG, "Batch item $index complete: ${result.totalDebrisCount} debris")
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "Batch item $index timed out after ${timeoutMs / 1000}s", e)
                    results.add(BatchItemResult.Failed(uri, "Timed out (${timeoutMs / 60000}min limit)"))
                    updateNotification("Image ${index + 1}/${uris.size} timed out.")
                } catch (e: Exception) {
                    Log.e(TAG, "Batch item $index failed", e)
                    results.add(BatchItemResult.Failed(uri, e.message ?: "Unknown error"))
                }

                // Reset orchestrator state between batch items
                app.detectionOrchestrator.reset()
            }

            app.inferenceServiceState.value = InferenceServiceState.BatchComplete(uris, results)
            val successCount = results.count { it is BatchItemResult.Done }
            updateNotification("Batch complete! $successCount/${uris.size} images processed.")
            Log.i(TAG, "Batch complete: $successCount/${uris.size} succeeded")
            stopSelf()
        }
    }

    // -----------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------

    private fun cancelInference() {
        inferenceJob?.cancel()
        app.inferenceServiceState.value = InferenceServiceState.Idle
        app.detectionOrchestrator.reset()
        Log.i(TAG, "Inference cancelled by user")
        stopSelf()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun tryAnnotate(uri: Uri, detections: List<com.oceanguard.ai.inference.DetectionResult>): String? {
        if (detections.isEmpty()) return null
        return try {
            BitmapAnnotator.annotateAndSave(
                context = applicationContext,
                imageUri = uri,
                detections = detections,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Annotation failed (non-fatal)", e)
            null
        }
    }

    private fun analysisStateToNotificationText(state: AnalysisState): String? = when (state) {
        is AnalysisState.Idle -> null
        is AnalysisState.LoadingImage -> "Loading image..."
        is AnalysisState.Detecting -> "Running AI detection..."
        is AnalysisState.DetectionsReady -> "${state.detections.size} objects found. Starting deep analysis..."
        is AnalysisState.AnalyzingDeep -> "Running deep ecosystem analysis (this may take several minutes)..."
        is AnalysisState.Complete -> "Analysis complete!"
        is AnalysisState.Error -> "Analysis failed: ${state.message}"
    }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OceanGuard Analysis",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress of marine debris analysis"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = Intent(this, InferenceService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OceanGuard AI")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent,
            )
            .build()
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission denied on Android 13+ — non-fatal
            Log.w(TAG, "Cannot update notification: permission denied", e)
        }
    }
}
