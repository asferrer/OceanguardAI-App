package com.oceanguard.ai.inference.species

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Resolves a (lat, lon) coordinate to a [MarineRegion] using a pre-built
 * MEOW raster and an ecoregion hierarchy JSON.
 *
 * ## Binary format of `meow_raster_vN.bin`
 *
 * Layout produced by the Python builder (`finetune/species/build_distribution_filter.py`).
 * Both sides MUST agree on this format:
 *
 * ```
 * Header (32 bytes, little-endian):
 *   [0..3]   magic   = 0x4D455257  ("MERW" in ASCII, uint32)
 *   [4..7]   version = 1           (uint32)
 *   [8..11]  nLat    = number of latitude rows    (uint32)  — expected 720 for 0.25°
 *   [12..15] nLon    = number of longitude columns(uint32)  — expected 1440 for 0.25°
 *   [16..19] reserved (zero)
 *   [20..23] reserved (zero)
 *   [24..27] reserved (zero)
 *   [28..31] reserved (zero)
 *
 * Grid cells (nLat × nLon × 2 bytes, int16, little-endian, row-major):
 *   Cell value = MEOW ecoregion ID (1..232) for marine cells.
 *   Cell value = 0 for land / freshwater cells.
 *   Rows are ordered from latitude +90 (north pole) to -90 (south pole).
 *   Columns are ordered from longitude -180 to +180.
 * ```
 *
 * Total file size: 32 + nLat * nLon * 2 bytes.
 * At 0.25°: 32 + 720*1440*2 = 2,073,632 bytes (~2 MB).
 *
 * ## ecoregion_hierarchy_vN.json
 *
 * A JSON array of objects:
 * ```json
 * [{"ecoregionId":1,"provinceId":1,"realmId":1,
 *   "ecoregionName":"Bering Sea","provinceName":"Cold Temperate NE Pacific","realmName":"Temperate Northern Pacific"}]
 * ```
 *
 * ## Land-cell fallback
 *
 * If the resolved cell is 0 (land/fresh), the resolver searches expanding rings
 * of neighbouring cells until a marine cell (value > 0) is found within
 * [maxRingSearch] degrees. This handles coastal dive sites near the shore.
 */
