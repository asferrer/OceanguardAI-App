package com.oceanguard.ai.inference

import com.oceanguard.ai.data.EnvironmentalImpact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a pre-rendered markdown "data bundle" from [ToolReportContext] that
 * is injected into the agent loop as a user message after PHASE 1 completes.
 *
 * Tables use human-readable localized labels (e.g. "Red de pesca" instead of
 * `FISHING_NET`) so the final report reads naturally. Column headers are also
 * localized to the bundle's target language — the model's job collapses to
 * "copy these rows verbatim".
 */
object ToolDataBundleFormatter {

    /**
     * Canonical row for a data table. Key is the translated label as it appears
     * in the bundle; `canonicalLine` is the complete markdown row that should
     * overwrite whatever the model produced for this label.
     */
    data class CanonicalRow(val label: String, val canonicalLine: String)

    /**
     * Canonical row-by-label maps used by the post-process table repair. Lets
     * [ToolReportGenerator] replace any mangled percentage / count in the
     * model's tables with the ground-truth markdown line this bundle declares.
     */
    data class Canon(
        val materialRows: Map<String, String>,
        val typeRows: Map<String, String>,
        val ecoRows: Map<String, String>,
        val riskRows: Map<String, String>,
        /**
         * One row per analyzed image (per-session table). Key is the first
         * cell of the row exactly as it appears in the bundle (e.g. "#42") so
         * the post-process repair can rewrite any row the model paraphrases.
         */
        val perSessionRows: Map<String, String>,
        val totalsBlock: String,
        /**
         * Pre-rendered full markdown blocks (header + table) keyed by the
         * stable English identifier the writer model is asked to emit as a
         * placeholder. Used by [ToolReportGenerator.substituteTablePlaceholders]
         * to replace every `[TABLE: <key>]` token in the model's prose with
         * the deterministic, source-of-truth table. Keys:
         *   "material", "type", "ecological", "risk",
         *   "per_session", "temporal", "waypoints", "stats"
         * Tables that are not relevant for the current report (e.g. "temporal"
         * for a GENERIC survey) are simply absent from the map; their
         * placeholders are stripped by the substituter.
         */
        val tablesByKey: Map<String, String>,
    )

    fun format(ctx: ToolReportContext): String = buildFormatted(ctx).first

    /** Format the bundle AND return a canonical-row map for post-process repair. */
    fun formatWithCanon(ctx: ToolReportContext): Pair<String, Canon> = buildFormatted(ctx)

    private fun buildFormatted(ctx: ToolReportContext): Pair<String, Canon> {
        val lang = ctx.language
        val h = headings(lang)
        val sb = StringBuilder()
        val tablesByKey = LinkedHashMap<String, String>()

        sb.append("## CONFIRMED DATA — USE THESE EXACT VALUES\n\n")
        sb.append("The tool calls returned the data below. Copy the numbers and labels into the report verbatim. Do NOT emit placeholders (`[Valor]`, `[Tipo_A]`, `| ... |`). Tables below are already in ${languageName(lang)}; reproduce them exactly — same rows, same values, same labels.\n\n")

        val totalsStart = sb.length
        appendTotals(sb, ctx, h, lang)
        tablesByKey["totals"] = extractTableBlock(sb, totalsStart)

        val matStart = sb.length
        val materialRows = appendMaterials(sb, ctx, h, lang)
        tablesByKey["material"] = extractTableBlock(sb, matStart)

        val typeStart = sb.length
        val typeRows = appendTypes(sb, ctx, h, lang)
        tablesByKey["type"] = extractTableBlock(sb, typeStart)

        val ecoStart = sb.length
        val ecoRows = appendEcological(sb, ctx, h, lang)
        tablesByKey["ecological"] = extractTableBlock(sb, ecoStart)

        val riskStart = sb.length
        val riskRows = appendRisk(sb, ctx, h, lang)
        tablesByKey["risk"] = extractTableBlock(sb, riskStart)

        // Waypoints intentionally skipped: the spatial coordinate table was
        // discarded in v0.2.x. The per-image table already carries lat/lon per
        // analyzed image, and the Monitoring Protocol is now type-specific
        // prose. Rendering it here let the model copy the rows AND fed the
        // force-append fallback in substituteTablePlaceholders, which leaked
        // a "| Lat | Lon | ... |" table into the final report (report id=11,
        // 2026-05-17). The tool itself stays callable for the agentic
        // showcase; only the bundle rendering is suppressed.

        val statsStart = sb.length
        appendStats(sb, ctx, h)
        tablesByKey["stats"] = extractTableBlock(sb, statsStart)

        val perSessionStart = sb.length
        val perSessionRows = appendPerSession(sb, ctx, h, lang)
        if (ctx.sessionDetails.isNotEmpty()) {
            tablesByKey["per_session"] = extractTableBlock(sb, perSessionStart)
        }

        val trendStart = sb.length
        appendTrend(sb, ctx, h)
        if (ctx.temporalTrend != null) {
            tablesByKey["temporal"] = extractTableBlock(sb, trendStart)
        }

        sb.append("---\n\n")
        sb.append(h.writeInstruction)
        val totalsBlock = buildString {
            append("- ${h.totalDebris}: **${ctx.totalDebrisItems}**\n")
            append("- ${h.sessionsAnalysed}: **${ctx.sessionCount}**\n")
            append("- ${h.avgHealth}: **${ctx.avgHealthScore}** / 100\n")
        }
        return sb.toString() to Canon(
            materialRows = materialRows,
            typeRows = typeRows,
            ecoRows = ecoRows,
            riskRows = riskRows,
            perSessionRows = perSessionRows,
            totalsBlock = totalsBlock,
            tablesByKey = tablesByKey,
        )
    }

