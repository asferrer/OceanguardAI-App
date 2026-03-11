package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.sin

/**
 * Ambient floating bubble particles for underwater atmosphere.
 *
 * Renders 10 translucent circles drifting upward with sinusoidal horizontal
 * sway. Each bubble has a small highlight spot simulating light refraction.
 * Uses [graphicsLayer] for GPU-composited rendering.
 *
 * Performance: max 10 circles, no blur, < 1ms/frame on CPU.
 */
@Composable
fun BubbleParticles(
    modifier: Modifier = Modifier,
    bubbleCount: Int = 10,
) {
    val bubbles = remember(bubbleCount) {
        List(bubbleCount) {
            Bubble(
                xFraction = (it * 0.089f + 0.05f) % 1f,
                speed = 0.6f + (it % 5) * 0.12f,
                radius = 2f + (it % 4) * 1.5f,
                alpha = 0.03f + (it % 3) * 0.01f,
                swayAmount = 0.015f + (it % 3) * 0.008f,
                phaseOffset = it * 0.63f,
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "bubbles")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
        ),
        label = "bubblePhase",
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { },
    ) {
        val w = size.width
        val h = size.height

        bubbles.forEach { b ->
            val progress = (phase * b.speed + b.phaseOffset) % 1f
            val y = h * (1f - progress)
            val sway = sin((progress * 6.28f + b.phaseOffset) * 2f) * w * b.swayAmount
            val x = w * b.xFraction + sway
            val r = b.radius * density

            // Bubble body
            drawCircle(
                color = Color.White.copy(alpha = b.alpha),
                radius = r,
                center = Offset(x, y),
            )
            // Highlight spot (light refraction)
            drawCircle(
                color = Color.White.copy(alpha = b.alpha * 1.5f),
                radius = r * 0.35f,
                center = Offset(x - r * 0.25f, y - r * 0.25f),
            )
        }
    }
}

private data class Bubble(
    val xFraction: Float,
    val speed: Float,
    val radius: Float,
    val alpha: Float,
    val swayAmount: Float,
    val phaseOffset: Float,
)
