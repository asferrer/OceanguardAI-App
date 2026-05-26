package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import android.util.Log
import com.oceanguard.ai.inference.LiteRTTextEngine

/**
 * Production adapter that wraps [LiteRTTextEngine.generateWithImage] behind
 * the [VlmImageEngine] interface.
 *
 * Constructed once in `OceanGuardApp` and shared between [OrganismLocator]
 * and [SpeciesDescriber]. The underlying [LiteRTTextEngine] must already be
 * initialised before any call to [generateWithImage].
 *
 * Any exception thrown by the engine (e.g. "Engine not loaded") is swallowed
 * and replaced with an empty string so that callers can degrade gracefully
 * without crashing the BioDex pipeline.
 */
class LiteRTImageEngine(
    private val engine: LiteRTTextEngine,
) : VlmImageEngine {

    override val isReady: Boolean
        get() = engine.isReady()

    override suspend fun generateWithImage(
        bitmap: Bitmap,
        prompt: String,
        systemMessage: String?,
        maxTokens: Int,
        temperature: Double,
    ): String = try {
        engine.generateWithImage(
            bitmap        = bitmap,
            prompt        = prompt,
            maxTokens     = maxTokens,
            systemMessage = systemMessage,
            temperature   = temperature,
        )
    } catch (e: Exception) {
        Log.w("LiteRTImageEngine", "generateWithImage failed: ${e.message}")
        ""
    }
}
