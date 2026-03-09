package com.oceanguard.ai.data

import android.util.Log
import com.oceanguard.ai.data.collection.AchievementDao
import com.oceanguard.ai.data.collection.MarineDexDao
import com.oceanguard.ai.data.collection.MarineDexEntry
import kotlinx.coroutines.flow.first
import java.util.Date

/**
 * Manages demo data injected during guided tours so screens that depend on
 * existing sessions / reports / marinedex entries can showcase real functionality.
 *
 * Only a single generic "OTHER" detection is created to avoid spoiling the
 * MarineDex discovery mechanic for real debris types.
 *
 * Demo data is identified by the tag [DEMO_TAG] on sessions and the
 * [DEMO_REPORT_TAG] substring in report text. All demo artefacts are removed
 * once the user has completed every guided-tour screen.
 */
class TourDemoDataManager(
    private val repository: DetectionRepository,
    private val settingsRepository: SettingsRepository,
    private val reportDao: GeneratedReportDao,
    private val marineDexDao: MarineDexDao,
    private val achievementDao: AchievementDao,
) {
    companion object {
        private const val TAG = "TourDemoData"
        const val DEMO_TAG = "demo:tour"

        /** Hidden marker embedded in the demo report text. */
        const val DEMO_REPORT_TAG = "<!--demo:tour-->"

        /** Demo achievement IDs that are reset during cleanup. */
        private val DEMO_ACHIEVEMENT_IDS = listOf("first_scan", "first_dex_entry")

        /** The only debris type seeded by the demo (preserves MarineDex mystery). */
        private const val DEMO_DEX_TYPE = "OTHER"

        /** Legacy demo types from previous versions (must be cleaned on upgrade). */
        private val LEGACY_DEMO_DEX_TYPES = listOf(
            "BOTTLE", "CAN", "FISHING_NET", "PLASTIC_DEBRIS", "GLASS_DEBRIS", "METAL_DEBRIS",
        )
    }

    // -----------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------

    /** Inject demo data if the DB is empty and there are pending guided-tour screens. */
    suspend fun ensureDemoDataIfNeeded() {
        try {
            val hasPendingTours = SettingsRepository.GUIDED_TOUR_IDS.any { id ->
                !settingsRepository.isTourComplete(id).first()
            }
            if (!hasPendingTours) return

            val sessionCount = repository.allSessions.first().size
            if (sessionCount > 0) return // user already has real data

            Log.i(TAG, "Injecting demo data for guided tours")
            injectDemoSession()
            injectDemoMarineDex()
            injectDemoAchievements()
            injectDemoReport()
            Log.i(TAG, "Demo data injected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject demo data (non-fatal)", e)
        }
    }

    /** Remove all demo data if every guided-tour screen has been completed. */
    suspend fun cleanupIfAllToursComplete() {
        try {
            val allComplete = SettingsRepository.GUIDED_TOUR_IDS.all { id ->
                settingsRepository.isTourComplete(id).first()
            }
            if (!allComplete) return
            cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup check failed (non-fatal)", e)
        }
    }

    /** Force-remove all demo data regardless of tour state. */
    suspend fun cleanup() {
        try {
            repository.deleteDemoSessions()
            reportDao.deleteDemoReports()

            // Delete current + legacy demo MarineDex entries
            marineDexDao.deleteByType(DEMO_DEX_TYPE)
            for (legacyType in LEGACY_DEMO_DEX_TYPES) {
                marineDexDao.deleteByType(legacyType)
            }

            val remaining = repository.allSessions.first()
            if (remaining.isEmpty()) {
                // No real sessions — also reset demo achievements
                for (id in DEMO_ACHIEVEMENT_IDS) {
                    val a = achievementDao.getById(id) ?: continue
                    achievementDao.upsert(a.copy(progress = 0, unlockedAt = 0))
                }
            } else {
                // Rebuild MarineDex entries from real session data
                rebuildDexFromSessions(remaining)
            }
            Log.i(TAG, "Demo data cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed (non-fatal)", e)
        }
    }

    // -----------------------------------------------------------------
    // Private — rebuild after cleanup
    // -----------------------------------------------------------------

    private suspend fun rebuildDexFromSessions(sessions: List<DetectionSession>) {
        val deletedTypes = LEGACY_DEMO_DEX_TYPES + DEMO_DEX_TYPE
        for (typeName in deletedTypes) {
            val matching = sessions
                .filter { s -> s.debrisList.any { it.type.name == typeName } }
                .sortedBy { it.timestamp }
            if (matching.isEmpty()) continue
            val first = matching.first()
            val last = matching.last()
            marineDexDao.upsert(
                MarineDexEntry(
                    debrisType = typeName,
                    firstSeenAt = first.timestamp.time,
                    firstSeenSessionId = first.id,
                    timesDetected = matching.size,
                    lastSeenAt = last.timestamp.time,
                )
            )
        }
    }

    // -----------------------------------------------------------------
    // Private — session injection (single generic detection)
    // -----------------------------------------------------------------

    private suspend fun injectDemoSession() {
        val now = System.currentTimeMillis()
        val session = DetectionSession(
            imageUri = "",
            debrisList = listOf(
                Debris(
                    bbox = BoundingBox(120f, 90f, 100f, 110f),
                    material = DebrisMaterial.OTHER,
                    type = DebrisType.OTHER,
                    confidence = 0.85f,
                ),
            ),
            totalCount = 1,
            healthScore = 85,
            location = Location(41.38, 2.19, 15f, LocationSource.GPS),
            timestamp = Date(now),
            imageQuality = ImageQuality.GOOD,
            processingTimeMs = 5900L,
            tags = DEMO_TAG,
        )
        repository.saveSession(session, skipAchievements = true)
    }

    // -----------------------------------------------------------------
    // Private — MarineDex (only OTHER)
    // -----------------------------------------------------------------

    private suspend fun injectDemoMarineDex() {
        val now = System.currentTimeMillis()
        if (marineDexDao.getByType(DEMO_DEX_TYPE) == null) {
            marineDexDao.upsert(
                MarineDexEntry(
                    debrisType = DEMO_DEX_TYPE,
                    firstSeenAt = now,
                    firstSeenSessionId = 0,
                    timesDetected = 1,
                    lastSeenAt = now,
                )
            )
        }
    }

    // -----------------------------------------------------------------
    // Private — Achievements (minimal)
    // -----------------------------------------------------------------

    private suspend fun injectDemoAchievements() {
        val now = System.currentTimeMillis()
        val demoUnlocks = listOf(
            "first_scan" to 1,
            "first_dex_entry" to 1,
        )
        for ((id, target) in demoUnlocks) {
            val existing = achievementDao.getById(id) ?: continue
            if (existing.unlockedAt == 0L) {
                achievementDao.upsert(
                    existing.copy(progress = target, unlockedAt = now)
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // Private — Report
    // -----------------------------------------------------------------

    private suspend fun injectDemoReport() {
        val existing = reportDao.getAll().first()
        if (existing.any { it.text.contains(DEMO_REPORT_TAG) }) return

        val reportText = """
$DEMO_REPORT_TAG
# OceanGuard Environmental Report

## Summary
This is a **demo report** generated during the guided tour. It shows what a real analysis report looks like after scanning a coastal area with OceanGuard.

## Detection Results
- **1 debris object** detected (unidentified item)
- Ecosystem health score: **85/100** (Good condition)
- Location: Barcelona coast (41.38°N, 2.19°E)

## How It Works
When you scan real images, OceanGuard AI will:
1. Detect and classify marine debris using RT-DETRv2
2. Identify materials (plastic, metal, glass, fabric, etc.)
3. Calculate an ecosystem health score
4. Generate detailed environmental reports

## Next Steps
Start scanning real coastal images to discover all 11 debris types in your **MarineDex** collection!

---
*Generated by OceanGuard AI — Demo Report*
        """.trimIndent()

        reportDao.insert(
            GeneratedReport(
                text = reportText,
                language = "en",
                sessionCount = 1,
                usedAi = false,
            )
        )
    }
}
