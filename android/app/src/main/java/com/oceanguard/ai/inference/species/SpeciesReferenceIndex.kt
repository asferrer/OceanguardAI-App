package com.oceanguard.ai.inference.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory brute-force cosine similarity index over species prototype embeddings.
 *
 * ## Binary format of `species_index_vN.bin`
 *
 * The Python builder (`finetune/species/build_reference_bank.py`) MUST emit this
 * exact layout — document here so both sides stay in sync:
 *
 * ```
 * Header (32 bytes, little-endian):
 *   [0..3]   magic   = 0x53504558  ("SPEX" in ASCII, uint32)
 *   [4..7]   version = 1           (uint32)
 *   [8..11]  n       = number of prototype rows  (uint32)
 *   [12..15] dim     = embedding dimension, expected 512  (uint32)
 *   [16..31] reserved (zero-padded)
 *
 * Prototype rows (n × dim × 2 bytes each, float16, little-endian):
 *   Each row is a L2-normalised float16 vector of length [dim].
 *
 * Metadata rows (n entries, UTF-8 JSON lines):
 *   Each line is a JSON object:
 *   {"speciesKey":"126436","scientificName":"Caretta caretta","viewTag":"lateral","nRefs":6}
 *   Lines follow immediately after the binary prototype block.
 * ```
 *
 * Total file size: 32 + n*dim*2 + (metadata text) bytes.
 *
 * ## Search algorithm
 *
 * 1. Deserialize prototypes to float32 (upcast from float16 at load time).
 * 2. For a query embedding q (L2-normalised, dim=512):
 *    cosine(p_i, q) = dot(p_i, q)   (both L2-norm → cosine = dot product)
 * 3. Aggregate scores per species by taking the max across its prototypes.
 * 4. Apply geo-filter + soft-prior (multiply by [GeoPrior.g]).
 * 5. Sort descending, return top-[topK] as [SpeciesMatch].
 *
 * @param catalogEntries  Map from speciesKey → [SpeciesCatalogEntry] (loaded from catalog JSON).
 * @param confidence      Calibration parameters (Platt sigmoid + thresholds).
 */
