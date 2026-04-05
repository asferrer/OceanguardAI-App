package com.oceanguard.ai.inference

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Supported text-model tiers for report generation.
 *
 * Each entry bundles all model-specific config: filename, context size, sampling params,
 * and chat-template formatter. Adding a new model only requires a new enum entry —
 * no changes needed in [LlamaTextEngine] or [ReportGenerator].
 *
 * All Qwen3.5 Q4_K_M GGUF (Apache 2.0, llama.cpp compatible).
 * The Qwen3.5 family ships in 0.8B / 2B / 4B / 9B — no 1.5B or 3B variants.
 *
 * - [FAST]:     Qwen3.5-0.8B — ~533 MB, ~20–30 tok/s on Exynos 2200 big cores. No thinking.
 * - [BALANCED]: Qwen3.5-2B   — ~1.1 GB, ~12–18 tok/s. Thinking mode enabled (temperature=1.0).
 * - [QUALITY]:  Qwen3.5-4B   — ~2.74 GB, ~6–10 tok/s. Thinking mode enabled.
 */
enum class TextModelTier(
    val filename: String,
    val sizeLabel: String,
    val displayName: String,
    /** llama_context window size. Larger models with thinking need more room for reasoning tokens. */
    val nCtx: Int,
    /** Sampling temperature. Qwen3.5 docs recommend 1.0 for thinking mode, 0.3 for non-thinking. */
    val temperature: Float,
    val topK: Int,
    /** Chat-template formatter. Swap this to support a different model family (e.g. Llama-3.2). */
    val formatter: PromptFormatter,
) {
    FAST(
        filename     = "Qwen3.5-0.8B-Q4_K_M.gguf",
        sizeLabel    = "~533 MB",
        displayName  = "Fast (0.8B)",
        nCtx         = 8192,   // Raised from 4096 — gives ~6400 output tokens after prompt overhead
        temperature  = 0.3f,
        topK         = 20,
        formatter    = QwenPromptFormatter,
    ),
    /** 2B model — thinking mode ON for better report coherence. */
    BALANCED(
        filename     = "Qwen3.5-2B-Q4_K_M.gguf",
        sizeLabel    = "~1.1 GB",
        displayName  = "Balanced (2B)",
        nCtx         = 8192,   // Thinking generates 500-2000 extra reasoning tokens
        temperature  = 1.0f,   // Recommended by Qwen3.5 docs for thinking mode
        topK         = 20,
        formatter    = QwenPromptFormatter,
    ),
    QUALITY(
        filename     = "Qwen3.5-4B-Q4_K_M.gguf",
        sizeLabel    = "~2.74 GB",
        displayName  = "Quality (4B)",
        nCtx         = 8192,
        temperature  = 1.0f,
        topK         = 20,
        formatter    = QwenPromptFormatter,
    );

    companion object {
        fun fromKey(key: String): TextModelTier =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: FAST
    }
}

/**
 * [VlmTextEngine] implementation backed by llama.cpp (via [LlamaCppBridge]).
 *
 * Supports two quality tiers selectable at construction time:
 * - [TextModelTier.FAST]    — Qwen3.5-1.5B Q4_K_M (~1.0 GB, ~15–20 tok/s)
 * - [TextModelTier.QUALITY] — Qwen3.5-3B Q4_K_M   (~1.9 GB, ~8–12 tok/s)
 *
 * Lifecycle mirrors [RTDETRInference]: deferred load, explicit release,
 * 2-minute auto-release managed by [OceanGuardApp].
 */
class LlamaTextEngine(val tier: TextModelTier = TextModelTier.FAST) : VlmTextEngine {

    companion object {
        private const val TAG = "LlamaTextEngine"

        // Hardware-specific threading (Exynos 2200: 1×X2 + 3×A710 big cores)
        private const val N_THREADS       = 4
        private const val N_THREADS_BATCH = 6 // big + medium cores for faster prefill

        /** Legacy 0.8B model kept for users who already downloaded it. */
        const val MODEL_FILENAME_LEGACY = "Qwen3.5-0.8B-Q4_K_M.gguf"
    }

