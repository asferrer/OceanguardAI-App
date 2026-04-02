package com.oceanguard.ai.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.BubbleParticles
import com.oceanguard.ai.ui.components.OceanGradientButton
import com.oceanguard.ai.ui.components.OnGradientColor
import com.oceanguard.ai.ui.theme.BioluminescentCyan
import com.oceanguard.ai.ui.theme.GradientCTAEnd
import com.oceanguard.ai.ui.theme.GradientCTAStart
import com.oceanguard.ai.ui.theme.GradientHeroEnd
import com.oceanguard.ai.ui.theme.GradientHeroStart
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val accentColor: Color,
)

/**
 * Onboarding screen with 4 swipeable slides introducing the app.
 * Shown only on first launch; flags completion in DataStore.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Filled.WaterDrop,
            title = stringResource(R.string.onboarding_page1_title),
            description = stringResource(R.string.onboarding_page1_desc),
            accentColor = GradientCTAStart,
        ),
        OnboardingPage(
            icon = Icons.Filled.CameraAlt,
            title = stringResource(R.string.onboarding_page2_title),
            description = stringResource(R.string.onboarding_page2_desc),
            accentColor = GradientCTAEnd,
        ),
        OnboardingPage(
            icon = Icons.Filled.Psychology,
            title = stringResource(R.string.onboarding_page3_title),
            description = stringResource(R.string.onboarding_page3_desc),
            accentColor = BioluminescentCyan,
        ),
        OnboardingPage(
            icon = Icons.Filled.Security,
            title = stringResource(R.string.onboarding_page4_title),
            description = stringResource(R.string.onboarding_page4_desc),
            accentColor = GradientCTAEnd,
        ),
        OnboardingPage(
            icon = Icons.Filled.EmojiEvents,
            title = stringResource(R.string.onboarding_page5_title),
            description = stringResource(R.string.onboarding_page5_desc),
            accentColor = OceanGreen,
        ),
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.lastIndex

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            OnboardingPageContent(page = pages[page])
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Page indicators
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.forEachIndexed { index, _ ->
                    PageIndicator(isActive = index == pagerState.currentPage)
                    if (index < pages.lastIndex) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // CTA button with landing page gradient
            OceanGradientButton(
                onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
                cornerRadius = 16.dp,
            ) {
                if (isLastPage) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = OnGradientColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_btn_get_started),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = OnGradientColor,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.onboarding_btn_continue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnGradientColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = OnGradientColor,
                    )
                }
            }

            // Skip button (not on last page)
            if (!isLastPage) {
                TextButton(
                    onClick = onComplete,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_btn_skip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8), // landing --text-secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    // Content entry animation per page
    val contentAlpha = remember(page.title) { Animatable(0f) }
    val contentOffsetY = remember(page.title) { Animatable(30f) }

    LaunchedEffect(page.title) {
        contentAlpha.animateTo(
            1f,
            animationSpec = tween(500, easing = FastOutSlowInEasing),
        )
    }
    LaunchedEffect(page.title) {
        contentOffsetY.animateTo(
            0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

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
        OnboardingWaves(accentColor = page.accentColor)
        BubbleParticles()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = 160.dp)
                .graphicsLayer {
                    alpha = contentAlpha.value
                    translationY = contentOffsetY.value
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Icon in glass circle with accent border
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(color = Color(0x800F172A)) // landing --glass
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                page.accentColor.copy(alpha = 0.4f),
                                page.accentColor.copy(alpha = 0.1f),
                            ),
                        ),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = page.accentColor,
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Gradient title
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White, GradientCTAStart, GradientCTAEnd),
                    ),
                ),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = page.description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF94A3B8), // landing --text-secondary
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Per-page wave background with accent-tinted caustic. */
@Composable
private fun OnboardingWaves(accentColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "obWave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
        ),
        label = "obWavePhase",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Accent-tinted caustic glow
        val causticPhase = phase * 0.25f
        val cx = size.width * 0.5f + sin(causticPhase) * size.width * 0.08f
        val cy = size.height * 0.35f +
            cos(causticPhase * 0.6f) * size.height * 0.05f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.03f),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = size.width * 0.25f,
            ),
            radius = size.width * 0.25f,
            center = Offset(cx, cy),
        )

        // Two subtle wave layers
        val waveConfigs = listOf(
            Triple(0.72f, 0.025f, OceanBlueLight.copy(alpha = 0.035f)),
            Triple(0.80f, 0.018f, OceanGreen.copy(alpha = 0.03f)),
        )
        waveConfigs.forEachIndexed { index, (yFrac, ampFrac, color) ->
            val wavePath = Path()
            val amplitude = size.height * ampFrac
            val yCenter = size.height * yFrac
            val phaseOff = index * 1.5f

            wavePath.moveTo(0f, yCenter)
            for (x in 0..size.width.toInt() step 4) {
                val xf = x.toFloat()
                val y = yCenter + amplitude * sin(
                    (xf / size.width * 3 * Math.PI + phase + phaseOff).toFloat(),
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

@Composable
private fun PageIndicator(isActive: Boolean) {
    val width by animateDpAsState(
        targetValue = if (isActive) 28.dp else 8.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "indicatorWidth",
    )
    val color by animateColorAsState(
        targetValue = if (isActive) GradientCTAStart else Color.White.copy(alpha = 0.25f),
        label = "indicatorColor",
    )

    Box(
        modifier = Modifier
            .width(width)
            .height(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
