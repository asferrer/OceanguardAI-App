package com.oceanguard.ai.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.oceanguard.ai.data.DebrisMaterial

// ---------------------------------------------------------------------------
// Raw ocean palette
// ---------------------------------------------------------------------------

/** Primary brand blue: ocean deep #006994 */
val OceanBlue = Color(0xFF006994)

/** Primary container: light sky for legibility on dark bg */
val OceanBlueLight = Color(0xFF4A9FBD)

/** Darkest blue used on backgrounds */
val OceanBlueDark = Color(0xFF003D5C)

/** Secondary brand teal: bioluminescent green #00A896 */
val OceanGreen = Color(0xFF00A896)
val OceanGreenLight = Color(0xFF05C9A8)
val OceanGreenDark = Color(0xFF006B61)

/** Accent: coral / warning red */
val CoralRed = Color(0xFFFF6B6B)

/** Neutral sandy tone */
val SandBeige = Color(0xFFF4E4C1)

// ---------------------------------------------------------------------------
// Semantic health score colours
// ---------------------------------------------------------------------------

/** 80-100: excellent ecosystem health */
val HealthExcellent = Color(0xFF4CAF50)

/** 60-79: good */
val HealthGood = Color(0xFF8BC34A)

/** 40-59: fair — amber warning */
val HealthFair = Color(0xFFFFC107)

/** 20-39: poor — orange alert */
val HealthPoor = Color(0xFFFF9800)

/** 0-19: critical — red alert */
val HealthCritical = Color(0xFFF44336)

// ---------------------------------------------------------------------------
// Debris material classification colours
// ---------------------------------------------------------------------------

val MaterialPlastic = Color(0xFFE91E63)   // pink-red: high risk
val MaterialMetal = Color(0xFF9E9E9E)     // grey
val MaterialFabric = Color(0xFF3F51B5)    // indigo
val MaterialRubber = Color(0xFF795548)    // brown
val MaterialGlass = Color(0xFF00BCD4)     // cyan
val MaterialFishingNet = Color(0xFFFF6F00) // deep amber: highest risk
val MaterialOther = Color(0xFF607D8B)     // blue-grey

// ---------------------------------------------------------------------------
// Gradient & glassmorphism palette
// ---------------------------------------------------------------------------

/** Hero gradient: deep ocean -> teal surface */
val GradientDeepStart = Color(0xFF0A1929)    // = Neutral10
val GradientDeepMid   = Color(0xFF003D5C)    // = OceanBlueDark
val GradientDeepEnd   = Color(0xFF00A896)    // = OceanGreen

/** Glassmorphism card surfaces */
val GlassSurface = Color(0x12FFFFFF)         // 7% white
val GlassBorder  = Color(0x33FFFFFF)         // 20% white

/** Shimmer placeholder colors for dark mode */
val ShimmerBase      = Color(0xFF112C47)     // = Neutral30
val ShimmerHighlight = Color(0xFF1A3449)

// ---------------------------------------------------------------------------
// Accent gradients for premium card effects
// ---------------------------------------------------------------------------

/** Teal accent — start of gradient fills on premium cards */
val AccentGradientStart = Color(0xFF00A896)   // = OceanGreen
/** Ocean blue — midpoint of gradient fills */
val AccentGradientMid   = Color(0xFF006994)   // = OceanBlue
/** Light blue — end of gradient fills */
val AccentGradientEnd   = Color(0xFF4A9FBD)   // = OceanBlueLight

// ---------------------------------------------------------------------------
// Enhanced glassmorphism palette
// ---------------------------------------------------------------------------

/** Inner glow for GlassCard — 5% white radial overlay */
val GlassInnerGlow = Color(0x0DFFFFFF)
/** Highlight edge for GlassCard — 10% white */
val GlassHighlight = Color(0x1AFFFFFF)

// ---------------------------------------------------------------------------
// Text hierarchy (consistent luminance steps for dark mode)
// ---------------------------------------------------------------------------

/** Primary text — 91% luminance, for headings and key values */
val TextPrimary = Color(0xFFE8F0F8)
/** Secondary text — 68% luminance, for body and descriptions */
val TextSecondary = Color(0xFFA0BCC8)
/** Tertiary text — 45% luminance, for captions and metadata */
val TextTertiary = Color(0xFF6B8A9E)

// ---------------------------------------------------------------------------
// Accent glow and bioluminescent highlights
// ---------------------------------------------------------------------------

/** 10% OceanGreen overlay for interactive element backgrounds */
val AccentGlow = Color(0x1A00A896)
/** Bioluminescent cyan for special highlights and effects */
val BioluminescentCyan = Color(0xFF00E5FF)

// ---------------------------------------------------------------------------
// Gradient presets
// ---------------------------------------------------------------------------

/** Hero gradient: ultra-deep start (matches landing --bg-primary #0a0e1a) */
val GradientHeroStart = Color(0xFF0A0E1A)
/** Hero gradient: deep ocean end (matches landing --bg-secondary #0d1225) */
val GradientHeroEnd = Color(0xFF0D1225)
/** CTA button gradient start (cyan, matches landing page --accent-cyan) */
val GradientCTAStart = Color(0xFF00D4FF)
/** CTA button gradient end (emerald, matches landing page --accent-emerald) */
val GradientCTAEnd = Color(0xFF10B981)
/** CTA glow colour (cyan glow for button shadow effect) */
val CTAGlow = Color(0x4D00D4FF)

// ---------------------------------------------------------------------------
// Bottom navigation
// ---------------------------------------------------------------------------

/** Active tab indicator pill colour */
val NavIndicatorColor = Color(0xFF00A896)     // = OceanGreen