    /**
     * Extracts the table-or-bullet block from [sb] starting at [startIdx],
     * skipping the leading "### Heading" line. The result is what
     * [ToolReportGenerator.substituteTablePlaceholders] inserts when the
     * writer model emits a `[TABLE: <key>]` placeholder.
     *
     * For tables: returns everything from the first `|` to the end of the
     * section (trimmed).
     * For bullet sections (totals, stats): returns everything from the first
     * `-` to the end.
     */
    private fun extractTableBlock(sb: StringBuilder, startIdx: Int): String {
        val section = sb.substring(startIdx).trimEnd()
        if (section.isEmpty()) return ""
        val lines = section.lines()
        // Drop leading lines until we hit a `|` (table) or `-` (bullet list).
        val bodyLines = lines.dropWhile {
            val t = it.trimStart()
            !t.startsWith("|") && !t.startsWith("-")
        }
        return bodyLines.joinToString("\n").trimEnd()
    }

    /**
     * Per-session "analyzed images" table. The first column is the session id
     * prefixed with "#" so the repair step can match each row regardless of
     * what the model emits between the bars.
     */
    private fun appendPerSession(
        sb: StringBuilder,
        ctx: ToolReportContext,
        h: H,
        lang: String,
    ): Map<String, String> {
        if (ctx.sessionDetails.isEmpty()) return emptyMap()
        val rows = LinkedHashMap<String, String>()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        sb.append("### ${h.perSession}\n")
        sb.append("| ${h.imageId} | ${h.date} | ${h.lat} | ${h.lon} | ${h.debris} | ${h.health} | ${h.dominant} |\n")
        sb.append("|---|---|---:|---:|---:|---:|---|\n")
        for (d in ctx.sessionDetails) {
            val idCell = "#${d.sessionId}"
            val dateCell = fmt.format(Date(d.timestampMs))
            val latCell = d.lat?.let { "%.5f".format(Locale.US, it) } ?: "—"
            val lonCell = d.lon?.let { "%.5f".format(Locale.US, it) } ?: "—"
            val dominantCell = ReportGenerator.translateType(d.dominantType, lang)
            val row = "| $idCell | $dateCell | $latCell | $lonCell | ${d.totalDebris} | ${d.healthScore} | $dominantCell |"
            sb.append(row).append('\n')
            rows[idCell] = row
        }
        sb.append('\n')
        return rows
    }

    private fun appendTotals(sb: StringBuilder, ctx: ToolReportContext, h: H, lang: String) {
        val dominantMat = ctx.materialCounts.entries.firstOrNull()?.key?.name
            ?.let { ReportGenerator.translateMaterial(it, lang) } ?: "—"
        val dominantType = ctx.typeCounts.entries.firstOrNull()?.key?.name
            ?.let { ReportGenerator.translateType(it, lang) } ?: "—"
        val risk = ctx.riskBreakdown
        sb.append("### ${h.surveyTotals}\n")
        sb.append("- ${h.totalDebris}: **${ctx.totalDebrisItems}**\n")
        sb.append("- ${h.sessionsAnalysed}: **${ctx.sessionCount}**\n")
        sb.append("- ${h.avgHealth}: **${ctx.avgHealthScore}** / 100\n")
        sb.append("- ${h.dominantMaterial}: **$dominantMat**\n")
        sb.append("- ${h.dominantType}: **$dominantType**\n")
        sb.append("- ${h.riskBreakdown}: ${h.high}=${risk["high"] ?: 0}, ${h.medium}=${risk["medium"] ?: 0}, ${h.low}=${risk["low"] ?: 0}\n\n")
    }

