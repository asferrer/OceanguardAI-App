package com.oceanguard.ai.inference

import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.math.abs

class OceanGuardToolsTest {

    @Test
    fun `getDebrisSummary reports totals and dominant categories`() {
        val tools = toolsFor(listOf(
            session(id = 1, debris = listOf(
                debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
                debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
                debris(DebrisType.CAN, DebrisMaterial.METAL),
            ), healthScore = 60),
        ))

        val s = tools.getDebrisSummary()
        assertEquals(3, s["totalDebrisItems"])
        assertEquals(1, s["sessionCount"])
        assertEquals(60, s["avgHealthScore"])
        assertEquals("PLASTIC", s["dominantMaterial"])
        assertEquals("BOTTLE", s["dominantType"])
    }

    @Test
    fun `getMaterialBreakdown percentages sum to 100`() {
        val tools = toolsFor(listOf(session(id = 1, debris = listOf(
            debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
            debris(DebrisType.PLASTIC_BAG, DebrisMaterial.PLASTIC),
            debris(DebrisType.PLASTIC_BAG, DebrisMaterial.PLASTIC),
            debris(DebrisType.CAN, DebrisMaterial.METAL),
            debris(DebrisType.PAPER, DebrisMaterial.PAPER),
        ))))

        val r = tools.getMaterialBreakdown()
        assertEquals(5, r["total"])
        @Suppress("UNCHECKED_CAST")
        val items = r["items"] as List<Map<String, Any>>
        val sum = items.sumOf { it["percent"] as Double }
        assertTrue("percent sum=$sum", abs(sum - 100.0) <= 0.3)
    }

    @Test
    fun `getTypeBreakdown returns sorted desc with percentages`() {
        val tools = toolsFor(listOf(session(id = 1, debris = listOf(
            debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
            debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
            debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
            debris(DebrisType.CAN, DebrisMaterial.METAL),
        ))))

        val r = tools.getTypeBreakdown()
        @Suppress("UNCHECKED_CAST")
        val items = r["items"] as List<Map<String, Any>>
        assertEquals("BOTTLE", items[0]["name"])
        assertEquals(3, items[0]["count"])
        assertEquals(75.0, items[0]["percent"])
    }

    @Test
    fun `getEcologicalImpact returns IMPACT_MAP values for FISHING_NET`() {
        val tools = toolsFor(listOf(session(id = 1, debris = listOf(
            debris(DebrisType.FISHING_NET, DebrisMaterial.FISHING_NET),
        ))))

        val impact = tools.getEcologicalImpact("FISHING_NET")
        assertEquals("FISHING_NET", impact["type"])
        assertEquals("600+ years", impact["degradationTime"])
        assertEquals(10, impact["riskScore"])
        assertEquals("~640K tonnes/year", impact["annualVolumeOcean"])
    }

    @Test
    fun `getCollectionWaypoints orders by priority and returns up to 10`() {
        val sessions = (1..6).map { i ->
            session(
                id = i.toLong(),
                debris = repeat(if (i == 2) 12 else 2, DebrisType.BOTTLE),
                healthScore = if (i == 2) 25 else 85,
                location = Location(40.0 + i * 0.1, -3.0 - i * 0.1),
            )
        }
        val r = toolsFor(sessions).getCollectionWaypoints()
        assertEquals(6, r["count"])
        @Suppress("UNCHECKED_CAST")
        val items = r["items"] as List<Map<String, Any>>
        assertEquals("HIGH", items[0]["priority"])
        assertEquals(2L, items[0]["sessionId"])
    }

    @Test
    fun `getCollectionWaypoints caps at 10 even with many sessions`() {
        val sessions = (1..25).map { i ->
            session(id = i.toLong(),
                debris = listOf(debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC)),
                location = Location(40.0, -3.0))
        }
        val r = toolsFor(sessions).getCollectionWaypoints()
        assertEquals(10, r["count"])
    }

    @Test
    fun `getRiskAssessment is sorted by riskScore desc and uses IMPACT_MAP scores`() {
        val tools = toolsFor(listOf(session(id = 1, debris = listOf(
            debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),       // 8
            debris(DebrisType.FISHING_NET, DebrisMaterial.FISHING_NET), // 10
            debris(DebrisType.GLASS_DEBRIS, DebrisMaterial.GLASS),   // 4
        ))))

        val r = tools.getRiskAssessment()
        @Suppress("UNCHECKED_CAST")
        val items = r["items"] as List<Map<String, Any>>
        assertEquals("FISHING_NET", items[0]["type"])
        assertEquals(10, items[0]["riskScore"])
        assertEquals("CRITICAL", items[0]["urgency"])
        assertEquals("BOTTLE", items[1]["type"])
        assertEquals("GLASS_DEBRIS", items[2]["type"])
    }

    @Test
    fun `computeStatistics for health_score returns correct min max avg`() {
        val tools = toolsFor(listOf(
            session(id = 1, debris = repeat(1, DebrisType.BOTTLE), healthScore = 40),
            session(id = 2, debris = repeat(1, DebrisType.BOTTLE), healthScore = 60),
            session(id = 3, debris = repeat(1, DebrisType.BOTTLE), healthScore = 80),
        ))

        val r = tools.computeStatistics("health_score")
        assertEquals(3, r["n"])
        assertEquals(40.0, r["min"])
        assertEquals(80.0, r["max"])
        assertEquals(60.0, r["avg"])
        assertEquals(60.0, r["median"])
    }

    @Test
    fun `computeStatistics rejects unknown field`() {
        val tools = toolsFor(listOf(session(id = 1, debris = repeat(1, DebrisType.BOTTLE))))
        val r = tools.computeStatistics("nonsense")
        assertNotNull(r["error"])
    }

    @Test
    fun `getSessionDetail returns most recent at index 0`() {
        val tools = toolsFor(listOf(
            session(id = 1, debris = repeat(2, DebrisType.BOTTLE), tsOffsetDays = 0),
            session(id = 2, debris = repeat(5, DebrisType.CAN), tsOffsetDays = 5),
        ))

        val r = tools.getSessionDetail(0)
        assertEquals(2L, r["sessionId"])
        assertEquals(5, r["totalDebris"])
    }

    @Test
    fun `getSessionDetail returns error for out-of-range index`() {
        val tools = toolsFor(listOf(session(id = 1, debris = repeat(1, DebrisType.BOTTLE))))
        val r = tools.getSessionDetail(99)
        assertNotNull(r["error"])
    }

    @Test
    fun `getTemporalTrend returns available=false for generic reports`() {
        val tools = toolsFor(listOf(session(id = 1, debris = repeat(1, DebrisType.BOTTLE))))
        val r = tools.getTemporalTrend()
        assertEquals(false, r["available"])
    }

    // ---- helpers ----

    private fun toolsFor(sessions: List<DetectionSession>): OceanGuardTools =
        OceanGuardTools(ToolReportContext(sessions, "en", ReportAudience.SCIENTIFIC))

    private fun session(
        id: Long,
        debris: List<Debris>,
        healthScore: Int = 70,
        location: Location? = null,
        tsOffsetDays: Long = 0,
    ) = DetectionSession(
        id = id,
        imageUri = "file:///tmp/$id.jpg",
        debrisList = debris,
        totalCount = debris.size,
        healthScore = healthScore,
        location = location,
        timestamp = Date(1_700_000_000_000L + tsOffsetDays * 86_400_000L),
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
            DebrisType.GLASS_DEBRIS -> DebrisMaterial.GLASS
            else -> DebrisMaterial.OTHER
        }
        return List(n) { debris(type, mat) }
    }
}