// ---------------------------------------------------------------------------
// Surface gradients for elevated cards
// ---------------------------------------------------------------------------

/** Card gradient start — matches Neutral20 */
val CardGradientStart = Color(0xFF0D2137)
/** Card gradient end — matches Neutral30 */
val CardGradientEnd   = Color(0xFF112C47)

// ---------------------------------------------------------------------------
// Ambient shadow / highlight edge for depth
// ---------------------------------------------------------------------------

/** 25% black for ambient shadows behind elevated components */
val ElevatedShadowColor = Color(0x40000000)
/** 5% white for top-edge highlight on elevated components */
val ElevatedHighlightEdge = Color(0x0DFFFFFF)

// ---------------------------------------------------------------------------
// Shared colour utility functions
// ---------------------------------------------------------------------------

/**
 * Maps a 0-100 health score to the corresponding semantic colour.
 * Single source of truth — used by HomeScreen, ResultsScreen, HistoryScreen,
 * HealthScoreGauge, HealthTrendChart, and SessionDetailScreen.
 */
fun healthScoreColor(score: Int): Color = when {
    score >= 80 -> HealthExcellent
    score >= 60 -> HealthGood
    score >= 40 -> HealthFair
    score >= 20 -> HealthPoor
    else        -> HealthCritical
}

/**
 * Returns the semantic material colour for the given [DebrisMaterial].
 * Single source of truth — used by ResultsScreen, DebrisCard,
 * BoundingBoxOverlay, and SessionDetailScreen.
 */
fun materialColor(material: DebrisMaterial): Color = when (material) {
    DebrisMaterial.PLASTIC     -> MaterialPlastic
    DebrisMaterial.METAL       -> MaterialMetal
    DebrisMaterial.FABRIC      -> MaterialFabric
    DebrisMaterial.RUBBER      -> MaterialRubber
    DebrisMaterial.GLASS       -> MaterialGlass
    DebrisMaterial.FISHING_NET -> MaterialFishingNet
    DebrisMaterial.OTHER       -> MaterialOther
}

// ---------------------------------------------------------------------------
// Material 3 neutral surfaces — deep-ocean dark mode
// ---------------------------------------------------------------------------

private val Neutral10 = Color(0xFF0A1929)  // deepest ocean background
private val Neutral20 = Color(0xFF0D2137)
private val Neutral30 = Color(0xFF112C47)
private val Neutral80 = Color(0xFFB0C8D4)
private val Neutral90 = Color(0xFFCCDDE8)
private val Neutral95 = Color(0xFFE5EFF5)
private val Neutral99 = Color(0xFFF5FAFC)

private val NeutralVariant30 = Color(0xFF1A3549)
private val NeutralVariant50 = Color(0xFF4A6880)
private val NeutralVariant80 = Color(0xFFA0BCC8)

private val ErrorBase = Color(0xFFCF6679)
private val ErrorContainer = Color(0xFF93000A)

// ---------------------------------------------------------------------------
// Dark colour scheme — default for underwater use
// High contrast on dark backgrounds mirrors diving conditions.
// ---------------------------------------------------------------------------

val OceanDarkColorScheme = darkColorScheme(
    // Primary: ocean blue
    primary = OceanBlueLight,
    onPrimary = Color(0xFF00293D),
    primaryContainer = OceanBlueDark,
    onPrimaryContainer = Color(0xFFC9E6FF),

    // Secondary: teal / bioluminescent
    secondary = OceanGreenLight,
    onSecondary = Color(0xFF003730),
    secondaryContainer = OceanGreenDark,
    onSecondaryContainer = Color(0xFFA8F5E8),

    // Tertiary: coral accent for alerts
    tertiary = CoralRed,
    onTertiary = Color(0xFF4A0000),
    tertiaryContainer = Color(0xFF6B1E1E),
    onTertiaryContainer = Color(0xFFFFDAD5),

    // Error
    error = ErrorBase,
    onError = Color(0xFF690005),
    errorContainer = ErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),

    // Backgrounds — deep ocean dark
    background = Neutral10,
    onBackground = Neutral90,

    // Surface hierarchy
    surface = Neutral20,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,

    // Outlines
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant30,

    // Inverse (used for snackbars, tooltips)
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = OceanBlue,

    // Scrim for modal dialogs
    scrim = Color(0xFF000000),

    // Surface containers — used by cards, bottom sheets, etc.
    surfaceContainerLowest = Color(0xFF060F18),
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral20,
    surfaceContainerHigh = Neutral30,
    surfaceContainerHighest = Color(0xFF1A3449),
)

// ---------------------------------------------------------------------------
// Light colour scheme — for surface environments / post-dive review
// ---------------------------------------------------------------------------

val OceanLightColorScheme = lightColorScheme(
    primary = OceanBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9E6FF),
    onPrimaryContainer = Color(0xFF001E30),

    secondary = OceanGreen,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA8F5E8),
    onSecondaryContainer = Color(0xFF002019),

    tertiary = Color(0xFFC0392B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD5),
    onTertiaryContainer = Color(0xFF410002),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Neutral99,
    onBackground = Color(0xFF1A1C1E),

    surface = Neutral99,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDCE3EB),
    onSurfaceVariant = Color(0xFF42484F),

    outline = Color(0xFF72787E),
    outlineVariant = Color(0xFFC2C8CE),

    inverseSurface = Color(0xFF2F3133),
    inverseOnSurface = Neutral95,
    inversePrimary = OceanBlueLight,

    scrim = Color(0xFF000000),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5FAFE),
    surfaceContainer = Color(0xFFEFF4F8),
    surfaceContainerHigh = Color(0xFFE9EFF3),
    surfaceContainerHighest = Color(0xFFE3E9ED),
)
