package com.oceanguard.ai.inference

import android.util.Log
import com.oceanguard.ai.data.DetectionSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tool-calling report generator. Companion to [ReportGenerator] for the
 * LiteRT-LM (Gemma 4) path. Instead of front-loading the entire dataset as
 * JSON in the prompt, this generator wires Gemma 4 to query [OceanGuardTools]
 * on demand, eliminating hallucinated percentages and inventing of reference
 * data such as degradation times and risk scores.
 *
 * The legacy [ReportGenerator] remains the fallback path for engines that do
 * not support native tool calling (e.g. llama.cpp / Qwen).
 */
class ToolReportGenerator(private val engine: LiteRTTextEngine) {

    companion object {
        private const val TAG = "ToolReportGenerator"

        private const val MAX_TOOL_ROUNDS = 14
        private const val MAX_OUTPUT_TOKENS = 6144

        // Tools Gemma 4 MUST invoke before it is allowed to start writing the report.
        // Enforced by ToolAgentLoop: if the model tries to wrap the turn without
        // calling every name in this set, its draft is discarded and it is redirected
        // back to PHASE 1. Method names match the Kotlin @Tool methods in
        // OceanGuardTools (reflection keeps them camelCase).
        // LiteRT-LM reflection converts @Tool camelCase method names to snake_case
        // when surfacing them to the FC parser. ToolCall.name comes back snake_case,
        // so required-set entries MUST match that form or the enforcement will
        // falsely trigger redirects even when every tool has been invoked.
        private val REQUIRED_TOOLS_GENERIC = setOf(
            "get_debris_summary",
            "get_material_breakdown",
            "get_type_breakdown",
            "get_risk_assessment",
            "get_collection_waypoints",
            "get_ecological_impact",
        )
        private val REQUIRED_TOOLS_ZONE = REQUIRED_TOOLS_GENERIC + "get_temporal_trend"

        private val LANGUAGE_NAMES = mapOf(
            "en" to "English",
            "es" to "Spanish (Español)",
            "fr" to "French (Français)",
            "de" to "German (Deutsch)",
            "it" to "Italian (Italiano)",
            "pt" to "Portuguese (Português)",
        )

        private val FIRST_HEADING = mapOf(
            "en" to "## Executive Summary",
            "es" to "## Resumen Ejecutivo",
            "fr" to "## Résumé Exécutif",
            "de" to "## Zusammenfassung",
            "it" to "## Sommario Esecutivo",
            "pt" to "## Resumo Executivo",
        )

        private val FIRST_HEADING_ZONE = mapOf(
            "en" to "## Zone Profile",
            "es" to "## Perfil de la Zona",
            "fr" to "## Profil de la Zone",
            "de" to "## Zonenprofil",
            "it" to "## Profilo della Zona",
            "pt" to "## Perfil da Zona",
        )
    }

    suspend fun generateReportWithToolsStreaming(
        sessions: List<DetectionSession>,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        require(sessions.isNotEmpty()) { "Cannot generate report with no sessions" }
        val ctx = ToolReportContext(sessions, language, audience, zoneInput = null)
        val tools = OceanGuardTools(ctx)

        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val firstHeading = FIRST_HEADING[language] ?: "## Executive Summary"
        val system = buildSystemMessage(languageName, audience, ReportKind.GENERIC)
        val prompt = buildUserPrompt(languageName, firstHeading, ReportKind.GENERIC)

        Log.i(TAG, "Tool-based report: ${sessions.size} sessions, lang=$language, audience=$audience")
        engine.generateWithTools(
            prompt = prompt,
            toolSet = tools,
            systemMessage = system,
            maxToolRounds = MAX_TOOL_ROUNDS,
            requiredToolNames = REQUIRED_TOOLS_GENERIC,
            onPartialResult = onPartialResult,
            onToolCallStarted = onToolCallStarted,
        )
    }

