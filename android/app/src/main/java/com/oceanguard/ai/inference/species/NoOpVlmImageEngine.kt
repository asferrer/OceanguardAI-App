package com.oceanguard.ai.inference.species

import android.graphics.Bitmap

/**
 * Test double for [VlmImageEngine] that returns a configurable response
 * without invoking any native model.
 *
 * @param response       String returned by every [generateWithImage] call.
 * @param readyOverride  Overrides [isReady] (default true).
 */
class NoOpVlmImageEngine(
    private val response: String = "",
    private val readyOverride: Boolean = true,
) : VlmImageEngine {

    override val isReady: Boolean get() = readyOverride

    @Suppress("UnusedParameter")
    override suspend fun generateWithImage(
        bitmap: Bitmap,
        prompt: String,
        systemMessage: String?,
        maxTokens: Int,
        temperature: Double,
        topK: Int,
        stopWhen: ((String) -> Boolean)?,
    ): String = response
}
