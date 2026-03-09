package com.oceanguard.ai.ui.screens.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.theme.HealthCritical
import com.oceanguard.ai.ui.theme.HealthExcellent
import com.oceanguard.ai.ui.theme.HealthFair
import com.oceanguard.ai.ui.theme.HealthGood
import com.oceanguard.ai.ui.theme.HealthPoor
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.NumberValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.util.ClickResult
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.maplibre.spatialk.geojson.Point

/**
 * MapLibre layers for zone-aggregated detection data.
 *
 * Each GeoJSON feature is a **zone** (1 km grouping), not an individual session.
 * No MapLibre clustering is used — zones are pre-aggregated by [ZoneAggregator].
 *
 * Two modes:
 * - **Density**: large translucent circles simulating a heatmap
 * - **Pins**: health-score colored circles sized by total debris count
 *
 * Visibility is toggled via opacity to avoid SourceReferenceEffect lifecycle
 * crashes in maplibre-compose 0.12.1.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun ZoneMapLayers(
    source: GeoJsonSource,
    showHeatmap: Boolean,
    onZoneClick: (zoneIndex: Int) -> Unit,
) {
    val totalCount = feature.get("totalCount") as Expression<NumberValue<Number>>

    val densityOpacity = if (showHeatmap) 1f else 0f
    val pinOpacity = if (showHeatmap) 0f else 1f

    // ── Density mode (circle-based heatmap substitute) ─────────────────

    // Outer glow ring — large, very translucent
    CircleLayer(
        id = "density-glow",
        source = source,
        color = healthScoreStepExpression(),
        radius = interpolate(
            type = linear(),
            input = zoom(),
            3 to const(20.dp),
            10 to const(35.dp),
            14 to const(50.dp),
        ),
        opacity = const(0.12f * densityOpacity),
        blur = const(1f),
    )

    // Inner density dot
    CircleLayer(
        id = "density-core",
        source = source,
        color = healthScoreStepExpression(),
        radius = interpolate(
            type = linear(),
            input = zoom(),
            3 to const(8.dp),
            10 to const(14.dp),
            14 to const(22.dp),
        ),
        opacity = const(0.35f * densityOpacity),
    )

    // ── Pin mode ───────────────────────────────────────────────────────

    // Zone markers — sized by total debris count, colored by health score
    CircleLayer(
        id = "zone-points",
        source = source,
        color = healthScoreStepExpression(),
        radius = step(
            input = totalCount,
            fallback = const(10.dp),
            5 to const(14.dp),
            15 to const(18.dp),
            30 to const(22.dp),
        ),
        strokeWidth = const(2.dp),
        strokeColor = const(Color.White.copy(alpha = 0.8f)),
        opacity = const(pinOpacity),
        onClick = { features ->
            if (showHeatmap) return@CircleLayer ClickResult.Pass
            val zoneIdx = features.firstOrNull()
                ?.properties
                ?.get("zoneIndex")
                ?.jsonPrimitive
                ?.intOrNull
            if (zoneIdx != null) {
                onZoneClick(zoneIdx)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        },
    )
}

/**
 * Step expression mapping healthScore property to the app's health color palette.
 */
@Suppress("UNCHECKED_CAST")
@Composable
private fun healthScoreStepExpression(): Expression<ColorValue> = step(
    input = feature.get("healthScore") as Expression<NumberValue<Number>>,
    fallback = const(HealthCritical),
    20 to const(HealthPoor),
    40 to const(HealthFair),
    60 to const(HealthGood),
    80 to const(HealthExcellent),
)
