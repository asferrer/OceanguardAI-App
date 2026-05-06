package com.oceanguard.ai.inference

import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ToolDataBundleFormatterTest {

    // ---- Suite A.1: empty sessions — no exception, no NaN/Infinity ----

    @Test
    fun `formatWithCanon with empty sessions does not throw and contains CONFIRMED DATA header`() {
        val ctx = ToolReportContext(emptyList(), "en", ReportAudience.SCIENTIFIC)

        val (bundle, canon) = ToolDataBundleFormatter.formatWithCanon(ctx)

        assertNotNull(bundle)
        assertTrue(
            "Bundle must start with CONFIRMED DATA section",
            bundle.contains("## CONFIRMED DATA")
        )
        assertFalse("totalsBlock must not contain NaN", canon.totalsBlock.contains("NaN"))
        assertFalse(
            "totalsBlock must not contain -Infinity",
            canon.totalsBlock.contains("-Infinity")
        )
        assertFalse(
            "totalsBlock must not contain Infinity",
            canon.totalsBlock.contains("Infinity")
        )
        assertTrue(canon.materialRows.isEmpty())
        assertTrue(canon.typeRows.isEmpty())
    }

    // ---- Suite A.2: single session produces canonical rows for dominant entries ----

    @Test
    fun `formatWithCanon with one session produces canonical material and type rows`() {
        val ctx = ToolReportContext(
            sessions = listOf(
                session(
                    id = 1L,
                    debris = listOf(
                        debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
                        debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
                        debris(DebrisType.CAN, DebrisMaterial.METAL),
                    ),
                    healthScore = 70,
                )
            ),
            language = "en",
            audience = ReportAudience.SCIENTIFIC,
        )

        val (_, canon) = ToolDataBundleFormatter.formatWithCanon(ctx)

        // Material rows keyed by translated EN label
        assertTrue(
            "materialRows must contain 'Plastic' key",
            canon.materialRows.containsKey("Plastic")
        )
        assertTrue(
            "materialRows must contain 'Metal' key",
            canon.materialRows.containsKey("Metal")
        )
        // Type rows keyed by translated EN label
        assertTrue(
            "typeRows must contain 'Bottle' key",
            canon.typeRows.containsKey("Bottle")
        )
        assertTrue(
            "typeRows must contain 'Can' key",
            canon.typeRows.containsKey("Can")
        )
        // Canonical row for Plastic: 2 items out of 3 = 67%
        val plasticRow = canon.materialRows["Plastic"]!!
        assertTrue("Plastic row must contain count 2", plasticRow.contains("| 2 |"))
        assertTrue("Plastic row must contain 67%", plasticRow.contains("67%"))
    }

    // ---- Suite A.3: Canon.materialRows keyed by localized labels (Spanish) ----

    @Test
    fun `Canon materialRows uses localized labels for Spanish`() {
        val ctx = ToolReportContext(
            sessions = listOf(
                session(
                    id = 1L,
                    debris = listOf(
                        debris(DebrisType.PLASTIC_DEBRIS, DebrisMaterial.PLASTIC),
                        debris(DebrisType.FISHING_NET, DebrisMaterial.FISHING_NET),
                    ),
                    healthScore = 60,
                )
            ),
            language = "es",
            audience = ReportAudience.SCIENTIFIC,
        )

        val (_, canon) = ToolDataBundleFormatter.formatWithCanon(ctx)

        // Spanish: PLASTIC → "Plástico", FISHING_NET → "Red de pesca"
        assertTrue(
            "materialRows must contain Spanish key 'Plástico'",
            canon.materialRows.containsKey("Plástico")
        )
        assertTrue(
            "materialRows must contain Spanish key 'Red de pesca'",
            canon.materialRows.containsKey("Red de pesca")
        )
        // Must NOT contain raw enum name
        assertFalse(
            "materialRows must not use UPPER_SNAKE_CASE key 'PLASTIC'",
            canon.materialRows.containsKey("PLASTIC")
        )
        assertFalse(
            "materialRows must not use UPPER_SNAKE_CASE key 'FISHING_NET'",
            canon.materialRows.containsKey("FISHING_NET")
        )
    }

    // ---- Suite A.4: smoke test across all 6 supported languages ----

    @Test
    fun `formatWithCanon does not throw for any supported language and bundle starts with CONFIRMED DATA`() {
        val languages = listOf("en", "es", "fr", "de", "it", "pt")
        val ctx = ToolReportContext(
            sessions = listOf(
                session(
                    id = 1L,
                    debris = listOf(
                        debris(DebrisType.BOTTLE, DebrisMaterial.PLASTIC),
                        debris(DebrisType.CAN, DebrisMaterial.METAL),
                    ),
                    healthScore = 75,
                )
            ),
            language = "en",  // language override per iteration below
            audience = ReportAudience.SCIENTIFIC,
        )

        for (lang in languages) {
            val langCtx = ToolReportContext(
                sessions = ctx.sessions,
                language = lang,
                audience = ReportAudience.SCIENTIFIC,
            )
            val (bundle, canon) = ToolDataBundleFormatter.formatWithCanon(langCtx)

            assertTrue(
                "Bundle for lang=$lang must contain CONFIRMED DATA section",
                bundle.contains("## CONFIRMED DATA")
            )
            assertFalse(
                "totalsBlock for lang=$lang must not contain NaN",
                canon.totalsBlock.contains("NaN")
            )
        }
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
}
