package com.oceanguard.ai.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.theme.GradientDeepEnd
import com.oceanguard.ai.ui.theme.GradientDeepMid
import com.oceanguard.ai.ui.theme.GradientDeepStart
import com.oceanguard.ai.ui.theme.OceanBlue
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val gradientColors: List<Color>,
)

/**
 * Onboarding screen with 4 swipeable slides introducing the app.
 * Shown only on first launch; flags completion in DataStore.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    // Page data is built inside the composable so stringResource() is available.
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Filled.WaterDrop,
            title = stringResource(R.string.onboarding_page1_title),
            description = stringResource(R.string.onboarding_page1_desc),
            gradientColors = listOf(
                GradientDeepStart,
                GradientDeepMid,
                GradientDeepEnd.copy(alpha = 0.5f),
            ),
        ),
        OnboardingPage(
            icon = Icons.Filled.CameraAlt,
            title = stringResource(R.string.onboarding_page2_title),
            description = stringResource(R.string.onboarding_page2_desc),
            gradientColors = listOf(
                Color(0xFF0A1929),
                OceanBlue.copy(alpha = 0.6f),
                Color(0xFF003D5C),
            ),
        ),
        OnboardingPage(
            icon = Icons.Filled.Psychology,
            title = stringResource(R.string.onboarding_page3_title),
            description = stringResource(R.string.onboarding_page3_desc),
            gradientColors = listOf(
                Color(0xFF0A1929),
                Color(0xFF1A3449),
                OceanGreen.copy(alpha = 0.4f),
            ),
        ),
        OnboardingPage(
            icon = Icons.Filled.Security,
            title = stringResource(R.string.onboarding_page4_title),
            description = stringResource(R.string.onboarding_page4_desc),
            gradientColors = listOf(
                Color(0xFF0A1929),
                Color(0xFF006B61),
                OceanGreen.copy(alpha = 0.5f),
            ),
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

            // Action button
            Button(
                onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OceanGreen,
                ),
            ) {
                if (isLastPage) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_btn_get_started),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.onboarding_btn_continue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
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
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(colors = page.gradientColors),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = 160.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Large icon
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        color = OceanGreen.copy(alpha = 0.15f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = OceanGreen,
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = page.description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
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
        targetValue = if (isActive) OceanGreen else Color.White.copy(alpha = 0.3f),
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
