package com.oceanguard.ai.data.species.enrichment

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Parsed record from the WoRMS REST API.
 *
 * @param aphiaId        WoRMS AphiaID (canonical identifier).
 * @param scientificName Accepted scientific name.
 * @param authority      Taxonomic authority string.
 * @param rank           Taxonomic rank, e.g. "Species".
 * @param isMarine       True when WoRMS considers the taxon marine.
 * @param status         Taxonomic status, e.g. "accepted", "unaccepted".
 */
data class WormsRecord(
    val aphiaId: Long,
    val scientificName: String,
    val authority: String?,
    val rank: String?,
    val isMarine: Boolean,
    val status: String?,
)

/**
 * Thin HTTP client for the WoRMS REST API.
 *
 * Only taxonomy metadata is requested — no image or user location is ever sent.
 * All network calls are dispatched on [Dispatchers.IO]. Errors produce null
 * rather than propagating exceptions so callers can treat WoRMS as optional.
 *
 * API base: https://www.marinespecies.org/rest/
 */
class WormsClient {

    private companion object {
        const val TAG = "WormsClient"
        const val BASE_URL = "https://www.marinespecies.org/rest"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val USER_AGENT = "OceanGuardAI/BioDex (https://github.com/asferrer/OceanguardAI-App)"
    }

    /**
     * Fetches the WoRMS record for [aphiaId].
     *
     * Returns null if the request fails, the ID is not found (HTTP 204/404),
     * or the response cannot be parsed.
     */
    suspend fun byAphiaId(aphiaId: Long): WormsRecord? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/AphiaRecordByAphiaID/$aphiaId"
        fetchAndParse(url)
    }

    /**
     * Fetches the WoRMS record matching [name] (exact binomial, case-insensitive).
     *
     * Returns null on any error or when no match is found.
     */
    suspend fun byName(name: String): WormsRecord? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val url = "$BASE_URL/AphiaRecordsByName/$encoded?like=false&marine_only=false&offset=1"
        val json = get(url) ?: return@withContext null
        // The by-name endpoint returns a JSON array; take the first element.
        runCatching {
            val arr = JsonParser.parseString(json).asJsonArray
            if (arr.isEmpty) return@withContext null
            parseRecord(arr[0].asJsonObject)
        }.onFailure { Log.w(TAG, "Parse error for name='$name': ${it.message}") }
            .getOrNull()
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private fun fetchAndParse(url: String): WormsRecord? {
        val json = get(url) ?: return null
        return runCatching { parseRecord(JsonParser.parseString(json).asJsonObject) }
            .onFailure { Log.w(TAG, "Parse error for $url: ${it.message}") }
            .getOrNull()
    }

    /**
     * Pure function: parses a WoRMS JSON object into a [WormsRecord].
     * Exposed as internal so tests can exercise it without network.
     */
    internal fun parseRecord(obj: com.google.gson.JsonObject): WormsRecord {
        return WormsRecord(
            aphiaId        = obj.get("AphiaID")?.asLong ?: 0L,
            scientificName = obj.get("scientificname")?.asString ?: "",
            authority      = obj.get("authority")?.takeIf { !it.isJsonNull }?.asString,
            rank           = obj.get("rank")?.takeIf { !it.isJsonNull }?.asString,
            isMarine       = obj.get("isMarine")?.takeIf { !it.isJsonNull }?.asInt == 1,
            status         = obj.get("status")?.takeIf { !it.isJsonNull }?.asString,
        )
    }

    /** Opens a GET connection with shared timeout/User-Agent settings. */
    private fun get(urlString: String): String? {
        return runCatching {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.d(TAG, "HTTP ${conn.responseCode} for $urlString")
                    return@runCatching null
                }
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "Network error for $urlString: ${it.message}") }
            .getOrNull()
    }
}