    private fun appendMaterials(sb: StringBuilder, ctx: ToolReportContext, h: H, lang: String): Map<String, String> {
        val rows = LinkedHashMap<String, String>()
        val total = ctx.materialCounts.values.sum().coerceAtLeast(1).toDouble()
        sb.append("### ${h.materialComp}\n")
        sb.append("| ${h.material} | ${h.count} | ${h.percent} |\n|---|---:|---:|\n")
        ctx.materialCounts.forEach { (mat, count) ->
            val pct = Math.round(count * 100.0 / total).toInt()
            val label = ReportGenerator.translateMaterial(mat.name, lang)
            val row = "| $label | $count | ${pct}% |"
            sb.append(row).append('\n')
            rows[label] = row
        }
        sb.append('\n')
        return rows
    }

    private fun appendTypes(sb: StringBuilder, ctx: ToolReportContext, h: H, lang: String): Map<String, String> {
        val rows = LinkedHashMap<String, String>()
        val total = ctx.typeCounts.values.sum().coerceAtLeast(1).toDouble()
        sb.append("### ${h.typeComp}\n")
        sb.append("| ${h.type} | ${h.count} | ${h.percent} |\n|---|---:|---:|\n")
        ctx.typeCounts.forEach { (type, count) ->
            val pct = Math.round(count * 100.0 / total).toInt()
            val label = ReportGenerator.translateType(type.name, lang)
            val row = "| $label | $count | ${pct}% |"
            sb.append(row).append('\n')
            rows[label] = row
        }
        sb.append('\n')
        return rows
    }

    private fun appendEcological(sb: StringBuilder, ctx: ToolReportContext, h: H, lang: String): Map<String, String> {
        val rows = LinkedHashMap<String, String>()
        sb.append("### ${h.ecologicalImpact}\n")
        sb.append("| ${h.type} | ${h.degradationTime} | ${h.primaryRisk} | ${h.riskScore} | ${h.annualVolume} |\n|---|---|---|---:|---|\n")
        ctx.typeCounts.keys.forEach { type ->
            val impact = EnvironmentalImpact.getImpact(type)
            val vol = impact.annualVolumeOcean ?: h.notQuantified
            val label = ReportGenerator.translateType(type.name, lang)
            val row = "| $label | ${translateDegradation(impact.degradationTime, lang)} | ${translateRisk(impact.primaryRisk, lang)} | ${impact.riskScore} | ${translateVolume(vol, lang)} |"
            sb.append(row).append('\n')
            rows[label] = row
        }
        sb.append('\n')
        return rows
    }

    private fun appendRisk(sb: StringBuilder, ctx: ToolReportContext, h: H, lang: String): Map<String, String> {
        val rows = LinkedHashMap<String, String>()
        sb.append("### ${h.riskRanking}\n")
        sb.append("| ${h.type} | ${h.count} | ${h.riskScore} | ${h.urgency} |\n|---|---:|---:|---|\n")
        ctx.typeCounts.entries
            .map { (type, count) -> Triple(type.name, count, EnvironmentalImpact.getImpact(type).riskScore) }
            .sortedByDescending { it.third }
            .forEach { (name, count, score) ->
                val label = ReportGenerator.translateType(name, lang)
                val row = "| $label | $count | $score | ${urgencyLabel(score, lang)} |"
                sb.append(row).append('\n')
                rows[label] = row
            }
        sb.append('\n')
        return rows
    }

    private fun appendWaypoints(sb: StringBuilder, ctx: ToolReportContext, h: H, lang: String) {
        if (ctx.waypoints.isEmpty()) return
        sb.append("### ${h.waypoints}\n")
        sb.append("| ${h.lat} | ${h.lon} | ${h.debris} | ${h.health} | ${h.dominant} | ${h.priority} |\n|---:|---:|---:|---:|---|---|\n")
        ctx.waypoints.take(10).forEach { w ->
            val lat = "%.5f".format(Locale.US, w.lat)
            val lon = "%.5f".format(Locale.US, w.lon)
            sb.append("| $lat | $lon | ${w.debrisCount} | ${w.healthScore} | ${ReportGenerator.translateType(w.dominantType, lang)} | ${priorityLabel(w.priority, lang)} |\n")
        }
        sb.append('\n')
    }

