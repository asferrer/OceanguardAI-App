package com.oceanguard.ai.data.collection

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MarineDexDao {

    @Query("SELECT * FROM marine_dex_entries ORDER BY lastSeenAt DESC")
    fun getAll(): Flow<List<MarineDexEntry>>

    @Query("SELECT * FROM marine_dex_entries WHERE debrisType = :type LIMIT 1")
    suspend fun getByType(type: String): MarineDexEntry?

    @Query("SELECT COUNT(*) FROM marine_dex_entries")
    fun getDiscoveredCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM marine_dex_entries WHERE isFavorite = 1")
    fun getFavoriteCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(timesDetected), 0) FROM marine_dex_entries")
    fun getTotalDetections(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MarineDexEntry)

    @Query("UPDATE marine_dex_entries SET isFavorite = :fav WHERE debrisType = :type")
    suspend fun setFavorite(type: String, fav: Boolean)

    @Query("DELETE FROM marine_dex_entries")
    suspend fun deleteAll()

    @Query("DELETE FROM marine_dex_entries WHERE debrisType = :type")
    suspend fun deleteByType(type: String)
}
