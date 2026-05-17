package com.oceanguard.ai.inference

import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
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

    @Tool(description = "Get counts and percentages for each debris MATERIAL actually detected in this survey. Returned items array is the authoritative and complete list — do not add other materials. Percentages sum to 100.0.")
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

    @Tool(description = "Get counts and percentages for each specific debris TYPE actually detected in this survey. Returned items array is the authoritative and complete list — do not mention other debris types. Percentages sum to 100.0.")
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

    @Tool(description = "Get environmental persistence (degradation time), primary risk, risk score, and annual ocean volume for every debris type detected in this survey. Returns items sorted by count desc. Use this BEFORE writing about any debris type's ecological impact.")
    fun getEcologicalImpacts(): Map<String, Any> {
        val items = ctx.typeCounts.entries.map { (type, count) ->
            val impact = EnvironmentalImpact.getImpact(type)
            mapOf(
                "type" to type.name,
                "count" to count,
                "degradationTime" to impact.degradationTime,
                "primaryRisk" to impact.primaryRisk,
                "riskScore" to impact.riskScore,
                "annualVolumeOcean" to (impact.annualVolumeOcean ?: "Not quantified"),
            )
        }
        return mapOf("count" to items.size, "items" to items)
    }

    @Tool(description = "Get a row for EVERY analyzed image: sessionId, ISO date, lat/lon, totalDebris, healthScore, dominantType, top three types. Returned items array is the authoritative and complete list — use these EXACT rows for any per-session/per-image table; never invent or paraphrase session IDs, dates or counts.")
    fun getPerSessionDetails(): Map<String, Any> {
        val items = ctx.sessionDetails.map { d ->
            mapOf(
                "sessionId" to d.sessionId,
                "dateMs" to d.timestampMs,
                "lat" to (d.lat ?: ""),
                "lon" to (d.lon ?: ""),
                "totalDebris" to d.totalDebris,
                "healthScore" to d.healthScore,
                "dominantType" to d.dominantType,
                "topTypes" to d.topTypes.map { tc ->
                    mapOf("name" to tc.name, "count" to tc.count)
                },
            )
        }
        return mapOf("count" to items.size, "items" to items)
    }

    @Tool(description = "Compute statistical metrics (min, max, avg, median, stdDev) for BOTH health_score and debris_count across all sessions in a single call.")
    fun getSurveyStatistics(): Map<String, Any> {
        val healthValues = ctx.sessions.map { it.healthScore.toDouble() }
        val countValues = ctx.sessions.map { it.totalCount.toDouble() }
        return mapOf(
            "health_score" to summarize(healthValues),
            "debris_count" to summarize(countValues),
        )
    }

    private fun summarize(values: List<Double>): Map<String, Any> {
        if (values.isEmpty()) return mapOf("n" to 0)
        val sorted = values.sorted()
        val avg = values.average()
        val variance = values.sumOf { (it - avg) * (it - avg) } / values.size
        return mapOf(
            "n" to values.size,
            "min" to sorted.first(),
            "max" to sorted.last(),
            "avg" to roundTo1(avg),
            "median" to sorted[sorted.size / 2],
            "stdDev" to roundTo1(sqrt(variance)),
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