    private fun appendStats(sb: StringBuilder, ctx: ToolReportContext, h: H) {
        val healthValues = ctx.sessions.map { it.healthScore }
        val countValues = ctx.sessions.map { it.totalCount }
        sb.append("### ${h.sessionStats}\n")
        sb.append("- ${h.healthStatLine}: min=${healthValues.minOrNull() ?: 0}, max=${healthValues.maxOrNull() ?: 0}, avg=${ctx.avgHealthScore}, n=${healthValues.size}\n")
        sb.append("- ${h.debrisStatLine}: min=${countValues.minOrNull() ?: 0}, max=${countValues.maxOrNull() ?: 0}, avg=${"%.1f".format(Locale.US, countValues.average().takeIf { !it.isNaN() } ?: 0.0)}\n\n")
    }

    private fun appendTrend(sb: StringBuilder, ctx: ToolReportContext, h: H) {
        val trend = ctx.temporalTrend ?: return
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sb.append("### ${h.temporalTrend}\n")
        sb.append("- ${h.trendLabel}: **${trend.trend.name}**\n")
        sb.append("- ${h.healthDelta}: **${trend.healthScoreDelta}**\n")
        sb.append("| ${h.date} | ${h.sessionsCol} | ${h.debris} | ${h.avgHealth} |\n|---|---:|---:|---:|\n")
        trend.surveyDays.forEach { d ->
            sb.append("| ${fmt.format(Date(d.dateMs))} | ${d.sessionCount} | ${d.totalDebris} | ${d.avgHealthScore} |\n")
        }
        sb.append('\n')
    }

    /**
     * Translate English risk descriptions from [EnvironmentalImpact] to the
     * bundle's target language. Gemma 4 E2B mixes languages when left to
     * translate these itself, so we pre-translate the common phrases.
     */
    private fun translateRisk(risk: String, lang: String): String {
        if (lang == "en") return risk
        val map = RISK_TRANSLATIONS[lang] ?: return risk
        return map[risk] ?: risk
    }

    private fun translateDegradation(deg: String, lang: String): String {
        if (lang == "en") return deg
        val map = DEGRADATION_TRANSLATIONS[lang] ?: return deg
        return map[deg] ?: deg
    }

    private fun translateVolume(vol: String, lang: String): String {
        if (lang == "en" || vol.startsWith("~") || vol == "Not quantified") {
            return when {
                vol == "Not quantified" -> when (lang) {
                    "es" -> "No cuantificado"; "fr" -> "Non quantifié"; "de" -> "Nicht quantifiziert"
                    "it" -> "Non quantificato"; "pt" -> "Não quantificado"; else -> vol
                }
                else -> vol.replace("tonnes/year", tonnesYearWord(lang))
            }
        }
        return vol
    }

    private fun tonnesYearWord(lang: String): String = when (lang) {
        "es", "pt" -> "toneladas/año"
        "fr" -> "tonnes/an"; "de" -> "Tonnen/Jahr"; "it" -> "tonnellate/anno"
        else -> "tonnes/year"
    }

