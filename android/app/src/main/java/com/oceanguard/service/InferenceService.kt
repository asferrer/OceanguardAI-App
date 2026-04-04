package com.oceanguard.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.VideoAnalysis
import com.oceanguard.ai.inference.AnalysisState
import com.oceanguard.ai.inference.VideoProcessor
import com.oceanguard.ai.ui.MainActivity
import com.oceanguard.ai.utils.BitmapAnnotator
import com.oceanguard.ai.utils.ExifLocationExtractor
import com.oceanguard.ai.utils.ImagePersistence
import com.oceanguard.ai.utils.VideoPersistence
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Foreground Service that runs inference jobs sequentially from a queue.
 *
 * New jobs are enqueued via [onStartCommand]. When the current job finishes,
 * the next job in the queue is automatically started. The service stops itself
 * only when the queue is empty and no job is running.
 *
 * State is shared with the UI via [OceanGuardApp.inferenceServiceState].
 */
class InferenceService : LifecycleService() {

    companion object {
        private const val TAG = "InferenceService"
        const val CHANNEL_ID = "oceanguard_inference"
        private const val NOTIFICATION_ID = 1001

        private const val BATCH_ITEM_TIMEOUT_MS = 10L * 60 * 1000
        private const val BATCH_ITEM_TIMEOUT_NO_VLM_MS = 2L * 60 * 1000

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_URI_LIST = "uri_list"
        const val ACTION_CANCEL = "com.oceanguard.ai.CANCEL_INFERENCE"
        const val EXTRA_DEEP_LINK_ROUTE = "deep_link_route"

        fun singleImageIntent(context: Context, uri: Uri): Intent =
            Intent(context, InferenceService::class.java).apply {
                putExtra(EXTRA_MODE, "single")
                putExtra(EXTRA_URI, uri.toString())
            }

        fun batchIntent(context: Context, uris: List<Uri>): Intent =
            Intent(context, InferenceService::class.java).apply {
                putExtra(EXTRA_MODE, "batch")
                putStringArrayListExtra(
                    EXTRA_URI_LIST, ArrayList(uris.map { it.toString() })
                )
            }

        fun videoIntent(context: Context, uri: Uri): Intent =
            Intent(context, InferenceService::class.java).apply {
                putExtra(EXTRA_MODE, "video")
                putExtra(EXTRA_URI, uri.toString())
            }
    }

    private lateinit var app: OceanGuardApp
    private lateinit var notificationManager: NotificationManager
    private var inferenceJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var videoProcessor: VideoProcessor? = null
    private val jobQueue = InferenceJobQueue()

    override fun onCreate() {
        super.onCreate()
        app = application as OceanGuardApp
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_CANCEL) {
            cancelAll()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: run {
            if (jobQueue.totalCount == 0) stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground immediately
        startForeground(NOTIFICATION_ID, buildNotification("Preparing..."))

        // Acquire wake lock to prevent CPU throttling when screen is off / app is backgrounded
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OceanGuard:InferenceWakeLock")
                .also { it.acquire(60 * 60 * 1000L) } // 60-min safety timeout
        }

        // Parse intent into job(s) and enqueue
        enqueueFromIntent(mode, intent)

        // If no job is currently running, start processing the queue
        if (jobQueue.currentJob == null) {
            processNextJob()
        } else {
            // Update notification to show queue count
            val pending = jobQueue.pendingCount
            if (pending > 0) {
                updateNotification("Processing... ($pending queued)")
            }
        }

        return START_NOT_STICKY
    }

