package com.oceanguard.ai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.oceanguard.ai.ui.theme.InterFontFamily

/**
 * OceanGuard Typography — Material 3
 *
 * Design constraints for underwater usability:
 * - Minimum body text: 16sp (wet/gloved fingers reduce tap precision)
 * - Minimum label text: 14sp
 * - Bold weights on titles for quick scanning at depth
 * - Generous line heights (+20%) to aid legibility through dive masks
 * - Inter via Google Fonts: clean, high-legibility at all sizes;
 *   falls back to Roboto automatically when offline / GMS unavailable
 */
val OceanGuardTypography = Typography(

    // -----------------------------------------------------------------------
    // Display — splash screens, large stat numbers
    // -----------------------------------------------------------------------
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 68.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 54.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // -----------------------------------------------------------------------
    // Headline — screen titles, section headers
    // Bold weight for quick visual scanning under water
    // -----------------------------------------------------------------------
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // -----------------------------------------------------------------------
    // Title — card headers, dialog titles, bottom-sheet titles
    // -----------------------------------------------------------------------
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,       // +2sp above M3 default: 16sp
        lineHeight = 26.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,       // +2sp above M3 default: 14sp
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),

    // -----------------------------------------------------------------------
    // Body — main readable content
    // 16sp minimum to satisfy underwater usability requirement
    // -----------------------------------------------------------------------
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,       // +2sp above M3 default: 16sp
        lineHeight = 28.sp,     // generous line height for mask legibility
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,       // M3 default: 14sp — raised to minimum
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,       // M3 default: 12sp — raised to minimum
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp
    ),

    // -----------------------------------------------------------------------
    // Label — chips, badges, table cells, secondary metadata
    // 14sp minimum enforced; medium weight aids legibility on dark surfaces
    // -----------------------------------------------------------------------
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,       // button text; +2sp above M3 default: 14sp
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,       // minimum label size
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,       // M3 default: 11sp — raised to minimum
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp
    ),
)
