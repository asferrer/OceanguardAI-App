package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DayGroup
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.ui.components.PlaceNameText
import com.oceanguard.ai.ui.theme.GlassBorder
import com.oceanguard.ai.ui.theme.GlassSurface
import com.oceanguard.ai.ui.theme.HealthCritical
import com.oceanguard.ai.ui.theme.HealthExcellent
import com.oceanguard.ai.ui.theme.healthScoreColor
import com.oceanguard.ai.utils.toPlaceLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet displaying day-grouped sessions within a [ZoneCluster].
 *
 * Shows aggregate stats, place name, day filter chips, and a scrollable
 * list grouped by calendar day with sticky headers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneMapBottomSheet(
    zone: ZoneCluster,
    onDismiss: () -> Unit,
    onSessionClick: (sessionId: Long) -> Unit,
    onGenerateReport: (centroidLat: Double, centroidLon: Double, locationName: String) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val scoreColor = healthScoreColor(zone.aggregateHealthScore)
    val ctx = LocalContext.current
    val geocoder = remember(ctx) {
        (ctx.applicationContext as OceanGuardApp).geocoder
    }
    val dayGroups = remember(zone) { ZoneAggregator.groupByDay(zone) }
    var selectedDay by remember { mutableStateOf<Date?>(null) }
    var resolvedPlaceName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(zone) {
        val result = geocoder.reverse(zone.centroidLat, zone.centroidLon)
        resolvedPlaceName = result?.toPlaceLabel()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Header: health pill + trend ----
            ZoneHeader(zone = zone, scoreColor = scoreColor)

            PlaceNameText(
                location = Location(zone.centroidLat, zone.centroidLon),
                geocoder = geocoder,
                style = MaterialTheme.typography.titleSmall,
            )

            // ---- Stats summary ----
            ZoneStatsBar(zone = zone, dayGroups = dayGroups, scoreColor = scoreColor)

            // ---- Generate report button ----
            OutlinedButton(
                onClick = {
                    onGenerateReport(
                        zone.centroidLat,
                        zone.centroidLon,
                        resolvedPlaceName ?: ctx.getString(R.string.zone_name_unknown),
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Analytics,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 4.dp),
                )
                Text(text = stringResource(R.string.zone_sheet_btn_generate_report))
            }

            // ---- Day filter chips ----
            if (dayGroups.size > 1) {
                DayFilterChips(
                    dayGroups = dayGroups,
                    selectedDay = selectedDay,
                    onDaySelected = { selectedDay = it },
                )
            }

            // ---- Section header ----
            Text(
                text = stringResource(R.string.zone_sheet_sessions_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Day-grouped session list ----
            val displayGroups = if (selectedDay != null) {
                dayGroups.filter { it.date == selectedDay }
            } else {
                dayGroups
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 350.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                displayGroups.forEach { dayGroup ->
                    stickyHeader(key = dayGroup.date.time) {
                        DayGroupHeader(dayGroup = dayGroup)
                    }
                    items(
                        items = dayGroup.sessions,
                        key = { it.id },
                    ) { session ->
                        SessionSummaryRow(
                            session = session,
                            onClick = { onSessionClick(session.id) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private composables
// ---------------------------------------------------------------------------

@Composable
private fun ZoneHeader(zone: ZoneCluster, scoreColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = scoreColor,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(listOf(GlassBorder, Color.Transparent)),
            ),
        ) {
            Text(
                text = stringResource(R.string.map_sheet_health_label, zone.aggregateHealthScore),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }

        val (trendIcon, trendColor) = when (zone.trend) {
            HealthTrend.IMPROVING -> Icons.AutoMirrored.Filled.TrendingUp to HealthExcellent
            HealthTrend.DEGRADING -> Icons.AutoMirrored.Filled.TrendingDown to HealthCritical
            HealthTrend.STABLE -> Icons.AutoMirrored.Filled.TrendingFlat to scoreColor
        }
        Icon(
            imageVector = trendIcon,
            contentDescription = null,
            tint = trendColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ZoneStatsBar(
    zone: ZoneCluster,
    dayGroups: List<DayGroup>,
    scoreColor: Color,
) {
    Surface(
        color = GlassSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(listOf(GlassBorder, Color.Transparent)),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZoneStat(
                label = stringResource(R.string.zone_sheet_stat_dives),
                value = dayGroups.size.toString(),
                modifier = Modifier.weight(1f),
            )
            ZoneStat(
                label = stringResource(R.string.zone_sheet_stat_images),
                value = zone.sessions.size.toString(),
                modifier = Modifier.weight(1f),
            )
            ZoneStat(
                label = stringResource(R.string.zone_sheet_stat_total_items),
                value = zone.sessions.sumOf { it.totalCount }.toString(),
                modifier = Modifier.weight(1f),
            )
            ZoneStat(
                label = stringResource(R.string.zone_sheet_stat_avg_health),
                value = zone.aggregateHealthScore.toString(),
                valueColor = scoreColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayFilterChips(
    dayGroups: List<DayGroup>,
    selectedDay: Date?,
    onDaySelected: (Date?) -> Unit,
) {
    val chipFormatter = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedDay == null,
            onClick = { onDaySelected(null) },
            label = { Text(stringResource(R.string.zone_sheet_day_all)) },
        )
        dayGroups.forEach { dayGroup ->
            FilterChip(
                selected = selectedDay == dayGroup.date,
                onClick = {
                    onDaySelected(if (selectedDay == dayGroup.date) null else dayGroup.date)
                },
                label = { Text(chipFormatter.format(dayGroup.date)) },
            )
        }
    }
}

@Composable
private fun DayGroupHeader(dayGroup: DayGroup) {
    val headerFormatter = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = headerFormatter.format(dayGroup.date),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.zone_sheet_day_summary, dayGroup.sessions.size, dayGroup.totalDebris),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ZoneStat(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SessionSummaryRow(
    session: DetectionSession,
    onClick: () -> Unit,
) {
    val scoreColor = healthScoreColor(session.healthScore)
    val dateFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val thumbnailSource = session.thumbnailUri ?: session.imageUri
            AsyncImage(
                model = thumbnailSource,
                contentDescription = stringResource(R.string.cd_session_thumbnail),
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateFormatter.format(session.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.map_sheet_items_detected, session.totalCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                color = scoreColor,
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = session.healthScore.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
