package com.oceanguard.ai.inference.species

import kotlin.math.exp

/**
 * Platt-scaling calibration for species identification confidence.
 *
 * Raw cosine scores from [SpeciesReferenceIndex] are not probabilities.
 * Platt scaling fits a sigmoid to transform them into calibrated probabilities:
 *
 *   P(correct | score) = 1 / (1 + exp(-(A * score + B)))
 *
 * Default parameters (A=10.0, B=-3.5) are placeholders; calibrate against
 * a held-out validation set from `finetune/species/eval_retrieval.py` and
 * update before the first release.
 *
 * Decision thresholds:
 *   confidence ≥ τ_alta (0.60) AND margin ≥ δ (0.05) → RAG_CORE
 *   confidence ≥ τ_baja (0.30)                        → RAG_TENTATIVE
 *   confidence < τ_baja                               → unknown / VLM fallback
 *
 * The margin δ is the difference in raw cosine scores between rank-1 and rank-2.
 * A small margin means the index is uncertain between two close species.
 *
 * @param plattA  Sigmoid slope (calibrated offline).
 * @param plattB  Sigmoid bias (calibrated offline).
 * @param tauHigh Minimum calibrated confidence for RAG_CORE.
 * @param tauLow  Minimum calibrated confidence for RAG_TENTATIVE.
 * @param delta   Minimum rank-1 vs rank-2 cosine margin for RAG_CORE.
 */
class SpeciesConfidence(
    val plattA: Float = DEFAULT_PLATT_A,
    val plattB: Float = DEFAULT_PLATT_B,
    val tauHigh: Float = DEFAULT_TAU_HIGH,
    val tauLow: Float = DEFAULT_TAU_LOW,
    val delta: Float = DEFAULT_DELTA,
) {
    companion object {
        const val DEFAULT_PLATT_A  = 10.0f
        const val DEFAULT_PLATT_B  = -3.5f
        const val DEFAULT_TAU_HIGH = 0.60f
        const val DEFAULT_TAU_LOW  = 0.30f
        const val DEFAULT_DELTA    = 0.05f
    }

    /**
     * Maps a raw cosine [score] in [0,1] to a calibrated probability in [0,1].
     *
     * Uses the Platt sigmoid: 1 / (1 + exp(-(A*score + B))).
     */
    fun calibrate(score: Float): Float {
        val logit = plattA * score + plattB
        return (1.0f / (1.0f + exp(-logit.toDouble()))).toFloat()
    }

    /**
     * Decide the [com.oceanguard.ai.data.species.SpeciesIdSource] given the
     * top-1 calibrated [confidence] and the margin between top-1 and top-2 raw
     * cosine scores.
     *
     * @return  "RAG_CORE", "RAG_TENTATIVE", or null (→ VLM fallback).
     */
    fun idSource(confidence: Float, margin: Float): String? = when {
        confidence >= tauHigh && margin >= delta -> "RAG_CORE"
        confidence >= tauLow                     -> "RAG_TENTATIVE"
        else                                     -> null
    }

    /**
     * True if the top-1 score is below [tauLow], meaning the match is too
     * weak for any RAG attribution → VLM open-vocab fallback required.
     */
    fun isUnknown(confidence: Float): Boolean = confidence < tauLow
}
