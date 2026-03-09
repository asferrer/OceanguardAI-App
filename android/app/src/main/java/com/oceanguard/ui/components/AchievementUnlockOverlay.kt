package com.oceanguard.ai.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.oceanguard.ai.R
import com.oceanguard.ai.data.collection.AchievementDef
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private val GoldenGradient = Brush.linearGradient(
    listOf(Color(0xFFFFD700), Color(0xFFFFA500), Color(0xFFFFD700)),
)

private val CardShape = RoundedCornerShape(20.dp)

private val KonfettiParty = Party(
    emitter = Emitter(duration = 2, TimeUnit.SECONDS).perSecond(40),
    position = Position.Relative(0.5, 0.3),
    colors = listOf(
        0xFF00C9A7.toInt(),
        0xFF1E90FF.toInt(),
        0xFFFFD700.toInt(),
        0xFFFF6B6B.toInt(),
    ),
)

// ---------------------------------------------------------------------------
// Public composable
// ---------------------------------------------------------------------------

/**
 * Full-screen overlay that celebrates an unlocked achievement.
 *
 * Shown when [achievementDef] transitions from null to non-null. Auto-dismisses
 * after 3.5 seconds. Tapping the scrim also dismisses the overlay.
 *
 * Animation sequence:
 * - 0 ms   : scrim fades in via [AnimatedVisibility]
 * - 100 ms : card scales in with a spring bounce (0 -> 1)
 * - 200 ms : icon rotates 0 -> 360 degrees with a spring
 * - 3500 ms: auto-dismiss via [onDismiss]
 *
 * Haptic feedback ([HapticFeedbackConstants.LONG_PRESS]) fires on each show.
 *
 * @param achievementDef Definition of the achievement to celebrate, or null
 *                       when the overlay should be hidden.
 * @param onDismiss      Called when the overlay should be removed (auto or tap).
 */
@Composable
fun AchievementUnlockOverlay(
    achievementDef: AchievementDef?,
    onDismiss: () -> Unit,
) {
    // Animatable state for card scale and icon rotation; recreated each time
    // a new achievement fires so the animation always starts from scratch.
    val cardScale = remember(achievementDef) { Animatable(0f) }
    val iconRotation = remember(achievementDef) { Animatable(0f) }

    val view = LocalView.current

    // Drive all timed animations and the auto-dismiss from a single effect
    // keyed on achievementDef so it restarts whenever a new achievement fires.
    LaunchedEffect(achievementDef) {
        if (achievementDef == null) return@LaunchedEffect

        // Haptic feedback on show
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        // 100 ms: card spring bounce
        launch {
            delay(100L)
            cardScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }

        // 200 ms: icon rotation
        launch {
            delay(200L)
            iconRotation.animateTo(
                targetValue = 360f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }

        // 3500 ms: auto-dismiss
        delay(3_500L)
        onDismiss()
    }

    AnimatedVisibility(
        visible = achievementDef != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f),
    ) {
        if (achievementDef == null) return@AnimatedVisibility

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Konfetti layer behind the card
            KonfettiView(
                modifier = Modifier.fillMaxSize(),
                parties = listOf(KonfettiParty),
            )

            // Card with spring bounce entry
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = cardScale.value
                        scaleY = cardScale.value
                    }
                    // Prevent card tap from dismissing (only the scrim should)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                AchievementCard(
                    achievementDef = achievementDef,
                    iconRotationDegrees = iconRotation.value,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

@Composable
private fun AchievementCard(
    achievementDef: AchievementDef,
    iconRotationDegrees: Float,
) {
    Box(
        modifier = Modifier
            .clip(CardShape)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shape = CardShape,
            )
            .border(width = 2.dp, brush = GoldenGradient, shape = CardShape)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val achievementName = stringResource(achievementDef.nameRes)
        val achievementDescription = stringResource(achievementDef.descriptionRes)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.achievement_unlock_overlay_title),
                style = MaterialTheme.typography.labelLarge,
                color = OceanGreen,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Icon(
                imageVector = achievementDef.icon,
                contentDescription = achievementName,
                tint = OceanGreen,
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer { rotationZ = iconRotationDegrees },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = achievementName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = achievementDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
