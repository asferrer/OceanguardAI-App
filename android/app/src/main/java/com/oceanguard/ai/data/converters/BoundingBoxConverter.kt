package com.oceanguard.ai.data.converters

import android.util.Log
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.oceanguard.ai.data.BoundingBox

private const val TAG = "BoundingBoxConverter"

/**
 * Room TypeConverter for nullable [BoundingBox].
 *
 * Serialises to / from a compact JSON object using the same shared Gson
 * strategy used by [DebrisListConverter] and [LocationConverter]:
 * field names are the Kotlin property names, null is stored as SQL NULL.
 *
 * JSON schema (stable — the Python asset-builder also emits this format):
 * ```json
 * {"x":12.5,"y":34.0,"width":100.0,"height":80.0}
 * ```
 *
 * Registered at the [com.oceanguard.ai.data.OceanGuardDatabase] level so it
 * applies automatically to [com.oceanguard.ai.data.species.SpeciesObservation].
 */
class BoundingBoxConverter {

    private val gson = Gson()

    @TypeConverter
    fun fromBoundingBox(bbox: BoundingBox?): String? {
        if (bbox == null) return null
        return gson.toJson(bbox, BoundingBox::class.java)
    }

    @TypeConverter
    fun toBoundingBox(json: String?): BoundingBox? {
        if (json.isNullOrBlank() || json == "null") return null
        return try {
            gson.fromJson(json, BoundingBox::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize BoundingBox JSON: ${json.take(200)}", e)
            null
        }
    }
}
