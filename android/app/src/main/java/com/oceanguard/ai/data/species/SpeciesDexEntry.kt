package com.oceanguard.ai.data.species

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single entry in the BioDex species collection.
 *
 * Mirrors [com.oceanguard.ai.data.collection.MarineDexEntry] for the species
 * track. Each [speciesKey] appears at most once (PK).
 *
 * [speciesKey] format:
 *  - Catalogued species: AphiaID as string, e.g. "126436"
 *  - Uncatalogued (open-vocab): "uncat:<slug>", e.g. "uncat:unknown_fish_01"
 */
@Entity(tableName = "species_dex_entries")
data class SpeciesDexEntry(
    @PrimaryKey val speciesKey: String,
    val scientificName: String,
    val firstSeenAt: Long,
    val firstSeenObservationId: Long,
    val timesObserved: Int = 1,
    val lastSeenAt: Long,
    val isFavorite: Boolean = false,
)
