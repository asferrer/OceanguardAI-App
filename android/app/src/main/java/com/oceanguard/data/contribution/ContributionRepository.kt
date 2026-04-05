package com.oceanguard.ai.data.contribution

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.service.DataContributionWorker
import com.oceanguard.ai.utils.CocoAnnotationSerializer
import com.oceanguard.ai.utils.WebDavUploadClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class ContributionRepository(
    private val dao: ContributionQueueDao,
    private val settings: SettingsRepository,
    private val context: Context,
) {
    /**
     * Called by InferenceService after saving a session.
     * Returns true if enqueued (consent given + NAS configured at build time),
     * false if UI should prompt user.
     */
    suspend fun maybeEnqueue(session: DetectionSession): Boolean {
        if (!settings.contributeConsentGiven.first()) return false
        if (!WebDavUploadClient.isConfigured) return false
        return enqueueSession(session)
    }

    /**
     * Direct enqueue — called when user accepts the contribution prompt or taps the
     * upload button in SessionDetailScreen. Duplicate-safe.
     * Returns false if COCO serialization fails (missing image, invalid dimensions).
     */
    suspend fun enqueueSession(session: DetectionSession): Boolean {
        val existing = dao.getStatusForUri(session.imageUri)
        if (existing == ContributionStatus.PENDING.name || existing == ContributionStatus.DONE.name) return true
        val json = CocoAnnotationSerializer.build(context, session) ?: return false
        dao.insert(
            ContributionQueueItem(
                sessionId = session.id,
                imageUri = session.imageUri,
                annotationsJson = json,
                status = ContributionStatus.PENDING,
            )
        )
        scheduleWorker()
        return true
    }

    /** Clear all pending items (called when user revokes contribution consent). */
    suspend fun clearPendingQueue() {
        dao.deletePending()
    }

    /** Clear entire queue (called by reset all data). */
    suspend fun clearAll() {
        dao.deleteAll()
    }

    fun getPendingCountFlow(): Flow<Int> = dao.getPendingCountFlow()
    fun getDoneCountFlow(): Flow<Int> = dao.getDoneCountFlow()
    fun getFailedCountFlow(): Flow<Int> = dao.getFailedCountFlow()

    fun getStatusFlowForUri(uri: String): Flow<String?> = dao.getStatusFlowForUri(uri)

    /** Enqueue an immediate upload (any network, bypass WiFi-only constraint). */
    fun scheduleImmediateUpload() {
        val req = OneTimeWorkRequestBuilder<DataContributionWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }

    private suspend fun scheduleWorker() {
        val wifiOnly = settings.contributeWifiOnly.first()
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<DataContributionWorker>()
            .setConstraints(Constraints(requiredNetworkType = networkType))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("data_contribution", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}
