package com.oceanguard.ai.inference

import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.EnvironmentalImpact
import kotlin.math.round
import kotlin.math.sqrt

/**
 * [ToolSet] exposed to Gemma 4 via LiteRT-LM tool calling.
 *
 * Each `@Tool`-annotated method retrieves precise, pre-computed data from
 * [ToolReportContext]. The model is instructed (via system prompt) to call
 * these tools instead of fabricating numbers — this eliminates hallucinated
 * percentages and risk scores in generated reports.
 *
 * NOT a singleton: a fresh instance is created per report so each call sees
 * the correct snapshot of [ToolReportContext].
 *
 * All methods are synchronous (no `suspend`) — LiteRT-LM invokes them via
 * reflection on the inference thread. Heavy work must already be precomputed
 * inside [ToolReportContext].
 */
@OptIn(ExperimentalApi::class)
class OceanGuardTools(private val ctx: ToolReportContext) : ToolSet {

    @Tool(description = "Get aggregate debris counts, average health score, dominant material/type, and high/medium/low risk breakdown for the survey. Always call this first.")
    fun getDebrisSummary(): Map<String, Any> {
        val dominantMaterial = ctx.materialCounts.entries.firstOrNull()?.key?.name ?: "NONE"
        val dominantType = ctx.typeCounts.entries.firstOrNull()?.key?.name ?: "NONE"
        return mapOf(
            "totalDebrisItems" to ctx.totalDebrisItems,
            "sessionCount" to ctx.sessionCount,
            "avgHealthScore" to ctx.avgHealthScore,
            "dominantMaterial" to dominantMaterial,
            "dominantType" to dominantType,
            "riskBreakdown" to ctx.riskBreakdown,
        )
    }

    @Tool(description = "Get counts and percentages for each debris MATERIAL (Plastic, Metal, Glass, Fishing_Net, Rubber, Fabric, Wood, Paper, Ceramic, Chemical, Other). Percentages sum to 100.0.")
    fun getMaterialBreakdown(): Map<String, Any> {
        val total = ctx.materialCounts.values.sum().toDouble().coerceAtLeast(1.0)
        val items = ctx.materialCounts.entries.map { (mat, count) ->
            mapOf(
                "name" to mat.name,
                "count" to count,
                "percent" to roundTo1(count * 100.0 / total),
            )
        }
        return mapOf("total" to total.toInt(), "items" to items)
    }

    @Tool(description = "Get counts and percentages for each specific debris TYPE (Bottle, Fishing_Net, Tire, Plastic_Bag, Cigarette_Butt, etc.). Percentages sum to 100.0.")
    fun getTypeBreakdown(): Map<String, Any> {
        val total = ctx.typeCounts.values.sum().toDouble().coerceAtLeast(1.0)
        val items = ctx.typeCounts.entries.map { (t, count) ->
            mapOf(
                "name" to t.name,
                "count" to count,
                "percent" to roundTo1(count * 100.0 / total),
            )
        }
        return mapOf("total" to total.toInt(), "items" to items)
    }

    @Tool(description = "Compute risk scores for each detected debris type and return a ranked risk table with primary ecological risk and urgency label.")
    fun getRiskAssessment(): Map<String, Any> {
        val items = ctx.typeCounts.entries.map { (type, count) ->
            val impact = EnvironmentalImpact.getImpact(type)
            mapOf(
                "type" to type.name,
                "count" to count,
                "riskScore" to impact.riskScore,
                "primaryRisk" to impact.primaryRisk,
                "urgency" to urgencyLabel(impact.riskScore),
            )
        }.sortedByDescending { it["riskScore"] as Int }
        return mapOf("items" to items)
    }

    @Tool(description = "Get up to 10 GPS waypoints for prioritized debris collection, sorted by priority HIGH→LOW. Returns lat, lon, debrisCount, healthScore, dominantType, priority for each waypoint.")
    fun getCollectionWaypoints(): Map<String, Any> {
        val items = ctx.waypoints.take(10).map { w ->
            mapOf(
                "sessionId" to w.sessionId,
                "lat" to w.lat,
                "lon" to w.lon,
                "debrisCount" to w.debrisCount,
                "healthScore" to w.healthScore,
                "dominantType" to w.dominantType,
                "priority" to w.priority,
            )
        }
        return mapOf("count" to items.size, "items" to items)
    }

