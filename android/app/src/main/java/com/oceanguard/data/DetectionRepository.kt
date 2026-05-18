package com.oceanguard.ai.data

import android.util.Log
import com.google.gson.GsonBuilder
import com.oceanguard.ai.data.collection.AchievementChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.Date

/**
 * Repository that mediates between the rest of the application and the Room
 * database layer.
 *
 * ## Responsibilities
 *  - Exposes reactive [Flow] streams for the UI / ViewModel layer.
 *  - Provides suspend functions for one-shot write and read operations.
 *  - Builds [DetectionStatistics] aggregates by combining multiple DAO flows.
 *  - Hides Room-specific types (DAOs, database classes) from upper layers.
 *
 * ## Instantiation
 *
 * Construct with the DAO obtained from [OceanGuardDatabase]:
 * ```kotlin
 * val db  = OceanGuardDatabase.getInstance(context)
 * val repo = DetectionRepository(db.detectionSessionDao())
 * ```
 * In production, wire through a dependency-injection framework (Hilt, Koin, or
 * manual DI in an Application subclass) so the same instance is shared across
 * the process lifetime.
 *
 * @param dao The DAO instance supplied by the database. Injected rather than
 *            created here to allow easy substitution of a fake DAO in unit tests.
 */
class DetectionRepository(
    private val dao: DetectionSessionDao,
    private val achievementChecker: AchievementChecker? = null,
) {

    // -----------------------------------------------------------------------
    // Reactive streams – consumed by ViewModels via collectAsState / collectLatest
    // -----------------------------------------------------------------------

    /**
     * All detection sessions ordered by timestamp descending (newest first).
     *
     * The Flow is hot in the sense that Room keeps it alive as long as there is
     * at least one active collector; no manual refresh is needed.
     */
    val allSessions: Flow<List<DetectionSession>> = dao.getAllSessions()

    val totalSessionCount: Flow<Int> = dao.getTotalSessionCount()

    val averageHealthScore: Flow<Float?> = dao.getAverageHealthScore()

    /**
     * Live statistics aggregated from the entire session history.
     *
     * Computed by combining three independent DAO flows so the returned
     * [DetectionStatistics] re-emits whenever any of its inputs changes.
     *
     * Note on [DetectionStatistics.materialBreakdown] and
     * [DetectionStatistics.hotspots]: these require iterating over all sessions
     * and cannot be expressed as pure SQL aggregates without complex JSON
     * parsing in SQLite. They are derived from [allSessions] in the [map]
     * operator to keep DAO queries simple.
     *
     * Note on [DetectionStatistics.dateRange]: computed from [allSessions]
     * which is already ordered DESC; the first element holds the most recent
     * date and the last element holds the oldest.
     */
    val statistics: Flow<DetectionStatistics> =
        combine(
            dao.getTotalSessionCount(),
            dao.getTotalDebrisCount(),
            dao.getAverageHealthScore()
        ) { count, debrisTotal, avgHealth ->
            Triple(count, debrisTotal, avgHealth)
        }.combine(allSessions) { (count, debrisTotal, avgHealth), sessions ->
            buildStatistics(
                totalSessions = count,
                totalDebrisDetected = debrisTotal,
                avgHealthScore = avgHealth ?: 0f,
                sessions = sessions
            )
        }

    // -----------------------------------------------------------------------
    // Write operations
    // -----------------------------------------------------------------------

    /**
     * Persist a new [DetectionSession] and return the auto-generated row id.
     *
     * Must be called from a coroutine (e.g. inside [androidx.lifecycle.viewModelScope]).
     * The database write is dispatched on the coroutine context provided by the
     * caller; Room internally switches to its own executor for the actual I/O.
     *
     * @param session The session to save. Its [DetectionSession.id] must be 0
     *                (the Room default) to trigger auto-generation.
     * @return The newly assigned primary key.
     */
    suspend fun saveSession(session: DetectionSession, skipAchievements: Boolean = false): Long {
        val id = dao.insert(session)
        if (!skipAchievements) {
            try {
                achievementChecker?.processSession(session.copy(id = id))
            } catch (e: Exception) {
                Log.e("DetectionRepository", "Achievement check failed (non-fatal)", e)
            }
        }
        return id
    }

    /**
     * Overwrite all fields of an existing session matched by its primary key.
     *
     * Typical use cases: attaching user notes, correcting GPS coordinates, or
     * updating tags after initial save.
     *
     * @param session Must carry a non-zero [DetectionSession.id] that already
     *                exists in the database; otherwise Room silently does nothing.
     */
    suspend fun updateSession(session: DetectionSession) = dao.update(session)

    /**
     * Remove a single session from the database.
     *
     * @param session Matched by [DetectionSession.id]; all other fields are
     *                ignored by Room.
     */
    suspend fun deleteSession(session: DetectionSession) = dao.delete(session)

    // -----------------------------------------------------------------------
    // Batch write operations (multi-select)
    // -----------------------------------------------------------------------

    private val batchGson by lazy { GsonBuilder().serializeNulls().create() }

    suspend fun updateSessionsTimestamp(ids: List<Long>, newTimestamp: Date) {
        dao.updateTimestampBatch(ids, newTimestamp.time)
    }

    suspend fun updateSessionsLocation(ids: List<Long>, location: Location?) {
        val json = if (location != null) batchGson.toJson(location, Location::class.java) else null
        dao.updateLocationBatch(ids, json)
    }

    suspend fun deleteSessions(ids: List<Long>) = dao.deleteBatch(ids)

    /**
     * Remove every session from the database.
     *
     * Irreversible. The caller is responsible for presenting a confirmation
     * dialog to the user before invoking this method.
     */
    suspend fun clearAllSessions() = dao.deleteAll()

    /** Remove only sessions tagged as demo data. */
    suspend fun deleteDemoSessions() = dao.deleteDemoSessions()

    // -----------------------------------------------------------------------
    // One-shot read operations
    // -----------------------------------------------------------------------

    /**
     * Fetch a single session by its primary key, suspending until the database
     * responds.
     *
     * Returns null if no row with the given [id] exists.
     */
    suspend fun getSession(id: Long): DetectionSession? = dao.getSessionById(id)

    // -----------------------------------------------------------------------
    // Filtered reactive streams
    // -----------------------------------------------------------------------

    /**
     * Emit sessions whose timestamp falls within the supplied date range.
     *
     * Both bounds are inclusive. The [Flow] re-emits on any table change, so
     * newly inserted sessions that fall within the range appear automatically.
     *
     * @param start Beginning of the range (inclusive).
     * @param end   End of the range (inclusive).
     */
    fun getSessionsByDateRange(start: Date, end: Date): Flow<List<DetectionSession>> =
        dao.getSessionsByDateRange(startMs = start.time, endMs = end.time)

    /**
     * Convenience overload accepting epoch millisecond values directly.
     *
     * Useful when the caller already holds timestamps as Long (e.g. from a
     * date-picker that returns millis).
     */
    fun getSessionsByDateRange(startMs: Long, endMs: Long): Flow<List<DetectionSession>> =
        dao.getSessionsByDateRange(startMs, endMs)

    /**
     * Emit sessions that contain at least one [Debris] item whose
     * canonical type matches [type], ordered newest-first.
     *
     * Why client-side filtering instead of a tight LIKE query:
     *  - The previous DAO query `LIKE '%BOTTLE%'` over-matched, because the
     *    JSON-serialised debrisList contains BOTTLE not only in `type:"BOTTLE"`
     *    but also in `subType:"GLASS_BOTTLE"` and `rawLabel:"glass_bottle"`.
     *    Result: a glass-bottle detection (type GLASS_DEBRIS) leaked into the
     *    MarineDex BOTTLE gallery — user-reported on 2026-05-18.
     *  - A strict `LIKE '%"type":"BOTTLE"%'` would fix that one case but
     *    wouldn't expand correctly for canonical types: tapping
     *    PLASTIC_DEBRIS in MarineDex should also surface sessions whose
     *    debris is BOTTLE_CAP / PLASTIC_BAG / STRAW / etc., because those
     *    canonicalise to PLASTIC_DEBRIS.
     *
     * Implementation: pull every session via [DetectionSessionDao.getAllSessions]
     * (already-reactive Room Flow, cached internally) and filter in-memory by
     * `debrisList.any { it.type.canonical() == type }`. The cost is O(N · D)
     * per emission where N=sessions, D=avg debris per session — both tiny in
     * a single-user device. The trade-off vs SQL is acceptable until N grows
     * past ~10k sessions, at which point a denormalised `session_debris_types`
     * join table would be the right move.
     */
    fun getSessionsByDebrisType(type: DebrisType): Flow<List<DetectionSession>> =
        dao.getAllSessions().map { sessions ->
            sessions.filter { session ->
                session.debrisList.any { debris -> debris.type.canonical() == type }
            }
        }

    // -----------------------------------------------------------------------
    // Statistics helper
    // -----------------------------------------------------------------------

    /**
     * Build a [DetectionStatistics] snapshot from pre-aggregated scalar values
     * and the current full session list.
     *
     * [materialBreakdown] sums individual debris items across all sessions so
     * the map reflects the total number of each material type detected, not the
     * number of sessions that contain a given material.
     *
     * [hotspots] collects distinct non-null locations from all sessions. The
     * list is ordered by session timestamp descending, matching [allSessions].
     * Deduplication (clustering nearby GPS points) is left to the presentation
     * layer where a map viewport is available.
     *
     * [dateRange] is null when the session list is empty.
     */
    private fun buildStatistics(
        totalSessions: Int,
        totalDebrisDetected: Int,
        avgHealthScore: Float,
        sessions: List<DetectionSession>
    ): DetectionStatistics {

        val materialBreakdown: Map<DebrisMaterial, Int> = sessions
            .flatMap { it.debrisList }
            .groupBy { it.material }
            .mapValues { (_, items) -> items.size }

        val hotspots: List<Location> = sessions
            .mapNotNull { it.location }
            .distinctBy { "${it.latitude},${it.longitude}" }

        val dateRange: Pair<Date, Date>? = if (sessions.isEmpty()) {
            null
        } else {
            // sessions is ordered DESC so first = newest, last = oldest
            Pair(sessions.last().timestamp, sessions.first().timestamp)
        }

        return DetectionStatistics(
            totalSessions = totalSessions,
            totalDebrisDetected = totalDebrisDetected,
            avgHealthScore = avgHealthScore,
            materialBreakdown = materialBreakdown,
            hotspots = hotspots,
            dateRange = dateRange
        )
    }
}
