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

    // -----------------------------------------------------------------
    // Public API — Generic report
    // -----------------------------------------------------------------

    suspend fun generateReport(
        sessions: List<DetectionSession>,
        language: String = "en",
    ): String = withContext(Dispatchers.IO) {
        require(sessions.isNotEmpty()) { "Cannot generate report with no sessions" }
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val summary = buildJsonSummary(sessions)
        Log.i(TAG, "Generating report for ${sessions.size} sessions in $languageName")
        val prompt = buildVlmPrompt(languageName, summary, language)
        val response = runTextInference(prompt, language, languageName)
        Log.i(TAG, "VLM report generated successfully (${response.length} chars)")
        QwenPromptFormatter.sanitizeOutput(response)
    }

    // -----------------------------------------------------------------
    // Public API — Zone-based temporal report
    // -----------------------------------------------------------------

    suspend fun generateZoneReport(
        input: ZoneReportInput,
        language: String = "en",
    ): String = withContext(Dispatchers.IO) {
        require(input.sessions.isNotEmpty()) { "Cannot generate zone report with no sessions" }
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val summary = buildZoneJsonSummary(input)
        Log.i(TAG, "Generating zone report for ${input.locationName} in $languageName")
        val dateRangeLabel = buildDateRangeLabel(input.dateRangeStartMs, input.dateRangeEndMs)
        val prompt = buildZoneVlmPrompt(languageName, summary, input.locationName, dateRangeLabel, language)
        val response = runTextInference(prompt, language, languageName)
        Log.i(TAG, "Zone VLM report generated (${response.length} chars)")
        QwenPromptFormatter.sanitizeOutput(response)
    }

    // -----------------------------------------------------------------
    // Streaming API — emit partial text during generation
    // -----------------------------------------------------------------

    suspend fun generateReportStreaming(
        sessions: List<DetectionSession>,
        language: String = "en",
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
            maxTokens = 4096,
            systemMessage = buildSystemMessage(languageName),
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
            maxTokens = 4096,
            systemMessage = buildSystemMessage(languageName),
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
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val languageName = LANGUAGE_NAMES[language] ?: "English"
        val firstHeading = FIRST_HEADING_ZONE[language] ?: "## Zone Profile"

        val enrichedJson = buildEnrichedZoneJsonSummary(input, verifications)
        val dateRangeLabel = buildDateRangeLabel(input.dateRangeStartMs, input.dateRangeEndMs)
        val prompt = buildVerifiedZoneVlmPrompt(languageName, enrichedJson, input.locationName, dateRangeLabel, language, verifications)

        val response = inference.generateText(
            prompt           = prompt,
            maxTokens        = 4096,
            systemMessage    = buildSystemMessage(languageName),
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

        val prompt = """
LANGUAGE REQUIREMENT: Write this entire report in $languageName. Every heading, sentence, and table cell must be in $languageName.

Generate a marine debris environmental assessment using ONLY the JSON data below.
The JSON includes RT-DETRv2 detections enriched with visual verification from a multimodal AI
that inspected $verifiedCount field photographs. Each verified session has a "vlm_verification"
field with confirmed detections, false positives, material_issues, site conditions, and debris state.$fpNote$matNote

Rules: never invent data; ## headings; markdown tables with | separators; bullet lists;
"analyzed images" not "sessions"; be quantitative; prefer confirmed detections over raw when available.

${buildVlmPrompt(languageName, enrichedJson, language)
    .lines()
    .dropWhile { !it.startsWith("## Executive Summary") }
    .joinToString("\n")}
""".trimIndent()

        val response = inference.generateText(
            prompt           = prompt,
            maxTokens        = 4096,
            systemMessage    = buildSystemMessage(languageName),
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

        val sessionsArr = baseJson.optJSONArray("sessions") ?: return baseJson.toString(2)
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
        return baseJson.toString(2)
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
        val sessions = baseJson.optJSONArray("sessions") ?: return baseJson.toString(2)
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

        return baseJson.toString(2)
    }

    private fun buildVerifiedZoneVlmPrompt(
        languageName: String,
        enrichedJson: String,
        locationName: String,
        dateRangeLabel: String?,
        language: String,
        verifications: List<VlmVerificationResult>,
    ): String {
        val periodLine = if (dateRangeLabel != null) {
            "REPORTING PERIOD: $dateRangeLabel\n"
        } else ""
        val verifiedCount = verifications.size
        val fpNote = if (verifications.any { it.falsePositives.isNotEmpty() })
            "\nNOTE: Some sessions have vlm_false_positives in the data — exclude those from counts and analysis."
        else ""
        val matNote = if (verifications.any { it.materialIssues.isNotEmpty() })
            "\nNOTE: Some sessions have vlm_verification.material_issues — flag these classes as having uncertain material classification in the report."
        else ""
        return """
LANGUAGE REQUIREMENT: Write this entire report in $languageName. Every heading, sentence, and table cell must be in $languageName.

You are generating a marine debris environmental assessment for $locationName.
${periodLine}The JSON data includes automated RT-DETRv2 detections enriched with visual verification
from a multimodal AI that inspected $verifiedCount field photographs.
Each verified session has a "vlm_verification" field with confirmed detections,
false positives to exclude, material_issues (classes where assigned material is visually inconsistent),
site conditions, and debris degradation state.$fpNote$matNote

Rules: use only the JSON data; never invent; ## headings; markdown tables with | separators;
bullet lists; "analyzed images" not "sessions"; be quantitative with exact numbers and percentages.
When vlm_verification is present for a session, prefer the confirmed list over raw detections.
When material_issues are present, note the uncertainty in the material classification.

${buildZoneVlmPrompt(languageName, enrichedJson, locationName, dateRangeLabel, language)
    .lines()
    .dropWhile { !it.startsWith("## Zone Profile") }
    .joinToString("\n")}
""".trimIndent()
    }

    // -----------------------------------------------------------------
    // VLM inference
    // -----------------------------------------------------------------

    /**
     * System message with a language directive injected.
     * Placing the language requirement in the system role is essential:
     * Qwen3.5 tends to default to English when the system prompt is
     * language-neutral, regardless of instructions in the user message.
     */
    private fun buildSystemMessage(languageName: String): String =
        "You are a professional marine conservation scientist and environmental analyst. " +
        "Generate comprehensive, scientifically rigorous environmental assessment reports " +
        "based solely on the provided detection data. " +
        "Format with ## section headings, bullet points, and markdown tables using | separators. " +
        "Be quantitative: always include exact counts, percentages, and scores from the data. " +
        "Never invent or extrapolate data not present in the input. " +
        "CRITICAL: Your entire response MUST be written in $languageName. " +
        "Every heading, sentence, bullet point, and table cell must be in $languageName. " +
        "Do not write in English unless a scientific term has absolutely no equivalent in $languageName."

    private suspend fun runTextInference(
        prompt: String,
        language: String = "en",
        languageName: String = "English",
    ): String = inference.generateText(
        prompt = prompt,
        systemMessage = buildSystemMessage(languageName),
        assistantPrefill = FIRST_HEADING[language] ?: "## Executive Summary",
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
        allDebris.forEach { d -> typeMap[d.type.name] = (typeMap[d.type.name] ?: 0) + 1 }
        return JSONObject().apply {
            put("id", session.id)   // needed for VLM verification lookup
            put("timestamp", DATE_FORMAT.format(session.timestamp))
            put("debris_count", session.totalCount)
            put("health_score", session.healthScore)
            put("image_quality", session.imageQuality.name)
            put("type_breakdown", JSONObject(typeMap as Map<*, *>))
            if (session.location != null) {
                put("lat", String.format("%.4f", session.location.latitude))
                put("lon", String.format("%.4f", session.location.longitude))
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
        val sessionDetails = JSONArray()
        sorted.forEach { sessionDetails.put(buildSessionDetail(it)) }

        return JSONObject().apply {
            put("analyzed_images", sessions.size)
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
            put("sessions", sessionDetails)
        }.toString(2)
    }

    private fun buildVlmPrompt(languageName: String, jsonSummary: String, language: String = "en"): String = """
LANGUAGE REQUIREMENT: Write this entire report in $languageName. Every heading, sentence, bullet point, and table cell must be in $languageName. Do not write in English unless a technical term has no equivalent.

Generate a marine debris environmental assessment report using ONLY the JSON data below.
Rules: no invented data; use ## headings; markdown tables with | separators; bullet lists; say "analyzed images" not "sessions"; be quantitative with exact numbers and percentages.

## Executive Summary
4 sentences: (1) total analyzed images and date range, (2) total debris items and dominant material with its % of total, (3) health score interpretation (0=critically contaminated, 100=pristine), (4) primary environmental threat and urgency level.

## Survey Overview
- Date range, total analyzed images, average debris items per image
- Image quality breakdown (from image_quality field): count per quality tier
- Detection confidence range: min / avg / max from confidence_range
- Risk profile summary: high_risk / medium_risk / low_risk item counts

## Debris Composition

Table 1 — by material (calculate % from total_debris_items):
| Material | Count | % of Total | Estimated Marine Persistence | Risk Level |

Table 2 — by detection type:
| Debris Type | Count | % of Total | Primary Marine Hazard |

## Environmental Impact Assessment
- **Marine fauna at risk**: for each major debris type found, name specific vulnerable species (fishing nets → entanglement → sea turtles, dolphins, seabirds; plastic fragments → ingestion → fish, seabirds, sea turtles; tires → habitat alteration → benthic invertebrates)
- **Microplastic fragmentation**: identify which materials will fragment into microplastics and approximate timeline (PET plastic ~450 yr; rubber ~80 yr; aluminum ~200 yr)
- **Benthic and coastal impact**: seabed accumulation dynamics, shoreline retention, tidal redistribution potential
- **Trophic bioaccumulation**: debris-to-plankton-to-fish-to-predator pathway for materials detected

## Risk Assessment
- High-risk items (exact count from data): identify which types, why critical in marine context
- Medium-risk items (exact count): describe ecological concern
- Low-risk items (exact count): describe
- **Overall site risk rating**: Critical / High / Moderate / Low — justify using health_score and dominant_material

## Location and Spatial Context
If GPS data available: describe coordinate extent, identify likely marine zone (coastal/estuary/open water/pelagic). If no GPS: note limitation, recommend systematic georeferencing for future surveys.

## Conservation Recommendations
6 specific, prioritized, actionable interventions referencing the actual debris found:
1. Immediate removal (within 48h): specify the most hazardous debris type and the exact collection method
2. Cleanup methodology best suited to the dominant material and site type
3. Source tracing: most probable debris origin pathways to address at root
4. Monitoring plan: specific survey frequency based on contamination level
5. Community and stakeholder engagement protocols
6. Long-term prevention and habitat restoration

---
Detection data (read only — do not copy into report):
$jsonSummary
---
Write the full report now in $languageName. Start directly with ${FIRST_HEADING[language] ?: "## Executive Summary"}:""".trimIndent()

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

        val surveyDays = JSONArray()
        input.dayGroups.sortedBy { it.date }.forEach { surveyDays.put(buildDayEntry(it)) }

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
LANGUAGE REQUIREMENT: Write this entire report in $languageName. Every heading, sentence, bullet point, and table cell must be in $languageName. Do not write in English unless a technical term has no equivalent.

You are generating a marine debris environmental assessment for $locationName.
${periodLine}Rules: use ONLY the JSON data; never invent data; use ## headings; markdown tables with | separators; bullet lists; say "analyzed images" not "sessions"; be quantitative.

## Zone Profile
- Site name and coordinates (lat/lon from JSON); classify the marine zone (coastal/estuary/open water/port)
- Survey coverage: number of survey days, date range, total analyzed images, total debris items
- Site health: interpret overall avg_health_score (0=critically contaminated, 50=moderately contaminated, 100=pristine)

## Survey Timeline
If survey_days has 2 or more entries, show temporal evolution:
| Date | Images | Debris Items | Health Score | Dominant Material | Change vs Prior |

If only 1 survey day: state that temporal trend analysis requires multiple survey visits.

## Debris Composition

Table 1 — by material (calculate % from total_debris_items):
| Material | Count | % of Total | Marine Persistence | Risk Level |

Table 2 — by detection type:
| Debris Type | Count | % of Total | Primary Marine Hazard |

## Environmental Impact Assessment
- **Fauna at risk**: for each major debris type found, name specific vulnerable species and threat mechanism (fishing nets → entanglement → sea turtles, dolphins, diving seabirds; plastic fragments → ingestion → fish larvae, seabirds; ghost gear → passive entanglement → large marine mammals)
- **Microplastic generation**: identify which materials present will fragment and their estimated timeline (PET ~450 yr; polypropylene ~20–30 yr; rubber ~80 yr; aluminum ~200 yr)
- **Benthic and coastal dynamics**: describe seabed accumulation risk, shoreline retention, tidal redistribution for this specific site type
- **Ecosystem stress**: combine health score + high_risk item count + dominant material persistence to characterize overall site stress level

## Contamination Trend
Using the "trend" field (IMPROVING/STABLE/WORSENING/INSUFFICIENT_DATA):
- IMPROVING or WORSENING with multiple days: quantify as % change in total debris count and health score delta (first survey vs last survey)
- STABLE: describe steady-state contamination level and contributing factors
- INSUFFICIENT_DATA: specify what follow-up surveys (frequency, method) would enable trend detection

## Recommended Actions for $locationName
6 prioritized, site-specific, actionable interventions:
1. Immediate removal target: name the most hazardous debris type present and the specific collection method for this site
2. Cleanup methodology adapted to site type and dominant debris material
3. Source tracing: identify the most probable pollution origin pathways (river discharge, fishing vessels, coastal urbanization)
4. Monitoring schedule: survey frequency based on contamination trend and severity
5. Stakeholder coordination: relevant local actors (fishing community, port authority, municipality, NGOs)
6. Long-term prevention targeting the dominant debris source at origin

---
Detection data (read only — do not copy into report):
$jsonSummary
---
Write the full report now in $languageName for $locationName. Start directly with ${FIRST_HEADING_ZONE[language] ?: "## Zone Profile"}:""".trimIndent()
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
                systemMessage    = buildSystemMessage(languageName),
                assistantPrefill = firstHeading,
            ) { partial -> onPartialResult(QwenPromptFormatter.sanitizePartial(partial)) }
        } else {
            val prompt = buildVisionZoneVlmPrompt(languageName, jsonSummary, input.locationName, dateRangeLabel, language)
            visionEngine.generateWithImage(
                bitmap           = bitmaps.first(),
                prompt           = prompt,
                maxTokens        = 4096,
                systemMessage    = buildSystemMessage(languageName),
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
