package com.oceanguard.ai.data.contribution

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ContributionStatus { PENDING, DONE, FAILED }

@Entity(tableName = "contribution_queue")
data class ContributionQueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val imageUri: String,
    val annotationsJson: String,
    val status: ContributionStatus,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
