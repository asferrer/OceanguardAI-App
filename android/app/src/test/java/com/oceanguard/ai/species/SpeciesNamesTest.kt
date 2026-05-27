package com.oceanguard.ai.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesNames
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SpeciesNames.commonName] localised name resolution.
 *
 * Exercises the pure `(entry, language, fallback)` overload — no Android
 * framework, no AppCompat locale state. Guards the BioDex requirement that the
 * grid + detail header show the localised common name with an
 * en → scientificName fallback chain.
 */
class SpeciesNamesTest {

    private fun entry(
        commonNames: Map<String, String>,
        scientificName: String = "Caretta caretta",
    ) = SpeciesCatalogEntry(
        speciesKey     = "137205",
        aphiaId        = 137205L,
        scientificName = scientificName,
        commonNames    = commonNames,
        spritePath     = null,
        ecoregions     = emptyList(),
    )

    @Test
    fun `returns localised common name when language present`() {
        val e = entry(mapOf("en" to "Loggerhead turtle", "es" to "Tortuga boba"))
        assertEquals("Tortuga boba", SpeciesNames.commonName(e, language = "es"))
        assertEquals("Loggerhead turtle", SpeciesNames.commonName(e, language = "en"))
    }

    @Test
    fun `falls back to english when requested language missing`() {
        val e = entry(mapOf("en" to "Loggerhead turtle"))
        assertEquals(
            "Loggerhead turtle",
            SpeciesNames.commonName(e, language = "fr"),
        )
    }

    @Test
    fun `falls back to scientific name when no common name for language or english`() {
        val e = entry(commonNames = mapOf("de" to "Unechte Karettschildkröte"))
        // Requested "es" missing AND "en" missing → scientific name.
        assertEquals("Caretta caretta", SpeciesNames.commonName(e, language = "es"))
    }

    @Test
    fun `falls back to scientific name when common names empty`() {
        val e = entry(commonNames = emptyMap())
        assertEquals("Caretta caretta", SpeciesNames.commonName(e, language = "en"))
    }

    @Test
    fun `returns provided fallback when entry is null`() {
        assertEquals("???", SpeciesNames.commonName(null, language = "en"))
        assertEquals(
            "octopus_vulgaris",
            SpeciesNames.commonName(null, language = "en", fallback = "octopus_vulgaris"),
        )
    }
}
