package com.oceanguard.ai.ui.components.spotlight

import androidx.annotation.StringRes

/**
 * Represents a single step in a guided tour spotlight sequence.
 *
 * @param targetId      Unique identifier matching the composable registered via
 *                      [Modifier.spotlightTarget].
 * @param titleRes      String resource ID for the short heading shown in the tooltip card.
 * @param descriptionRes String resource ID for the explanatory body text shown in the tooltip.
 * @param shape         Shape of the spotlight cutout (default: rounded rect).
 */
data class SpotlightStep(
    val targetId: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val shape: SpotlightShape = SpotlightShape.ROUNDED_RECT,
)

/** Cutout shape used by the spotlight overlay Canvas. */
enum class SpotlightShape { ROUNDED_RECT, CIRCLE }
