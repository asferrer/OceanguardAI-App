package com.oceanguard.ai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * CompositionLocal that exposes the app's *effective* dark-mode preference
 * (driven by [SettingsRepository.darkMode], not the OS setting). Branded
 * "ocean" components read this to pick between the deep-ocean and the
 * sunlit-beach palettes without re-collecting the DataStore.
 *
 * Default `false` so previews / tests that render outside [OceanGuardTheme]
 * fall back to light-mode appearance (safer than rendering dark on a light
 * surface).
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * Consistent corner-radius system for the OceanGuard design language.
 *
 * - small  (8 dp): chips, badges, small buttons
 * - medium (14 dp): cards, text fields, dropdowns
 * - large  (20 dp): bottom sheets, dialogs, GlassCards
 * - extraLarge (28 dp): full-screen modals, onboarding slides
 */
val OceanGuardShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * OceanGuardTheme
 *
 * Central theme composable for the OceanGuard AI application.
 *
 * Behaviour:
 * - Dark mode is the default (useDarkTheme = true) because it maximises
 *   contrast under water, reduces glare on dive computer screens, and
 *   preserves battery on OLED panels common in waterproof device housings.
 * - Dynamic colour (Material You, Android 12+) is intentionally disabled
 *   by default. Dive safety colours — health scores, material-type badges —
 *   must remain perceptually consistent regardless of system wallpaper.
 *   Pass dynamicColor = true only for surface/review screens where
 *   personalisation matters more than safety signal consistency.
 * - System bar colours are synchronised so the status bar blends with the
 *   deep-ocean dark background during actual detection sessions.
 *
 * @param useDarkTheme   When true (default) the deep-ocean dark palette is
 *                       applied. Callers may pass isSystemInDarkTheme() to
 *                       honour the system setting for non-detection screens.
 * @param dynamicColor   When true on Android 12+, Material You colour
 *                       extraction overrides the ocean palette. Disabled by
 *                       default for safety-signal consistency.
 * @param content        Composable content rendered inside the theme scope.
 */
@Composable
fun OceanGuardTheme(
    useDarkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when {
        // Dynamic colour (Material You) — Android 12+ only
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDarkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }

        // Ocean dark scheme — default for detection and underwater use
        useDarkTheme -> OceanDarkColorScheme

        // Ocean light scheme — post-dive review, accessibility preference
        else -> OceanLightColorScheme
    }

    // Synchronise system bar colours with the selected colour scheme.
    // WindowCompat.setDecorFitsSystemWindows(false) must be called in
    // the Activity before setContent for edge-to-edge rendering.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides useDarkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OceanGuardTypography,
            shapes = OceanGuardShapes,
            content = content,
        )
    }
}
