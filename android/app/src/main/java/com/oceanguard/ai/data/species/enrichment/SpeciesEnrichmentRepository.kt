package com.oceanguard.ai.data.species.enrichment

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oceanguard.ai.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/**
 * Repository that provides online species enrichment data (WoRMS, GBIF, iNaturalist).
 *
 * PRIVACY CONTRACT
 * ----------------
 * - This class NEVER transmits the user's image or geographic location.
 * - Only the WoRMS AphiaID or scientific name is sent to external APIs.
 * - Network calls only happen when the user has explicitly opted in via
 *   [SettingsRepository.speciesOnlineEnrichmentEnabled] (default: false).
 *
 * CACHE STRATEGY
 * --------------
 * Enrichment records are stored as individual JSON files under:
 *   `<filesDir>/species_enrichment/<cacheKey>.json`
 * The cache key is the AphiaID (preferred) or URL-safe scientific name.
 * TTL is [CACHE_TTL_MS] (30 days). Stale records are re-fetched on next call.
 * Files are read/written with simple I/O — no Room dependency is introduced.
 *
 * @param context            Android context (used for [Context.getFilesDir]).
 * @param settingsRepository Settings repository to check the opt-in flag.
 * @param wormsClient        WoRMS HTTP client (injectable for tests).
 * @param gbifClient         GBIF HTTP client (injectable for tests).
 * @param iNatClient         iNaturalist HTTP client (injectable for tests).
 */
class SpeciesEnrichmentRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    internal val wormsClient: WormsClient = WormsClient(),
    internal val gbifClient: GbifClient = GbifClient(),
    internal val iNatClient: INaturalistClient = INaturalistClient(),
) {

    private companion object {
        const val TAG = "SpeciesEnrichRepo"
        const val CACHE_DIR_NAME = "species_enrichment"
        /** 30 days in milliseconds. */
        const val CACHE_TTL_MS = 30L * 24 * 3600 * 1000
    }

    private val cacheDir: File
        get() = File(context.filesDir, CACHE_DIR_NAME).also { it.mkdirs() }

    /**
     * Returns enrichment data for the given species, or null when:
     * - the user has not opted in ([SettingsRepository.speciesOnlineEnrichmentEnabled] = false), or
     * - no network is available and no valid cache entry exists, or
     * - all API calls fail.
     *
     * When a valid cache entry exists it is returned immediately without any
     * network call, regardless of connectivity.
     *
     * PRIVACY: Only [aphiaId] or [scientificName] is used in API requests.
     * The caller's image and location are NOT accepted by this function.
     */
    suspend fun enrich(aphiaId: Long?, scientificName: String): SpeciesEnrichment? {
        if (!settingsRepository.speciesOnlineEnrichmentEnabled.first()) {
            Log.d(TAG, "Online enrichment disabled — returning null without network call")
            return null
        }

        val key = cacheKey(aphiaId, scientificName)
        val cached = readCache(key)
        if (cached != null) {
            Log.d(TAG, "Cache hit for key=$key (age ${System.currentTimeMillis() - cached.fetchedAt} ms)")
            return cached
        }

        Log.d(TAG, "Cache miss for key=$key — fetching from APIs")
        val enrichment = fetchAndMerge(aphiaId, scientificName) ?: return null
        writeCache(key, enrichment)
        return enrichment
    }

    // -----------------------------------------------------------------------
    // Cache I/O
    // -----------------------------------------------------------------------

    /** Reads and validates a cache entry. Returns null if missing or expired. */
    internal fun readCache(key: String): SpeciesEnrichment? {
        val file = File(cacheDir, "$key.json")
        if (!file.exists()) return null
        return runCatching {
            val json = file.readText()
            val record = deserializeEnrichment(json)
            val age = System.currentTimeMillis() - record.fetchedAt
            if (age > CACHE_TTL_MS) {
                Log.d(TAG, "Cache expired for key=$key (age ${age}ms)")
                null
            } else {
                record
            }
        }.onFailure { Log.w(TAG, "Cache read error for key=$key: ${it.message}") }
            .getOrNull()
    }

    /** Persists an enrichment record to disk. Silently ignores I/O errors. */
    internal fun writeCache(key: String, enrichment: SpeciesEnrichment) {
        runCatching {
            File(cacheDir, "$key.json").writeText(serializeEnrichment(enrichment))
        }.onFailure { Log.w(TAG, "Cache write error for key=$key: ${it.message}") }
    }

    // -----------------------------------------------------------------------
    // Fetch + merge
    // -----------------------------------------------------------------------

    private suspend fun fetchAndMerge(
        aphiaId: Long?,
        scientificName: String,
    ): SpeciesEnrichment? {
        val sources = mutableListOf<String>()

        // WoRMS — primary taxonomy source
        val worms = if (aphiaId != null) {
            wormsClient.byAphiaId(aphiaId)
        } else {
            wormsClient.byName(scientificName)
        }
        if (worms != null) sources += "WoRMS"

        // GBIF — distribution data
        val gbif = gbifClient.byName(worms?.scientificName ?: scientificName)
        if (gbif != null) sources += "GBIF"

        // iNaturalist — representative photo + conservation status
        val inat = iNatClient.byName(worms?.scientificName ?: scientificName)
        if (inat != null) sources += "iNaturalist"

        if (sources.isEmpty()) {
            Log.w(TAG, "All API calls failed for '$scientificName'")
            return null
        }

        return SpeciesEnrichment(
            aphiaId                = worms?.aphiaId ?: aphiaId,
            acceptedName           = worms?.scientificName,
            authority              = worms?.authority,
            rank                   = worms?.rank,
            iucnStatus             = inat?.iucnStatusCode,
            distributionSummary    = gbif?.distributionSummary,
            representativePhotoUrl = inat?.representativePhotoUrl,
            sources                = sources,
            fetchedAt              = System.currentTimeMillis(),
        )
    }

    // -----------------------------------------------------------------------
    // Serialization (pure JSON, no Gson reflection — avoids ProGuard issues)
    // -----------------------------------------------------------------------

    /**
     * Serializes a [SpeciesEnrichment] to a JSON string for disk caching.
     * Uses manual construction to avoid Gson reflection on data classes
     * (ProGuard/R8 strips field names under full minification).
     */
    internal fun serializeEnrichment(e: SpeciesEnrichment): String {
        val obj = JsonObject().apply {
            addProperty("aphiaId", e.aphiaId)
            addProperty("acceptedName", e.acceptedName)
            addProperty("authority", e.authority)
            addProperty("rank", e.rank)
            addProperty("iucnStatus", e.iucnStatus)
            addProperty("distributionSummary", e.distributionSummary)
            addProperty("representativePhotoUrl", e.representativePhotoUrl)
            add("sources", JsonArray().also { arr -> e.sources.forEach { arr.add(it) } })
            addProperty("fetchedAt", e.fetchedAt)
        }
        return obj.toString()
    }

    /**
     * Deserializes a [SpeciesEnrichment] from a JSON string.
     * Exposed as internal so tests can verify round-trip correctness.
     */
    internal fun deserializeEnrichment(json: String): SpeciesEnrichment {
        val obj = JsonParser.parseString(json).asJsonObject
        val sources = obj.getAsJsonArray("sources")
            ?.mapNotNull { it.takeIf { !it.isJsonNull }?.asString }
            ?: emptyList()
        return SpeciesEnrichment(
            aphiaId                = obj.get("aphiaId")?.takeIf { !it.isJsonNull }?.asLong,
            acceptedName           = obj.get("acceptedName")?.takeIf { !it.isJsonNull }?.asString,
            authority              = obj.get("authority")?.takeIf { !it.isJsonNull }?.asString,
            rank                   = obj.get("rank")?.takeIf { !it.isJsonNull }?.asString,
            iucnStatus             = obj.get("iucnStatus")?.takeIf { !it.isJsonNull }?.asString,
            distributionSummary    = obj.get("distributionSummary")?.takeIf { !it.isJsonNull }?.asString,
            representativePhotoUrl = obj.get("representativePhotoUrl")?.takeIf { !it.isJsonNull }?.asString,
            sources                = sources,
            fetchedAt              = obj.get("fetchedAt")?.asLong ?: 0L,
        )
    }

    // -----------------------------------------------------------------------
    // Cache key
    // -----------------------------------------------------------------------

    /**
     * Derives a filesystem-safe cache key.
     * AphiaID is preferred because it is stable; falls back to a sanitised
     * scientific name (spaces → underscores, non-alphanumeric stripped).
     */
    internal fun cacheKey(aphiaId: Long?, scientificName: String): String {
        if (aphiaId != null && aphiaId > 0L) return "aphia_$aphiaId"
        val safe = scientificName
            .lowercase()
            .replace(' ', '_')
            .filter { it.isLetterOrDigit() || it == '_' }
            .take(80)
        return "name_$safe"
    }
}