class SpeciesReferenceIndex(
    private val catalogEntries: Map<String, SpeciesCatalogEntry>,
    private val confidence: SpeciesConfidence = SpeciesConfidence(),
) {
    companion object {
        private const val MAGIC = 0x53504558.toInt()
        private const val EXPECTED_VERSION = 1
        const val EMBEDDING_DIM = 512
    }

    // Parallel arrays: prototypes[i] is a float32 L2-norm vector; meta[i] is its metadata.
    private var prototypes: Array<FloatArray> = emptyArray()
    private var meta: Array<ProtoMeta> = emptyArray()

    @Volatile
    private var loaded = false

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    /**
     * Load prototypes from [indexFile]. Must be called before [search].
     * Idempotent: subsequent calls are no-ops if already loaded.
     *
     * Reads the binary header, float16 prototype block, and JSON metadata lines.
     *
     * @throws IllegalStateException if magic/version mismatch or dim != [EMBEDDING_DIM].
     * @throws java.io.IOException   on any I/O error.
     */
    fun load(indexFile: File) {
        if (loaded) return

        val bytes = indexFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.int
        check(magic == MAGIC) { "Bad magic 0x${magic.toString(16)}, expected 0x${MAGIC.toString(16)}" }
        val version = buf.int
        check(version == EXPECTED_VERSION) { "Unsupported index version $version" }
        val n = buf.int
        val dim = buf.int
        check(dim == EMBEDDING_DIM) { "Index dim=$dim, expected $EMBEDDING_DIM" }
        buf.position(32) // skip reserved bytes

        // Read float16 prototype block
        val protoBytes = n * dim * 2
        val protoBlock = ByteArray(protoBytes)
        buf.get(protoBlock)
        val protoProtos = Array(n) { i ->
            val row = FloatArray(dim)
            val base = i * dim * 2
            for (d in 0 until dim) {
                row[d] = float16ToFloat32(protoBlock, base + d * 2)
            }
            row
        }

        // Read metadata lines (UTF-8 JSON, remainder of the file)
        val metaText = bytes.copyOfRange(32 + protoBytes, bytes.size).toString(Charsets.UTF_8)
        val metaLines = metaText.trim().lines()
        check(metaLines.size == n) {
            "Metadata line count ${metaLines.size} != prototype count $n"
        }
        val parsedMeta = Array(n) { i -> parseMetaLine(metaLines[i]) }

        prototypes = protoProtos
        meta = parsedMeta
        loaded = true
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /**
     * Search the index for the [topK] best-matching species given query [emb].
     *
     * [emb] must be a L2-normalised float32 vector of dimension [EMBEDDING_DIM].
     *
     * When [region] is non-null the geo-filter is applied:
     *  - Hard-filter: species outside the realm (for BALANCED) or province (for STRICT)
     *    are excluded from the initial candidate set, UNLESS cosmopolitan or no data.
     *  - Soft-prior: adjusted_score = cosineScore * [GeoPrior.g](level).
     *  - Relajación: if after the hard-filter the best remaining score is below
     *    [SpeciesConfidence.tauLow], the search falls back to global (filter=OFF)
     *    with [SpeciesMatch.geoMatchLevel] = GLOBAL and [outOfRange] semantics
     *    propagated by the caller.
     *
     * When [region] is null (no GPS or filter=OFF) all species are searched.
     *
     * @param sensitivity Controls the hard-filter level (default BALANCED).
     * @return            Top-[topK] matches sorted by adjusted score descending.
     *                    May be shorter than [topK] if fewer species exist in
     *                    the index after filtering.
     */
    fun search(
        emb: FloatArray,
        region: MarineRegion?,
        topK: Int = 5,
        sensitivity: GeoFilterSensitivity = GeoFilterSensitivity.BALANCED,
        hierarchy: (Int) -> Pair<Int, Int>? = { null },
    ): List<SpeciesMatch> {
        require(emb.size == EMBEDDING_DIM) {
            "Query embedding dim=${emb.size}, expected $EMBEDDING_DIM"
        }
        if (!loaded || prototypes.isEmpty()) return emptyList()

        // Score each prototype
        val rawScores = FloatArray(prototypes.size) { i -> dotProduct(prototypes[i], emb) }

        // Aggregate per species (max-prototype strategy)
        val speciesScores = mutableMapOf<String, Float>()
        for (i in prototypes.indices) {
            val key = meta[i].speciesKey
            val current = speciesScores[key] ?: -1f
            if (rawScores[i] > current) speciesScores[key] = rawScores[i]
        }

        // Compute geo match level and apply prior; build candidates list
        fun buildCandidates(useFilter: Boolean): List<SearchCandidate> =
            speciesScores.entries.mapNotNull { (key, cosine) ->
                val entry = catalogEntries[key] ?: return@mapNotNull null
                val level = GeoPrior.matchLevel(entry, region, hierarchy)
                if (useFilter && !passesHardFilter(level, sensitivity, region)) return@mapNotNull null
                SearchCandidate(key, cosine, cosine * GeoPrior.g(level), level)
            }

        val filtered = buildCandidates(useFilter = true)

        // Relajación: if the geo-filtered set is empty OR the best calibrated score is
        // below tauLow, fall back to a global (unfiltered) search so we never return
        // an empty result just because of geography.
        val bestFilteredConfidence = filtered.maxOfOrNull { confidence.calibrate(it.cosine) } ?: 0f
        val needsRelaxation = region != null &&
            sensitivity != GeoFilterSensitivity.OFF &&
            (filtered.isEmpty() || confidence.isUnknown(bestFilteredConfidence))

        val effectiveCandidates: List<SearchCandidate> = if (needsRelaxation) {
            buildCandidates(useFilter = false)
                .map { c -> SearchCandidate(c.key, c.cosine, c.cosine, GeoMatchLevel.GLOBAL) }
        } else {
            filtered
        }

        // Sort by adjusted score descending, take top-k
        val top = effectiveCandidates.sortedByDescending { it.adjusted }.take(topK)

        return top.map { c ->
            val entry = catalogEntries[c.key]
            SpeciesMatch(
                speciesKey     = c.key,
                scientificName = entry?.scientificName ?: c.key,
                cosineScore    = c.cosine,
                confidence     = confidence.calibrate(c.cosine),
                geoMatchLevel  = c.level,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Internal data class for search candidates
    // -----------------------------------------------------------------------

    private data class SearchCandidate(
        val key: String,
        val cosine: Float,
        val adjusted: Float,
        val level: GeoMatchLevel,
    )

    // -----------------------------------------------------------------------
    // Internal helpers — pure math, no I/O
    // -----------------------------------------------------------------------

    /** Dot product of two equal-length float arrays. O(dim). */
    internal fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * IEEE 754 float16 → float32 conversion.
     *
     * Layout: 1 sign | 5 exponent | 10 mantissa (little-endian in [buf]).
     * Handles denormals, infinities, and NaN faithfully.
     */
    internal fun float16ToFloat32(buf: ByteArray, offset: Int): Float {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() and 0xFF
        val h = (hi shl 8) or lo

        val sign     = (h ushr 15) and 0x1
        val exponent = (h ushr 10) and 0x1F
        val mantissa =  h          and 0x3FF

        val bits: Int = when (exponent) {
            0    -> if (mantissa == 0) sign shl 31
                    else { // denormal
                        var m = mantissa
                        var e = -14
                        while (m and 0x400 == 0) { m = m shl 1; e-- }
                        (sign shl 31) or ((e + 127) shl 23) or ((m and 0x3FF) shl 13)
                    }
            0x1F -> if (mantissa == 0) (sign shl 31) or (0xFF shl 23) // ±Inf
                    else               (sign shl 31) or (0xFF shl 23) or (mantissa shl 13) // NaN
            else -> (sign shl 31) or ((exponent + 112) shl 23) or (mantissa shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    /**
     * True if [level] passes the hard-filter for [sensitivity].
     *
     * Cosmopolitan and GLOBAL (no-data) species ALWAYS pass regardless of sensitivity.
     */
    private fun passesHardFilter(
        level: GeoMatchLevel,
        sensitivity: GeoFilterSensitivity,
        region: MarineRegion?,
    ): Boolean {
        if (region == null || sensitivity == GeoFilterSensitivity.OFF) return true
        if (level == GeoMatchLevel.COSMOPOLITAN || level == GeoMatchLevel.GLOBAL) return true
        return when (sensitivity) {
            GeoFilterSensitivity.STRICT   -> level == GeoMatchLevel.ECOREGION || level == GeoMatchLevel.PROVINCE
            GeoFilterSensitivity.BALANCED -> level != GeoMatchLevel.GLOBAL
            GeoFilterSensitivity.OFF      -> true
        }
    }

    // -----------------------------------------------------------------------
    // Internal data class for prototype metadata
    // -----------------------------------------------------------------------

    private data class ProtoMeta(
        val speciesKey: String,
        val scientificName: String,
        val viewTag: String,
        val nRefs: Int,
    )

    /** Minimal JSON parse for a single metadata line (avoids a Gson dependency here). */
    private fun parseMetaLine(line: String): ProtoMeta {
        fun extract(key: String): String {
            val marker = "\"$key\":"
            val start = line.indexOf(marker)
            if (start < 0) return ""
            val valueStart = start + marker.length
            return if (line[valueStart] == '"') {
                val end = line.indexOf('"', valueStart + 1)
                if (end < 0) "" else line.substring(valueStart + 1, end)
            } else {
                var end = valueStart
                while (end < line.length && line[end] != ',' && line[end] != '}') end++
                line.substring(valueStart, end).trim()
            }
        }
        return ProtoMeta(
            speciesKey    = extract("speciesKey"),
            scientificName = extract("scientificName"),
            viewTag       = extract("viewTag"),
            nRefs         = extract("nRefs").toIntOrNull() ?: 0,
        )
    }
}
