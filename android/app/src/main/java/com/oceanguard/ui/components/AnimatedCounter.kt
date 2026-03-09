package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Animated integer counter that smoothly transitions between values.
 * Uses tabular (monospaced) numerals to prevent layout shifts during animation.
 */
@Composable
fun AnimatedCounter(
    targetValue: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displaySmall,
    color: Color = MaterialTheme.colorScheme.primary,
    durationMs: Int = 800,
    suffix: String = "",
) {
    val animatedValue by animateIntAsState(
        targetValue = targetValue,
        animationSpec = tween(
            durationMillis = durationMs,
            easing = FastOutSlowInEasing,
        ),
        label = "counter",
    )

    Text(
        text = "$animatedValue$suffix",
        style = style.copy(
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum", // tabular (monospaced) numerals
        ),
        color = color,
        modifier = modifier,
    )
}
