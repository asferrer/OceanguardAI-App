package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Adds a press-down scale effect and optional haptic feedback to any composable.
 *
 * On press the element shrinks to [pressedScale] (default 0.96) with a bouncy
 * spring and returns to 1.0 on release. If [hapticEnabled] is true, a
 * [HapticFeedbackType.LongPress] vibration is triggered on first press.
 *
 * Usage: `Modifier.pressableScale()` — chain before `.clickable { ... }`.
 */
fun Modifier.pressableScale(
    pressedScale: Float = 0.96f,
    hapticEnabled: Boolean = true,
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    if (hapticEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    tryAwaitRelease()
                    isPressed = false
                },
            )
        }
}
