package com.oceanguard.ai.inference.species

/**
 * Minimal interface for resolving a (lat, lon) coordinate to a [MarineRegion].
 *
 * Extracted so that [SpeciesIdentifier] can be unit-tested without loading
 * the MEOW raster binary. The production implementation is [MarineRegionResolver];
 * test doubles implement this interface without file I/O.
 *
 * @see MarineRegionResolver — production implementation (raster lookup)
 */
interface RegionResolverPort {

    /**
     * Resolve latitude/longitude (WGS-84 degrees) to a [MarineRegion].
     *
     * Returns null if the point falls on land with no nearby marine cell,
     * or if assets are not loaded.
     */
    fun resolve(lat: Double, lon: Double): MarineRegion?

    /**
     * Returns the (provinceId, realmId) pair for [ecoregionId], used by
     * [SpeciesReferenceIndex] for soft-prior computation.
     *
     * Returns null if the ecoregion is not in the hierarchy.
     */
    fun hierarchy(ecoregionId: Int): Pair<Int, Int>?
}
