package com.oceanguard.ai.inference

import android.graphics.Bitmap

/**
 * Common contract for on-device text generation engines.
 * Mirrors [ObjectDetector]: one interface, swappable implementations.
 *
 * Thread-safety: all suspend functions dispatch to Dispatchers.IO internally.
 */
interface VlmTextEngine {
    /** Human-readable name shown in logs and UI, e.g. "Qwen3.5-0.8B Q4_K_M". */
    val displayName: String

    /** Whether the engine is loaded and ready for inference. */
    fun isReady(): Boolean

    /**
     * Load model weights into memory. Idempotent — safe to call repeatedly.
     * @param modelPath Absolute path to the GGUF file.
     */
    suspend fun initialize(modelPath: String)

    /**
     * Run a short inference to warm up CPU caches and NEON JIT paths.
     * Non-fatal: failures are logged but not propagated.
     */
    suspend fun warmUp()

    /** Release all native resources. [isReady] returns false after this call. */
    fun release()

    /**
     * Generate text with streaming output.
     *
     * @param prompt           Full user message (report prompt + JSON data).
     * @param maxTokens        Hard cap on generated tokens.
     * @param systemMessage    Optional system prompt override — use to inject language directives.
     *                         Null falls back to the engine's default English system prompt.
     * @param assistantPrefill Text injected at the start of the assistant turn after the <think>
     *                         block. Forces the model to start in the target language. The native
     *                         layer generates AFTER this prefix; callers receive it prepended to
     *                         all partial results and the final return value.
     * @param onPartialResult  Called with accumulated text (prefill + generated so far) as each
     *                         token arrives.
     * @return Complete generated text (prefill + all generated tokens).
     */
    suspend fun generateText(
        prompt: String,
        maxTokens: Int = 1024,
        systemMessage: String? = null,
        assistantPrefill: String? = null,
        thinkingEnabled: Boolean = true,
        onPartialResult: (String) -> Unit = {},
    ): String
}

/**
 * Extends [VlmTextEngine] with vision (multimodal) capability.
 * Requires a CLIP projection model (mmproj) alongside the LLM weights.
 */
interface VlmVisionEngine : VlmTextEngine {
    /**
     * Load both LLM weights and the CLIP projection model.
     * @param modelPath  Absolute path to the GGUF (language model).
     * @param mmprojPath Absolute path to the mmproj GGUF (vision projector).
     */
    suspend fun initialize(modelPath: String, mmprojPath: String)

    /** Throws — vision engine requires both model paths. */
    override suspend fun initialize(modelPath: String): Unit =
        throw UnsupportedOperationException(
            "VlmVisionEngine requires mmprojPath. Use initialize(modelPath, mmprojPath)."
        )

    /**
     * Generate text conditioned on an image and a text prompt.
     *
     * @param bitmap           Input image (resized to model patch size internally).
     * @param prompt           Text prompt describing the task.
     * @param maxTokens        Hard cap on generated tokens.
     * @param systemMessage    Optional system prompt override for language directives.
     * @param assistantPrefill Text injected at the start of the assistant turn (after
     *                         the <think> block) to force the target language. Same
     *                         semantics as in [VlmTextEngine.generateText].
     * @param onPartialResult  Streaming callback, called with accumulated text.
     * @return Complete generated text (prefill + all generated tokens).
     */
    suspend fun generateWithImage(
        bitmap: Bitmap,
        prompt: String,
        maxTokens: Int = 512,
        systemMessage: String? = null,
        assistantPrefill: String? = null,
        onPartialResult: (String) -> Unit = {},
    ): String
}
