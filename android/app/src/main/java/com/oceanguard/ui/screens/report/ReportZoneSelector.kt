package com.oceanguard.ai.ui.screens.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.data.ZoneCluster

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZoneSelector(
    zoneNames: List<String>,
    selectedIndex: Int?,
    onZoneSelected: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = selectedIndex?.let { zoneNames.getOrNull(it) }
        ?: stringResource(R.string.report_zone_selector_hint)

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.report_zone_selector_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = displayValue,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            ExposedDropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
            ) {
                zoneNames.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onZoneSelected(index)
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ZonePreviewCard(
    zone: ZoneCluster,
    zoneName: String,
    dayCount: Int,
    modifier: Modifier = Modifier,
) {
    val trendIcon = when (zone.trend) {
        HealthTrend.IMPROVING -> Icons.AutoMirrored.Filled.TrendingUp
        HealthTrend.DEGRADING -> Icons.AutoMirrored.Filled.TrendingDown
        HealthTrend.STABLE -> Icons.AutoMirrored.Filled.TrendingFlat
    }
    val trendColor = when (zone.trend) {
        HealthTrend.IMPROVING -> MaterialTheme.colorScheme.tertiary
        HealthTrend.DEGRADING -> MaterialTheme.colorScheme.error
        HealthTrend.STABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trendLabel = when (zone.trend) {
        HealthTrend.IMPROVING -> stringResource(R.string.report_zone_preview_trend_improving)
        HealthTrend.DEGRADING -> stringResource(R.string.report_zone_preview_trend_degrading)
        HealthTrend.STABLE -> stringResource(R.string.report_zone_preview_trend_stable)
    }
    val totalDebris = zone.sessions.sumOf { it.totalCount }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Zone name + trend
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = zoneName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = trendIcon,
                    contentDescription = trendLabel,
                    modifier = Modifier.size(20.dp),
                    tint = trendColor,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = trendLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = trendColor,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(
                    label = stringResource(R.string.report_zone_preview_days, dayCount),
                    value = "$dayCount",
                )
                StatItem(
                    label = "Sessions",
                    value = "${zone.sessions.size}",
                )
                StatItem(
                    label = "Debris",
                    value = "$totalDebris",
                )
                StatItem(
                    label = "Health",
                    value = "${zone.aggregateHealthScore}/100",
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
