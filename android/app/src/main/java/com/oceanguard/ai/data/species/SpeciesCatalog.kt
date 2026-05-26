package com.oceanguard.ai.data.species

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * In-memory catalog parsed lazily from `species_catalog_vN.json`.
 *
 * The JSON file is downloaded from HuggingFace via VlmModelManager and stored
 * at `getExternalFilesDir/models/species/species_catalog_v1.json`. The catalog
 * is loaded once on first access and kept in-process; it is typically 50-200 KB.
 *
 * Thread-safety: [load] is idempotent and the catalog file is read-only after
 * download. Callers should call [load] from a background dispatcher before the
 * first [byKey] call if they want to avoid the blocking I/O on the first call.
 *
 * @param catalogFile  Path to the downloaded JSON catalog file.
 */
class SpeciesCatalog(private val catalogFile: File) {

    private val gson = Gson()
    private val entryListType = object : TypeToken<List<SpeciesCatalogEntry>>() {}.type

    @Volatile
    private var entries: Map<String, SpeciesCatalogEntry>? = null

    /**
     * Parses the catalog JSON and caches it. Safe to call multiple times;
     * subsequent calls are no-ops if the catalog is already loaded.
     *
     * @throws IllegalStateException if the file does not exist.
     */
    fun load() {
        if (entries != null) return
        check(catalogFile.exists()) {
            "Species catalog not found at ${catalogFile.absolutePath}. Download it first."
        }
        val list: List<SpeciesCatalogEntry> = gson.fromJson(
            catalogFile.readText(Charsets.UTF_8),
            entryListType,
        )
        entries = list.associateBy { it.speciesKey }
    }

    /**
     * Returns the [SpeciesCatalogEntry] for [speciesKey], or null if not found
     * or the catalog has not been loaded yet.
     */
    fun byKey(speciesKey: String): SpeciesCatalogEntry? = entries?.get(speciesKey)

    /** Returns all entries in the loaded catalog. Empty list if not loaded. */
    fun all(): List<SpeciesCatalogEntry> = entries?.values?.toList() ?: emptyList()

    /** Total number of catalogued species. 0 if not loaded yet. */
    fun size(): Int = entries?.size ?: 0

    /** Every speciesKey in the loaded catalog. Empty list if not loaded. */
    fun allKeys(): List<String> = entries?.keys?.toList() ?: emptyList()

    /** Alias of [size]; used by the BioDex UI for the "N / total" counter. */
    fun totalCount(): Int = size()

    /** True if the catalog has been loaded into memory. */
    fun isLoaded(): Boolean = entries != null
}
