package com.oceanguard.ai.inference.species

import android.graphics.Bitmap

/**
 * Minimal interface for species description / confirmation used by
 * [SpeciesIdentifier].
 *
 * Extracted so that the orchestrator can be unit-tested on plain JVM without
 * a running VLM. The production implementation is [SpeciesDescriber];
 * test doubles can implement this interface without Android SDK constraints.
 */
interface DescriberPort {

    /**
     * Ask the VLM to confirm or refute a RAG top-1 hypothesis.
     *
     * @see SpeciesDescriber.confirmOrDescribe
     */
    suspend fun confirmOrDescribe(
        crop: Bitmap,
        hypothesisKey: String,
        hypothesisScientificName: String,
        region: MarineRegion? = null,
        language: String = "en",
    ): SpeciesIdResult

    /**
     * Open-vocabulary description for an uncatalogued organism.
     *
     * @see SpeciesDescriber.describeOpenVocab
     */
    suspend fun describeOpenVocab(
        crop: Bitmap,
        region: MarineRegion? = null,
        language: String = "en",
    ): SpeciesIdResult
}
