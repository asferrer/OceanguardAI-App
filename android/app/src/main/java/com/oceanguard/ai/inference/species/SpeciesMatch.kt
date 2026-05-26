package com.oceanguard.ai.inference.species

/**
 * Result of a single species candidate from [SpeciesReferenceIndex.search].
 *
 * @param speciesKey      Matches [com.oceanguard.ai.data.species.SpeciesDexEntry.speciesKey].
 * @param scientificName  Binomial name, copied from the index metadata.
 * @param cosineScore     Maximum cosine similarity across all prototypes for
 *                        this species, in [0, 1]. Since all vectors are L2-
 *                        normalised, cosine = dot product.
 * @param confidence      Platt-calibrated probability from [SpeciesConfidence],
 *                        in [0, 1].
 * @param geoMatchLevel   Level at which the species passed the geo-filter.
 */
data class SpeciesMatch(
    val speciesKey: String,
    val scientificName: String,
    val cosineScore: Float,
    val confidence: Float,
    val geoMatchLevel: GeoMatchLevel,
)
