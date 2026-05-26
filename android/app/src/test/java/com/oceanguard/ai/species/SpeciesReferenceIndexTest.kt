package com.oceanguard.ai.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.inference.species.GeoFilterSensitivity
import com.oceanguard.ai.inference.species.GeoMatchLevel
import com.oceanguard.ai.inference.species.MarineRegion
import com.oceanguard.ai.inference.species.SpeciesConfidence
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Unit tests for [SpeciesReferenceIndex].
 *
 * All tests use synthetic prototypes (no real asset files).
 * Relies only on pure JVM — no Android instrumentation needed.
 */
class SpeciesReferenceIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    companion object {
        private const val DIM = SpeciesReferenceIndex.EMBEDDING_DIM

        /** Builds a unit vector with a spike at [spikeIdx]. */
        fun spikeVector(spikeIdx: Int): FloatArray {
            val v = FloatArray(DIM)
            v[spikeIdx] = 1.0f
            return v
        }

        /** L2-normalise a float array in-place and return it. */
        fun l2(v: FloatArray): FloatArray {
            var norm = 0f
            for (x in v) norm += x * x
            norm = sqrt(norm.toDouble()).toFloat()
            if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
            return v
        }

        /**
         * Writes a minimal valid index binary with [rows] prototypes.
         * Each prototype is a spike vector at [rows[i].first] with speciesKey
         * [rows[i].second].
         */
        fun buildIndexFile(
            tempFolder: TemporaryFolder,
            rows: List<Pair<Int, String>>,  // (spikeIdx, speciesKey)
            scientificNames: Map<String, String> = emptyMap(),
        ): File {
            val n = rows.size
            val file = tempFolder.newFile("test_index.bin")
            val protoBytes = ByteArray(n * DIM * 2)
            val metaLines = StringBuilder()

            for ((i, row) in rows.withIndex()) {
                val (spikeIdx, key) = row
                val vec = spikeVector(spikeIdx)
                for (d in 0 until DIM) {
                    val f16 = floatToFloat16(vec[d])
                    protoBytes[i * DIM * 2 + d * 2]     = (f16 and 0xFF).toByte()
                    protoBytes[i * DIM * 2 + d * 2 + 1] = ((f16 shr 8) and 0xFF).toByte()
                }
                val sci = scientificNames[key] ?: key
                metaLines.appendLine(
                    """{"speciesKey":"$key","scientificName":"$sci","viewTag":"test","nRefs":1}"""
                )
            }

            // Header
            val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x53504558)   // magic SPEX
                putInt(1)            // version
                putInt(n)
                putInt(DIM)
                // reserved 16 bytes
            }.array()

            file.outputStream().use { out ->
                out.write(header)
                out.write(protoBytes)
                out.write(metaLines.toString().toByteArray(Charsets.UTF_8))
            }
            return file
        }

        /** Minimal IEEE 754 float32 → float16 (round-to-nearest, no denormal). */
        private fun floatToFloat16(f: Float): Int {
            val bits = java.lang.Float.floatToIntBits(f)
            val sign = (bits ushr 31) and 0x1
            val exp  = ((bits ushr 23) and 0xFF) - 112
            val mant = (bits shr 13) and 0x3FF
            return if (exp <= 0) 0 else (sign shl 15) or (exp shl 10) or mant
        }
    }

    private lateinit var catalog: Map<String, SpeciesCatalogEntry>
    private lateinit var index: SpeciesReferenceIndex
    private lateinit var indexFile: File

    @Before
    fun setUp() {
        // Three species, spike vectors at dimensions 0, 100, 200
        catalog = mapOf(
            "sp_A" to SpeciesCatalogEntry(
                speciesKey = "sp_A", aphiaId = 1L, scientificName = "Species albus",
                commonNames = mapOf("en" to "White Species"),
                spritePath = null, ecoregions = listOf(10, 20), cosmopolitan = false,
            ),
            "sp_B" to SpeciesCatalogEntry(
                speciesKey = "sp_B", aphiaId = 2L, scientificName = "Species beta",
                commonNames = mapOf("en" to "Beta Species"),
                spritePath = null, ecoregions = listOf(30), cosmopolitan = false,
            ),
            "sp_C" to SpeciesCatalogEntry(
                speciesKey = "sp_C", aphiaId = 3L, scientificName = "Species cosmopolitanus",
                commonNames = mapOf("en" to "Wide Ranger"),
                spritePath = null, ecoregions = emptyList(), cosmopolitan = true,
            ),
        )

        indexFile = buildIndexFile(
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

        index = SpeciesReferenceIndex(catalog, SpeciesConfidence())
        index.load(indexFile)
    }

    // -----------------------------------------------------------------------
    // Basic cosine search
    // -----------------------------------------------------------------------

    @Test
    fun `top-1 result is correct for unambiguous query`() {
        // Query perfectly aligned with sp_A (spike at 0)
        val query = spikeVector(0)
        val results = index.search(query, region = null, topK = 3)
        assertEquals(3, results.size)
        assertEquals("sp_A", results[0].speciesKey)
    }

    @Test
    fun `cosine score for perfect alignment is 1_0`() {
        val query = spikeVector(100) // aligned with sp_B
        val results = index.search(query, region = null, topK = 1)
        assertEquals(1, results.size)
        assertEquals("sp_B", results[0].speciesKey)
        assertEquals(1.0f, results[0].cosineScore, 1e-5f)
    }

    @Test
    fun `top-k respects k limit`() {
        val query = spikeVector(0)
        val results = index.search(query, region = null, topK = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `results are sorted by adjusted score descending`() {
        val query = spikeVector(0)
        val results = index.search(query, region = null, topK = 3)
        for (i in 0 until results.size - 1) {
            assertTrue(
                "Results must be sorted descending: [${results[i].cosineScore}] >= [${results[i+1].cosineScore}]",
                results[i].cosineScore >= results[i + 1].cosineScore,
            )
        }
    }

    @Test
    fun `scientific name is populated from catalog`() {
        val results = index.search(spikeVector(0), region = null, topK = 1)
        assertEquals("Species albus", results[0].scientificName)
    }

    // -----------------------------------------------------------------------
    // Geographic pre-filter + relajación
    // -----------------------------------------------------------------------

    @Test
    fun `cosmopolitan species never excluded by geo filter in STRICT mode`() {
        // sp_C is cosmopolitan; region is in realm 99 (completely foreign)
        val foreignRegion = MarineRegion(ecoregionId = 999, provinceId = 99, realmId = 99)
        val results = index.search(
            spikeVector(200),  // perfectly aligned with sp_C
            region = foreignRegion,
            topK = 3,
            sensitivity = GeoFilterSensitivity.STRICT,
        )
        val cosmopolitan = results.firstOrNull { it.speciesKey == "sp_C" }
        assertNotNull("Cosmopolitan sp_C must not be excluded by STRICT filter", cosmopolitan)
        assertEquals(GeoMatchLevel.COSMOPOLITAN, cosmopolitan!!.geoMatchLevel)
    }

    @Test
    fun `species with empty ecoregion data never excluded`() {
        // sp_C has ecoregions=[] — treated as GLOBAL (no data)
        val region = MarineRegion(ecoregionId = 1, provinceId = 1, realmId = 1)
        val results = index.search(
            spikeVector(200),
            region = region,
            topK = 3,
            sensitivity = GeoFilterSensitivity.BALANCED,
            hierarchy = { null },
        )
        val cosmopo = results.firstOrNull { it.speciesKey == "sp_C" }
        assertNotNull("Species with no ecoregion data must not be excluded", cosmopo)
    }

    @Test
    fun `false discard rate is zero for BALANCED mode with sp_A in-region`() {
        // Region contains ecoregionId=10, which is in sp_A.ecoregions
        val region = MarineRegion(ecoregionId = 10, provinceId = 5, realmId = 2)
        val results = index.search(
            spikeVector(0),   // aligned with sp_A
            region = region,
            topK = 3,
            sensitivity = GeoFilterSensitivity.BALANCED,
            hierarchy = { ecoId -> if (ecoId == 10 || ecoId == 20) Pair(5, 2) else null },
        )
        val spA = results.firstOrNull { it.speciesKey == "sp_A" }
        assertNotNull("sp_A must appear in results when GPS is in its ecoregion", spA)
        assertEquals(GeoMatchLevel.ECOREGION, spA!!.geoMatchLevel)
    }

    @Test
    fun `filter OFF returns all species regardless of region`() {
        // Completely foreign region
        val foreignRegion = MarineRegion(ecoregionId = 999, provinceId = 99, realmId = 99)
        val results = index.search(
            spikeVector(0),
            region = foreignRegion,
            topK = 3,
            sensitivity = GeoFilterSensitivity.OFF,
        )
        assertEquals("All 3 species must be returned with filter OFF", 3, results.size)
    }

    @Test
    fun `geoMatchLevel is GLOBAL when no region provided`() {
        val results = index.search(spikeVector(0), region = null, topK = 1)
        assertEquals(GeoMatchLevel.GLOBAL, results[0].geoMatchLevel)
    }

    // -----------------------------------------------------------------------
    // float16 round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `float16 decode of value 1_0 is accurate`() {
        // 1.0f in float16 = 0x3C00
        val buf = byteArrayOf(0x00.toByte(), 0x3C.toByte())
        val decoded = index.float16ToFloat32(buf, 0)
        assertEquals(1.0f, decoded, 1e-5f)
    }

    @Test
    fun `float16 decode of value 0_0 is zero`() {
        val buf = byteArrayOf(0x00.toByte(), 0x00.toByte())
        val decoded = index.float16ToFloat32(buf, 0)
        assertEquals(0.0f, decoded, 1e-10f)
    }

    @Test
    fun `dot product of orthogonal vectors is zero`() {
        val a = spikeVector(0)
        val b = spikeVector(1)
        assertEquals(0.0f, index.dotProduct(a, b), 1e-6f)
    }

    @Test
    fun `dot product of identical unit vectors is 1_0`() {
        val a = spikeVector(0)
        assertEquals(1.0f, index.dotProduct(a, a), 1e-6f)
    }
}
