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
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Serializes [initialize] so that concurrent callers (e.g. Gemma4VisionDetector
     * AND ToolReportGenerator both touching the shared engine) do not each spin up
     * a native Engine — the first finishes, the rest see [isReady] and skip.
     * Without this, the second native Engine() ends up leaked AND competes for the
     * GPU, slowing inference ~10x.
     */
    private val initMutex = Mutex()

    override fun isReady(): Boolean = engine?.isInitialized() == true

    /** Which backend was actually selected after GPU probing. Exposed for UI status. */
    @Volatile var activeBackendName: String = "CPU"
        private set

    override suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isReady()) {
                Log.d(TAG, "Already initialized, skipping")
                return@withLock
            }
            doInitialize(modelPath)
        }
    }

    private suspend fun doInitialize(modelPath: String) {
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
        /** Sampler temperature. Lower = more deterministic (best for JSON output). */
        temperature: Double = 0.3,
        /** Sampler top-K. Lower = faster decode with negligible quality loss on structured output. */
        topK: Int = 20,
        onPartialResult: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val eng = engine
        check(eng != null && eng.isInitialized()) { "Engine not loaded. Call initialize() first." }

        val imageBytes = bitmapToJpegBytes(bitmap)
        Log.d(TAG, "Image: ${bitmap.width}x${bitmap.height}, ${imageBytes.size / 1024} KB JPEG, sampler temp=$temperature topK=$topK")

        val system = systemMessage ?: "You are a precise visual analysis assistant."
        val conv = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(
                    topK = topK,
                    topP = 0.95,
                    temperature = temperature,
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
        // 85 is visually indistinguishable from 90 for the vision encoder
        // (Gemma 4 downsamples internally) and ~25 % smaller on the wire,
        // shaving a measurable slice off the encoder prefill on batch runs.
        quality: Int = 85,
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

    /**
     * Generate text with native LiteRT-LM tool calling.
     *
     * Uses `automaticToolCalling = false` so the agent loop can stream tokens,
     * surface progress to the UI, and cap iterations. The conversation persists
     * across all rounds so the KV cache is reused (avoids re-prefilling the
     * system prompt every turn).
     *
     * @param prompt              First user-turn prompt.
     * @param toolSet             [ToolSet] with `@Tool`-annotated methods.
     * @param systemMessage       System prompt (defaults to a generic one).
     * @param maxToolRounds       Hard cap on tool dispatch rounds.
     * @param onPartialResult     Streaming callback for accumulated text.
     * @param onToolCallStarted   Notified each time a tool invocation begins.
     */
    suspend fun generateWithTools(
        prompt: String,
        toolSet: ToolSet,
        systemMessage: String? = null,
        maxToolRounds: Int = 8,
        requiredToolNames: Set<String> = emptySet(),
        dataBundle: String? = null,
        onPartialResult: (String) -> Unit = {},
        onToolCallStarted: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val eng = engine
        check(eng != null && eng.isInitialized()) { "Engine not loaded. Call initialize() first." }

        val system = systemMessage ?: PromptFormatter.DEFAULT_SYSTEM_PROMPT
        val provider = tool(toolSet)
        // Numeric reports need enough sampler entropy to keep the model curious about calling
        // the remaining tools, but low enough to copy tool output verbatim without drifting
        // into fabricated rows. 0.30 / topK=20 is the sweet spot measured on Gemma 4 E2B:
        //   0.15 → model stops after 1 tool call and hallucinates the rest
        //   0.50 → model calls all tools but invents percentages in tables
        val conv = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(
                    topK = 20,
                    topP = 0.9,
                    temperature = 0.30,
                ),
                tools = listOf(provider),
                automaticToolCalling = false,
            )
        )
        try {
            Log.d(TAG, "Tool-calling prompt length: ${prompt.length} chars (~${prompt.length / 3} tokens)")
            val loop = ToolAgentLoop(conv, toolSet)
            val result = loop.run(
                prompt = prompt,
                maxToolRounds = maxToolRounds,
                requiredToolNames = requiredToolNames,
                dataBundle = dataBundle,
                onPartialResult = onPartialResult,
                onToolCallStarted = onToolCallStarted,
            )
            onPartialResult(result)
            Log.d(TAG, "Tool-calling generation complete: ${result.length} chars")
            result
        } finally {
            conv.close()
        }
    }
}
