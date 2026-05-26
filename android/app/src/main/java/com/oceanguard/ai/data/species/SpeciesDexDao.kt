package com.oceanguard.ai.data.species

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [SpeciesDexEntry].
 *
 * Mirrors [com.oceanguard.ai.data.collection.MarineDexDao] for the species
 * track. Each [SpeciesDexEntry.speciesKey] is unique (PK).
 */
@Dao
interface SpeciesDexDao {

    @Query("SELECT * FROM species_dex_entries ORDER BY lastSeenAt DESC")
    fun getAll(): Flow<List<SpeciesDexEntry>>

    @Query("SELECT * FROM species_dex_entries WHERE speciesKey = :key LIMIT 1")
    suspend fun getByKey(key: String): SpeciesDexEntry?

    @Query("SELECT COUNT(*) FROM species_dex_entries")
    fun getDiscoveredCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM species_dex_entries WHERE isFavorite = 1")
    fun getFavoriteCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(timesObserved), 0) FROM species_dex_entries")
    fun getTotalObservations(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SpeciesDexEntry)

    @Query("UPDATE species_dex_entries SET isFavorite = :fav WHERE speciesKey = :key")
    suspend fun setFavorite(key: String, fav: Boolean)

    @Query("DELETE FROM species_dex_entries")
    suspend fun deleteAll()

    @Query("DELETE FROM species_dex_entries WHERE speciesKey = :key")
    suspend fun deleteByKey(key: String)
}
