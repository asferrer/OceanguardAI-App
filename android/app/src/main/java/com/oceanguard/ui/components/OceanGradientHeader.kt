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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.theme.GradientDeepEnd
import com.oceanguard.ai.ui.theme.GradientDeepMid
import com.oceanguard.ai.ui.theme.GradientDeepStart
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlin.math.sin

/**
 * Animated ocean gradient header with the OceanGuard logo, wave overlays,
 * and spring + floating animations.
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
                        GradientDeepStart,
                        GradientDeepMid,
                        GradientDeepEnd.copy(alpha = 0.3f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedWaveBackground()

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

            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
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

    Canvas(modifier = Modifier.fillMaxSize()) {
        val waveConfigs = listOf(
            Triple(0.65f, 0.04f, OceanBlueLight.copy(alpha = 0.12f)),
            Triple(0.72f, 0.03f, OceanGreen.copy(alpha = 0.10f)),
            Triple(0.80f, 0.025f, OceanBlueLight.copy(alpha = 0.08f)),
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
                    (xFloat / size.width * 4 * Math.PI + phase + phaseOffset).toFloat()
                )
                wavePath.lineTo(xFloat, y)
            }

            drawPath(
                path = wavePath,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
