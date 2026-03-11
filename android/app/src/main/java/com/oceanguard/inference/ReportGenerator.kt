package com.oceanguard.ai.inference

import android.util.Log
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DayGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ReportGenerator — aggregates detection session data and produces a
 * natural-language environmental report using the on-device VLM.
 *
 * Supports two generation modes:
 * - **Generic report**: [generateReport] — flat summary of all sessions.
 * - **Zone report**: [generateZoneReport] — temporal evolution at a
 *   specific location, comparing different survey days.
 *
 * VLM is required — exceptions propagate to the caller.
 *
 * @param inference The shared [OceanGuardInference] instance. The
 *   generator does NOT own the lifecycle of this object.
 */
class ReportGenerator(private val inference: OceanGuardInference) {

    companion object {
        private const val TAG = "ReportGenerator"

        private val LANGUAGE_NAMES = mapOf(
            "en" to "English", "es" to "Spanish", "fr" to "French",
            "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        )

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        private const val HIGH_RISK_THRESHOLD = 4
        private const val MEDIUM_RISK_THRESHOLD = 3
    }

    // -----------------------------------------------------------------
    // Public API — Generic report
    // -----------------------------------------------------------------

    suspend fun generateReport(
        sessions: List<DetectionSession>,
        language: String = "en",
    ): String = withContext(Dispatchers.IO) {
        require(sessions.isNotEmpty()) { "Cannot generate report with no sessions" }
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val summary = buildJsonSummary(sessions)
        Log.i(TAG, "Generating report for ${sessions.size} sessions in $languageName")
        val prompt = buildVlmPrompt(languageName, summary)
        val response = runTextInference(prompt)
        Log.i(TAG, "VLM report generated successfully (${response.length} chars)")
        sanitizeVlmOutput(response)
    }

    // -----------------------------------------------------------------
    // Public API — Zone-based temporal report
    // -----------------------------------------------------------------

    suspend fun generateZoneReport(
        input: ZoneReportInput,
        language: String = "en",
    ): String = withContext(Dispatchers.IO) {
        require(input.sessions.isNotEmpty()) { "Cannot generate zone report with no sessions" }
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val summary = buildZoneJsonSummary(input)
        Log.i(TAG, "Generating zone report for ${input.locationName} (${input.sessions.size} sessions)")
        val prompt = buildZoneVlmPrompt(languageName, summary, input.locationName)
        val response = runTextInference(prompt)
        Log.i(TAG, "Zone VLM report generated (${response.length} chars)")
        sanitizeVlmOutput(response)
    }

    // -----------------------------------------------------------------
    // VLM inference + sanitization
    // -----------------------------------------------------------------

    private suspend fun runTextInference(prompt: String): String =
        inference.generateTextResponse(prompt)

    internal fun sanitizeVlmOutput(raw: String): String {
        return raw
            .replace(Regex("""</?(?:end_of_turn|start_of_turn|bos|eos|pad|unk)>"""), "")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            .replace("\uFFFD", "")
            .replace("\uFEFF", "")
            .replace(Regex("[\\u200B-\\u200F\\u2028-\\u202F\\u2060\\uFFF9-\\uFFFC]"), "")
            .replace(Regex("""([*#=~`])\1{3,}""")) { it.groupValues[1].repeat(3) }
            .replace(Regex("""\\(?![n\\*_`\[])"""), "")
            .replace(Regex("""\n{4,}"""), "\n\n\n")
            .trim()
    }

    // -----------------------------------------------------------------
    // JSON helpers — shared
    // -----------------------------------------------------------------

    private fun buildConfidenceStats(allDebris: List<Debris>): JSONObject {
        if (allDebris.isEmpty()) return JSONObject().apply { put("note", "no_detections") }
        val confs = allDebris.map { it.confidence }
        return JSONObject().apply {
            put("min", String.format("%.2f", confs.min()))
            put("max", String.format("%.2f", confs.max()))
            put("avg", String.format("%.2f", confs.average()))
        }
    }

    private fun buildTypeBreakdown(allDebris: List<Debris>): JSONObject {
        val typeMap = mutableMapOf<String, Int>()
        allDebris.forEach { d ->
            typeMap[d.type.name] = (typeMap[d.type.name] ?: 0) + 1
        }
        return JSONObject(typeMap as Map<*, *>)
    }

    private fun buildRiskBreakdown(allDebris: List<Debris>): JSONObject {
        var high = 0; var medium = 0; var low = 0
        allDebris.forEach { d ->
            val score = d.getRiskScore()
            when {
                score >= HIGH_RISK_THRESHOLD -> high++
                score >= MEDIUM_RISK_THRESHOLD -> medium++
                else -> low++
            }
        }
        return JSONObject().apply {
            put("high_risk", high)
            put("medium_risk", medium)
            put("low_risk", low)
        }
    }

    private fun buildQualityDistribution(sessions: List<DetectionSession>): JSONObject {
        val qualityMap = mutableMapOf<String, Int>()
        sessions.forEach { s ->
            qualityMap[s.imageQuality.name] = (qualityMap[s.imageQuality.name] ?: 0) + 1
        }
        return JSONObject(qualityMap as Map<*, *>)
    }

    private fun buildDateRange(sessions: List<DetectionSession>): JSONObject {
        val sorted = sessions.sortedBy { it.timestamp }
        val first = sorted.first().timestamp
        val last = sorted.last().timestamp
        val spanDays = TimeUnit.MILLISECONDS.toDays(last.time - first.time)
        return JSONObject().apply {
            put("first", DATE_FORMAT.format(first))
            put("last", DATE_FORMAT.format(last))
            put("span_days", spanDays)
        }
    }

    // -----------------------------------------------------------------
    // Generic JSON + prompt
    // -----------------------------------------------------------------

    private fun buildSessionDetail(session: DetectionSession): JSONObject {
        val allDebris = session.debrisList
        val typeMap = mutableMapOf<String, Int>()
        allDebris.forEach { d -> typeMap[d.type.name] = (typeMap[d.type.name] ?: 0) + 1 }
        return JSONObject().apply {
            put("timestamp", DATE_FORMAT.format(session.timestamp))
            put("debris_count", session.totalCount)
            put("health_score", session.healthScore)
            put("image_quality", session.imageQuality.name)
            put("type_breakdown", JSONObject(typeMap as Map<*, *>))
            if (session.location != null) {
                put("lat", String.format("%.4f", session.location.latitude))
                put("lon", String.format("%.4f", session.location.longitude))
            }
        }
    }

    internal fun buildJsonSummary(sessions: List<DetectionSession>): String {
        val sorted = sessions.sortedBy { it.timestamp }
        val allDebris = sessions.flatMap { it.debrisList }
        val materialMap = mutableMapOf<String, Int>()
        allDebris.forEach { d -> materialMap[d.material.name] = (materialMap[d.material.name] ?: 0) + 1 }
        val locationsWithData = sessions.mapNotNull { it.location }
        val locationSummary = if (locationsWithData.isNotEmpty()) {
            val lats = locationsWithData.map { it.latitude }
            val lons = locationsWithData.map { it.longitude }
            JSONObject().apply {
                put("count", locationsWithData.size)
                put("lat_range", "${String.format("%.4f", lats.min())} to ${String.format("%.4f", lats.max())}")
                put("lon_range", "${String.format("%.4f", lons.min())} to ${String.format("%.4f", lons.max())}")
            }
        } else {
            JSONObject().apply { put("count", 0); put("note", "No GPS data recorded") }
        }
        val sessionDetails = JSONArray()
        sorted.forEach { sessionDetails.put(buildSessionDetail(it)) }

        return JSONObject().apply {
            put("analyzed_images", sessions.size)
            put("total_debris_items", sessions.sumOf { it.totalCount })
            put("average_health_score", String.format("%.1f", sessions.map { it.healthScore }.average()))
            put("dominant_material", materialMap.maxByOrNull { it.value }?.key ?: "N/A")
            put("date_range", buildDateRange(sorted))
            put("material_breakdown", JSONObject(materialMap as Map<*, *>))
            put("type_breakdown", buildTypeBreakdown(allDebris))
            put("confidence_range", buildConfidenceStats(allDebris))
            put("risk_breakdown", buildRiskBreakdown(allDebris))
            put("image_quality", buildQualityDistribution(sessions))
            put("locations", locationSummary)
            put("sessions", sessionDetails)
        }.toString(2)
    }

    private fun buildVlmPrompt(languageName: String, jsonSummary: String): String = """
You are a marine conservation scientist writing an environmental assessment report in $languageName.

STRICT RULES:
- Use ONLY the dates, numbers and facts from the JSON data below. Do NOT invent dates, locations or statistics.
- Use markdown with ## headings.
- Use markdown tables (| col | col |) for structured data like material breakdowns.
- If only one analysis date exists, do NOT imply multiple survey days.
- Refer to data points as "analyzed images", never as "sessions".

Structure:

## Executive Summary
2-3 sentences: overall contamination level and dominant concern.

## Survey Overview
Exact dates from date_range, number of analyzed images, methodology: RT-DETRv2 on-device AI detection (OceanGuard, no cloud).
Include confidence range (min/max/avg from confidence_range) and image quality distribution from image_quality.

## Findings by Material
Present TWO markdown tables:

Table 1 — By material:
| Material | Count | % of Total | Risk Level | Impact |
Use exact counts from material_breakdown. Calculate percentages from total_debris_items.

Table 2 — By debris type:
| Debris Type | Count | % of Total |
Use exact counts from type_breakdown.

## Environmental Impact Assessment
- Ingestion hazard: analyse ingestion risk per dominant debris type from type_breakdown.
- Entanglement risk: focus on Fishing_Net, Mask, Glove if present in type_breakdown.
- Microplastic fragmentation: assess potential based on plastic items detected.
- Habitat degradation: overall habitat impact based on risk_breakdown (high/medium/low counts).

## Risk Assessment
High-risk items count from risk_breakdown.high_risk, medium/low counts. Ingestion and entanglement hazards.

## Location Analysis
If locations.count > 0: describe spatial distribution using lat_range and lon_range from locations. Otherwise state no GPS data was recorded.

## Conservation Recommendations
3-5 actionable items prioritised by urgency.

Data:
$jsonSummary""".trimIndent()

    // -----------------------------------------------------------------
    // Zone JSON + prompt
    // -----------------------------------------------------------------

    private fun buildDayEntry(day: DayGroup): JSONObject {
        val allDebris = day.sessions.flatMap { it.debrisList }
        val materialMap = mutableMapOf<String, Int>()
        val typeMap = mutableMapOf<String, Int>()
        allDebris.forEach { d ->
            materialMap[d.material.name] = (materialMap[d.material.name] ?: 0) + 1
            typeMap[d.type.name] = (typeMap[d.type.name] ?: 0) + 1
        }
        return JSONObject().apply {
            put("date", DAY_FORMAT.format(day.date))
            put("analyzed_images", day.sessions.size)
            put("total_debris", day.totalDebris)
            put("health_score", day.avgHealthScore)
            put("material_breakdown", JSONObject(materialMap as Map<*, *>))
            put("type_breakdown", JSONObject(typeMap as Map<*, *>))
            put("confidence_range", buildConfidenceStats(allDebris))
        }
    }

    internal fun buildZoneJsonSummary(input: ZoneReportInput): String {
        val allDebris = input.sessions.flatMap { it.debrisList }
        val materialCounts = mutableMapOf<String, Int>()
        allDebris.forEach { d -> materialCounts[d.material.name] = (materialCounts[d.material.name] ?: 0) + 1 }

        val surveyDays = JSONArray()
        input.dayGroups.sortedBy { it.date }.forEach { surveyDays.put(buildDayEntry(it)) }

        val overall = JSONObject().apply {
            put("total_analyzed_images", input.sessions.size)
            put("total_debris_items", input.sessions.sumOf { it.totalCount })
            put("average_health_score", String.format("%.1f", input.sessions.map { it.healthScore }.average()))
            put("dominant_material", materialCounts.maxByOrNull { it.value }?.key ?: "N/A")
            put("high_risk_items", allDebris.count { it.getRiskScore() >= HIGH_RISK_THRESHOLD })
            put("trend", input.trend.name)
            put("type_breakdown", buildTypeBreakdown(allDebris))
            put("confidence_range", buildConfidenceStats(allDebris))
            put("risk_breakdown", buildRiskBreakdown(allDebris))
            put("image_quality", buildQualityDistribution(input.sessions))
            put("material_breakdown", JSONObject(materialCounts as Map<*, *>))
        }

        return JSONObject().apply {
            put("location", input.locationName)
            put("coordinates", JSONObject().apply {
                put("lat", String.format("%.4f", input.centroidLat))
                put("lon", String.format("%.4f", input.centroidLon))
            })
            put("survey_days", surveyDays)
            put("overall", overall)
        }.toString(2)
    }

    private fun buildZoneVlmPrompt(
        languageName: String,
        jsonSummary: String,
        locationName: String,
    ): String = """
You are a marine conservation scientist writing a zone assessment report in $languageName for $locationName.

STRICT RULES:
- Use ONLY the dates, numbers and facts from the JSON data below. Do NOT invent dates, locations or statistics.
- Use markdown with ## headings and markdown tables (| col | col |) for structured data.
- If survey_days has only 1 entry, do NOT describe day-by-day changes or imply multiple visits.
- All numbers must match the JSON exactly.
- Refer to data points as "analyzed images", never as "sessions".

Structure:

## Zone Profile
Location name, coordinates from data, total analyzed images and exact survey dates from survey_days array.

## Day-by-Day Analysis
If multiple days: present as a markdown table:
| Date | Images | Debris | Health Score | Top Type | Notable Change |
Use analyzed_images and type_breakdown per day from survey_days entries.
If only one day: describe that single survey day without inventing comparisons.

## Material Composition
Present TWO markdown tables:

Table 1 — By material:
| Material | Count | % of Total | Risk Level |
Use exact counts from overall.material_breakdown.

Table 2 — By debris type:
| Debris Type | Count | % of Total |
Use exact counts from overall.type_breakdown.

## Environmental Impact Assessment
- Ingestion hazard: analyse per dominant debris type from overall.type_breakdown.
- Entanglement risk: focus on Fishing_Net, Mask, Glove if present.
- Microplastic fragmentation potential based on plastic counts.
- Habitat degradation based on overall.risk_breakdown.

## Trend Assessment
Use the "trend" field from overall. If only 1 survey day, state that trend assessment requires more data.

## Site-Specific Recommendations
3-5 actionable items tailored to this location.

Data:
$jsonSummary""".trimIndent()
}