    // Translations for canonical English impact strings from
    // [EnvironmentalImpact]. Must cover EVERY primaryRisk string defined there
    // — unmapped strings fall through to English in the report, producing the
    // mixed-language Eco table seen in id=16 ("Ingestion risk, microplastic
    // release" + "Physical injury to marine life" leaked in EN inside an ES
    // report).
    private val RISK_TRANSLATIONS: Map<String, Map<String, String>> = mapOf(
        "es" to mapOf(
            "Ingestion risk, microplastic fragmentation" to "Riesgo de ingestión, fragmentación en microplásticos",
            "Sharp edges, toxic chemical leaching" to "Bordes afilados, lixiviación de sustancias tóxicas",
            "Ghost fishing, entanglement of marine life" to "Pesca fantasma, enredo de fauna marina",
            "Ingestion risk, microplastic release" to "Riesgo de ingestión, liberación de microplásticos",
            "Entanglement of small marine organisms" to "Enredo de pequeños organismos marinos",
            "Sharp edges, chemical leaching, habitat disruption" to "Bordes afilados, lixiviación química, alteración del hábitat",
            "Microplastic fragmentation, ingestion by marine fauna" to "Fragmentación en microplásticos, ingestión por fauna marina",
            "Toxic chemicals (zinc, cadmium, heavy metals)" to "Sustancias tóxicas (zinc, cadmio, metales pesados)",
            "Microfiber release, entanglement" to "Liberación de microfibras, enredo",
            "Physical injury to marine life" to "Lesiones físicas a la fauna marina",
            "Unknown environmental impact" to "Impacto ambiental no determinado",
        ),
        "fr" to mapOf(
            "Ingestion risk, microplastic fragmentation" to "Risque d'ingestion, fragmentation en microplastiques",
            "Sharp edges, toxic chemical leaching" to "Bords tranchants, lixiviation de substances toxiques",
            "Ghost fishing, entanglement of marine life" to "Pêche fantôme, enchevêtrement de la faune marine",
            "Ingestion risk, microplastic release" to "Risque d'ingestion, libération de microplastiques",
            "Entanglement of small marine organisms" to "Enchevêtrement de petits organismes marins",
            "Sharp edges, chemical leaching, habitat disruption" to "Bords tranchants, lixiviation chimique, perturbation de l'habitat",
            "Microplastic fragmentation, ingestion by marine fauna" to "Fragmentation en microplastiques, ingestion par la faune marine",
            "Toxic chemicals (zinc, cadmium, heavy metals)" to "Substances toxiques (zinc, cadmium, métaux lourds)",
            "Microfiber release, entanglement" to "Libération de microfibres, enchevêtrement",
            "Physical injury to marine life" to "Blessures physiques à la faune marine",
            "Unknown environmental impact" to "Impact environnemental indéterminé",
        ),
        "de" to mapOf(
            "Ingestion risk, microplastic fragmentation" to "Verschluckungsrisiko, Mikroplastik-Fragmentierung",
            "Sharp edges, toxic chemical leaching" to "Scharfe Kanten, Auslaugung giftiger Chemikalien",
            "Ghost fishing, entanglement of marine life" to "Geisterfischerei, Verfangung von Meereslebewesen",
            "Ingestion risk, microplastic release" to "Verschluckungsrisiko, Freisetzung von Mikroplastik",
            "Entanglement of small marine organisms" to "Verfangung kleiner Meeresorganismen",
            "Sharp edges, chemical leaching, habitat disruption" to "Scharfe Kanten, chemische Auslaugung, Lebensraumstörung",
            "Microplastic fragmentation, ingestion by marine fauna" to "Mikroplastik-Fragmentierung, Verschlucken durch Meeresfauna",
            "Toxic chemicals (zinc, cadmium, heavy metals)" to "Giftige Chemikalien (Zink, Cadmium, Schwermetalle)",
            "Microfiber release, entanglement" to "Mikrofaser-Freisetzung, Verfangung",
            "Physical injury to marine life" to "Physische Verletzungen für Meereslebewesen",
            "Unknown environmental impact" to "Unbekannte Umweltauswirkung",
        ),
        "it" to mapOf(
            "Ingestion risk, microplastic fragmentation" to "Rischio di ingestione, frammentazione in microplastiche",
            "Sharp edges, toxic chemical leaching" to "Bordi taglienti, rilascio di sostanze tossiche",
            "Ghost fishing, entanglement of marine life" to "Pesca fantasma, intrappolamento della fauna marina",
            "Ingestion risk, microplastic release" to "Rischio di ingestione, rilascio di microplastiche",
            "Entanglement of small marine organisms" to "Intrappolamento di piccoli organismi marini",
            "Sharp edges, chemical leaching, habitat disruption" to "Bordi taglienti, rilascio chimico, alterazione dell'habitat",
            "Microplastic fragmentation, ingestion by marine fauna" to "Frammentazione in microplastiche, ingestione da fauna marina",
            "Toxic chemicals (zinc, cadmium, heavy metals)" to "Sostanze tossiche (zinco, cadmio, metalli pesanti)",
            "Microfiber release, entanglement" to "Rilascio di microfibre, intrappolamento",
            "Physical injury to marine life" to "Lesioni fisiche alla fauna marina",
            "Unknown environmental impact" to "Impatto ambientale indeterminato",
        ),
        "pt" to mapOf(
            "Ingestion risk, microplastic fragmentation" to "Risco de ingestão, fragmentação em microplásticos",
            "Sharp edges, toxic chemical leaching" to "Bordas afiadas, lixiviação de substâncias tóxicas",
            "Ghost fishing, entanglement of marine life" to "Pesca fantasma, emaranhamento da fauna marinha",
            "Ingestion risk, microplastic release" to "Risco de ingestão, libertação de microplásticos",
            "Entanglement of small marine organisms" to "Emaranhamento de pequenos organismos marinhos",
            "Sharp edges, chemical leaching, habitat disruption" to "Bordas afiadas, lixiviação química, perturbação do habitat",
            "Microplastic fragmentation, ingestion by marine fauna" to "Fragmentação em microplásticos, ingestão pela fauna marinha",
            "Toxic chemicals (zinc, cadmium, heavy metals)" to "Substâncias tóxicas (zinco, cádmio, metais pesados)",
            "Microfiber release, entanglement" to "Libertação de microfibras, emaranhamento",
            "Physical injury to marine life" to "Lesões físicas à fauna marinha",
            "Unknown environmental impact" to "Impacto ambiental indeterminado",
        ),
    )

