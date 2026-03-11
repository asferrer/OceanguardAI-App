package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.theme.BioluminescentCyan
import com.oceanguard.ai.ui.theme.GradientCTAEnd
import com.oceanguard.ai.ui.theme.GradientCTAStart
import com.oceanguard.ai.ui.theme.GradientHeroEnd
import com.oceanguard.ai.ui.theme.GradientHeroStart
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated ocean gradient header with the OceanGuard logo, filled wave overlays,
 * caustic light effects, gradient text, and spring + floating animations.
 */
@Composable
fun OceanGradientHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    // --- Entry animation: spring scale-in ---
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    val entryScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "logoEntryScale",
    )
    val entryAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "logoEntryAlpha",
    )

    // --- Continuous floating animation ---
    val infiniteTransition = rememberInfiniteTransition(label = "logoFloat")
    val floatY by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatY",
    )
    val floatRotation by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatRotation",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GradientHeroStart,
                        GradientHeroEnd,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedWaveBackground()
        BubbleParticles()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            // --- Animated logo ---
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "OceanGuard AI Logo",
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        scaleX = entryScale
                        scaleY = entryScale
                        alpha = entryAlpha
                        translationY = floatY
                        rotationZ = floatRotation
                    },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // --- Gradient text title ---
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White, GradientCTAStart, GradientCTAEnd),
                    ),
                ),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = entryAlpha },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = entryAlpha },
            )
        }
    }
}

/**
 * Filled wave shapes + caustic light patches for deep underwater atmosphere.
 */
@Composable
private fun AnimatedWaveBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
        ),
        label = "wavePhase",
    )
    // Slow caustic drift (20s cycle, separate from waves)
    val causticPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
        ),
        label = "causticPhase",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        // --- Caustic light patches ---
        val caustics = listOf(
            Triple(0.25f, 0.35f, OceanBlueLight.copy(alpha = 0.04f)),
            Triple(0.70f, 0.25f, BioluminescentCyan.copy(alpha = 0.03f)),
            Triple(0.50f, 0.65f, OceanGreen.copy(alpha = 0.03f)),
        )
        caustics.forEachIndexed { i, (baseX, baseY, color) ->
            val offset = i * 2.1f
            val cx = size.width * baseX + sin(causticPhase + offset) * size.width * 0.06f
            val cy = size.height * baseY + cos(causticPhase + offset * 0.7f) * size.height * 0.08f
            val radius = size.width * 0.18f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }

        // --- Filled wave shapes ---
        val waveConfigs = listOf(
            Triple(0.62f, 0.045f, OceanBlueLight.copy(alpha = 0.06f)),
            Triple(0.70f, 0.035f, OceanGreen.copy(alpha = 0.05f)),
            Triple(0.78f, 0.028f, OceanBlueLight.copy(alpha = 0.04f)),
        )

        waveConfigs.forEachIndexed { index, (yFraction, amplitudeFraction, color) ->
            val wavePath = Path()
            val amplitude = size.height * amplitudeFraction
            val yCenter = size.height * yFraction
            val phaseOffset = index * 1.2f

            wavePath.moveTo(0f, yCenter)
            for (x in 0..size.width.toInt() step 3) {
                val xFloat = x.toFloat()
                val y = yCenter + amplitude * sin(
                    (xFloat / size.width * 4 * Math.PI + phase + phaseOffset).toFloat(),
                )
                wavePath.lineTo(xFloat, y)
            }
            // Close the path to fill from wave line down to bottom
            wavePath.lineTo(size.width, size.height)
            wavePath.lineTo(0f, size.height)
            wavePath.close()

            drawPath(path = wavePath, color = color)
        }
    }
}
