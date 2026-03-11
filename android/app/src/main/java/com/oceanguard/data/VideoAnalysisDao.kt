package com.oceanguard.ai.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoAnalysisDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(analysis: VideoAnalysis): Long

    @Update
    suspend fun update(analysis: VideoAnalysis)

    @Delete
    suspend fun delete(analysis: VideoAnalysis)

    @Query("SELECT * FROM video_analyses WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VideoAnalysis?

    @Query("SELECT * FROM video_analyses ORDER BY timestamp DESC")
    fun getAll(): Flow<List<VideoAnalysis>>

    @Query("SELECT COUNT(*) FROM video_analyses")
    fun getTotalCount(): Flow<Int>

    @Query("DELETE FROM video_analyses")
    suspend fun deleteAll()
}