    // Covers every degradationTime string in [EnvironmentalImpact]. Must
    // include the unusual entries ("1,000,000+ years" for glass, "200-500
    // years" for metal, "1-200+ years" for fabric) that were missing in the
    // previous map and leaked English into the ES report.
    private val DEGRADATION_TRANSLATIONS: Map<String, Map<String, String>> = mapOf(
        "es" to mapOf(
            "450+ years" to "Más de 450 años",
            "600+ years" to "Más de 600 años",
            "200+ years" to "Más de 200 años",
            "100+ years" to "Más de 100 años",
            "50+ years" to "Más de 50 años",
            "2000+ years" to "Más de 2000 años",
            "200-500 years" to "200-500 años",
            "1-200+ years" to "1-200+ años",
            "1,000,000+ years" to "Más de 1.000.000 de años",
            "Variable" to "Variable",
        ),
        "fr" to mapOf(
            "450+ years" to "Plus de 450 ans",
            "600+ years" to "Plus de 600 ans",
            "200+ years" to "Plus de 200 ans",
            "100+ years" to "Plus de 100 ans",
            "50+ years" to "Plus de 50 ans",
            "2000+ years" to "Plus de 2000 ans",
            "200-500 years" to "200-500 ans",
            "1-200+ years" to "1-200+ ans",
            "1,000,000+ years" to "Plus de 1 000 000 d'ans",
            "Variable" to "Variable",
        ),
        "de" to mapOf(
            "450+ years" to "Über 450 Jahre",
            "600+ years" to "Über 600 Jahre",
            "200+ years" to "Über 200 Jahre",
            "100+ years" to "Über 100 Jahre",
            "50+ years" to "Über 50 Jahre",
            "2000+ years" to "Über 2000 Jahre",
            "200-500 years" to "200-500 Jahre",
            "1-200+ years" to "1-200+ Jahre",
            "1,000,000+ years" to "Über 1.000.000 Jahre",
            "Variable" to "Variabel",
        ),
        "it" to mapOf(
            "450+ years" to "Oltre 450 anni",
            "600+ years" to "Oltre 600 anni",
            "200+ years" to "Oltre 200 anni",
            "100+ years" to "Oltre 100 anni",
            "50+ years" to "Oltre 50 anni",
            "2000+ years" to "Oltre 2000 anni",
            "200-500 years" to "200-500 anni",
            "1-200+ years" to "1-200+ anni",
            "1,000,000+ years" to "Oltre 1.000.000 di anni",
            "Variable" to "Variabile",
        ),
        "pt" to mapOf(
            "450+ years" to "Mais de 450 anos",
            "600+ years" to "Mais de 600 anos",
            "200+ years" to "Mais de 200 anos",
            "100+ years" to "Mais de 100 anos",
            "50+ years" to "Mais de 50 anos",
            "2000+ years" to "Mais de 2000 anos",
            "200-500 years" to "200-500 anos",
            "1-200+ years" to "1-200+ anos",
            "1,000,000+ years" to "Mais de 1.000.000 de anos",
            "Variable" to "Variável",
        ),
    )

    private fun languageName(lang: String): String = when (lang) {
        "es" -> "Spanish"; "fr" -> "French"; "de" -> "German"
        "it" -> "Italian"; "pt" -> "Portuguese"; else -> "English"
    }

    private fun urgencyLabel(score: Int, lang: String): String = when (lang) {
        "es" -> when { score >= 8 -> "Crítica"; score >= 6 -> "Alta"; score >= 4 -> "Media"; else -> "Baja" }
        "fr" -> when { score >= 8 -> "Critique"; score >= 6 -> "Élevée"; score >= 4 -> "Modérée"; else -> "Faible" }
        "de" -> when { score >= 8 -> "Kritisch"; score >= 6 -> "Hoch"; score >= 4 -> "Mittel"; else -> "Niedrig" }
        "it" -> when { score >= 8 -> "Critica"; score >= 6 -> "Alta"; score >= 4 -> "Media"; else -> "Bassa" }
        "pt" -> when { score >= 8 -> "Crítica"; score >= 6 -> "Alta"; score >= 4 -> "Média"; else -> "Baixa" }
        else -> when { score >= 8 -> "Critical"; score >= 6 -> "High"; score >= 4 -> "Medium"; else -> "Low" }
    }

