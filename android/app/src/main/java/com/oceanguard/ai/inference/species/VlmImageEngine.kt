package com.oceanguard.ai.inference.species

import android.graphics.Bitmap

/**
 * Minimal interface for a VLM capable of image-conditioned text generation.
 *
 * Extracted so that [OrganismLocator] and [SpeciesDescriber] can be
 * constructed with test doubles (no native LiteRT-LM / llama.cpp required in
 * unit tests).
 *
 * The production implementation is a thin adapter over
 * [com.oceanguard.ai.inference.LiteRTTextEngine.generateWithImage].
 *
 * @see LiteRTImageEngine   — production adapter (LiteRT-LM Gemma 4)
 * @see NoOpVlmImageEngine  — test double that returns empty strings
 */
interface VlmImageEngine {

    /** True when the underlying model is loaded and ready for inference. */
    val isReady: Boolean

    /**
     * Generate text conditioned on [bitmap] and a text [prompt].
     *
     * @param bitmap        Input image.
     * @param prompt        User-turn text prompt.
     * @param systemMessage Optional system-prompt override.
     * @param maxTokens     Advisory cap on generated output tokens.
     * @param temperature   Sampler temperature (lower = more deterministic;
     *                      0.0 = greedy when paired with [topK] = 1).
     * @param topK          Sampler top-K (1 = greedy, fastest for structured output).
     * @param stopWhen      Optional early-stop predicate on accumulated output;
     *                      when it returns true the decode is aborted and the
     *                      partial result returned. Lets short structured outputs
     *                      (e.g. a closed `box_2d` JSON array) return without
     *                      decoding to the natural EOS.
     * @return              Generated text, or empty string on any engine error.
     */
    suspend fun generateWithImage(
        bitmap: Bitmap,
        prompt: String,
        systemMessage: String? = null,
        maxTokens: Int = 512,
        temperature: Double = 0.3,
        topK: Int = 20,
        stopWhen: ((String) -> Boolean)? = null,
    ): String
}
