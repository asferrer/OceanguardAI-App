package com.oceanguard.ai.inference

/**
 * Callback invoked from native code during token streaming.
 * Called on the inference thread — implementations must be thread-safe.
 */
interface TokenStreamCallback {
    /**
     * Called after each token is generated.
     * @param piece The new text piece for this token only (not the full accumulated text).
     *   Callers that need accumulated text should append pieces in a StringBuilder.
     */
    fun onToken(piece: String)
}

/**
 * Kotlin JNI declarations for the llama.cpp native bridge (libllama_oceanguard.so).
 *
 * All [external] functions are thin wrappers around llama.cpp C APIs. Context handles
 * are opaque [Long] values (C pointers cast to jlong). A value of 0L means "not loaded".
 *
 * Thread-safety: callers are responsible for serializing calls per context handle.
 */
object LlamaCppBridge {

    init {
        try {
            System.loadLibrary("llama_oceanguard")
        } catch (e: UnsatisfiedLinkError) {
            // Wrap with a descriptive message so crash reports are actionable.
            // Possible causes: missing ABI in APK, corrupted install, emulator without ARM libs.
            throw RuntimeException(
                "Failed to load native library 'llama_oceanguard'. " +
                "Ensure libllama_oceanguard.so is packaged for this device's ABI.",
                e,
            )
        }
    }

    // ---- Text model lifecycle ----

    /**
     * Load a GGUF model file and create a llama_context.
     * @param modelPath     Absolute path to .gguf file.
     * @param nCtx          Context window size (e.g. 4096).
     * @param nThreads      CPU threads for token decoding (e.g. 4 big cores).
     * @param nThreadsBatch CPU threads for prompt prefill batch (e.g. 6 -- more parallelism OK).
     * @param nGpuLayers    Number of layers to offload to GPU via Vulkan (0 = CPU-only, 99 = all).
     * @return Opaque context handle, or 0L on failure.
     */
    @JvmStatic
    external fun nativeInitTextModel(modelPath: String, nCtx: Int, nThreads: Int, nThreadsBatch: Int, nGpuLayers: Int = 0): Long

    /**
     * Generate text from a prompt with token-level streaming.
     * Calls [callback.onToken] after each decoded token.
     * @return Complete generated text, or empty string on error.
     */
    @JvmStatic
    external fun nativeGenerateText(
        contextHandle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        callback: TokenStreamCallback,
    ): String

    /** Run a short inference pass to warm up CPU caches. Non-fatal. */
    @JvmStatic
    external fun nativeWarmUp(contextHandle: Long)

    /** Free llama_context and llama_model. After this, contextHandle is invalid. */
    @JvmStatic
    external fun nativeReleaseModel(contextHandle: Long)

    // ---- Vision (CLIP / llava) lifecycle ----

    /**
     * Returns true when the native library was compiled with vision support.
     * Currently false (stub build) — mtmd API migration is deferred.
     * Callers should check this before attempting to load a vision model.
     */
    @JvmStatic
    external fun nativeIsVisionSupported(): Boolean

    /**
     * Load a CLIP vision projector (mmproj) GGUF file.
     * @param llmContextHandle Handle from [nativeInitTextModel] (needed for mtmd_init_from_file).
     * @param mmprojPath       Absolute path to mmproj .gguf file.
     * @param nThreads         CPU threads for image encoding.
     * @return Opaque context handle, or 0L on failure (or when vision is not compiled).
     */
    @JvmStatic
    external fun nativeInitClipModel(llmContextHandle: Long, mmprojPath: String, nThreads: Int): Long

    /**
     * Generate text conditioned on an image + text prompt.
     * @param llmContextHandle  Handle from [nativeInitTextModel].
     * @param clipContextHandle Handle from [nativeInitClipModel].
     * @param bitmapPixels      ARGB_8888 pixel array from Bitmap.getPixels().
     * @param bitmapWidth       Image width in pixels.
     * @param bitmapHeight      Image height in pixels.
     * @return Complete generated text, or empty string on error.
     */
    @JvmStatic
    external fun nativeGenerateWithImage(
        llmContextHandle: Long,
        clipContextHandle: Long,
        bitmapPixels: IntArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: TokenStreamCallback,
    ): String

    /** Free clip_ctx. After this, clipContextHandle is invalid. */
    @JvmStatic
    external fun nativeReleaseClipModel(clipContextHandle: Long)
}
