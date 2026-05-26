package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import android.util.Log

/**
 * VLM-based organism description and RAG hypothesis confirmation.
 *
 * ## Role in the pipeline
 *
 * After [SpeciesReferenceIndex.search] returns a top-k candidate list, the
 * [SpeciesIdentifier] calls one of two entry points:
 *
 *  - [confirmOrDescribe]: when top-1 score is tentative (tauLow..tauHigh) or
 *    margin < δ. Passes the RAG hypothesis to the VLM for confirmation or
 *    contradiction. If the VLM contradicts the hypothesis strongly,
 *    [SpeciesIdResult.contradicted] is true → orchestrator demotes to
 *    RAG_TENTATIVE and surfaces both hypotheses.
 *
 *  - [describeOpenVocab]: when score < tauLow or the index is empty. The VLM
 *    names the organism freely (cola larga / "no catalogada"). Result has
 *    [SpeciesIdResult.confirmedKey] = null and [SpeciesIdResult.freeLabel]
 *    populated if the VLM provided a species name.
 *
 * Both methods inject optional geographic context from [MarineRegion] into
 * the prompt to bias the VLM toward regionally plausible species, following
 * plan section §6.
 *
 * Temperature is set low (0.25) to minimise hallucination. If [engine] is not
 * ready the methods return a safe fallback result without throwing.
 *
 * @param engine  Injected [VlmImageEngine]. When null or not ready, all calls
 *                return safe fallback results.
 */
