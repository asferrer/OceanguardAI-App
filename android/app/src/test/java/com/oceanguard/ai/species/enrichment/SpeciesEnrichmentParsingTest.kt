package com.oceanguard.ai.species.enrichment

import com.google.gson.JsonParser
import com.oceanguard.ai.data.species.enrichment.GbifClient
import com.oceanguard.ai.data.species.enrichment.INaturalistClient
import com.oceanguard.ai.data.species.enrichment.WormsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the JSON parsing functions of each API client.
 *
 * No network calls are made — the JSON fixtures are embedded in the test itself.
 * Each `parse*` function is internal and accepts raw JSON strings or Gson objects,
 * making them fully testable without Android instrumentation.
 */
class SpeciesEnrichmentParsingTest {

    // -----------------------------------------------------------------------
    // WoRMS parsing
    // -----------------------------------------------------------------------

    private val wormsClient = WormsClient()

    /** Full WoRMS AphiaRecord JSON as returned by /AphiaRecordByAphiaID/137205 */
    private val wormsFullJson = """
        {
          "AphiaID": 137205,
          "scientificname": "Caretta caretta",
          "authority": "(Linnaeus, 1758)",
          "rank": "Species",
          "status": "accepted",
          "isMarine": 1,
          "isBrackish": 0,
          "isFreshwater": 0,
          "isTerrestrial": 0
        }
    """.trimIndent()

    @Test
    fun `parseRecord returns all fields for a full WoRMS response`() {
        val obj = JsonParser.parseString(wormsFullJson).asJsonObject
        val record = wormsClient.parseRecord(obj)

        assertEquals(137205L, record.aphiaId)
        assertEquals("Caretta caretta", record.scientificName)
        assertEquals("(Linnaeus, 1758)", record.authority)
        assertEquals("Species", record.rank)
        assertEquals("accepted", record.status)
        assertEquals(true, record.isMarine)
    }

    @Test
    fun `parseRecord tolerates null authority and rank`() {
        val json = """{"AphiaID":999,"scientificname":"Unknown sp","isMarine":0}"""
        val obj = JsonParser.parseString(json).asJsonObject
        val record = wormsClient.parseRecord(obj)

        assertEquals(999L, record.aphiaId)
        assertEquals("Unknown sp", record.scientificName)
        assertNull(record.authority)
        assertNull(record.rank)
        assertEquals(false, record.isMarine)
    }

    @Test
    fun `parseRecord handles explicit JSON null for authority`() {
        val json = """{"AphiaID":42,"scientificname":"Foo bar","authority":null,"rank":null,"isMarine":1}"""
        val obj = JsonParser.parseString(json).asJsonObject
        val record = wormsClient.parseRecord(obj)

        assertNull(record.authority)
        assertNull(record.rank)
        assertEquals(true, record.isMarine)
    }

    // -----------------------------------------------------------------------
    // GBIF parsing
    // -----------------------------------------------------------------------

    private val gbifClient = GbifClient()

    /** Minimal /species/match response for Caretta caretta */
    private val gbifMatchJson = """
        {
          "usageKey": 2442071,
          "canonicalName": "Caretta caretta",
          "matchType": "EXACT",
          "confidence": 99,
          "status": "ACCEPTED",
          "rank": "SPECIES"
        }
    """.trimIndent()

    @Test
    fun `parseMatch returns correct fields from GBIF species match JSON`() {
        val record = gbifClient.parseMatch(gbifMatchJson)

        assertEquals(2442071, record.usageKey)
        assertEquals("Caretta caretta", record.canonicalName)
        assertEquals("EXACT", record.matchType)
        assertNull("distributionSummary must be null from parseMatch", record.distributionSummary)
    }

    @Test
    fun `parseMatch returns zero usageKey when field absent`() {
        val json = """{"matchType":"NONE","canonicalName":null}"""
        val record = gbifClient.parseMatch(json)

        assertEquals(0, record.usageKey)
        assertNull(record.canonicalName)
        assertEquals("NONE", record.matchType)
    }

    // -----------------------------------------------------------------------
    // iNaturalist parsing
    // -----------------------------------------------------------------------

    private val inatClient = INaturalistClient()

    /** Realistic /taxa response with photo and conservation_status */
    private val inatTaxaJson = """
        {
          "total_results": 1,
          "results": [
            {
              "id": 46159,
              "preferred_common_name": "Loggerhead Sea Turtle",
              "default_photo": {
                "id": 12345,
                "medium_url": "https://static.inaturalist.org/photos/12345/medium.jpg",
                "attribution": "(c) iNaturalist"
              },
              "conservation_status": {
                "status_name": "vulnerable",
                "iucn": 10
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parseTaxa returns all fields including photo and IUCN code`() {
        val record = inatClient.parseTaxa(inatTaxaJson)

        assertNotNull(record)
        assertEquals(46159, record!!.taxonId)
        assertEquals("Loggerhead Sea Turtle", record.preferredCommonName)
        assertEquals(
            "https://static.inaturalist.org/photos/12345/medium.jpg",
            record.representativePhotoUrl,
        )
        assertEquals("VU", record.iucnStatusCode)
    }

    @Test
    fun `parseTaxa returns null when results array is empty`() {
        val json = """{"total_results":0,"results":[]}"""
        val record = inatClient.parseTaxa(json)
        assertNull(record)
    }

    @Test
    fun `parseTaxa tolerates missing default_photo and conservation_status`() {
        val json = """
            {"total_results":1,"results":[{"id":99,"preferred_common_name":"Spotted seal"}]}
        """.trimIndent()
        val record = inatClient.parseTaxa(json)

        assertNotNull(record)
        assertEquals(99, record!!.taxonId)
        assertEquals("Spotted seal", record.preferredCommonName)
        assertNull(record.representativePhotoUrl)
        assertNull(record.iucnStatusCode)
    }

    @Test
    fun `parseTaxa maps critically_endangered to CR`() {
        val json = """
            {"total_results":1,"results":[{
              "id":1,"preferred_common_name":"CR species",
              "conservation_status":{"status_name":"critically_endangered"}
            }]}
        """.trimIndent()
        val record = inatClient.parseTaxa(json)!!
        assertEquals("CR", record.iucnStatusCode)
    }
}
