package com.oceanguard.ai.data.converters

import android.util.Log
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import java.util.Date

private const val TAG = "Converters"

/**
 * Room TypeConverters for all non-primitive types used in DetectionSession.
 *
 * Registered at the database level so they apply to all entities. Each converter
 * class is also referenced directly on the DetectionSession entity for clarity.
 *
 * Serialization strategy:
 *  - Complex objects (Debris, Location) -> JSON strings via Gson
 *  - Enums -> their name() string so values survive future enum reorderings
 *  - Date -> Long epoch millis, which Room can index efficiently
 */

// ---------------------------------------------------------------------------
// Shared Gson instance
// ---------------------------------------------------------------------------

/**
 * Lazily initialised Gson configured for the OceanGuard domain types.
 *
 * BoundingBox, DebrisMaterial, DebrisType and LocationSource are plain data
 * classes / enums, so the default Gson serialisation (field names + enum
 * name strings) is sufficient. No custom adapters are required.
 */
private val gson: Gson by lazy {
    GsonBuilder()
        .serializeNulls()       // keep null fields in JSON so round-trips are stable
        .create()
}

// ---------------------------------------------------------------------------
// 1. DebrisListConverter
// ---------------------------------------------------------------------------

/**
 * Converts List<Debris> to a JSON string and back.
 *
 * Each [Debris] element embeds a [com.oceanguard.ai.data.BoundingBox] and two
 * enums ([com.oceanguard.ai.data.DebrisMaterial], [com.oceanguard.ai.data.DebrisType]),
 * all of which Gson handles natively.
 *
 * Null / empty-list edge cases:
 *  - A null column value is mapped to an empty list (safe default).
 *  - An empty list is stored as the JSON string "[]".
 */
class DebrisListConverter {

    private val debrisListType = object : TypeToken<List<Debris>>() {}.type

    @TypeConverter
    fun fromDebrisList(debrisList: List<Debris>?): String {
        if (debrisList == null) return "[]"
        return gson.toJson(debrisList, debrisListType)
    }

    @TypeConverter
    fun toDebrisList(json: String?): List<Debris> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        return try {
            gson.fromJson(json, debrisListType) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize debrisList JSON (returning empty): ${json?.take(200)}", e)
            emptyList()
        }
    }
}

// ---------------------------------------------------------------------------
// 2. LocationConverter
// ---------------------------------------------------------------------------

/**
 * Converts a nullable [Location] to a nullable JSON string and back.
 *
 * A null location (session captured without geo data) is persisted as a SQL
 * NULL column value, which keeps the storage footprint minimal and allows
 * straightforward IS NULL / IS NOT NULL queries.
 */
class LocationConverter {

    @TypeConverter
    fun fromLocation(location: Location?): String? {
        if (location == null) return null
        return gson.toJson(location, Location::class.java)
    }

    @TypeConverter
    fun toLocation(json: String?): Location? {
        if (json.isNullOrBlank() || json == "null") return null
        return try {
            gson.fromJson(json, Location::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize Location JSON (returning null): ${json?.take(200)}", e)
            null
        }
    }
}

// ---------------------------------------------------------------------------
// 3. DateConverter
// ---------------------------------------------------------------------------

/**
 * Converts [Date] to a Long epoch-millisecond value and back.
 *
 * Storing timestamps as Long lets Room (SQLite) sort and range-query on the
 * raw column without string parsing, which is significantly faster on large
 * tables.
 */
class DateConverter {

    @TypeConverter
    fun fromDate(date: Date?): Long? = date?.time

    @TypeConverter
    fun toDate(epochMs: Long?): Date? {
        if (epochMs == null) return null
        return Date(epochMs)
    }
}

// ---------------------------------------------------------------------------
// 4. ImageQualityConverter
// ---------------------------------------------------------------------------

/**
 * Converts [ImageQuality] to its name string and back.
 *
 * Using [Enum.name] instead of [Enum.ordinal] ensures the database values
 * remain valid if the enum declaration order ever changes.
 */
class ImageQualityConverter {

    @TypeConverter
    fun fromImageQuality(quality: ImageQuality?): String? = quality?.name

    @TypeConverter
    fun toImageQuality(value: String?): ImageQuality? {
        if (value.isNullOrBlank()) return null
        return try {
            ImageQuality.valueOf(value)
        } catch (e: IllegalArgumentException) {
            ImageQuality.FAIR   // safe fallback matching the companion fromString logic
        }
    }
}