class SpeciesDescriber(
    private val engine: VlmImageEngine? = null,
) : DescriberPort {
    companion object {
        private const val TAG = "SpeciesDescriber"
        private const val MAX_TOKENS = 200
        private const val TEMPERATURE = 0.25

        internal const val ORGANISM_DESCRIBER_SYSTEM =
            "You are a marine biologist assistant. Describe marine organisms briefly and accurately. " +
            "If a specific species hypothesis is given, confirm it or explain why it does not match. " +
            "Output plain text only — no markdown, no lists, no headers."

        /** Regex to extract a species name from common VLM preambles. */
        private val SPECIES_NAME_PATTERN = Regex(
            """(?:this (?:is|appears to be|looks like) (?:a |an )?)([A-Z][a-z]+ [a-z]+)""",
        )

        /** Keywords that indicate the VLM is contradicting the hypothesis. */
        private val CONTRADICTION_KEYWORDS = listOf(
            "not a ", "does not match", "this is not", "cannot confirm",
            "unlikely to be", "appears to be different", "instead",
        )
    }

    /**
     * Ask the VLM to confirm or refute [hypothesisScientificName] given [crop].
     *
     * Returns a [SpeciesIdResult] with [SpeciesIdResult.confirmedKey] set when
     * the VLM agrees, or [SpeciesIdResult.contradicted] = true when it disagrees.
     *
     * Falls back to a confirming stub result when the engine is not available,
     * to prevent a missing VLM from crashing the identification pipeline.
     *
     * @param crop                    Cropped organism bitmap.
     * @param hypothesisKey           Species key for the RAG top-1 candidate.
     * @param hypothesisScientificName Scientific name for display in the prompt.
     * @param region                  Optional resolved MEOW region for prompt injection.
     * @param language                BCP-47 language code for the output description.
     */
    override suspend fun confirmOrDescribe(
        crop: Bitmap,
        hypothesisKey: String,
        hypothesisScientificName: String,
        region: MarineRegion?,
        language: String,
    ): SpeciesIdResult {
        val vlm = engine
        if (vlm == null || !vlm.isReady) {
            Log.d(TAG, "Engine not ready — returning stub confirmation for $hypothesisScientificName")
            return SpeciesIdResult(description = "", confirmedKey = hypothesisKey)
        }

        val geoContext = region?.let { " Photo taken in ${it.ecoregionName} (${it.realmName})." } ?: ""
        val prompt = buildConfirmPrompt(hypothesisScientificName, geoContext, language)

        val raw = safeGenerate(vlm, crop, prompt)
        return interpretConfirmation(raw, hypothesisKey)
    }

    /**
     * Open-vocabulary description for an organism that did not match any
     * catalogued species (score < τ_baja or empty catalogue).
     *
     * @param crop     Cropped organism bitmap.
     * @param region   Optional MEOW region for geographic context in the prompt.
     * @param language BCP-47 language code for the output description.
     */
    override suspend fun describeOpenVocab(
        crop: Bitmap,
        region: MarineRegion?,
        language: String,
    ): SpeciesIdResult {
        val vlm = engine
        if (vlm == null || !vlm.isReady) {
            Log.d(TAG, "Engine not ready — returning empty open-vocab description")
            return SpeciesIdResult(description = "")
        }

        val geoContext = region?.let { " Photo taken in ${it.ecoregionName} (${it.realmName})." } ?: ""
        val prompt = buildOpenVocabPrompt(geoContext, language)

        val raw = safeGenerate(vlm, crop, prompt)
        return interpretOpenVocab(raw)
    }

    // -----------------------------------------------------------------------
    // Prompt builders
    // -----------------------------------------------------------------------

    private fun buildConfirmPrompt(
        scientificName: String,
        geoContext: String,
        language: String,
    ): String =
        "Is the marine organism in this image $scientificName?$geoContext " +
        "If yes, briefly describe it (2–3 sentences). " +
        "If no, identify what it actually is and explain the difference. " +
        "Reply in $language."

    private fun buildOpenVocabPrompt(geoContext: String, language: String): String =
        "Identify the marine organism in this image.$geoContext " +
        "State its common name, scientific name if known, and describe it briefly (2–3 sentences). " +
        "If the species cannot be determined, describe the organism's key features. " +
        "Reply in $language."

    // -----------------------------------------------------------------------
    // Response interpretation
    // -----------------------------------------------------------------------

    /**
     * Decides confirmation vs contradiction from the VLM free-text response.
     *
     * Contradiction is detected when [CONTRADICTION_KEYWORDS] appear in the
     * first 150 characters (the VLM's verdict sentence typically comes first).
     */
    private fun interpretConfirmation(raw: String, hypothesisKey: String): SpeciesIdResult {
        if (raw.isBlank()) return SpeciesIdResult(description = "", confirmedKey = hypothesisKey)
        val lowerHead = raw.lowercase().take(150)
        val contradicted = CONTRADICTION_KEYWORDS.any { lowerHead.contains(it) }
        return SpeciesIdResult(
            description  = raw.trim(),
            confirmedKey = if (contradicted) null else hypothesisKey,
            contradicted = contradicted,
        )
    }

    /**
     * Extracts an optional species name from the open-vocab response.
     *
     * Tries to find a binomial pattern ("Genus species") in the raw text.
     * If found, stores it in [SpeciesIdResult.freeLabel].
     */
    private fun interpretOpenVocab(raw: String): SpeciesIdResult {
        if (raw.isBlank()) return SpeciesIdResult(description = "")
        val match = SPECIES_NAME_PATTERN.find(raw)
        return SpeciesIdResult(
            description = raw.trim(),
            freeLabel   = match?.groupValues?.getOrNull(1),
        )
    }

    // -----------------------------------------------------------------------
    // Safe call wrapper
    // -----------------------------------------------------------------------

    private suspend fun safeGenerate(vlm: VlmImageEngine, crop: Bitmap, prompt: String): String {
        return try {
            vlm.generateWithImage(
                bitmap        = crop,
                prompt        = prompt,
                systemMessage = ORGANISM_DESCRIBER_SYSTEM,
                maxTokens     = MAX_TOKENS,
                temperature   = TEMPERATURE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "VLM describe failed: ${e.message}")
            ""
        }
    }
}
