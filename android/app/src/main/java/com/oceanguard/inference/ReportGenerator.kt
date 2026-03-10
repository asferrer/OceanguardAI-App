package com.oceanguard.ai.inference

import android.util.Log
import com.oceanguard.ai.data.DetectionSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

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
    }

    // -----------------------------------------------------------------
    // Public API — Generic report
    // -----------------------------------------------------------------

    /**
     * Generates a report from an arbitrary list of sessions using the VLM.
     * Throws if inference fails — caller handles the error.
     */
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

    /**
     * Generates a zone report with temporal evolution analysis using the VLM.
     * Throws if inference fails — caller handles the error.
     */
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

    private suspend fun runTextInference(prompt: String): String {
        return inference.generateTextResponse(prompt)
    }

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
    // Generic prompt + JSON
    // -----------------------------------------------------------------

    private fun buildVlmPrompt(languageName: String, jsonSummary: String): String {
        return """You are a marine conservation scientist writing an environmental assessment report in $languageName.

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
Exact dates from the data, number of analyzed images, methodology: on-device AI detection (OceanGuard).

## Findings by Material
Present as a markdown table:
| Material | Count | % of Total | Risk Level | Impact |
Use the exact counts from material_breakdown. Calculate percentages from total_debris_items.

## Risk Assessment
High-risk items count, ingestion/entanglement hazards, microplastic fragmentation risk.

## Conservation Recommendations
3-5 actionable items prioritised by urgency.

Data:
$jsonSummary"""
    }

    private fun buildJsonSummary(sessions: List<DetectionSession>): String {
        val totalDebris = sessions.sumOf { it.totalCount }
        val avgHealth = sessions.map { it.healthScore }.average()
        val materialMap = mutableMapOf<String, Int>()
        sessions.forEach { s ->
            s.debrisList.forEach { d ->
                materialMap[d.material.name] = (materialMap[d.material.name] ?: 0) + 1
            }
        }
        val locationsWithData = sessions.mapNotNull { it.location }
        val locationSummary = if (locationsWithData.isNotEmpty()) {
            val lats = locationsWithData.map { it.latitude }
            val lons = locationsWithData.map { it.longitude }
            mapOf(
                "count" to locationsWithData.size,
                "lat_range" to "${String.format("%.4f", lats.min())} to ${String.format("%.4f", lats.max())}",
                "lon_range" to "${String.format("%.4f", lons.min())} to ${String.format("%.4f", lons.max())}",
            )
        } else {
            mapOf("count" to 0, "note" to "No GPS data recorded")
        }
        val sorted = sessions.sortedBy { it.timestamp }
        val dominantMaterial = materialMap.maxByOrNull { it.value }?.key ?: "N/A"
        val highRiskCount = sessions.sumOf { s -> s.debrisList.count { it.getRiskScore() >= 4 } }

        // Per-session details so VLM has exact timestamps
        val sessionDetails = JSONArray()
        sorted.forEach { s ->
            sessionDetails.put(JSONObject().apply {
                put("timestamp", DATE_FORMAT.format(s.timestamp))
                put("debris_count", s.totalCount)
                put("health_score", s.healthScore)
                if (s.location != null) {
                    put("lat", String.format("%.4f", s.location.latitude))
                    put("lon", String.format("%.4f", s.location.longitude))
                }
            })
        }

        return JSONObject().apply {
            put("analyzed_images", sessions.size)
            put("total_debris_items", totalDebris)
            put("average_health_score", String.format("%.1f", avgHealth))
            put("dominant_material", dominantMaterial)
            put("high_risk_items", highRiskCount)
            put("material_breakdown", JSONObject(materialMap as Map<*, *>))
            put("locations", JSONObject(locationSummary as Map<*, *>))
            put("sessions", sessionDetails)
        }.toString(2)
    }

    // -----------------------------------------------------------------
    // Zone prompt + temporal JSON
    // -----------------------------------------------------------------

    private fun buildZoneVlmPrompt(
        languageName: String,
        jsonSummary: String,
        locationName: String,
    ): String {
        return """You are a marine conservation scientist writing a zone assessment report in $languageName for $locationName.

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
| Date | Sessions | Debris | Health Score | Notable Change |
If only one day: describe that single survey day without inventing comparisons.

## Material Composition
Present as a markdown table:
| Material | Count | % of Total | Risk Level |
Use exact counts from material_breakdown. Calculate percentages from total_debris_items.

## Trend Assessment
Use the "trend" field from the data. If only 1 survey day, state that trend assessment requires more data.

## Site-Specific Recommendations
3-5 actionable items tailored to this location.

Data:
$jsonSummary"""
    }

    private fun buildZoneJsonSummary(input: ZoneReportInput): String {
        val totalDebris = input.sessions.sumOf { it.totalCount }
        val avgHealth = input.sessions.map { it.healthScore }.average()
        val highRiskCount = input.sessions.sumOf { s -> s.debrisList.count { it.getRiskScore() >= 4 } }
        val materialCounts = mutableMapOf<String, Int>()
        input.sessions.forEach { s ->
            s.debrisList.forEach { d ->
                materialCounts[d.material.name] = (materialCounts[d.material.name] ?: 0) + 1
            }
        }
        val dominantMaterial = materialCounts.maxByOrNull { it.value }?.key ?: "N/A"

        val surveyDays = JSONArray()
        input.dayGroups.sortedBy { it.date }.forEach { day ->
            val dayMaterials = mutableMapOf<String, Int>()
            day.sessions.forEach { s ->
                s.debrisList.forEach { d ->
                    dayMaterials[d.material.name] = (dayMaterials[d.material.name] ?: 0) + 1
                }
            }
            surveyDays.put(JSONObject().apply {
                put("date", DAY_FORMAT.format(day.date))
                put("session_count", day.sessions.size)
                put("total_debris", day.totalDebris)
                put("health_score", day.avgHealthScore)
                put("material_breakdown", JSONObject(dayMaterials as Map<*, *>))
            })
        }

        val overall = JSONObject().apply {
            put("total_analyzed_images", input.sessions.size)
            put("total_debris_items", totalDebris)
            put("average_health_score", String.format("%.1f", avgHealth))
            put("dominant_material", dominantMaterial)
            put("high_risk_items", highRiskCount)
            put("trend", input.trend.name)
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
}
