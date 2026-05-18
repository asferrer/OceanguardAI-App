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

    // ---- B.4: unknown label DROPPED (closed-world enforcement, v0.2.8+) ----

    @Test
    fun `repair drops rows whose label is absent from canon inside a known section`() {
        // Closed-world enforcement: if the section is recognised (here
        // "Materiales" → materialRows) and the row's label is NOT in that
        // canon map, the row is fabricated and must be removed. Prior to
        // v0.2.8 such rows were kept untouched, which let the model emit
        // sections for debris types the survey never detected.
        val canon = canonWith(
            material = mapOf("Botella" to "| Botella | 12 | 50% |"),
        )
        val input = "### Materiales\n| ItemDesconocido | 5 | 25% |"

        val repaired = ToolReportGenerator.repairHallucinations(input, canon)

        assertFalse(
            "Closed-world strip should remove fabricated row, got: $repaired",
            repaired.contains("ItemDesconocido"),
        )
    }

    // ---- B.4b: rows OUTSIDE a known section are kept (totals, prose-driven tables) ----

    @Test
    fun `repair leaves rows alone outside a known data section`() {
        val canon = canonWith(
            material = mapOf("Botella" to "| Botella | 12 | 50% |"),
        )
        // No `### Materiales`/Tipos/Riesgo etc heading — section is "unknown",
        // so the closed-world rule does not fire and totals-style rows survive.
        val input = "| Total general | 25 | 100% |"

        val repaired = ToolReportGenerator.repairHallucinations(input, canon)

        assertTrue(
            "Row outside any known section should survive, got: $repaired",
            repaired.contains("Total general"),
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
            perSessionRows = emptyMap(),
            totalsBlock = "",
            tablesByKey = emptyMap(),
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

    // ---- B.10: prose decode-noise — fused word+digit gets a space ----

    @Test
    fun `repair inserts space between Spanish lead word and immediately-following digit`() {
        // Real prose snippets from report ids=19,20 (2026-05-18) where SD
        // accepted a draft token that pasted "de" + "8" without a separator.
        val input = "La puntuación de8 indica riesgo crítico, salud de47 baja, urgencia7."
        val out = ToolReportGenerator.repairProseDecodeNoise(input)
        assertTrue("'de8' should become 'de 8', got: $out", out.contains("de 8"))
        assertTrue("'de47' should become 'de 47'", out.contains("de 47"))
        assertTrue("'urgencia7' should become 'urgencia 7'", out.contains("urgencia 7"))
    }

    @Test
    fun `repair leaves image id markers alone`() {
        // `#148` is a legitimate per-session ID. The lead-word whitelist
        // doesn't include `#` so it must survive untouched.
        val input = "La imagen #148 muestra una botella."
        val out = ToolReportGenerator.repairProseDecodeNoise(input)
        assertTrue("'#148' must remain intact", out.contains("#148"))
    }

    // ---- B.11: truncated Spanish words ----

    @Test
    fun `repair fixes the truncated heading "Prómos pasos"`() {
        val input = "## Conclusiones y Prómos pasos\n\nEn conclusión…"
        val out = ToolReportGenerator.repairProseDecodeNoise(input)
        assertTrue(
            "Heading should be repaired to 'Próximos Pasos': $out",
            out.contains("## Conclusiones y Próximos Pasos"),
        )
    }

    @Test
    fun `repair fixes the truncated word "monitore"`() {
        val input = "Para el monitore los residuos plásticos, se recomienda…"
        val out = ToolReportGenerator.repairProseDecodeNoise(input)
        assertTrue(
            "'monitore' alone (followed by word boundary) must be repaired: $out",
            out.contains("Para el monitoreo los"),
        )
    }

    @Test
    fun `repair does not touch monitoreo when already correct`() {
        val input = "El monitoreo continuo es necesario."
        val out = ToolReportGenerator.repairProseDecodeNoise(input)
        assertEquals(input, out)
    }

    // ---- B.6c: combined "Material y por Tipo" heading preserves type rows ----

    @Test
    fun `repair keeps type rows under combined material-and-type heading`() {
        // Repro of report id=13 (2026-05-18) bug. The zone-report writer
        // emits "## Composición de Residuos (tablas por Material y por Tipo)"
        // as a single top-level heading. The old firstOrNull lookup matched
        // "material" first → currentNormMap = materialRows → every row whose
        // first cell was a TYPE label ("Guante", "Botella") got dropped by
        // the closed-world strip. The union-fallback fix should preserve them.
        val canon = ToolDataBundleFormatter.Canon(
            materialRows = mapOf("Plástico" to "| Plástico | 5 | 50% |"),
            typeRows = mapOf("Guante" to "| Guante | 2 | 33% |"),
            ecoRows = emptyMap(),
            riskRows = emptyMap(),
            perSessionRows = emptyMap(),
            totalsBlock = "",
            tablesByKey = emptyMap(),
        )
        val input = """
            ## Composición de Residuos (tablas por Material y por Tipo)

            | Material | Conteo | Porcentaje |
            |---|---:|---:|
            | Plástico | 5 | 50% |
            | Guante | 2 | 33% |
        """.trimIndent()

        val repaired = ToolReportGenerator.repairHallucinations(input, canon)

        assertTrue(
            "Material row must survive combined heading, got: $repaired",
            repaired.contains("| Plástico | 5 | 50% |"),
        )
        assertTrue(
            "Type row must survive combined heading (was being dropped before fix), got: $repaired",
            repaired.contains("| Guante | 2 | 33% |"),
        )
    }

    // ---- B.7: strip removes bullet-list rows that contain >=2 pipes ----

    @Test
    fun `strip removes bullet row with two or more pipes`() {
        // Reproduces the bug from report id=12 (2026-05-18): Gemma 4 E2B
        // emitted duplicate "tables" as bullet lists with pipes right after
        // each [TABLE: …] substitution. The pipe-only filter missed them and
        // they polluted the final report with corrupt values.
        val input = """
            ## Composición por Material

            Some prose…

            * Material | Conteo | Porcentaje
            * Plástico | 5 | 63%
            - Tela | 2 | 25%
            * Metal | 1 | 13%
        """.trimIndent()

        val stripped = ToolReportGenerator.stripModelWrittenTables(input)

        assertFalse("Bullet pipe-row should be dropped", stripped.contains("Plástico | 5 | 63%"))
        assertFalse("Bullet pipe-row should be dropped", stripped.contains("Tela | 2 | 25%"))
        assertFalse("Bullet pipe-row should be dropped", stripped.contains("Metal | 1 | 13%"))
        assertFalse("Bullet pipe header should be dropped", stripped.contains("Material | Conteo | Porcentaje"))
        assertTrue("Prose must be preserved", stripped.contains("Some prose"))
        assertTrue("Section heading must be preserved", stripped.contains("## Composición por Material"))
    }

    // ---- B.8: strip keeps legit bullet summaries (no pipes) ----

    @Test
    fun `strip keeps bullet summary rows that contain no pipes`() {
        // The Zone Profile / Resumen Ejecutivo placeholder substitution emits
        // legit bullet lists like "- Total: **8**". They have ZERO pipes and
        // must survive the strip pass.
        val input = """
            ## Perfil de la Zona

            - Elementos totales de residuos: **8**
            - Sesiones analizadas: **8**
            - Puntuación media de salud: **50** / 100
            - Distribución de riesgo: Alto=7, Medio=1, Bajo=0
        """.trimIndent()

        val stripped = ToolReportGenerator.stripModelWrittenTables(input)

        assertTrue("Pipe-free bullet must survive", stripped.contains("- Elementos totales de residuos: **8**"))
        assertTrue("Pipe-free bullet must survive", stripped.contains("- Sesiones analizadas: **8**"))
        assertTrue("Pipe-free bullet must survive", stripped.contains("- Distribución de riesgo: Alto=7, Medio=1, Bajo=0"))
    }

    // ---- B.9: strip leaves legit pipe tables for substitution to handle ----

    @Test
    fun `strip removes pipe-only table rows (existing behavior)`() {
        // Sanity: the legacy pipe-row strip still works. The model's
        // markdown-style table rows must be removed so substituteTablePlaceholders
        // can insert the canonical bundle tables.
        val input = """
            ## Composición

            | Material | Conteo | Porcentaje |
            |---|---:|---:|
            | Plástico | 5 | 63% |
        """.trimIndent()

        val stripped = ToolReportGenerator.stripModelWrittenTables(input)

        assertFalse("Table header line must be stripped", stripped.contains("| Material | Conteo | Porcentaje |"))
        assertFalse("Separator line must be stripped", stripped.contains("|---|"))
        assertFalse("Data row must be stripped", stripped.contains("| Plástico | 5 | 63% |"))
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
        perSessionRows = emptyMap(),
        totalsBlock = totals,
        tablesByKey = emptyMap(),
    )

    private fun emptyCanon() = canonWith()
}
