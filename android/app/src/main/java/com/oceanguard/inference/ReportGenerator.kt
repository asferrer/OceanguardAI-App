package com.oceanguard.ai.inference

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DayGroup
import com.oceanguard.ai.utils.ImagePreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
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
 * @param inference The shared [VlmTextEngine] instance (e.g. LlamaTextEngine).
 *   The generator does NOT own the lifecycle of this object.
 */
class ReportGenerator(
    private val inference: VlmTextEngine,
    /** Optional: required only for [generateVisionZoneReportStreaming]. */
    private val imagePreprocessor: ImagePreprocessor? = null,
) {

    companion object {
        private const val TAG = "ReportGenerator"

        // JSON data caps — prevents prompt overflow with large datasets.
        // Template alone (~2250 tok) + data + maxTokens(6144) must fit in nCtx(12288).
        // Budget for JSON data: 12288 - 6144 - 2250 - 300 (system) ≈ 3600 tokens.
        private const val MAX_WAYPOINTS = 20      // top-priority GPS waypoints (~50 tok each)
        private const val MAX_SESSION_DETAILS = 25 // most recent sessions in generic JSON (~75 tok each)
        private const val MAX_ZONE_DAYS = 30       // most recent survey days in zone JSON (~50 tok each)

        // Native + English name — models respond more reliably to native-language names.
        private val LANGUAGE_NAMES = mapOf(
            "en" to "English",
            "es" to "Español (Spanish)",
            "fr" to "Français (French)",
            "de" to "Deutsch (German)",
            "it" to "Italiano (Italian)",
            "pt" to "Português (Portuguese)",
        )

        // Translated first section heading used as assistant prefill.
        // Pre-filling the assistant turn with a non-English heading forces the 0.8B model
        // to continue in that language — more reliable than instructions alone.
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

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        private const val HIGH_RISK_THRESHOLD = 4
        private const val MEDIUM_RISK_THRESHOLD = 3
    }

    /**
     * Distinguishes report types for section template selection.
     * NONE is used for vision-based reports whose user prompt provides its own structure.
     */
    private enum class ReportType { GENERIC, ZONE, NONE }

    // -----------------------------------------------------------------
    // Public API — Generic report
    // -----------------------------------------------------------------

    suspend fun generateReport(
        sessions: List<DetectionSession>,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
    ): String = withContext(Dispatchers.IO) {
        require(sessions.isNotEmpty()) { "Cannot generate report with no sessions" }
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val summary = buildJsonSummary(sessions)
        Log.i(TAG, "Generating report for ${sessions.size} sessions in $languageName")
        val prompt = buildVlmPrompt(languageName, summary, language)
        val response = runTextInference(prompt, language, languageName, audience)
        Log.i(TAG, "VLM report generated successfully (${response.length} chars)")
        QwenPromptFormatter.sanitizeOutput(response)
    }

    // -----------------------------------------------------------------
    // Public API — Zone-based temporal report
    // -----------------------------------------------------------------

    suspend fun generateZoneReport(
        input: ZoneReportInput,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
    ): String = withContext(Dispatchers.IO) {
        require(input.sessions.isNotEmpty()) { "Cannot generate zone report with no sessions" }
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val summary = buildZoneJsonSummary(input)
        Log.i(TAG, "Generating zone report for ${input.locationName} in $languageName")
        val dateRangeLabel = buildDateRangeLabel(input.dateRangeStartMs, input.dateRangeEndMs)
        val prompt = buildZoneVlmPrompt(languageName, summary, input.locationName, dateRangeLabel, language)
        val response = runTextInference(prompt, language, languageName, audience, ReportType.ZONE)
        Log.i(TAG, "Zone VLM report generated (${response.length} chars)")
        QwenPromptFormatter.sanitizeOutput(response)
    }

    // -----------------------------------------------------------------
    // Streaming API — emit partial text during generation
    // -----------------------------------------------------------------

    suspend fun generateReportStreaming(
        sessions: List<DetectionSession>,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        require(sessions.isNotEmpty()) { "Cannot generate report with no sessions" }
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val summary = buildJsonSummary(sessions)
        Log.i(TAG, "Generating streamed report for ${sessions.size} sessions in $languageName")
        val prompt = buildVlmPrompt(languageName, summary, language)
        val firstHeading = FIRST_HEADING[language] ?: "## Executive Summary"
        val response = inference.generateText(
            prompt = prompt,
            maxTokens = 6144,
            systemMessage = buildSystemMessage(languageName, audience, ReportType.GENERIC),
            assistantPrefill = firstHeading,
        ) { partial ->
            onPartialResult(QwenPromptFormatter.sanitizePartial(partial))
        }
        Log.i(TAG, "Streamed report complete (${response.length} chars)")
        QwenPromptFormatter.sanitizeOutput(response)
    }

    suspend fun generateZoneReportStreaming(
        input: ZoneReportInput,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        require(input.sessions.isNotEmpty()) { "Cannot generate zone report with no sessions" }
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val summary = buildZoneJsonSummary(input)
        Log.i(TAG, "Generating streamed zone report for ${input.locationName} in $languageName")
        val dateRangeLabel = buildDateRangeLabel(input.dateRangeStartMs, input.dateRangeEndMs)
        val prompt = buildZoneVlmPrompt(languageName, summary, input.locationName, dateRangeLabel, language)
        val firstHeading = FIRST_HEADING_ZONE[language] ?: "## Zone Profile"
        val response = inference.generateText(
            prompt = prompt,
            maxTokens = 6144,
            systemMessage = buildSystemMessage(languageName, audience, ReportType.ZONE),
            assistantPrefill = firstHeading,
        ) { partial ->
            onPartialResult(QwenPromptFormatter.sanitizePartial(partial))
        }
        Log.i(TAG, "Streamed zone report complete (${response.length} chars)")
        QwenPromptFormatter.sanitizeOutput(response)
    }

    // -----------------------------------------------------------------
    // Detection verification (VLM as auditor)
    // -----------------------------------------------------------------

    /**
     * Result of VLM visual verification for a single [DetectionSession].
     *
     * @param sessionId       Matches [DetectionSession.id].
     * @param confirmed       Debris class names the VLM can visually confirm.
     * @param falsePositives  Classes the VLM believes are wrong detections.
     * @param siteConditions  Brief free-text description of environmental context.
     * @param debrisState     Degradation state: "fresh", "weathered", "fragmented", "mixed".
     * @param confidence      VLM self-reported confidence (0.0–1.0).
     * @param rawOutput       Raw model output, kept for debugging.
     */
    data class VlmVerificationResult(
        val sessionId: Long,
        val confirmed: List<String>,
        val falsePositives: List<String>,
        val siteConditions: String,
        val debrisState: String,
        val confidence: Float,
        /** Classes where the assigned material is visually inconsistent (e.g. "Can" but looks plastic). */
        val materialIssues: List<String> = emptyList(),
        val rawOutput: String = "",
    )

    /**
     * Verify detections in up to [maxImages] sessions using the [visionEngine].
     *
     * Each session produces a short JSON verification (~50–80 tokens) instead of a
     * full report. This is fast enough to run for 5 sessions in ~60–90s before
     * handing off to the text model for actual report generation.
     *
     * The VLM output is forced to start with `{"confirmed":` via assistant prefill
     * so even a 2B model reliably produces parseable JSON.
     *
     * @param onProgress Called after each session is verified: (verified, total).
     */
    /**
     * Verify detections in up to [maxImages] sessions using [visionEngine].
     * Default verifies ALL sessions — pass a lower cap if needed for time budgets.
     */
    suspend fun verifyDetections(
        sessions: List<DetectionSession>,
        visionEngine: VlmVisionEngine,
        maxImages: Int = Int.MAX_VALUE,
        onProgress: (verified: Int, total: Int) -> Unit = { _, _ -> },
    ): List<VlmVerificationResult> = withContext(Dispatchers.IO) {
        val processor = imagePreprocessor ?: return@withContext emptyList()
        val candidates = if (maxImages >= sessions.size) sessions
            else sessions.sortedByDescending { it.totalCount }.take(maxImages)
        val results = mutableListOf<VlmVerificationResult>()

        candidates.forEachIndexed { idx, session ->
            val bitmap: Bitmap? = runCatching {
                processor.loadAndPreprocess(Uri.parse(session.imageUri), targetSize = 512)
            }.onFailure { e ->
                Log.w(TAG, "Cannot load image for session ${session.id}: ${e.message}")
            }.getOrNull()

            if (bitmap != null) {
                try {
                    val result = verifySingleSession(session, bitmap, visionEngine)
                    results.add(result)
                    Log.d(TAG, "Verified session ${session.id}: confirmed=${result.confirmed} fp=${result.falsePositives}")
                } catch (e: Exception) {
                    Log.w(TAG, "Verification failed for session ${session.id}: ${e.message}")
                } finally {
                    bitmap.recycle()
                }
            }
            onProgress(idx + 1, candidates.size)
        }

        Log.i(TAG, "Verification complete: ${results.size}/${candidates.size} sessions verified")
        results
    }

    private suspend fun verifySingleSession(
        session: DetectionSession,
        bitmap: Bitmap,
        visionEngine: VlmVisionEngine,
    ): VlmVerificationResult {
        // Build a minimal detection list for the prompt
        val detectionList = session.debrisList
            .groupingBy { it.type.name }
            .eachCount()
            .entries
            .joinToString(", ") { "${it.key}×${it.value}" }
            .ifEmpty { "no detections" }

        val prompt = """
Verify these marine debris detections against the photograph.
Detections: $detectionList

Output ONLY valid JSON — no preamble, no explanation:
{"confirmed":[],"false_positives":[],"material_issues":[],"site_conditions":"","debris_state":"fresh","confidence":0.0}

Rules:
- confirmed: class names you can visually verify in the image
- false_positives: classes not visible or clearly misidentified
- material_issues: class names where the assigned material looks wrong (e.g. "Can" but appears plastic, not metal; "Plastic_Debris" but appears metallic)
- site_conditions: ≤10 words describing site (water clarity, coast type, conditions)
- debris_state: one of "fresh", "weathered", "fragmented", "mixed"
- confidence: float 0.0–1.0 for your overall verification confidence
""".trimIndent()

        // Prefill with opening brace to force JSON output from the 2B model
        val raw = visionEngine.generateWithImage(
            bitmap           = bitmap,
            prompt           = prompt,
            maxTokens        = 150,
            systemMessage    = "You are a marine debris detection verification system. Output only JSON.",
            assistantPrefill = "{\"confirmed\":",
        )

        return parseVerificationJson(session.id, raw)
    }

    private fun parseVerificationJson(sessionId: Long, raw: String): VlmVerificationResult {
        // The model outputs starting AFTER our prefill, so reconstruct full JSON.
        // raw may start with the rest after "confirmed": or may include the whole thing.
        val jsonStr = when {
            raw.trimStart().startsWith("{") -> raw.trim()
            else -> "{\"confirmed\":${raw.trim()}"
        }.let { s ->
            // Close unclosed JSON if model was cut off by maxTokens
            if (!s.trimEnd().endsWith("}")) "$s}" else s
        }

        return try {
            val obj = JSONObject(jsonStr)
            val confirmed = obj.optJSONArray("confirmed")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val fp = obj.optJSONArray("false_positives")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val matIssues = obj.optJSONArray("material_issues")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            VlmVerificationResult(
                sessionId      = sessionId,
                confirmed      = confirmed,
                falsePositives = fp,
                siteConditions = obj.optString("site_conditions", ""),
                debrisState    = obj.optString("debris_state", "unknown"),
                confidence     = obj.optDouble("confidence", 0.5).toFloat(),
                materialIssues = matIssues,
                rawOutput      = raw,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse verification JSON for session $sessionId: $raw")
            VlmVerificationResult(
                sessionId      = sessionId,
                confirmed      = emptyList(),
                falsePositives = emptyList(),
                siteConditions = "",
                debrisState    = "unknown",
                confidence     = 0f,
                rawOutput      = raw,
            )
        }
    }

    /**
     * Generate a zone report using pre-computed VLM verification results.
     *
     * The [verifications] are embedded into the JSON summary so the text model
     * (Quality 3B) has explicit "confirmed vs false_positive" data per session,
     * plus site conditions and debris state that the detector cannot provide.
     *
     * This is the main entry point after [verifyZoneDetections] completes.
     */
    suspend fun generateVerifiedZoneReportStreaming(
        input: ZoneReportInput,
        verifications: List<VlmVerificationResult>,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val firstHeading = FIRST_HEADING_ZONE[language] ?: "## Zone Profile"

        val enrichedJson = buildEnrichedZoneJsonSummary(input, verifications)
        val dateRangeLabel = buildDateRangeLabel(input.dateRangeStartMs, input.dateRangeEndMs)
        val prompt = buildVerifiedZoneVlmPrompt(languageName, enrichedJson, input.locationName, dateRangeLabel, language, verifications)

        val response = inference.generateText(
            prompt           = prompt,
            maxTokens        = 6144,
            systemMessage    = buildSystemMessage(languageName, audience, ReportType.ZONE),
            assistantPrefill = firstHeading,
        ) { partial ->
            onPartialResult(QwenPromptFormatter.sanitizePartial(partial))
        }
        Log.i(TAG, "Verified zone report complete (${response.length} chars, ${verifications.size} verified sessions)")
        QwenPromptFormatter.sanitizeOutput(response)
    }

    /**
     * Generate a generic (non-zone) report using pre-computed VLM verification results.
     * Equivalent to [generateVerifiedZoneReportStreaming] but for flat session lists
     * without zone/location grouping.
     */
    suspend fun generateVerifiedReportStreaming(
        sessions: List<DetectionSession>,
        verifications: List<VlmVerificationResult>,
        language: String = "en",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val firstHeading = FIRST_HEADING[language] ?: "## Executive Summary"

        val enrichedJson = buildEnrichedJsonSummary(sessions, verifications)
        val verifiedCount = verifications.size
        val fpNote = if (verifications.any { it.falsePositives.isNotEmpty() })
            "\nNOTE: Some sessions have vlm_false_positives — exclude those from counts."
        else ""
        val matNote = if (verifications.any { it.materialIssues.isNotEmpty() })
            "\nNOTE: Some sessions have material_issues — flag uncertain material classifications."
        else ""

        val verifiedNote = "The JSON includes RT-DETRv2 detections enriched with VLM visual verification " +
            "of $verifiedCount field photographs. Each verified session has a 'vlm_verification' field. " +
            "Prefer confirmed detections over raw counts when available.$fpNote$matNote"
        val prompt = "$verifiedNote\n${buildVlmPrompt(languageName, enrichedJson, language)}"

        val response = inference.generateText(
            prompt           = prompt,
            maxTokens        = 6144,
            systemMessage    = buildSystemMessage(languageName, audience, ReportType.GENERIC),
            assistantPrefill = firstHeading,
        ) { partial ->
            onPartialResult(QwenPromptFormatter.sanitizePartial(partial))
        }
        Log.i(TAG, "Verified generic report complete (${response.length} chars, ${verifications.size} verified)")
        QwenPromptFormatter.sanitizeOutput(response)
    }

    /**
     * Enriches the standard generic JSON summary with per-session VLM verification data.
     */
    private fun buildEnrichedJsonSummary(
        sessions: List<DetectionSession>,
        verifications: List<VlmVerificationResult>,
    ): String {
        val verificationMap = verifications.associateBy { it.sessionId }
        val baseJson = JSONObject(buildJsonSummary(sessions))

        val verifiedCount = verifications.count { it.confirmed.isNotEmpty() }
        val allFalsePositives = verifications.flatMap { it.falsePositives }.groupingBy { it }.eachCount()
        val allMaterialIssues = verifications.flatMap { it.materialIssues }.groupingBy { it }.eachCount()
        baseJson.put("vlm_verified_sessions", verifiedCount)
        baseJson.put("vlm_false_positives_summary", JSONObject(allFalsePositives as Map<*, *>))
        baseJson.put("vlm_material_issues_summary", JSONObject(allMaterialIssues as Map<*, *>))

        val sessionsArr = baseJson.optJSONArray("sessions") ?: return baseJson.toString()
        for (i in 0 until sessionsArr.length()) {
            val sessionObj = sessionsArr.getJSONObject(i)
            val sessionId = sessionObj.optLong("id", -1L)
            val verification = verificationMap[sessionId]
            if (verification != null) {
                sessionObj.put("vlm_verification", JSONObject().apply {
                    put("confirmed", JSONArray(verification.confirmed))
                    put("false_positives", JSONArray(verification.falsePositives))
                    put("material_issues", JSONArray(verification.materialIssues))
                    put("site_conditions", verification.siteConditions)
                    put("debris_state", verification.debrisState)
                    put("confidence", verification.confidence.toDouble())
                })
            }
        }
        return baseJson.toString()
    }

    /**
     * Enriches the standard zone JSON summary with per-session VLM verification data.
     * The text model receives both the original detection metadata and the visual audit.
     */
    private fun buildEnrichedZoneJsonSummary(
        input: ZoneReportInput,
        verifications: List<VlmVerificationResult>,
    ): String {
        val verificationMap = verifications.associateBy { it.sessionId }
        val baseJson = JSONObject(buildZoneJsonSummary(input))

        // Add verification summary at top level
        val verifiedCount = verifications.count { it.confirmed.isNotEmpty() }
        val allFalsePositives = verifications.flatMap { it.falsePositives }.groupingBy { it }.eachCount()
        val allMaterialIssues = verifications.flatMap { it.materialIssues }.groupingBy { it }.eachCount()
        val siteConditionsSamples = verifications.mapNotNull { it.siteConditions.takeIf { s -> s.isNotBlank() } }.take(3)

        baseJson.put("vlm_verified_sessions", verifiedCount)
        baseJson.put("vlm_false_positives_summary", JSONObject(allFalsePositives as Map<*, *>))
        baseJson.put("vlm_material_issues_summary", JSONObject(allMaterialIssues as Map<*, *>))
        baseJson.put("vlm_site_conditions_samples", JSONArray(siteConditionsSamples))

        // Annotate each session detail with its verification result
        val sessions = baseJson.optJSONArray("sessions") ?: return baseJson.toString()
        for (i in 0 until sessions.length()) {
            val sessionObj = sessions.getJSONObject(i)
            val sessionId = sessionObj.optLong("id", -1L)
            val verification = verificationMap[sessionId]
            if (verification != null) {
                sessionObj.put("vlm_verification", JSONObject().apply {
                    put("confirmed", JSONArray(verification.confirmed))
                    put("false_positives", JSONArray(verification.falsePositives))
                    put("material_issues", JSONArray(verification.materialIssues))
                    put("site_conditions", verification.siteConditions)
                    put("debris_state", verification.debrisState)
                    put("confidence", verification.confidence.toDouble())
                })
            }
        }

        return baseJson.toString()
    }

    private fun buildVerifiedZoneVlmPrompt(
        languageName: String,
        enrichedJson: String,
        locationName: String,
        dateRangeLabel: String?,
        language: String,
        verifications: List<VlmVerificationResult>,
    ): String {
        val verifiedCount = verifications.size
        val fpNote = if (verifications.any { it.falsePositives.isNotEmpty() })
            "\nNOTE: Some sessions have vlm_false_positives in the data — exclude those from counts and analysis."
        else ""
        val matNote = if (verifications.any { it.materialIssues.isNotEmpty() })
            "\nNOTE: Some sessions have vlm_verification.material_issues — flag these classes as having uncertain material classification in the report."
        else ""
        val verifiedNote = "The JSON includes RT-DETRv2 detections enriched with VLM visual verification " +
            "of $verifiedCount field photographs. Each verified session has a 'vlm_verification' field " +
            "with confirmed detections, false positives, material_issues, site conditions, and debris state. " +
            "Prefer confirmed detections over raw counts when available." +
            fpNote + matNote
        return buildZoneVlmPrompt(languageName, enrichedJson, locationName, dateRangeLabel, language)
            .let { base -> "$verifiedNote\n$base" }
    }

    // -----------------------------------------------------------------
    // VLM inference
    // -----------------------------------------------------------------

    /**
     * Audience-aware system message with language directive in first position.
     * Language comes first because Qwen3.5 tends to default to English when the
     * directive appears later — front-loading it minimises that failure mode.
     * Grounding rules are explicit numbered constraints to reduce hallucinations.
     */
    private fun buildSystemMessage(
        languageName: String,
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        reportType: ReportType = ReportType.GENERIC,
    ): String {
        val persona = when (audience) {
            ReportAudience.SCIENTIFIC ->
                "You are a senior marine conservation scientist authoring a comprehensive, peer-reviewed " +
                "field assessment report. Use scientific nomenclature, cite ecological mechanisms " +
                "by name, provide quantitative analysis, and maintain full methodological rigor throughout."
            ReportAudience.NGO_MANAGER ->
                "You are a senior environmental consultant preparing a detailed operational marine debris " +
                "assessment for a coastal conservation organization. Provide thorough technical analysis " +
                "combined with concrete, prioritized action plans suited for field managers and funders."
            ReportAudience.CITIZEN ->
                "You are an experienced marine environment educator writing a detailed, engaging report " +
                "for citizen scientists, recreational divers, and the general public. Use vivid, " +
                "accessible language with relatable analogies. Motivate action with specific, clear guidance."
        }
        val styleRules = when (audience) {
            ReportAudience.SCIENTIFIC ->
                "Style:\n" +
                "- Passive voice for methodology; active voice for findings and conclusions.\n" +
                "- Hedge appropriately: 'indicates', 'suggests', 'consistent with', 'preliminary data suggest'.\n" +
                "- Species: binomial Latin name (e.g. Caretta caretta) + common name on first mention.\n" +
                "- Tense: present for findings, conditional for recommendations.\n" +
                "- All bullet points are complete sentences ending with a period.\n" +
                "- Include specific numeric values and percentages in every key claim."
            ReportAudience.NGO_MANAGER ->
                "Style:\n" +
                "- Active voice, imperative mood for all recommendations.\n" +
                "- Label every intervention: IMMEDIATE (<48 h) / SHORT-TERM (<30 days) / LONG-TERM.\n" +
                "- Name specific local actors: municipality, port authority, fishing community, coast guard.\n" +
                "- Include rough effort estimates (person-hours, equipment, cost range) where applicable.\n" +
                "- Tense: present for findings, imperative for recommendations."
            ReportAudience.CITIZEN ->
                "Style:\n" +
                "- Warm, motivating, active voice — never condescending or alarmist.\n" +
                "- Convert every abstract metric into a relatable analogy (e.g. '450 years ≈ 6 human lifetimes').\n" +
                "- Explain jargon the first time it appears: 'microplastics (tiny plastic fragments under 5 mm)'.\n" +
                "- Use common names for animals, no Latin binomials.\n" +
                "- Recommendations: personal actions + community actions + civic/policy actions."
        }
        val comprehensivenessDirective =
            "COMPREHENSIVENESS MANDATE: This is a formal assessment document, NOT a summary. " +
            "Write at minimum 1200 words of body content. Every section must contain multiple full paragraphs " +
            "OR a detailed table with ≥4 data rows. Do NOT end the report prematurely — write ALL sections completely. " +
            "If a section has little data, note the limitation and expand adjacent analysis instead.\n\n" +
            "VISUAL ELEMENTS (mandatory):\n" +
            "- Include at least one ASCII bar chart using ██ blocks (e.g. for debris type distribution).\n" +
            "- If collection_waypoints is non-empty, include a text-based spatial map in a code block.\n" +
            "- Include a detailed collection itinerary table with waypoint IDs, coordinates, priority, and method.\n\n"
        return "OUTPUT LANGUAGE: $languageName. Every word of your response must be in " +
            "$languageName. Do not write a single sentence in English unless a Latin scientific " +
            "term has no equivalent. All ## section headings must be in $languageName.\n\n" +
            "$persona\n\n" +
            "$styleRules\n\n" +
            comprehensivenessDirective +
            "Consistency: present tense for findings throughout; refer to data points as 'analyzed images' " +
            "not 'samples', 'photos', or 'sessions'; keep terminology uniform.\n\n" +
            "GROUNDING RULES — mandatory, no exceptions:\n" +
            "1. Only mention debris types, materials, and species derivable from the JSON.\n" +
            "2. Percentages must match JSON counts exactly (round to 1 decimal place).\n" +
            "3. Dates must fall within date_range in the JSON.\n" +
            "4. health_score > 75 means good condition — do not describe it as contaminated.\n" +
            "5. Do not invent GPS coordinates, species names, or ecological pathways not in the data.\n" +
            "6. collection_waypoints in the JSON are the ONLY GPS points you may cite for the itinerary.\n" +
            "7. If a section cannot be substantiated by the JSON data, write exactly: " +
            "'[Insufficient data for this section]' and proceed — never fabricate content to fill sections.\n" +
            "Format: ## headings, ### subheadings, bullet points, markdown tables (| col | col |), code blocks for maps." +
            "\n\n" + buildSectionTemplate(languageName, reportType)
    }

    private suspend fun runTextInference(
        prompt: String,
        language: String = "en",
        languageName: String = "English",
        audience: ReportAudience = ReportAudience.SCIENTIFIC,
        reportType: ReportType = ReportType.GENERIC,
    ): String = inference.generateText(
        prompt = prompt,
        systemMessage = buildSystemMessage(languageName, audience, reportType),
        assistantPrefill = if (reportType == ReportType.ZONE) {
            FIRST_HEADING_ZONE[language] ?: "## Zone Profile"
        } else {
            FIRST_HEADING[language] ?: "## Executive Summary"
        },
    )

    // -----------------------------------------------------------------
    // JSON helpers — shared
    // -----------------------------------------------------------------

    private fun buildDateRangeLabel(startMs: Long?, endMs: Long?): String? {
        if (startMs == null && endMs == null) return null
        val s = startMs?.let { DAY_FORMAT.format(Date(it)) } ?: "…"
        val e = endMs?.let { DAY_FORMAT.format(Date(it)) } ?: "…"
        return "$s – $e"
    }

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
        val materialMap = mutableMapOf<String, Int>()
        allDebris.forEach { d ->
            typeMap[d.type.name] = (typeMap[d.type.name] ?: 0) + 1
            materialMap[d.material.name] = (materialMap[d.material.name] ?: 0) + 1
        }
        val dominantType = typeMap.maxByOrNull { it.value }?.key ?: "N/A"
        val priority = when {
            session.totalCount >= 8 || session.healthScore < 30 -> "HIGH"
            session.totalCount >= 4 || session.healthScore < 60 -> "MEDIUM"
            else -> "LOW"
        }
        return JSONObject().apply {
            put("id", session.id)
            put("timestamp", DATE_FORMAT.format(session.timestamp))
            put("debris_count", session.totalCount)
            put("health_score", session.healthScore)
            put("image_quality", session.imageQuality.name)
            put("type_breakdown", JSONObject(typeMap as Map<*, *>))
            put("material_breakdown", JSONObject(materialMap as Map<*, *>))
            put("dominant_type", dominantType)
            put("collection_priority", priority)
            if (session.location != null) {
                put("lat", String.format("%.5f", session.location.latitude))
                put("lon", String.format("%.5f", session.location.longitude))
            }
        }
    }

    /** Builds a prioritized, GPS-ordered list of collection waypoints from sessions with location data.
     *  Capped at [MAX_WAYPOINTS] to prevent prompt token overflow with large datasets. */
    private fun buildCollectionWaypoints(sessions: List<DetectionSession>): JSONArray {
        return JSONArray().also { arr ->
            sessions
                .filter { it.location != null }
                .sortedByDescending { it.totalCount }
                .take(MAX_WAYPOINTS)
                .forEachIndexed { idx, session ->
                    val materialMap = mutableMapOf<String, Int>()
                    session.debrisList.forEach { d ->
                        materialMap[d.material.name] = (materialMap[d.material.name] ?: 0) + 1
                    }
                    val dominantMaterial = materialMap.maxByOrNull { it.value }?.key ?: "N/A"
                    val dominantType = session.debrisList
                        .groupingBy { it.type.name }.eachCount()
                        .maxByOrNull { it.value }?.key ?: "N/A"
                    arr.put(JSONObject().apply {
                        put("waypoint_id", "WP-${String.format("%02d", idx + 1)}")
                        put("lat", String.format("%.5f", session.location!!.latitude))
                        put("lon", String.format("%.5f", session.location.longitude))
                        put("debris_count", session.totalCount)
                        put("health_score", session.healthScore)
                        put("dominant_type", dominantType)
                        put("dominant_material", dominantMaterial)
                        put("timestamp", DATE_FORMAT.format(session.timestamp))
                        put("collection_priority", when {
                            session.totalCount >= 8 || session.healthScore < 30 -> "HIGH"
                            session.totalCount >= 4 || session.healthScore < 60 -> "MEDIUM"
                            else -> "LOW"
                        })
                    })
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
        // Cap session details to avoid prompt overflow; aggregates above still use all sessions.
        val cappedSessions = if (sorted.size > MAX_SESSION_DETAILS) sorted.takeLast(MAX_SESSION_DETAILS) else sorted
        val sessionDetails = JSONArray()
        cappedSessions.forEach { sessionDetails.put(buildSessionDetail(it)) }

        return JSONObject().apply {
            put("analyzed_images", sessions.size)
            if (sorted.size > MAX_SESSION_DETAILS)
                put("sessions_note", "Showing ${MAX_SESSION_DETAILS} most recent of ${sessions.size} total")
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
            put("collection_waypoints", buildCollectionWaypoints(sessions))
            put("sessions", sessionDetails)
        }.toString()
    }

    /**
     * Section structure templates for GENERIC and ZONE reports.
     * Lives in the system prompt so the KV cache prefix can be reused across consecutive
     * reports generated with the same language + audience combination.
     * NONE returns an empty string — used when the user prompt provides its own structure.
     */
    private fun buildSectionTemplate(languageName: String, reportType: ReportType): String = when (reportType) {
        ReportType.NONE -> ""
        ReportType.GENERIC -> """
REPORT STRUCTURE — Write ALL sections completely in this exact order:

## Executive Summary
Open directly with the most critical finding — NO preamble phrases like "This report presents...".
- Paragraph 1 (3–4 sentences): State the health category (Critical <30 / High 30–50 / Moderate 50–70 / Good >70), exact debris count, survey scope (date range + location or "unspecified location"), and recommended action urgency (Immediate / Short-term / Routine).
- Paragraph 2 (2–3 sentences): Name the dominant debris type (with its exact % of total) and the dominant material, and explain their primary combined ecological risk.

## Methodology & Survey Overview
- Survey parameters: date range (first to last analyzed image), total analyzed images, total debris items detected, average items per image.
- Image quality distribution table (all column headers in $languageName):
  | Quality Tier | Analyzed Images | % of Total |
- Detection confidence statistics: minimum / average / maximum confidence score from confidence_range.
- Risk profile overview: high_risk / medium_risk / low_risk item counts from risk_breakdown.
- Paragraph explaining survey limitations (image quality, GPS availability, single vs. multi-day coverage).

## Spatial Distribution & Collection Waypoints
If collection_waypoints is non-empty:

### Site Map
Using the waypoint coordinates, generate a text-based spatial map in a code block showing the relative geographic positions of all waypoints.
Mark each point with its waypoint_id. Add a compass rose (N/S/E/W) and approximate scale.
```
[Insert ASCII spatial map here using relative positions of waypoints]
```

### Collection Route Itinerary
Generate a prioritized collection itinerary. Sort waypoints by collection_priority (HIGH first), then by debris_count descending.
Table (all column headers in $languageName):
| Waypoint | Latitude | Longitude | Debris Count | Health Score | Dominant Type | Priority | Recommended Method |
For "Recommended Method": suggest boat/on-foot/diving/drone based on debris type and location context.
After the table, write 2–3 sentences describing the recommended collection route sequence.

If collection_waypoints is empty: write a 2-sentence note on the importance of GPS georeferencing for cleanup operations.

## Debris Composition

### Material Analysis
ASCII bar chart (use ██ blocks, scale to the highest count = 20 blocks):
```
[Insert material distribution bar chart]
Example format: Material ████████████ XX% (count: N)
```
Detailed table (all column headers in $languageName):
| Material | Count | % of Total | Marine Persistence (years) | Microplastic Risk | Primary Threat Pathway | Risk Level |

### Type Analysis
Detailed table (all column headers in $languageName):
| Debris Type | Count | % of Total | Primary Marine Hazard | Secondary Hazard | Most Vulnerable Taxa | Estimated Cleanup Effort |

## Per-Type Ecological Impact Analysis
For EACH debris type present in type_breakdown (no omissions, no "maximum N" limit):
Write a dedicated paragraph (4–6 sentences) covering: (1) quantity and proportion found; (2) specific threatened species + threat mechanism (entanglement / ingestion / habitat alteration / chemical leaching); (3) microplastic fragmentation timeline if applicable; (4) ecological pathway in this specific marine context.

## Risk Assessment

### Risk Matrix
Table (all column headers in $languageName):
| Debris Type | Risk Score | Count | Primary Risk Driver | Affected Marine Zone | Urgency |

### Site Risk Summary
- High-risk items: exact count, which types, and why they are critical in a marine context.
- Medium-risk items: exact count and ecological concern.
- Low-risk items: exact count and description.
- **Overall site risk rating**: Critical / High / Moderate / Low — justify with health_score, dominant_material, and risk_breakdown counts.

## Statistical Analysis
- Full per-image analysis table (all column headers in $languageName):
  | Image # | Timestamp | Debris Count | Health Score | Image Quality | Dominant Type | GPS |
  (use "No GPS" if lat/lon absent; include collection_priority)
- Detection confidence interpretation: what do the min/avg/max values imply about detection reliability?
- Quality assessment: correlation between image_quality tiers and debris count.

## Conservation Recommendations
Write 8 specific, prioritized, actionable interventions referencing the ACTUAL debris types and materials found. Number each item and label the timeframe:

1. [IMMEDIATE — <48 h] Target the highest-priority waypoints: specify exact type, collection method, and team size.
2. [IMMEDIATE — <48 h] Emergency containment for the most hazardous item type (entanglement/ingestion risk).
3. [SHORT-TERM — <2 weeks] Full-site cleanup methodology adapted to dominant material and site access.
4. [SHORT-TERM — <30 days] Source tracing: identify the most probable origin pathways and engage upstream stakeholders.
5. [SHORT-TERM — <30 days] Community mobilization: specific volunteer engagement protocols.
6. [MEDIUM-TERM — 3 months] Monitoring plan: survey frequency, KPIs, data collection protocol.
7. [LONG-TERM — 6–12 months] Prevention: policy/regulatory interventions targeting the dominant debris source.
8. [LONG-TERM] Habitat restoration: actions for any affected ecosystems identified in the ecological impact analysis.

## Monitoring Protocol
- Recommended survey frequency based on contamination level (health_score-derived).
- Key performance indicators (KPIs) to track between surveys: debris density trend, health score evolution, species indicators.
- Data collection requirements for the next survey: minimum image count, GPS requirement, quality threshold.
- Trigger conditions for escalating to emergency response.""".trimIndent()

        ReportType.ZONE -> """
REPORT STRUCTURE — Write ALL sections completely in this exact order:

## Zone Profile
- Site name: use the "location" field from the JSON. Coordinates from JSON (centroid lat/lon). Classify the marine zone: coastal / estuary / open water / port / reef — based on coordinate context.
- Survey coverage: total survey days, date range, total analyzed images, total debris items, average debris per image.
- Paragraph (3–4 sentences): Interpret the overall health score (Critical <30 / High 30–50 / Moderate 50–70 / Good >70), the dominant material and type, and their combined significance for this specific marine zone.

## Survey Timeline & Spatial Coverage

### Survey-Day Evolution Table
If survey_days has 2+ entries, show full temporal evolution (all column headers in $languageName):
| Date | Analyzed Images | Debris Items | Health Score | Dominant Material | Dominant Type | Change vs Prior |
After the table, write a paragraph characterizing the temporal pattern.
If only 1 survey day: write a paragraph noting the single-day baseline and recommending a revisit schedule.

### Site Map
If collection_waypoints is non-empty, generate a text-based spatial map in a code block showing waypoint positions:
```
[Insert ASCII spatial map with waypoint labels, compass rose, and approximate scale]
```

### Collection Route Itinerary
Generate a prioritized cleanup itinerary sorted by collection_priority (HIGH first), then debris_count descending.
Table (all column headers in $languageName):
| Waypoint | Latitude | Longitude | Debris Count | Health Score | Dominant Type | Priority | Recommended Method | Est. Time |
After the table, write a 2–3 sentence route narrative describing the recommended sequence and access logistics.

## Debris Composition

### Material Analysis
ASCII bar chart of material distribution (scale: highest count = 20 ██ blocks):
```
[Insert material distribution bar chart]
```
Detailed table (all column headers in $languageName):
| Material | Count | % of Total | Marine Persistence (years) | Microplastic Risk | Primary Threat Pathway | Risk Level |

### Type Analysis
Detailed table (all column headers in $languageName):
| Debris Type | Count | % of Total | Primary Marine Hazard | Secondary Hazard | Most Vulnerable Taxa | Est. Cleanup Effort |

## Per-Type Ecological Impact Analysis
For EACH debris type present in overall.type_breakdown (no omissions):
Write a dedicated paragraph (4–6 sentences): quantity and proportion; specific threatened species + threat mechanism; microplastic fragmentation timeline if applicable; ecological pathway in this marine zone.

## Contamination Trend Analysis
If trend_delta is present (2+ survey days):
- Health score evolution: from [health_score_first] to [health_score_last] = [change] points.
- Debris count evolution: from [debris_count_first] to [debris_count_last] = [change] items.
- Trajectory classification: IMPROVING (health ↑ AND debris ↓) / DEGRADING / MIXED / STABLE — justify with exact numbers.
- Trend interpretation paragraph (3–4 sentences): causation hypotheses, seasonal factors, effectiveness of any prior interventions.
- Projection: if trend continues unchanged, describe the likely site condition in 6 and 12 months.
If trend_delta absent: write that temporal analysis requires ≥2 survey visits; recommend specific revisit interval based on health score.

## Risk Assessment

### Risk Matrix
Table (all column headers in $languageName):
| Debris Type | Risk Score | Count | Primary Risk Driver | Affected Marine Zone | Urgency |

### Overall Risk Rating
- **Site risk rating**: Critical / High / Moderate / Low — justify with health_score, dominant_material, high_risk count.
- Paragraph (3–4 sentences): synthesize the risk profile for this site, naming the most immediate biological threats and the most vulnerable ecosystem components.

## Statistical Analysis
Per-image summary table (all column headers in $languageName):
| Image # | Timestamp | Debris Count | Health Score | Image Quality | Dominant Type | GPS |
(use "No GPS" if coordinates absent)
- Confidence interpretation: what min/avg/max values imply about detection reliability at this site.
- Quality-debris correlation: note any relationship between image_quality tier and debris density.

## Conservation Recommendations
8 specific, prioritized, actionable interventions referencing actual debris types and materials found:

1. [IMMEDIATE — <48 h] Highest-priority waypoints: specify WP IDs, debris types, collection team and equipment.
2. [IMMEDIATE — <48 h] Emergency containment for the highest-entanglement/ingestion-risk item.
3. [SHORT-TERM — <2 weeks] Complete site cleanup plan: methodology adapted to dominant material, site access, and tidal conditions.
4. [SHORT-TERM — <30 days] Source tracing: most probable pollution pathways (river discharge, fishing vessels, coastal urbanization, stormwater runoff).
5. [SHORT-TERM — <30 days] Community and stakeholder engagement: specific local actors, roles, and coordination protocol.
6. [MEDIUM-TERM — 3 months] Monitoring schedule: survey frequency, minimum image count per visit, GPS coverage requirements.
7. [LONG-TERM — 6–12 months] Policy and regulatory interventions targeting the dominant debris source at origin.
8. [LONG-TERM] Habitat restoration: specific actions for affected ecosystems identified in the ecological analysis.

## Monitoring Protocol
- Recommended survey frequency derived from health score and trend.
- KPIs to track between surveys: debris density, health score, dominant type ratio, new type appearances.
- Data requirements for next survey: minimum analyzed images, mandatory GPS, image quality threshold.
- Alert thresholds: define debris count or health score values that trigger emergency response.""".trimIndent()
    }

    /** Minimal user prompt — section structure and grounding live in the system message. */
    private fun buildVlmPrompt(languageName: String, jsonSummary: String, language: String = "en"): String = """
Generate the marine debris environmental assessment using ONLY the JSON data below.

---
$jsonSummary
---
All ## headings in $languageName. Start directly with ${FIRST_HEADING[language] ?: "## Executive Summary"}:""".trimIndent()

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

        val sortedDays = input.dayGroups.sortedBy { it.date }
        val cappedDays = if (sortedDays.size > MAX_ZONE_DAYS) sortedDays.takeLast(MAX_ZONE_DAYS) else sortedDays
        val surveyDays = JSONArray()
        cappedDays.forEach { surveyDays.put(buildDayEntry(it)) }

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

        val trendDelta = if (input.dayGroups.size >= 2) {
            val sorted = input.dayGroups.sortedBy { it.date }
            val first = sorted.first()
            val last = sorted.last()
            JSONObject().apply {
                put("first_day", DAY_FORMAT.format(first.date))
                put("last_day", DAY_FORMAT.format(last.date))
                put("health_score_first", first.avgHealthScore)
                put("health_score_last", last.avgHealthScore)
                put("health_score_change", last.avgHealthScore - first.avgHealthScore)
                put("debris_count_first", first.totalDebris)
                put("debris_count_last", last.totalDebris)
                put("debris_count_change", last.totalDebris - first.totalDebris)
            }
        } else null

        return JSONObject().apply {
            put("location", input.locationName)
            put("coordinates", JSONObject().apply {
                put("lat", String.format("%.4f", input.centroidLat))
                put("lon", String.format("%.4f", input.centroidLon))
            })
            put("survey_days", surveyDays)
            if (sortedDays.size > MAX_ZONE_DAYS)
                put("survey_days_note", "Showing ${MAX_ZONE_DAYS} most recent of ${sortedDays.size} total survey days")
            put("overall", overall)
            put("collection_waypoints", buildCollectionWaypoints(input.sessions))
            if (trendDelta != null) put("trend_delta", trendDelta)
        }.toString()
    }

    private fun buildZoneVlmPrompt(
        languageName: String,
        jsonSummary: String,
        locationName: String,
        dateRangeLabel: String?,
        language: String = "en",
    ): String {
        val periodLine = if (dateRangeLabel != null) {
            "REPORTING PERIOD: $dateRangeLabel (only analyzed images within this range are included)\n"
        } else ""
        return """
${periodLine}Location: $locationName
Generate the zone environmental assessment using ONLY the JSON data below.

---
$jsonSummary
---
All ## headings in $languageName. Start directly with ${FIRST_HEADING_ZONE[language] ?: "## Zone Profile"}:""".trimIndent()
    }

    // -----------------------------------------------------------------
    // Vision-enhanced zone report (multimodal)
    // -----------------------------------------------------------------

    /**
     * Generate a zone report using vision capabilities.
     *
     * Selects up to [maxImages] sessions with the highest debris count as
     * representative images, loads their bitmaps via [ImagePreprocessor],
     * and passes the most representative one to [visionEngine.generateWithImage]
     * alongside the full structured JSON summary.
     *
     * Falls back to text-only generation ([generateZoneReportStreaming]) if
     * no images are accessible or [imagePreprocessor] was not injected.
     *
     * @param visionEngine Must be initialized before calling.
     * @param maxImages    Number of sessions to try loading bitmaps from.
     */
    suspend fun generateVisionZoneReportStreaming(
        input: ZoneReportInput,
        language: String = "en",
        visionEngine: VlmVisionEngine,
        maxImages: Int = 2,
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val firstHeading = FIRST_HEADING_ZONE[language] ?: "## Zone Profile"

        // Build JSON summary and date label first (always needed)
        val jsonSummary    = buildZoneJsonSummary(input)
        val dateRangeLabel = buildDateRangeLabel(input.dateRangeStartMs, input.dateRangeEndMs)

        // Attempt to load representative bitmaps
        val bitmaps = mutableListOf<Bitmap>()
        val processor = imagePreprocessor
        if (processor != null) {
            input.sessions
                .sortedByDescending { it.totalCount }
                .take(maxImages)
                .forEach { session ->
                    runCatching {
                        val uri = Uri.parse(session.imageUri)
                        bitmaps.add(processor.loadAndPreprocess(uri, targetSize = 512))
                    }.onFailure { e ->
                        Log.w(TAG, "Could not load image ${session.imageUri}: ${e.message}")
                    }
                }
        }

        val result = if (bitmaps.isEmpty()) {
            // No images available — fall back to text-only report
            Log.w(TAG, "No bitmaps loaded — falling back to text-only zone report")
            val prompt = buildZoneVlmPrompt(languageName, jsonSummary, input.locationName, dateRangeLabel, language)
            visionEngine.generateText(
                prompt           = prompt,
                maxTokens        = 4096,
                systemMessage    = buildSystemMessage(languageName, reportType = ReportType.NONE),
                assistantPrefill = firstHeading,
            ) { partial -> onPartialResult(QwenPromptFormatter.sanitizePartial(partial)) }
        } else {
            val prompt = buildVisionZoneVlmPrompt(languageName, jsonSummary, input.locationName, dateRangeLabel, language)
            visionEngine.generateWithImage(
                bitmap           = bitmaps.first(),
                prompt           = prompt,
                maxTokens        = 4096,
                systemMessage    = buildSystemMessage(languageName, reportType = ReportType.NONE),
                assistantPrefill = firstHeading,
            ) { partial -> onPartialResult(QwenPromptFormatter.sanitizePartial(partial)) }
        }

        bitmaps.forEach { it.recycle() }

        Log.i(TAG, "Vision zone report complete (${result.length} chars, ${bitmaps.size} images used)")
        QwenPromptFormatter.sanitizeOutput(result)
    }

    /**
     * Zone report prompt variant for vision-enhanced generation.
     * Adds an instruction to describe the image and integrate visual observations.
     */
    private fun buildVisionZoneVlmPrompt(
        languageName: String,
        jsonSummary: String,
        locationName: String,
        dateRangeLabel: String?,
        language: String = "en",
    ): String {
        val periodLine = if (dateRangeLabel != null) {
            "REPORTING PERIOD: $dateRangeLabel (only analyzed images within this range are included)\n"
        } else ""
        return """
LANGUAGE REQUIREMENT: Write this entire report in $languageName. Every heading, sentence, bullet point, and table cell must be in $languageName. Do not write in English unless a technical term has no equivalent.

You are analyzing a field photograph of the survey site together with automated detection metadata.
Describe what you see in the image: debris types visible, water clarity, debris density, spatial
distribution, and any environmental context (vegetation, coastline type, water conditions).
Integrate your visual observations throughout the report sections.

You are generating a marine debris environmental assessment for $locationName.
${periodLine}Rules: ground claims in image AND JSON data; never invent data; use ## headings; markdown tables with | separators; bullet lists; say "analyzed images" not "sessions"; be quantitative.

## Zone Profile
- Site name and coordinates (lat/lon from JSON); classify marine zone (coastal/estuary/open water/port)
- Survey coverage: number of survey days, date range, total analyzed images, total debris items
- Site health: interpret avg_health_score (0=critically contaminated, 50=moderate, 100=pristine)
- Visual observation: describe what the image shows about the site conditions

## Survey Timeline
If survey_days has 2 or more entries, show temporal evolution:
| Date | Images | Debris Items | Health Score | Dominant Material | Change vs Prior |

If only 1 survey day: state that temporal trend analysis requires multiple survey visits.

## Debris Analysis
- Material breakdown table: | Material | Count | % of Total |
- Most dominant type and its visual characteristics as seen in the image
- Estimated fragment/item size range based on image context

## Environmental Risk Assessment
Score 1–5 for: Aquatic toxicity | Entanglement risk | Ingestion risk | Persistence | Spread potential
Justification for each score referencing both detection data and visual evidence.
Overall site risk level: Critical/High/Medium/Low

## Visual Field Observations
Describe in detail what is visible in the image:
- Debris condition: fresh/weathered/fragmented
- Debris distribution: clustered/scattered/tideline
- Environmental conditions: water turbidity, vegetation, coastal morphology
- Any context not captured by the detector (unusual items, recent disturbance signs)

## Recommended Actions for $locationName
6 prioritized, site-specific, actionable interventions informed by visual observations:
1. Immediate removal target: most hazardous type + collection method suitable for this site
2. Cleanup methodology adapted to site type and dominant material
3. Source tracing: most probable pollution origin pathways
4. Monitoring schedule: survey frequency based on trend and severity
5. Stakeholder coordination: relevant local actors
6. Long-term prevention targeting the dominant debris source at origin

---
Detection data (read only — do not copy into report):
$jsonSummary
---
Write the full report now in $languageName for $locationName. Start directly with ${FIRST_HEADING_ZONE[language] ?: "## Zone Profile"}:""".trimIndent()
    }
}
