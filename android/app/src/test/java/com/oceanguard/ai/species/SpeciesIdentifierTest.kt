package com.oceanguard.ai.species

import android.graphics.Bitmap
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.inference.species.CropProvider
import com.oceanguard.ai.inference.species.DescriberPort
import com.oceanguard.ai.inference.species.FakeSpeciesEmbedder
import com.oceanguard.ai.inference.species.SpeciesEmbedder
import com.oceanguard.ai.inference.species.GeoFilterSensitivity
import com.oceanguard.ai.inference.species.GeoMatchLevel
import com.oceanguard.ai.inference.species.MarineRegion
import com.oceanguard.ai.inference.species.OrganismCrop
import com.oceanguard.ai.inference.species.RegionResolverPort
import com.oceanguard.ai.inference.species.SpeciesConfidence
import com.oceanguard.ai.inference.species.SpeciesIdResult
import com.oceanguard.ai.inference.species.SpeciesIdentifier
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [SpeciesIdentifier].
 *
 * Tests the identification decision logic (RAG_CORE / RAG_TENTATIVE /
 * VLM_OPENVOCAB) using:
 *  - [FakeSpeciesEmbedder] for controlled, deterministic embeddings.
 *  - [SyntheticSpeciesIndex] builder (adapted from [SpeciesReferenceIndexTest]).
 *  - [FakeCropProvider] for injecting pre-built crops without Android SDK.
 *  - [RecordingDescriberPort] to verify when and how the describer is called.
 *
 * All tests run on plain JVM (no Android instrumentation needed).
 * Android SDK calls within [FakeCropProvider] / [RecordingDescriberPort] are
 * kept to null — the stub Bitmap is never dereferenced inside these doubles.
 */
class SpeciesIdentifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -----------------------------------------------------------------------
    // Thresholds that produce predictable decisions in tests
    //   cosine = 1.0 → calibrate(1.0) ≈ 0.9985 > tauHigh=0.60  (RAG_CORE)
    //   cosine = 0.0 → calibrate(0.0) ≈ 0.029  < tauLow=0.30   (unknown)
    //   cosine = mid → calibrate(mid) ≈ 0.5     in [tauLow,tauHigh) (RAG_TENTATIVE)
    // -----------------------------------------------------------------------
    private val conf = SpeciesConfidence(
        plattA  = 10.0f,
        plattB  = -3.5f,
        tauHigh = 0.60f,
        tauLow  = 0.30f,
        delta   = 0.05f,
    )

    private lateinit var catalog: Map<String, SpeciesCatalogEntry>
    private lateinit var indexFile: File

    @Before
    fun setUp() {
        // Three species with spike vectors at distinct dimensions:
        //   sp_A → dim 0 (perfect cosine = 1.0 when query is spikeVector(0))
        //   sp_B → dim 100
        //   sp_C → dim 200 (cosmopolitan)
        catalog = mapOf(
            "sp_A" to SpeciesCatalogEntry(
                speciesKey     = "sp_A", aphiaId = 1L,
                scientificName = "Species albus",
                commonNames    = mapOf("en" to "White Species"),
                spritePath     = null,
                ecoregions     = listOf(10, 20),
                cosmopolitan   = false,
            ),
            "sp_B" to SpeciesCatalogEntry(
                speciesKey     = "sp_B", aphiaId = 2L,
                scientificName = "Species beta",
                commonNames    = mapOf("en" to "Beta Species"),
                spritePath     = null,
                ecoregions     = listOf(30),
                cosmopolitan   = false,
            ),
            "sp_C" to SpeciesCatalogEntry(
                speciesKey     = "sp_C", aphiaId = 3L,
                scientificName = "Species cosmopolitanus",
                commonNames    = mapOf("en" to "Cosmopolitan"),
                spritePath     = null,
                ecoregions     = emptyList(),
                cosmopolitan   = true,
            ),
        )

        // Index: sp_A → spike@0, sp_B → spike@100, sp_C → spike@200
        indexFile = SpeciesReferenceIndexTest.buildIndexFile(
            tempFolder,
            rows = listOf(
                Pair(0, "sp_A"),
                Pair(100, "sp_B"),
                Pair(200, "sp_C"),
            ),
            scientificNames = mapOf(
                "sp_A" to "Species albus",
                "sp_B" to "Species beta",
                "sp_C" to "Species cosmopolitanus",
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Helper: build the index with custom Platt parameters
    // -----------------------------------------------------------------------

    private fun buildIndex(): SpeciesReferenceIndex {
        val idx = SpeciesReferenceIndex(catalog, conf)
        idx.load(indexFile)
        return idx
    }

    private fun buildIdentifier(
        embedder: SpeciesEmbedder,
        describer: DescriberPort = RecordingDescriberPort(),
        locator: CropProvider? = null,
    ): SpeciesIdentifier {
        val effectiveLocator = locator ?: FixedEmbeddingCropProvider(embedder)
        return SpeciesIdentifier(
            embedder   = embedder,
            index      = buildIndex(),
            resolver   = null,
            confidence = conf,
            describer  = describer,
            locator    = effectiveLocator,
        )
    }

    // -----------------------------------------------------------------------
    // Tests: RAG_CORE path
    // -----------------------------------------------------------------------

    /**
     * When the query perfectly aligns with sp_A (cosine = 1.0, margin >> δ),
     * idSource must be RAG_CORE and the describer must NOT be called.
     */
    @Test
    fun `RAG_CORE when top-1 cosine=1 and large margin`() = runBlocking {
        val describer = RecordingDescriberPort()
        // FakeSpeciesEmbedder with seed=0 produces a vector near spike@0
        // but not exactly; use a custom embedder that returns spikeVector(0) exactly.
        val embedder = SpikeEmbedder(spikeIdx = 0)
        val sut = buildIdentifier(embedder, describer)

        val results = sut.identify(nullBitmap())
        assertEquals(1, results.size)
        assertEquals("sp_A", results[0].speciesKey)
        assertEquals(SpeciesIdSource.RAG_CORE, results[0].idSource)
        assertFalse("Describer must NOT be called for RAG_CORE", describer.wasCalledForConfirmation)
        assertFalse(describer.wasCalledForOpenVocab)
    }

    @Test
    fun `RAG_CORE result has no vlmDescription`() = runBlocking {
        val embedder = SpikeEmbedder(spikeIdx = 0)
        val sut = buildIdentifier(embedder)

        val result = sut.identify(nullBitmap()).first()
        assertNull("vlmDescription must be null for RAG_CORE", result.vlmDescription)
    }

    // -----------------------------------------------------------------------
    // Tests: RAG_TENTATIVE path
    // -----------------------------------------------------------------------

    /**
     * When cosine is mid-range (0.35 → calibrated ≈ 0.5, in [tauLow, tauHigh)),
     * idSource must be RAG_TENTATIVE and the describer's confirmOrDescribe
     * must be invoked.
     */
    @Test
    fun `RAG_TENTATIVE when cosine in mid range and describer is called`() = runBlocking {
        val describer = RecordingDescriberPort(
            onConfirmOrDescribe = { key, _ -> SpeciesIdResult(description = "Mid-range match", confirmedKey = key) },
        )
        // A mid-range cosine means the query vector is partially aligned with sp_A.
        // Use seed=0.35f / sqrt(512 dims) ≈ not a pure spike, cosine ~0.35 with sp_A.
        val embedder = PartialAlignmentEmbedder(targetSpikeIdx = 0, cosineTarget = 0.35f)
        val sut = buildIdentifier(embedder, describer)

        val results = sut.identify(nullBitmap())
        assertEquals(1, results.size)
        assertEquals(SpeciesIdSource.RAG_TENTATIVE, results[0].idSource)
        assertTrue("Describer confirmOrDescribe must be called", describer.wasCalledForConfirmation)
        assertNotNull("vlmDescription must be populated", results[0].vlmDescription)
    }

    @Test
    fun `RAG_TENTATIVE - VLM description is attached to result`() = runBlocking {
        val expectedDesc = "Definitely a white species."
        val describer = RecordingDescriberPort(
            onConfirmOrDescribe = { key, _ ->
                SpeciesIdResult(description = expectedDesc, confirmedKey = key)
            },
        )
        val embedder = PartialAlignmentEmbedder(targetSpikeIdx = 0, cosineTarget = 0.35f)
        val sut = buildIdentifier(embedder, describer)

        val result = sut.identify(nullBitmap()).first()
        assertEquals(expectedDesc, result.vlmDescription)
    }

    // -----------------------------------------------------------------------
    // Tests: VLM_OPENVOCAB path
    // -----------------------------------------------------------------------

    /**
     * When cosine is very low (< tauLow), idSource must be VLM_OPENVOCAB
     * and describeOpenVocab must be invoked.
     */
    @Test
    fun `VLM_OPENVOCAB when cosine is below tauLow`() = runBlocking {
        val describer = RecordingDescriberPort(
            onOpenVocab = { SpeciesIdResult(description = "Probably a jellyfish.", freeLabel = "Aurelia aurita") },
        )
        // A query perfectly orthogonal to all index prototypes: spike@300
        // (no prototype there → cosine = 0 for all species → score below tauLow).
        val embedder = SpikeEmbedder(spikeIdx = 300)
        val sut = buildIdentifier(embedder, describer)

        val results = sut.identify(nullBitmap())
        assertEquals(1, results.size)
        assertEquals(SpeciesIdSource.VLM_OPENVOCAB, results[0].idSource)
        assertTrue("uncatalogued flag must be true", results[0].uncatalogued)
        assertTrue("Describer open-vocab must be called", describer.wasCalledForOpenVocab)
        assertFalse(describer.wasCalledForConfirmation)
    }

    @Test
    fun `VLM_OPENVOCAB - freeLabel and description propagated from describer`() = runBlocking {
        val describer = RecordingDescriberPort(
            onOpenVocab = {
                SpeciesIdResult(description = "Jellyfish desc.", freeLabel = "Aurelia aurita")
            },
        )
        val embedder = SpikeEmbedder(spikeIdx = 300)
        val sut = buildIdentifier(embedder, describer)

        val result = sut.identify(nullBitmap()).first()
        assertEquals("Aurelia aurita", result.freeLabel)
        assertEquals("Jellyfish desc.", result.vlmDescription)
        assertEquals("Aurelia aurita", result.scientificName)
        assertTrue(result.speciesKey.startsWith("uncat:"))
    }

    @Test
    fun `VLM_OPENVOCAB has zero cosine and confidence`() = runBlocking {
        val embedder = SpikeEmbedder(spikeIdx = 300)
        val sut = buildIdentifier(embedder)

        val result = sut.identify(nullBitmap()).first()
        assertEquals(0f, result.cosineScore, 1e-6f)
        assertEquals(0f, result.confidence, 1e-6f)
    }

    // -----------------------------------------------------------------------
    // Tests: outOfRange and geoMatchLevel propagation
    // -----------------------------------------------------------------------

    /**
     * outOfRange must be true when the resolver returns a foreign region
     * (sp_A's ecoregions [10,20] are in realm 1; the resolved region is realm 99)
     * and the index relaxes to GLOBAL after the hard filter fails.
     *
     * Uses [FakeRegionResolverPort] to inject a non-null region without file I/O.
     */
    @Test
    fun `outOfRange true when resolved region is foreign and species relaxed to GLOBAL`() = runBlocking {
        // Foreign region: realm 99, no overlap with sp_A (realm 1) or sp_B (realm 2)
        val foreignRegion = MarineRegion(ecoregionId = 999, provinceId = 99, realmId = 99)
        // hierarchy: sp_A.ecoregions [10,20] → province 5, realm 1 (not 99 → REALM mismatch)
        val fakeResolver = FakeRegionResolverPort(
            fixedRegion = foreignRegion,
            hierarchyFn = { ecoId -> if (ecoId in listOf(10, 20)) Pair(5, 1) else null },
        )
        val embedder = SpikeEmbedder(spikeIdx = 0) // perfect sp_A alignment (cosine=1 > tauHigh)

        val sut = SpeciesIdentifier(
            embedder   = embedder,
            index      = buildIndex(),
            resolver   = fakeResolver,
            confidence = conf,
            describer  = RecordingDescriberPort(),
            locator    = FixedEmbeddingCropProvider(embedder),
        )
        val result = sut.identify(nullBitmap(), location = Location(0.0, 0.0)).first()

        // sp_A is found (cosine=1 beats all thresholds after relajación)
        assertEquals("sp_A", result.speciesKey)
        assertTrue("outOfRange must be true for a foreign-region match", result.outOfRange)
        assertEquals(GeoMatchLevel.GLOBAL.name, result.geoMatchLevel)
    }

    @Test
    fun `geoMatchLevel ECOREGION string propagated to IdentificationResult`() = runBlocking {
        // No resolver → region=null → geoMatchLevel defaults to GLOBAL in the index
        val embedder = SpikeEmbedder(spikeIdx = 0)
        val sut = buildIdentifier(embedder)

        val result = sut.identify(nullBitmap(), location = null).first()
        // With region=null, search uses GLOBAL (no filter) → all sp are GLOBAL level
        assertEquals(GeoMatchLevel.GLOBAL.name, result.geoMatchLevel)
        assertFalse("outOfRange must be false when region is null", result.outOfRange)
    }

    // -----------------------------------------------------------------------
    // Tests: embedder not ready → empty results
    // -----------------------------------------------------------------------

    @Test
    fun `returns empty list when embedder is not ready`() = runBlocking {
        val notReadyEmbedder = FakeSpeciesEmbedder() // isReady = true always
        // Wrap in a not-ready proxy:
        val notReady = object : com.oceanguard.ai.inference.species.SpeciesEmbedder {
            override val isReady = false
            override suspend fun embed(bitmap: Bitmap) =
                throw IllegalStateException("Not ready")
            override fun close() {}
        }
        val sut = SpeciesIdentifier(
            embedder  = notReady,
            index     = buildIndex(),
            describer = RecordingDescriberPort(),
            locator   = FixedEmbeddingCropProvider(FakeSpeciesEmbedder()),
        )
        val results = sut.identify(nullBitmap())
        assertTrue("Expected empty results when embedder not ready", results.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Tests: multi-crop behaviour
    // -----------------------------------------------------------------------

    @Test
    fun `multiple crops produce multiple results`() = runBlocking {
        val embedder = SpikeEmbedder(spikeIdx = 0)
        val twoCropProvider = object : CropProvider {
            override suspend fun locate(bitmap: Bitmap): List<OrganismCrop> = listOf(
                OrganismCrop(BoundingBox(0f, 0f, 10f, 10f), bitmap, 0.9f),
                OrganismCrop(BoundingBox(10f, 10f, 10f, 10f), bitmap, 0.8f),
            )
        }
        val sut = SpeciesIdentifier(
            embedder  = embedder,
            index     = buildIndex(),
            describer = RecordingDescriberPort(),
            locator   = twoCropProvider,
        )
        val results = sut.identify(nullBitmap())
        assertEquals("Two crops must produce two results", 2, results.size)
    }

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    /**
     * Returns a non-null mock [Bitmap]. Pixels are never read: the bitmap only
     * flows identify() -> CropProvider -> OrganismCrop -> embedder (which ignores
     * it), so a bare Mockito mock satisfies the non-null parameter checks.
     */
    private fun nullBitmap(): Bitmap = org.mockito.Mockito.mock(Bitmap::class.java)

    /**
     * [SpeciesEmbedder] that always returns spikeVector(spikeIdx).
     * Deterministic; never calls Bitmap pixel APIs.
     */
    private inner class SpikeEmbedder(private val spikeIdx: Int) :
        com.oceanguard.ai.inference.species.SpeciesEmbedder {

        override val isReady = true

        override suspend fun embed(bitmap: Bitmap) = embedSync()

        /** Synchronous embed — useful for direct index search in tests. */
        fun embedSync(): FloatArray = SpeciesReferenceIndexTest.spikeVector(spikeIdx)

        override fun close() {}
    }

    /**
     * [SpeciesEmbedder] that returns a vector with a partial dot-product
     * of [cosineTarget] toward [targetSpikeIdx] and the rest toward dim 0.
     *
     * Used to land the calibrated confidence in the RAG_TENTATIVE window.
     * The exact cosine achieved = cosineTarget (spike@targetSpikeIdx has unit norm).
     */
    private inner class PartialAlignmentEmbedder(
        private val targetSpikeIdx: Int,
        private val cosineTarget: Float,
    ) : com.oceanguard.ai.inference.species.SpeciesEmbedder {

        override val isReady = true

        override suspend fun embed(bitmap: Bitmap): FloatArray {
            // Construct: query = cosineTarget * spike[targetSpikeIdx]
            //                  + sqrt(1 - cosineTarget^2) * spike[targetSpikeIdx + 1]
            // (L2-norm = 1 by construction)
            val v = FloatArray(SpeciesReferenceIndex.EMBEDDING_DIM)
            val perp = targetSpikeIdx + 1  // orthogonal dim (must not collide with other spikes)
            v[targetSpikeIdx] = cosineTarget
            v[perp] = sqrt((1.0 - cosineTarget.toDouble() * cosineTarget.toDouble())).toFloat()
            // Already L2-normalised: ||v||^2 = cos^2 + sin^2 = 1
            return v
        }

        override fun close() {}
    }

    /**
     * [CropProvider] that returns a single crop whose bitmap equals the input
     * bitmap. The [embedder] is not used directly — [SpeciesIdentifier] calls
     * [embedder.embed] separately.
     */
    private inner class FixedEmbeddingCropProvider(
        @Suppress("UnusedPrivateMember") private val embedder: Any,
    ) : CropProvider {
        override suspend fun locate(bitmap: Bitmap): List<OrganismCrop> =
            listOf(OrganismCrop(BoundingBox(0f, 0f, 1f, 1f), bitmap, 1.0f))
    }

    /**
     * [DescriberPort] test double that records whether each method was called
     * and allows injection of custom responses.
     */
    private inner class RecordingDescriberPort(
        private val onConfirmOrDescribe: (suspend (key: String, name: String) -> SpeciesIdResult)? = null,
        private val onOpenVocab: (suspend () -> SpeciesIdResult)? = null,
    ) : DescriberPort {

        var wasCalledForConfirmation = false
            private set
        var wasCalledForOpenVocab = false
            private set
        var lastHypothesisKey: String? = null
            private set

        override suspend fun confirmOrDescribe(
            crop: Bitmap,
            hypothesisKey: String,
            hypothesisScientificName: String,
            region: MarineRegion?,
            language: String,
        ): SpeciesIdResult {
            wasCalledForConfirmation = true
            lastHypothesisKey = hypothesisKey
            return onConfirmOrDescribe?.invoke(hypothesisKey, hypothesisScientificName)
                ?: SpeciesIdResult(description = "stub confirm", confirmedKey = hypothesisKey)
        }

        override suspend fun describeOpenVocab(
            crop: Bitmap,
            region: MarineRegion?,
            language: String,
        ): SpeciesIdResult {
            wasCalledForOpenVocab = true
            return onOpenVocab?.invoke()
                ?: SpeciesIdResult(description = "stub open-vocab", freeLabel = null)
        }
    }

    /**
     * [RegionResolverPort] test double that returns a fixed [MarineRegion]
     * without any file I/O.
     */
    private inner class FakeRegionResolverPort(
        private val fixedRegion: MarineRegion?,
        private val hierarchyFn: (Int) -> Pair<Int, Int>? = { null },
    ) : RegionResolverPort {
        override fun resolve(lat: Double, lon: Double): MarineRegion? = fixedRegion
        override fun hierarchy(ecoregionId: Int): Pair<Int, Int>? = hierarchyFn(ecoregionId)
    }
}
