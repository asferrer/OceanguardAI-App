package com.oceanguard.ai.data.species

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [SpeciesObservation].
 *
 * All Flow-returning queries are collected on the main thread via Compose's
 * collectAsStateWithLifecycle; suspend queries run on Dispatchers.IO at the
 * call site (repository layer).
 */
@Dao
interface SpeciesObservationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(observation: SpeciesObservation): Long

    @Query("SELECT * FROM species_observations ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SpeciesObservation>>

    @Query("SELECT * FROM species_observations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SpeciesObservation?

    /** All observations that link to a given [sessionId]. */
    @Query("SELECT * FROM species_observations WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun getBySessionId(sessionId: Long): Flow<List<SpeciesObservation>>

    /**
     * All observations for a given species key.
     * The key is matched against [SpeciesObservation.aphiaId] (if numeric)
     * or via the scientificName stored in [SpeciesDexEntry].
     * The caller filters by speciesKey; this query works per aphiaId string.
     */
    @Query("""
        SELECT * FROM species_observations
        WHERE CAST(aphiaId AS TEXT) = :aphiaIdStr
        ORDER BY timestamp DESC
    """)
    fun getByAphiaId(aphiaIdStr: String): Flow<List<SpeciesObservation>>

    @Query("SELECT COUNT(*) FROM species_observations")
    fun getTotalCount(): Flow<Int>

    @Query("DELETE FROM species_observations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM species_observations ORDER BY timestamp ASC")
    suspend fun getAllAscending(): List<SpeciesObservation>
}
