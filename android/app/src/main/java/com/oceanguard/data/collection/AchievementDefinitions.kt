package com.oceanguard.ai.data.collection

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ScubaDiving
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector
import com.oceanguard.ai.R

/**
 * Static definition of an achievement (display metadata).
 * Separate from the Room [Achievement] entity which holds mutable state.
 *
 * [nameRes] and [descriptionRes] are string resource IDs so this data
 * class can be defined outside of a Composable context.
 */
data class AchievementDef(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val target: Int,
    val icon: ImageVector,
    val category: AchievementCategory,
)

enum class AchievementCategory(@StringRes val labelRes: Int) {
    FIRST_STEPS(R.string.achievement_category_first_steps),
    EXPLORER(R.string.achievement_category_explorer),
    COLLECTOR(R.string.achievement_category_collector),
    IMPACT(R.string.achievement_category_impact),
    ELITE(R.string.achievement_category_elite),
}

/**
 * All 21 achievement definitions.
 */
val ALL_ACHIEVEMENTS: List<AchievementDef> = listOf(
    // --- First Steps (6) ---
    AchievementDef("first_scan", R.string.achievement_name_first_scan, R.string.achievement_desc_first_scan, 1, Icons.Filled.Explore, AchievementCategory.FIRST_STEPS),
    AchievementDef("first_live", R.string.achievement_name_first_live, R.string.achievement_desc_first_live, 1, Icons.Filled.Videocam, AchievementCategory.FIRST_STEPS),
    AchievementDef("first_report", R.string.achievement_name_first_report, R.string.achievement_desc_first_report, 1, Icons.Filled.Description, AchievementCategory.FIRST_STEPS),
    AchievementDef("first_batch", R.string.achievement_name_first_batch, R.string.achievement_desc_first_batch, 1, Icons.Filled.BurstMode, AchievementCategory.FIRST_STEPS),
    AchievementDef("first_dex_entry", R.string.achievement_name_first_dex_entry, R.string.achievement_desc_first_dex_entry, 1, Icons.Filled.MenuBook, AchievementCategory.FIRST_STEPS),
    AchievementDef("tutorial_complete", R.string.achievement_name_tutorial_complete, R.string.achievement_desc_tutorial_complete, 1, Icons.Filled.School, AchievementCategory.FIRST_STEPS),

    // --- Explorer (5) ---
    AchievementDef("scan_10", R.string.achievement_name_scan_10, R.string.achievement_desc_scan_10, 10, Icons.Filled.CleaningServices, AchievementCategory.EXPLORER),
    AchievementDef("scan_50", R.string.achievement_name_scan_50, R.string.achievement_desc_scan_50, 50, Icons.Filled.ScubaDiving, AchievementCategory.EXPLORER),
    AchievementDef("scan_100", R.string.achievement_name_scan_100, R.string.achievement_desc_scan_100, 100, Icons.Filled.Shield, AchievementCategory.EXPLORER),
    AchievementDef("scan_500", R.string.achievement_name_scan_500, R.string.achievement_desc_scan_500, 500, Icons.Filled.MilitaryTech, AchievementCategory.EXPLORER),
    AchievementDef("streak_7", R.string.achievement_name_streak_7, R.string.achievement_desc_streak_7, 7, Icons.Filled.LocalFireDepartment, AchievementCategory.EXPLORER),

    // --- MarineDex Collector (5) ---
    AchievementDef("dex_3", R.string.achievement_name_dex_3, R.string.achievement_desc_dex_3, 3, Icons.Filled.WaterDrop, AchievementCategory.COLLECTOR),
    AchievementDef("dex_6", R.string.achievement_name_dex_6, R.string.achievement_desc_dex_6, 6, Icons.Filled.Science, AchievementCategory.COLLECTOR),
    AchievementDef("dex_9", R.string.achievement_name_dex_9, R.string.achievement_desc_dex_9, 9, Icons.Filled.Waves, AchievementCategory.COLLECTOR),
    AchievementDef("dex_11", R.string.achievement_name_dex_11, R.string.achievement_desc_dex_11, 11, Icons.Filled.Star, AchievementCategory.COLLECTOR),
    AchievementDef("dex_favorite_3", R.string.achievement_name_dex_favorite_3, R.string.achievement_desc_dex_favorite_3, 3, Icons.Filled.Favorite, AchievementCategory.COLLECTOR),

    // --- Environmental Impact (3) ---
    AchievementDef("debris_50", R.string.achievement_name_debris_50, R.string.achievement_desc_debris_50, 50, Icons.Filled.Park, AchievementCategory.IMPACT),
    AchievementDef("debris_200", R.string.achievement_name_debris_200, R.string.achievement_desc_debris_200, 200, Icons.Filled.Rocket, AchievementCategory.IMPACT),
    AchievementDef("health_80", R.string.achievement_name_health_80, R.string.achievement_desc_health_80, 1, Icons.Filled.CalendarMonth, AchievementCategory.IMPACT),

    // --- Elite (2) ---
    AchievementDef("all_achievements", R.string.achievement_name_all_achievements, R.string.achievement_desc_all_achievements, 20, Icons.Filled.WorkspacePremium, AchievementCategory.ELITE),
    AchievementDef("report_10", R.string.achievement_name_report_10, R.string.achievement_desc_report_10, 10, Icons.Filled.EmojiEvents, AchievementCategory.ELITE),
)

/** Quick lookup by id. */
val ACHIEVEMENT_DEF_MAP: Map<String, AchievementDef> = ALL_ACHIEVEMENTS.associateBy { it.id }
