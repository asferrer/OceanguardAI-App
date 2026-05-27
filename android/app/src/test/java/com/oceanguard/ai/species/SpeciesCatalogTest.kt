package com.oceanguard.ai.species

import com.oceanguard.ai.data.species.SpeciesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Guards the cross-language contract between the Python catalog builder
 * (`finetune/species/build_distribution_filter.py`) and [SpeciesCatalog].
 *
 * The builder emits a top-level OBJECT `{"version":..,"sources":..,"species":[..]}`
 * (carrying provenance metadata), NOT a bare array. A regression here previously
 * threw `JsonSyntaxException: Expected BEGIN_ARRAY but was BEGIN_OBJECT` at
 * runtime on device, silently falling back to the demo identifier.
 */
class SpeciesCatalogTest {

    /** Catalog JSON exactly as the Python builder writes it (object wrapper). */
    private val wrapperJson = """
        {
          "version": 1,
          "sources": ["mock"],
          "license_note": "MEOW: TNC; OBIS/GBIF: CC BY 4.0.",
          "species": [
            {
              "speciesKey": "amphiprion_ocellaris",
              "aphiaId": 397008,
              "scientificName": "Amphiprion ocellaris",
              "commonNames": {"en": "Clown anemonefish", "es": "Pez payaso"},
              "spritePath": "sprites/amphiprion_ocellaris.webp",
              "ecoregions": [84, 85, 87, 88],
              "cosmopolitan": false,
              "descriptions": {"en": ""},
              "iucnStatus": "LC"
            },
            {
              "speciesKey": "octopus_vulgaris",
              "aphiaId": 140605,
              "scientificName": "Octopus vulgaris",
              "commonNames": {"en": "Common octopus"},
              "spritePath": "sprites/octopus_vulgaris.webp",
              "ecoregions": [22, 23, 24, 25],
              "cosmopolitan": false,
              "descriptions": {},
              "iucnStatus": null
            }
          ]
        }
    """.trimIndent()

    private fun tempCatalog(content: String): File =
        File.createTempFile("species_catalog", ".json").apply {
            writeText(content, Charsets.UTF_8)
            deleteOnExit()
        }

    @Test
    fun loadsObjectWrappedCatalog() {
        val catalog = SpeciesCatalog(tempCatalog(wrapperJson))
        catalog.load()

        assertEquals(2, catalog.totalCount())
        val clownfish = catalog.byKey("amphiprion_ocellaris")
        assertNotNull(clownfish)
        assertEquals("Amphiprion ocellaris", clownfish!!.scientificName)
        assertEquals("Clown anemonefish", clownfish.commonNames["en"])
        assertEquals(listOf(84, 85, 87, 88), clownfish.ecoregions)
        assertEquals(397008L, clownfish.aphiaId)
    }

    @Test
    fun handlesNullableFields() {
        val catalog = SpeciesCatalog(tempCatalog(wrapperJson))
        catalog.load()

        val octopus = catalog.byKey("octopus_vulgaris")
        assertNotNull(octopus)
        assertEquals(140605L, octopus!!.aphiaId)  // aphiaId present
        assertNull(octopus.iucnStatus)            // explicit null in JSON
        assertEquals("Common octopus", octopus.commonNames["en"])
    }

    @Test
    fun missingKeyReturnsNull() {
        val catalog = SpeciesCatalog(tempCatalog(wrapperJson))
        catalog.load()
        assertNull(catalog.byKey("nonexistent_species"))
    }
}
