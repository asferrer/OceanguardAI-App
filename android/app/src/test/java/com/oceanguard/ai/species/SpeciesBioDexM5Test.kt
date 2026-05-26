package com.oceanguard.ai.species

import android.graphics.Bitmap
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.species.SampleSpeciesData
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.data.species.SpeciesObservationDao
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesDexDao
import com.oceanguard.ai.inference.species.GeoFilterSensitivity
import com.oceanguard.ai.inference.species.GeoMatchLevel
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.inference.species.SpeciesConfidence
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex.Companion.ProtoSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File

/**
 * M5 milestone unit tests.
 *
 * (a) [SpeciesReferenceIndex.Companion.inMemory] + [SpeciesReferenceIndex.search]
 *     returns the correct top-1 for a query aligned with a prototype.
 *
 * (b) [SampleSpeciesData.buildIndex] + [SampleSpeciesData.SampleQueryEmbedder]
 *     produces a catalogued match with cosine ≈ 1.0 for a mock Bitmap.
 *
 * (c) [SpeciesCollectionRepository.toObservation] maps [IdentificationResult]
 *     fields to [SpeciesObservation] correctly (pure function, no Room needed).
 *
 * All tests run on plain JVM (no Android instrumentation).
 */
class SpeciesBioDexM5Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -----------------------------------------------------------------------
    // (a) SpeciesReferenceIndex.inMemory + search
    // -----------------------------------------------------------------------

    /**
     * [inMemory] must produce an index that is immediately ready for [search].
     * A query spike at dim=10 must return the prototype seeded at dim=10 as top-1.
     */
    @Test
    fun `inMemory index search returns correct top-1 for aligned query`() {
        val catalog = mapOf(
            "sp_X" to makeEntry("sp_X", 10L, "Xenia xenia"),
            "sp_Y" to makeEntry("sp_Y", 20L, "Yrsa yellowfin"),
        )
        val seeds = listOf(
            ProtoSeed("sp_X", "Xenia xenia",       spikeVector(10)),
            ProtoSeed("sp_Y", "Yrsa yellowfin",    spikeVector(50)),
        )
        val index = SpeciesReferenceIndex.inMemory(catalog, seeds)

        // Query perfectly aligned with sp_X (spike at dim 10)
        val results = index.search(
            emb         = spikeVector(10),
            region      = null,
            topK        = 2,
            sensitivity = GeoFilterSensitivity.OFF,
        )

        assertEquals("Expected 2 results", 2, results.size)
        assertEquals("Top-1 must be sp_X", "sp_X", results[0].speciesKey)
        assertEquals("Top-1 cosine must be 1.0", 1.0f, results[0].cosineScore, 1e-5f)
        assertEquals("Top-1 scientificName from catalog", "Xenia xenia", results[0].scientificName)
        assertEquals("Top-2 must be sp_Y", "sp_Y", results[1].speciesKey)
    }

    /** Orthogonal query (spike at a dim not used by any seed) yields low scores. */
    @Test
    fun `inMemory search for orthogonal query returns zero cosine for all entries`() {
        val catalog = mapOf("sp_Z" to makeEntry("sp_Z", 30L, "Zoanthus sp"))
        val seeds = listOf(ProtoSeed("sp_Z", "Zoanthus sp", spikeVector(0)))
        val index = SpeciesReferenceIndex.inMemory(catalog, seeds)

        val results = index.search(spikeVector(1), region = null, topK = 1)
        assertEquals(1, results.size)
        assertEquals(0.0f, results[0].cosineScore, 1e-5f)
    }

    /** [inMemory] must reject seeds whose vector dimension is wrong. */
    @Test(expected = IllegalArgumentException::class)
    fun `inMemory throws when vector dim is wrong`() {
        val catalog = mapOf("sp_W" to makeEntry("sp_W", 40L, "Wrong dim"))
        val badSeed = ProtoSeed("sp_W", "Wrong dim", FloatArray(10) { 0.1f })
        SpeciesReferenceIndex.inMemory(catalog, listOf(badSeed))
    }

    // -----------------------------------------------------------------------
    // (b) SampleSpeciesData.buildIndex + SampleQueryEmbedder
    // -----------------------------------------------------------------------

    /**
     * [SampleSpeciesData.buildIndex] returns an index with 4 species.
     * [SampleQueryEmbedder] must produce a match with cosine ≈ 1.0 for any
     * mock Bitmap (spike always aligns with one of the 4 prototypes).
     */
    @Test
    fun `SampleSpeciesData buildIndex produces catalogued match with cosine 1`() = runBlocking {
        val index    = SampleSpeciesData.buildIndex()
        val embedder = SampleSpeciesData.embedder()

        assertTrue("Embedder must be ready", embedder.isReady)

        val bitmap  = mock(Bitmap::class.java)
        val query   = embedder.embed(bitmap)

        assertEquals(
            "Query vector must have dimension 512",
            SpeciesReferenceIndex.EMBEDDING_DIM,
            query.size,
        )

        val results = index.search(query, region = null, topK = 1, sensitivity = GeoFilterSensitivity.OFF)

        assertEquals("Expected exactly 1 result", 1, results.size)
        val top1 = results[0]
        assertEquals("Top-1 cosine must be 1.0 (spike alignment)", 1.0f, top1.cosineScore, 1e-5f)

        // Verify the matched species key is one of the 4 catalogued ones
        val validKeys = SampleSpeciesData.catalogEntries().map { it.speciesKey }.toSet()
        assertTrue(
            "Matched key ${top1.speciesKey} must be in the demo catalog",
            top1.speciesKey in validKeys,
        )
    }

    /**
     * The [SampleSpeciesData.SampleQueryEmbedder] selects the spike dimension
     * from abs(bitmap.hashCode()) % 4, so two Mockito mocks with different
     * identities may hit different or the same prototype.  The important
     * guarantee is that the matched key is always catalogued.
     */
    @Test
    fun `SampleSpeciesData embedder always produces a catalogued top-1`() = runBlocking {
        val index    = SampleSpeciesData.buildIndex()
        val embedder = SampleSpeciesData.embedder()
        val validKeys = SampleSpeciesData.catalogEntries().map { it.speciesKey }.toSet()

        repeat(4) { iteration ->
            val bitmap  = mock(Bitmap::class.java)
            val query   = embedder.embed(bitmap)
            val results = index.search(query, region = null, topK = 1, sensitivity = GeoFilterSensitivity.OFF)
            assertNotNull("Result must not be null (iteration $iteration)", results.firstOrNull())
            assertTrue(
                "Result must be catalogued (iteration $iteration)",
                results[0].speciesKey in validKeys,
            )
        }
    }

    // -----------------------------------------------------------------------
    // (c) SpeciesCollectionRepository.toObservation mapping
    // -----------------------------------------------------------------------

    /**
     * [toObservation] must correctly map every field of [IdentificationResult]
     * to the corresponding [SpeciesObservation] field without calling any DAO.
     */
    @Test
    fun `toObservation maps IdentificationResult fields correctly`() {
        val repo = buildRepositoryWithStubDaos()
        val bbox = BoundingBox(10f, 20f, 100f, 80f)
        val location = Location(41.3851, 2.1734)

        val result = IdentificationResult(
            speciesKey     = "137205",
            scientificName = "Caretta caretta",
            idSource       = SpeciesIdSource.RAG_CORE,
            cosineScore    = 0.97f,
            confidence     = 0.93f,
            vlmDescription = null,
            bbox           = bbox,
            geoMatchLevel  = GeoMatchLevel.COSMOPOLITAN.name,
            outOfRange     = false,
            uncatalogued   = false,
            freeLabel      = null,
        )

        val obs = repo.toObservation(result, "content://img/1", "content://thumb/1", location)

        assertEquals(137205L, obs.aphiaId)
        assertEquals("Caretta caretta", obs.scientificName)
        assertEquals(SpeciesIdSource.RAG_CORE.name, obs.idSource)
        assertEquals(0.97f, obs.cosineScore, 1e-5f)
        assertEquals(0.93f, obs.confidence, 1e-5f)
        assertEquals(bbox, obs.bbox)
        assertEquals(GeoMatchLevel.COSMOPOLITAN.name, obs.geoMatchLevel)
        assertEquals(false, obs.outOfRange)
        assertEquals(false, obs.uncatalogued)
        assertEquals("content://img/1", obs.imageUri)
        assertEquals("content://thumb/1", obs.thumbnailUri)
        assertEquals(location, obs.location)
    }

    /** Uncatalogued results (uncat: prefix) must have aphiaId = null. */
    @Test
    fun `toObservation sets aphiaId null for uncat speciesKey`() {
        val repo = buildRepositoryWithStubDaos()
        val result = IdentificationResult(
            speciesKey     = "uncat:moon_jelly",
            scientificName = "Unknown jellyfish",
            idSource       = SpeciesIdSource.VLM_OPENVOCAB,
            cosineScore    = 0f,
            confidence     = 0f,
            uncatalogued   = true,
        )
        val obs = repo.toObservation(result, "content://img/2", null, null)

        assertEquals(null, obs.aphiaId)
        assertEquals(true, obs.uncatalogued)
        assertEquals(null, obs.thumbnailUri)
        assertEquals(null, obs.location)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun spikeVector(dim: Int): FloatArray {
        val v = FloatArray(SpeciesReferenceIndex.EMBEDDING_DIM)
        v[dim] = 1.0f
        return v
    }

    private fun makeEntry(key: String, aphiaId: Long, sciName: String) = SpeciesCatalogEntry(
        speciesKey     = key,
        aphiaId        = aphiaId,
        scientificName = sciName,
        commonNames    = mapOf("en" to sciName),
        spritePath     = null,
        ecoregions     = emptyList(),
        cosmopolitan   = false,
    )

    /**
     * Build a [SpeciesCollectionRepository] with stub DAO doubles.
     *
     * Only [toObservation] is exercised here, so the DAOs are never called.
     * Using minimal anonymous implementations avoids a Room dependency in tests.
     */
    private fun buildRepositoryWithStubDaos(): SpeciesCollectionRepository {
        val stubObsDao = object : SpeciesObservationDao {
            override suspend fun insert(observation: SpeciesObservation) = 1L
            override fun getAll() = flowOf<List<SpeciesObservation>>(emptyList())
            override suspend fun getById(id: Long): SpeciesObservation? = null
            override fun getBySessionId(sessionId: Long) = flowOf<List<SpeciesObservation>>(emptyList())
            override fun getByAphiaId(aphiaIdStr: String) = flowOf<List<SpeciesObservation>>(emptyList())
            override fun getTotalCount(): Flow<Int> = flowOf(0)
            override suspend fun deleteById(id: Long) {}
            override suspend fun getAllAscending(): List<SpeciesObservation> = emptyList()
        }
        val stubDexDao = object : SpeciesDexDao {
            override fun getAll() = flowOf<List<com.oceanguard.ai.data.species.SpeciesDexEntry>>(emptyList())
            override suspend fun getByKey(key: String) = null
            override fun getDiscoveredCount(): Flow<Int> = flowOf(0)
            override fun getFavoriteCount(): Flow<Int> = flowOf(0)
            override fun getTotalObservations(): Flow<Int> = flowOf(0)
            override suspend fun upsert(entry: com.oceanguard.ai.data.species.SpeciesDexEntry) {}
            override suspend fun setFavorite(key: String, fav: Boolean) {}
            override suspend fun deleteAll() {}
            override suspend fun deleteByKey(key: String) {}
        }
        // Catalog file path is irrelevant here; load() is never called in these tests.
        val catalog = SpeciesCatalog(File("stub_not_used_catalog.json"))
        return SpeciesCollectionRepository(stubObsDao, stubDexDao, catalog)
    }
}
