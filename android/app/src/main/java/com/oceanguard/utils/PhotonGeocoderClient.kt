package com.oceanguard.ai.utils

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Result of a Photon geocoding search with rich location metadata.
 */
data class GeocodingResult(
    val name: String?,
    val street: String?,
    val district: String?,
    val city: String?,
    val county: String?,
    val state: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Format a [GeocodingResult] as a short, human-readable place label.
 *
 * Builds the most specific label possible:
 * - "Playa de San Juan, Alicante"  (name + city)
 * - "Alicante, Valencia"           (city + state, when no name)
 * - "Valencia, Spain"              (state + country)
 *
 * Never returns coordinates — always a human-readable string.
 */
fun GeocodingResult.toPlaceLabel(): String {
    val primary = name ?: street ?: district
    val secondary = city ?: county ?: state

    return when {
        primary != null && secondary != null -> "$primary, $secondary"
        primary != null && country != null -> "$primary, $country"
        primary != null -> primary
        secondary != null && country != null -> "$secondary, $country"
        secondary != null -> secondary
        state != null && country != null -> "$state, $country"
        state != null -> state
        country != null -> country
        else -> null
    } ?: "Unknown location"
}

/**
 * Lightweight geocoding client backed by the Photon API (photon.komoot.io).
 *
 * Free to use, no API key required. Returns GeoJSON FeatureCollection.
 * Network I/O is dispatched on [Dispatchers.IO].
 */
class PhotonGeocoderClient {

    private companion object {
        const val TAG = "PhotonGeocoder"
        const val CACHE_MAX_SIZE = 128
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** LRU cache for reverse geocoding results keyed by rounded coordinates. */
    private val reverseCache = object : LinkedHashMap<String, GeocodingResult?>(
        64, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, GeocodingResult?>,
        ): Boolean = size > CACHE_MAX_SIZE
    }

    @Synchronized
    private fun getCached(key: String): Pair<Boolean, GeocodingResult?> {
        return if (reverseCache.containsKey(key)) true to reverseCache[key]
        else false to null
    }

    @Synchronized
    private fun putCache(key: String, value: GeocodingResult?) {
        reverseCache[key] = value
    }

    /**
     * Reverse-geocode a coordinate pair into a place name.
     *
     * Results are cached using coordinates rounded to 3 decimal places
     * (~111 m precision) to avoid redundant network calls for nearby points.
     */
    suspend fun reverse(latitude: Double, longitude: Double): GeocodingResult? {
        val cacheKey = "%.3f,%.3f".format(latitude, longitude)
        val (hit, cached) = getCached(cacheKey)
        if (hit) return cached

        return withContext(Dispatchers.IO) {
            runCatching { fetchReverse(latitude, longitude) }
                .getOrNull()
                .also { putCache(cacheKey, it) }
        }
    }

    private fun fetchReverse(latitude: Double, longitude: Double): GeocodingResult? {
        val url = "https://photon.komoot.io/reverse?lat=$latitude&lon=$longitude"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseFeatureCollection(body).firstOrNull()
        }
    }

    /**
     * Search for locations matching [query].
     *
     * @param query  Free-text search string.
     * @param limit  Maximum number of results (default 5).
     * @return Ordered list of [GeocodingResult]; empty on error or no results.
     */
    suspend fun search(query: String, limit: Int = 5): List<GeocodingResult> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { fetchResults(query, limit) }.getOrDefault(emptyList())
        }
    }

    private fun fetchResults(query: String, limit: Int): List<GeocodingResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://photon.komoot.io/api/?q=$encoded&limit=$limit"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            return parseFeatureCollection(body)
        }
    }

    private fun parseFeatureCollection(json: String): List<GeocodingResult> {
        val root = JsonParser.parseString(json).asJsonObject
        val features = root.getAsJsonArray("features") ?: return emptyList()

        return features.mapNotNull { element ->
            runCatching { parseFeature(element.asJsonObject) }.getOrNull()
        }
    }

    private fun parseFeature(feature: com.google.gson.JsonObject): GeocodingResult? {
        val coords = feature.getAsJsonObject("geometry")
            ?.getAsJsonArray("coordinates") ?: return null
        val longitude = coords[0].asDouble
        val latitude = coords[1].asDouble

        val props = feature.getAsJsonObject("properties") ?: return null
        val name = props.get("name")?.asString
        val street = props.get("street")?.asString
        val district = props.get("district")?.asString
        val city = props.get("city")?.asString
        val county = props.get("county")?.asString
        val state = props.get("state")?.asString
        val country = props.get("country")?.asString

        // At least one human-readable field must be present
        if (name == null && street == null && district == null &&
            city == null && county == null && state == null && country == null
        ) return null

        return GeocodingResult(
            name = name,
            street = street,
            district = district,
            city = city,
            county = county,
            state = state,
            country = country,
            latitude = latitude,
            longitude = longitude,
        )
    }
}
