package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import android.util.Log
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.species.SpeciesIdSource

/**
 * Immutable result of one organism identification within a single image.
 *
 * @param speciesKey      Matched species key (from catalogue), or a generated
 *                        "uncat:<slug>" key for uncatalogued organisms.
 * @param scientificName  Scientific name (from catalogue or VLM suggestion).
 * @param idSource        How the identification was produced.
 * @param cosineScore     Raw cosine similarity (0 when VLM_OPENVOCAB).
 * @param confidence      Platt-calibrated probability in [0, 1].
 * @param vlmDescription  Description / confirmation text. Null when VLM not invoked.
 * @param bbox            Organism bounding box in original image pixels; null
 *                        when the full frame was used (no localisation).
 * @param geoMatchLevel   Name of [GeoMatchLevel] enum entry; null = no GPS.
 * @param outOfRange      True when matched outside known distribution range.
 * @param uncatalogued    True for open-vocab VLM results with no catalogue entry.
 * @param freeLabel       VLM-suggested organism name for open-vocab results.
 */
data class IdentificationResult(
    val speciesKey: String,
    val scientificName: String,
    val idSource: SpeciesIdSource,
    val cosineScore: Float,
    val confidence: Float,
    val vlmDescription: String? = null,
    val bbox: BoundingBox? = null,
    val geoMatchLevel: String? = null,
    val outOfRange: Boolean = false,
    val uncatalogued: Boolean = false,
    val freeLabel: String? = null,
)

/**
 * Orchestrates the full BioDex identification pipeline for a single image.
 *
 * Sequence (per crop from [locator]):
 * 1. Resolve GPS → [MarineRegion] (if [resolver] + [Location] are available).
 * 2. Embed crop with [embedder].
 * 3. Search [index] with optional region + [sensitivity].
 * 4. Decide [SpeciesIdSource] via [confidence]:
 *    - RAG_CORE      → emit result immediately (no VLM to save latency).
 *    - RAG_TENTATIVE → call [describer.confirmOrDescribe]; attach description.
 *    - null (unknown)→ call [describer.describeOpenVocab] → VLM_OPENVOCAB.
 * 5. Propagate [outOfRange] when geo-filter fell back to GLOBAL.
 *
 * All dependencies are injected through interfaces — no I/O, no Room, no
 * Android Context required. Fully testable on plain JVM.
 *
 * @param embedder   L2-normalised image embedding.
 * @param index      Brute-force cosine index over reference prototypes.
 * @param resolver   Optional MEOW raster resolver; null = skip geo-filter.
 * @param confidence Platt calibration + threshold parameters.
 * @param describer  VLM description / confirmation engine ([DescriberPort]).
 * @param locator    Organism detection / cropping engine ([CropProvider]).
 */
