package com.oceanguard.ai.inference.species

import android.util.Log

/**
 * Tiny rolling latency tracker for the BioDex identification pipeline.
 *
 * Each [record] keeps the last value and a bounded ring of recent samples per
 * stage; [logSummary] emits one logcat line with `last` and `p50` for every
 * stage seen so far (locate / encode / search / describe / total). Designed to
 * make on-device latency measurable from logcat without a profiler or any
 * external dependency. Not thread-safe by design — it is confined to the single
 * identify coroutine, and the ring is small.
 *
 * @param tag        Logcat tag (shared with the owning class).
 * @param windowSize Number of recent samples retained per stage for the p50.
 */
class StageLatency(
    private val tag: String,
    private val windowSize: Int = 20,
) {
    private val samples = LinkedHashMap<String, ArrayDeque<Long>>()
    private val last = LinkedHashMap<String, Long>()

    /** Record [ms] for [stage], evicting the oldest sample beyond [windowSize]. */
    fun record(stage: String, ms: Long) {
        last[stage] = ms
        val ring = samples.getOrPut(stage) { ArrayDeque() }
        ring.addLast(ms)
        while (ring.size > windowSize) ring.removeFirst()
    }

    /** Median of the retained samples for [stage], or -1 when none recorded. */
    fun p50(stage: String): Long {
        val ring = samples[stage] ?: return -1L
        if (ring.isEmpty()) return -1L
        val sorted = ring.sorted()
        return sorted[sorted.size / 2]
    }

    /** Last recorded value for [stage], or -1 when none recorded. */
    fun last(stage: String): Long = last[stage] ?: -1L

    /**
     * Emit a single summary line, e.g.
     * `BioDex latency ms | locate last=2800 p50=3000 | encode last=120 p50=130 | total last=3100 p50=3300`.
     * Only stages that have at least one sample appear.
     */
    fun logSummary() {
        if (samples.isEmpty()) return
        val parts = samples.keys.joinToString(" | ") { stage ->
            "$stage last=${last(stage)} p50=${p50(stage)}"
        }
        Log.i(tag, "BioDex latency ms | $parts")
    }
}
