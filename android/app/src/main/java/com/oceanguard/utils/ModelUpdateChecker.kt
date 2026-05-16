package com.oceanguard.ai.utils

import android.util.Log
import com.google.gson.JsonParser
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.model.BaseModelRef
import com.oceanguard.ai.data.model.BestModel
import com.oceanguard.ai.data.model.ModelManifest
import com.oceanguard.ai.data.model.ModelMetrics
import com.oceanguard.ai.data.model.TrainingInfo
import com.oceanguard.ai.inference.VlmModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Result of a model update check — a newer fine-tuned model is available.
 */
data class ModelUpdate(
    val version: String,
    val sizeMb: Int,
    val deltaVsBase: Double,
    val mapAt50: Double,
    val releaseNotes: String,
    val downloadUrl: String,
    val filename: String,
)

/**
 * Checks the HuggingFace manifest for a newer OceanGuard fine-tuned model.
 * Rate-limited to one network call per 24 hours. Fails silently on errors.
 * Mirrors the exact pattern used by [UpdateChecker].
 */
class ModelUpdateChecker(private val settings: SettingsRepository) {

    private companion object {
        const val TAG = "ModelUpdateChecker"
        const val CHECK_INTERVAL_MS = 24 * 3600 * 1000L
        const val MANIFEST_URL =
            "https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/resolve/main/models_manifest.json"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a [ModelUpdate] when a newer fine-tuned model is available,
     * or null if within rate-limit window, network unreachable, up-to-date,
     * or the user has skipped this version.
     */
    suspend fun check(): ModelUpdate? {
        val lastCheck = settings.lastModelCheckMs.first()
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return null

        val manifest = runCatching { fetchManifest() }
            .onFailure { Log.w(TAG, "Manifest fetch failed: ${it.message}") }
            .getOrNull() ?: return null

        settings.setLastModelCheckMs(System.currentTimeMillis())

        val best = manifest.bestModel
        val installed = settings.installedFinetunedVersion.first()
        if (best.version == installed) return null

        val skipped = settings.skippedFinetunedVersion.first()
        if (best.version == skipped) return null

        return ModelUpdate(
            version     = best.version,
            sizeMb      = best.sizeMb,
            deltaVsBase = best.metrics?.map50DeltaVsBase ?: 0.0,
            mapAt50     = best.metrics?.map50 ?: 0.0,
            releaseNotes = best.releaseNotes,
            downloadUrl  = best.url,
            filename     = best.filename,
        )
    }

    /**
     * Fetches the manifest from network, falling back to cached JSON on failure.
     * On successful fetch the result is persisted to DataStore for offline use.
     * Returns null only when both network and cache are unavailable.
     */
    suspend fun fetchManifestCached(): ModelManifest? {
        val live = runCatching { fetchManifest() }.getOrNull()
        if (live != null) {
            settings.setCachedModelManifest(manifestToJson(live))
            return live
        }
        val cached = settings.cachedModelManifest.first()
        return if (cached.isBlank()) null
        else runCatching { parseManifest(cached) }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // Internal — network + parsing
    // -----------------------------------------------------------------------

    internal suspend fun fetchManifest(): ModelManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(MANIFEST_URL).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body?.string() ?: error("Empty body")
            parseManifest(body)
        }
    }

