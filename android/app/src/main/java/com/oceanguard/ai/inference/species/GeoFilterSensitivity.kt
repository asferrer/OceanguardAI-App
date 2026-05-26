package com.oceanguard.ai.inference.species

/**
 * User-configurable geographic filter sensitivity.
 *
 * Stored as the enum name in [com.oceanguard.ai.data.SettingsRepository]
 * under key `geo_filter_sensitivity`.
 *
 *   STRICT    — hard-filter at province level (maximum precision).
 *   BALANCED  — hard-filter at realm level + soft geo-prior (default).
 *   OFF       — global search, no geographic exclusion.
 *
 * The hard-filter level is the *minimum* [GeoMatchLevel] a species must achieve
 * to be included in the candidate set before cosine search. Species marked
 * [com.oceanguard.ai.data.species.SpeciesCatalogEntry.cosmopolitan] and species
 * with empty ecoregion data are NEVER excluded regardless of sensitivity.
 */
enum class GeoFilterSensitivity {
    STRICT,
    BALANCED,
    OFF,
}
