package com.oceanguard.ai.inference

import android.util.Log
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Post-generation validator that checks VLM report text against the
 * source [DetectionSession] data that was used to generate it.
 *
 * Catches common hallucination categories:
 * - Fabricated debris classes or materials not present in the data
 * - Percentages that don't match actual material/type counts
 * - Dates outside the actual survey date range
 * - GPS coordinates not found in source sessions
 * - Health score mischaracterization (e.g. calling score >75 "contaminated")
 */
object ReportValidator {

    private const val TAG = "ReportValidator"

    /**
     * All lowercase name variants for a material (raw + translated). For
     * non-English output languages the raw English alias is intentionally
     * omitted: words like "chemical" or "paper" appear inside localized
     * prose (e.g. "toxic chemical leaching" inside Spanish risk descriptions)
     * and would otherwise trigger false-positive fabrication alerts.
     */
    private fun materialNames(key: String, language: String): Set<String> = buildSet {
        if (language == "en") {
            add(key.lowercase())
            add(key.lowercase().replace("_", " "))
        }
        add(ReportGenerator.translateMaterial(key, language).lowercase())
        add(ReportGenerator.translateMaterial(key, "en").lowercase())
    }

    /** All lowercase name variants for a type (raw + translated). */
    private fun typeNames(key: String, language: String): Set<String> = buildSet {
        add(key.lowercase())
        add(key.lowercase().replace("_", " "))
        add(ReportGenerator.translateType(key, language).lowercase())
        add(ReportGenerator.translateType(key, "en").lowercase())
    }

    /** Checks if any name variant appears in the lowercased text. */
    private fun textContainsAny(textLower: String, names: Set<String>): Boolean =
        names.any { textLower.contains(it) }

    /**
     * Checks if the line matches a specific entity using word-boundary-aware matching.
     * Returns the BEST (longest) matching key to avoid "plástico" matching inside "residuos plásticos".
     */
    private fun findBestEntityMatch(
        lineLower: String,
        entityAliases: Map<String, Set<String>>,
    ): String? {
        var bestKey: String? = null
        var bestLen = 0
        for ((key, aliases) in entityAliases) {
            for (alias in aliases) {
                if (alias.length > bestLen && lineLower.contains(alias)) {
                    bestKey = key
                    bestLen = alias.length
                }
            }
        }
        return bestKey
    }

    // Regex patterns for extracting claims from VLM text
    private val PERCENTAGE_PATTERN = Regex("""(\d{1,3}(?:\.\d{1,2})?)[\s]*%""")
    private val GPS_PATTERN = Regex("""-?\d{1,3}\.\d{3,6}""")
    private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
    private val HEALTH_SCORE_PATTERN = Regex("""health\s*score[:\s]*(\d{1,3})""", RegexOption.IGNORE_CASE)

    // Tolerance for percentage validation (accounts for rounding)
    private const val PERCENTAGE_TOLERANCE = 1.5f

