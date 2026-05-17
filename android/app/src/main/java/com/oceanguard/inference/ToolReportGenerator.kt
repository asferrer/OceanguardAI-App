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
            // Per-session table source. Without this, the model was forced to
            // fabricate the "Statistical Analysis (per-session table)" rows
            // because no other tool exposed per-image data.
            "get_per_session_details",
        )
        private val REQUIRED_TOOLS_ZONE = REQUIRED_TOOLS_GENERIC + "get_temporal_trend"

        /**
         * Tools whose absence MUST cause the agent loop to discard a partial
         * draft and redirect, regardless of how much prose has streamed out.
         * For generic reports every required tool is critical (no soft tools).
         * For zone reports, [get_temporal_trend] is soft-optional: it returns
         * an empty payload for single-day surveys and the prose section is
         * structured so it can be omitted without breaking the rest of the
         * report.
         */
        private val CRITICAL_TOOLS_GENERIC = REQUIRED_TOOLS_GENERIC
        private val CRITICAL_TOOLS_ZONE = REQUIRED_TOOLS_ZONE - "get_temporal_trend"

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
            // Per-session map keys are already in the "#<id>" form used as the
            // first cell of the analyzed-images table, so no normalisation is
            // needed for the section-row lookup. We still build a normalized
            // version so paraphrased prefixes ("# 42 ", "ID 42") fall through.
            val perSessionByExact = canon.perSessionRows
            val perSessionByNorm = canon.perSessionRows.mapKeys { (k, _) ->
                normalizeLabel(k.removePrefix("#"))
            }

            val canonDateRows: Map<String, String> = buildCanonDateRows(canon)

            val allLabels = (canon.materialRows.keys + canon.typeRows.keys).toSet()
            val lines = text.lines().toMutableList()
            var replaced = 0
            // Lines flagged for removal — Kotlin can't delete from MutableList
            // while iterating by index without shifting. Collect indices, drop
            // in a second pass.
            val linesToDrop = mutableSetOf<Int>()
            // Detect placeholder rows ("| [Foo] | [Bar] |") that the model
            // emitted when it lacked a real value. Closed-world enforcement
            // requires dropping these instead of trying to repair them — the
            // CONFIRMED DATA does not have a row for them, so they are fabricated
            // by definition.
            val placeholderCellPattern = Regex("""\[[^\]]+\]""")

            var currentNormMap: Map<String, String> = emptyMap()
            var inPerSessionSection = false
            val sectionHeadings = listOf(
                "material" to normMaterial,
                "type" to normType,
                "ecological" to normEco,
                "eco" to normEco,
                "risk" to normRisk,
            )
            // Heading fragments (lower-case) that mark the per-session table.
            // Localised forms across the 6 supported languages.
            val perSessionHeadings = listOf(
                "per-analyzed-image", "analyzed image", "analyzed images",
                "imagen analizada", "imágenes analizadas", "por imagen",
                "image analysée", "images analysées", "par image",
                "bild analysiert", "pro analysiertem bild", "analysiert",
                "immagine analizzata", "immagini analizzate", "per immagine",
                "imagem analisada", "imagens analisadas", "por imagem",
                "statistical analysis", "análisis estadístico",
                "analyse statistique", "statistische analyse",
                "analisi statistica", "análise estatística",
            )

            for (i in lines.indices) {
                val line = lines[i]
                if (line.trimStart().startsWith("###")) {
                    val lower = line.lowercase()
                    inPerSessionSection = perSessionHeadings.any { lower.contains(it) }
                    currentNormMap = if (inPerSessionSection) {
                        emptyMap()
                    } else {
                        sectionHeadings.firstOrNull { lower.contains(it.first) }?.second ?: emptyMap()
                    }
                    continue
                }
                if (!line.trimStart().startsWith("|")) continue
                if (line.contains("---")) continue

                val firstCell = line.substringAfter("|").substringBefore("|").trim()
                if (firstCell.isEmpty()) continue

                // Drop ANY row that still contains a "[...]" placeholder. The
                // model uses these when it doesn't have real data — closed-world
                // says: no real data means the row should not exist.
                if (placeholderCellPattern.containsMatchIn(line)) {
                    linesToDrop.add(i)
                    continue
                }

                if (inPerSessionSection) {
                    // Exact match against "#<id>" cells first.
                    val exact = perSessionByExact[firstCell]
                    if (exact != null) {
                        if (exact != line.trim()) {
                            lines[i] = exact
                            replaced++
                        }
                        continue
                    }
                    // Fallback: tolerate "# 42", "ID 42", "Session 42" by normalising.
                    val normKey = normalizeLabel(firstCell.removePrefix("#"))
                        .replace(Regex("""^(id|session|sesi[oó]n|sessione|sess[ãa]o)\s+"""), "")
                    val byNorm = perSessionByNorm[normKey]
                    if (byNorm != null && byNorm != line.trim()) {
                        lines[i] = byNorm
                        replaced++
                        continue
                    }
                    // Row that doesn't match ANY known per-session entry — the
                    // model fabricated a session row. Drop it (closed-world).
                    linesToDrop.add(i)
                    continue
                }

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
                if (canonical != null) {
                    if (canonical != line.trim()) {
                        lines[i] = canonical
                        replaced++
                    }
                    continue
                }
                // CLOSED-WORLD STRIP: this row's first cell is NOT in any canon
                // map. If we are inside a known data section (material/type/eco/
                // risk), the row is fabricated — the survey did not detect this
                // type. Drop it. Outside a known section we leave the row alone
                // (could be a totals row, prose-driven table, etc).
                if (currentNormMap.isNotEmpty()) {
                    linesToDrop.add(i)
                }
            }

            // Drop fabricated/placeholder rows in reverse-index order so prior
            // indices remain valid.
            val dropped = linesToDrop.size
            for (idx in linesToDrop.sortedDescending()) {
                lines.removeAt(idx)
            }

            if (replaced > 0 || dropped > 0) {
                Log.i(TAG, "Canonical row repair: rewrote=$replaced dropped=$dropped (closed-world strip + placeholders) bundle labels=${allLabels.size} per-session=${perSessionByExact.size}")
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
        // Canonicalize debris types before anything reaches the model: the tools,
        // the bundle, and the prose writer all share this canonical projection
        // so the report can only reference the 11 canonical type names.
        val canonSessions = ReportGenerator.canonicalizeSessions(sessions)
        val ctx = ToolReportContext(canonSessions, language, audience, zoneInput = null)
        val tools = OceanGuardTools(ctx)

        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val firstHeading = FIRST_HEADING[language] ?: "## Executive Summary"
        val system = buildSystemMessage(languageName, audience, ReportKind.GENERIC)

        val (bundle, canon) = ToolDataBundleFormatter.formatWithCanon(ctx)
        val prompt = buildUserPrompt(languageName, firstHeading, ReportKind.GENERIC, bundle)
        Log.i(TAG, "Tool-based report: ${sessions.size} sessions, lang=$language, audience=$audience, bundle=${bundle.length} chars (injected in user prompt)")
        runTwoPhase(
            phase1Prompt = prompt,
            tools = tools,
            systemMessage = system,
            requiredToolNames = REQUIRED_TOOLS_GENERIC,
            criticalToolNames = CRITICAL_TOOLS_GENERIC,
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
        // Canonicalize sessions inside the ZoneReportInput too: the tools resolve
        // counts/aggregates from both ctx.sessions and ctx.zoneInput?.sessions
        // so both must use canonical types or per-day vs overall tables diverge.
        val canonSessions = ReportGenerator.canonicalizeSessions(input.sessions)
        val canonInput = input.copy(sessions = canonSessions)
        val ctx = ToolReportContext(canonSessions, language, audience, zoneInput = canonInput)
        val tools = OceanGuardTools(ctx)

        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val firstHeading = FIRST_HEADING_ZONE[language] ?: "## Zone Profile"
        val system = buildSystemMessage(languageName, audience, ReportKind.ZONE)

        val (bundle, canon) = ToolDataBundleFormatter.formatWithCanon(ctx)
        val prompt = buildUserPrompt(languageName, firstHeading, ReportKind.ZONE, bundle, input.locationName)
        Log.i(TAG, "Tool-based zone report: ${input.locationName}, ${input.sessions.size} sessions, bundle=${bundle.length} chars (injected in user prompt)")
        runTwoPhase(
            phase1Prompt = prompt,
            tools = tools,
            systemMessage = system,
            requiredToolNames = REQUIRED_TOOLS_ZONE,
            criticalToolNames = CRITICAL_TOOLS_ZONE,
            bundle = bundle,
            canon = canon,
            onPartialResult = onPartialResult,
            onToolCallStarted = onToolCallStarted,
        )
    }

    /**
     * Single-pass agentic generation.
     *
     * One LiteRT-LM conversation, one KV cache. The model:
     *   1. Calls each required tool — `onToolCallStarted(name)` fires per
     *      invocation so the UI can announce "Querying <tool>…".
     *   2. Receives the JSON responses as `Content.ToolResponse` turns inside
     *      the SAME conversation. The KV cache from the tool dispatch carries
     *      into the writing turn so the model genuinely uses the tool data.
     *   3. Writes the final markdown report. Those tokens stream live to the
     *      user via `onPartialResult` — no swallow, no fresh-conversation
     *      reset, no "Generating…" deadtime.
     *
     * The deterministic [bundle] is no longer injected as a separate PHASE-2
     * prompt; instead it stays available for the post-pass
     * [repairHallucinations] which rewrites any row whose label matches the
     * canonical map. That keeps numbers/labels precise even if the model
     * paraphrases a cell.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun runTwoPhase(
        phase1Prompt: String,
        tools: OceanGuardTools,
        systemMessage: String,
        requiredToolNames: Set<String>,
        criticalToolNames: Set<String>,
        bundle: String,
        canon: ToolDataBundleFormatter.Canon,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit,
    ): String {
        Log.i(TAG, "Agentic report: ${requiredToolNames.size} required tools (${criticalToolNames.size} critical), bundle=${bundle.length} chars (post-repair canon)")
        val raw = engine.generateWithTools(
            prompt = phase1Prompt,
            toolSet = tools,
            systemMessage = systemMessage,
            maxToolRounds = MAX_TOOL_ROUNDS,
            requiredToolNames = requiredToolNames,
            criticalToolNames = criticalToolNames,
            // null → ToolAgentLoop keeps every prose token and streams it to the
            // user. The single-conversation flow means the report writing phase
            // simply continues after the last tool turn with the KV cache intact.
            dataBundle = null,
            onPartialResult = onPartialResult,
            onToolCallStarted = onToolCallStarted,
        )
        val repaired = repairHallucinations(raw, canon)
        if (repaired != raw && repaired.isNotBlank()) {
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

DEBRIS VOCABULARY (closed set — the FULL universe of types your training has seen is 11 entries: Bottle, Can, Fishing Net, Glove, Mask, Metal Debris, Plastic Debris, Tire, Fabric Debris, Glass Debris, Other. THIS SURVEY has typically detected only a SUBSET of those — usually 3-6. The CONFIRMED DATA section the user gave you lists EVERY row that should appear in every table. NEVER add a row for a type that is not in CONFIRMED DATA, even if you remember it from training.) Use the translated forms from the CONFIRMED DATA tables for the final report — never English snake_case identifiers.

$persona

PROTOCOL:
  Step 1: Invoke EVERY one of these tools, ONCE each, with empty arguments, BEFORE writing any prose. Emit only tool calls in this step — no commentary, no preamble. Skipping ANY tool will produce a wrong report.
    (1) per-session-details   ← MANDATORY. Returns one row per analyzed image. The per-image table is impossible without this.
    (2) debris-summary
    (3) material-breakdown
    (4) type-breakdown
    (5) ecological-impacts
    (6) risk-assessment
    (7) collection-waypoints
    (8) survey-statistics${if (kind == ReportKind.ZONE) "\n    (9) temporal-trend" else ""}
  Step 2: As soon as the last tool has returned, write the FINAL report directly in this same turn. Use ONLY the numbers, percentages, labels and rows returned by the tools — do not invent values, do not paraphrase row labels. The very next characters you produce after the last tool response MUST be the first heading of the report (see STRUCTURE).

RULES:
  • CLOSED-WORLD: mention only debris types and materials that appear in the CONFIRMED DATA tables.
  • HUMAN LABELS: use the human-readable labels exactly as they appear in the CONFIRMED DATA tables (already in $languageName). Never revert to UPPER_SNAKE_CASE identifiers like `FISHING_NET` or `PLASTIC_DEBRIS` — the report is for human readers.
  • NO PLACEHOLDERS: never emit `[anything]`, `| ... |`, `TODO`. Every bracketed token is a bug.
  • COPY TABLES AND NUMBERS VERBATIM: reproduce each CONFIRMED DATA table with identical rows, percentages, counts, labels, degradation times ("600+ years" stays "600+ years"), risk scores, and annual volumes. Do not re-tokenize digits (13.3% never becomes 133.3%). Do not drop digits (600+ never becomes 60+).
  • PER-SESSION TABLE: rows MUST come exclusively from get_per_session_details. The first cell is the session id (as returned, prefixed with "#"). Never invent session ids, dates or per-image counts; never describe images that are not in that tool's response. If a number contradicts the tool data, the tool data wins.
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
        bundle: String,
        locationName: String? = null,
    ): String {
        val locClause = locationName?.let { " for \"$it\"" } ?: ""
        val kindLabel = if (kind == ReportKind.ZONE) "zone temporal-evolution" else "marine debris assessment"
        // The bundle is injected as CONFIRMED DATA in the first user turn so the
        // model has every row of every table in its KV cache BEFORE writing any
        // prose. Tool calls in Step 1 still happen (the agentic showcase), but
        // they now serve as a verification pass — each tool returns the same
        // structured data that is already present in the bundle. This makes the
        // prose generation a one-shot grounded write rather than a fragile
        // reconstruction from JSON tool responses, eliminating the placeholder
        // / fabricated-type / repeated-token failure modes observed up to v0.2.7.
        return """
Produce the $kindLabel report$locClause in $languageName.

CONFIRMED DATA — every number, percentage, label, row below comes from this
survey's database. The report MUST use ONLY these values. Do not paraphrase
labels, do not invent rows, do not add types not present below.

$bundle

PROTOCOL:
  PHASE 1 NOW: call every required tool (each with empty arguments). The
  tool responses will repeat the data above in JSON form; that is expected
  and is the verification pass.
  PHASE 2 (this same turn, immediately after the last tool returns): write
  the FINAL report. The very next characters after the last tool response
  MUST be "$firstHeading". Use only the CONFIRMED DATA above. Do not emit
  any token wrapped in `[...]` — every placeholder is a bug.
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
