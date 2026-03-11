package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen

/**
 * Radial darkening at screen edges for cinematic depth.
 * Renders a transparent-center -> dark-edges overlay on top of content.
 */
fun Modifier.oceanVignette(
    strength: Float = 0.15f,
): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = strength),
            ),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.width.coerceAtLeast(size.height) * 0.7f,
        ),
    )
}

/**
 * Navy-tinted ambient shadow behind elevated components.
 * Draws 3 layered rounded rectangles with decreasing alpha for soft glow.
 */
fun Modifier.depthShadow(
    cornerRadius: Float = 20f,
): Modifier = drawBehind {
    val shadowColor = Color(0xFF061220)
    val layers = listOf(
        Triple(6.dp.toPx(), 0.12f, 8.dp.toPx()),
        Triple(4.dp.toPx(), 0.08f, 4.dp.toPx()),
        Triple(2.dp.toPx(), 0.05f, 2.dp.toPx()),
    )
    layers.forEach { (offsetY, alpha, expand) ->
        drawRoundRect(
            color = shadowColor.copy(alpha = alpha),
            topLeft = Offset(-expand, offsetY - expand),
            size = Size(size.width + expand * 2, size.height + expand * 2),
            cornerRadius = CornerRadius(cornerRadius.dp.toPx()),
        )
    }
}

/**
 * Diagonal ocean-tinted shimmer sweep for loading states and unlock shine.
 * Animates a gradient band across the content on a 2.5s loop.
 */
fun Modifier.oceanShimmer(): Modifier = composed {
    val infiniteTransition = rememberInfiniteTransition(label = "oceanShimmer")
    val progress by infiniteTransition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
        ),
        label = "shimmerProgress",
    )

    drawWithContent {
        drawContent()
        val bandWidth = size.width * 0.4f
        val startX = size.width * progress - bandWidth
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    OceanGreen.copy(alpha = 0.06f),
                    OceanBlueLight.copy(alpha = 0.08f),
                    OceanGreen.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
                startX = startX,
                endX = startX + bandWidth,
            ),
        )
    }
}