    /**
     * Validate a generated report against the source sessions.
     *
     * @param reportText The VLM-generated markdown report text.
     * @param sessions   The [DetectionSession] list used to generate the report.
     * @return [ValidationResult] with per-field checks and an overall score.
     */
    fun validate(
        reportText: String,
        sessions: List<DetectionSession>,
        language: String = "en",
    ): ValidationResult {
        if (sessions.isEmpty()) {
            return ValidationResult(
                overallScore = 0,
                checks = listOf(
                    ValidationCheck("source_data", CheckStatus.FAIL, "No source sessions provided")
                ),
            )
        }

        if (reportText.isBlank()) {
            return ValidationResult(
                overallScore = 0,
                checks = listOf(
                    ValidationCheck(
                        field = "report_text",
                        status = CheckStatus.FAIL,
                        detail = "Generated report is empty — model produced no prose output",
                    )
                ),
            )
        }

        val checks = mutableListOf<ValidationCheck>()
        val allDebris = sessions.flatMap { it.debrisList }

        checks += validateDebrisClasses(reportText, allDebris, language)
        checks += validateMaterials(reportText, allDebris, language)
        checks += validatePercentages(reportText, allDebris, language)
        checks += validatePercentageSum(reportText)
        checks += validateInternalConsistency(reportText, allDebris, language)
        checks += validateCoverage(reportText, allDebris, language)
        checks += validateDates(reportText, sessions)
        checks += validateGpsCoordinates(reportText, sessions)
        checks += validateHealthCharacterization(reportText, sessions)
        checks += validateTextRepetition(reportText)
        checks += validatePlaceholders(reportText)
        checks += validateRiskScoreRange(reportText)

        val score = computeOverallScore(checks)
        Log.i(TAG, "Validation complete: score=$score, checks=${checks.size} " +
            "(pass=${checks.count { it.status == CheckStatus.PASS }}, " +
            "warn=${checks.count { it.status == CheckStatus.WARNING }}, " +
            "fail=${checks.count { it.status == CheckStatus.FAIL }})")
        checks.filter { it.status != CheckStatus.PASS }.forEach { c ->
            Log.w(TAG, "Check ${c.status.name} [${c.field}]: ${c.detail}")
        }

        return ValidationResult(overallScore = score, checks = checks)
    }

    // -----------------------------------------------------------------
    // Debris class validation
    // -----------------------------------------------------------------

    private fun validateDebrisClasses(
        text: String,
        allDebris: List<com.oceanguard.ai.data.Debris>,
        language: String = "en",
    ): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        val presentTypes = allDebris.map { it.type }.toSet()
        val textLower = text.lowercase()

        // Check for fabricated classes (mentioned in text but not in data)
        val allKnownTypes = DebrisType.entries.filter { it != DebrisType.OTHER }
        val fabricated = allKnownTypes.filter { type ->
            textContainsAny(textLower, typeNames(type.name, language)) &&
                type !in presentTypes
        }

