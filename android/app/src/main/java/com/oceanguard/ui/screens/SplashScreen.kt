package com.oceanguard.ai.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.ui.theme.GradientDeepEnd
import com.oceanguard.ai.ui.theme.GradientDeepMid
import com.oceanguard.ai.ui.theme.GradientDeepStart
import kotlinx.coroutines.delay

/**
 * Animated splash screen shown once at app launch.
 *
 * Sequence: ocean gradient fade-in -> logo spring bounce -> title fade-in
 * -> navigates to onboarding (first launch) or home (returning user).
 *
 * Duration: ~1.5s total.
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
        subtitleAlpha.animateTo(1f, animationSpec = tween(400))

        // Hold for a moment
        delay(600)
    }

    // Navigate once onboarding state is resolved
    LaunchedEffect(onboardingComplete) {
        if (onboardingComplete == null) return@LaunchedEffect
        // Wait for animations
        delay(1500)
        if (onboardingComplete == true) {
            onNavigateToHome()
        } else {
            onNavigateToOnboarding()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GradientDeepStart,
                        GradientDeepMid,
                        GradientDeepEnd.copy(alpha = 0.6f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "OceanGuard AI Logo",
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                    }
                    .alpha(logoAlpha.value),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "OceanGuard AI",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alpha(titleAlpha.value),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.home_header_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.alpha(subtitleAlpha.value),
            )
        }
    }
}