    @Tool(description = "Get temporal evolution of debris counts and health scores across survey days. Only available for zone reports; returns empty for generic reports.")
    fun getTemporalTrend(): Map<String, Any> {
        val trend = ctx.temporalTrend ?: return mapOf("available" to false)
        return mapOf(
            "available" to true,
            "trend" to trend.trend.name,
            "healthScoreDelta" to trend.healthScoreDelta,
            "days" to trend.surveyDays.map { d ->
                mapOf(
                    "dateMs" to d.dateMs,
                    "sessionCount" to d.sessionCount,
                    "totalDebris" to d.totalDebris,
                    "avgHealthScore" to d.avgHealthScore,
                )
            },
        )
    }

    @Tool(description = "Get environmental persistence (degradation time), primary risk, risk score, and annual ocean volume for a specific debris type. Use this BEFORE writing about any debris type's ecological impact.")
    fun getEcologicalImpact(
        @ToolParam(description = "Debris type name in UPPER_SNAKE_CASE, e.g. FISHING_NET, BOTTLE, TIRE, PLASTIC_DEBRIS.")
        debrisType: String,
    ): Map<String, Any> {
        val type = DebrisType.fromString(debrisType)
        val impact = EnvironmentalImpact.getImpact(type)
        return mapOf(
            "type" to type.name,
            "degradationTime" to impact.degradationTime,
            "primaryRisk" to impact.primaryRisk,
            "riskScore" to impact.riskScore,
            "annualVolumeOcean" to (impact.annualVolumeOcean ?: "Not quantified"),
        )
    }

    @Tool(description = "Compute statistical metrics (min, max, avg, median, stdDev) over a numeric field across all sessions. Field must be one of: health_score, debris_count.")
    fun computeStatistics(
        @ToolParam(description = "Field name: health_score or debris_count.")
        field: String,
    ): Map<String, Any> {
        val values: List<Double> = when (field.lowercase()) {
            "health_score" -> ctx.sessions.map { it.healthScore.toDouble() }
            "debris_count" -> ctx.sessions.map { it.totalCount.toDouble() }
            else -> return mapOf("error" to "Unknown field: $field. Use health_score or debris_count.")
        }
        if (values.isEmpty()) return mapOf("error" to "No data available")
        val sorted = values.sorted()
        val avg = values.average()
        val variance = values.sumOf { (it - avg) * (it - avg) } / values.size
        return mapOf(
            "field" to field.lowercase(),
            "n" to values.size,
            "min" to sorted.first(),
            "max" to sorted.last(),
            "avg" to roundTo1(avg),
            "median" to sorted[sorted.size / 2],
            "stdDev" to roundTo1(sqrt(variance)),
        )
    }

    @Tool(description = "Get detailed breakdown for a specific detection session by its sequential index (0 = most recent). Returns id, timestamp, debris count, health score, GPS, image quality.")
    fun getSessionDetail(
        @ToolParam(description = "Zero-based index of the session (0 = most recent).")
        index: Int,
    ): Map<String, Any> {
        if (index < 0 || index >= ctx.sessionDetails.size) {
            return mapOf("error" to "Index $index out of range [0, ${ctx.sessionDetails.size - 1}]")
        }
        val d = ctx.sessionDetails[index]
        return mapOf(
            "index" to d.index,
            "sessionId" to d.sessionId,
            "timestampMs" to d.timestampMs,
            "totalDebris" to d.totalDebris,
            "healthScore" to d.healthScore,
            "lat" to (d.lat ?: "unknown"),
            "lon" to (d.lon ?: "unknown"),
            "imageQuality" to d.imageQuality,
        )
    }

    private fun urgencyLabel(riskScore: Int): String = when {
        riskScore >= 8 -> "CRITICAL"
        riskScore >= 6 -> "HIGH"
        riskScore >= 4 -> "MEDIUM"
        else -> "LOW"
    }

    private fun roundTo1(v: Double): Double = round(v * 10.0) / 10.0
}
