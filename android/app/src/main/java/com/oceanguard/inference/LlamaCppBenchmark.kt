package com.oceanguard.ai.inference

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Benchmark spike for llama.cpp on Exynos 2200.
 * Measures load, warm-up, prefill TTFT, decode tok/s via token callback timestamps.
 * Runs the same prompt as [LiteRTBenchmark] for direct comparison.
 *
 * TODO: Remove after Go/No-Go decision is made.
 */
object LlamaCppBenchmark {

    private const val TAG = "LlamaCppBenchmark"

    data class Result(
        val label: String,
        val tier: String,
        val loadMs: Long,
        val warmUpMs: Long,
        val ttftMs: Long,
        val prefillTokPerSec: Double,
        val decodeTokPerSec: Double,
        val totalTokens: Int,
        val outputLength: Int,
    ) {
        override fun toString(): String =
            "[$label] $tier | " +
            "load=${loadMs}ms | warmup=${warmUpMs}ms | " +
            "TTFT=${String.format("%.2f", ttftMs / 1000.0)}s | " +
            "prefill=${String.format("%.1f", prefillTokPerSec)} tok/s | " +
            "decode=${String.format("%.1f", decodeTokPerSec)} tok/s " +
            "($totalTokens tok) | " +
            "output=$outputLength chars"
    }

    /**
     * Benchmark a single [TextModelTier] with llama.cpp.
     * @param modelPath absolute path to the .gguf file
     * @param tier model tier config (nCtx, temperature, topK, formatter)
     */
    suspend fun run(modelPath: String, tier: TextModelTier): Result =
        withContext(Dispatchers.IO) {
            Log.w(TAG, "╔══════════════════════════════════════════╗")
            Log.w(TAG, "║   llama.cpp Benchmark — ${tier.displayName}")
            Log.w(TAG, "╚══════════════════════════════════════════╝")
            Log.w(TAG, "Model: $modelPath")

            // ── Load ──
            val t0 = System.nanoTime()
            val handle = LlamaCppBridge.nativeInitTextModel(
                modelPath, tier.nCtx, N_THREADS, N_THREADS_BATCH
            )
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            check(handle != 0L) { "Failed to load model: $modelPath" }
            Log.w(TAG, "Model loaded in ${loadMs}ms (nCtx=${tier.nCtx})")

            // ── Warm-up ──
            val t1 = System.nanoTime()
            try { LlamaCppBridge.nativeWarmUp(handle) } catch (_: Exception) {}
            val warmUpMs = (System.nanoTime() - t1) / 1_000_000
            Log.w(TAG, "Warm-up in ${warmUpMs}ms")

            // ── Format prompt ──
            val formatted = tier.formatter.format(
                userMessage = TEXT_PROMPT,
                systemMessage = PromptFormatter.DEFAULT_SYSTEM_PROMPT,
                assistantPrefill = "",
                thinkingEnabled = false,
            )
            val estimatedPromptTokens = formatted.length / 3
            val maxTokens = (tier.nCtx - estimatedPromptTokens - 256)
                .coerceAtLeast(256)
                .coerceAtMost(4096)

            // ── Generate with timing ──
            var firstTokenNs = 0L
            var tokenCount = 0
            val generateStartNs = System.nanoTime()

            val callback = object : TokenStreamCallback {
                override fun onToken(piece: String) {
                    tokenCount++
                    if (tokenCount == 1) {
                        firstTokenNs = System.nanoTime()
                    }
                }
            }

            val raw = LlamaCppBridge.nativeGenerateText(
                handle, formatted, maxTokens, tier.temperature, tier.topK, callback
            )
            val generateEndNs = System.nanoTime()

            // ── Release ──
            LlamaCppBridge.nativeReleaseModel(handle)

            // ── Compute metrics ──
            val ttftMs = if (firstTokenNs > 0)
                (firstTokenNs - generateStartNs) / 1_000_000
            else 0L

            val decodeNs = if (firstTokenNs > 0 && tokenCount > 1)
                generateEndNs - firstTokenNs
            else generateEndNs - generateStartNs

            val decodeTokens = (tokenCount - 1).coerceAtLeast(1)
            val decodeTokPerSec = decodeTokens * 1_000_000_000.0 / decodeNs

            // Prefill: estimated prompt tokens processed in TTFT time
            val prefillTokPerSec = if (ttftMs > 0)
                estimatedPromptTokens * 1000.0 / ttftMs
            else 0.0

            val sanitized = tier.formatter.sanitizeOutput(raw)

            val result = Result(
                label = "LLAMA.CPP",
                tier = tier.displayName,
                loadMs = loadMs,
                warmUpMs = warmUpMs,
                ttftMs = ttftMs,
                prefillTokPerSec = prefillTokPerSec,
                decodeTokPerSec = decodeTokPerSec,
                totalTokens = tokenCount,
                outputLength = sanitized.length,
            )
            Log.w(TAG, result.toString())
            Log.w(TAG, "╔══════════════════════════════════════════╗")
            Log.w(TAG, "║              RESULT                      ║")
            Log.w(TAG, "╚══════════════════════════════════════════╝")
            Log.w(TAG, result.toString())
            result
        }

    // Match LlamaTextEngine threading for Exynos 2200
    private const val N_THREADS = 4
    private const val N_THREADS_BATCH = 6

    private const val TEXT_PROMPT =
        "Generate a 200-word environmental assessment report about " +
        "plastic debris found on a Mediterranean beach. Include " +
        "debris type counts, material breakdown, and recommendations."
}
