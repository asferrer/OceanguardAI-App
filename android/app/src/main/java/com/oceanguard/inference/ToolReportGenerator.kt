package com.oceanguard.ai.inference

import android.util.Log
import com.oceanguard.ai.data.DetectionSession
import java.text.Normalizer
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
            "get_ecological_impacts",
            "get_survey_statistics",
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

        /**
         * Normalizes a table-cell label for fuzzy matching: lowercase, collapse
         * whitespace, strip combining diacritics so "Botella " and "BOTELLA" both
         * match "botella".
         */
        internal fun normalizeLabel(s: String): String {
            val decomposed = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
            return decomposed
                .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .replace("\\s+".toRegex(), " ")
        }

        /**
         * Builds a normalized-key lookup from a canonical row map. Warns when the
         * same normalized key appears in both materialRows and typeRows with
         * different canonical lines (ambiguous repair).
         */
        internal fun buildNormalizedMap(
            rows: Map<String, String>,
            mapName: String,
            otherRows: Map<String, String>? = null,
        ): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            rows.forEach { (label, line) ->
                val key = normalizeLabel(label)
                result[key] = line
                if (otherRows != null) {
                    val otherLine = otherRows.entries.firstOrNull { normalizeLabel(it.key) == key }?.value
                    if (otherLine != null && otherLine != line) {
                        Log.w(TAG, "Ambiguous canon label '$label' (norm='$key') differs between material and type maps — section-aware repair will resolve")
                    }
                }
            }
            return result
        }

        /**
         * Repairs the model's tables by matching each row label against the
         * canonical map for the current section. Any row whose first cell matches
         * a known label is replaced with the ground-truth line. Section-aware
         * matching avoids material/type collisions when the same translated label
         * appears in both maps with different values.
         *
         * Date repair: any cell containing 5+ consecutive digits is considered
         * corrupted. The row is restored verbatim from the temporal-trend bundle
         * lines if available; otherwise it is left untouched (no lossy substitution).
         */
        internal fun repairHallucinations(text: String, canon: ToolDataBundleFormatter.Canon): String {
            val normMaterial = buildNormalizedMap(canon.materialRows, "materialRows", canon.typeRows)
            val normType = buildNormalizedMap(canon.typeRows, "typeRows")
            val normEco = buildNormalizedMap(canon.ecoRows, "ecoRows")
            val normRisk = buildNormalizedMap(canon.riskRows, "riskRows")

            val canonDateRows: Map<String, String> = buildCanonDateRows(canon)

            val allLabels = (canon.materialRows.keys + canon.typeRows.keys).toSet()
            val lines = text.lines().toMutableList()
            var replaced = 0

            var currentNormMap: Map<String, String> = emptyMap()
            val sectionHeadings = listOf(
                "material" to normMaterial,
                "type" to normType,
                "ecological" to normEco,
                "eco" to normEco,
                "risk" to normRisk,
            )

            for (i in lines.indices) {
                val line = lines[i]
                if (line.trimStart().startsWith("###")) {
                    val lower = line.lowercase()
                    currentNormMap = sectionHeadings.firstOrNull { lower.contains(it.first) }?.second
                        ?: emptyMap()
                    continue
                }
                if (!line.trimStart().startsWith("|")) continue
                if (line.contains("---")) continue

                val firstCell = line.substringAfter("|").substringBefore("|").trim()
                if (firstCell.isEmpty()) continue

                if (Regex("""\d{5,}""").containsMatchIn(line)) {
                    val restored = canonDateRows[firstCell]
                    if (restored != null) {
                        lines[i] = restored
                        replaced++
                    }
                    continue
                }

                val normKey = normalizeLabel(firstCell)
                val canonical = currentNormMap[normKey]
                    ?: normEco[normKey]
                    ?: normRisk[normKey]
                if (canonical != null && canonical != line.trim()) {
                    lines[i] = canonical
                    replaced++
                }
            }

            if (replaced > 0) {
                Log.i(TAG, "Canonical row repair: rewrote $replaced row(s) matching bundle labels=${allLabels.size}")
            }
            var out = lines.joinToString("\n")
            out = Regex("""^\s*\|[\s\.\|]+\|\s*$""", RegexOption.MULTILINE).replace(out, "")
            out = Regex("""\n{3,}""").replace(out, "\n\n")
            return out
        }

        /**
         * Extracts canonical date rows from the temporal-trend section of the
         * bundle. Currently returns empty — Canon does not yet expose date rows,
         * so corrupt dates fall through to the "leave untouched" path.
         */
        internal fun buildCanonDateRows(canon: ToolDataBundleFormatter.Canon): Map<String, String> {
            return emptyMap()
        }
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

        val (bundle, canon) = ToolDataBundleFormatter.formatWithCanon(ctx)
        Log.i(TAG, "Tool-based report: ${sessions.size} sessions, lang=$language, audience=$audience, bundle=${bundle.length} chars")
        runTwoPhase(
            phase1Prompt = prompt,
            tools = tools,
            systemMessage = system,
            requiredToolNames = REQUIRED_TOOLS_GENERIC,
            bundle = bundle,
            canon = canon,
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

        val (bundle, canon) = ToolDataBundleFormatter.formatWithCanon(ctx)
        Log.i(TAG, "Tool-based zone report: ${input.locationName}, ${input.sessions.size} sessions, bundle=${bundle.length} chars")
        runTwoPhase(
            phase1Prompt = prompt,
            tools = tools,
            systemMessage = system,
            requiredToolNames = REQUIRED_TOOLS_ZONE,
            bundle = bundle,
            canon = canon,
            onPartialResult = onPartialResult,
            onToolCallStarted = onToolCallStarted,
        )
    }

    /**
     * Two-phase orchestrator: tool dispatch in one conversation, prose writing in
     * a fresh one. Keeping PHASE 2 isolated from PHASE 1 avoids KV-cache
     * contamination from the model's early placeholder drafts and lets Gemma 4
     * E2B focus on a simple "copy these tables" task using the pre-rendered
     * markdown bundle as context.
     */
    private suspend fun runTwoPhase(
        phase1Prompt: String,
        tools: OceanGuardTools,
        systemMessage: String,
        requiredToolNames: Set<String>,
        bundle: String,
        canon: ToolDataBundleFormatter.Canon,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit,
    ): String {
        // PHASE 1: model invokes tools; returns immediately once all required
        // tools have run (see ToolAgentLoop early-exit). We swallow any stray
        // text the model may have started — it is pre-bundle garbage.
        engine.generateWithTools(
            prompt = phase1Prompt,
            toolSet = tools,
            systemMessage = systemMessage,
            maxToolRounds = MAX_TOOL_ROUNDS,
            requiredToolNames = requiredToolNames,
            dataBundle = bundle,
            onPartialResult = { /* swallow PHASE 1 stream */ },
            onToolCallStarted = onToolCallStarted,
        )

        // PHASE 2: fresh conversation with an ultra-compact writer prompt and the
        // bundle as the sole context. No tools, no multi-turn — one shot.
        val writerSystem = buildWriterSystemMessage(systemMessage)
        Log.i(TAG, "PHASE-2 starting on fresh conversation: bundle=${bundle.length} chars")
        val raw = engine.generateText(
            prompt = bundle,
            maxTokens = MAX_OUTPUT_TOKENS,
            systemMessage = writerSystem,
            assistantPrefill = null,
            thinkingEnabled = false,
            onPartialResult = onPartialResult,
        )
        val repaired = repairHallucinations(raw, canon)
        if (repaired != raw) {
            Log.i(TAG, "Post-process: ${raw.length}→${repaired.length} chars after canonical repair")
            onPartialResult(repaired)
        }
        return repaired
    }

    /**
     * Strips the PROTOCOL/PHASE-1 lines from the main system prompt — at PHASE 2
     * the tools have already run and only the writing rules matter.
     */
    private fun buildWriterSystemMessage(fullSystem: String): String {
        val protocolStart = fullSystem.indexOf("PROTOCOL:")
        val rulesStart = fullSystem.indexOf("RULES:")
        return if (protocolStart >= 0 && rulesStart > protocolStart) {
            fullSystem.substring(0, protocolStart) + fullSystem.substring(rulesStart)
        } else {
            fullSystem
        }
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
        return """
OUTPUT LANGUAGE: $languageName (headings, prose, tables — every word, including all debris-type and material labels).

$persona

PROTOCOL:
  PHASE 1 — call the available tools once each, in any order, with empty arguments. Emit NO prose in PHASE 1, only tool calls.
  Required tools (all no-argument): debris-summary, material-breakdown, type-breakdown, risk-assessment, collection-waypoints, survey-statistics, ecological-impacts${if (kind == ReportKind.ZONE) ", temporal-trend" else ""}.
  PHASE 2 — after the last tool returns, wait for a user turn containing `## CONFIRMED DATA — USE THESE EXACT VALUES`. When you see that block, write the final report by copying its tables into the report structure below.

RULES:
  • CLOSED-WORLD: mention only debris types and materials that appear in the CONFIRMED DATA tables.
  • HUMAN LABELS: use the human-readable labels exactly as they appear in the CONFIRMED DATA tables (already in $languageName). Never revert to UPPER_SNAKE_CASE identifiers like `FISHING_NET` or `PLASTIC_DEBRIS` — the report is for human readers.
  • NO PLACEHOLDERS: never emit `[anything]`, `| ... |`, `TODO`. Every bracketed token is a bug.
  • COPY TABLES AND NUMBERS VERBATIM: reproduce each CONFIRMED DATA table with identical rows, percentages, counts, labels, degradation times ("600+ years" stays "600+ years"), risk scores, and annual volumes. Do not re-tokenize digits (13.3% never becomes 133.3%). Do not drop digits (600+ never becomes 60+).
  • PROSE QUALITY: each section opens with 3–5 sentences of flowing scientific narrative that interprets the numbers (what they mean ecologically, why they matter), then presents the table. Avoid bullet fragments where prose would read better. Connect sections with transitions. Do not mention "tools", "data retrieval", or the report-generation process.
  • TONE from average health score: ≥80 excellent, 70–79 good, 50–69 degraded, <50 critical.

STRUCTURE (## headings in $languageName, in order):
$sections

STYLE: markdown tables `| col | col |`, bullet lists only when genuinely enumerative, no horizontal rules, no code fences around tables. 700–900 words of body.
""".trimIndent()
    }

    private fun buildUserPrompt(
        languageName: String,
        firstHeading: String,
        kind: ReportKind,
        locationName: String? = null,
    ): String {
        val locClause = locationName?.let { " for \"$it\"" } ?: ""
        val kindLabel = if (kind == ReportKind.ZONE) "zone temporal-evolution" else "marine debris assessment"
        return """
Produce the $kindLabel report$locClause in $languageName.

PHASE 1 NOW: call every required tool (each with empty arguments). Emit no prose yet. Wait for my next turn.
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
