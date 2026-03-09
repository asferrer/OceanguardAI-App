package com.oceanguard.ai.data.collection

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievements ORDER BY unlockedAt DESC")
    fun getAll(): Flow<List<Achievement>>

    @Query("SELECT * FROM achievements WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Achievement?

    @Query("SELECT COUNT(*) FROM achievements WHERE unlockedAt > 0")
    fun getUnlockedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(achievement: Achievement)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(achievements: List<Achievement>)

    @Query("DELETE FROM achievements")
    suspend fun deleteAll()

    @Query("UPDATE achievements SET progress = 0, unlockedAt = 0")
    suspend fun resetAllProgress()
}
