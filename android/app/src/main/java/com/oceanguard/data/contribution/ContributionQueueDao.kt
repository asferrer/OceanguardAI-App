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

    @Query("SELECT COUNT(*) FROM contribution_queue WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM contribution_queue WHERE status = 'DONE'")
    suspend fun getDoneCount(): Int

    @Query("SELECT status FROM contribution_queue WHERE imageUri = :uri LIMIT 1")
    suspend fun getStatusForUri(uri: String): String?

    @Query("SELECT status FROM contribution_queue WHERE imageUri = :uri LIMIT 1")
    fun getStatusFlowForUri(uri: String): Flow<String?>
}
