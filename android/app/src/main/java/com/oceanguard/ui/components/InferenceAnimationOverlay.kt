package com.oceanguard.ai.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.theme.OceanGreen

/**
 * Inference feedback overlay shown on top of the captured image while the
 * detection pipeline is running.
 *
 * Combines three synchronised animations to communicate "the model is working":
 *  - Sonar pulse: three expanding rings emanating from the centre.
 *  - Scan beam: a thin horizontal line sweeping top-to-bottom (re-uses the
 *    [ScanningOverlay] visual language).
 *  - Reticle: a pulsing circular crosshair anchored at the centre.
 *
 * The overlay is semi-transparent (no full scrim) so the user still sees the
 * captured photo behind it. A status pill near the bottom displays
 * [statusText] (and optional [subText]) with an animated three-dot indicator
 * on its right.
 *
 * @param statusText  Main message shown in the status pill.
 * @param subText     Optional secondary line (e.g. "Open-vocabulary AI · ~25 s").
 * @param accentColor Animation tint. Defaults to the ocean-green theme accent.
 */
@Composable
fun InferenceAnimationOverlay(
    statusText: String,
    modifier: Modifier = Modifier,
    subText: String? = null,
    accentColor: Color = OceanGreen,
) {
    val infinite = rememberInfiniteTransition(label = "inference_overlay")

    val wave1 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "sonar_wave_1",
    )
    val wave2 by infinite.animateFloat(
        initialValue = 0.33f,
        targetValue = 1.33f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "sonar_wave_2",
    )
    val wave3 by infinite.animateFloat(
        initialValue = 0.66f,
        targetValue = 1.66f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "sonar_wave_3",
    )

    val reticleScale by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reticle_scale",
    )
    val reticleAlpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reticle_alpha",
    )

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrimEdgeColor = MaterialTheme.colorScheme.background

    Box(modifier = modifier.fillMaxSize()) {
        // Soft radial scrim tinted in the *theme* background colour so the
        // overlay fades into the same chrome that surrounds every other screen
        // in the app (Home / History / etc.) instead of into a different
        // near-black blue.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            scrimEdgeColor.copy(alpha = 0.55f),
                        ),
                    ),
                ),
        )

        // Horizontal scan beam — reuse the existing component for consistency.
        ScanningOverlay(
            modifier = Modifier.fillMaxSize(),
            lineColor = accentColor,
            cycleDurationMs = 1800,
        )

        // Sonar pulses + reticle on a single canvas so they share the centre.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = minOf(size.width, size.height) * 0.42f

            listOf(wave1, wave2, wave3).forEach { progress ->
                val p = progress % 1f
                val radius = maxRadius * p
                val alpha = (1f - p).coerceIn(0f, 1f) * 0.55f
                drawCircle(
                    color = accentColor.copy(alpha = alpha),
                    radius = radius,
                    center = centre,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            val reticleRadius = (maxRadius * 0.18f) * reticleScale
            val tint = accentColor.copy(alpha = reticleAlpha)
            drawCircle(
                color = tint,
                radius = reticleRadius,
                center = centre,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = tint.copy(alpha = reticleAlpha * 0.18f),
                radius = reticleRadius,
                center = centre,
            )

            val tick = reticleRadius * 0.45f
            val gap = reticleRadius * 1.15f
            val strokePx = 2.dp.toPx()
            drawLine(tint, Offset(centre.x, centre.y - gap - tick), Offset(centre.x, centre.y - gap), strokePx)
            drawLine(tint, Offset(centre.x, centre.y + gap), Offset(centre.x, centre.y + gap + tick), strokePx)
            drawLine(tint, Offset(centre.x - gap - tick, centre.y), Offset(centre.x - gap, centre.y), strokePx)
            drawLine(tint, Offset(centre.x + gap, centre.y), Offset(centre.x + gap + tick, centre.y), strokePx)
        }

        // In landscape the screen height is short, so the status pill sits
        // close to the bottom edge (above the system bars) instead of being
        // floated half-way up the screen.
        val pillBottomPadding = if (isLandscape) 16.dp else 120.dp
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = pillBottomPadding, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    AnimatedDots(color = accentColor)
                }
            }
            if (!subText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subText,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AnimatedDots(color: Color) {
    val infinite = rememberInfiniteTransition(label = "dots")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "dots_phase",
    )
    // derivedStateOf so the Row only recomposes when the *integer* index flips
    // (≈3 times per second) instead of every animation frame (60 fps).
    val activeIndex by remember { derivedStateOf { phase.toInt() % 3 } }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0..2) {
            // Fixed size — no per-frame measure/layout, only colour swap when
            // the active dot moves.
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (i == activeIndex) color else color.copy(alpha = 0.35f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}
