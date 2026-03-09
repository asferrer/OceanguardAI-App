package com.oceanguard.ai.data.collection

import android.util.Log
import com.oceanguard.ai.data.DetectionSessionDao
import kotlinx.coroutines.flow.first

/**
 * One-time backfill: populate the MarineDex from existing detection sessions
 * so users who upgrade don't start with an empty collection.
 */
object DexBackfill {

    private const val TAG = "DexBackfill"

    suspend fun backfillFromExistingSessions(
        sessionDao: DetectionSessionDao,
        collectionRepo: CollectionRepository,
    ) {
        val sessions = sessionDao.getAllSessions().first()
        if (sessions.isEmpty()) {
            Log.i(TAG, "No existing sessions to backfill")
            return
        }

        Log.i(TAG, "Backfilling MarineDex from ${sessions.size} existing sessions...")
        var newEntries = 0

        // Process sessions oldest-first so firstSeenAt / firstSeenSessionId are correct
        for (session in sessions.reversed()) {
            val types = session.debrisList.map { it.type.name }.distinct()
            for (type in types) {
                val isNew = collectionRepo.discoverOrUpdate(type, session.id)
                if (isNew) newEntries++
            }
        }

        Log.i(TAG, "Backfill complete: $newEntries new MarineDex entries created")
    }
}
