package com.oceanguard.ai.species

import com.oceanguard.ai.inference.species.StageLatency
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [StageLatency] — the rolling per-stage latency tracker used to
 * make BioDex inference timings measurable from logcat. `logSummary` is not
 * asserted here (it only logs); the `last` / `p50` / windowing maths are.
 */
class StageLatencyTest {

    @Test
    fun `unrecorded stage reports -1`() {
        val l = StageLatency("test")
        assertEquals(-1L, l.last("locate"))
        assertEquals(-1L, l.p50("locate"))
    }

    @Test
    fun `last returns the most recent sample`() {
        val l = StageLatency("test")
        l.record("encode", 100)
        l.record("encode", 250)
        assertEquals(250L, l.last("encode"))
    }

    @Test
    fun `p50 is the median of recorded samples`() {
        val l = StageLatency("test")
        listOf(10L, 30L, 20L, 50L, 40L).forEach { l.record("locate", it) }
        // sorted: 10,20,30,40,50 → median index 2 = 30
        assertEquals(30L, l.p50("locate"))
    }

    @Test
    fun `window evicts oldest samples beyond windowSize`() {
        val l = StageLatency("test", windowSize = 3)
        // Record 1,2,3,1000 → only [2,3,1000] retained → median = 3
        l.record("total", 1)
        l.record("total", 2)
        l.record("total", 3)
        l.record("total", 1000)
        assertEquals(3L, l.p50("total"))
        // last always reflects the newest value regardless of windowing.
        assertEquals(1000L, l.last("total"))
    }

    @Test
    fun `stages are tracked independently`() {
        val l = StageLatency("test")
        l.record("locate", 3000)
        l.record("encode", 120)
        assertEquals(3000L, l.last("locate"))
        assertEquals(120L, l.last("encode"))
        assertEquals(-1L, l.last("search"))
    }
}
