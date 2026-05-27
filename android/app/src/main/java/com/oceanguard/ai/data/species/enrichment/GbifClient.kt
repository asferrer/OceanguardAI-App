package com.oceanguard.ai.data.species.enrichment

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Parsed record from the GBIF Species API.
 *
 * @param usageKey            GBIF internal species key.
 * @param canonicalName       Canonical binomial without authority.
 * @param distributionSummary Free-text summary assembled from GBIF distribution facets.
 * @param matchType           Match confidence reported by GBIF: "EXACT", "FUZZY", "NONE".
 */
data class GbifRecord(
    val usageKey: Int,
    val canonicalName: String?,
    val distributionSummary: String?,
    val matchType: String?,
)

/**
 * Thin HTTP client for the GBIF Species API.
 *
 * Only the scientific name is transmitted — no image or user location is sent.
 * All I/O is on [Dispatchers.IO]. Errors produce null (optional data source).
 *
 * API base: https://api.gbif.org/v1/
 */
class GbifClient {

    private companion object {
        const val TAG = "GbifClient"
        const val BASE_URL = "https://api.gbif.org/v1"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val USER_AGENT = "OceanGuardAI/BioDex (https://github.com/asferrer/OceanguardAI-App)"
        /** Maximum number of distribution country codes to include in the summary. */
        const val MAX_DISTRIBUTION_COUNTRIES = 8
    }

    /**
     * Matches [name] against the GBIF backbone taxonomy and returns a [GbifRecord]
     * that includes a brief distribution summary, or null on any error.
     */
    suspend fun byName(name: String): GbifRecord? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val matchUrl = "$BASE_URL/species/match?name=$encoded&strict=false"
        val matchJson = get(matchUrl) ?: return@withContext null

        val matchRecord = runCatching { parseMatch(matchJson) }
            .onFailure { Log.w(TAG, "Match parse error for '$name': ${it.message}") }
            .getOrNull() ?: return@withContext null

        if (matchRecord.usageKey == 0) return@withContext null

        val distSummary = fetchDistributionSummary(matchRecord.usageKey)
        matchRecord.copy(distributionSummary = distSummary)
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Fetches up to [MAX_DISTRIBUTION_COUNTRIES] country codes from the GBIF
     * occurrence facet endpoint and returns a comma-joined summary string.
     */
    private fun fetchDistributionSummary(usageKey: Int): String? {
        val url = "$BASE_URL/occurrence/search?taxonKey=$usageKey&facet=country&facetLimit=$MAX_DISTRIBUTION_COUNTRIES&limit=0"
        val json = get(url) ?: return null
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            val facets = root.getAsJsonArray("facets") ?: return@runCatching null
            if (facets.isEmpty) return@runCatching null
            val counts = facets[0].asJsonObject.getAsJsonArray("counts") ?: return@runCatching null
            val codes = counts.mapNotNull { it.asJsonObject.get("name")?.asString }.take(MAX_DISTRIBUTION_COUNTRIES)
            if (codes.isEmpty()) null else codes.joinToString(", ")
        }.onFailure { Log.w(TAG, "Distribution parse error: ${it.message}") }
            .getOrNull()
    }

    /**
     * Pure function: parses a GBIF species/match JSON response into a [GbifRecord].
     * Exposed as internal so tests can exercise it without network.
     */
    internal fun parseMatch(json: String): GbifRecord {
        val obj = JsonParser.parseString(json).asJsonObject
        return GbifRecord(
            usageKey      = obj.get("usageKey")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            canonicalName = obj.get("canonicalName")?.takeIf { !it.isJsonNull }?.asString,
            distributionSummary = null, // filled in by caller after distribution fetch
            matchType     = obj.get("matchType")?.takeIf { !it.isJsonNull }?.asString,
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
