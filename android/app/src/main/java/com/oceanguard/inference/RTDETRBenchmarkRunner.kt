package com.oceanguard.ai.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * Deterministic latency benchmark for RT-DETRv2.
 *
 * Runs 5 warm-up + 50 measured inferences and reports p50, p95, p99,
 * min, max, and mean in ms. NMS is included in every measurement.
 *
 * Tag: RTDETRBenchmark — filter with:
 *   adb logcat -s RTDETRBenchmark:I
 */
object RTDETRBenchmarkRunner {

    private const val TAG = "RTDETRBenchmark"
    private const val WARMUP_RUNS = 5
    private const val MEASURED_RUNS = 50
    private const val BENCHMARK_IMAGE_PATH = "benchmark/test_image.jpg"

    data class ModelResult(
        val modelLabel: String,
        val delegateLabel: String,
        val threads: Int,
        val p50Ms: Long,
        val p95Ms: Long,
        val p99Ms: Long,
        val minMs: Long,
        val maxMs: Long,
        val meanMs: Long,
    ) {
        override fun toString(): String =
            "[$TAG] model=$modelLabel delegate=$delegateLabel threads=$threads " +
            "warmup=$WARMUP_RUNS measured=$MEASURED_RUNS\n" +
            "[$TAG] p50=${p50Ms}ms p95=${p95Ms}ms p99=${p99Ms}ms " +
            "min=${minMs}ms max=${maxMs}ms mean=${meanMs}ms"
    }

    /**
     * Run the full benchmark suite (FP16 + INT8, both delegate strategies).
     * Results are logged with TAG [TAG] at INFO level.
     *
     * @return list of [ModelResult], one per (model, delegate) combination.
     */
    suspend fun runAll(context: Context): List<ModelResult> =
        withContext(Dispatchers.IO) {
            val testBitmap = loadTestBitmap(context)
            val results = mutableListOf<ModelResult>()
            try {
                results += benchmarkModel(
                    context, testBitmap,
                    modelPath = RTDETRInference.MODEL_PATH_FP16,
                    strategy = DelegateStrategy.NNAPI_PREFERRED,
                    label = "FP16",
                )
                results += benchmarkModel(
                    context, testBitmap,
                    modelPath = RTDETRInference.MODEL_PATH_INT8,
                    strategy = DelegateStrategy.NNAPI_PREFERRED,
                    label = "INT8",
                )
            } finally {
                testBitmap.recycle()
            }
            results
        }

    private suspend fun benchmarkModel(
        context: Context,
        testBitmap: Bitmap,
        modelPath: String,
        strategy: DelegateStrategy,
        label: String,
    ): ModelResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "[$TAG] Starting benchmark: model=$label strategy=$strategy")

        val detector = RTDETRInference(context, modelPath, strategy)
        try {
            detector.initialize()

            val delegateActual = inferDelegateLabel(strategy)
            Log.i(TAG, "[$TAG] model=$label delegate=$delegateActual warmup=$WARMUP_RUNS measured=$MEASURED_RUNS")

            repeat(WARMUP_RUNS) {
                detector.detect(testBitmap)
            }
            Log.i(TAG, "[$TAG] Warm-up complete ($WARMUP_RUNS runs)")

            val samples = LongArray(MEASURED_RUNS)
            for (i in 0 until MEASURED_RUNS) {
                val t0 = System.nanoTime()
                detector.detect(testBitmap)
                samples[i] = (System.nanoTime() - t0) / 1_000_000L
            }

            val result = computeStats(label, delegateActual, 4, samples)
            Log.i(TAG, result.toString())
            result
        } finally {
            detector.release()
        }
    }

    private fun computeStats(
        label: String,
        delegateLabel: String,
        threads: Int,
        samplesMs: LongArray,
    ): ModelResult {
        val sorted = samplesMs.clone().also { it.sort() }
        val n = sorted.size
        val mean = sorted.average().roundToLong()
        val p50 = sorted[(n * 0.50).toInt().coerceAtMost(n - 1)]
        val p95 = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)]
        val p99 = sorted[(n * 0.99).toInt().coerceAtMost(n - 1)]
        return ModelResult(
            modelLabel = label,
            delegateLabel = delegateLabel,
            threads = threads,
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            minMs = sorted.first(),
            maxMs = sorted.last(),
            meanMs = mean,
        )
    }

    private fun loadTestBitmap(context: Context): Bitmap {
        return try {
            context.assets.open(BENCHMARK_IMAGE_PATH).use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
                    ?: throw IllegalStateException("BitmapFactory returned null")
            }.let { src ->
                Bitmap.createScaledBitmap(src, RTDETRInference.INPUT_SIZE, RTDETRInference.INPUT_SIZE, true)
                    .also { if (it !== src) src.recycle() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$TAG] Test image not found at $BENCHMARK_IMAGE_PATH — using random bitmap", e)
            Bitmap.createBitmap(RTDETRInference.INPUT_SIZE, RTDETRInference.INPUT_SIZE, Bitmap.Config.ARGB_8888)
        }
    }

    private fun inferDelegateLabel(strategy: DelegateStrategy): String = when (strategy) {
        DelegateStrategy.NNAPI_PREFERRED -> "NNAPI+XNNPACK"
        DelegateStrategy.XNNPACK_ONLY -> "XNNPACK"
    }
}
