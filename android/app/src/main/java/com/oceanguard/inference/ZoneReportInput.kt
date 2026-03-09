package com.oceanguard.ai.inference

import com.oceanguard.ai.data.DayGroup
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend

/** All data needed to generate a zone-based temporal evolution report. */
data class ZoneReportInput(
    val locationName: String,
    val centroidLat: Double,
    val centroidLon: Double,
    val sessions: List<DetectionSession>,
    val dayGroups: List<DayGroup>,
    val trend: HealthTrend,
    val dateRangeStartMs: Long?,
    val dateRangeEndMs: Long?,
)
