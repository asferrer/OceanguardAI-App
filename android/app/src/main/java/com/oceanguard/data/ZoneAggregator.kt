package com.oceanguard.ai.data

import java.util.Calendar
import java.util.Date
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Domain models
// ---------------------------------------------------------------------------

enum class HealthTrend { IMPROVING, STABLE, DEGRADING }

data class ZoneCluster(
    val centroidLat: Double,
    val centroidLon: Double,
    val sessions: List<DetectionSession>,
    val aggregateHealthScore: Int,
    val trend: HealthTrend,
)

/** Sessions captured on the same calendar day within a zone. */
data class DayGroup(
    val date: Date,
    val sessions: List<DetectionSession>,
    val totalDebris: Int,
    val avgHealthScore: Int,
)

/**
 * Named decomposition of the health score formula.
 *
 * - [debrisCountImpact]       max 30  (densityFactor * 30)
 * - [riskLevelImpact]         max 50  (avgRisk * 10, risk scores 2–5)
 * - [materialDiversityImpact] max 10  (distinct material penalty)
 *
 * [totalScore] mirrors the behaviour of [DebrisDetection.calculateHealthScore]
 * but gives each penalty a name so the UI can display it.
 */
data class HealthScoreFactors(
    val debrisCountImpact: Int,
    val riskLevelImpact: Int,
    val materialDiversityImpact: Int,
    val baseScore: Int = 100,
) {
    val totalScore: Int
        get() = (baseScore - debrisCountImpact - riskLevelImpact - materialDiversityImpact)
            .coerceIn(0, 100)
}

// ---------------------------------------------------------------------------
// Aggregator
// ---------------------------------------------------------------------------

object ZoneAggregator {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val TREND_THRESHOLD = 5

    /**
     * Greedy GPS clustering: sessions within [radiusMeters] of an existing
     * cluster centroid are merged into that cluster; otherwise a new cluster
     * is created. After all sessions are assigned the centroid is recomputed
     * as the average of all member session coordinates.
     */
    fun cluster(
        sessions: List<DetectionSession>,
        radiusMeters: Double = 1000.0,
    ): List<ZoneCluster> {
        val located = sessions.filter { it.location != null }
        if (located.isEmpty()) return emptyList()

        // Each entry: mutable list of sessions forming one zone
        val groups = mutableListOf<MutableList<DetectionSession>>()

        for (session in located) {
            val loc = session.location!!
            val nearest = groups.firstOrNull { group ->
                val centroid = centroidOf(group)
                haversineDistance(loc.latitude, loc.longitude, centroid.first, centroid.second) <= radiusMeters
            }
            if (nearest != null) nearest.add(session) else groups.add(mutableListOf(session))
        }

        return groups.map { group ->
            val (lat, lon) = centroidOf(group)
            val avgScore = group.map { it.healthScore }.average().toInt()
            ZoneCluster(
                centroidLat = lat,
                centroidLon = lon,
                sessions = group,
                aggregateHealthScore = avgScore,
                trend = computeTrend(group),
            )
        }
    }

    /**
     * Haversine formula — returns great-circle distance in metres.
     */
    fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Decomposes the existing [DebrisDetection.calculateHealthScore] formula
     * into named penalty factors for display in [HealthScoreBreakdown].
     *
     * Empty list → all zeros, perfect score.
     */
    fun computeFactors(debrisList: List<Debris>): HealthScoreFactors {
        if (debrisList.isEmpty()) {
            return HealthScoreFactors(
                debrisCountImpact = 0,
                riskLevelImpact = 0,
                materialDiversityImpact = 0,
            )
        }
        val avgRisk = debrisList.map { it.getRiskScore() }.average()
        val densityFactor = minOf(debrisList.size / 10.0, 1.0)
        val distinctMaterials = debrisList.map { it.material }.toSet().size
        // Penalty: 0 for 1 material, up to 10 for 7+ distinct materials
        val diversityPenalty = ((distinctMaterials - 1) * 10 / 6).coerceIn(0, 10)

        return HealthScoreFactors(
            debrisCountImpact = (densityFactor * 30).toInt(),
            riskLevelImpact = (avgRisk * 10).toInt(),
            materialDiversityImpact = diversityPenalty,
        )
    }

    /** Sub-groups a zone's sessions by calendar day, sorted most recent first. */
    fun groupByDay(zone: ZoneCluster): List<DayGroup> {
        return zone.sessions
            .groupBy { truncateToDay(it.timestamp) }
            .map { (date, sessions) ->
                val sorted = sessions.sortedByDescending { it.timestamp }
                DayGroup(
                    date = date,
                    sessions = sorted,
                    totalDebris = sorted.sumOf { it.totalCount },
                    avgHealthScore = sorted.map { it.healthScore }.average().toInt(),
                )
            }
            .sortedByDescending { it.date }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun truncateToDay(date: Date): Date {
        val cal = Calendar.getInstance()
        cal.time = date
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private fun centroidOf(group: List<DetectionSession>): Pair<Double, Double> {
        val lats = group.mapNotNull { it.location?.latitude }
        val lons = group.mapNotNull { it.location?.longitude }
        return Pair(lats.average(), lons.average())
    }

    private fun computeTrend(sessions: List<DetectionSession>): HealthTrend {
        if (sessions.size < 2) return HealthTrend.STABLE
        val sorted = sessions.sortedBy { it.timestamp }
        val half = sorted.size / 2
        val firstHalfAvg = sorted.take(half).map { it.healthScore }.average()
        val secondHalfAvg = sorted.drop(half).map { it.healthScore }.average()
        val delta = secondHalfAvg - firstHalfAvg
        return when {
            delta > TREND_THRESHOLD  -> HealthTrend.IMPROVING
            delta < -TREND_THRESHOLD -> HealthTrend.DEGRADING
            else                     -> HealthTrend.STABLE
        }
    }
}
