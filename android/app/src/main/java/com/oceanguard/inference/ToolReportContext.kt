package com.oceanguard.ai.inference

import com.oceanguard.ai.data.DayGroup
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend

/**
 * Pre-computed aggregates over a set of [DetectionSession]s, used as the data
 * backend for [OceanGuardTools]. Built once per report; tools read O(1) from it.
 *
 * The constructor pre-computes everything so that synchronous tool calls during
 * the agent loop are dirt-cheap (no scanning of the session list per call).
 */
data class ToolReportContext(
    val sessions: List<DetectionSession>,
    val language: String,
    val audience: ReportAudience,
    val zoneInput: ZoneReportInput? = null,
) {
    /** Total debris items across every session. */
    val totalDebrisItems: Int = sessions.sumOf { it.totalCount }

    /** Number of sessions in scope. */
    val sessionCount: Int = sessions.size

    /** Average ecosystem health score (0–100). 100 if no sessions. */
    val avgHealthScore: Int = if (sessions.isEmpty()) 100
        else sessions.map { it.healthScore }.average().toInt()

    /** All debris items flattened across sessions. */
    val allDebris: List<Debris> = sessions.flatMap { it.debrisList }

    /** Counts grouped by [DebrisMaterial] (descending by count). */
    val materialCounts: Map<DebrisMaterial, Int> = allDebris
        .groupingBy { it.material }
        .eachCount()
        .toList()
        .sortedByDescending { it.second }
        .toMap()

    /** Counts grouped by [DebrisType] (descending by count). */
    val typeCounts: Map<DebrisType, Int> = allDebris
        .groupingBy { it.type }
        .eachCount()
        .toList()
        .sortedByDescending { it.second }
        .toMap()

    /** Risk breakdown: HIGH (≥4), MEDIUM (=3), LOW (≤2) by max risk per debris item. */
    val riskBreakdown: Map<String, Int> = run {
        val high = allDebris.count { it.getRiskScore() >= 4 }
        val medium = allDebris.count { it.getRiskScore() == 3 }
        val low = allDebris.count { it.getRiskScore() <= 2 }
        mapOf("high" to high, "medium" to medium, "low" to low)
    }

    /** GPS waypoints sorted by collection priority (HIGH → LOW). */
    val waypoints: List<Waypoint> = sessions
        .filter { it.location != null }
        .map { s ->
            val loc = s.location!!
            val dominantType = s.debrisList
                .groupingBy { it.type }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?.name
                ?: "UNKNOWN"
            Waypoint(
                sessionId = s.id,
                lat = loc.latitude,
                lon = loc.longitude,
                debrisCount = s.totalCount,
                healthScore = s.healthScore,
                dominantType = dominantType,
                priority = priorityOf(s.healthScore, s.totalCount),
            )
        }
        .sortedWith(compareBy({ priorityRank(it.priority) }, { -it.debrisCount }))

    /** Per-session breakdown for table generation in the report. */
    val sessionDetails: List<SessionDetail> = sessions
        .sortedByDescending { it.timestamp }
        .mapIndexed { idx, s ->
            val typeCounts = s.debrisList
                .groupingBy { it.type }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
            val top3 = typeCounts.take(3).map { (t, c) -> TypeCount(t.name, c) }
            val dominant = typeCounts.firstOrNull()?.first?.name ?: "NONE"
            SessionDetail(
                index = idx,
                sessionId = s.id,
                timestampMs = s.timestamp.time,
                totalDebris = s.totalCount,
                healthScore = s.healthScore,
                lat = s.location?.latitude,
                lon = s.location?.longitude,
                imageQuality = s.imageQuality.name,
                dominantType = dominant,
                topTypes = top3,
            )
        }

    /** Temporal trend, if this is a zone report. Null for generic reports. */
    val temporalTrend: TrendData? = zoneInput?.let { zi ->
        val days = zi.dayGroups.sortedBy { it.date }
        val first = days.firstOrNull()
        val last = days.lastOrNull()
        val delta = if (first != null && last != null && first != last) {
            last.avgHealthScore - first.avgHealthScore
        } else 0
        TrendData(
            trend = zi.trend,
            healthScoreDelta = delta,
            surveyDays = days.map { d ->
                SurveyDayBrief(
                    dateMs = d.date.time,
                    sessionCount = d.sessions.size,
                    totalDebris = d.totalDebris,
                    avgHealthScore = d.avgHealthScore,
                )
            },
        )
    }

    private fun priorityOf(healthScore: Int, debrisCount: Int): String = when {
        healthScore < 40 || debrisCount >= 10 -> "HIGH"
        healthScore < 60 || debrisCount >= 5 -> "MEDIUM"
        else -> "LOW"
    }

    private fun priorityRank(p: String): Int = when (p) {
        "HIGH" -> 0; "MEDIUM" -> 1; else -> 2
    }
}

data class Waypoint(
    val sessionId: Long,
    val lat: Double,
    val lon: Double,
    val debrisCount: Int,
    val healthScore: Int,
    val dominantType: String,
    val priority: String,
)

data class SessionDetail(
    val index: Int,
    val sessionId: Long,
    val timestampMs: Long,
    val totalDebris: Int,
    val healthScore: Int,
    val lat: Double?,
    val lon: Double?,
    val imageQuality: String,
    val dominantType: String,
    val topTypes: List<TypeCount>,
)

/** A debris-type name paired with its count within one session. */
data class TypeCount(val name: String, val count: Int)

data class TrendData(
    val trend: HealthTrend,
    val healthScoreDelta: Int,
    val surveyDays: List<SurveyDayBrief>,
)

data class SurveyDayBrief(
    val dateMs: Long,
    val sessionCount: Int,
    val totalDebris: Int,
    val avgHealthScore: Int,
)
