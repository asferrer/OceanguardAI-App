package com.oceanguard.ai.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*

/** M3 Expressive-style spring entry: slide up + fade in */
val ScreenEnterTransition: EnterTransition =
    slideInVertically(
        initialOffsetY = { fullHeight -> fullHeight / 8 },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
    ) + fadeIn(
        animationSpec = tween(300),
    )

val ScreenExitTransition: ExitTransition =
    slideOutVertically(
        targetOffsetY = { fullHeight -> -fullHeight / 12 },
        animationSpec = tween(200),
    ) + fadeOut(
        animationSpec = tween(200),
    )

/** Pop entry (going back): spring slide-in from left + fade */
val ScreenPopEnterTransition: EnterTransition =
    fadeIn(tween(300)) + slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 6 },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
    )

/** Pop exit (going back): slide out to right + fade */
val ScreenPopExitTransition: ExitTransition =
    fadeOut(tween(200)) + slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth / 4 },
        animationSpec = tween(250),
    )

/** For content appearing within a screen (cards loading, etc.) */
val ContentEnterTransition: EnterTransition =
    fadeIn(tween(400)) + expandVertically(
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
    )

/** Bottom sheet / overlay entry: slide up from bottom with spring */
val BottomSheetEnterTransition: EnterTransition =
    slideInVertically(
        initialOffsetY = { fullHeight -> fullHeight },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
    ) + fadeIn(tween(200))

/** Bottom sheet / overlay exit: slide down + fade out */
val BottomSheetExitTransition: ExitTransition =
    slideOutVertically(
        targetOffsetY = { fullHeight -> fullHeight },
        animationSpec = tween(200),
    ) + fadeOut(tween(150))
