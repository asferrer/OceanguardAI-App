package com.oceanguard.ai.data.collection

import android.util.Log
import com.oceanguard.ai.data.DetectionSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository for the MarineDex collection and achievements.
 */
class CollectionRepository(
    private val marineDexDao: MarineDexDao,
    private val achievementDao: AchievementDao,
) {
    val allDexEntries: Flow<List<MarineDexEntry>> = marineDexDao.getAll()

    val allAchievements: Flow<List<Achievement>> = achievementDao.getAll()

    val discoveredCount: Flow<Int> = marineDexDao.getDiscoveredCount()

    val favoriteCount: Flow<Int> = marineDexDao.getFavoriteCount()

    val totalDetections: Flow<Int> = marineDexDao.getTotalDetections()

    val unlockedAchievementCount: Flow<Int> = achievementDao.getUnlockedCount()

    val completionPercentage: Flow<Float> = marineDexDao.getDiscoveredCount().map { it / 11f }

    /**
     * Register a debris type sighting. Creates the entry if first time,
     * otherwise increments [MarineDexEntry.timesDetected] and updates
     * [MarineDexEntry.lastSeenAt].
     *
     * @return true if this was a NEW discovery (first time seeing this type)
     */
    suspend fun discoverOrUpdate(debrisType: String, sessionId: Long): Boolean {
        val now = System.currentTimeMillis()
        val existing = marineDexDao.getByType(debrisType)
        return if (existing == null) {
            marineDexDao.upsert(
                MarineDexEntry(
                    debrisType = debrisType,
                    firstSeenAt = now,
                    firstSeenSessionId = sessionId,
                    timesDetected = 1,
                    lastSeenAt = now,
                )
            )
            true
        } else {
            marineDexDao.upsert(
                existing.copy(
                    timesDetected = existing.timesDetected + 1,
                    lastSeenAt = now,
                )
            )
            false
        }
    }

    suspend fun getAchievement(id: String): Achievement? = achievementDao.getById(id)

    suspend fun updateAchievement(achievement: Achievement) = achievementDao.upsert(achievement)

    suspend fun toggleFavorite(debrisType: String) {
        val entry = marineDexDao.getByType(debrisType) ?: return
        marineDexDao.setFavorite(debrisType, !entry.isFavorite)
    }

    /**
     * Rebuild every MarineDex entry from the supplied (remaining) sessions.
     *
     * Call after deleting one or more sessions so the denormalised
     * [MarineDexEntry.timesDetected] / [MarineDexEntry.lastSeenAt] columns and
     * the discovered-types set stay consistent with the live history. Types
     * that are no longer present in any remaining session are
     * [MarineDexDao.deleteByType]-ed so they vanish from the dex (re-locked).
     *
     * Note: `firstSeenAt` / `firstSeenSessionId` are also recomputed against
     * the surviving sessions — if the original "first sighting" session was
     * the one the user just deleted, those fields now point at the
     * oldest-surviving session containing that type.
     */
    suspend fun recomputeFromSessions(remainingSessions: List<DetectionSession>) {
        // Build aggregate map from oldest -> newest so firstSeenAt / firstSeenSessionId
        // are taken from the oldest surviving session for each type.
        val ascending = remainingSessions.sortedBy { it.timestamp.time }
        val agg = mutableMapOf<String, TypeAgg>()
        for (session in ascending) {
            val types = session.debrisList
                .map { it.type.canonical().name }
                .distinct()
            for (type in types) {
                val current = agg[type]
                if (current == null) {
                    agg[type] = TypeAgg(
                        count = 1,
                        firstSeenAt = session.timestamp.time,
                        firstSeenSessionId = session.id,
                        lastSeenAt = session.timestamp.time,
                    )
                } else {
                    agg[type] = current.copy(
                        count = current.count + 1,
                        lastSeenAt = maxOf(current.lastSeenAt, session.timestamp.time),
                    )
                }
            }
        }

        // Reconcile against the current marine_dex_entries table.
        val currentEntries = marineDexDao.getAll().first()
        var relocked = 0
        var updated = 0
        for (entry in currentEntries) {
            val target = agg[entry.debrisType]
            if (target == null) {
                // No remaining session contains this type -> re-lock the entry.
                marineDexDao.deleteByType(entry.debrisType)
                relocked++
            } else if (
                entry.timesDetected != target.count ||
                entry.firstSeenAt != target.firstSeenAt ||
                entry.firstSeenSessionId != target.firstSeenSessionId ||
                entry.lastSeenAt != target.lastSeenAt
            ) {
                marineDexDao.upsert(
                    entry.copy(
                        timesDetected = target.count,
                        firstSeenAt = target.firstSeenAt,
                        firstSeenSessionId = target.firstSeenSessionId,
                        lastSeenAt = target.lastSeenAt,
                    )
                )
                updated++
            }
        }
        if (relocked + updated > 0) {
            Log.i(
                "CollectionRepository",
                "Dex recompute: $relocked re-locked, $updated count/timestamp updates",
            )
        }
    }

    /**
     * Internal aggregate used by [recomputeFromSessions].
     */
    private data class TypeAgg(
        val count: Int,
        val firstSeenAt: Long,
        val firstSeenSessionId: Long,
        val lastSeenAt: Long,
    )

    /**
     * Seed all achievement rows if they don't exist yet.
     * Uses IGNORE conflict strategy so existing progress is preserved.
     */
    suspend fun ensureAchievementsSeeded() {
        val seeds = ALL_ACHIEVEMENTS.map { def ->
            Achievement(id = def.id, target = def.target)
        }
        achievementDao.insertAll(seeds)
    }

    /**
     * Delete all MarineDex entries and achievements, then re-seed
     * empty achievements so the user starts completely fresh.
     */
    suspend fun resetAll() {
        marineDexDao.deleteAll()
        achievementDao.deleteAll()
        ensureAchievementsSeeded()
    }
}
