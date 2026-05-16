package com.oceanguard.ai.data.model

/**
 * Parsed representation of models_manifest.json hosted on HuggingFace.
 * Only critical fields are required; all others default gracefully.
 * Serialized with Gson (same as UpdateChecker) — no KotlinX dependency added.
 */
data class ModelManifest(
    val schemaVersion: Int = 1,
    val manifestUrl: String = "",
    val updatedAt: String = "",
    val bestModel: BestModel,
)

data class BestModel(
    /** Experiment identifier, e.g. "exp10_real_full_v1". */
    val id: String = "",
    /** Semver string, e.g. "1.0.0". Critical field — used for version comparison. */
    val version: String,
    val releaseDate: String = "",
    /** Filename on disk and on HuggingFace. Critical field. */
    val filename: String,
    /** Direct HuggingFace download URL. Critical field. */
    val url: String,
    /** File size in bytes. Critical field — used for size display. */
    val sizeBytes: Long = 0L,
    val sizeLabel: String = "",
    val sha256: String = "",
    val baseModel: BaseModelRef? = null,
    val training: TrainingInfo? = null,
    val metrics: ModelMetrics? = null,
    val releaseNotes: String = "",
    val minimumAppVersion: String = "",
    val license: String = "apache-2.0",
) {
    /** Size rounded to MB, safe even when sizeBytes is 0. */
    val sizeMb: Int get() = if (sizeBytes > 0) (sizeBytes / 1_000_000).toInt() else 0
}

data class BaseModelRef(
    val tier: String = "",
    val provider: String = "",
    val baseFilename: String = "",
)

data class TrainingInfo(
    val experimentId: String = "",
    val dataset: String = "",
    val trainSamples: Int = 0,
    val testSamples: Int = 0,
    val syntheticRatio: Double = 0.0,
    val loraRank: Int = 0,
    val loraAlpha: Int = 0,
    val epochs: Int = 0,
    val effectiveBatchSize: Int = 0,
    val optimizer: String = "",
    val learningRate: Double = 0.0,
)

data class ModelMetrics(
    /** mAP@0.5 of the fine-tuned model. Critical field. */
    val map50: Double = 0.0,
    /** Delta vs base model mAP@0.5. Critical field for display badge. */
    val map50DeltaVsBase: Double = 0.0,
    val baseMap50: Double = 0.0,
    val relativeImprovementPct: Double = 0.0,
    val jsonValidity: Double = 0.0,
    val latencySPerImage: Double = 0.0,
    val predictionsEmitted: Int = 0,
    val perClassMap50: Map<String, Double> = emptyMap(),
)
