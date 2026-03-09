package com.oceanguard.ai.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stateless helper that extracts the set of calendar dates on which at least
 * one [DetectionSession] was recorded.
 *
 * Each session's [DetectionSession.timestamp] (a [java.util.Date]) is
 * converted to a [LocalDate] in the device's default time zone so that the
 * result matches what the user would read on their clock.
 *
 * The result is a [Set] so duplicates are automatically deduplicated when
 * multiple sessions share the same calendar day.
 */
object SessionDatesHelper {

    /**
     * Returns the distinct [LocalDate]s on which at least one session was
     * recorded.
     *
     * @param sessions Full list of sessions, typically collected from
     *                 [DetectionRepository].
     * @return An unordered [Set] of dates that have associated data.
     */
    fun datesWithData(sessions: List<DetectionSession>): Set<LocalDate> {
        return sessions.mapTo(mutableSetOf()) { session ->
            Instant.ofEpochMilli(session.timestamp.time)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
    }
}
