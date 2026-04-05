package com.oceanguard.ai.data.contribution

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContributionQueueDao {

    @Insert
    suspend fun insert(item: ContributionQueueItem): Long

    @Query("SELECT * FROM contribution_queue WHERE status = 'PENDING'")
    suspend fun getPending(): List<ContributionQueueItem>

    @Query("UPDATE contribution_queue SET status = :status, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /** Increment retry count without changing status (keeps item PENDING for next attempt). */
    @Query("UPDATE contribution_queue SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("SELECT COUNT(*) FROM contribution_queue WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM contribution_queue WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM contribution_queue WHERE status = 'DONE'")
    suspend fun getDoneCount(): Int

    @Query("SELECT COUNT(*) FROM contribution_queue WHERE status = 'DONE'")
    fun getDoneCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM contribution_queue WHERE status = 'FAILED'")
    fun getFailedCountFlow(): Flow<Int>

    @Query("SELECT status FROM contribution_queue WHERE imageUri = :uri ORDER BY id DESC LIMIT 1")
    suspend fun getStatusForUri(uri: String): String?

    @Query("SELECT status FROM contribution_queue WHERE imageUri = :uri ORDER BY id DESC LIMIT 1")
    fun getStatusFlowForUri(uri: String): Flow<String?>

    /** Fetch one pending item at a time to avoid OOM on large queues. */
    @Query("SELECT * FROM contribution_queue WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextPending(): ContributionQueueItem?

    /** Items that exceeded max retries — mark as permanently failed. */
    @Query("UPDATE contribution_queue SET status = 'FAILED' WHERE status = 'PENDING' AND retryCount >= :maxRetries")
    suspend fun failExceededRetries(maxRetries: Int)

    /** Purge completed and failed items older than [cutoffMs] epoch timestamp. */
    @Query("DELETE FROM contribution_queue WHERE status IN ('DONE', 'FAILED') AND createdAt < :cutoffMs")
    suspend fun purgeOlderThan(cutoffMs: Long)

    /** Delete all pending items (used when user revokes consent). */
    @Query("DELETE FROM contribution_queue WHERE status = 'PENDING'")
    suspend fun deletePending()

    /** Delete all items (used by reset all data). */
    @Query("DELETE FROM contribution_queue")
    suspend fun deleteAll()
}
