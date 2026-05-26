package com.oceanguard.ai.data.species

// TODO: swap this entire file for a real downloaded asset once the OpenCLIP encoder
//       (ViT-B/32, dim=512) and the reference-bank binary are available in production.
//       This file is DEMO ONLY — it wires 4 hard-coded species so the BioDex UI and
//       the RAG pipeline are exercisable end-to-end without any downloaded models.

import android.graphics.Bitmap
import com.oceanguard.ai.inference.species.SpeciesEmbedder
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex.Companion.ProtoSeed
import kotlin.math.abs

/**
 * Hard-coded catalog and index for 4 representative marine species.
 *
 * Species covered (WoRMS AphiaIDs):
 *  - 159559  Amphiprion ocellaris   (Ocellaris clownfish)
 *  - 137205  Caretta caretta        (Loggerhead sea turtle) — cosmopolitan
 *  - 140605  Octopus vulgaris       (Common octopus)
 *  - 135305  Aurelia aurita         (Moon jellyfish) — cosmopolitan
 *
 * Prototype vectors are synthetic unit spikes; the [SampleQueryEmbedder]
 * produces the matching spike for any query, guaranteeing a RAG_CORE hit so
 * the full pipeline can be exercised in demos and CI tests.
 *
 * DEMO ONLY — do NOT ship these vectors in production.
 */
object SampleSpeciesData {

    // Index positions of the spike dims, one per species (0-based).
    private const val SPIKE_CLOWNFISH  = 0
    private const val SPIKE_TURTLE     = 1
    private const val SPIKE_OCTOPUS    = 2
    private const val SPIKE_JELLYFISH  = 3

    private val CLOWNFISH = SpeciesCatalogEntry(
        speciesKey   = "159559",
        aphiaId      = 159559L,
        scientificName = "Amphiprion ocellaris",
        commonNames  = mapOf(
            "en" to "Ocellaris clownfish",
            "es" to "Pez payaso",
            "fr" to "Poisson-clown",
            "de" to "Falscher Clownfisch",
            "it" to "Pesce pagliaccio",
            "pt" to "Peixe-palhaço",
        ),
        spritePath   = null,
        ecoregions   = emptyList(),
        cosmopolitan = false,
        descriptions = emptyMap(),
        iucnStatus   = null,
    )

    private val TURTLE = SpeciesCatalogEntry(
        speciesKey   = "137205",
        aphiaId      = 137205L,
        scientificName = "Caretta caretta",
        commonNames  = mapOf(
            "en" to "Loggerhead sea turtle",
            "es" to "Tortuga boba",
            "fr" to "Tortue caouanne",
            "de" to "Unechte Karettschildkröte",
            "it" to "Tartaruga comune",
            "pt" to "Tartaruga-cabeçuda",
        ),
        spritePath   = null,
        ecoregions   = emptyList(),
        cosmopolitan = true,
        descriptions = emptyMap(),
        iucnStatus   = null,
    )

    private val OCTOPUS = SpeciesCatalogEntry(
        speciesKey   = "140605",
        aphiaId      = 140605L,
        scientificName = "Octopus vulgaris",
        commonNames  = mapOf(
            "en" to "Common octopus",
            "es" to "Pulpo común",
            "fr" to "Pieuvre commune",
            "de" to "Gemeiner Oktopus",
            "it" to "Polpo comune",
            "pt" to "Polvo-comum",
        ),
        spritePath   = null,
        ecoregions   = emptyList(),
        cosmopolitan = false,
        descriptions = emptyMap(),
        iucnStatus   = null,
    )

    private val JELLYFISH = SpeciesCatalogEntry(
        speciesKey   = "135305",
        aphiaId      = 135305L,
        scientificName = "Aurelia aurita",
        commonNames  = mapOf(
            "en" to "Moon jellyfish",
            "es" to "Medusa común",
            "fr" to "Méduse commune",
            "de" to "Ohrenqualle",
            "it" to "Medusa comune",
            "pt" to "Alforreca-lua",
        ),
        spritePath   = null,
        ecoregions   = emptyList(),
        cosmopolitan = true,
        descriptions = emptyMap(),
        iucnStatus   = null,
    )

    /** Returns the 4 demo catalog entries. */
    fun catalogEntries(): List<SpeciesCatalogEntry> =
        listOf(CLOWNFISH, TURTLE, OCTOPUS, JELLYFISH)

    /**
     * Builds an in-memory [SpeciesReferenceIndex] backed by 4 synthetic spike
     * prototypes.
     *
     * Each prototype is a unit vector with a single non-zero component (the
     * "spike"), placed at a distinct dimension index so the 4 species are
     * perfectly orthogonal to each other.  This guarantees cosine = 1.0 when
     * [SampleQueryEmbedder] produces the matching spike.
     *
     * DEMO ONLY — prototypes must be replaced with real OpenCLIP embeddings.
     */
    fun buildIndex(): SpeciesReferenceIndex {
        val catalog = catalogEntries().associateBy { it.speciesKey }
        val seeds = listOf(
            ProtoSeed(CLOWNFISH.speciesKey,  CLOWNFISH.scientificName,  spikeVector(SPIKE_CLOWNFISH)),
            ProtoSeed(TURTLE.speciesKey,     TURTLE.scientificName,     spikeVector(SPIKE_TURTLE)),
            ProtoSeed(OCTOPUS.speciesKey,    OCTOPUS.scientificName,    spikeVector(SPIKE_OCTOPUS)),
            ProtoSeed(JELLYFISH.speciesKey,  JELLYFISH.scientificName,  spikeVector(SPIKE_JELLYFISH)),
        )
        return SpeciesReferenceIndex.inMemory(catalog, seeds)
    }

    /**
     * Returns a [SpeciesEmbedder] that maps any [Bitmap] to the prototype
     * vector of one of the 4 demo species.
     *
     * The species is selected deterministically from the bitmap's hash code
     * modulo 4, so different mock bitmaps land on different species in tests.
     * The output is always a unit spike, guaranteeing cosine ≈ 1.0 against the
     * corresponding prototype in [buildIndex].
     *
     * DEMO ONLY — replace with [OnnxSpeciesEmbedder] once the real encoder is
     * available.
     */
    fun embedder(): SpeciesEmbedder = SampleQueryEmbedder()

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Returns a unit vector with 1.0 at [dim] and 0.0 elsewhere. */
    internal fun spikeVector(dim: Int): FloatArray {
        val v = FloatArray(SpeciesEmbedder.EMBEDDING_DIM)
        v[dim] = 1.0f
        return v
    }

    /**
     * Deterministic demo [SpeciesEmbedder].
     *
     * Maps any [Bitmap] to the spike prototype at index `abs(bitmap.hashCode()) % 4`.
     * isReady is always true; no ONNX model is loaded.
     *
     * DEMO ONLY — not for production use.
     */
    internal class SampleQueryEmbedder : SpeciesEmbedder {

        override val isReady: Boolean = true

        override suspend fun embed(bitmap: Bitmap): FloatArray {
            val spikeIdx = abs(bitmap.hashCode()) % 4
            return spikeVector(spikeIdx)
        }

        override fun close() { /* no-op: no native resources */ }
    }
}
