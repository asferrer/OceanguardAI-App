package com.oceanguard.ai.inference

import android.util.Log
import com.oceanguard.ai.data.DetectionSession
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lifecycle phases a tool-calling report goes through. Surfaced to the UI via
 * [ToolReportGenerator.generateReportWithToolsStreaming.onPhaseChanged] so the
 * GeneratingBanner can swap its message between "Querying tools…" and
 * "Composing report prose…" — the two phases have very different perceived
 * durations and the user otherwise sees a single opaque 3-5 min wait.
 */
enum class ReportPhase {
    /** Engine is loading or model warming up. UI shows "Loading analysis engine…". */
    LOADING,

    /** PHASE 1 — Gemma 4 is dispatching typed tools (~20-30 s on Exynos 2200). */
    PHASE_1_TOOLS,

    /** PHASE 2 — fresh conversation writing prose with the bundle in KV cache. */
    PHASE_2_PROSE,
}

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
        // Must not exceed LiteRTTextEngine.MAX_TOKENS minus typical prompt size
        // (~1.6K tokens for PHASE 2). With MAX_TOKENS=5120 in the engine, this
        // leaves ~3.5K for output — covers the 2.4K typical and a 1.4× spike.
        private const val MAX_OUTPUT_TOKENS = 3072

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
            // get_collection_waypoints intentionally removed: the spatial table
            // was discarded long ago; no section of the report consumes it.
            // Keeping it REQUIRED was costing ~3:19 per report — when the model
            // happened to write prose right after get_temporal_trend, the agent
            // loop detected "waypoints missing" and issued a redirect that took
            // ~3 min to land the obsolete tool call (logcat 2026-05-18 09:48–
            // 09:51). The method itself stays in OceanGuardTools (callable for
            // the agentic showcase / future use) but is no longer mandatory.
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
            // Section-heading keywords mapped to their canon row source.
            // English fragments first (used by the bundle's own ### headings),
            // then translated variants because the writer model emits headings
            // in the report's target language (e.g. "Composición por Tipo"
            // never contains the English word "type").
            val sectionHeadings = listOf(
                // Material composition
                "material" to normMaterial,         // EN / ES / PT (Composición de Material)
                "matériau" to normMaterial,         // FR
                "materiale" to normMaterial,        // IT
                "materialzusammensetzung" to normMaterial, // DE (compound noun)

                // Type composition
                "type" to normType,                 // EN / FR (typecomposition / type)
                "tipo" to normType,                 // ES / IT / PT
                "typ" to normType,                  // DE / PL

                // Ecological impact
                "ecological" to normEco,            // EN
                "ecológic" to normEco,              // ES
                "écologique" to normEco,            // FR
                "ecologico" to normEco,             // IT
                "ökologisch" to normEco,            // DE
                "eco" to normEco,                   // generic fallback (matches both)

                // Risk
                "risk" to normRisk,                 // EN
                "riesgo" to normRisk,               // ES
                "risque" to normRisk,               // FR
                "rischio" to normRisk,              // IT
                "risiko" to normRisk,               // DE
                "risco" to normRisk,                // PT
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
                // Detect both `##` (top-level — what the writer model emits)
                // and `###` (sub-section — what the bundle uses). Without the
                // `##` branch, sections in the model's report would never be
                // recognised and the row-level fallback below would happily
                // rewrite every "Botella" row to its ecological-impact version,
                // corrupting tables that substituteTablePlaceholders had just
                // inserted correctly.
                val trimmedStart = line.trimStart()
                if (trimmedStart.startsWith("##")) {
                    val lower = line.lowercase()
                    inPerSessionSection = perSessionHeadings.any { lower.contains(it) }
                    // Combined section headings like
                    //   "## Composición de Residuos (tablas por Material y por Tipo)"
                    // match BOTH "material" and "tipo". firstOrNull (the previous
                    // implementation) picked "material" and silently dropped every
                    // row of the type table during closed-world strip — observed
                    // in report id=13 (2026-05-18), 4 rows dropped. Build the
                    // UNION of every matched map so a row qualifies as legitimate
                    // when it appears in ANY of the sections referenced by the
                    // heading. Ambiguous heading → wider acceptance, never
                    // narrower.
                    currentNormMap = if (inPerSessionSection) {
                        emptyMap()
                    } else {
                        val matched = sectionHeadings.filter { lower.contains(it.first) }
                        when (matched.size) {
                            0 -> emptyMap()
                            1 -> matched[0].second
                            else -> {
                                // Deduplicate distinct row maps then merge. Most
                                // entries in sectionHeadings point to the same
                                // normMap (one per language), so a "##
                                // Eco-Impact" heading matching both "ecological"
                                // and "eco" still resolves to a single map.
                                val distinctMaps = matched
                                    .map { it.second }
                                    .distinctBy { System.identityHashCode(it) }
                                if (distinctMaps.size == 1) distinctMaps[0]
                                else distinctMaps.fold(LinkedHashMap<String, String>()) { acc, m ->
                                    acc.also { it.putAll(m) }
                                }
                            }
                        }
                    }
                    continue
                }
                if (!line.trimStart().startsWith("|")) continue
                if (line.contains("---")) continue

                // Header rows are immediately followed by a separator (`|---|`).
                // The substituted tables from canon.tablesByKey always start
                // with a header row whose first cell is the column NAME
                // ("Material", "Tipo", "Lat", "ID", …) — none of those are in
                // the canon row maps, so the closed-world strip below would
                // wrongly delete the header, leaving the report with bare
                // `|---|---:|---:|` separator lines as the table starter (bug
                // observed in report id=11, 2026-05-17). Skip header rows.
                val nextLine = if (i + 1 < lines.size) lines[i + 1] else ""
                val isHeaderRow = nextLine.trimStart().startsWith("|") && nextLine.contains("---")
                if (isHeaderRow) continue

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
                // CRITICAL: only rewrite rows when we KNOW which section we are
                // in. The previous fallback `?: normEco[normKey] ?: normRisk[normKey]`
                // corrupted substituted tables: a row like `| Botella | 2 | 13% |`
                // in the type section had `currentNormMap` empty (section
                // unrecognised in the report's target language) and the fallback
                // would happily replace it with the ecological-impact row
                // `| Botella | Más de 450 años | … | 8 | ~1M toneladas/año |`,
                // producing a 5-column row inside a 3-column table header.
                val canonical = currentNormMap[normKey]
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

        /**
         * Removes every line that is a markdown table row from [text]. Lines
         * are considered table rows when, after trimming, they start with `|`
         * AND end with `|`. This catches:
         *   • data rows           `| Bottle | 5 | 63% |`
         *   • header rows         `| Type | Count | Percent |`
         *   • separator rows      `|---|---:|---:|`
         *   • malformed rows      `| Bottle | Más de 450 años | ... |`
         *
         * It ALSO drops bullet-list rows that contain two or more pipes —
         * Gemma 4 E2B disguises duplicate tables as bullet lists right after
         * the [TABLE: …] substitution, e.g.
         *   * Botella | 4 | 50%
         *   * Material | Conteo | Porcentaje
         * Legit bullet summaries (Resumen Ejecutivo "- Total: **8**") have
         * zero pipes, so the >=2-pipe threshold avoids touching them.
         *
         * Finally collapses any 3+ consecutive blank lines down to 2 so the
         * placeholder-substitution step inserts tables in a clean layout.
         */
        /**
         * Repairs decode-noise patterns introduced by speculative decoding in
         * PHASE 2 prose. Touches narrative only — tables come from canon and
         * are already correct.
         *
         * Three classes of errors observed across report ids 17-20 (2026-05-18):
         *
         *  1. Word-and-number fused without space: "puntuación de8", "riesgo7",
         *     "salud de67". Specific Spanish lead-words (`de`, `riesgo`,
         *     `salud`, `años`, `urgencia`, `puntuación`) followed immediately
         *     by digits → insert space. Image IDs like `#148` are skipped
         *     because the `#` is not in the allowed prefix set.
         *
         *  2. Truncated Spanish words at the end of a token boundary. SD
         *     accepted a draft token that ended mid-word: `monitore` instead
         *     of `monitoreo`, `Prómos` instead of `Próximos`, `sustancias tóx`
         *     instead of `sustancias tóxicas`. Whitelist-replace only when
         *     the truncation is followed by a word boundary so we don't touch
         *     legitimate strings.
         *
         *  3. The heading `## Conclusiones y Prómos pasos` and similar
         *     section-title decode noise. Targeted replacements aligned with
         *     the localized section labels (see [SECTION_LABELS]).
         *
         * We deliberately do NOT try to repair number-magnitude errors
         * ("60 años" when the table says "600 años") because we cannot know
         * which value the model meant without a confidence anchor. The prompt
         * rule "NUMERIC FIDELITY" handles those at generation time.
         */
        internal fun repairProseDecodeNoise(text: String): String {
            var out = text

            // Word + digit fused. Lead words come from the prose vocabulary we
            // observed contracting. Anchored to a word boundary so URLs / IDs
            // (`v0.2.10`, `#148`) are not touched.
            val fusedWordDigit = Regex(
                "(?i)\\b(de|riesgo|salud|años|urgencia|puntuación|porcentaje|frecuencia|conteo|total)(\\d)",
            )
            out = fusedWordDigit.replace(out) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }

            // Common Spanish truncations seen in PHASE 2 output. Each entry is
            // anchored on a word boundary on the right so we only replace
            // when the truncated form ends a token.
            val truncations = listOf(
                Regex("""\bmonitore\b""")    to "monitoreo",
                Regex("""\btóx\b""")          to "tóxicas",
                Regex("""\bPrómos\b""")       to "Próximos",
                Regex("""\bPrónes\b""")       to "Próximos",
                Regex("""\bPróms\b""")        to "Próximos",
                Regex("""\bdesech\b""")       to "desechos",
                Regex("""\bplástic\b""")      to "plástico",
                Regex("""\bingestió\b""")     to "ingestión",
                Regex("""\byredo\b""")        to "y enredo",
                Regex("""\bcontaminacion\b""") to "contaminación",
                Regex("""\bheterogeneidad\b""") to "heterogénea",
                Regex("""\blixión\b""")        to "lixiviación",
                Regex("""\brecole\b""")        to "recolección",
            )
            for ((pattern, replacement) in truncations) {
                out = pattern.replace(out, replacement)
            }

            // Deduplicate immediately-repeated words like "redes de redes" or
            // "de un solo de un" — SD sometimes accepts a draft segment that
            // double-emits a short phrase. Pattern: same 2-3 word run repeated
            // back-to-back with one space. Only collapses exact duplicates so
            // legitimate repetition ("cada día cada vez") is left alone.
            out = Regex("""\b(\w{2,12}(?:\s+\w{2,12}){0,2})\s+\1\b""")
                .replace(out) { it.groupValues[1] }

            // Heading-specific repair. The truncation pass produces "Próximos"
            // from "Prómos"/"Prónes"/"Próms"; here we also normalise the case
            // ("pasos" → "Pasos") and recover from partial truncations where
            // even "pasos" was lost ("Conclusiones y Próximos" alone).
            out = out.replace(
                Regex("""## Conclusiones y Próximos pasos"""),
                "## Conclusiones y Próximos Pasos",
            )
            out = out.replace(
                Regex("""## Conclusiones y Próximos$""", RegexOption.MULTILINE),
                "## Conclusiones y Próximos Pasos",
            )

            return out
        }

        internal fun stripModelWrittenTables(text: String): String {
            val pipeRowRegex = Regex("""^\s*\|.*$""")
            val bulletPipeRegex = Regex("""^\s*[*\-]\s+.*\|.*\|.*$""")
            val keptLines = text.lines().filter { line ->
                !pipeRowRegex.matches(line) && !bulletPipeRegex.matches(line)
            }
            return keptLines.joinToString("\n")
                .replace(Regex("""\n{3,}"""), "\n\n")
        }
    }

    suspend fun generateReportWithToolsStreaming(
        sessions: List<DetectionSession>,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit = {},
        /**
         * Cumulative progress within PHASE 1: (current, total, snake_case name).
         * `current` is 1-based and counts unique tools dispatched so far in this
         * report. `total` equals the required-tools set size. Used by the UI to
         * render "(3/8) Querying material breakdown…".
         */
        onToolCallProgress: (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
        /**
         * Phase transitions: LOADING → PHASE_1_TOOLS → PHASE_2_PROSE. The UI
         * swaps its banner copy on each transition. Emitted before the work of
         * the new phase starts.
         */
        onPhaseChanged: (ReportPhase) -> Unit = {},
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
        val system = buildSystemMessage(languageName, language, audience, ReportKind.GENERIC)

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
            kind = ReportKind.GENERIC,
            onPartialResult = onPartialResult,
            onToolCallStarted = onToolCallStarted,
            onToolCallProgress = onToolCallProgress,
            onPhaseChanged = onPhaseChanged,
        )
    }

    suspend fun generateZoneReportWithToolsStreaming(
        input: ZoneReportInput,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit = {},
        onToolCallProgress: (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
        onPhaseChanged: (ReportPhase) -> Unit = {},
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
        val system = buildSystemMessage(languageName, language, audience, ReportKind.ZONE)

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
            kind = ReportKind.ZONE,
            onPartialResult = onPartialResult,
            onToolCallStarted = onToolCallStarted,
            onToolCallProgress = onToolCallProgress,
            onPhaseChanged = onPhaseChanged,
        )
    }

    /**
     * Two-phase generation with a fresh PHASE 2 conversation.
     *
     * PHASE 1 — Tool dispatch (showcase). LiteRT-LM conversation A is opened
     * with every @Tool method registered. The model is asked to invoke each
     * required tool ONCE so the UI can stream "Querying <tool>…" banners. Any
     * prose the model tries to emit in this conversation is discarded — its
     * KV cache is throwaway. Latency: ~5-15 s for 9 sequential dispatches.
     *
     * PHASE 2 — Prose writing (clean KV). A FRESH LiteRT-LM conversation B is
     * opened with NO tools. The system message contains only the writing
     * rules (PROTOCOL stripped). The user message embeds the deterministic
     * [bundle] — every row of every table the report needs, pre-rendered as
     * markdown. The model writes the full report in ONE coherent pass with
     * those rows already in its KV cache. Latency: ~3-6 min of decode.
     *
     * Why this beats single-conversation:
     *  - No redirect cycles: PHASE 2 cannot discard prose mid-stream, so KV
     *    cache cannot be polluted by a half-committed draft (the v0.2.7 /
     *    v0.2.8 failure mode).
     *  - No fabricated tables: the bundle is the SINGLE data source the
     *    writer model sees, so it cannot invent rows for types the survey
     *    did not detect.
     *  - Tool-calling showcase intact: PHASE 1 still triggers every typed
     *    Kotlin tool, every `onToolCallStarted(name)` callback still fires,
     *    the "8-9 typed tools dispatched" narrative remains visible to the
     *    user and in logcat.
     */
    private suspend fun runTwoPhase(
        phase1Prompt: String,
        tools: OceanGuardTools,
        systemMessage: String,
        requiredToolNames: Set<String>,
        @Suppress("UNUSED_PARAMETER") criticalToolNames: Set<String>,
        bundle: String,
        canon: ToolDataBundleFormatter.Canon,
        kind: ReportKind,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit,
        onToolCallProgress: (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
        onPhaseChanged: (ReportPhase) -> Unit = {},
    ): String {
        // ---------- PHASE 1 — tool dispatch showcase ----------
        Log.i(
            TAG,
            "PHASE 1: dispatching ${requiredToolNames.size} tools (throwaway conversation); bundle=${bundle.length} chars",
        )
        onPhaseChanged(ReportPhase.PHASE_1_TOOLS)
        // Track unique tool names dispatched so the UI banner can render a
        // monotonic "(3/8) Querying material-breakdown…" counter regardless of
        // whether the model fires all tools in one turn or across rounds.
        val seen = LinkedHashSet<String>()
        val total = requiredToolNames.size.coerceAtLeast(1)
        val wrappedToolCallback: (String) -> Unit = { name ->
            onToolCallStarted(name)
            if (seen.add(name)) {
                onToolCallProgress(seen.size, total, name)
            }
        }
        // Pass the bundle so ToolAgentLoop runs in PHASE-1 mode:
        //  - any prose token past 64 chars aborts the turn immediately
        //  - when every required tool has run, the loop returns "" so we move
        //    on to PHASE 2 without waiting on more decode.
        // We intentionally do NOT forward onPartialResult here — PHASE 1 prose
        // is throwaway and should not appear in the user-visible stream.
        engine.generateWithTools(
            prompt = phase1Prompt,
            toolSet = tools,
            systemMessage = systemMessage,
            maxToolRounds = MAX_TOOL_ROUNDS,
            requiredToolNames = requiredToolNames,
            dataBundle = bundle,
            onPartialResult = { /* discard PHASE 1 prose */ },
            onToolCallStarted = wrappedToolCallback,
        )

        // ---------- PHASE 2 — prose writing on a fresh conversation ----------
        val writerSystem = buildWriterSystemMessage(systemMessage)
        val phase2Prompt = buildPhase2WriterPrompt(bundle, kind)
        Log.i(
            TAG,
            "PHASE 2: writing prose on fresh conversation (system=${writerSystem.length} chars, prompt=${phase2Prompt.length} chars)",
        )
        onPhaseChanged(ReportPhase.PHASE_2_PROSE)
        val raw = engine.generateText(
            prompt = phase2Prompt,
            maxTokens = MAX_OUTPUT_TOKENS,
            systemMessage = writerSystem,
            onPartialResult = onPartialResult,
        )

        // ---------- POST-PROCESS — strip model tables + substitute + repair ----------
        // 1. STRIP every markdown table row the model wrote. Gemma 4 E2B
        //    ignores the "no `|` rows" rule in some sections (typically the
        //    type-composition and risk-ranking sections) and emits raw rows
        //    that are wrong — wrong columns (5 cells where 3 belong), wrong
        //    values copied from a different table in its KV cache. By
        //    deleting EVERY pipe-table line we guarantee that the only
        //    tables in the final output come from substituteTablePlaceholders
        //    below, which inserts the deterministic bundle tables.
        val stripped = Companion.stripModelWrittenTables(raw)
        // 2. Substitute [TABLE: <key>] placeholders with the deterministic
        //    bundle tables (Gemma 4 E2B cannot reliably copy multi-digit
        //    percentages from markdown, so we replace at the Kotlin layer).
        val substituted = substituteTablePlaceholders(stripped, canon)
        // 3. Run the existing canonical-row repair as a defensive layer in
        //    case any rows from step 2 still need closed-world strip.
        val repaired = repairHallucinations(substituted, canon)
        // 4. Repair common decode-noise patterns introduced by speculative
        //    decoding in PHASE 2 prose (word+digit fused, common Spanish
        //    typos). The table data is already correct (it came from canon);
        //    this pass only touches the narrative around them.
        val proseRepaired = Companion.repairProseDecodeNoise(repaired)
        if (proseRepaired != raw) {
            Log.i(
                TAG,
                "Post-process: ${raw.length}→${stripped.length} (strip)→${substituted.length} (sub)→${repaired.length} (repair)→${proseRepaired.length} (prose)",
            )
            onPartialResult(proseRepaired)
        }
        return proseRepaired
    }

    /**
     * Removes every line that is a markdown table row from [text]. Lines are
     * considered table rows when, after trimming, they start with `|` AND end
     * with `|`. This catches:
     *   • data rows           `| Bottle | 5 | 63% |`
     *   • header rows         `| Type | Count | Percent |`
     *   • separator rows      `|---|---:|---:|`
     *   • malformed rows      `| Bottle | Más de 450 años | ... |` (5 cols)
     *
     * Lines starting with `|` but not closing with `|` (rare, partial output)
     * are also stripped to keep the post-process resilient.
     *
     * Bullet lists (`-`) and headings (`##`) are preserved. Bullet "totals"
     * blocks emitted by the bundle still survive because they don't use `|`.
     *
     * Finally collapses any 3+ consecutive blank lines down to 2 so the
     * placeholder-substitution step inserts tables in a clean layout.
     */
    // stripModelWrittenTables now lives in the companion object so it can be
    // exercised by unit tests via ToolReportGenerator.stripModelWrittenTables.

    /**
     * Builds the PHASE 2 user prompt: the deterministic markdown bundle plus
     * a strict instruction to ONLY write prose and emit `[TABLE: KEY]`
     * placeholders where a table belongs. Gemma 4 E2B is empirically unable
     * to copy multi-digit percentages from markdown tables (we observed
     * "62.5%" → "637.5%" mis-transcriptions on production reports). The
     * post-process substituter resolves every placeholder against the
     * deterministic [ToolDataBundleFormatter.Canon.tablesByKey] map, so the
     * table content is guaranteed correct regardless of what the model
     * would have transcribed.
     */
    private fun buildPhase2WriterPrompt(bundle: String, kind: ReportKind): String {
        val hasTrend = kind == ReportKind.ZONE
        val trendLine = if (hasTrend) "  [TABLE: temporal]      → temporal-trend table (zone reports)\n" else ""
        return """
You are writing the body of a marine debris report.

CONFIRMED DATA (read-only reference; the post-processor uses it to insert
the actual tables — your prose must AGREE with these numbers but you must
NOT copy the table rows yourself, you WILL get the digits wrong):

$bundle

YOUR JOB: write the report as PROSE only, with markdown headings. Wherever
a table belongs, write a literal placeholder on its own line — exactly one
of these tokens:

  [TABLE: totals]         → survey totals (bullet list)
  [TABLE: material]       → material composition table
  [TABLE: type]           → debris-type composition table
  [TABLE: ecological]     → ecological impact table
  [TABLE: risk]           → risk ranking table
  [TABLE: stats]          → session statistics (bullet list)
  [TABLE: per_session]    → per-analyzed-image detail table
$trendLine
Examples of what to write (with $hasTrend=zone):

  ## Composición por Material

  Three sentences interpreting which materials dominate, what their
  ecological signal is, and how that shapes priorities…

  [TABLE: material]

  ## Impacto Ecológico

  Three to five sentences on degradation times and primary risks…

  [TABLE: ecological]

HARD RULES (read these twice):
  • Do NOT write any `|` markdown table rows yourself.
  • Do NOT translate a placeholder. `[TABLE: material]` is a literal token;
    write it verbatim, in English, on its own line.
  • Numbers, percentages and row labels in your PROSE must agree with
    CONFIRMED DATA. If a type is absent from CONFIRMED DATA, do not
    mention it.
  • Prose sections: 3-5 sentences each. No bullet fragments unless
    explicitly recommending actions.

Write the report now. Follow the STRUCTURE from the system message; emit
`[TABLE: …]` placeholders at the spots indicated; do not skip any section.
""".trimIndent()
    }

    /**
     * Replaces every `[TABLE: KEY]` placeholder in [text] with the canonical
     * markdown table from [canon.tablesByKey]. Unrecognised keys collapse to
     * an empty line so the report never contains a stray placeholder.
     *
     * If the model forgot to emit a placeholder for a table that we have
     * data for AND the corresponding section heading appears in the text,
     * we append the table at the end of that section heading's prose as a
     * defensive fallback (handled by the second pass).
     */
    private fun substituteTablePlaceholders(
        text: String,
        canon: ToolDataBundleFormatter.Canon,
    ): String {
        val pattern = Regex("""\[TABLE:\s*([a-zA-Z_]+)\s*\]""")
        var replaced = 0
        var dedupedDuplicates = 0
        val seenKeys = mutableSetOf<String>()
        val firstPass = pattern.replace(text) { match ->
            val key = match.groupValues[1].trim().lowercase()
            // Deduplicate: Gemma 4 emits the same placeholder multiple times
            // when narrating each row of a table in its own prose paragraph
            // (observed in report id=15: [TABLE: ecological] × 3 and
            // [TABLE: risk] × 4 because the writer described each debris
            // type in a separate sentence). One table per key is enough — the
            // duplicates produced a report with the same table copy-pasted 4×
            // back-to-back.
            if (key in seenKeys) {
                dedupedDuplicates++
                return@replace ""
            }
            val block = canon.tablesByKey[key]
            if (block.isNullOrBlank()) {
                Log.w(TAG, "Unknown table placeholder key=$key; stripping")
                ""
            } else {
                seenKeys.add(key)
                replaced++
                // Wrap in blank lines so two adjacent placeholders
                //   [TABLE: material]
                //   [TABLE: type]
                // don't render as a single fused table (markdown joins
                // contiguous `|` rows into one table). Observed in report
                // id=16, 2026-05-18: the type-table header appeared as a
                // 5th data row of the material table. The trailing \n\n
                // pushes the next placeholder onto its own paragraph; the
                // leading \n\n is harmless when the placeholder sits right
                // after a heading (3+ newlines collapse to 2 anyway).
                "\n\n" + block.trim() + "\n\n"
            }
        }
        // Defensive fallback: if the model failed to emit a placeholder for a
        // table we have data for, append it at the end so the report still
        // includes the canonical data. Skips totals/stats because they are
        // bullet lists that we don't want to dangle.
        // waypoints intentionally OMITTED: the spatial table was discarded long
        // ago; the per-image table already carries lat/lon per analyzed image
        // and the Monitoring Protocol is now prose per debris type. Leaving
        // `waypoints` here force-appended the old coordinate table at the end
        // of every zone report (bug seen in report id=11, 2026-05-17).
        val tablesAlwaysShown = setOf("material", "type", "ecological", "risk", "per_session", "temporal")
        val missing = tablesAlwaysShown
            .filter { it !in seenKeys && canon.tablesByKey[it]?.isNotBlank() == true }
        val finalText = if (missing.isEmpty()) {
            firstPass
        } else {
            Log.w(TAG, "Tables not emitted by writer (appending at end): $missing")
            buildString {
                append(firstPass.trimEnd())
                append("\n\n")
                for (k in missing) {
                    canon.tablesByKey[k]?.let { append(it).append("\n\n") }
                }
            }.trimEnd()
        }
        if (replaced > 0 || missing.isNotEmpty() || dedupedDuplicates > 0) {
            Log.i(
                TAG,
                "Placeholder substitution: resolved=$replaced deduped=$dedupedDuplicates appended=${missing.size} totalTables=${canon.tablesByKey.size}",
            )
        }
        // Collapse the extra blank lines introduced when we wrapped each
        // substituted block in \n\n…\n\n (and when the model already had a
        // blank line around the placeholder).
        return finalText.replace(Regex("""\n{3,}"""), "\n\n")
    }

    /**
     * Builds the PHASE 2 (writer) system message. Re-uses the personality and
     * STRUCTURE section from [fullSystem] but REPLACES the RULES with rules
     * tailored to the placeholder approach: prose-only output, no markdown
     * tables, emit `[TABLE: <key>]` placeholders where tables belong.
     */
    private fun buildWriterSystemMessage(fullSystem: String): String {
        val protocolStart = fullSystem.indexOf("PROTOCOL:")
        val rulesStart = fullSystem.indexOf("RULES:")
        val structureStart = fullSystem.indexOf("STRUCTURE")
        // Keep the language/persona preamble (everything before PROTOCOL)
        val preamble = if (protocolStart >= 0) fullSystem.substring(0, protocolStart) else fullSystem
        // Keep the STRUCTURE block from the original
        val structureAndStyle = if (structureStart >= 0) fullSystem.substring(structureStart) else ""
        // Replace RULES entirely with placeholder-aware rules
        val placeholderRules = """
RULES (read carefully — your output is post-processed):
  • TABLES — NEVER write them. For every place a table belongs, emit a single
    line with one of these placeholder tokens, in English, verbatim:
        [TABLE: totals]       [TABLE: material]    [TABLE: type]
        [TABLE: ecological]   [TABLE: risk]        [TABLE: stats]
        [TABLE: per_session]  [TABLE: temporal]
    The post-processor swaps each placeholder for the canonical table. If
    you write `|` markdown rows yourself, the digits WILL be wrong; the
    post-processor cannot recover paraphrased percentages.
  • NEVER duplicate placeholder data as a bullet list AFTER the placeholder.
    `* Plástico | 5 | 63%` or `- Plástico | 5 | 63%` style bullets that
    repeat the same rows as a table are wasted decode — the post-processor
    DELETES them on sight. The placeholder IS the table; one is enough.
  • EACH PLACEHOLDER APPEARS AT MOST ONCE. Do NOT emit `[TABLE: ecological]`
    after every paragraph describing a debris type — emit it ONE TIME after
    the section's prose, then continue with the next section. Repeated
    placeholders are dropped by the post-processor (only the first survives),
    so the duplicates are wasted decode. If you find yourself describing
    types in separate paragraphs and feel tempted to put a placeholder after
    each, merge the paragraphs into a single section ending with one
    placeholder.
  • PROSE IS QUALITATIVE ONLY. Do NOT write specific numbers in your prose
    (no counts, no percentages, no degradation years, no risk scores, no
    image IDs, no annual volumes). All numeric values belong to the tables
    that follow the prose — the post-processor inserts the correct values
    there. When you want to reference a value, point the reader to the table
    instead with phrases like "ver tabla siguiente", "según la tabla de
    composición", "as shown in the risk table". Forbidden examples (any
    number in narrative prose is a bug):
      ✗ "el plástico representa el 63% de la muestra"
      ✓ "el plástico es el material dominante (ver tabla)"
      ✗ "tiempo de degradación de más de 450 años"
      ✓ "tiempo de degradación muy elevado (ver tabla de impacto)"
    Comparative language is fine ("mayoría", "minoritario", "el más alto",
    "preocupante") — those are qualitative judgments based on the table you
    can see but don't need to copy. This rule eliminates the entire class
    of decode-noise typos in prose ("250%" instead of "25%", "45000 años"
    instead of "450", "puntuación 28" instead of "8") because the model
    cannot mistype a number it never writes.
  • PROSE in your sections must AGREE with the CONFIRMED DATA you were
    given. Quote percentages and counts exactly as written there.
  • CLOSED-WORLD — mention only debris types and materials that appear in
    CONFIRMED DATA. Never add a row, type or percentage for a category that
    is absent from the data.
  • NO BRACKET TOKENS other than the [TABLE: ...] placeholders. Never write
    `[Foo]`, `[Valor]`, `[xxx_anual]` etc — these are template artefacts.
  • SECTION HEADINGS — use `## <Heading>` (two hashes). Do not nest `###`.
  • PROSE QUALITY — each section opens with 3-5 sentences of flowing
    scientific narrative that interprets the numbers (what they mean
    ecologically, why they matter). Follow the prose with a placeholder line
    where indicated. Use transitions between sections.
  • TONE from average health score: ≥80 excellent, 70-79 good, 50-69
    degraded, <50 critical.

"""
        return preamble + placeholderRules + structureAndStyle
    }

    private enum class ReportKind { GENERIC, ZONE }

    private fun buildSystemMessage(
        languageName: String,
        languageCode: String,
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
        val sections = sectionList(kind, languageCode)
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
    (7) survey-statistics${if (kind == ReportKind.ZONE) "\n    (8) temporal-trend" else ""}
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

    /**
     * PHASE 1 user prompt — terse. The model's only job here is to dispatch
     * every required tool so the UI showcase fires. Prose is discarded by
     * the agent loop (PHASE-1 mode via dataBundle != null), so we don't
     * bother instructing the model to write anything. PHASE 2 runs on a
     * fresh conversation with the actual writing prompt.
     */
    private fun buildUserPrompt(
        languageName: String,
        @Suppress("UNUSED_PARAMETER") firstHeading: String,
        kind: ReportKind,
        @Suppress("UNUSED_PARAMETER") bundle: String,
        locationName: String? = null,
    ): String {
        val locClause = locationName?.let { " for \"$it\"" } ?: ""
        val kindLabel = if (kind == ReportKind.ZONE) "zone temporal-evolution" else "marine debris assessment"
        return """
Producing the $kindLabel report$locClause in $languageName.

STEP 1 NOW: invoke every required tool listed in the system message ONCE
each, with empty arguments. Emit only tool calls in this turn — no prose,
no commentary. After the last tool returns you may stop; I will handle
the next step.
""".trimIndent()
    }

    /**
     * Section structure shown in the system message. Each section line lists
     * the section heading and (where applicable) the placeholder token(s) the
     * writer model must emit. The post-processor swaps each placeholder for
     * the canonical bundle table.
     *
     * Headings are localised to [languageCode] because Gemma 4 E2B otherwise
     * copies the English label verbatim ("## Zone Profile" leaked even when
     * the rest of the report was in Spanish).
     *
     * Waypoints table was discarded — the per-image table already carries
     * lat/lon per analyzed image, and the Monitoring Protocol section is now
     * type-specific instructions written as prose, not a coordinate table.
     */
    private fun sectionList(kind: ReportKind, languageCode: String): String {
        val lang = if (SECTION_LABELS.containsKey(languageCode)) languageCode else "en"
        val L = SECTION_LABELS[lang]!!
        return when (kind) {
            ReportKind.GENERIC -> """
  1. ${L.execSummary}                                       → [TABLE: totals]
  2. ${L.methodology}                                       → (no table)
  3. ${L.composition}                                       → [TABLE: material]   [TABLE: type]
  4. ${L.ecoImpact}                                         → [TABLE: ecological]
  5. ${L.riskAssessment}                                    → [TABLE: risk]
  6. ${L.statistical}                                       → [TABLE: per_session]   [TABLE: stats]
  7. ${L.recommendations}                                   → (no table; numbered prose)
""".trimIndent()
            ReportKind.ZONE -> """
  1. ${L.zoneProfile}                                       → [TABLE: totals]
  2. ${L.temporal}                                          → [TABLE: temporal]
  3. ${L.composition}                                       → [TABLE: material]   [TABLE: type]
  4. ${L.perImage}                                          → [TABLE: per_session]
  5. ${L.ecoImpact}                                         → [TABLE: ecological]
  6. ${L.riskAssessment}                                    → [TABLE: risk]
  7. ${L.conclusions}                                       → (no table)
  8. ${L.recommendations}                                   → (no table; numbered prose)
  9. ${L.monitoring}                                        → (no table; ${L.monitoringHint})
""".trimIndent()
        }
    }
}

/** Localized section labels used by [ToolReportGenerator.sectionList]. */
private data class SectionLabels(
    val execSummary: String,
    val methodology: String,
    val composition: String,
    val ecoImpact: String,
    val riskAssessment: String,
    val statistical: String,
    val recommendations: String,
    val zoneProfile: String,
    val temporal: String,
    val perImage: String,
    val conclusions: String,
    val monitoring: String,
    val monitoringHint: String,
)

private val SECTION_LABELS: Map<String, SectionLabels> = mapOf(
    "en" to SectionLabels(
        execSummary = "Executive Summary",
        methodology = "Methodology & Survey Overview",
        composition = "Debris Composition (Material + Type Analysis tables)",
        ecoImpact = "Per-Type Ecological Impact Analysis",
        riskAssessment = "Risk Assessment (ranked risk matrix)",
        statistical = "Statistical Analysis (per-image table)",
        recommendations = "Conservation Recommendations (prioritized)",
        zoneProfile = "Zone Profile",
        temporal = "Temporal Evolution (day-by-day trend)",
        perImage = "Per-analyzed-image Detail",
        conclusions = "Conclusions & Next Steps",
        monitoring = "Monitoring Protocol",
        monitoringHint = "prose only — concrete monitoring actions per debris type detected (frequency, indicators, prevention) — NO coordinate table",
    ),
    "es" to SectionLabels(
        execSummary = "Resumen Ejecutivo",
        methodology = "Metodología y Visión del Muestreo",
        composition = "Composición de Residuos (tablas por Material y por Tipo)",
        ecoImpact = "Impacto Ecológico por Tipo",
        riskAssessment = "Evaluación de Riesgos (matriz priorizada)",
        statistical = "Análisis Estadístico (tabla por imagen)",
        recommendations = "Recomendaciones de Conservación (priorizadas)",
        zoneProfile = "Perfil de la Zona",
        temporal = "Evolución Temporal (tendencia diaria)",
        perImage = "Detalle por imagen analizada",
        conclusions = "Conclusiones y Próximos Pasos",
        monitoring = "Protocolo de Monitoreo",
        monitoringHint = "solo prosa — acciones concretas de monitoreo por cada tipo de residuo detectado (frecuencia, indicadores, prevención) — NO tabla de coordenadas",
    ),
    "fr" to SectionLabels(
        execSummary = "Résumé Exécutif",
        methodology = "Méthodologie et Aperçu de l'Enquête",
        composition = "Composition des Déchets (tableaux Matériau + Type)",
        ecoImpact = "Impact Écologique par Type",
        riskAssessment = "Évaluation des Risques (matrice classée)",
        statistical = "Analyse Statistique (tableau par image)",
        recommendations = "Recommandations de Conservation (priorisées)",
        zoneProfile = "Profil de la Zone",
        temporal = "Évolution Temporelle (tendance quotidienne)",
        perImage = "Détail par image analysée",
        conclusions = "Conclusions et Prochaines Étapes",
        monitoring = "Protocole de Surveillance",
        monitoringHint = "prose uniquement — actions concrètes de surveillance par type de déchet détecté (fréquence, indicateurs, prévention) — PAS de tableau de coordonnées",
    ),
    "de" to SectionLabels(
        execSummary = "Zusammenfassung",
        methodology = "Methodik und Überblick zur Erhebung",
        composition = "Abfallzusammensetzung (Tabellen Material + Typ)",
        ecoImpact = "Ökologische Auswirkungen pro Typ",
        riskAssessment = "Risikobewertung (gewichtete Matrix)",
        statistical = "Statistische Analyse (Tabelle pro Bild)",
        recommendations = "Schutzempfehlungen (priorisiert)",
        zoneProfile = "Zonenprofil",
        temporal = "Zeitliche Entwicklung (täglicher Trend)",
        perImage = "Detail pro analysiertem Bild",
        conclusions = "Schlussfolgerungen und Nächste Schritte",
        monitoring = "Überwachungsprotokoll",
        monitoringHint = "nur Prosa — konkrete Überwachungsmaßnahmen pro erkanntem Abfalltyp (Frequenz, Indikatoren, Prävention) — KEINE Koordinatentabelle",
    ),
    "it" to SectionLabels(
        execSummary = "Sommario Esecutivo",
        methodology = "Metodologia e Panoramica dell'Indagine",
        composition = "Composizione dei Rifiuti (tabelle Materiale + Tipo)",
        ecoImpact = "Impatto Ecologico per Tipo",
        riskAssessment = "Valutazione del Rischio (matrice classificata)",
        statistical = "Analisi Statistica (tabella per immagine)",
        recommendations = "Raccomandazioni di Conservazione (prioritarie)",
        zoneProfile = "Profilo della Zona",
        temporal = "Evoluzione Temporale (tendenza giornaliera)",
        perImage = "Dettaglio per immagine analizzata",
        conclusions = "Conclusioni e Prossimi Passi",
        monitoring = "Protocollo di Monitoraggio",
        monitoringHint = "solo prosa — azioni concrete di monitoraggio per ogni tipo di rifiuto rilevato (frequenza, indicatori, prevenzione) — NESSUNA tabella di coordinate",
    ),
    "pt" to SectionLabels(
        execSummary = "Resumo Executivo",
        methodology = "Metodologia e Visão Geral da Pesquisa",
        composition = "Composição dos Resíduos (tabelas Material + Tipo)",
        ecoImpact = "Impacto Ecológico por Tipo",
        riskAssessment = "Avaliação de Riscos (matriz priorizada)",
        statistical = "Análise Estatística (tabela por imagem)",
        recommendations = "Recomendações de Conservação (priorizadas)",
        zoneProfile = "Perfil da Zona",
        temporal = "Evolução Temporal (tendência diária)",
        perImage = "Detalhe por imagem analisada",
        conclusions = "Conclusões e Próximos Passos",
        monitoring = "Protocolo de Monitoramento",
        monitoringHint = "apenas prosa — ações concretas de monitoramento por tipo de resíduo detectado (frequência, indicadores, prevenção) — SEM tabela de coordenadas",
    ),
)
