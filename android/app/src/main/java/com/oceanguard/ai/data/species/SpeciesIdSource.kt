package com.oceanguard.ai.data.species

/**
 * Source of a species identification stored in [SpeciesObservation.idSource].
 *
 * The enum name is persisted as a string in Room so adding new entries is
 * backward-compatible with existing rows.
 */
enum class SpeciesIdSource {
    /**
     * High-confidence RAG match: cosineScore ≥ τ_alta AND margin(top1−top2) ≥ δ.
     * Species is in the curated nucleus catalogue.
     */
    RAG_CORE,

    /**
     * Low-confidence RAG match: τ_baja ≤ cosineScore < τ_alta or margin < δ.
     * The RAG returned a tentative result; the VLM may have been invoked to
     * confirm or contradict. Shown with a "tentative" badge in the UI.
     */
    RAG_TENTATIVE,

    /**
     * VLM open-vocabulary fallback: cosineScore < τ_baja, or species not in
     * the curated catalogue. The VLM named the organism. [SpeciesObservation.uncatalogued]
     * is typically true for this source.
     */
    VLM_OPENVOCAB,
}