class MarineRegionResolver(
    private val rasterFile: File,
    private val hierarchyFile: File,
) : RegionResolverPort {
    companion object {
        private const val MAGIC = 0x4D455257.toInt()
        private const val EXPECTED_VERSION = 1
        private const val DEFAULT_RESOLUTION = 0.25   // degrees per cell
        private const val MAX_RING_SEARCH = 2          // search up to 2° away for nearest marine cell

        // Grid sentinel values.
        private const val LAND = 0                     // land / freshwater
        private const val OPEN_OCEAN_NO_ECOREGION = -1 // marine but outside any MEOW ecoregion
    }

    private var nLat = 0
    private var nLon = 0
    private var resolution = DEFAULT_RESOLUTION
    private lateinit var grid: ShortArray           // [nLat * nLon], row-major

    // ecoregionId → (provinceId, realmId)
    private val hierarchyMap = mutableMapOf<Int, Triple<Int, Int, String>>()

    // ecoregionId → full MarineRegion (names included)
    private val regionMap = mutableMapOf<Int, MarineRegion>()

    @Volatile
    private var loaded = false

    /**
     * Load raster and hierarchy. Idempotent.
     *
     * @throws IllegalStateException on magic/version mismatch.
     * @throws java.io.IOException on I/O errors.
     */
    fun load() {
        if (loaded) return
        loadRaster()
        loadHierarchy()
        loaded = true
    }

    /**
     * Resolve [lat]/[lon] (degrees, WGS-84) to a [MarineRegion].
     *
     * Returns null if:
     *  - The resolver is not loaded.
     *  - The point is on land AND no marine cell is found within [maxRingSearch] degrees.
     */
    override fun resolve(lat: Double, lon: Double): MarineRegion? =
        resolve(lat, lon, MAX_RING_SEARCH.toDouble())

    fun resolve(lat: Double, lon: Double, maxRingSearch: Double): MarineRegion? {
        if (!loaded) return null

        val cellVal = lookupCell(lat, lon)
        if (cellVal > 0) return buildRegion(cellVal.toInt())

        // Open ocean with no MEOW ecoregion: marine but unclassifiable. Return null so
        // the caller skips the geo-prefilter (treats as GLOBAL) instead of ring-searching
        // as if this were a coastal land cell, which would wrongly borrow a nearby ecoregion.
        if (cellVal.toInt() == OPEN_OCEAN_NO_ECOREGION) return null

        // Land cell (LAND) — search expanding rings for the nearest marine ecoregion cell.
        val maxRingSteps = (maxRingSearch / resolution).toInt()

        for (ring in 1..maxRingSteps) {
            val neighborCell = searchRing(lat, lon, ring)
            if (neighborCell > 0) return buildRegion(neighborCell.toInt())
        }
        return null
    }

    /**
     * Returns the (provinceId, realmId) pair for [ecoregionId], or null if
     * the hierarchy was not loaded or the id is unknown.
     * Exposed for injection into [GeoPrior.matchLevel].
     */
    override fun hierarchy(ecoregionId: Int): Pair<Int, Int>? {
        val t = hierarchyMap[ecoregionId] ?: return null
        return Pair(t.first, t.second)
    }

    // -----------------------------------------------------------------------
    // Internal — grid access
    // -----------------------------------------------------------------------

    private fun lookupCell(lat: Double, lon: Double): Short {
        val row = latToRow(lat)
        val col = lonToCol(lon)
        return grid[row * nLon + col]
    }

    internal fun latToRow(lat: Double): Int {
        // Row 0 = north pole (+90), row (nLat-1) = south pole (-90)
        val clamped = lat.coerceIn(-90.0, 90.0)
        return ((90.0 - clamped) / resolution).toInt().coerceIn(0, nLat - 1)
    }

    internal fun lonToCol(lon: Double): Int {
        // Normalise to [-180, 180), column 0 = -180
        val normalized = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return ((normalized + 180.0) / resolution).toInt().coerceIn(0, nLon - 1)
    }

    private fun searchRing(lat: Double, lon: Double, ring: Int): Short {
        val ringDeg = ring * resolution
        val latList = listOf(lat + ringDeg, lat - ringDeg)
        val lonList = listOf(lon + ringDeg, lon - ringDeg)

        // Top and bottom rows of the ring
        for (ringLat in latList) {
            var ringLon = lon - ringDeg
            while (ringLon <= lon + ringDeg) {
                val v = lookupCell(ringLat, ringLon)
                if (v > 0) return v
                ringLon += resolution
            }
        }
        // Left and right columns (skip corners already visited)
        for (ringLon in lonList) {
            var ringLat = lat - ringDeg + resolution
            while (ringLat < lat + ringDeg) {
                val v = lookupCell(ringLat, ringLon)
                if (v > 0) return v
                ringLat += resolution
            }
        }
        return 0
    }

    private fun buildRegion(ecoregionId: Int): MarineRegion? {
        return regionMap[ecoregionId]
    }

    // -----------------------------------------------------------------------
    // Loading helpers
    // -----------------------------------------------------------------------

    private fun loadRaster() {
        val bytes = rasterFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.int
        check(magic == MAGIC) {
            "Raster bad magic 0x${magic.toString(16)}, expected 0x${MAGIC.toString(16)}"
        }
        val version = buf.int
        check(version == EXPECTED_VERSION) { "Unsupported raster version $version" }

        nLat = buf.int
        nLon = buf.int
        buf.position(32) // skip reserved

        grid = ShortArray(nLat * nLon)
        for (i in grid.indices) grid[i] = buf.short
    }

    private data class HierarchyEntry(
        val ecoregionId: Int,
        val provinceId: Int,
        val realmId: Int,
        val ecoregionName: String = "",
        val provinceName: String = "",
        val realmName: String = "",
    )

    private fun loadHierarchy() {
        val type = object : TypeToken<List<HierarchyEntry>>() {}.type
        val entries: List<HierarchyEntry> = Gson().fromJson(
            hierarchyFile.readText(Charsets.UTF_8),
            type,
        )
        for (e in entries) {
            hierarchyMap[e.ecoregionId] = Triple(e.provinceId, e.realmId, e.realmName)
            regionMap[e.ecoregionId] = MarineRegion(
                ecoregionId  = e.ecoregionId,
                provinceId   = e.provinceId,
                realmId      = e.realmId,
                ecoregionName = e.ecoregionName,
                provinceName  = e.provinceName,
                realmName     = e.realmName,
            )
        }
    }
}
