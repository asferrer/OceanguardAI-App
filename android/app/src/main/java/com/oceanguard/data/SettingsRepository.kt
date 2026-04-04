package com.oceanguard.ai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Extension property to create a singleton DataStore instance.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "oceanguard_settings"
)

/**
 * Repository for user settings backed by Jetpack DataStore.
 *
 * Exposes all preferences as [Flow] streams so Compose UIs can collect
 * them reactively with [collectAsStateWithLifecycle].
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")
        val LANGUAGE = stringPreferencesKey("language")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val BATCH_MAX_IMAGES = stringPreferencesKey("batch_max_images")
        val VLM_ENABLED = booleanPreferencesKey("vlm_enabled")
        val LIVE_DETECTION_RESOLUTION = stringPreferencesKey("live_detection_resolution")
        val DETECTOR_PRECISION = stringPreferencesKey("detector_precision")
        val CONFIRM_CAPTURE = booleanPreferencesKey("confirm_capture")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val DEX_BACKFILL_COMPLETE = booleanPreferencesKey("dex_backfill_complete")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val GUIDED_TOUR_ACTIVE = booleanPreferencesKey("guided_tour_active")
        val DEV_MODE_ENABLED = booleanPreferencesKey("dev_mode_enabled")
        val LAST_UPDATE_CHECK_MS = longPreferencesKey("last_update_check_ms")
        val SKIPPED_VERSION = stringPreferencesKey("skipped_update_version")
        val VLM_MODEL_TIER = stringPreferencesKey("vlm_model_tier")
        val REPORT_AUDIENCE = stringPreferencesKey("report_audience")

        // Research contribution
        val CONTRIBUTE_CONSENT_GIVEN = booleanPreferencesKey("contribute_consent_given")
        val CONTRIBUTE_WIFI_ONLY     = booleanPreferencesKey("contribute_wifi_only")
        val CONTRIBUTE_DECLINE_COUNT = intPreferencesKey("contribute_decline_count")
    }

    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.7f
        const val DEFAULT_LANGUAGE = "es"
        const val DEFAULT_DARK_MODE = true
        const val DEFAULT_BATCH_MAX_IMAGES = 5
        const val DEFAULT_VLM_ENABLED = false
        const val DEFAULT_LIVE_DETECTION_RESOLUTION = 480
        const val DEFAULT_CONFIRM_CAPTURE = true
        const val DEFAULT_DETECTOR_PRECISION = "fp16"
        const val DEFAULT_VLM_MODEL_TIER = "balanced"
        const val DEFAULT_REPORT_AUDIENCE = "scientific"

        /** Supported audience modes for report generation. Keys match ReportAudience.fromKey(). */
        val REPORT_AUDIENCES = mapOf(
            "scientific" to "Scientific",
            "ngo"        to "NGO / Manager",
            "citizen"    to "Citizen",
        )

        /** Supported detector precision modes. INT8 removed: causes native
         *  SIGABRT on Exynos 2200 NNAPI delegate (irrecoverable crash). */
        val DETECTOR_PRECISIONS = mapOf(
            "fp16" to "FP16 (83 MB, NNAPI)",
        )

        /** All screen IDs included in the full guided tour (9 stops). */
        val GUIDED_TOUR_IDS = listOf(
            "home", "marinedex", "marinedex_detail", "achievements",
            "map", "reports", "history", "session_detail", "settings",
        )

        /** Supported languages for report generation. */
        val SUPPORTED_LANGUAGES = mapOf(
            "en" to "English",
            "es" to "Espa\u00f1ol",
            "fr" to "Fran\u00e7ais",
            "de" to "Deutsch",
            "it" to "Italiano",
            "pt" to "Portugu\u00eas",
        )
    }

    // -----------------------------------------------------------------------
    // Read flows
    // -----------------------------------------------------------------------

    /**
     * Detection confidence threshold (0.0 - 1.0).
     * Detections below this value are filtered out.
     */
    val confidenceThreshold: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONFIDENCE_THRESHOLD] ?: DEFAULT_CONFIDENCE_THRESHOLD
    }

    /**
     * Language code for AI report generation (e.g. "es", "en").
     */
    val language: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LANGUAGE] ?: DEFAULT_LANGUAGE
    }

    /**
     * Dark mode preference.
     */
    val darkMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DARK_MODE] ?: DEFAULT_DARK_MODE
    }

    /**
     * Maximum number of images in batch processing.
     */
    val batchMaxImages: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.BATCH_MAX_IMAGES]?.toIntOrNull()) ?: DEFAULT_BATCH_MAX_IMAGES
    }

    /**
     * Whether to run Gemma 3n VLM deep analysis after RT-DETRv2 detection.
     * When disabled, only fast object detection runs. VLM remains available
     * for report generation regardless of this setting.
     */
    val vlmEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.VLM_ENABLED] ?: DEFAULT_VLM_ENABLED
    }

    /**
     * Input resolution for live detection mode (320, 480, or 640).
     * Lower resolution = faster inference, less accuracy.
     */
    val liveDetectionResolution: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.LIVE_DETECTION_RESOLUTION]?.toIntOrNull() ?: DEFAULT_LIVE_DETECTION_RESOLUTION
    }

    /**
     * Detector model precision ("fp16" or "int8").
     * Requires app restart to take effect.
     */
    val detectorPrecision: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DETECTOR_PRECISION] ?: DEFAULT_DETECTOR_PRECISION
    }

    /**
     * Whether to show a preview of the captured photo before sending it
     * to the detection pipeline. When false, capture goes directly to analysis.
     */
    val confirmCapture: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONFIRM_CAPTURE] ?: DEFAULT_CONFIRM_CAPTURE
    }

    /**
     * Whether the user has completed the onboarding flow.
     * When false, the splash screen navigates to onboarding instead of home.
     */
    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETE] ?: false
    }

    val dexBackfillComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEX_BACKFILL_COMPLETE] ?: false
    }

    /**
     * App UI language code (empty string = follow system locale).
     * Separate from [language] which controls AI report generation language.
     */
    val appLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_LANGUAGE] ?: ""
    }

    // -----------------------------------------------------------------------
    // Write operations
    // -----------------------------------------------------------------------

    suspend fun setConfidenceThreshold(value: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONFIDENCE_THRESHOLD] = value.coerceIn(0f, 1f)
        }
    }

    suspend fun setLanguage(code: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = code
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DARK_MODE] = enabled
        }
    }

    suspend fun setBatchMaxImages(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BATCH_MAX_IMAGES] = count.coerceIn(1, 10).toString()
        }
    }

    suspend fun setVlmEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VLM_ENABLED] = enabled
        }
    }

    suspend fun setLiveDetectionResolution(resolution: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LIVE_DETECTION_RESOLUTION] = resolution.coerceIn(320, 640).toString()
        }
    }

    suspend fun setDetectorPrecision(precision: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DETECTOR_PRECISION] = precision
        }
    }

    suspend fun setConfirmCapture(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONFIRM_CAPTURE] = enabled
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETE] = complete
        }
    }

    suspend fun setDexBackfillComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEX_BACKFILL_COMPLETE] = complete
        }
    }

    suspend fun setAppLanguage(code: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.APP_LANGUAGE] = code
        }
    }

    fun getAppLanguageSync(): String {
        val prefs = runBlocking { context.dataStore.data.first() }
        return prefs[Keys.APP_LANGUAGE] ?: ""
    }

    /**
     * Read current detector precision synchronously (blocking).
     * Used at app startup before coroutines are available.
     */
    fun getDetectorPrecisionSync(): String {
        val prefs = runBlocking { context.dataStore.data.first() }
        return prefs[Keys.DETECTOR_PRECISION] ?: DEFAULT_DETECTOR_PRECISION
    }

    // -----------------------------------------------------------------------
    // Tour completion tracking
    // -----------------------------------------------------------------------

    /**
     * Returns a [Flow] that emits true once the guided tour for [screenId]
     * has been completed (or explicitly skipped).
     */
    fun isTourComplete(screenId: String): Flow<Boolean> {
        val key = booleanPreferencesKey("tour_complete_$screenId")
        return context.dataStore.data.map { prefs -> prefs[key] ?: false }
    }

    /** Persists tour completion for [screenId] so it is not shown again. */
    suspend fun markTourComplete(screenId: String) {
        val key = booleanPreferencesKey("tour_complete_$screenId")
        context.dataStore.edit { prefs -> prefs[key] = true }
    }

    /**
     * Clears all tour-completion flags so every screen tour will play again
     * on next visit. Used by the "Replay Guided Tours" setting.
     */
    suspend fun resetAllTours() {
        context.dataStore.edit { prefs ->
            val tourKeys = prefs.asMap().keys.filter { it.name.startsWith("tour_complete_") }
            tourKeys.forEach { key -> prefs.remove(key) }
            prefs[Keys.GUIDED_TOUR_ACTIVE] = true
        }
    }

    // -----------------------------------------------------------------------
    // Guided tour mode (linear chained tour)
    // -----------------------------------------------------------------------

    /** True while the linear guided tour is active (Home→Map→Reports→History→Settings). */
    val guidedTourActive: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.GUIDED_TOUR_ACTIVE] ?: false
    }

    suspend fun setGuidedTourActive(active: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.GUIDED_TOUR_ACTIVE] = active }
    }

    /** Mark all 9 guided-tour screens complete and deactivate guided tour. */
    suspend fun markAllGuidedToursComplete() {
        context.dataStore.edit { prefs ->
            for (screenId in GUIDED_TOUR_IDS) {
                prefs[booleanPreferencesKey("tour_complete_$screenId")] = true
            }
            prefs[Keys.GUIDED_TOUR_ACTIVE] = false
        }
    }

    // -----------------------------------------------------------------------
    // Developer mode (easter egg: tap logo 21 times)
    // -----------------------------------------------------------------------

    val devModeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEV_MODE_ENABLED] ?: false
    }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.DEV_MODE_ENABLED] = enabled }
    }

    // -----------------------------------------------------------------------
    // Update checker
    // -----------------------------------------------------------------------

    /**
     * Selected VLM text-model tier: "fast" (1.5B) or "quality" (3B).
     * Controls which model is loaded when generating reports.
     */
    val vlmModelTier: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.VLM_MODEL_TIER] ?: DEFAULT_VLM_MODEL_TIER
    }

    /** Audience for AI-generated reports: "scientific", "ngo", or "citizen". */
    val reportAudience: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.REPORT_AUDIENCE] ?: DEFAULT_REPORT_AUDIENCE
    }

    fun getReportAudienceSync(): String {
        val prefs = runBlocking { context.dataStore.data.first() }
        return prefs[Keys.REPORT_AUDIENCE] ?: DEFAULT_REPORT_AUDIENCE
    }

    suspend fun setReportAudience(value: String) {
        context.dataStore.edit { prefs -> prefs[Keys.REPORT_AUDIENCE] = value }
    }

    val lastUpdateCheckMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_UPDATE_CHECK_MS] ?: 0L
    }

    val skippedVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SKIPPED_VERSION] ?: ""
    }

    suspend fun setLastUpdateCheckMs(ms: Long) {
        context.dataStore.edit { prefs -> prefs[Keys.LAST_UPDATE_CHECK_MS] = ms }
    }

    suspend fun setSkippedVersion(version: String) {
        context.dataStore.edit { prefs -> prefs[Keys.SKIPPED_VERSION] = version }
    }

    suspend fun setVlmModelTier(tier: String) {
        context.dataStore.edit { prefs -> prefs[Keys.VLM_MODEL_TIER] = tier }
    }

    fun getVlmModelTierSync(): String {
        val prefs = runBlocking { context.dataStore.data.first() }
        return prefs[Keys.VLM_MODEL_TIER] ?: DEFAULT_VLM_MODEL_TIER
    }

    // -----------------------------------------------------------------------
    // Research contribution
    // -----------------------------------------------------------------------

    /** True once the user has explicitly given consent to contribute images. */
    val contributeConsentGiven: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONTRIBUTE_CONSENT_GIVEN] ?: false
    }

    /** When true, uploads only occur on WiFi (UNMETERED) networks. Default: true. */
    val contributeWifiOnly: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONTRIBUTE_WIFI_ONLY] ?: true
    }

    /**
     * Number of times the user has dismissed the contribution prompt without consenting.
     * The prompt stops showing once this reaches 3.
     */
    val contributeDeclineCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONTRIBUTE_DECLINE_COUNT] ?: 0
    }

    suspend fun setContributeConsentGiven(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.CONTRIBUTE_CONSENT_GIVEN] = v }
    }

    suspend fun setContributeWifiOnly(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.CONTRIBUTE_WIFI_ONLY] = v }
    }

    suspend fun incrementContributeDeclineCount() {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONTRIBUTE_DECLINE_COUNT] = (prefs[Keys.CONTRIBUTE_DECLINE_COUNT] ?: 0) + 1
        }
    }
}
