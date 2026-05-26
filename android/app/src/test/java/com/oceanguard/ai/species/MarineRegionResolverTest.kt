package com.oceanguard.ai.species

import com.oceanguard.ai.inference.species.MarineRegionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [MarineRegionResolver].
 *
 * Uses a synthetic 3×4 mini-raster (nLat=3 rows, nLon=4 columns, 0.25° cells
 * covering a tiny patch of ocean) so no production asset file is needed.
 *
 * ## Synthetic raster layout (0.25° grid, origin top-left = NW corner)
 *
 * Row/col indices based on:
 *   row = (90 - lat) / 0.25 → clamped to [0, nLat-1]
 *   col = (lon + 180) / 0.25 → clamped to [0, nLon-1]
 *
 * Mini-raster (3 rows × 4 cols):
 *   [[ 0,  5,  5,  5 ],    ← row 0 (lat≈90..89.5)  col0=land, rest=eco5
 *    [ 3,  3,  0,  7 ],    ← row 1                  col0,1=eco3, col2=land, col3=eco7
 *    [ 3,  3,  7,  7 ]]    ← row 2                  col0,1=eco3, col2,3=eco7
 *
 * Hierarchy: eco3 → prov1 → realm1; eco5 → prov2 → realm1; eco7 → prov3 → realm2.
 */
class MarineRegionResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    companion object {
        private const val N_LAT = 3
        private const val N_LON = 4
        private const val RESOLUTION = 0.25  // degrees per cell

        // Grid data row-major: row0=[0,5,5,5], row1=[3,3,0,7], row2=[3,3,7,7]
        private val GRID_DATA = shortArrayOf(
            0, 5, 5, 5,
            3, 3, 0, 7,
            3, 3, 7, 7,
        )

        private val HIERARCHY_JSON = """
            [
              {"ecoregionId":3,"provinceId":1,"realmId":1,"ecoregionName":"TestEco3","provinceName":"Prov1","realmName":"Realm1"},
              {"ecoregionId":5,"provinceId":2,"realmId":1,"ecoregionName":"TestEco5","provinceName":"Prov2","realmName":"Realm1"},
              {"ecoregionId":7,"provinceId":3,"realmId":2,"ecoregionName":"TestEco7","provinceName":"Prov3","realmName":"Realm2"}
            ]
        """.trimIndent()

        fun buildRasterFile(folder: TemporaryFolder): File {
            val file = folder.newFile("test_raster.bin")
            val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x4D455257)   // magic MERW
                putInt(1)            // version
                putInt(N_LAT)
                putInt(N_LON)
                // 16 bytes reserved → zeroes (array already zeroed)
            }.array()

            val gridBuf = ByteBuffer.allocate(N_LAT * N_LON * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in GRID_DATA) gridBuf.putShort(v)

            file.outputStream().use { out ->
                out.write(header)
                out.write(gridBuf.array())
            }
            return file
        }

        fun buildHierarchyFile(folder: TemporaryFolder): File {
            val file = folder.newFile("test_hierarchy.json")
            file.writeText(HIERARCHY_JSON)
            return file
        }
    }

    private lateinit var resolver: MarineRegionResolver

    @Before
    fun setUp() {
        val rasterFile = buildRasterFile(tempFolder)
        val hierarchyFile = buildHierarchyFile(tempFolder)
        resolver = MarineRegionResolver(rasterFile, hierarchyFile)
        resolver.load()
    }

    // -----------------------------------------------------------------------
    // latToRow / lonToCol
    // -----------------------------------------------------------------------

    @Test
    fun `latToRow maps first-cell lat to row 0`() {
        // row = (90 - lat) / 0.25. Row 0 covers lat 90.0..89.75 (centre 89.875).
        // 89.875 → (0.125 / 0.25) = 0.5 → row 0. (lat 89.625 would be row 1.)
        val row = resolver.latToRow(89.875) // centre of row 0
        assertEquals(0, row.coerceIn(0, N_LAT - 1))
    }

    @Test
    fun `lonToCol maps western longitude correctly`() {
        // col = (lon + 180) / 0.25; lon=-180 → col 0
        val col = resolver.lonToCol(-180.0)
        assertEquals(0, col.coerceIn(0, N_LON - 1))
    }

    // -----------------------------------------------------------------------
    // Marine cell lookup
    // -----------------------------------------------------------------------

    @Test
    fun `resolve returns correct ecoregion for marine cell at row1 col0`() {
        // row1, col0 → value=3
        // lat for row1 ≈ 90 - 1*0.25 = 89.75 (middle of row 1)
        // lon for col0 ≈ -180 + 0*0.25 = -180 (left edge of col 0)
        // Use pixel-centre: lat = 90 - (1 + 0.5) * 0.25 = 89.625 → but lat clamped to row1 = 89.75..89.5
        val lat = 90.0 - (1 + 0.5) * RESOLUTION  // 89.625
        val lon = -180.0 + (0 + 0.5) * RESOLUTION // -179.875
        val region = resolver.resolve(lat, lon)
        assertNotNull("Expected a marine region at ($lat, $lon)", region)
        assertEquals(3, region!!.ecoregionId)
        assertEquals(1, region.provinceId)
        assertEquals(1, region.realmId)
        assertEquals("TestEco3", region.ecoregionName)
    }

    @Test
    fun `resolve returns ecoregion 7 for cell at row1 col3`() {
        val lat = 90.0 - (1 + 0.5) * RESOLUTION
        val lon = -180.0 + (3 + 0.5) * RESOLUTION
        val region = resolver.resolve(lat, lon)
        assertNotNull(region)
        assertEquals(7, region!!.ecoregionId)
        assertEquals(2, region.realmId)
    }

    // -----------------------------------------------------------------------
    // Land-cell ring search fallback
    // -----------------------------------------------------------------------

    @Test
    fun `resolve finds nearest marine cell when queried point is on land`() {
        // row0, col0 = value 0 (land). Ring search should find eco5 (col1)
        // or eco3 (row1,col0) in ring=1.
        val lat = 90.0 - (0 + 0.5) * RESOLUTION  // 89.875
        val lon = -180.0 + (0 + 0.5) * RESOLUTION // -179.875
        val region = resolver.resolve(lat, lon, maxRingSearch = 1.0)
        assertNotNull("Ring search must find a marine cell adjacent to land", region)
    }

    @Test
    fun `resolve returns null when entire search area is land`() {
        // With maxRingSearch=0 the resolver finds no marine cell for a land cell
        val lat = 90.0 - (0 + 0.5) * RESOLUTION
        val lon = -180.0 + (0 + 0.5) * RESOLUTION
        val region = resolver.resolve(lat, lon, maxRingSearch = 0.0)
        assertNull("No marine cell within 0° → must return null", region)
    }

    // -----------------------------------------------------------------------
    // Hierarchy export
    // -----------------------------------------------------------------------

    @Test
    fun `hierarchy returns correct provinceId and realmId for eco3`() {
        val pair = resolver.hierarchy(3)
        assertNotNull(pair)
        assertEquals(1, pair!!.first)  // provinceId
        assertEquals(1, pair.second)   // realmId
    }

    @Test
    fun `hierarchy returns null for unknown ecoregionId`() {
        assertNull(resolver.hierarchy(999))
    }
}
