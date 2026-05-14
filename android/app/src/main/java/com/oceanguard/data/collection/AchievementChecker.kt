package com.oceanguard.ai.data.collection

import android.util.Log
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.DetectionSessionDao
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * Evaluates and unlocks achievements after each detection session.
 *
 * Emits newly unlocked achievements via [achievementUnlocked] so the UI
 * can show a celebratory overlay.
 */
class AchievementChecker(
    private val collectionRepo: CollectionRepository,
    private val achievementDao: AchievementDao,
    private val marineDexDao: MarineDexDao,
    private val sessionDao: DetectionSessionDao,
) {
    companion object {
        private const val TAG = "AchievementChecker"
    }

    private val _achievementUnlocked = MutableSharedFlow<AchievementDef>(extraBufferCapacity = 5)
    val achievementUnlocked: SharedFlow<AchievementDef> = _achievementUnlocked.asSharedFlow()

    /**
     * Process a saved session: update MarineDex, evaluate and unlock achievements.
     *
     * Batch-uploaded sessions (tag "source:batch") only update MarineDex and
     * check the first_batch achievement. Scan counts, streaks, and other
     * achievements require camera or real-time captures.
     */
    suspend fun processSession(session: DetectionSession) {
        try {
            val isBatch = session.tags?.contains("source:batch") == true

            // 1. Extract unique CANONICAL debris types from the session.
            //    Extended Gemma 4 types (e.g. PLASTIC_BAG) collapse to their
            //    parent (PLASTIC_DEBRIS) so marine_dex_entries only ever holds
            //    the 11 canonical entries and dex_* achievements are coherent.
            val debrisTypes = session.debrisList
                .map { it.type.canonical().name }
                .distinct()

            // 2. Update MarineDex entries (always, including batch)
            for (type in debrisTypes) {
                collectionRepo.discoverOrUpdate(type, session.id)
            }

            // 3. Batch sessions: only unlock first_batch, skip all other achievements
            if (isBatch) {
                checkFirstBatch()
                return
            }

            // 4. Camera/real-time sessions: full achievement evaluation
            evaluateScanAchievements()
            evaluateDexAchievements()
            evaluateDebrisCountAchievements()
            evaluateHealthAchievement(session)
            evaluateStreakAchievement()
            evaluateMetaAchievement()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing achievements for session ${session.id}", e)
        }
    }

    /**
     * Called after a report is generated to check report-related achievements.
     */
    suspend fun processReportGenerated() {
        try {
            // report_10 achievement is checked externally since we don't have the report dao here
            // The caller provides the current report count
        } catch (e: Exception) {
            Log.e(TAG, "Error processing report achievement", e)
        }
    }

    /**
     * Check and unlock report count achievement.
     */
    suspend fun checkReportCount(totalReports: Int) {
        tryAdvance("report_10", totalReports)
    }

    /**
     * Check first_live achievement (called from LiveDetectionScreen).
     */
    suspend fun checkFirstLive() {
        tryAdvance("first_live", 1)
    }

    /**
     * Check first_batch achievement (called from batch processing).
     */
    suspend fun checkFirstBatch() {
        tryAdvance("first_batch", 1)
    }

    /**
     * Check tutorial_complete achievement (called when full guided tour finishes).
     */
    suspend fun checkTutorialComplete() {
        tryAdvance("tutorial_complete", 1)
        evaluateMetaAchievement()
    }

    /**
     * Check research contribution achievements (called from DataContributionWorker
     * after successful uploads).
     */
    suspend fun checkContributionCount(totalUploaded: Int) {
        tryAdvance("contribute_1",   minOf(totalUploaded, 1))
        tryAdvance("contribute_10",  totalUploaded)
        tryAdvance("contribute_50",  totalUploaded)
        tryAdvance("contribute_100", totalUploaded)
        evaluateMetaAchievement()
    }

    /**
     * Check dex_favorite_3 achievement (called when user toggles favorite).
     */
    suspend fun checkFavoriteCount() {
        val favCount = marineDexDao.getFavoriteCount().first()
        tryAdvance("dex_favorite_3", favCount)
    }

    // -----------------------------------------------------------------------
    // Private evaluation helpers
    // -----------------------------------------------------------------------

    private suspend fun evaluateScanAchievements() {
        val cameraSessions = sessionDao.getCameraSessionCount().first()
        tryAdvance("first_scan", minOf(cameraSessions, 1))
        tryAdvance("scan_10", cameraSessions)
        tryAdvance("scan_50", cameraSessions)
        tryAdvance("scan_100", cameraSessions)
        tryAdvance("scan_500", cameraSessions)
    }

    private suspend fun evaluateDexAchievements() {
        val dexCount = marineDexDao.getDiscoveredCount().first()
        tryAdvance("first_dex_entry", minOf(dexCount, 1))
        tryAdvance("dex_3", dexCount)
        tryAdvance("dex_6", dexCount)
        tryAdvance("dex_9", dexCount)
        tryAdvance("dex_11", dexCount)
    }

    private suspend fun evaluateDebrisCountAchievements() {
        val totalDebris = marineDexDao.getTotalDetections().first()
        tryAdvance("debris_50", totalDebris)
        tryAdvance("debris_200", totalDebris)
    }

    private suspend fun evaluateHealthAchievement(session: DetectionSession) {
        if (session.healthScore >= 80) {
            tryAdvance("health_80", 1)
        }
    }

    private suspend fun evaluateStreakAchievement() {
        // Calculate consecutive days with at least one camera/real-time scan
        val allSessions = sessionDao.getCameraAllSessions().first()
        if (allSessions.isEmpty()) return

        val calendar = Calendar.getInstance()
        val daysWithScans = allSessions.map { session ->
            calendar.time = session.timestamp
            val year = calendar.get(Calendar.YEAR)
            val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
            year * 1000 + dayOfYear
        }.distinct().sorted().reversed()

        var streak = 1
        for (i in 0 until daysWithScans.size - 1) {
            val diff = daysWithScans[i] - daysWithScans[i + 1]
            if (diff == 1) {
                streak++
            } else {
                break
            }
        }
        tryAdvance("streak_7", streak)
    }

    private suspend fun evaluateMetaAchievement() {
        val unlocked = achievementDao.getUnlockedCount().first()
        // "all_achievements" itself doesn't count, so 19 others needed
        tryAdvance("all_achievements", unlocked)
    }

    /**
     * Try to advance an achievement's progress. If the achievement reaches
     * its target and was not previously unlocked, emit it via SharedFlow.
     */
    private suspend fun tryAdvance(id: String, newProgress: Int) {
        val existing = achievementDao.getById(id) ?: return
        if (existing.unlockedAt > 0) return // already unlocked

        val clamped = minOf(newProgress, existing.target)
        if (clamped > existing.progress || clamped >= existing.target) {
            val now = if (clamped >= existing.target) System.currentTimeMillis() else 0L
            achievementDao.upsert(existing.copy(progress = clamped, unlockedAt = now))
            if (now > 0) {
                val def = ACHIEVEMENT_DEF_MAP[id]
                if (def != null) {
                    Log.i(TAG, "Achievement unlocked: ${def.id}")
                    _achievementUnlocked.emit(def)
                }
            }
        }
    }
}
