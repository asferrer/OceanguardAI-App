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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.oceanguard.ai.ui.theme.OceanGreen

/**
 * Horizontal scan line that sweeps vertically, used during model processing
 * in CameraScreen and ResultsScreen loading states.
 *
 * Features:
 * - Gradient line (transparent edges -> OceanGreen center)
 * - Semi-transparent trail behind the line
 * - 2-second sweep cycle
 */
@Composable
fun ScanningOverlay(
    modifier: Modifier = Modifier,
    lineColor: Color = OceanGreen,
    cycleDurationMs: Int = 2000,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanLine")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(cycleDurationMs, easing = LinearEasing),
        ),
        label = "scanProgress",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val lineY = size.height * progress
        val lineWidth = 2f

        // Trail behind the line (fading upward)
        val trailHeight = size.height * 0.08f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    lineColor.copy(alpha = 0.03f),
                ),
                startY = (lineY - trailHeight).coerceAtLeast(0f),
                endY = lineY,
            ),
            topLeft = Offset(0f, (lineY - trailHeight).coerceAtLeast(0f)),
            size = Size(size.width, trailHeight.coerceAtMost(lineY)),
        )

        // Scan line with gradient (transparent edges -> colored center)
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    lineColor.copy(alpha = 0.5f),
                    lineColor.copy(alpha = 0.5f),
                    Color.Transparent,
                ),
                startX = 0f,
                endX = size.width,
            ),
            start = Offset(0f, lineY),
            end = Offset(size.width, lineY),
            strokeWidth = lineWidth,
        )
    }
}
