package com.oceanguard.ai.data.species.enrichment

/**
 * Online-enrichment payload for a single species.
 *
 * Populated by [SpeciesEnrichmentRepository] from WoRMS, GBIF, and iNaturalist.
 * The queries only use AphiaID or scientific name — the image and user location
 * are NEVER transmitted to any external service.
 *
 * @param aphiaId               WoRMS AphiaID used for the primary lookup (null if lookup
 *                              was name-based and no record was found).
 * @param acceptedName          WoRMS-accepted scientific name (may differ from the
 *                              queried name when the queried name is a synonym).
 * @param authority             Taxonomic authority string, e.g. "(Linnaeus, 1758)".
 * @param rank                  Taxonomic rank as returned by WoRMS, e.g. "Species".
 * @param iucnStatus            IUCN Red List category code, e.g. "VU", "EN", "LC".
 *                              Sourced from iNaturalist conservation status when present.
 * @param distributionSummary   Human-readable summary of the species' global distribution
 *                              (assembled from GBIF distribution facets).
 * @param representativePhotoUrl URL of a representative photo from iNaturalist
 *                              (medium-size, publicly licensed). Never a user photo.
 * @param sources               Attribution list of data providers that contributed to
 *                              this record, e.g. ["WoRMS", "GBIF", "iNaturalist"].
 * @param fetchedAt             Unix epoch milliseconds when this record was fetched.
 *                              Used to enforce the 30-day cache TTL.
 */
data class SpeciesEnrichment(
    val aphiaId: Long?,
    val acceptedName: String?,
    val authority: String?,
    val rank: String?,
    val iucnStatus: String?,
    val distributionSummary: String?,
    val representativePhotoUrl: String?,
    val sources: List<String>,
    val fetchedAt: Long,
)