    private fun priorityLabel(priority: String, lang: String): String = when (lang) {
        "es" -> when (priority) { "HIGH" -> "Alta"; "MEDIUM" -> "Media"; else -> "Baja" }
        "fr" -> when (priority) { "HIGH" -> "Élevée"; "MEDIUM" -> "Modérée"; else -> "Faible" }
        "de" -> when (priority) { "HIGH" -> "Hoch"; "MEDIUM" -> "Mittel"; else -> "Niedrig" }
        "it" -> when (priority) { "HIGH" -> "Alta"; "MEDIUM" -> "Media"; else -> "Bassa" }
        "pt" -> when (priority) { "HIGH" -> "Alta"; "MEDIUM" -> "Média"; else -> "Baixa" }
        else -> when (priority) { "HIGH" -> "High"; "MEDIUM" -> "Medium"; else -> "Low" }
    }

    private data class H(
        val surveyTotals: String, val totalDebris: String, val sessionsAnalysed: String,
        val avgHealth: String, val dominantMaterial: String, val dominantType: String,
        val riskBreakdown: String, val high: String, val medium: String, val low: String,
        val materialComp: String, val typeComp: String, val material: String, val type: String,
        val count: String, val percent: String, val ecologicalImpact: String,
        val degradationTime: String, val primaryRisk: String, val riskScore: String,
        val annualVolume: String, val notQuantified: String, val riskRanking: String,
        val urgency: String, val waypoints: String, val lat: String, val lon: String,
        val debris: String, val health: String, val dominant: String, val priority: String,
        val sessionStats: String, val healthStatLine: String, val debrisStatLine: String,
        val temporalTrend: String, val trendLabel: String, val healthDelta: String,
        val date: String, val sessionsCol: String, val writeInstruction: String,
        // Per-session ("analyzed images") table headers.
        val perSession: String, val imageId: String,
    )