    private fun parseManifest(json: String): ModelManifest {
        val root = JsonParser.parseString(json).asJsonObject
        val bm = root.getAsJsonObject("best_model")

        val metrics = bm.getAsJsonObject("metrics")?.let { m ->
            ModelMetrics(
                map50                  = m.get("map_50")?.asDouble ?: 0.0,
                map50DeltaVsBase       = m.get("map_50_delta_vs_base")?.asDouble ?: 0.0,
                baseMap50              = m.get("base_map_50")?.asDouble ?: 0.0,
                relativeImprovementPct = m.get("relative_improvement_pct")?.asDouble ?: 0.0,
                jsonValidity           = m.get("json_validity")?.asDouble ?: 0.0,
                latencySPerImage       = m.get("latency_s_per_image")?.asDouble ?: 0.0,
                predictionsEmitted     = m.get("predictions_emitted")?.asInt ?: 0,
            )
        }

        val training = bm.getAsJsonObject("training")?.let { t ->
            TrainingInfo(
                experimentId       = t.get("experiment_id")?.asString ?: "",
                dataset            = t.get("dataset")?.asString ?: "",
                trainSamples       = t.get("train_samples")?.asInt ?: 0,
                testSamples        = t.get("test_samples")?.asInt ?: 0,
                syntheticRatio     = t.get("synthetic_ratio")?.asDouble ?: 0.0,
                loraRank           = t.get("lora_rank")?.asInt ?: 0,
                loraAlpha          = t.get("lora_alpha")?.asInt ?: 0,
                epochs             = t.get("epochs")?.asInt ?: 0,
                effectiveBatchSize = t.get("effective_batch_size")?.asInt ?: 0,
                optimizer          = t.get("optimizer")?.asString ?: "",
                learningRate       = t.get("learning_rate")?.asDouble ?: 0.0,
            )
        }

        val baseModel = bm.getAsJsonObject("base_model")?.let { b ->
            BaseModelRef(
                tier         = b.get("tier")?.asString ?: "",
                provider     = b.get("provider")?.asString ?: "",
                baseFilename = b.get("base_filename")?.asString ?: "",
            )
        }

        val bestModel = BestModel(
            id                = bm.get("id")?.asString ?: "",
            version           = bm.get("version")?.asString ?: "0.0.0",
            releaseDate       = bm.get("release_date")?.asString ?: "",
            filename          = bm.get("filename")?.asString
                ?: VlmModelManager.GEMMA4_LITERTLM_FINETUNED_FILENAME,
            url               = bm.get("url")?.asString ?: "",
            sizeBytes         = bm.get("size_bytes")?.asLong ?: 0L,
            sizeLabel         = bm.get("size_label")?.asString ?: "",
            sha256            = bm.get("sha256")?.asString ?: "",
            baseModel         = baseModel,
            training          = training,
            metrics           = metrics,
            releaseNotes      = bm.get("release_notes")?.asString ?: "",
            minimumAppVersion = bm.get("minimum_app_version")?.asString ?: "",
            license           = bm.get("license")?.asString ?: "apache-2.0",
        )

        return ModelManifest(
            schemaVersion = root.get("schema_version")?.asInt ?: 1,
            manifestUrl   = root.get("manifest_url")?.asString ?: "",
            updatedAt     = root.get("updated_at")?.asString ?: "",
            bestModel     = bestModel,
        )
    }

    /**
     * Minimal JSON serialization for DataStore caching.
     * Uses Gson-style string escaping via [jsonStr] — no extra library needed.
     */
    private fun manifestToJson(m: ModelManifest): String {
        val bm = m.bestModel
        val mt = bm.metrics
        return buildString {
            append("""{"schema_version":${m.schemaVersion}""")
            append(""","manifest_url":${jsonStr(m.manifestUrl)}""")
            append(""","updated_at":${jsonStr(m.updatedAt)}""")
            append(""","best_model":{""")
            append(""""id":${jsonStr(bm.id)}""")
            append(""","version":${jsonStr(bm.version)}""")
            append(""","release_date":${jsonStr(bm.releaseDate)}""")
            append(""","filename":${jsonStr(bm.filename)}""")
            append(""","url":${jsonStr(bm.url)}""")
            append(""","size_bytes":${bm.sizeBytes}""")
            append(""","size_label":${jsonStr(bm.sizeLabel)}""")
            append(""","sha256":${jsonStr(bm.sha256)}""")
            append(""","release_notes":${jsonStr(bm.releaseNotes)}""")
            append(""","minimum_app_version":${jsonStr(bm.minimumAppVersion)}""")
            append(""","license":${jsonStr(bm.license)}""")
            if (mt != null) {
                append(""","metrics":{"map_50":${mt.map50}""")
                append(""","map_50_delta_vs_base":${mt.map50DeltaVsBase}""")
                append(""","base_map_50":${mt.baseMap50}""")
                append(""","relative_improvement_pct":${mt.relativeImprovementPct}""")
                append(""","json_validity":${mt.jsonValidity}""")
                append(""","latency_s_per_image":${mt.latencySPerImage}""")
                append(""","predictions_emitted":${mt.predictionsEmitted}}""")
            } else {
                append(""","metrics":null""")
            }
            append("}}")
        }
    }

    /** Wraps [s] in JSON double-quotes with minimal escaping for DataStore caching. */
    private fun jsonStr(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
