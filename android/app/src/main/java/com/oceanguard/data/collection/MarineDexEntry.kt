package com.oceanguard.ai.data.collection

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single entry in the MarineDex collection.
 *
 * Each [debrisType] can appear at most once (PK). Tracks when it was first
 * and last seen, how many times it has been detected, and whether the user
 * marked it as a favourite.
 */
@Entity(tableName = "marine_dex_entries")
data class MarineDexEntry(
    @PrimaryKey val debrisType: String,
    val firstSeenAt: Long,
    val firstSeenSessionId: Long,
    val timesDetected: Int = 1,
    val lastSeenAt: Long,
    val isFavorite: Boolean = false,
)