        if (fabricated.isEmpty()) {
            checks += ValidationCheck(
                field = "debris_classes",
                status = CheckStatus.PASS,
                detail = "All mentioned debris types exist in source data",
            )
        } else {
            checks += ValidationCheck(
                field = "debris_classes",
                status = CheckStatus.FAIL,
                detail = "Fabricated debris types: ${fabricated.joinToString { it.name }}",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Material validation
    // -----------------------------------------------------------------

    private fun validateMaterials(
        text: String,
        allDebris: List<com.oceanguard.ai.data.Debris>,
        language: String = "en",
    ): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        val presentMaterials = allDebris.map { it.material }.toSet()
        val textLower = text.lowercase()

        val allKnownMaterials = DebrisMaterial.entries.filter { it != DebrisMaterial.OTHER }
        val fabricated = allKnownMaterials.filter { mat ->
            textContainsAny(textLower, materialNames(mat.name, language)) &&
                mat !in presentMaterials
        }

        if (fabricated.isEmpty()) {
            checks += ValidationCheck(
                field = "materials",
                status = CheckStatus.PASS,
                detail = "All mentioned materials exist in source data",
            )
        } else {
            checks += ValidationCheck(
                field = "materials",
                status = CheckStatus.FAIL,
                detail = "Fabricated materials: ${fabricated.joinToString { it.name }}",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Percentage validation
    // -----------------------------------------------------------------

    private fun validatePercentages(
        text: String,
        allDebris: List<com.oceanguard.ai.data.Debris>,
        language: String = "en",
    ): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        if (allDebris.isEmpty()) return checks

        val totalCount = allDebris.size
        // Build ground-truth percentages for materials
        val materialCounts = allDebris.groupingBy { it.material.name }.eachCount()
        val materialPcts = materialCounts.mapValues { (_, count) ->
            count * 100f / totalCount
        }
        // Build ground-truth percentages for types
        val typeCounts = allDebris.groupingBy { it.type.name }.eachCount()
        val typePcts = typeCounts.mapValues { (_, count) ->
            count * 100f / totalCount
        }
        val allPcts = materialPcts + typePcts

        // Build name alias lookup: raw key → set of all name variants
        val keyAliases = mutableMapOf<String, Set<String>>()
        for (key in materialCounts.keys) keyAliases[key] = materialNames(key, language)
        for (key in typeCounts.keys) keyAliases[key] = typeNames(key, language)

        // Extract percentage claims from text and try to match them
        val lines = text.lines()
        var matchedCorrect = 0
        var matchedIncorrect = 0
        val incorrectDetails = mutableListOf<String>()

        for (line in lines) {
            val lineLower = line.lowercase()
            val pctMatches = PERCENTAGE_PATTERN.findAll(line)

            for (match in pctMatches) {
                val claimedPct = match.groupValues[1].toFloatOrNull() ?: continue
                // Skip 100% or 0% — too generic
                if (claimedPct == 0f || claimedPct == 100f) continue

                // Find the BEST matching entity (longest alias wins to avoid substring collisions)
                val matchedKey = findBestEntityMatch(lineLower, keyAliases)

                if (matchedKey != null) {
                    val expectedPct = allPcts[matchedKey]!!
                    if (kotlin.math.abs(claimedPct - expectedPct) <= PERCENTAGE_TOLERANCE) {
                        matchedCorrect++
                    } else {
                        matchedIncorrect++
                        incorrectDetails += "$matchedKey: claimed ${claimedPct}%, actual ${String.format("%.1f", expectedPct)}%"
                    }
                }
            }
        }

        val totalMatched = matchedCorrect + matchedIncorrect
        if (totalMatched == 0) {
            checks += ValidationCheck(
                field = "percentages",
                status = CheckStatus.WARNING,
                detail = "No verifiable percentages found in report text",
            )
        } else if (matchedIncorrect == 0) {
            checks += ValidationCheck(
                field = "percentages",
                status = CheckStatus.PASS,
                detail = "All $matchedCorrect verified percentages are accurate",
            )
        } else {
            checks += ValidationCheck(
                field = "percentages",
                status = if (matchedIncorrect > matchedCorrect) CheckStatus.FAIL else CheckStatus.WARNING,
                detail = "$matchedIncorrect/$totalMatched percentages inaccurate: ${incorrectDetails.joinToString("; ")}",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Date validation
    // -----------------------------------------------------------------

    private fun validateDates(
        text: String,
        sessions: List<DetectionSession>,
    ): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val sorted = sessions.sortedBy { it.timestamp }
        val firstDate = sorted.first().timestamp
        val lastDate = sorted.last().timestamp

        // Allow 1-day margin for timezone edge cases
        val marginMs = TimeUnit.DAYS.toMillis(1)
        val rangeStart = Date(firstDate.time - marginMs)
        val rangeEnd = Date(lastDate.time + marginMs)

        val claimedDates = DATE_PATTERN.findAll(text).mapNotNull { match ->
            runCatching { dateFormat.parse(match.value) }.getOrNull()
        }.toList()

        if (claimedDates.isEmpty()) {
            checks += ValidationCheck(
                field = "dates",
                status = CheckStatus.PASS,
                detail = "No explicit dates found to validate",
            )
        } else {
            val outOfRange = claimedDates.filter { it.before(rangeStart) || it.after(rangeEnd) }
            if (outOfRange.isEmpty()) {
                checks += ValidationCheck(
                    field = "dates",
                    status = CheckStatus.PASS,
                    detail = "All ${claimedDates.size} dates fall within survey range",
                )
            } else {
                checks += ValidationCheck(
                    field = "dates",
                    status = CheckStatus.FAIL,
                    detail = "${outOfRange.size}/${claimedDates.size} dates outside survey range " +
                        "(${dateFormat.format(firstDate)} to ${dateFormat.format(lastDate)})",
                )
            }
        }

        return checks
    }

    // -----------------------------------------------------------------
    // GPS coordinate validation
    // -----------------------------------------------------------------

    private fun validateGpsCoordinates(
        text: String,
        sessions: List<DetectionSession>,
    ): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        val locations = sessions.mapNotNull { it.location }

        if (locations.isEmpty()) {
            // No GPS data in source — any coordinates in text are fabricated
            val gpsMatches = GPS_PATTERN.findAll(text).toList()
            // Filter out matches that are likely not GPS (e.g., percentages, IDs)
            val possibleGps = gpsMatches.filter { match ->
                val value = match.value.toDoubleOrNull() ?: return@filter false
                // Reasonable lat/lon range
                value in -180.0..180.0 && match.value.contains(".")
                    && match.value.substringAfter(".").length >= 3
            }
            if (possibleGps.size >= 2) {
                checks += ValidationCheck(
                    field = "gps_coordinates",
                    status = CheckStatus.WARNING,
                    detail = "Report contains ${possibleGps.size} GPS-like values but source has no GPS data",
                )
            }
            return checks
        }

        val lats = locations.map { it.latitude }
        val lons = locations.map { it.longitude }
        // Expand bounding box by a small margin (~100m at equator)
        val margin = 0.001
        val latRange = (lats.min() - margin)..(lats.max() + margin)
        val lonRange = (lons.min() - margin)..(lons.max() + margin)

        // Extract GPS coordinate pairs from text (look for lat/lon patterns in context)
        val allNumbers = GPS_PATTERN.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
        val outOfBounds = mutableListOf<Double>()

        for (num in allNumbers) {
            // Only validate numbers in plausible lat/lon range with sufficient precision
            val str = num.toString()
            if (str.substringAfter(".").length < 3) continue

            when {
                num in -90.0..90.0 && num !in latRange && lats.any { kotlin.math.abs(it) > 0.01 } -> {
                    // Possible latitude outside range
                    if (num !in lonRange) outOfBounds += num
                }
                num in -180.0..180.0 && num !in lonRange && num !in latRange -> {
                    outOfBounds += num
                }
            }
        }

        if (outOfBounds.isEmpty()) {
            checks += ValidationCheck(
                field = "gps_coordinates",
                status = CheckStatus.PASS,
                detail = "GPS coordinates consistent with source data bounding box",
            )
        } else {
            checks += ValidationCheck(
                field = "gps_coordinates",
                status = CheckStatus.WARNING,
                detail = "${outOfBounds.size} coordinate values outside source bounding box",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Health score characterization
    // -----------------------------------------------------------------

    private fun validateHealthCharacterization(
        text: String,
        sessions: List<DetectionSession>,
    ): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        val avgHealth = sessions.map { it.healthScore }.average()
        val textLower = text.lowercase()

        // Check for mischaracterization: good health described as bad, or vice versa
        val negativeTerms = listOf(
            "critical", "severe", "alarming", "heavily polluted", "heavily contaminated",
            "extremely degraded", "urgent crisis", "devastating",
            "critico", "severo", "alarmante", "altamente contaminad",
            "extremadamente degradad", "crisis urgente",
        )
        val positiveTerms = listOf(
            "pristine", "excellent condition", "minimal pollution", "very healthy",
            "negligible contamination", "virtually clean",
            "prístino", "excelente condición", "contaminación mínima",
        )

        val hasNegative = negativeTerms.any { textLower.contains(it) }
        val hasPositive = positiveTerms.any { textLower.contains(it) }

        when {
            avgHealth > 75 && hasNegative && !hasPositive -> {
                checks += ValidationCheck(
                    field = "health_characterization",
                    status = CheckStatus.FAIL,
                    detail = "Report uses alarming language but avg health score is ${String.format("%.0f", avgHealth)} (good condition)",
                )
            }
            avgHealth < 30 && hasPositive && !hasNegative -> {
                checks += ValidationCheck(
                    field = "health_characterization",
                    status = CheckStatus.FAIL,
                    detail = "Report uses positive language but avg health score is ${String.format("%.0f", avgHealth)} (critical condition)",
                )
            }
            else -> {
                checks += ValidationCheck(
                    field = "health_characterization",
                    status = CheckStatus.PASS,
                    detail = "Health characterization consistent with avg score ${String.format("%.0f", avgHealth)}",
                )
            }
        }

        // Validate explicit health score mentions
        val scoreMentions = HEALTH_SCORE_PATTERN.findAll(text).toList()
        for (mention in scoreMentions) {
            val claimed = mention.groupValues[1].toIntOrNull() ?: continue
            val closest = sessions.minByOrNull { kotlin.math.abs(it.healthScore - claimed) }
            if (closest != null && kotlin.math.abs(closest.healthScore - claimed) > 5) {
                checks += ValidationCheck(
                    field = "health_score_value",
                    status = CheckStatus.WARNING,
                    detail = "Claimed health score $claimed doesn't closely match any session score",
                )
            }
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Percentage sum validation (E1)
    // -----------------------------------------------------------------

    private fun validatePercentageSum(text: String): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()

        // Find markdown tables that contain a "%" column
        val lines = text.lines()
        var inTable = false
        var pctColumnIndex = -1
        val tablePcts = mutableListOf<Float>()
        val tableSums = mutableListOf<Float>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                val cells = trimmed.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (!inTable) {
                    // Header row — find % column
                    pctColumnIndex = cells.indexOfFirst { it.contains("%") || it.contains("Total", ignoreCase = true) }
                    if (pctColumnIndex >= 0) {
                        inTable = true
                        tablePcts.clear()
                    }
                } else if (trimmed.contains("---")) {
                    // Separator row — skip
                } else if (pctColumnIndex >= 0 && pctColumnIndex < cells.size) {
                    val pctStr = cells[pctColumnIndex].replace("%", "").trim()
                    pctStr.toFloatOrNull()?.let { tablePcts.add(it) }
                }
            } else if (inTable && tablePcts.isNotEmpty()) {
                // End of table — record sum
                tableSums.add(tablePcts.sum())
                tablePcts.clear()
                inTable = false
                pctColumnIndex = -1
            }
        }
        // Handle table at end of text
        if (tablePcts.isNotEmpty()) {
            tableSums.add(tablePcts.sum())
        }

        val badSums = tableSums.filter { kotlin.math.abs(it - 100f) > 5f }
        if (badSums.isEmpty() && tableSums.isNotEmpty()) {
            checks += ValidationCheck(
                field = "percentage_sum",
                status = CheckStatus.PASS,
                detail = "Table percentage columns sum correctly (~100%)",
            )
        } else if (badSums.isNotEmpty()) {
            checks += ValidationCheck(
                field = "percentage_sum",
                status = CheckStatus.FAIL,
                detail = "Table percentages sum to ${badSums.joinToString { String.format("%.1f%%", it) }} instead of ~100%",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Internal consistency validation (E2)
    // -----------------------------------------------------------------

    private fun validateInternalConsistency(
        text: String,
        allDebris: List<com.oceanguard.ai.data.Debris>,
        language: String = "en",
    ): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        if (allDebris.isEmpty()) return checks

        // Collect all percentage mentions per entity (material or type name)
        val entityPcts = mutableMapOf<String, MutableList<Float>>()
        val materialKeys = allDebris.map { it.material.name }.toSet()
        val typeKeys = allDebris.map { it.type.name }.toSet()
        // Build alias lookup: raw key → set of lowercased name variants
        val entityAliases = mutableMapOf<String, Set<String>>()
        for (key in materialKeys) entityAliases[key] = materialNames(key, language)
        for (key in typeKeys) entityAliases[key] = typeNames(key, language)

        for (line in text.lines()) {
            val lineLower = line.lowercase()
            for (match in PERCENTAGE_PATTERN.findAll(line)) {
                val pct = match.groupValues[1].toFloatOrNull() ?: continue
                if (pct == 0f || pct == 100f) continue

                // Use best-match to avoid substring collisions (e.g. "plástico" inside "residuos plásticos")
                val bestKey = findBestEntityMatch(lineLower, entityAliases)
                if (bestKey != null) {
                    entityPcts.getOrPut(bestKey) { mutableListOf() }.add(pct)
                }
            }
        }

        val inconsistent = mutableListOf<String>()
        for ((entity, pcts) in entityPcts) {
            val distinct = pcts.distinct()
            if (distinct.size > 1) {
                val minPct = distinct.min()
                val maxPct = distinct.max()
                if (maxPct - minPct > 2f) {
                    inconsistent += "$entity: ${distinct.joinToString("/") { "${it}%" }}"
                }
            }
        }

        if (inconsistent.isEmpty()) {
            checks += ValidationCheck(
                field = "internal_consistency",
                status = CheckStatus.PASS,
                detail = "Percentages consistent across report sections",
            )
        } else {
            checks += ValidationCheck(
                field = "internal_consistency",
                status = CheckStatus.FAIL,
                detail = "Contradictory percentages: ${inconsistent.joinToString("; ")}",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Coverage validation (E3)
    // -----------------------------------------------------------------

    private fun validateCoverage(
        text: String,
        allDebris: List<com.oceanguard.ai.data.Debris>,
        language: String = "en",
    ): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        if (allDebris.isEmpty()) return checks

        val total = allDebris.size
        val textLower = text.lowercase()

        // Check materials with ≥10% representation
        val materialCounts = allDebris.groupingBy { it.material }.eachCount()
        val missingMaterials = materialCounts.filter { (_, count) ->
            count * 100f / total >= 10f
        }.filter { (mat, _) ->
            !textContainsAny(textLower, materialNames(mat.name, language))
        }

        // Check types with ≥10% representation
        val typeCounts = allDebris.groupingBy { it.type }.eachCount()
        val missingTypes = typeCounts.filter { (_, count) ->
            count * 100f / total >= 10f
        }.filter { (type, _) ->
            !textContainsAny(textLower, typeNames(type.name, language))
        }

        val allMissing = missingMaterials.map { (mat, count) ->
            "${mat.name} (${String.format("%.0f", count * 100f / total)}%)"
        } + missingTypes.map { (type, count) ->
            "${type.name} (${String.format("%.0f", count * 100f / total)}%)"
        }

        if (allMissing.isEmpty()) {
            checks += ValidationCheck(
                field = "data_coverage",
                status = CheckStatus.PASS,
                detail = "All significant materials/types (≥10%) mentioned in report",
            )
        } else {
            checks += ValidationCheck(
                field = "data_coverage",
                status = CheckStatus.WARNING,
                detail = "Significant items omitted from report: ${allMissing.joinToString(", ")}",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Text repetition detection (E4)
    // -----------------------------------------------------------------

    private fun validateTextRepetition(text: String): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()

        // Split into sentences, normalize
        val sentences = text
            .replace(Regex("""\|[^\n]*\|"""), "") // strip table rows
            .replace(Regex("""```[^`]*```""", RegexOption.DOT_MATCHES_ALL), "") // strip code blocks
            .split(Regex("""[.\n]+"""))
            .map { it.trim().lowercase().replace(Regex("""\s+"""), " ") }
            .filter { it.split(" ").size >= 15 }

        val seen = mutableMapOf<String, Int>()
        for (sentence in sentences) {
            seen[sentence] = (seen[sentence] ?: 0) + 1
        }

        val duplicates = seen.filter { it.value > 1 }
        val totalDuplicates = duplicates.size

        when {
            totalDuplicates == 0 -> {
                checks += ValidationCheck(
                    field = "text_repetition",
                    status = CheckStatus.PASS,
                    detail = "No significant text repetition detected",
                )
            }
            totalDuplicates <= 3 -> {
                checks += ValidationCheck(
                    field = "text_repetition",
                    status = CheckStatus.WARNING,
                    detail = "$totalDuplicates repeated sentence(s) found",
                )
            }
            else -> {
                checks += ValidationCheck(
                    field = "text_repetition",
                    status = CheckStatus.FAIL,
                    detail = "$totalDuplicates repeated sentences - report contains circular/copied text",
                )
            }
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Placeholder detection (E6)
    // -----------------------------------------------------------------

    private fun validatePlaceholders(text: String): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()
        val placeholderPatterns = listOf(
            "[Insert", "[insert", "[Insertar", "[insertar",
            "[TODO", "[todo", "[PLACEHOLDER", "[placeholder",
            "[Valor", "[valor", "[Value", "[value",
            "[Nombre", "[nombre", "[Name", "[name",
            "[Mencionar", "[mencionar", "[Mention", "[mention",
            "[Etiqueta", "[etiqueta", "[Label", "[label",
            "[Frecuencia", "[frecuencia",
            "| ... |", "|...|",
        )

        val found = placeholderPatterns.filter { text.contains(it) }

        if (found.isEmpty()) {
            checks += ValidationCheck(
                field = "placeholders",
                status = CheckStatus.PASS,
                detail = "No unfilled placeholders detected",
            )
        } else {
            checks += ValidationCheck(
                field = "placeholders",
                status = CheckStatus.FAIL,
                detail = "Unfilled placeholders in report: ${found.joinToString(", ") { "\"$it...\"" }}",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Risk score range validation (1-5 scale)
    // -----------------------------------------------------------------

    private val RISK_SCORE_PATTERN = Regex(
        """risk\s*score[:\s]*(\d+)""", RegexOption.IGNORE_CASE,
    )

    private fun validateRiskScoreRange(text: String): List<ValidationCheck> {
        val checks = mutableListOf<ValidationCheck>()

        // Also scan Risk Matrix table rows for numeric scores in the score column
        val riskScores = mutableListOf<Int>()

        // Method 1: "Risk Score: N" patterns in text
        RISK_SCORE_PATTERN.findAll(text).forEach { match ->
            match.groupValues[1].toIntOrNull()?.let { riskScores += it }
        }

        // Method 2: scan table rows in Risk Matrix section
        val lines = text.lines()
        var inRiskTable = false
        var scoreColumnIdx = -1
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                val cells = trimmed.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (!inRiskTable && cells.any { cell ->
                    cell.contains("Risk", ignoreCase = true) && cell.contains(Regex("""\d"""))
                        || cell.contains("Risk Score", ignoreCase = true)
                        || cell.contains("Riesgo", ignoreCase = true) && cell.contains("1-5")
                }) {
                    scoreColumnIdx = cells.indexOfFirst { cell ->
                        cell.contains("Risk Score", ignoreCase = true)
                            || (cell.contains("Risk", ignoreCase = true) && cell.contains(Regex("""\d""")))
                            || (cell.contains("Riesgo", ignoreCase = true) && cell.contains("1-5"))
                    }
                    inRiskTable = true
                } else if (inRiskTable && trimmed.contains("---")) {
                    // separator — skip
                } else if (inRiskTable && scoreColumnIdx in cells.indices) {
                    cells[scoreColumnIdx].trim().toIntOrNull()?.let { riskScores += it }
                }
            } else if (inRiskTable && !trimmed.startsWith("|")) {
                inRiskTable = false
                scoreColumnIdx = -1
            }
        }

        if (riskScores.isEmpty()) return checks

        val outOfRange = riskScores.filter { it !in 1..5 }
        if (outOfRange.isEmpty()) {
            checks += ValidationCheck(
                field = "risk_score_range",
                status = CheckStatus.PASS,
                detail = "All ${riskScores.size} risk scores within valid 1-5 range",
            )
        } else {
            checks += ValidationCheck(
                field = "risk_score_range",
                status = CheckStatus.FAIL,
                detail = "${outOfRange.size}/${riskScores.size} risk scores outside valid 1-5 range: ${outOfRange.joinToString()}",
            )
        }

        return checks
    }

    // -----------------------------------------------------------------
    // Confidence scoring
    // -----------------------------------------------------------------

    /**
     * Computes a composite confidence score (0–100) for a generated report.
     *
     * Combines:
     * - **Validation score** (40%): from [validate] checks
     * - **Data quality** (30%): image quality distribution, GPS coverage, session count
     * - **Detection confidence** (30%): average model confidence across all detections
     *
     * Higher score = more trustworthy report.
     */
    fun computeConfidenceScore(
        validationResult: ValidationResult,
        sessions: List<DetectionSession>,
    ): Int {
        // 1. Validation component (0-100, weight 40%)
        val validationComponent = validationResult.overallScore

        // 2. Data quality component (0-100, weight 30%)
        val dataQuality = computeDataQualityScore(sessions)

        // 3. Detection confidence component (0-100, weight 30%)
        val detectionConfidence = computeDetectionConfidenceScore(sessions)

        return ((validationComponent * 0.4f) +
            (dataQuality * 0.3f) +
            (detectionConfidence * 0.3f)).toInt().coerceIn(0, 100)
    }

    private fun computeDataQualityScore(sessions: List<DetectionSession>): Int {
        if (sessions.isEmpty()) return 0

        // Image quality: GOOD=100, FAIR=60, POOR=20
        val qualityScore = sessions.map { s ->
            when (s.imageQuality) {
                ImageQuality.GOOD -> 100
                ImageQuality.FAIR -> 60
                ImageQuality.POOR -> 20
            }
        }.average()

        // GPS coverage: % of sessions with location data
        val gpsCoverage = sessions.count { it.location != null } * 100.0 / sessions.size

        // Session count bonus: more data = more reliable (log scale, max at 20+)
        val countBonus = (minOf(sessions.size, 20) * 100.0 / 20)

        return ((qualityScore * 0.4) + (gpsCoverage * 0.3) + (countBonus * 0.3)).toInt()
            .coerceIn(0, 100)
    }

    private fun computeDetectionConfidenceScore(sessions: List<DetectionSession>): Int {
        val allDebris = sessions.flatMap { it.debrisList }
        if (allDebris.isEmpty()) return 50 // neutral when no detections

        val avgConfidence = allDebris.map { it.confidence }.average()
        // Map 0.0-1.0 confidence to 0-100 score
        return (avgConfidence * 100).toInt().coerceIn(0, 100)
    }

    // -----------------------------------------------------------------
    // Overall score
    // -----------------------------------------------------------------

    private fun computeOverallScore(checks: List<ValidationCheck>): Int {
        if (checks.isEmpty()) return 100
        val weights = mapOf(
            CheckStatus.PASS to 100,
            CheckStatus.WARNING to 60,
            CheckStatus.FAIL to 0,
        )
        val total = checks.sumOf { weights[it.status] ?: 0 }
        return (total / checks.size).coerceIn(0, 100)
    }
}

// =====================================================================
// Data models
// =====================================================================

enum class CheckStatus { PASS, WARNING, FAIL }

data class ValidationCheck(
    val field: String,
    val status: CheckStatus,
    val detail: String,
)

data class ValidationResult(
    val overallScore: Int,
    val checks: List<ValidationCheck>,
) {
    val passCount: Int get() = checks.count { it.status == CheckStatus.PASS }
    val warningCount: Int get() = checks.count { it.status == CheckStatus.WARNING }
    val failCount: Int get() = checks.count { it.status == CheckStatus.FAIL }

    fun toSummaryString(): String = buildString {
        append("Validation: $overallScore/100")
        append(" (${passCount}P/${warningCount}W/${failCount}F)")
        val failures = checks.filter { it.status == CheckStatus.FAIL }
        if (failures.isNotEmpty()) {
            append("\nIssues: ")
            append(failures.joinToString("; ") { it.detail })
        }
    }

    fun toJsonString(): String = buildString {
        append("{\"score\":$overallScore,\"checks\":[")
        checks.forEachIndexed { i, check ->
            if (i > 0) append(",")
            append("{\"field\":\"${check.field}\",\"status\":\"${check.status.name}\",")
            append("\"detail\":\"${check.detail.replace("\"", "\\\"")}\"}")
        }
        append("]}")
    }
}
