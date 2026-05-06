package com.oceanguard.ai.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the canonical row-repair logic executed after PHASE 2 in
 * ToolReportGenerator. Exercises ToolReportGenerator.repairHallucinations
 * directly via its companion object (internal visibility).
 */
class ToolReportGeneratorTest {

    // ---- B.1: double space in model output cell ----

    @Test
    fun `repair replaces row with double-space label using canonical row`() {
        val canon = canonWith(
            material = mapOf("Botella" to "| Botella | 12 | 50% |"),
        )
        val input = "### Materiales\n| Botella  | 99 | 99% |"

        val repaired = ToolReportGenerator.repairHallucinations(input, canon)

        assertTrue(
            "Expected canonical row in output, got: $repaired",
            repaired.contains("| Botella | 12 | 50% |"),
        )
        assertFalse("Hallucinated row must be replaced", repaired.contains("99 | 99%"))
    }

    // ---- B.2: missing accent — Plastico vs Plástico ----

    @Test
    fun `repair replaces row with missing accent using canonical row`() {
        val canon = canonWith(
            material = mapOf("Plástico" to "| Plástico | 8 | 40% |"),
        )
        val input = "### Materiales\n| Plastico | 1 | 1% |"

        val repaired = ToolReportGenerator.repairHallucinations(input, canon)

        assertTrue(
            "Expected accented canonical row, got: $repaired",
            repaired.contains("| Plástico | 8 | 40% |"),
        )
    }

    // ---- B.3: uppercase label ----

    @Test
    fun `repair replaces row with UPPERCASE label using canonical row`() {
        val canon = canonWith(
            material = mapOf("Botella" to "| Botella | 12 | 50% |"),
        )
        val input = "### Materiales\n| BOTELLA | 99 | 99% |"

        val repaired = ToolReportGenerator.repairHallucinations(input, canon)

        assertTrue(repaired.contains("| Botella | 12 | 50% |"))
        assertFalse(repaired.contains("BOTELLA"))
    }

    // ---- B.4: unknown label preserved unchanged ----

    @Test
    fun `repair does not alter rows whose label is absent from canon`() {
        val canon = canonWith(
            material = mapOf("Botella" to "| Botella | 12 | 50% |"),
        )
        val input = "### Materiales\n| ItemDesconocido | 5 | 25% |"

        val repaired = ToolReportGenerator.repairHallucinations(input, canon)

        assertTrue(
            "Unknown label must survive untouched, got: $repaired",
            repaired.contains("| ItemDesconocido | 5 | 25% |"),
        )
    }

    // ---- B.5: corrupt year — must NOT be silently rewritten ----

    @Test
    fun `repair leaves corrupt date untouched when canon has no date entry`() {
        // Production behavior change: the old destructive regex
        // Regex("""20\d{2}(\d+)(-\d{2}-\d{2})""") could mangle valid dates and
        // produced unverifiable substitutions. The current implementation
        // detects the corruption (5+ consecutive digits in the line) but only
        // restores the row if Canon carries a matching date entry. With an
        // empty canon, the row is preserved verbatim — never silently mangled.
        val input = "| 20262-04-15 | 3 | 45 |"
        val repaired = ToolReportGenerator.repairHallucinations(input, emptyCanon())

        assertTrue(
            "Corrupt date must be left untouched without a canon entry, got: $repaired",
            repaired.contains("20262-04-15"),
        )
    }

    // ---- B.6: material/type section collision (now active) ----

    @Test
    fun `repair uses section-specific canon when material and type share same label`() {
        val materialCanon = mapOf("Plastic" to "| Plastic | 10 | 50% |")
        val typeCanon = mapOf("Plastic" to "| Plastic | 3 | 30% |")
        val canon = ToolDataBundleFormatter.Canon(
            materialRows = materialCanon,
            typeRows = typeCanon,
            ecoRows = emptyMap(),
            riskRows = emptyMap(),
            totalsBlock = "",
        )

        // Production tracks section context via "###" headings whose lowercase
        // text contains "material" or "type". Each section's row must resolve
        // against its own map.
        val input = """
            ### Materials
            | Plastic | 99 | 99% |

            ### Types
            | Plastic | 99 | 99% |
        """.trimIndent()

        val repaired = ToolReportGenerator.repairHallucinations(input, canon)

        assertTrue(
            "Material section must use materialRows value, got: $repaired",
            repaired.contains("| Plastic | 10 | 50% |"),
        )
        assertTrue(
            "Type section must use typeRows value, got: $repaired",
            repaired.contains("| Plastic | 3 | 30% |"),
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun canonWith(
        material: Map<String, String> = emptyMap(),
        type: Map<String, String> = emptyMap(),
        eco: Map<String, String> = emptyMap(),
        risk: Map<String, String> = emptyMap(),
        totals: String = "",
    ) = ToolDataBundleFormatter.Canon(
        materialRows = material,
        typeRows = type,
        ecoRows = eco,
        riskRows = risk,
        totalsBlock = totals,
    )

    private fun emptyCanon() = canonWith()
}
