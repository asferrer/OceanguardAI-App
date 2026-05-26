package com.oceanguard.ai.inference.species

/**
 * Resolved MEOW (Marine Ecoregions of the World, Spalding et al. 2007)
 * biogeographic region for a GPS position.
 *
 * The three-level hierarchy (ecoregion → province → realm) allows the
 * geo-filter to relax progressively when a tight match finds no candidates.
 *
 * Reference: Spalding et al. (2007) Marine Ecoregions of the World.
 * BioScience 57(7):573–583.  https://doi.org/10.1641/B570707
 *
 * @param ecoregionId   MEOW ecoregion ID (1–232).
 * @param provinceId    MEOW province ID (1–62).
 * @param realmId       MEOW realm ID (1–12).
 * @param ecoregionName Human-readable ecoregion name (for UI / prompt injection).
 * @param provinceName  Human-readable province name.
 * @param realmName     Human-readable realm name.
 */
data class MarineRegion(
    val ecoregionId: Int,
    val provinceId: Int,
    val realmId: Int,
    val ecoregionName: String = "",
    val provinceName: String = "",
    val realmName: String = "",
)
