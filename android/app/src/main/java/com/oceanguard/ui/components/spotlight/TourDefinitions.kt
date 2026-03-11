package com.oceanguard.ai.ui.components.spotlight

import com.oceanguard.ai.R

/**
 * Centralised tour definitions for all screens in OceanGuard AI.
 *
 * Each tour contains 2-4 [SpotlightStep] entries whose [SpotlightStep.targetId]
 * values must match the ids used in the corresponding screen's
 * [Modifier.spotlightTarget] calls.
 *
 * Title and description strings are referenced via @StringRes IDs so this
 * non-Composable object can remain free of Compose context.
 */
object TourDefinitions {

    val HOME = listOf(
        SpotlightStep("home_scan_button", R.string.tour_home_scan_title, R.string.tour_home_scan_desc),
        SpotlightStep("home_model_status", R.string.tour_home_model_status_title, R.string.tour_home_model_status_desc),
        SpotlightStep("home_stats", R.string.tour_home_stats_title, R.string.tour_home_stats_desc),
        SpotlightStep("home_marinedex", R.string.tour_home_marinedex_title, R.string.tour_home_marinedex_desc),
    )

    val CAMERA = listOf(
        SpotlightStep("camera_shutter", R.string.tour_camera_shutter_title, R.string.tour_camera_shutter_desc, SpotlightShape.CIRCLE),
        SpotlightStep("camera_flash", R.string.tour_camera_flash_title, R.string.tour_camera_flash_desc, SpotlightShape.CIRCLE),
        SpotlightStep("camera_grid", R.string.tour_camera_grid_title, R.string.tour_camera_grid_desc),
    )

    val LIVE_DETECTION = listOf(
        SpotlightStep("live_viewfinder", R.string.tour_live_detection_viewfinder_title, R.string.tour_live_detection_viewfinder_desc),
        SpotlightStep("live_fps", R.string.tour_live_detection_fps_title, R.string.tour_live_detection_fps_desc),
        SpotlightStep("live_capture", R.string.tour_live_detection_capture_title, R.string.tour_live_detection_capture_desc, SpotlightShape.CIRCLE),
    )

    val RESULTS = listOf(
        SpotlightStep("results_image", R.string.tour_results_image_title, R.string.tour_results_image_desc),
        SpotlightStep("results_health", R.string.tour_results_health_title, R.string.tour_results_health_desc),
        SpotlightStep("results_actions", R.string.tour_results_actions_title, R.string.tour_results_actions_desc),
    )

    val BATCH = listOf(
        SpotlightStep("batch_grid", R.string.tour_batch_grid_title, R.string.tour_batch_grid_desc),
        SpotlightStep("batch_summary", R.string.tour_batch_summary_title, R.string.tour_batch_summary_desc),
    )

    val HISTORY = listOf(
        SpotlightStep("history_media_filter", R.string.tour_history_media_filter_title, R.string.tour_history_media_filter_desc),
        SpotlightStep("history_search", R.string.tour_history_search_title, R.string.tour_history_search_desc),
        SpotlightStep("history_filters", R.string.tour_history_filters_title, R.string.tour_history_filters_desc),
        SpotlightStep("history_card", R.string.tour_history_card_title, R.string.tour_history_card_desc),
    )

    val SESSION_DETAIL = listOf(
        SpotlightStep("detail_image", R.string.tour_session_detail_image_title, R.string.tour_session_detail_image_desc),
        SpotlightStep("detail_health", R.string.tour_session_detail_health_title, R.string.tour_session_detail_health_desc),
        SpotlightStep("detail_location", R.string.tour_session_detail_location_title, R.string.tour_session_detail_location_desc),
    )

    val MAP = listOf(
        SpotlightStep("map_filters", R.string.tour_map_filters_title, R.string.tour_map_filters_desc),
        SpotlightStep("map_view", R.string.tour_map_view_title, R.string.tour_map_view_desc),
        SpotlightStep("map_heatmap", R.string.tour_map_heatmap_title, R.string.tour_map_heatmap_desc, SpotlightShape.CIRCLE),
        SpotlightStep("map_fit_all", R.string.tour_map_fit_all_title, R.string.tour_map_fit_all_desc, SpotlightShape.CIRCLE),
        SpotlightStep("map_style", R.string.tour_map_style_title, R.string.tour_map_style_desc, SpotlightShape.CIRCLE),
    )

    val REPORTS = listOf(
        SpotlightStep("reports_zone", R.string.tour_reports_zone_title, R.string.tour_reports_zone_desc),
        SpotlightStep("reports_language", R.string.tour_reports_language_title, R.string.tour_reports_language_desc),
        SpotlightStep("reports_generate", R.string.tour_reports_generate_title, R.string.tour_reports_generate_desc),
    )

    val SETTINGS = listOf(
        SpotlightStep("settings_confidence", R.string.tour_settings_confidence_title, R.string.tour_settings_confidence_desc),
        SpotlightStep("settings_deep_analysis", R.string.tour_settings_deep_analysis_title, R.string.tour_settings_deep_analysis_desc),
        SpotlightStep("settings_language", R.string.tour_settings_language_title, R.string.tour_settings_language_desc),
        SpotlightStep("settings_theme", R.string.tour_settings_theme_title, R.string.tour_settings_theme_desc),
        SpotlightStep("settings_data", R.string.tour_settings_data_title, R.string.tour_settings_data_desc),
        SpotlightStep("settings_tours", R.string.tour_settings_tours_title, R.string.tour_settings_tours_desc),
    )

    val MARINEDEX = listOf(
        SpotlightStep("dex_progress", R.string.tour_marinedex_progress_title, R.string.tour_marinedex_progress_desc),
        SpotlightStep("dex_grid", R.string.tour_marinedex_grid_title, R.string.tour_marinedex_grid_desc),
    )

    val MARINEDEX_DETAIL = listOf(
        SpotlightStep("dex_detail_sprite", R.string.tour_marinedex_detail_sprite_title, R.string.tour_marinedex_detail_sprite_desc),
        SpotlightStep("dex_detail_stats", R.string.tour_marinedex_detail_stats_title, R.string.tour_marinedex_detail_stats_desc),
        SpotlightStep("dex_detail_impact", R.string.tour_marinedex_detail_impact_title, R.string.tour_marinedex_detail_impact_desc),
        SpotlightStep("dex_detail_tabs", R.string.tour_marinedex_detail_tabs_title, R.string.tour_marinedex_detail_tabs_desc),
    )

    val ACHIEVEMENTS = listOf(
        SpotlightStep("achievements_progress", R.string.tour_achievements_progress_title, R.string.tour_achievements_progress_desc),
        SpotlightStep("achievements_list", R.string.tour_achievements_list_title, R.string.tour_achievements_list_desc),
    )

    /** Returns the DataStore persistence key for the given tour definition. */
    fun getScreenId(tour: List<SpotlightStep>): String = when (tour) {
        HOME -> "home"
        CAMERA -> "camera"
        LIVE_DETECTION -> "live_detection"
        RESULTS -> "results"
        BATCH -> "batch"
        HISTORY -> "history"
        SESSION_DETAIL -> "session_detail"
        MAP -> "map"
        REPORTS -> "reports"
        SETTINGS -> "settings"
        MARINEDEX -> "marinedex"
        MARINEDEX_DETAIL -> "marinedex_detail"
        ACHIEVEMENTS -> "achievements"
        else -> "unknown"
    }
}
