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
        return """As a marine conservation scientist, write a detailed environmental assessment report in $languageName. Use markdown with ## headings. Structure:
## Executive Summary
2-3 sentences: overall contamination level and dominant concern.
## Survey Overview
Dates, number of sessions, geographic coverage, methodology notes.
## Findings by Material
For each material detected: count, percentage of total, ecological risk level (low/medium/high/critical), specific harm to marine fauna.
## Risk Assessment
High-risk items identified, potential impact on marine ecosystems, ingestion/entanglement hazards, microplastic fragmentation risk.
## Conservation Recommendations
3-5 actionable items prioritised by urgency. Include: immediate cleanup targets, prevention strategies, monitoring suggestions.
Be specific with numbers and percentages. Formal scientific tone.
Data: $jsonSummary"""
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
        val dateRange = if (sorted.isNotEmpty()) {
            "${DATE_FORMAT.format(sorted.first().timestamp)} to ${DATE_FORMAT.format(sorted.last().timestamp)}"
        } else "N/A"
        val dominantMaterial = materialMap.maxByOrNull { it.value }?.key ?: "N/A"
        val highRiskCount = sessions.sumOf { s -> s.debrisList.count { it.getRiskScore() >= 4 } }

        return JSONObject().apply {
            put("survey_sessions", sessions.size)
            put("date_range", dateRange)
            put("total_debris_items", totalDebris)
            put("average_health_score", String.format("%.1f", avgHealth))
            put("dominant_material", dominantMaterial)
            put("high_risk_items", highRiskCount)
            put("material_breakdown", JSONObject(materialMap as Map<*, *>))
            put("locations", JSONObject(locationSummary as Map<*, *>))
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
        return """As a marine conservation scientist, write a detailed zone assessment report in $languageName for $locationName. Use markdown with ## headings. Structure:
## Zone Profile
Location name, coordinates, total survey effort (sessions, dates), general site characterization.
## Day-by-Day Analysis
For each survey day: date, debris count, health score, notable changes from previous day. Highlight significant increases or decreases.
## Material Composition
Breakdown by material type: count, percentage, ecological risk. Compare composition across survey days if multiple days.
## Trend Assessment
Overall trend (improving/stable/degrading) with supporting evidence. Rate of change if quantifiable.
## Site-Specific Recommendations
3-5 actionable items tailored to this location. Include: cleanup priorities, source identification, monitoring frequency, stakeholder engagement.
Be specific with numbers and percentages. Formal scientific tone.
Data: $jsonSummary"""
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
            put("total_sessions", input.sessions.size)
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
