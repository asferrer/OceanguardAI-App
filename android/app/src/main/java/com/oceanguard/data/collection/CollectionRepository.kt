package com.oceanguard.ai.data.collection

import kotlinx.coroutines.flow.Flow
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
