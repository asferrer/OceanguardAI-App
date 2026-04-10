package com.oceanguard.ai.inference

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.benchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Temporary benchmark spike for LiteRT-LM 0.10.0 + Gemma 4 E2B on Exynos 2200.
 * Two modes:
 * 1. **Quick benchmark** — uses built-in [benchmark] function (prefill + decode tok/s)
 * 2. **Full benchmark** — loads Engine, runs text generation, captures [BenchmarkInfo]
 *
 * TODO: Remove after Go/No-Go decision is made.
 */
@OptIn(ExperimentalApi::class)
object LiteRTBenchmark {

    private const val TAG = "LiteRTBenchmark"
    private const val MAX_TOKENS = 4096

    data class Result(
        val label: String,
        val loadMs: Long,
        val benchmark: BenchmarkInfo,
        val outputLength: Int,
    ) {
        override fun toString(): String =
            "[$label] load=${loadMs}ms | " +
            "TTFT=${String.format("%.2f", benchmark.timeToFirstTokenInSecond)}s | " +
            "prefill=${String.format("%.1f", benchmark.lastPrefillTokensPerSecond)} tok/s " +
            "(${benchmark.lastPrefillTokenCount} tok) | " +
            "decode=${String.format("%.1f", benchmark.lastDecodeTokensPerSecond)} tok/s " +
            "(${benchmark.lastDecodeTokenCount} tok) | " +
            "output=${outputLength} chars"
    }

    /**
     * Run text + optional vision benchmarks on Gemma 4 E2B.
     * @param modelPath absolute path to gemma-4-E2B-it.litertlm
     * @param cacheDir writable directory for compiled-model cache
     * @param imagePath optional image path for vision benchmark
     */
    suspend fun runAll(
        modelPath: String,
        cacheDir: String,
        imagePath: String? = null,
    ): List<Result> = withContext(Dispatchers.IO) {
        Log.w(TAG, "╔══════════════════════════════════════════╗")
        Log.w(TAG, "║   LiteRT-LM Benchmark — Gemma 4 E2B     ║")
        Log.w(TAG, "╚══════════════════════════════════════════╝")
        Log.w(TAG, "Model: $modelPath")

        // Enable benchmark metrics collection
        ExperimentalFlags.enableBenchmark = true

        val results = mutableListOf<Result>()

        // ── Quick benchmark (built-in, no conversation needed) ──
        Log.w(TAG, "── Quick benchmark (built-in) START ──")
        val quickInfo = benchmark(
            modelPath = modelPath,
            backend = Backend.CPU(),
            prefillTokens = 512,
            decodeTokens = 128,
            cacheDir = cacheDir,
        )
        Log.w(TAG, "Quick: prefill=${String.format("%.1f", quickInfo.lastPrefillTokensPerSecond)} tok/s, " +
            "decode=${String.format("%.1f", quickInfo.lastDecodeTokensPerSecond)} tok/s, " +
            "init=${String.format("%.2f", quickInfo.initTimeInSecond)}s")
        results += Result(
            label = "QUICK",
            loadMs = (quickInfo.initTimeInSecond * 1000).toLong(),
            benchmark = quickInfo,
            outputLength = 0,
        )

        // ── Full text benchmark with real generation ──
        val t0 = System.nanoTime()
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                cacheDir = cacheDir,
            )
        )
        engine.initialize()
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        Log.w(TAG, "Engine initialized in ${loadMs}ms")

        results += runTextBenchmark(engine, loadMs)

        if (imagePath != null) {
            results += runVisionBenchmark(engine, loadMs, imagePath)
        }

        engine.close()

        Log.w(TAG, "╔══════════════════════════════════════════╗")
        Log.w(TAG, "║              RESULTS SUMMARY             ║")
        Log.w(TAG, "╚══════════════════════════════════════════╝")
        results.forEach { Log.w(TAG, it.toString()) }
        results
    }

    private fun runTextBenchmark(engine: Engine, loadMs: Long): Result {
        Log.w(TAG, "── Text benchmark START ──")
        val conv = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(PromptFormatter.DEFAULT_SYSTEM_PROMPT),
                samplerConfig = SAMPLER,
            )
        )

        val response = conv.sendMessage(TEXT_PROMPT)
        val info = conv.getBenchmarkInfo()
        val text = response.contents.toString()
        conv.close()

        val result = Result("TEXT", loadMs, info, text.length)
        Log.w(TAG, result.toString())
        return result
    }

    private fun runVisionBenchmark(
        engine: Engine,
        loadMs: Long,
        imagePath: String,
    ): Result {
        Log.w(TAG, "── Vision benchmark START ──")
        val conv = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(PromptFormatter.DEFAULT_SYSTEM_PROMPT),
                samplerConfig = SAMPLER,
            )
        )

        val response = conv.sendMessage(
            Contents.of(
                Content.ImageFile(imagePath),
                Content.Text(VISION_PROMPT),
            )
        )
        val info = conv.getBenchmarkInfo()
        val text = response.contents.toString()
        conv.close()

        val result = Result("VISION", loadMs, info, text.length)
        Log.w(TAG, result.toString())
        return result
    }

    private val SAMPLER = SamplerConfig(
        topK = 20,
        topP = 0.95,
        temperature = 0.3,
    )

    private const val TEXT_PROMPT =
        "Generate a 200-word environmental assessment report about " +
        "plastic debris found on a Mediterranean beach. Include " +
        "debris type counts, material breakdown, and recommendations."

    private const val VISION_PROMPT =
        "Analyze this image of a coastal area. Identify and count any " +
        "marine debris visible. Classify each item by type and material. " +
        "Provide a brief environmental impact assessment."
}
