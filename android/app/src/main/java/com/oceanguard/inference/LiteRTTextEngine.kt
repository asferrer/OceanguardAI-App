package com.oceanguard.ai.inference

import android.util.Log
import com.google.ai.edge.litertlm.Backend
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

    override suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        if (isReady()) {
            Log.d(TAG, "Already initialized, skipping")
            return@withContext
        }
        Log.i(TAG, "Loading Gemma 4 E2B: $modelPath")
        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
            visionBackend = Backend.CPU(),
            maxNumTokens = MAX_TOKENS,
            cacheDir = cacheDir,
        )
        val eng = Engine(config)
        eng.initialize()
        engine = eng
        Log.i(TAG, "Engine initialized")
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

            val fullPrompt = if (prefill.isNotEmpty()) {
                "$prompt\n\nIMPORTANT: Your response MUST start with exactly this heading " +
                "and nothing before it: ${prefill.trim()}\n" +
                "Do not add any preamble, acknowledgment, or markdown code fences. " +
                "Do not repeat the heading.\n" +
                "Write a COMPREHENSIVE report with ALL sections. Minimum 2000 words."
            } else {
                "$prompt\nWrite a COMPREHENSIVE report with ALL sections. Minimum 2000 words."
            }
            Log.d(TAG, "Prompt length: ${fullPrompt.length} chars (~${fullPrompt.length / 3} tokens)")

            // Callback-based streaming — onMessage receives token deltas
            suspendCancellableCoroutine { continuation ->
                conv.sendMessageAsync(fullPrompt, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val token = message.contents.toString()
                        accumulated.append(token)
                        val now = System.currentTimeMillis()
                        if (now - lastPartialMs >= 200L) {
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
