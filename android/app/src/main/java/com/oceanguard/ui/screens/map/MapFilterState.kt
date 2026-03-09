package com.oceanguard.ai.ui.screens.map

import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession

/** Filter state shared between map filter bar and zone bottom sheet. */
data class MapFilterState(
    val selectedDebrisType: DebrisType? = null,
    val dateRangeStartMs: Long? = null,
    val dateRangeEndMs: Long? = null,
) {
    val hasActiveFilters: Boolean
        get() = selectedDebrisType != null || dateRangeStartMs != null

    fun applyTo(sessions: List<DetectionSession>): List<DetectionSession> {
        return sessions.filter { session ->
            val matchesType = selectedDebrisType == null ||
                session.debrisList.any { it.type == selectedDebrisType }
            val matchesDate =
                (dateRangeStartMs == null || session.timestamp.time >= dateRangeStartMs) &&
                (dateRangeEndMs == null || session.timestamp.time <= dateRangeEndMs)
            matchesType && matchesDate
        }
    }
}