    private fun headings(lang: String): H = when (lang) {
        "es" -> H(
            "Totales de la encuesta", "Elementos totales de residuos", "Sesiones analizadas",
            "Puntuación media de salud", "Material dominante", "Tipo dominante",
            "Distribución de riesgo", "Alto", "Medio", "Bajo",
            "Composición por material", "Composición por tipo", "Material", "Tipo",
            "Conteo", "Porcentaje", "Impacto ecológico por tipo",
            "Tiempo de degradación", "Riesgo principal", "Puntuación de riesgo",
            "Volumen oceánico anual", "No cuantificado", "Clasificación de riesgo",
            "Urgencia", "Puntos de recolección (top 10, orden de prioridad)", "Lat", "Lon",
            "Residuos", "Salud", "Tipo dominante", "Prioridad",
            "Estadísticas por sesión", "Puntuación de salud", "Residuos por sesión",
            "Evolución temporal", "Etiqueta de tendencia", "Delta de salud (último − primero)",
            "Fecha", "Sesiones",
            "Redacta el reporte final ahora usando los valores exactos anteriores. Escribe prosa fluida (3–5 frases por sección antes de las tablas), conectando los datos en narrativa científica. Reproduce las tablas EXACTAMENTE como aparecen arriba — mismas filas, mismos valores, mismas etiquetas en español. No placeholders, no filas con `| ... |`, no inventes porcentajes. Empieza con el primer encabezado de la estructura solicitada.\n",
            "Detalle por imagen analizada", "ID de imagen",
        )
        "fr" -> H(
            "Totaux de l'étude", "Total des débris", "Sessions analysées",
            "Score moyen de santé", "Matériau dominant", "Type dominant",
            "Répartition des risques", "Élevé", "Moyen", "Faible",
            "Composition par matériau", "Composition par type", "Matériau", "Type",
            "Quantité", "Pourcentage", "Impact écologique par type",
            "Temps de dégradation", "Risque principal", "Score de risque",
            "Volume océanique annuel", "Non quantifié", "Classement des risques",
            "Urgence", "Points de collecte (top 10, ordre de priorité)", "Lat", "Lon",
            "Débris", "Santé", "Type dominant", "Priorité",
            "Statistiques par session", "Score de santé", "Débris par session",
            "Évolution temporelle", "Étiquette de tendance", "Delta santé (dernier − premier)",
            "Date", "Sessions",
            "Rédigez le rapport final maintenant avec les valeurs exactes ci-dessus. Prose fluide (3–5 phrases par section avant les tableaux). Reproduisez les tableaux EXACTEMENT. Pas de placeholders. Commencez par le premier en-tête de la structure demandée.\n",
            "Détail par image analysée", "ID d'image",
        )
        "de" -> H(
            "Umfrage-Summen", "Müll gesamt", "Ausgewertete Sitzungen",
            "Durchschnittlicher Gesundheitswert", "Dominierendes Material", "Dominierender Typ",
            "Risikoverteilung", "Hoch", "Mittel", "Niedrig",
            "Materialzusammensetzung", "Typenzusammensetzung", "Material", "Typ",
            "Anzahl", "Prozent", "Ökologischer Einfluss nach Typ",
            "Abbauzeit", "Hauptrisiko", "Risikobewertung",
            "Jährliches Ozeanvolumen", "Nicht quantifiziert", "Risiko-Rangliste",
            "Dringlichkeit", "Sammelpunkte (Top 10, Prioritätsreihenfolge)", "Lat", "Lon",
            "Müll", "Gesundheit", "Dominierender Typ", "Priorität",
            "Sitzungsstatistik", "Gesundheitswert", "Müll pro Sitzung",
            "Zeitlicher Verlauf", "Trend-Label", "Gesundheits-Delta (letzter − erster)",
            "Datum", "Sitzungen",
            "Schreibe jetzt den endgültigen Bericht mit den exakten Werten oben. Flüssige Prosa (3–5 Sätze pro Abschnitt vor den Tabellen). Tabellen EXAKT reproduzieren. Keine Platzhalter. Beginne mit der ersten Überschrift der angeforderten Struktur.\n",
            "Detail pro analysiertem Bild", "Bild-ID",
        )
        "it" -> H(
            "Totali del rilievo", "Rifiuti totali", "Sessioni analizzate",
            "Punteggio medio di salute", "Materiale dominante", "Tipo dominante",
            "Distribuzione del rischio", "Alto", "Medio", "Basso",
            "Composizione per materiale", "Composizione per tipo", "Materiale", "Tipo",
            "Conteggio", "Percentuale", "Impatto ecologico per tipo",
            "Tempo di degradazione", "Rischio principale", "Punteggio di rischio",
            "Volume oceanico annuale", "Non quantificato", "Classifica di rischio",
            "Urgenza", "Punti di raccolta (top 10, ordine di priorità)", "Lat", "Lon",
            "Rifiuti", "Salute", "Tipo dominante", "Priorità",
            "Statistiche per sessione", "Punteggio di salute", "Rifiuti per sessione",
            "Evoluzione temporale", "Etichetta di tendenza", "Delta salute (ultimo − primo)",
            "Data", "Sessioni",
            "Scrivi il report finale ora con i valori esatti sopra. Prosa fluida (3–5 frasi per sezione prima delle tabelle). Riproduci le tabelle ESATTAMENTE. Niente placeholder. Inizia con la prima intestazione della struttura richiesta.\n",
            "Dettaglio per immagine analizzata", "ID immagine",
        )
        "pt" -> H(
            "Totais do levantamento", "Resíduos totais", "Sessões analisadas",
            "Pontuação média de saúde", "Material dominante", "Tipo dominante",
            "Distribuição de risco", "Alto", "Médio", "Baixo",
            "Composição por material", "Composição por tipo", "Material", "Tipo",
            "Contagem", "Percentagem", "Impacto ecológico por tipo",
            "Tempo de degradação", "Risco principal", "Pontuação de risco",
            "Volume oceânico anual", "Não quantificado", "Classificação de risco",
            "Urgência", "Pontos de recolha (top 10, ordem de prioridade)", "Lat", "Lon",
            "Resíduos", "Saúde", "Tipo dominante", "Prioridade",
            "Estatísticas por sessão", "Pontuação de saúde", "Resíduos por sessão",
            "Evolução temporal", "Etiqueta de tendência", "Delta de saúde (último − primeiro)",
            "Data", "Sessões",
            "Escreva o relatório final agora com os valores exatos acima. Prosa fluida (3–5 frases por secção antes das tabelas). Reproduza as tabelas EXATAMENTE. Sem placeholders. Comece pelo primeiro título da estrutura solicitada.\n",
            "Detalhe por imagem analisada", "ID de imagem",
        )
        else -> H(
            "Survey totals", "Total debris items", "Sessions analysed",
            "Average health score", "Dominant material", "Dominant type",
            "Risk breakdown", "High", "Medium", "Low",
            "Material composition", "Type composition", "Material", "Type",
            "Count", "Percent", "Ecological impact per type",
            "Degradation time", "Primary risk", "Risk score",
            "Annual ocean volume", "Not quantified", "Risk ranking",
            "Urgency", "Collection waypoints (top 10, priority order)", "Lat", "Lon",
            "Debris", "Health", "Dominant type", "Priority",
            "Session statistics", "Health score", "Debris per session",
            "Temporal trend", "Trend label", "Health delta (last − first)",
            "Date", "Sessions",
            "Write the final report now using the exact values above. Flowing prose (3–5 sentences per section before tables). Reproduce tables EXACTLY. No placeholders. Start with the first heading of the requested structure.\n",
            "Per-analyzed-image detail", "Image ID",
        )
    }
}
