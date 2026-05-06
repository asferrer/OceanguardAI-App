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
        val totalsBlock: String,
    )

    fun format(ctx: ToolReportContext): String = buildFormatted(ctx).first

    /** Format the bundle AND return a canonical-row map for post-process repair. */
    fun formatWithCanon(ctx: ToolReportContext): Pair<String, Canon> = buildFormatted(ctx)

    private fun buildFormatted(ctx: ToolReportContext): Pair<String, Canon> {
        val lang = ctx.language
        val h = headings(lang)
        val sb = StringBuilder()
        sb.append("## CONFIRMED DATA — USE THESE EXACT VALUES\n\n")
        sb.append("The tool calls returned the data below. Copy the numbers and labels into the report verbatim. Do NOT emit placeholders (`[Valor]`, `[Tipo_A]`, `| ... |`). Tables below are already in ${languageName(lang)}; reproduce them exactly — same rows, same values, same labels.\n\n")
        appendTotals(sb, ctx, h, lang)
        val materialRows = appendMaterials(sb, ctx, h, lang)
        val typeRows = appendTypes(sb, ctx, h, lang)
        val ecoRows = appendEcological(sb, ctx, h, lang)
        val riskRows = appendRisk(sb, ctx, h, lang)
        appendWaypoints(sb, ctx, h, lang)
        appendStats(sb, ctx, h)
        appendTrend(sb, ctx, h)
        sb.append("---\n\n")
        sb.append(h.writeInstruction)
        val totalsBlock = buildString {
            append("- ${h.totalDebris}: **${ctx.totalDebrisItems}**\n")
            append("- ${h.sessionsAnalysed}: **${ctx.sessionCount}**\n")
            append("- ${h.avgHealth}: **${ctx.avgHealthScore}** / 100\n")
        }
        return sb.toString() to Canon(materialRows, typeRows, ecoRows, riskRows, totalsBlock)
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

    // Translations for canonical English impact strings from EnvironmentalImpact.
    // Unmapped strings fall through unchanged.
    private val RISK_TRANSLATIONS: Map<String, Map<String, String>> = mapOf(
        "es" to mapOf(
            "Microplastic fragmentation, ingestion by marine fauna" to "Fragmentación en microplásticos, ingestión por fauna marina",
            "Ghost fishing, entanglement of marine life" to "Pesca fantasma, enredo de fauna marina",
            "Ingestion risk, microplastic fragmentation" to "Riesgo de ingestión, fragmentación en microplásticos",
            "Sharp edges, toxic chemical leaching" to "Bordes afilados, lixiviación de sustancias tóxicas",
            "Unknown environmental impact" to "Impacto ambiental no determinado",
        ),
        "fr" to mapOf(
            "Microplastic fragmentation, ingestion by marine fauna" to "Fragmentation en microplastiques, ingestion par la faune marine",
            "Ghost fishing, entanglement of marine life" to "Pêche fantôme, enchevêtrement de la faune marine",
            "Ingestion risk, microplastic fragmentation" to "Risque d'ingestion, fragmentation en microplastiques",
            "Sharp edges, toxic chemical leaching" to "Bords tranchants, lixiviation de substances toxiques",
            "Unknown environmental impact" to "Impact environnemental indéterminé",
        ),
    )

    private val DEGRADATION_TRANSLATIONS: Map<String, Map<String, String>> = mapOf(
        "es" to mapOf(
            "600+ years" to "Más de 600 años",
            "450+ years" to "Más de 450 años",
            "200+ years" to "Más de 200 años",
            "50+ years" to "Más de 50 años",
            "100+ years" to "Más de 100 años",
            "Variable" to "Variable",
        ),
        "fr" to mapOf(
            "600+ years" to "Plus de 600 ans",
            "450+ years" to "Plus de 450 ans",
            "200+ years" to "Plus de 200 ans",
            "50+ years" to "Plus de 50 ans",
            "100+ years" to "Plus de 100 ans",
            "Variable" to "Variable",
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
        )
    }
}
