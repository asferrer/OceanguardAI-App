package com.oceanguard.ai.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.utils.toPlaceLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.ZoneCluster

/**
 * Home-screen card showing the **top 3 hotspots** — geographical clusters of
 * sessions with the highest debris count. Sits below [TopDebrisTypesCard].
 *
 * Each row is clickable; tap navigates to the Map screen so the user can see
 * the cluster in context. The Map screen does not currently accept a
 * lat/lon focus argument, so we navigate without args and the user sees all
 * clusters at once — the dominant ones are visually obvious from the marker
 * sizes.
 *
 * Sessions without GPS are excluded by [ZoneAggregator.cluster] (returns
 * empty when no located sessions exist). When fewer than 3 hotspots are
 * available, only the actual ones are rendered.
 *
 * Coordinates are formatted to 4 decimal places (~10 m accuracy) for a
 * compact preview; the user gets the full map view on tap if they want
 * precise navigation.
 *
 * @param sessions Full session list from the ViewModel — same source used by
 *                 [TopDebrisTypesCard] so the two cards consume the same data
 *                 and refresh together.
 * @param geocodedNames Optional map of `clusterKey → human-readable name`
 *                 (e.g. "Camí del Carabassí, Santa Pola") if a parent screen
 *                 already resolved place names. Pass empty to fall back to
 *                 coordinates.
 * @param onHotspotClick Lambda called with the cluster's centroid when a
 *                 hotspot row is tapped. The caller is expected to navigate
 *                 to the Map screen.
 */
@Composable
fun TopHotspotsCard(
    sessions: List<DetectionSession>,
    geocodedNames: Map<String, String> = emptyMap(),
    onHotspotClick: (lat: Double, lon: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hotspots: List<ZoneCluster> = remember(sessions) {
        ZoneAggregator.cluster(sessions)
            .sortedByDescending { cluster -> cluster.sessions.sumOf { it.totalCount } }
            .take(3)
    }

    // Resolve human-readable place names for each hotspot centroid. Starts
    // from caller-supplied names (if any), then fills gaps via reverse-geocoding.
    val context = LocalContext.current
    val geocoder = remember(context) { (context.applicationContext as OceanGuardApp).geocoder }
    var resolvedNames by remember { mutableStateOf(geocodedNames) }
    LaunchedEffect(hotspots) {
        if (hotspots.isEmpty()) return@LaunchedEffect
        val fresh = hotspots.map { cluster ->
            async {
                val key = cluster.locationKey()
                if (resolvedNames.containsKey(key)) return@async key to resolvedNames[key]!!
                val result = geocoder.reverse(cluster.centroidLat, cluster.centroidLon)
                key to (result?.toPlaceLabel() ?: "%.4f, %.4f".format(cluster.centroidLat, cluster.centroidLon))
            }
        }.awaitAll()
        resolvedNames = fresh.toMap()
    }

    val a11y = remember(hotspots) {
        if (hotspots.isEmpty()) {
            "Top hotspots: no located sessions yet"
        } else {
            "Top debris hotspots: " + hotspots.joinToString(", ") { cluster ->
                val total = cluster.sessions.sumOf { it.totalCount }
                "%.4f, %.4f with %d items".format(cluster.centroidLat, cluster.centroidLon, total)
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11y },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.home_hotspots_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_hotspots_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hotspots.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (hotspots.isEmpty()) {
                EmptyHotspotsPlaceholder()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hotspots.forEachIndexed { idx, cluster ->
                        HotspotRow(
                            rank = idx + 1,
                            cluster = cluster,
                            displayName = resolvedNames[cluster.locationKey()],
                            onClick = {
                                onHotspotClick(cluster.centroidLat, cluster.centroidLon)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Stable key for matching a cluster to its geocoded name. */
private fun ZoneCluster.locationKey(): String =
    "%.4f,%.4f".format(centroidLat, centroidLon)

@Composable
private fun HotspotRow(
    rank: Int,
    cluster: ZoneCluster,
    displayName: String?,
    onClick: () -> Unit,
) {
    val totalDebris = remember(cluster) { cluster.sessions.sumOf { it.totalCount } }
    val sessionCount = cluster.sessions.size
    val title = displayName
        ?: "%.4f, %.4f".format(cluster.centroidLat, cluster.centroidLon)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Rank chip — same colour scheme as TopDebrisTypesCard so the two
            // cards feel like a single visual family.
            Surface(
                shape = CircleShape,
                color = when (rank) {
                    1 -> MaterialTheme.colorScheme.primary
                    2 -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.size(22.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "$rank",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when (rank) {
                            1 -> MaterialTheme.colorScheme.onPrimary
                            2 -> MaterialTheme.colorScheme.onSecondary
                            else -> MaterialTheme.colorScheme.onTertiary
                        },
                    )
                }
            }

            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.home_hotspots_row_meta,
                        totalDebris,
                        sessionCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyHotspotsPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.home_hotspots_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
