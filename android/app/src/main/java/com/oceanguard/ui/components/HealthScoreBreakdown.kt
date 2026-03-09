package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.HealthScoreFactors
import com.oceanguard.ai.ui.theme.CoralRed
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlinx.coroutines.launch

// Amber-orange for risk level — matches HealthPoor in the palette
private val RiskAmber = Color(0xFFFF9800)

// Max penalty values — used to normalise progress fraction
private const val MAX_DEBRIS_COUNT_IMPACT = 30
private const val MAX_RISK_LEVEL_IMPACT = 50
private const val MAX_DIVERSITY_IMPACT = 10

/**
 * Compact three-row breakdown panel showing what penalises the health score.
 *
 * Designed to fit directly below [HealthScoreGauge] inside a GlassCard.
 * Each row shows a coloured progress bar and the numeric value as X / MAX
 * so the colour alone never conveys the only meaning (WCAG 1.4.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScoreBreakdown(
    factors: HealthScoreFactors,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState(isPersistent = true)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Title row with info tooltip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.health_breakdown_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )

                TooltipBox(
                    positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                    tooltip = {
                        RichTooltip(
                            title = {
                                Text(stringResource(R.string.health_breakdown_tooltip_title))
                            },
                        ) {
                            Text(stringResource(R.string.health_breakdown_tooltip_body))
                        }
                    },
                    state = tooltipState,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.health_breakdown_tooltip_cd),
                        modifier = Modifier
                            .size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            FactorRow(
                label = stringResource(R.string.health_breakdown_factor_debris_density),
                impact = factors.debrisCountImpact,
                maxImpact = MAX_DEBRIS_COUNT_IMPACT,
                barColor = CoralRed,
                tooltip = stringResource(R.string.health_breakdown_factor_debris_density_tip),
            )
            FactorRow(
                label = stringResource(R.string.health_breakdown_factor_risk_level),
                impact = factors.riskLevelImpact,
                maxImpact = MAX_RISK_LEVEL_IMPACT,
                barColor = RiskAmber,
                tooltip = stringResource(R.string.health_breakdown_factor_risk_level_tip),
            )
            FactorRow(
                label = stringResource(R.string.health_breakdown_factor_material_variety),
                impact = factors.materialDiversityImpact,
                maxImpact = MAX_DIVERSITY_IMPACT,
                barColor = OceanGreen,
                tooltip = stringResource(R.string.health_breakdown_factor_material_variety_tip),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FactorRow(
    label: String,
    impact: Int,
    maxImpact: Int,
    barColor: Color,
    tooltip: String = "",
) {
    val fraction = if (maxImpact > 0) (impact.toFloat() / maxImpact).coerceIn(0f, 1f) else 0f

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            if (tooltip.isNotEmpty()) {
                PlainTooltip { Text(tooltip) }
            }
        },
        state = rememberTooltipState(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$impact / $maxImpact",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (impact > 0) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = barColor,
                trackColor = barColor.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}
