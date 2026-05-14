package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oceanguard.ai.ui.UiState
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen

/**
 * Two-chip horizontal progress strip for the single-image analysis pipeline.
 *
 * Stage 1: "Preparing" -- active during [UiState.ModelLoading] (image load,
 *          model cold-start). Done as soon as a detector pass starts.
 * Stage 2: "Detecting" or "Deep analysis" -- active during [UiState.Detecting]
 *          and [UiState.AnalyzingDeep]. Label switches based on [isDeepAnalysis]
 *          so the diver sees "Deep analysis" when Gemma 4 Vision is running.
 *
 * Both chips render at >=14sp with high contrast to stay legible through a dive
 * mask and underwater housing. The active chip pulses softly; completed chips
 * use the OceanGreen check icon.
 *
 * Designed for inclusion above the existing [LoadingContent] — does not replace
 * the descriptive subtitle / elapsed-seconds timer.
 */
@Composable
fun AnalysisProgressIndicator(
    state: UiState,
    isDeepAnalysis: Boolean,
    modifier: Modifier = Modifier,
) {
    val (prepareStatus, analyzeStatus) = stageStatuses(state)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageChip(
            label = "Preparing",
            status = prepareStatus,
            modifier = Modifier.weight(1f),
        )
        StageChip(
            label = if (isDeepAnalysis) "Deep analysis" else "Detecting",
            status = analyzeStatus,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class ChipStatus { PENDING, ACTIVE, DONE }

private fun stageStatuses(state: UiState): Pair<ChipStatus, ChipStatus> = when (state) {
    is UiState.ModelLoading -> ChipStatus.ACTIVE to ChipStatus.PENDING
    is UiState.Detecting,
    is UiState.AnalyzingDeep,
    is UiState.DetectionsReady,
    -> ChipStatus.DONE to ChipStatus.ACTIVE
    is UiState.AnalysisComplete -> ChipStatus.DONE to ChipStatus.DONE
    else -> ChipStatus.PENDING to ChipStatus.PENDING
}

@Composable
private fun StageChip(
    label: String,
    status: ChipStatus,
    modifier: Modifier = Modifier,
) {
    val (background, border, content) = when (status) {
        ChipStatus.DONE -> Triple(
            OceanGreen.copy(alpha = 0.18f),
            OceanGreen.copy(alpha = 0.6f),
            OceanGreen,
        )
        ChipStatus.ACTIVE -> Triple(
            OceanBlueLight.copy(alpha = 0.20f),
            OceanBlueLight.copy(alpha = 0.7f),
            OceanBlueLight,
        )
        ChipStatus.PENDING -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Soft pulse on the active chip — alpha cycles between 0.7 and 1.0.
    val pulseAlpha = if (status == ChipStatus.ACTIVE) {
        val transition = rememberInfiniteTransition(label = "stage_pulse")
        transition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "stage_pulse_alpha",
        ).value
    } else 1.0f

    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(
                width = 1.dp,
                color = border,
                shape = RoundedCornerShape(20.dp),
            )
            .alpha(pulseAlpha)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        StageIcon(status, content)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = content,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun StageIcon(status: ChipStatus, color: Color) {
    when (status) {
        ChipStatus.DONE -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        ChipStatus.ACTIVE -> Box(
            modifier = Modifier
                .size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = color,
                strokeWidth = 2.dp,
            )
        }
        ChipStatus.PENDING -> Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.4f)),
        )
    }
}
