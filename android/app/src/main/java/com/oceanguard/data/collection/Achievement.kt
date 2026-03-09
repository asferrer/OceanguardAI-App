package com.oceanguard.ai.data.collection

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted achievement state.
 *
 * [unlockedAt] == 0L means the achievement is still locked.
 * [progress] tracks incremental advance towards [target].
 */
@Entity(tableName = "achievements")
data class Achievement(
    @PrimaryKey val id: String,
    val unlockedAt: Long = 0L,
    val progress: Int = 0,
    val target: Int,
)