    private fun enqueueFromIntent(mode: String, intent: Intent) {
        when (mode) {
            "single" -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
                    ?: return
                jobQueue.enqueue(InferenceJob.SingleImage(uri))
                Log.i(TAG, "Enqueued single image job (queue: ${jobQueue.totalCount})")
            }
            "batch" -> {
                val uriStrings = intent.getStringArrayListExtra(EXTRA_URI_LIST)
                    ?: return
                val uris = uriStrings.map { Uri.parse(it) }
                jobQueue.enqueue(InferenceJob.ImageBatch(uris))
                Log.i(TAG, "Enqueued batch job: ${uris.size} images (queue: ${jobQueue.totalCount})")
            }
            "video" -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
                    ?: return
                jobQueue.enqueue(InferenceJob.Video(uri))
                Log.i(TAG, "Enqueued video job (queue: ${jobQueue.totalCount})")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Queue processing loop
    // -----------------------------------------------------------------------

    private fun processNextJob() {
        val job = jobQueue.dequeue()
        if (job == null) {
            Log.i(TAG, "Queue empty — stopping service")
            // Do NOT transition to Idle here — leave the last Complete state intact so
            // that LaunchedEffect guards in the UI can still see it and block accidental
            // re-launches triggered by recompositions or Activity recreation.
            // Idle is set explicitly by resetState() or the next analyzeMedia() call.
            stopSelf()
            return
        }

        Log.i(TAG, "Starting job ${job.id} (${job.typeLabel}), ${jobQueue.pendingCount} pending")

        when (job) {
            is InferenceJob.SingleImage -> launchSingleInference(job)
            is InferenceJob.ImageBatch -> launchBatchInference(job)
            is InferenceJob.Video -> launchVideoInference(job)
        }
    }

    private fun buildQueueInfo(job: InferenceJob) = QueueInfo(
        currentJobId = job.id,
        pendingCount = jobQueue.pendingCount,
        deepLinkRoute = job.deepLinkRoute,
    )

    // -----------------------------------------------------------------------
    // Single image inference
    // -----------------------------------------------------------------------

    private fun launchSingleInference(job: InferenceJob.SingleImage) {
        inferenceJob = lifecycleScope.launch {
            val stateCollector = launch {
                app.detectionOrchestrator.analysisState.collectLatest { state ->
                    val text = analysisStateToNotificationText(state)
                        ?: return@collectLatest
                    updateNotification(text, job.deepLinkRoute)
                }
            }

            try {
                app.inferenceServiceState.value =
                    InferenceServiceState.SingleRunning(job.uri, buildQueueInfo(job))

                val skipVLM = !app.settingsRepository.vlmEnabled.first()
                val threshold = app.settingsRepository.confidenceThreshold.first()
                val result = app.detectionOrchestrator.analyzeImage(
                    job.uri, skipVLM = skipVLM, confidenceThreshold = threshold
                )

                val persistedUri = ImagePersistence.persistImage(applicationContext, job.uri)
                val exifLocation = try { ExifLocationExtractor.extract(applicationContext, job.uri) } catch (_: Exception) { null }
                val deviceLocation = try { app.locationProvider.getLastKnownLocation() } catch (_: Exception) { null }
                val location = exifLocation ?: deviceLocation
                val annotatedUri = tryAnnotate(job.uri, result.rtdetrDetections)

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
                val savedSession = session.copy(id = sessionId)
                val contributionQueued = app.contributionRepository.maybeEnqueue(savedSession)

                app.inferenceServiceState.value = InferenceServiceState.SingleComplete(
                    uri = job.uri,
                    result = result,
                    sessionId = sessionId,
                    contributionQueued = contributionQueued,
                )
                updateNotification(
                    "Complete! ${result.totalDebrisCount} debris found.",
                    job.deepLinkRoute,
                )
                Log.i(TAG, "Single inference complete: ${result.totalDebrisCount} debris")
            } catch (e: Exception) {
                Log.e(TAG, "Single inference failed", e)
                app.inferenceServiceState.value =
                    InferenceServiceState.Error(e.message ?: "Unknown error")
            } finally {
                stateCollector.cancel()
                jobQueue.clearCurrent()
                processNextJob()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Batch inference
    // -----------------------------------------------------------------------

    private fun launchBatchInference(job: InferenceJob.ImageBatch) {
        inferenceJob = lifecycleScope.launch {
            val results = mutableListOf<BatchItemResult>()
            val skipVLM = !app.settingsRepository.vlmEnabled.first()
            val threshold = app.settingsRepository.confidenceThreshold.first()
            val timeoutMs = if (skipVLM) BATCH_ITEM_TIMEOUT_NO_VLM_MS else BATCH_ITEM_TIMEOUT_MS

            for ((index, uri) in job.uris.withIndex()) {
                updateNotification(
                    "Image ${index + 1}/${job.uris.size}: analyzing...",
                    job.deepLinkRoute,
                )
                app.inferenceServiceState.value = InferenceServiceState.BatchRunning(
                    job.uris, index, results.toList(), buildQueueInfo(job),
                )

                try {
                    val result = withTimeout(timeoutMs) {
                        app.detectionOrchestrator.analyzeImage(
                            uri, skipVLM = skipVLM, confidenceThreshold = threshold
                        )
                    }

                    val persistedUri = ImagePersistence.persistImage(applicationContext, uri)
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
                        tags = "source:batch",
                    )
                    val sessionId = app.repository.saveSession(session)
                    app.contributionRepository.maybeEnqueue(session.copy(id = sessionId))
                    results.add(BatchItemResult.Done(uri, result, sessionId, annotatedUri))
                } catch (e: TimeoutCancellationException) {
                    results.add(BatchItemResult.Failed(uri, "Timed out"))
                } catch (e: CancellationException) {
                    throw e  // never swallow — lets cancelAll() stop the loop immediately
                } catch (e: Exception) {
                    results.add(BatchItemResult.Failed(uri, e.message ?: "Unknown error"))
                }
                app.detectionOrchestrator.reset()
            }

            val successCount = results.count { it is BatchItemResult.Done }
            val consentGiven = app.settingsRepository.contributeConsentGiven.first()
            val contributionQueuedCount = if (consentGiven) successCount else 0
            app.inferenceServiceState.value =
                InferenceServiceState.BatchComplete(job.uris, results, contributionQueuedCount)
            updateNotification(
                "Batch done! $successCount/${job.uris.size} processed.",
                job.deepLinkRoute,
            )
            Log.i(TAG, "Batch complete: $successCount/${job.uris.size}")

            jobQueue.clearCurrent()
            processNextJob()
        }
    }

    // -----------------------------------------------------------------------
    // Video inference
    // -----------------------------------------------------------------------

    private fun launchVideoInference(job: InferenceJob.Video) {
        inferenceJob = lifecycleScope.launch {
            try {
                val threshold = app.settingsRepository.confidenceThreshold.first()
                val processor = VideoProcessor(
                    context = applicationContext,
                    detector = app.rtdetrInference,
                    confidenceThreshold = threshold,
                )
                videoProcessor = processor

                // Share processor reference for live preview
                app.currentVideoProcessor.value = processor

                app.inferenceServiceState.value = InferenceServiceState.VideoRunning(
                    job.uri, 0, 0, 0L, 0L, buildQueueInfo(job),
                )

                @OptIn(FlowPreview::class)
                val progressCollector = launch {
                    processor.progress
                        .debounce(200L)
                        .collectLatest { progress ->
                        if (progress.totalFrames > 0) {
                            val remainMin = progress.estimatedRemainingMs / 60000
                            val remainSec = (progress.estimatedRemainingMs % 60000) / 1000
                            val text = "Frame ${progress.currentFrame}/${progress.totalFrames} — ~${remainMin}m ${remainSec}s"
                            updateNotification(text, job.deepLinkRoute)
                            app.inferenceServiceState.value =
                                InferenceServiceState.VideoRunning(
                                    job.uri, progress.currentFrame, progress.totalFrames,
                                    progress.elapsedTimeMs, progress.estimatedRemainingMs,
                                    buildQueueInfo(job),
                                )
                        }
                    }
                }

                val persistedUri = VideoPersistence.persistVideo(applicationContext, job.uri)
                val result = processor.processVideo(Uri.parse(persistedUri))
                val location = try { app.locationProvider.getLastKnownLocation() } catch (_: Exception) { null }

                val analysis = VideoAnalysis(
                    sourceVideoUri = persistedUri,
                    outputVideoUri = result.outputVideoUri,
                    thumbnailUri = result.thumbnailUri,
                    durationMs = result.durationMs,
                    totalFrameCount = result.totalFrameCount,
                    processedFrameCount = result.processedFrameCount,
                    uniqueDebrisCount = result.uniqueDebrisCount,
                    classCounts = Gson().toJson(result.classCounts),
                    totalProcessingTimeMs = result.totalProcessingTimeMs,
                    avgInferenceTimeMs = result.avgInferenceTimeMs,
                    healthScore = result.healthScore,
                    location = location,
                    status = "complete",
                )
                val analysisId = app.videoAnalysisDao.insert(analysis)

                app.inferenceServiceState.value = InferenceServiceState.VideoComplete(
                    analysisId, result.outputVideoUri,
                    result.uniqueDebrisCount, result.totalProcessingTimeMs,
                )
                updateNotification(
                    "Video done! ${result.uniqueDebrisCount} unique debris.",
                    "video_detail/$analysisId",
                )
                Log.i(TAG, "Video complete: ${result.uniqueDebrisCount} unique debris")
                progressCollector.cancel()
            } catch (e: java.util.concurrent.CancellationException) {
                Log.i(TAG, "Video inference cancelled")
                app.inferenceServiceState.value = InferenceServiceState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Video inference failed", e)
                app.inferenceServiceState.value =
                    InferenceServiceState.Error(e.message ?: "Video processing failed")
            } finally {
                videoProcessor = null
                app.currentVideoProcessor.value = null
                jobQueue.clearCurrent()
                processNextJob()
            }
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------

    private fun cancelAll() {
        videoProcessor?.isCancelled = true
        inferenceJob?.cancel()
        val removed = jobQueue.cancelAll()
        app.currentVideoProcessor.value = null
        app.inferenceServiceState.value = InferenceServiceState.Idle
        app.detectionOrchestrator.reset()
        Log.i(TAG, "Cancelled: current job + ${removed.size} queued jobs")
        stopSelf()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun tryAnnotate(
        uri: Uri,
        detections: List<com.oceanguard.ai.inference.DetectionResult>,
    ): String? {
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

    private fun analysisStateToNotificationText(state: AnalysisState): String? =
        when (state) {
            is AnalysisState.Idle -> null
            is AnalysisState.LoadingImage -> "Loading image..."
            is AnalysisState.Detecting -> "Running AI detection..."
            is AnalysisState.DetectionsReady ->
                "${state.detections.size} objects found. Starting deep analysis..."
            is AnalysisState.AnalyzingDeep ->
                "Running deep ecosystem analysis (this may take several minutes)..."
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

    private fun buildNotification(
        contentText: String,
        deepLinkRoute: String = "home",
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DEEP_LINK_ROUTE, deepLinkRoute)
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

        // Show queue count in title if there are pending jobs
        val pending = jobQueue.pendingCount
        val title = if (pending > 0) {
            "OceanGuard AI ($pending queued)"
        } else {
            "OceanGuard AI"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
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

    private fun updateNotification(text: String, deepLinkRoute: String = "home") {
        try {
            notificationManager.notify(
                NOTIFICATION_ID, buildNotification(text, deepLinkRoute)
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot update notification: permission denied", e)
        }
    }
}
