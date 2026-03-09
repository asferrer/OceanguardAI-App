package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer
import com.oceanguard.ai.ui.theme.ShimmerBase

private val ShimmerShape = RoundedCornerShape(14.dp)

/**
 * Single shimmer placeholder block with wave-wash effect and subtle pulse.
 *
 * The pulse gently modulates opacity (0.6 - 1.0) alongside the shimmer
 * sweep, producing a more organic loading feel than shimmer alone.
 */
@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmerPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = pulseAlpha }
            .clip(ShimmerShape)
            .shimmer()
            .background(
                color = ShimmerBase,
                shape = ShimmerShape,
            ),
    )
}

/**
 * Full-screen shimmer loading layout mimicking the expected content structure.
 * Shows a hero card, stat row, and N list item placeholders.
 */
@Composable
fun ShimmerLoadingScreen(
    modifier: Modifier = Modifier,
    itemCount: Int = 3,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero placeholder
        ShimmerCard(height = 180.dp)

        // Stat row placeholder
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                ShimmerCard(
                    modifier = Modifier.weight(1f),
                    height = 80.dp,
                )
            }
        }

        // List item placeholders
        repeat(itemCount) {
            ShimmerCard(height = 100.dp)
        }
    }
}