    suspend fun generateZoneReportWithToolsStreaming(
        input: ZoneReportInput,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        require(input.sessions.isNotEmpty()) { "Cannot generate zone report with no sessions" }
        val ctx = ToolReportContext(input.sessions, language, audience, zoneInput = input)
        val tools = OceanGuardTools(ctx)

        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val firstHeading = FIRST_HEADING_ZONE[language] ?: "## Zone Profile"
        val system = buildSystemMessage(languageName, audience, ReportKind.ZONE)
        val prompt = buildUserPrompt(languageName, firstHeading, ReportKind.ZONE, input.locationName)

        Log.i(TAG, "Tool-based zone report: ${input.locationName}, ${input.sessions.size} sessions")
        engine.generateWithTools(
            prompt = prompt,
            toolSet = tools,
            systemMessage = system,
            maxToolRounds = MAX_TOOL_ROUNDS,
            requiredToolNames = REQUIRED_TOOLS_ZONE,
            onPartialResult = onPartialResult,
            onToolCallStarted = onToolCallStarted,
        )
    }

    private enum class ReportKind { GENERIC, ZONE }

    private fun buildSystemMessage(
        languageName: String,
        audience: ReportAudience,
        kind: ReportKind,
    ): String {
        val persona = when (audience) {
            ReportAudience.SCIENTIFIC ->
                "You are a senior marine conservation scientist authoring a peer-reviewed field assessment. " +
                    "Use scientific nomenclature, cite ecological mechanisms, maintain methodological rigor."
            ReportAudience.NGO_MANAGER ->
                "You are a senior environmental consultant preparing an operational assessment for a coastal " +
                    "conservation NGO. Provide technical analysis with prioritized, actionable recommendations."
            ReportAudience.CITIZEN ->
                "You are an experienced marine educator writing for citizen scientists, divers, and the public. " +
                    "Use accessible language with relatable analogies; motivate action with concrete guidance."
        }
        val sections = sectionList(kind)
        val zoneTrendStep = if (kind == ReportKind.ZONE)
            "  7. the temporal-trend tool (zone reports only)"
        else
            "  7. (skip the temporal-trend tool — it is empty for generic reports)"
        return """
OUTPUT LANGUAGE: $languageName. Every word — headings, body, tables, captions — MUST be in $languageName.

$persona

==================================================================
TWO-PHASE PROTOCOL (mandatory, non-negotiable)
==================================================================

PHASE 1 — DATA GATHERING (silent; emit ZERO prose, only tool calls)
Invoke the available tools in this exact order before writing any report text:
  1. the debris-summary tool
  2. the material-breakdown tool
  3. the type-breakdown tool
  4. the risk-assessment tool
  5. the collection-waypoints tool
  6. the statistics tool with field "health_score"
$zoneTrendStep
  8. the ecological-impact tool once for EACH of the top 3-5 debris types returned by the type-breakdown tool, passing the type name (UPPER_SNAKE_CASE, e.g. FISHING_NET, BOTTLE, PLASTIC_DEBRIS) as the debris_type argument.

Use the tools' native function-calling syntax exactly as declared by the runtime. Do not hand-write pseudo-function calls in prose. During PHASE 1 emit no narrative, no headings, no commentary — only tool invocations.

PHASE 2 — SINGLE-PASS WRITING
Only AFTER every tool above has returned, emit the full final report as one continuous stream starting EXACTLY with the first heading. Treat all tool results as facts you already know; reference them fluently without mentioning "tools", "queries", or "data retrieval". Additional silent tool calls mid-writing are allowed only to fix a missed number — never narrate them.

==================================================================
HARD PROHIBITIONS (violating any makes the output unusable)
==================================================================
• No meta-commentary: never write "I will now...", "let me call...", "calling tool X", "fetching", "retrieving", "querying".
• No placeholders: never write "[awaiting result]", "[pending]", "[TBD]", "[data incoming]", "...", "(to be filled)", "TODO".
• No process talk: never say "once I have the data", "after gathering the results", "I have retrieved", "the tools returned".
• No self-reference: do not mention the model, the agent, the tools, or the report-generation process.
• No invented facts: never fabricate percentages, GPS coordinates, degradation times, risk scores, species, dates, or counts. If a datum is missing, omit the sentence.

==================================================================
DATA PROVENANCE RULES (every number has a mandatory source)
==================================================================
• Percentages → from the material-breakdown or type-breakdown tool. Tables MUST reproduce the `items` array verbatim, one row per entry, with the exact `name`, `count`, and `percent` values returned. Do NOT add rows for categories the tool did not return. Do NOT merge, split, translate to other categories, or recompute percentages. The full set of rows you print must be exactly the set the tool returned.
• Degradation time, persistence, annual ocean volume → from the ecological-impact tool. Quote values verbatim ("600+ years", "~640K tonnes/year"). Only discuss debris types for which you called this tool.
• GPS waypoints → from the collection-waypoints tool. Keep the latitude, longitude, counts, and priority labels exactly as returned; preserve the tool's priority order; round coordinates only to 5 decimals if needed.
• min/max/avg/median/stdDev → from the statistics tool.
• Risk ranking → from the risk-assessment tool's ordered items.
• Temporal deltas → from the temporal-trend tool (zone reports only).
• Every percentage you write MUST either (a) appear in a tool's output exactly or (b) be a trivial sum of numbers that did. If you need a number that no tool returned, OMIT the sentence instead of guessing.

==================================================================
TABLE FIDELITY CHECKLIST (run this mentally before emitting any table)
==================================================================
1. Count the rows: it must equal the length of the tool's `items` array.
2. Names: UPPER_SNAKE_CASE, copied byte-for-byte from the tool; translate only to the human-readable label next to them if desired, never replace.
3. Percentages: copy the numeric value from the tool. Do not re-derive them.
4. Sum of the `percent` column equals 100 (±0.3) — if not, you have added or removed a row.
5. Never emit values like "355%", "455%", "150%" — those are mathematical impossibilities for a percentage.

==================================================================
REPORT STRUCTURE — level-2 ## headings in $languageName, IN THIS ORDER
==================================================================
$sections

STYLE: ## headings, ### subheadings, bullet lists, markdown tables (| col | col |). Target 700–900 words of body content — quality and accuracy over length. Every quantitative claim lives inside a markdown table or a bullet. No ASCII art, no horizontal rules (---), no code fences around tables.

TONE must match the average health score returned by the debris-summary tool:
  • ≥80: excellent, healthy, low localized impact
  • 70–79: generally good, moderate localized impact, targeted cleanup
  • 50–69: degraded, significant impact, urgent intervention
  • <50: compromised, critical, immediate emergency response
""".trimIndent()
    }

