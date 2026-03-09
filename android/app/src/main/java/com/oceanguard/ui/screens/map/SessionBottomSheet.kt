package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.ui.theme.GlassBorder
import com.oceanguard.ai.ui.theme.GlassSurface
import com.oceanguard.ai.ui.theme.HealthExcellent
import com.oceanguard.ai.ui.theme.HealthCritical
import com.oceanguard.ai.ui.theme.healthScoreColor
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.ui.components.PlaceNameText
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Glassmorphism-style bottom sheet showing a tapped detection session's summary.
 * When the session belongs to a [ZoneCluster] the aggregate zone health score
 * and trend are displayed as an additional row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionMapBottomSheet(
    session: DetectionSession,
    onDismiss: () -> Unit,
    onViewDetails: (Long) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    zoneScore: Int? = null,
    zoneTrend: HealthTrend? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Health score pill + timestamp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = healthScoreColor(session.healthScore),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(GlassBorder, Color.Transparent),
                        ),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.map_sheet_health_label, session.healthScore),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
                        .format(session.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Detection stats
            Surface(
                color = GlassSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(GlassBorder, Color.Transparent),
                    ),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.map_sheet_items_detected, session.totalCount),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.map_sheet_risk_level, session.getRiskLevel().name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    session.location?.let {
                        val ctx = LocalContext.current
                        val geocoder = remember(ctx) {
                            (ctx.applicationContext as OceanGuardApp).geocoder
                        }
                        PlaceNameText(
                            location = session.location,
                            geocoder = geocoder,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Zone aggregate health row (only when zone context is available)
            if (zoneScore != null) {
                ZoneHealthRow(zoneScore = zoneScore, trend = zoneTrend)
            }

            // View full details button
            Button(
                onClick = { onViewDetails(session.id) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.map_sheet_btn_view_details))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private helper
// ---------------------------------------------------------------------------

@Composable
private fun ZoneHealthRow(zoneScore: Int, trend: HealthTrend?) {
    val scoreColor = healthScoreColor(zoneScore)
    val (trendIcon, trendLabelRes, trendColor) = when (trend) {
        HealthTrend.IMPROVING  -> Triple(
            Icons.AutoMirrored.Filled.TrendingUp, R.string.map_sheet_trend_improving, HealthExcellent
        )
        HealthTrend.DEGRADING  -> Triple(
            Icons.AutoMirrored.Filled.TrendingDown, R.string.map_sheet_trend_degrading, HealthCritical
        )
        else                   -> Triple(
            Icons.AutoMirrored.Filled.TrendingFlat, R.string.map_sheet_trend_stable, scoreColor
        )
    }
    val trendLabel = stringResource(trendLabelRes)

    Surface(
        color = scoreColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.map_sheet_zone_health_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = zoneScore.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = trendIcon,
                    contentDescription = trendLabel,
                    tint = trendColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = trendLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = trendColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
