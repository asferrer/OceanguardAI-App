package com.oceanguard.ai.inference.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry

/**
 * Biogeographic prior for species identification.
 *
 * Computes the geographic plausibility weight `g(species, region)` used to
 * modulate the raw cosine score before ranking:
 *
 *   adjusted_score = cosineScore * g(level)
 *
 * The prior is intentionally soft: a foreign species with a very high cosine
 * score can still emerge at the top — it is not silenced, only down-weighted.
 *
 * Prior values per match level (calibrated conservatively to avoid false
 * exclusions while still boosting local species):
 *   ECOREGION   → 1.0   (species confirmed in this exact ecoregion)
 *   PROVINCE    → 0.6   (same province, adjacent ecoregions)
 *   REALM       → 0.3   (same realm, e.g. North Atlantic)
 *   COSMOPOLITAN→ 0.1   (wide-ranging; ubiquitous — small prior, cosine dominates)
 *   GLOBAL      → 1.0   (no GPS / filter OFF → no penalty)
 *
 * Note: COSMOPOLITAN gets a low weight because the prior encodes "how much does
 * geography help here?" — for cosmopolitan species it adds no information, so
 * the raw cosine score should dominate with minimal distortion.
 */
object GeoPrior {

    /**
     * Returns the geographic plausibility multiplier for [level].
     * Always in (0, 1].
     */
    fun g(level: GeoMatchLevel): Float = when (level) {
        GeoMatchLevel.ECOREGION    -> 1.0f
        GeoMatchLevel.PROVINCE     -> 0.6f
        GeoMatchLevel.REALM        -> 0.3f
        GeoMatchLevel.COSMOPOLITAN -> 0.1f
        GeoMatchLevel.GLOBAL       -> 1.0f
    }

    /**
     * Returns the [GeoMatchLevel] for a species given the resolved [region].
     *
     * Logic (in priority order):
     * 1. Cosmopolitan → always [GeoMatchLevel.COSMOPOLITAN].
     * 2. No region data → [GeoMatchLevel.GLOBAL].
     * 3. Ecoregion in species.ecoregions → [GeoMatchLevel.ECOREGION].
     * 4. Any species ecoregion in same province → [GeoMatchLevel.PROVINCE].
     * 5. Any species ecoregion in same realm → [GeoMatchLevel.REALM].
     * 6. Species has no ecoregion data → [GeoMatchLevel.GLOBAL] (no penalty).
     *
     * @param species   Catalog entry with distribution data.
     * @param region    Resolved marine region, or null if no GPS.
     * @param hierarchy Function that maps ecoregionId → (provinceId, realmId).
     *                  Injected so this class stays testable without file I/O.
     */
    fun matchLevel(
        species: SpeciesCatalogEntry,
        region: MarineRegion?,
        hierarchy: (ecoregionId: Int) -> Pair<Int, Int>?,
    ): GeoMatchLevel {
        if (species.cosmopolitan) return GeoMatchLevel.COSMOPOLITAN
        if (region == null) return GeoMatchLevel.GLOBAL
        if (species.ecoregions.isEmpty()) return GeoMatchLevel.GLOBAL

        if (region.ecoregionId in species.ecoregions) return GeoMatchLevel.ECOREGION

        val speciesProvRealmPairs = species.ecoregions.mapNotNull { hierarchy(it) }
        val speciesProvinces = speciesProvRealmPairs.map { it.first }.toSet()
        val speciesRealms = speciesProvRealmPairs.map { it.second }.toSet()

        if (region.provinceId in speciesProvinces) return GeoMatchLevel.PROVINCE
        if (region.realmId in speciesRealms) return GeoMatchLevel.REALM

        // Species exists but outside this realm → still valid, just out-of-range
        return GeoMatchLevel.GLOBAL
    }
}