    private fun buildUserPrompt(
        languageName: String,
        firstHeading: String,
        kind: ReportKind,
        locationName: String? = null,
    ): String {
        val locClause = locationName?.let { " for the location \"$it\"" } ?: ""
        val kindLabel = if (kind == ReportKind.ZONE) "zone temporal-evolution" else "marine debris assessment"
        return """
Produce the full $kindLabel report$locClause in $languageName now.

Follow the TWO-PHASE PROTOCOL defined in the system instructions:
  • PHASE 1: silently invoke ALL required tools (summary, breakdowns, risk, waypoints, statistics${if (kind == ReportKind.ZONE) ", temporal trend" else ""}, and the ecological-impact tool once per top debris type). No prose output yet.
  • PHASE 2: once every tool has returned, stream the complete final report in a single pass.

The very first character of prose you emit MUST be the first character of "$firstHeading". No preamble, no outline, no status updates, no mention of tools or data retrieval. Deliver the finished document directly.
""".trimIndent()
    }

    private fun sectionList(kind: ReportKind): String = when (kind) {
        ReportKind.GENERIC -> """
  1. Executive Summary
  2. Methodology & Survey Overview
  3. Spatial Distribution & Collection Waypoints
  4. Debris Composition (Material + Type Analysis tables)
  5. Per-Type Ecological Impact Analysis
  6. Risk Assessment (ranked risk matrix)
  7. Statistical Analysis (per-session table)
  8. Conservation Recommendations (prioritized)
""".trimIndent()
        ReportKind.ZONE -> """
  1. Zone Profile
  2. Temporal Evolution (day-by-day trend)
  3. Debris Composition (Material + Type Analysis tables)
  4. Ecological Impact
  5. Risk Assessment
  6. Conclusions & Next Steps
  7. Conservation Recommendations
  8. Monitoring Protocol
""".trimIndent()
    }
}