    override val displayName: String = "Qwen3.5-${tier.displayName} Q4_K_M"

    private val initMutex = Mutex()

    // Read-write lock that separates initialization/release (write) from generation (read).
    // Multiple concurrent reads (generations) are allowed; a write (release) is exclusive
    // and waits for all active generations to finish before freeing the native handle.
    // release() uses tryLock so it is non-blocking: if a generation is in progress it
    // skips the current cycle and the 2-minute idle timer will retry.
    private val releaseLock = ReentrantReadWriteLock()

    @Volatile
    private var contextHandle: Long = 0L

    override fun isReady(): Boolean = contextHandle != 0L

    override suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (contextHandle != 0L) {
                Log.d(TAG, "Already initialized (${tier.displayName}), skipping")
                return@withLock
            }
            Log.i(TAG, "Loading ${tier.displayName}: $modelPath")
            val handle = LlamaCppBridge.nativeInitTextModel(modelPath, tier.nCtx, N_THREADS, N_THREADS_BATCH)
            if (handle == 0L) {
                throw IllegalStateException("Failed to load ${tier.displayName} from: $modelPath")
            }
            contextHandle = handle
            Log.i(TAG, "${tier.displayName} loaded (handle=$handle)")
        }
    }

    override suspend fun warmUp() = withContext(Dispatchers.IO) {
        val handle = contextHandle
        if (handle == 0L) return@withContext
        try {
            LlamaCppBridge.nativeWarmUp(handle)
            Log.d(TAG, "Warm-up complete (${tier.displayName})")
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up failed (non-fatal): ${e.message}")
        }
    }

    override fun release() {
        // tryLock: if a generation is actively running, skip this release cycle.
        // The 2-minute idle timer in OceanGuardApp will retry. This avoids freeing
        // the native handle while nativeGenerateText() is still executing on it.
        if (!releaseLock.writeLock().tryLock()) {
            Log.w(TAG, "${tier.displayName} generation in progress, deferring release")
            return
        }
        try {
            val handle = contextHandle
            if (handle == 0L) return
            contextHandle = 0L
            LlamaCppBridge.nativeReleaseModel(handle)
            Log.i(TAG, "${tier.displayName} released")
        } finally {
            releaseLock.writeLock().unlock()
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
        // Read lock: allows concurrent generations, but blocks release() from running
        // until ALL active generate calls have finished. This prevents use-after-free
        // on the native context handle.
        releaseLock.readLock().lock()
        try {
        val handle = contextHandle
        check(handle != 0L) { "Model not loaded. Call initialize() first." }

        val prefill = assistantPrefill?.takeIf { it.isNotEmpty() }?.let { "$it\n" } ?: ""
        val formatted = tier.formatter.format(
            userMessage      = prompt,
            systemMessage    = systemMessage ?: QwenPromptFormatter.DEFAULT_SYSTEM_PROMPT,
            assistantPrefill = assistantPrefill ?: "",
            thinkingEnabled  = thinkingEnabled,
        )
        Log.d(TAG, "Generating [${tier.displayName}] maxTokens=$maxTokens thinking=$thinkingEnabled prefill=${prefill.length}b")

        // Start with prefill so callers see the heading immediately on first token.
        // Native generates ONLY tokens after the formatted prompt (prefill is input, not output).
        // Throttle toString() to max 5/s to avoid O(n) recompositions.
        val accumulated = StringBuilder(prefill)
        var lastPartialMs = 0L
        val callback = object : TokenStreamCallback {
            override fun onToken(piece: String) {
                accumulated.append(piece)
                val now = System.currentTimeMillis()
                if (now - lastPartialMs >= 200L) {
                    onPartialResult(tier.formatter.sanitizePartial(accumulated.toString()))
                    lastPartialMs = now
                }
            }
        }

        val raw = LlamaCppBridge.nativeGenerateText(
            handle, formatted, maxTokens, tier.temperature, tier.topK, callback
        )
        val result = prefill + tier.formatter.sanitizeOutput(raw)
        Log.d(TAG, "Generation complete [${tier.displayName}] ${result.length} chars")
        result
        } finally {
            releaseLock.readLock().unlock()
        }
    }
}
