package com.oceanguard.ai.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [DetectionSession] persistence.
 *
 * Design notes:
 *  - Reactive queries return [Flow] so the UI / ViewModel layer automatically
 *    receives updated data whenever the underlying table changes. Room handles
 *    the invalidation tracking internally.
 *  - One-shot write operations (insert, update, delete) are `suspend` functions
 *    so callers must invoke them from a coroutine scope, keeping the main thread
 *    free.
 *  - Aggregate queries (counts, averages) also return [Flow] so dashboards
 *    update in real time without manual refresh calls.
 */
@Dao
interface DetectionSessionDao {

    // -----------------------------------------------------------------------
    // Write operations
    // -----------------------------------------------------------------------

    /**
     * Insert a new [DetectionSession] and return the auto-generated row id.
     *
     * [OnConflictStrategy.IGNORE] silently skips if a session with the same
     * [DetectionSession.imageUri] already exists (unique index), returning -1.
     * This prevents duplicate history entries when multiple code paths attempt
     * to save the same inference result.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: DetectionSession): Long

    /**
     * Replace all fields of an existing session matched by its primary key.
     *
     * Useful for adding user notes / tags after the initial detection, or for
     * correcting location data.
     */
    @Update
    suspend fun update(session: DetectionSession)

    /**
     * Remove a single session from the database.
     *
     * Room matches the row by the [DetectionSession.id] primary key; all other
     * fields are ignored.
     */
    @Delete
    suspend fun delete(session: DetectionSession)

    /**
     * Delete every session in the table.
     *
     * Intended for "clear all data" UX flows and test teardown. Irreversible -
     * callers should show a confirmation dialog before invoking.
     */
    @Query("DELETE FROM detection_sessions")
    suspend fun deleteAll()

    // -----------------------------------------------------------------------
    // Batch write operations (multi-select)
    // -----------------------------------------------------------------------

    @Query("UPDATE detection_sessions SET timestamp = :newTimestampMs WHERE id IN (:ids)")
    suspend fun updateTimestampBatch(ids: List<Long>, newTimestampMs: Long)

    @Query("UPDATE detection_sessions SET location = :locationJson WHERE id IN (:ids)")
    suspend fun updateLocationBatch(ids: List<Long>, locationJson: String?)

    @Query("DELETE FROM detection_sessions WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<Long>)

    // -----------------------------------------------------------------------
    // Single-row queries (one-shot suspend)
    // -----------------------------------------------------------------------

    /**
     * Fetch a single session by its primary key, or null if not found.
     *
     * Marked `suspend` (not returning Flow) because it is a one-shot lookup -
     * the session detail screen does not need to react to external changes
     * while it is open.
     */
    @Query("SELECT * FROM detection_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): DetectionSession?

    // -----------------------------------------------------------------------
    // Reactive list queries (Flow)
    // -----------------------------------------------------------------------

    /**
     * Emit the full, timestamp-descending list of sessions.
     *
     * The newest captures appear first, matching the expected UX for a history
     * feed. Room re-emits whenever any row in the table is inserted, updated,
     * or deleted.
     */
    @Query("SELECT * FROM detection_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<DetectionSession>>

    /**
     * Emit sessions whose timestamp falls within [startMs]..[endMs] (both
     * inclusive, epoch milliseconds).
     *
     * The `timestamp` column is stored as Long by [com.oceanguard.ai.data.converters.DateConverter],
     * so a direct numeric comparison is valid and index-friendly.
     *
     * Example – fetch sessions from the current week:
     * ```kotlin
     * val weekStart = ...
     * val weekEnd   = System.currentTimeMillis()
     * dao.getSessionsByDateRange(weekStart, weekEnd)
     * ```
     */
    @Query(
        """
        SELECT * FROM detection_sessions
        WHERE  timestamp BETWEEN :startMs AND :endMs
        ORDER  BY timestamp DESC
        """
    )
    fun getSessionsByDateRange(startMs: Long, endMs: Long): Flow<List<DetectionSession>>

    /**
     * Emit sessions captured with camera or real-time mode.
     * Excludes batch-uploaded sessions (tag "source:batch") and demo tours.
     * Used for achievement evaluation to count only active field captures.
     */
    @Query("""
        SELECT * FROM detection_sessions
        WHERE (tags IS NULL OR tags NOT LIKE '%source:batch%')
          AND (tags IS NULL OR tags NOT LIKE '%demo:tour%')
        ORDER BY timestamp DESC
    """)
    fun getCameraAllSessions(): Flow<List<DetectionSession>>

    /**
     * Emit the count of camera/real-time sessions (excludes batch uploads and demo tours).
     * Used by [AchievementChecker] to count only active field captures for scan achievements.
     */
    @Query("""
        SELECT COUNT(*) FROM detection_sessions
        WHERE (tags IS NULL OR tags NOT LIKE '%source:batch%')
          AND (tags IS NULL OR tags NOT LIKE '%demo:tour%')
    """)
    fun getCameraSessionCount(): Flow<Int>

    // -----------------------------------------------------------------------
    // Aggregate / statistics queries (Flow)
    // -----------------------------------------------------------------------

    /**
     * Emit the total number of sessions ever recorded.
     *
     * Used in the dashboard summary card and in [DetectionStatistics].
     */
    @Query("SELECT COUNT(*) FROM detection_sessions")
    fun getTotalSessionCount(): Flow<Int>

    /**
     * Emit the mean health score across all sessions, or null if the table is
     * empty (AVG returns NULL on an empty set in SQLite).
     *
     * Health scores range 0-100, where 100 represents pristine water with no
     * detected debris.
     */
    @Query("SELECT AVG(healthScore) FROM detection_sessions")
    fun getAverageHealthScore(): Flow<Float?>

    /**
     * Emit the sum of [DetectionSession.totalCount] across all sessions.
     *
     * This represents the cumulative number of individual debris objects
     * detected, not the number of capture sessions.
     *
     * COALESCE converts the SQL NULL (empty table) to 0, keeping the return
     * type non-nullable [Int] instead of [Int?].
     */
    @Query("SELECT COALESCE(SUM(totalCount), 0) FROM detection_sessions")
    fun getTotalDebrisCount(): Flow<Int>

    /**
     * Emit sessions whose JSON [DetectionSession.debrisList] blob contains the
     * given debris type name string (case-sensitive enum name match).
     *
     * Uses a LIKE substring match against the serialised JSON column — sufficient
     * because [DebrisType] enum names are unique identifiers without ambiguous
     * sub-strings (e.g. "BOTTLE" does not appear inside any other enum name).
     *
     * Results are ordered newest-first to match the UX convention of the rest
     * of the session history.
     *
     * @param debrisTypeName The [DebrisType.name] string to search for.
     */
    @Query("SELECT * FROM detection_sessions WHERE debrisList LIKE '%' || :debrisTypeName || '%' ORDER BY timestamp DESC")
    fun getSessionsContainingDebrisType(debrisTypeName: String): Flow<List<DetectionSession>>

    // -----------------------------------------------------------------------
    // Demo data helpers
    // -----------------------------------------------------------------------

    @Query("DELETE FROM detection_sessions WHERE tags LIKE '%demo:tour%'")
    suspend fun deleteDemoSessions()
}
