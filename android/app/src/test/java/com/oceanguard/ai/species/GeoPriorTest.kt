package com.oceanguard.ai.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.inference.species.GeoPrior
import com.oceanguard.ai.inference.species.GeoMatchLevel
import com.oceanguard.ai.inference.species.MarineRegion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [GeoPrior].
 *
 * Verifies the `g(level)` weight function and [GeoPrior.matchLevel] logic
 * against known edge cases from plan section §2b.
 */
class GeoPriorTest {

    // -----------------------------------------------------------------------
    // g(level) — prior weight values
    // -----------------------------------------------------------------------

    @Test
    fun `g ECOREGION returns 1_0`() {
        assertEquals(1.0f, GeoPrior.g(GeoMatchLevel.ECOREGION), 1e-6f)
    }

    @Test
    fun `g PROVINCE returns 0_6`() {
        assertEquals(0.6f, GeoPrior.g(GeoMatchLevel.PROVINCE), 1e-6f)
    }

    @Test
    fun `g REALM returns 0_3`() {
        assertEquals(0.3f, GeoPrior.g(GeoMatchLevel.REALM), 1e-6f)
    }

    @Test
    fun `g COSMOPOLITAN returns 0_1`() {
        assertEquals(0.1f, GeoPrior.g(GeoMatchLevel.COSMOPOLITAN), 1e-6f)
    }

    @Test
    fun `g GLOBAL returns 1_0`() {
        assertEquals(1.0f, GeoPrior.g(GeoMatchLevel.GLOBAL), 1e-6f)
    }

    @Test
    fun `all g values are in range 0_exclusive to 1_inclusive`() {
        for (level in GeoMatchLevel.entries) {
            val g = GeoPrior.g(level)
            assert(g > 0f && g <= 1f) { "g($level) = $g is out of range (0, 1]" }
        }
    }

    // -----------------------------------------------------------------------
    // matchLevel — cosmopolitan always wins
    // -----------------------------------------------------------------------

    @Test
    fun `cosmopolitan species always returns COSMOPOLITAN regardless of region`() {
        val species = catalogEntry(ecoregions = emptyList(), cosmopolitan = true)
        val region = MarineRegion(ecoregionId = 1, provinceId = 1, realmId = 1)
        assertEquals(
            GeoMatchLevel.COSMOPOLITAN,
            GeoPrior.matchLevel(species, region) { null },
        )
    }

    @Test
    fun `cosmopolitan species returns COSMOPOLITAN with null region`() {
        val species = catalogEntry(ecoregions = emptyList(), cosmopolitan = true)
        assertEquals(
            GeoMatchLevel.COSMOPOLITAN,
            GeoPrior.matchLevel(species, null) { null },
        )
    }

    // -----------------------------------------------------------------------
    // matchLevel — no GPS → GLOBAL
    // -----------------------------------------------------------------------

    @Test
    fun `null region returns GLOBAL for non-cosmopolitan species`() {
        val species = catalogEntry(ecoregions = listOf(10), cosmopolitan = false)
        assertEquals(
            GeoMatchLevel.GLOBAL,
            GeoPrior.matchLevel(species, null) { null },
        )
    }

    // -----------------------------------------------------------------------
    // matchLevel — hierarchy levels
    // -----------------------------------------------------------------------

    @Test
    fun `species with matching ecoregion returns ECOREGION`() {
        val species = catalogEntry(ecoregions = listOf(10, 20), cosmopolitan = false)
        val region = MarineRegion(ecoregionId = 10, provinceId = 3, realmId = 1)
        assertEquals(
            GeoMatchLevel.ECOREGION,
            GeoPrior.matchLevel(species, region) { ecoId ->
                if (ecoId == 10 || ecoId == 20) Pair(3, 1) else null
            },
        )
    }

    @Test
    fun `species with matching province but not ecoregion returns PROVINCE`() {
        val species = catalogEntry(ecoregions = listOf(20), cosmopolitan = false)
        val region = MarineRegion(ecoregionId = 15, provinceId = 3, realmId = 1)
        // ecoregion 20 → province 3 (same as region.provinceId)
        assertEquals(
            GeoMatchLevel.PROVINCE,
            GeoPrior.matchLevel(species, region) { ecoId ->
                if (ecoId == 20) Pair(3, 1) else null
            },
        )
    }

    @Test
    fun `species with matching realm but not province returns REALM`() {
        val species = catalogEntry(ecoregions = listOf(30), cosmopolitan = false)
        val region = MarineRegion(ecoregionId = 15, provinceId = 3, realmId = 1)
        // ecoregion 30 → province 7 (different), realm 1 (matches)
        assertEquals(
            GeoMatchLevel.REALM,
            GeoPrior.matchLevel(species, region) { ecoId ->
                if (ecoId == 30) Pair(7, 1) else null
            },
        )
    }

    @Test
    fun `species completely outside realm returns GLOBAL (out-of-range)`() {
        val species = catalogEntry(ecoregions = listOf(30), cosmopolitan = false)
        val region = MarineRegion(ecoregionId = 15, provinceId = 3, realmId = 1)
        // ecoregion 30 → realm 9 (completely different from region.realmId=1)
        assertEquals(
            GeoMatchLevel.GLOBAL,
            GeoPrior.matchLevel(species, region) { ecoId ->
                if (ecoId == 30) Pair(7, 9) else null
            },
        )
    }

    @Test
    fun `species with empty ecoregion list returns GLOBAL (no data)`() {
        val species = catalogEntry(ecoregions = emptyList(), cosmopolitan = false)
        val region = MarineRegion(ecoregionId = 10, provinceId = 1, realmId = 1)
        assertEquals(
            GeoMatchLevel.GLOBAL,
            GeoPrior.matchLevel(species, region) { null },
        )
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private fun catalogEntry(
        ecoregions: List<Int>,
        cosmopolitan: Boolean,
    ) = SpeciesCatalogEntry(
        speciesKey = "test_sp",
        aphiaId = 1L,
        scientificName = "Testus testus",
        commonNames = emptyMap(),
        spritePath = null,
        ecoregions = ecoregions,
        cosmopolitan = cosmopolitan,
    )
}
