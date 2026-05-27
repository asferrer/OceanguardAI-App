package com.oceanguard.ai.species.enrichment

import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.species.enrichment.GbifClient
import com.oceanguard.ai.data.species.enrichment.GbifRecord
import com.oceanguard.ai.data.species.enrichment.INaturalistClient
import com.oceanguard.ai.data.species.enrichment.INaturalistRecord
import com.oceanguard.ai.data.species.enrichment.SpeciesEnrichment
import com.oceanguard.ai.data.species.enrichment.SpeciesEnrichmentRepository
import com.oceanguard.ai.data.species.enrichment.WormsClient
import com.oceanguard.ai.data.species.enrichment.WormsRecord
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

/**
 * Unit tests for [SpeciesEnrichmentRepository].
 *
 * All tests run on plain JVM — no Android instrumentation, no real DataStore,
 * no real network. The [SettingsRepository] and API clients are mocked with
 * Mockito (core only, no mockito-kotlin) so network isolation is complete.
 */
class SpeciesEnrichmentRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun mockSettings(enrichmentEnabled: Boolean): SettingsRepository {
        val settings = mock(SettingsRepository::class.java)
        whenever(settings.speciesOnlineEnrichmentEnabled)
            .thenReturn(flowOf(enrichmentEnabled))
        return settings
    }

    private fun stubWormsRecord(aphiaId: Long = 137205L) = WormsRecord(
        aphiaId        = aphiaId,
        scientificName = "Caretta caretta",
        authority      = "(Linnaeus, 1758)",
        rank           = "Species",
        isMarine       = true,
        status         = "accepted",
    )

    private fun stubGbifRecord() = GbifRecord(
        usageKey            = 2442071,
        canonicalName       = "Caretta caretta",
        distributionSummary = "US, ES, GR, TR, MX",
        matchType           = "EXACT",
    )

    private fun stubINatRecord() = INaturalistRecord(
        taxonId              = 46159,
        preferredCommonName  = "Loggerhead Sea Turtle",
        representativePhotoUrl = "https://static.inaturalist.org/photos/12345/medium.jpg",
        iucnStatusCode       = "VU",
    )

    private fun buildRepo(
        enrichmentEnabled: Boolean,
        worms: WormsClient = mock(WormsClient::class.java),
        gbif: GbifClient = mock(GbifClient::class.java),
        inat: INaturalistClient = mock(INaturalistClient::class.java),
    ): SpeciesEnrichmentRepository {
        val settings = mockSettings(enrichmentEnabled)
        val context  = mock(android.content.Context::class.java)
        whenever(context.filesDir).thenReturn(tempFolder.root)
        return SpeciesEnrichmentRepository(context, settings, worms, gbif, inat)
    }

    /** Returns a fully-stubbed repo with all three clients returning valid data. */
    private fun buildHappyPathRepo(): SpeciesEnrichmentRepository {
        val worms = mock(WormsClient::class.java)
        val gbif  = mock(GbifClient::class.java)
        val inat  = mock(INaturalistClient::class.java)
        runBlocking {
            whenever(worms.byAphiaId(137205L)).thenReturn(stubWormsRecord())
            whenever(worms.byName("Caretta caretta")).thenReturn(stubWormsRecord())
            whenever(gbif.byName("Caretta caretta")).thenReturn(stubGbifRecord())
            whenever(inat.byName("Caretta caretta")).thenReturn(stubINatRecord())
        }
        return buildRepo(enrichmentEnabled = true, worms = worms, gbif = gbif, inat = inat)
    }

    // -----------------------------------------------------------------------
    // (1) Flag-off guard — MUST return null without any network call
    // -----------------------------------------------------------------------

    @Test
    fun `enrich returns null immediately when online enrichment is disabled`() { runBlocking {
        val worms = mock(WormsClient::class.java)
        val gbif  = mock(GbifClient::class.java)
        val inat  = mock(INaturalistClient::class.java)
        val repo  = buildRepo(enrichmentEnabled = false, worms = worms, gbif = gbif, inat = inat)

        val result = repo.enrich(aphiaId = 137205L, scientificName = "Caretta caretta")

        assertNull("Must return null when enrichment is disabled", result)
        verify(worms, never()).byAphiaId(137205L)
        verify(worms, never()).byName("Caretta caretta")
        verify(gbif,  never()).byName("Caretta caretta")
        verify(inat,  never()).byName("Caretta caretta")
    } }

    // -----------------------------------------------------------------------
    // (2) Happy path — all APIs return data, result is merged correctly
    // -----------------------------------------------------------------------

    @Test
    fun `enrich merges data from all three APIs when flag is on`() = runBlocking {
        val result = buildHappyPathRepo().enrich(137205L, "Caretta caretta")

        assertNotNull("Result must not be null when flag is on and APIs succeed", result)
        assertEquals(137205L, result!!.aphiaId)
        assertEquals("Caretta caretta", result.acceptedName)
        assertEquals("(Linnaeus, 1758)", result.authority)
        assertEquals("Species", result.rank)
        assertEquals("VU", result.iucnStatus)
        assertEquals("US, ES, GR, TR, MX", result.distributionSummary)
        assertEquals(
            "https://static.inaturalist.org/photos/12345/medium.jpg",
            result.representativePhotoUrl,
        )
        assertTrue("Sources must include WoRMS",       "WoRMS"       in result.sources)
        assertTrue("Sources must include GBIF",        "GBIF"        in result.sources)
        assertTrue("Sources must include iNaturalist", "iNaturalist" in result.sources)
        assertTrue("fetchedAt must be positive", result.fetchedAt > 0L)
    }

    // -----------------------------------------------------------------------
    // (3) Cache round-trip — serialise → write → read → deserialise
    // -----------------------------------------------------------------------

    @Test
    fun `serializeEnrichment and deserializeEnrichment round-trip correctly`() {
        val repo = buildRepo(enrichmentEnabled = false)
        val original = SpeciesEnrichment(
            aphiaId                = 137205L,
            acceptedName           = "Caretta caretta",
            authority              = "(Linnaeus, 1758)",
            rank                   = "Species",
            iucnStatus             = "VU",
            distributionSummary    = "US, ES, GR",
            representativePhotoUrl = "https://example.com/photo.jpg",
            sources                = listOf("WoRMS", "GBIF", "iNaturalist"),
            fetchedAt              = 1_716_000_000_000L,
        )

        val json     = repo.serializeEnrichment(original)
        val restored = repo.deserializeEnrichment(json)

        assertEquals(original, restored)
    }

    @Test
    fun `round-trip handles all null optional fields`() {
        val repo = buildRepo(enrichmentEnabled = false)
        val original = SpeciesEnrichment(
            aphiaId                = null,
            acceptedName           = null,
            authority              = null,
            rank                   = null,
            iucnStatus             = null,
            distributionSummary    = null,
            representativePhotoUrl = null,
            sources                = listOf("WoRMS"),
            fetchedAt              = 1_716_000_000_000L,
        )

        val restored = repo.deserializeEnrichment(repo.serializeEnrichment(original))
        assertEquals(original, restored)
    }

    // -----------------------------------------------------------------------
    // (4) Cache hit — second call must not trigger network again
    // -----------------------------------------------------------------------

    @Test
    fun `second enrich call returns cached result without calling APIs again`() { runBlocking {
        val worms = mock(WormsClient::class.java)
        val gbif  = mock(GbifClient::class.java)
        val inat  = mock(INaturalistClient::class.java)
        runBlocking {
            whenever(worms.byAphiaId(137205L)).thenReturn(stubWormsRecord())
            whenever(worms.byName("Caretta caretta")).thenReturn(stubWormsRecord())
            whenever(gbif.byName("Caretta caretta")).thenReturn(stubGbifRecord())
            whenever(inat.byName("Caretta caretta")).thenReturn(stubINatRecord())
        }
        val repo = buildRepo(enrichmentEnabled = true, worms = worms, gbif = gbif, inat = inat)

        val first  = repo.enrich(137205L, "Caretta caretta")
        val second = repo.enrich(137205L, "Caretta caretta")

        assertNotNull(first)
        assertEquals(first, second)

        // Each client called exactly once despite two enrich() invocations
        verify(worms).byAphiaId(137205L)
        verify(gbif).byName("Caretta caretta")
        verify(inat).byName("Caretta caretta")
    } }

    // -----------------------------------------------------------------------
    // (5) Cache key derivation
    // -----------------------------------------------------------------------

    @Test
    fun `cacheKey uses aphia_ prefix when aphiaId is provided`() {
        val repo = buildRepo(enrichmentEnabled = false)
        assertEquals("aphia_137205", repo.cacheKey(137205L, "Caretta caretta"))
    }

    @Test
    fun `cacheKey falls back to name_ prefix when aphiaId is null`() {
        val repo = buildRepo(enrichmentEnabled = false)
        val key = repo.cacheKey(null, "Caretta caretta")
        assertTrue("Key must start with name_", key.startsWith("name_"))
        assertTrue("Key must not contain spaces", ' ' !in key)
    }

    @Test
    fun `cacheKey falls back to name_ prefix when aphiaId is zero`() {
        val repo = buildRepo(enrichmentEnabled = false)
        val key = repo.cacheKey(0L, "Tursiops truncatus")
        assertTrue(key.startsWith("name_"))
    }

    // -----------------------------------------------------------------------
    // (6) Partial API failure — result still returned from available sources
    // -----------------------------------------------------------------------

    @Test
    fun `enrich returns partial result when GBIF and iNat fail`() = runBlocking {
        val worms = mock(WormsClient::class.java)
        val gbif  = mock(GbifClient::class.java)
        val inat  = mock(INaturalistClient::class.java)
        runBlocking {
            whenever(worms.byAphiaId(137205L)).thenReturn(stubWormsRecord())
            whenever(gbif.byName("Caretta caretta")).thenReturn(null)
            whenever(inat.byName("Caretta caretta")).thenReturn(null)
        }
        val repo = buildRepo(enrichmentEnabled = true, worms = worms, gbif = gbif, inat = inat)

        val result = repo.enrich(137205L, "Caretta caretta")

        assertNotNull(result)
        assertEquals(listOf("WoRMS"), result!!.sources)
        assertNull(result.distributionSummary)
        assertNull(result.representativePhotoUrl)
        assertNull(result.iucnStatus)
    }

    @Test
    fun `enrich returns null when all three API calls fail`() = runBlocking {
        val worms = mock(WormsClient::class.java)
        val gbif  = mock(GbifClient::class.java)
        val inat  = mock(INaturalistClient::class.java)
        runBlocking {
            whenever(worms.byAphiaId(137205L)).thenReturn(null)
            whenever(worms.byName("Caretta caretta")).thenReturn(null)
            whenever(gbif.byName("Caretta caretta")).thenReturn(null)
            whenever(inat.byName("Caretta caretta")).thenReturn(null)
        }
        val repo = buildRepo(enrichmentEnabled = true, worms = worms, gbif = gbif, inat = inat)

        val result = repo.enrich(137205L, "Caretta caretta")
        assertNull(result)
    }
}
