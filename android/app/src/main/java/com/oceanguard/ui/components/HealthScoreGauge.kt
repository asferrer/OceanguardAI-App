package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.HealthScoreFactors
import com.oceanguard.ai.ui.theme.healthScoreColor

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** The arc spans 270 degrees, starting at the bottom-left (135 degrees). */
private const val ARC_START_ANGLE  = 135f
private const val ARC_SWEEP_MAX    = 270f

/** Track ring colour: a muted surface variant. */
private val TrackColor = Color(0x33FFFFFF)  // 20 % white works on both dark/light backgrounds

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Alias for the canonical [healthScoreColor] from Color.kt. */
fun healthColor(score: Int): Color = healthScoreColor(score)

/**
 * Returns label and subtitle resource IDs for the current health band.
 */
private fun healthLabelResPair(score: Int): Pair<Int, Int> = when {
    score >= 80 -> R.string.health_gauge_label_excellent to R.string.health_gauge_subtitle_excellent
    score >= 60 -> R.string.health_gauge_label_good      to R.string.health_gauge_subtitle_good
    score >= 40 -> R.string.health_gauge_label_fair      to R.string.health_gauge_subtitle_fair
    score >= 20 -> R.string.health_gauge_label_poor      to R.string.health_gauge_subtitle_poor
    else        -> R.string.health_gauge_label_critical  to R.string.health_gauge_subtitle_critical
}

// ---------------------------------------------------------------------------
// Composable
// ---------------------------------------------------------------------------

/**
 * Circular gauge that visualises an ecosystem health score from 0 to 100.
 *
 * The arc fills 270 degrees at maximum score. Color transitions through the
 * five semantic health bands (Critical -> Excellent). The filled portion
 * animates smoothly on first composition using [animateFloatAsState] with a
 * 900 ms ease-in-out curve, making the gauge immediately engaging without
 * feeling rushed on underwater review screens.
 *
 * Accessibility: the score is rendered as readable text inside the gauge so
 * the colour alone never carries meaning (WCAG 2.1 SC 1.4.1).
 *
 * @param score   Ecosystem health score in the range [0, 100].
 * @param modifier Standard Compose modifier.
 * @param size    Diameter of the gauge; defaults to 160.dp.
 * @param factors Optional score breakdown — when supplied, a
 *                [HealthScoreBreakdown] panel is rendered below the gauge.
 */
@Composable
fun HealthScoreGauge(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    factors: HealthScoreFactors? = null,
) {
    // Clamp so callers do not need to guard against out-of-range values.
    val clampedScore = score.coerceIn(0, 100)
    val arcColor     = healthColor(clampedScore)
    val targetFraction = clampedScore / 100f
    val (labelRes, subtitleRes) = healthLabelResPair(clampedScore)
    val label    = stringResource(labelRes)
    val subtitle = stringResource(subtitleRes)

    // Animate from 0 to the target fraction on first composition or when
    // the score changes (e.g. when navigating into a results screen).
    var animationTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(clampedScore) { animationTarget = targetFraction }

    val animatedFraction by animateFloatAsState(
        targetValue    = animationTarget,
        animationSpec  = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label          = "healthGaugeFraction"
    )

    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier.size(size)
        ) {
            // ----------------------------------------------------------------
            // Canvas: track ring + filled arc
            // ----------------------------------------------------------------
            Canvas(modifier = Modifier.size(size)) {
                val strokeWidth = (size.toPx() * 0.10f).coerceAtLeast(10f)
                val inset       = strokeWidth / 2f
                val arcSize     = Size(
                    width  = this.size.width  - strokeWidth,
                    height = this.size.height - strokeWidth
                )
                val topLeft = Offset(inset, inset)

                // 1. Background track (full 270-degree ring in muted grey)
                drawArc(
                    color      = TrackColor,
                    startAngle = ARC_START_ANGLE,
                    sweepAngle = ARC_SWEEP_MAX,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSize,
                    style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // 2. Subtle glow behind the filled arc (two soft layers)
                if (animatedFraction > 0f) {
                    // Outer diffuse glow
                    val glowOuter = strokeWidth * 0.5f
                    drawArc(
                        color      = arcColor.copy(alpha = 0.06f),
                        startAngle = ARC_START_ANGLE,
                        sweepAngle = ARC_SWEEP_MAX * animatedFraction,
                        useCenter  = false,
                        topLeft    = Offset(inset - glowOuter, inset - glowOuter),
                        size       = Size(
                            arcSize.width + glowOuter * 2,
                            arcSize.height + glowOuter * 2,
                        ),
                        style      = Stroke(width = strokeWidth * 2f, cap = StrokeCap.Round),
                    )
                    // Inner tight glow
                    val glowInner = strokeWidth * 0.2f
                    drawArc(
                        color      = arcColor.copy(alpha = 0.10f),
                        startAngle = ARC_START_ANGLE,
                        sweepAngle = ARC_SWEEP_MAX * animatedFraction,
                        useCenter  = false,
                        topLeft    = Offset(inset - glowInner, inset - glowInner),
                        size       = Size(
                            arcSize.width + glowInner * 2,
                            arcSize.height + glowInner * 2,
                        ),
                        style      = Stroke(width = strokeWidth * 1.4f, cap = StrokeCap.Round),
                    )
                }

                // 3. Filled portion (animated)
                if (animatedFraction > 0f) {
                    drawArc(
                        color      = arcColor,
                        startAngle = ARC_START_ANGLE,
                        sweepAngle = ARC_SWEEP_MAX * animatedFraction,
                        useCenter  = false,
                        topLeft    = topLeft,
                        size       = arcSize,
                        style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            // ----------------------------------------------------------------
            // Text: score number + health band label (shifted up to avoid
            // overlapping the lower arc edges)
            // ----------------------------------------------------------------
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .offset(y = -(size * 0.06f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text       = clampedScore.toString(),
                    fontSize   = (size.value * 0.24f).sp,
                    fontWeight = FontWeight.Bold,
                    color      = arcColor,
                    lineHeight = (size.value * 0.28f).sp,
                )
                Text(
                    text     = label,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Subtitle below the gauge circle (moved out to avoid overlap)
        Text(
            text     = subtitle,
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Label below the gauge circle
        Text(
            text  = stringResource(R.string.health_gauge_footer),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Optional breakdown panel
        if (factors != null) {
            Spacer(modifier = Modifier.height(12.dp))
            HealthScoreBreakdown(factors = factors)
        }
    }
}
