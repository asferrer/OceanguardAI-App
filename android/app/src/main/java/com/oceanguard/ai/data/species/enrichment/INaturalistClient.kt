package com.oceanguard.ai.data.species.enrichment

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Parsed record from the iNaturalist Taxa API.
 *
 * @param taxonId            iNaturalist internal taxon ID.
 * @param preferredCommonName Common name in the default locale (en).
 * @param representativePhotoUrl Medium-resolution photo URL (publicly licensed).
 *                           This is iNaturalist's own curated taxon photo,
 *                           NOT a user-submitted observation photo.
 * @param iucnStatusCode     IUCN Red List category code if present, e.g. "VU".
 */
data class INaturalistRecord(
    val taxonId: Int,
    val preferredCommonName: String?,
    val representativePhotoUrl: String?,
    val iucnStatusCode: String?,
)

/**
 * Thin HTTP client for the iNaturalist Taxa API.
 *
 * Only the scientific name is transmitted — no image or user location is sent.
 * All I/O is on [Dispatchers.IO]. Errors produce null (optional data source).
 *
 * API base: https://api.inaturalist.org/v1/
 */
class INaturalistClient {

    private companion object {
        const val TAG = "INaturalistClient"
        const val BASE_URL = "https://api.inaturalist.org/v1"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val USER_AGENT = "OceanGuardAI/BioDex (https://github.com/asferrer/OceanguardAI-App)"

        /** Maps iNaturalist conservation status names to IUCN codes. */
        private val IUCN_CODE_MAP = mapOf(
            "extinct"                  to "EX",
            "extinct_in_the_wild"      to "EW",
            "critically_endangered"    to "CR",
            "endangered"               to "EN",
            "vulnerable"               to "VU",
            "near_threatened"          to "NT",
            "least_concern"            to "LC",
            "data_deficient"           to "DD",
            "not_evaluated"            to "NE",
        )
    }

    /**
     * Searches iNaturalist taxa for [name] and returns the best-matching record,
     * or null on any error or when no result is found.
     */
    suspend fun byName(name: String): INaturalistRecord? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val url = "$BASE_URL/taxa?q=$encoded&rank=species&per_page=1"
        val json = get(url) ?: return@withContext null
        runCatching { parseTaxa(json) }
            .onFailure { Log.w(TAG, "Parse error for '$name': ${it.message}") }
            .getOrNull()
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Pure function: parses an iNaturalist taxa JSON response and returns the
     * first result as an [INaturalistRecord], or null when results is empty.
     * Exposed as internal so tests can exercise it without network.
     */
    internal fun parseTaxa(json: String): INaturalistRecord? {
        val root = JsonParser.parseString(json).asJsonObject
        val results = root.getAsJsonArray("results") ?: return null
        if (results.isEmpty) return null

        val taxon = results[0].asJsonObject
        val photoUrl = taxon.getAsJsonObject("default_photo")
            ?.get("medium_url")?.takeIf { !it.isJsonNull }?.asString

        val iucnCode = taxon.get("conservation_status")
            ?.takeIf { !it.isJsonNull }
            ?.asJsonObject
            ?.get("status_name")?.takeIf { !it.isJsonNull }?.asString
            ?.lowercase()
            ?.let { IUCN_CODE_MAP[it] ?: it.uppercase().take(2) }

        return INaturalistRecord(
            taxonId              = taxon.get("id")?.asInt ?: 0,
            preferredCommonName  = taxon.get("preferred_common_name")?.takeIf { !it.isJsonNull }?.asString,
            representativePhotoUrl = photoUrl,
            iucnStatusCode       = iucnCode,
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