class SpeciesIdentifier(
    private val embedder:   SpeciesEmbedder,
    private val index:      SpeciesReferenceIndex,
    private val resolver:   RegionResolverPort? = null,
    private val confidence: SpeciesConfidence = SpeciesConfidence(),
    private val describer:  DescriberPort = SpeciesDescriber(),
    private val locator:    CropProvider = OrganismLocator(),
) {
    companion object {
        private const val TAG = "SpeciesIdentifier"
        private const val TOP_K = 5
    }

    /**
     * Lightweight per-stage latency tracker. Logs the last timing of each stage
     * (locate / encode / search / describe / total) plus a running p50 so the
     * BioDex latency is measurable straight from logcat without a profiler.
     * Thread-confined to the identify coroutine; sample lists are tiny.
     */
    private val latency = StageLatency(TAG)

    /**
     * Identify all marine organisms found in [bitmap].
     *
     * @param bitmap      Full-resolution input frame.
     * @param location    GPS position; null = no geo-filter.
     * @param sensitivity Geographic filter level (default BALANCED).
     * @param language    BCP-47 language for VLM descriptions.
     * @return            One [IdentificationResult] per detected organism.
     *                    Empty only when [embedder] is not ready.
     */
    suspend fun identify(
        bitmap: Bitmap,
        location: Location? = null,
        sensitivity: GeoFilterSensitivity = GeoFilterSensitivity.BALANCED,
        language: String = "en",
    ): List<IdentificationResult> {
        if (!embedder.isReady) {
            Log.w(TAG, "Embedder not ready — skipping identification")
            return emptyList()
        }

        val totalStart = System.currentTimeMillis()
        val region = resolveRegion(location)
        val locateStart = System.currentTimeMillis()
        val crops  = locator.locate(bitmap)
        latency.record("locate", System.currentTimeMillis() - locateStart)
        Log.d(TAG, "Identifying ${crops.size} crop(s), region=${region?.ecoregionName}")

        val results = crops.mapNotNull { crop ->
            runCatching {
                identifyCrop(crop, region, sensitivity, language)
            }.onFailure { e ->
                Log.w(TAG, "Crop identification failed: ${e.message}")
            }.getOrNull()
        }
        latency.record("total", System.currentTimeMillis() - totalStart)
        latency.logSummary()
        return results
    }

    // -----------------------------------------------------------------------
    // Per-crop identification
    // -----------------------------------------------------------------------

    private suspend fun identifyCrop(
        crop: OrganismCrop,
        region: MarineRegion?,
        sensitivity: GeoFilterSensitivity,
        language: String,
    ): IdentificationResult {
        val encodeStart = System.currentTimeMillis()
        val emb     = embedder.embed(crop.crop)
        latency.record("encode", System.currentTimeMillis() - encodeStart)
        val searchStart = System.currentTimeMillis()
        val matches = index.search(
            emb         = emb,
            region      = region,
            topK        = TOP_K,
            sensitivity = sensitivity,
            hierarchy   = hierarchyFn(),
        )
        latency.record("search", System.currentTimeMillis() - searchStart)

        if (matches.isEmpty()) return openVocabResult(crop, region, language)

        val top1       = matches[0]
        val top2       = matches.getOrNull(1)
        val margin     = top1.cosineScore - (top2?.cosineScore ?: 0f)
        val source     = confidence.idSource(top1.confidence, margin)
        val outOfRange = top1.geoMatchLevel == GeoMatchLevel.GLOBAL && region != null

        return when (source) {
            "RAG_CORE"      -> ragCoreResult(top1, crop, outOfRange)
            "RAG_TENTATIVE" -> ragTentativeResult(top1, crop, region, language, outOfRange)
            else            -> openVocabResult(crop, region, language)
        }
    }

    // -----------------------------------------------------------------------
    // Result builders
    // -----------------------------------------------------------------------

    private fun ragCoreResult(
        top1: SpeciesMatch,
        crop: OrganismCrop,
        outOfRange: Boolean,
    ) = IdentificationResult(
        speciesKey     = top1.speciesKey,
        scientificName = top1.scientificName,
        idSource       = SpeciesIdSource.RAG_CORE,
        cosineScore    = top1.cosineScore,
        confidence     = top1.confidence,
        bbox           = crop.bbox,
        geoMatchLevel  = top1.geoMatchLevel.name,
        outOfRange     = outOfRange,
    )

    private suspend fun ragTentativeResult(
        top1: SpeciesMatch,
        crop: OrganismCrop,
        region: MarineRegion?,
        language: String,
        outOfRange: Boolean,
    ): IdentificationResult {
        val describeStart = System.currentTimeMillis()
        val vlm = describer.confirmOrDescribe(
            crop                     = crop.crop,
            hypothesisKey            = top1.speciesKey,
            hypothesisScientificName = top1.scientificName,
            region                   = region,
            language                 = language,
        )
        latency.record("describe", System.currentTimeMillis() - describeStart)
        return IdentificationResult(
            speciesKey     = top1.speciesKey,
            scientificName = top1.scientificName,
            idSource       = SpeciesIdSource.RAG_TENTATIVE,
            cosineScore    = top1.cosineScore,
            confidence     = top1.confidence,
            vlmDescription = vlm.description.ifBlank { null },
            bbox           = crop.bbox,
            geoMatchLevel  = top1.geoMatchLevel.name,
            outOfRange     = outOfRange,
        )
    }

    private suspend fun openVocabResult(
        crop: OrganismCrop,
        region: MarineRegion?,
        language: String,
    ): IdentificationResult {
        val describeStart = System.currentTimeMillis()
        val vlm     = describer.describeOpenVocab(crop.crop, region, language)
        latency.record("describe", System.currentTimeMillis() - describeStart)
        val slug    = vlm.freeLabel?.replace(" ", "_")?.lowercase() ?: "unknown"
        val key     = "uncat:$slug"
        val sciName = vlm.freeLabel ?: "Unknown organism"
        return IdentificationResult(
            speciesKey     = key,
            scientificName = sciName,
            idSource       = SpeciesIdSource.VLM_OPENVOCAB,
            cosineScore    = 0f,
            confidence     = 0f,
            vlmDescription = vlm.description.ifBlank { null },
            bbox           = crop.bbox,
            uncatalogued   = true,
            freeLabel      = vlm.freeLabel,
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun resolveRegion(location: Location?): MarineRegion? {
        if (location == null || resolver == null) return null
        return try {
            resolver.resolve(location.latitude, location.longitude)
        } catch (e: Exception) {
            Log.w(TAG, "Region resolution failed: ${e.message}")
            null
        }
    }

    private fun hierarchyFn(): (Int) -> Pair<Int, Int>? =
        if (resolver != null) resolver::hierarchy else { _ -> null }
}
