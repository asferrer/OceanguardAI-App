package com.oceanguard.ai.inference

import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ToolReportContextTest {

    @Test
    fun `aggregates totals and counts across sessions`() {
        val sessions = listOf(
            session(id = 1, debris = listOf(
                debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
                debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
                debris(DebrisType.CAN, DebrisMaterial.METAL),
            ), healthScore = 70),
            session(id = 2, debris = listOf(
                debris(DebrisType.FISHING_NET, DebrisMaterial.FISHING_NET),
                debris(DebrisType.PLASTIC_BAG, DebrisMaterial.PLASTIC),
            ), healthScore = 50),
        )

        val ctx = ToolReportContext(sessions, "en", ReportAudience.SCIENTIFIC)

        assertEquals(2, ctx.sessionCount)
        assertEquals(5, ctx.totalDebrisItems)
        assertEquals(60, ctx.avgHealthScore) // (70+50)/2
        assertEquals(3, ctx.materialCounts[DebrisMaterial.PLASTIC]) // 2 bottles + 1 bag
        assertEquals(1, ctx.materialCounts[DebrisMaterial.METAL])
        assertEquals(1, ctx.materialCounts[DebrisMaterial.FISHING_NET])
    }

    @Test
    fun `risk breakdown classifies by debris risk score`() {
        val sessions = listOf(session(id = 1, debris = listOf(
            debris(DebrisType.FISHING_NET, DebrisMaterial.FISHING_NET), // risk 5 → high
            debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),          // risk 5 (plastic) → high
            debris(DebrisType.CAN, DebrisMaterial.METAL),               // risk 3 → medium
            debris(DebrisType.PAPER, DebrisMaterial.PAPER),             // risk 1 → low
        )))

        val ctx = ToolReportContext(sessions, "en", ReportAudience.SCIENTIFIC)

        assertEquals(2, ctx.riskBreakdown["high"])
        assertEquals(1, ctx.riskBreakdown["medium"])
        assertEquals(1, ctx.riskBreakdown["low"])
    }

    @Test
    fun `waypoints sorted by priority HIGH first then by debris count`() {
        val sessions = listOf(
            session(id = 1, debris = repeat(2, DebrisType.BOTTLE), healthScore = 90,
                location = Location(40.0, -3.0)), // LOW
            session(id = 2, debris = repeat(15, DebrisType.PLASTIC_DEBRIS), healthScore = 30,
                location = Location(40.5, -3.5)), // HIGH (low health AND high count)
            session(id = 3, debris = repeat(6, DebrisType.CAN), healthScore = 55,
                location = Location(41.0, -4.0)), // MEDIUM
            session(id = 4, debris = listOf(debris(DebrisType.MASK, DebrisMaterial.PLASTIC)),
                healthScore = 95, location = null), // no GPS, dropped
        )

        val ctx = ToolReportContext(sessions, "en", ReportAudience.SCIENTIFIC)

        assertEquals(3, ctx.waypoints.size) // session 4 dropped (no GPS)
        assertEquals("HIGH", ctx.waypoints[0].priority)
        assertEquals(2L, ctx.waypoints[0].sessionId)
        assertEquals("MEDIUM", ctx.waypoints[1].priority)
        assertEquals("LOW", ctx.waypoints[2].priority)
    }

    @Test
    fun `temporal trend is null for generic reports`() {
        val sessions = listOf(session(id = 1, debris = repeat(3, DebrisType.BOTTLE)))
        val ctx = ToolReportContext(sessions, "en", ReportAudience.SCIENTIFIC, zoneInput = null)
        assertEquals(null, ctx.temporalTrend)
    }

    @Test
    fun `empty sessions produce safe defaults`() {
        val ctx = ToolReportContext(emptyList(), "en", ReportAudience.SCIENTIFIC)
        assertEquals(0, ctx.sessionCount)
        assertEquals(0, ctx.totalDebrisItems)
        assertEquals(100, ctx.avgHealthScore)
        assertTrue(ctx.materialCounts.isEmpty())
        assertTrue(ctx.waypoints.isEmpty())
    }

    // ---- helpers ----

    private fun session(
        id: Long,
        debris: List<Debris>,
        healthScore: Int = 70,
        location: Location? = null,
    ) = DetectionSession(
        id = id,
        imageUri = "file:///tmp/$id.jpg",
        debrisList = debris,
        totalCount = debris.size,
        healthScore = healthScore,
        location = location,
        timestamp = Date(1_700_000_000_000L + id * 86_400_000L),
        imageQuality = ImageQuality.GOOD,
        processingTimeMs = 100L,
    )

    private fun debris(
        type: DebrisType,
        material: DebrisMaterial,
        confidence: Float = 0.9f,
    ) = Debris(
        bbox = BoundingBox(0f, 0f, 0.5f, 0.5f),
        material = material,
        type = type,
        confidence = confidence,
    )

    private fun repeat(n: Int, type: DebrisType): List<Debris> {
        val mat = when (type) {
            DebrisType.BOTTLE, DebrisType.PLASTIC_DEBRIS, DebrisType.PLASTIC_BAG, DebrisType.MASK -> DebrisMaterial.PLASTIC
            DebrisType.CAN -> DebrisMaterial.METAL
            DebrisType.FISHING_NET -> DebrisMaterial.FISHING_NET
            DebrisType.PAPER -> DebrisMaterial.PAPER
            else -> DebrisMaterial.OTHER
        }
        return List(n) { debris(type, mat) }
    }
}
