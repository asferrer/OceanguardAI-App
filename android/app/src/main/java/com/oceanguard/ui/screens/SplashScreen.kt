package com.oceanguard.ai.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.ui.components.BubbleParticles
import com.oceanguard.ai.ui.theme.BioluminescentCyan
import com.oceanguard.ai.ui.theme.GradientCTAEnd
import com.oceanguard.ai.ui.theme.GradientCTAStart
import com.oceanguard.ai.ui.theme.GradientHeroEnd
import com.oceanguard.ai.ui.theme.GradientHeroStart
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated splash screen with landing-page-matching aesthetics.
 *
 * Sequence: ocean gradient + bubbles -> logo spring bounce -> gradient title
 * -> accent line -> subtitle -> navigate.
 */
@Composable
fun SplashScreen(
    settingsRepository: SettingsRepository,
    onNavigateToHome: () -> Unit,
    onNavigateToOnboarding: () -> Unit,
) {
    val onboardingComplete by settingsRepository.onboardingComplete
        .collectAsStateWithLifecycle(initialValue = null)

    // Animation states
    val logoScale = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val accentWidth = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Phase 1: Logo bounces in
        logoAlpha.animateTo(1f, animationSpec = tween(300))
        logoScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
        // Phase 2: Title fades in
        titleAlpha.animateTo(1f, animationSpec = tween(400))
        // Phase 3: Accent line expands
        accentWidth.animateTo(
            1f,
            animationSpec = tween(350, easing = FastOutSlowInEasing),
        )
        // Phase 4: Subtitle fades in
        subtitleAlpha.animateTo(1f, animationSpec = tween(350))
        delay(500)
    }

    // Navigate once onboarding state is resolved
    LaunchedEffect(onboardingComplete) {
        if (onboardingComplete == null) return@LaunchedEffect
        delay(1800)
        if (onboardingComplete == true) {
            onNavigateToHome()
        } else {
            onNavigateToOnboarding()
        }
    }

    // Continuous floating animation for logo
    val infiniteTransition = rememberInfiniteTransition(label = "splashFloat")
    val floatY by infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "splashFloatY",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(GradientHeroStart, GradientHeroEnd),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient effects
        SplashWaves()
        BubbleParticles()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Logo with spring + float
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "OceanGuard AI Logo",
                modifier = Modifier
                    .size(130.dp)
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                        alpha = logoAlpha.value
                        translationY = floatY
                    },
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Gradient title matching landing page
            Text(
                text = "OceanGuard AI",
                style = MaterialTheme.typography.headlineLarge.copy(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White, GradientCTAStart, GradientCTAEnd),
                    ),
                ),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = titleAlpha.value },
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Accent line (matches landing hero-accent: cyan -> emerald)
            Canvas(
                modifier = Modifier
                    .size(width = 60.dp, height = 3.dp)
                    .graphicsLayer { alpha = titleAlpha.value },
            ) {
                val lineWidth = size.width * accentWidth.value
                val startX = (size.width - lineWidth) / 2
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(GradientCTAStart, GradientCTAEnd),
                        start = Offset(startX, 0f),
                        end = Offset(startX + lineWidth, 0f),
                    ),
                    topLeft = Offset(startX, 0f),
                    size = Size(lineWidth, size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Subtitle
            Text(
                text = stringResource(R.string.home_header_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF94A3B8), // landing --text-secondary
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = subtitleAlpha.value },
            )
        }
    }
}

/** Subtle filled wave shapes + caustic light patches for splash background. */
@Composable
private fun SplashWaves() {
    val infiniteTransition = rememberInfiniteTransition(label = "splashWave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
        ),
        label = "splashWavePhase",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Caustic light patches
        val causticPhase = phase * 0.3f
        listOf(
            Triple(0.3f, 0.4f, GradientCTAStart.copy(alpha = 0.025f)),
            Triple(0.7f, 0.3f, BioluminescentCyan.copy(alpha = 0.02f)),
        ).forEachIndexed { i, (baseX, baseY, color) ->
            val offset = i * 2.5f
            val cx = size.width * baseX +
                sin(causticPhase + offset) * size.width * 0.05f
            val cy = size.height * baseY +
                cos(causticPhase + offset * 0.7f) * size.height * 0.06f
            val radius = size.width * 0.22f
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

        // Filled wave layers
        val waveConfigs = listOf(
            Triple(0.68f, 0.03f, OceanBlueLight.copy(alpha = 0.04f)),
            Triple(0.75f, 0.025f, OceanGreen.copy(alpha = 0.035f)),
            Triple(0.82f, 0.02f, OceanBlueLight.copy(alpha = 0.03f)),
        )

        waveConfigs.forEachIndexed { index, (yFrac, ampFrac, color) ->
            val wavePath = Path()
            val amplitude = size.height * ampFrac
            val yCenter = size.height * yFrac
            val phaseOff = index * 1.3f

            wavePath.moveTo(0f, yCenter)
            for (x in 0..size.width.toInt() step 4) {
                val xf = x.toFloat()
                val y = yCenter + amplitude * sin(
                    (xf / size.width * 4 * Math.PI + phase + phaseOff).toFloat(),
                )
                wavePath.lineTo(xf, y)
            }
            wavePath.lineTo(size.width, size.height)
            wavePath.lineTo(0f, size.height)
            wavePath.close()
            drawPath(path = wavePath, color = color)
        }
    }
}
