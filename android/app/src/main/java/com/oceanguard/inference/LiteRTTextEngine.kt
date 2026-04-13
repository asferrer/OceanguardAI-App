package com.oceanguard.ai.inference

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [VlmTextEngine] implementation backed by LiteRT-LM for Gemma 4 E2B.
 *
 * Uses Google's on-device LLM runtime (Engine + Conversation API).
 * 1.65x faster decode than llama.cpp for the same model on Exynos 2200 CPU.
 *
 * Lifecycle: [initialize] → [warmUp] → N × [generateText] → [release].
 * Thread-safe: all generation runs on [Dispatchers.IO].
 */
@OptIn(ExperimentalApi::class)
class LiteRTTextEngine(
    private val cacheDir: String,
) : VlmTextEngine {

    companion object {
        private const val TAG = "LiteRTTextEngine"
        // Context window: prompt + output. Gemma 4 E2B supports up to 32K but
        // higher values increase KV cache RAM. 8192 gives ~6K tokens for output
        // after a typical report prompt (~2K tokens).
        private const val MAX_TOKENS = 8192
    }

    override val displayName: String = "Gemma 4 E2B (LiteRT-LM)"

    @Volatile private var engine: Engine? = null

    override fun isReady(): Boolean = engine?.isInitialized() == true

    /** Which backend was actually selected after GPU probing. Exposed for UI status. */
    @Volatile var activeBackendName: String = "CPU"
        private set

    override suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        if (isReady()) {
            Log.d(TAG, "Already initialized, skipping")
            return@withContext
        }
        Log.i(TAG, "Loading Gemma 4 E2B: $modelPath")

        // Try GPU first, fall back to CPU if unavailable or initialization fails
        val (backend, visionBackend, backendLabel) = selectBackends()

        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = visionBackend,
            maxNumTokens = MAX_TOKENS,
            cacheDir = cacheDir,
        )
        try {
            val eng = Engine(config)
            eng.initialize()
            engine = eng
            activeBackendName = backendLabel
            Log.i(TAG, "Engine initialized on $backendLabel")
        } catch (e: Exception) {
            if (backendLabel != "CPU") {
                Log.w(TAG, "GPU init failed ($backendLabel), falling back to CPU: ${e.message}")
                val cpuConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    maxNumTokens = MAX_TOKENS,
                    cacheDir = cacheDir,
                )
                val eng = Engine(cpuConfig)
                eng.initialize()
                engine = eng
                activeBackendName = "CPU"
                Log.i(TAG, "Engine initialized on CPU (GPU fallback)")
            } else {
                throw e
            }
        }
    }

    /**
     * Probes for GPU support. Returns (backend, visionBackend, label).
     * Tries GPU first; returns CPU if GPU creation throws.
     */
    private fun selectBackends(): Triple<Backend, Backend, String> {
        return try {
            val gpu = Backend.GPU()
            val gpuVision = Backend.GPU()
            Triple(gpu, gpuVision, "GPU")
        } catch (e: Exception) {
            Log.i(TAG, "GPU backend not available: ${e.message}")
            Triple(Backend.CPU(), Backend.CPU(), "CPU")
        }
    }

    override suspend fun warmUp() = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext
        try {
            // Short generation to warm up CPU caches
            val conv = eng.createConversation(ConversationConfig())
            conv.sendMessage("Hi")
            conv.close()
            Log.d(TAG, "Warm-up complete")
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up failed (non-fatal): ${e.message}")
        }
    }

    override fun release() {
        val eng = engine ?: return
        engine = null
        try {
            eng.close()
            Log.i(TAG, "Engine released")
        } catch (e: Exception) {
            Log.w(TAG, "Release error (non-fatal): ${e.message}")
        }
    }

    override suspend fun generateText(
        prompt: String,
        maxTokens: Int,
        systemMessage: String?,
        assistantPrefill: String?,
        thinkingEnabled: Boolean,
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val eng = engine
        check(eng != null && eng.isInitialized()) { "Engine not loaded. Call initialize() first." }

        val prefill = assistantPrefill?.takeIf { it.isNotEmpty() }?.let { "$it\n" } ?: ""
        val system = systemMessage ?: PromptFormatter.DEFAULT_SYSTEM_PROMPT

        val conv = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(
                    topK = 20,
                    topP = 0.95,
                    temperature = 0.5,
                ),
            )
        )

        try {
            // LiteRT-LM manages the chat template internally, so `prefill` cannot be injected
            // as a raw assistant-turn prefix (as llama.cpp does). Instead we instruct the
            // model to start with the heading and let it generate the full output, then
            // post-process to deduplicate if needed.
            val accumulated = StringBuilder()
            var lastPartialMs = 0L

            val fullPrompt = prompt
            Log.d(TAG, "Prompt length: ${fullPrompt.length} chars (~${fullPrompt.length / 3} tokens)")

            // Callback-based streaming — onMessage receives token deltas
            suspendCancellableCoroutine { continuation ->
                conv.sendMessageAsync(fullPrompt, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val token = message.contents.toString()
                        accumulated.append(token)
                        val now = System.currentTimeMillis()
                        if (now - lastPartialMs >= 500L) {
                            onPartialResult(accumulated.toString())
                            lastPartialMs = now
                        }
                    }

                    override fun onDone() {
                        continuation.resume(Unit)
                    }

                    override fun onError(error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                })
            }

            val result = finalizeOutput(accumulated.toString(), prefill.trim())
            onPartialResult(result)
            Log.d(TAG, "Generation complete: ${result.length} chars")
            result
        } finally {
            conv.close()
        }
    }

    /**
     * Generate text conditioned on an image and a text prompt.
     *
     * Converts the [bitmap] to JPEG bytes and sends it alongside the [prompt]
     * as a multimodal [Contents] message. Requires [EngineConfig.visionBackend]
     * to be set during [initialize].
     *
     * @param bitmap          Input image (any size -- LiteRT-LM handles resizing).
     * @param prompt          Text prompt describing the task.
     * @param maxTokens       Hard cap on generated tokens (default 512 for detection).
     * @param systemMessage   Optional system prompt override.
     * @param onPartialResult Streaming callback with accumulated text.
     * @return Complete generated text.
     */
    suspend fun generateWithImage(
        bitmap: Bitmap,
        prompt: String,
        maxTokens: Int = 512,
        systemMessage: String? = null,
        onPartialResult: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val eng = engine
        check(eng != null && eng.isInitialized()) { "Engine not loaded. Call initialize() first." }

        val imageBytes = bitmapToJpegBytes(bitmap)
        Log.d(TAG, "Image: ${bitmap.width}x${bitmap.height}, ${imageBytes.size / 1024} KB JPEG")

        val system = systemMessage ?: "You are a precise visual analysis assistant."
        val conv = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(
                    topK = 20,
                    topP = 0.95,
                    temperature = 0.3,
                ),
            )
        )

        try {
            val contents = Contents.of(
                Content.ImageBytes(imageBytes),
                Content.Text(prompt),
            )

            val accumulated = StringBuilder()
            var lastPartialMs = 0L

            suspendCancellableCoroutine { continuation ->
                conv.sendMessageAsync(contents, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val token = message.contents.toString()
                        accumulated.append(token)
                        val now = System.currentTimeMillis()
                        if (onPartialResult != null && now - lastPartialMs >= 200L) {
                            onPartialResult(accumulated.toString())
                            lastPartialMs = now
                        }
                    }

                    override fun onDone() {
                        continuation.resume(Unit)
                    }

                    override fun onError(error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                })
            }

            val result = accumulated.toString()
            onPartialResult?.invoke(result)
            Log.d(TAG, "Vision generation complete: ${result.length} chars")
            result
        } finally {
            conv.close()
        }
    }

    private fun bitmapToJpegBytes(
        bitmap: Bitmap,
        quality: Int = 90,
    ): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Post-processes the model's raw output to guarantee the expected heading appears
     * exactly once at the start.
     *  - If the output starts with a duplicated heading ("## X\n## X"), removes the first copy.
     *  - If the output does not start with the heading at all, prepends it (safety fallback).
     *  - Otherwise returns the output unchanged.
     */
    private fun finalizeOutput(raw: String, heading: String): String {
        if (heading.isEmpty()) return raw
        val trimmed = raw.trimStart()
        val doubleNewline = "$heading\n$heading"
        val doubleSpace = "$heading $heading"
        return when {
            trimmed.startsWith(doubleNewline) || trimmed.startsWith(doubleSpace) ->
                trimmed.removePrefix(heading).trimStart()
            !trimmed.startsWith(heading) -> "$heading\n$trimmed"
            else -> trimmed
        }
    }
}
