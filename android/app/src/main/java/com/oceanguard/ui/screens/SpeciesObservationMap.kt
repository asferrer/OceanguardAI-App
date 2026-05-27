package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.screens.map.MapStyles
import com.oceanguard.ai.ui.theme.LocalIsDarkTheme
import com.oceanguard.ai.ui.theme.OceanGreen
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

/**
 * Embedded MapLibre map for the BioDex species Map tab.
 *
 * Mirrors [MarineDexTypeMap] exactly: same tile style (OpenFreeMap, dark/light
 * driven by [LocalIsDarkTheme]), same circle layer styling, same camera centering
 * heuristic.  Observations without GPS are silently skipped; if none carry a
 * location the appropriate empty-state is shown instead.
 *
 * @param observations All observations for the currently displayed species.
 * @param onObservationClick Invoked with the observation id when a map pin is tapped.
 */
@Composable
fun SpeciesObservationMap(
    observations: List<SpeciesObservation>,
    onObservationClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val located = remember(observations) { observations.filter { it.location != null } }

    if (located.isEmpty()) {
        SpeciesMapEmptyState(
            hasObservations = observations.isNotEmpty(),
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val isDark = LocalIsDarkTheme.current
    val styleUrl = remember(isDark) { MapStyles.forDarkMode(isDark) }
    val geoJson = remember(located) { located.toObservationGeoJson() }
    val centerPosition = remember(located) { computeObservationCenter(located) }
    val cameraState = rememberCameraState(firstPosition = centerPosition)

    Box(modifier = modifier) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cameraState,
            baseStyle = BaseStyle.Uri(styleUrl),
            options = MapOptions(
                ornamentOptions = OrnamentOptions(
                    isLogoEnabled = false,
                    isCompassEnabled = false,
                    isScaleBarEnabled = false,
                    isAttributionEnabled = false,
                ),
            ),
        ) {
            val stableData = remember(geoJson) { GeoJsonData.JsonString(geoJson) }
            val stableOptions = remember { GeoJsonOptions(cluster = false) }
            val source = rememberGeoJsonSource(data = stableData, options = stableOptions)

            SpeciesObservationCircleLayer(
                source = source,
                onObservationClick = onObservationClick,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Circle layer
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesObservationCircleLayer(
    source: org.maplibre.compose.sources.GeoJsonSource,
    onObservationClick: (Long) -> Unit,
) {
    CircleLayer(
        id = "species-obs-points",
        source = source,
        color = const(OceanGreen),
        radius = interpolate(
            type = linear(),
            input = zoom(),
            3 to const(6.dp),
            10 to const(10.dp),
            14 to const(14.dp),
        ),
        strokeWidth = const(2.dp),
        strokeColor = const(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)),
        onClick = { features ->
            val obsId = features.firstOrNull()
                ?.properties
                ?.get("observationId")
                ?.let {
                    it.toString().toLongOrNull()
                        ?: it.toString().trim('"').toLongOrNull()
                }
            if (obsId != null) {
                onObservationClick(obsId)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Camera center heuristic (mirrors computeCenter in MarineDexTypeMap)
// ---------------------------------------------------------------------------

private fun computeObservationCenter(observations: List<SpeciesObservation>): CameraPosition {
    val lats = observations.mapNotNull { it.location?.latitude }
    val lngs = observations.mapNotNull { it.location?.longitude }
    val centerLat = lats.average()
    val centerLng = lngs.average()
    val zoom = if (observations.size == 1) {
        14.0
    } else {
        val span = maxOf(lats.max() - lats.min(), lngs.max() - lngs.min())
        when {
            span < 0.01 -> 14.0
            span < 0.1 -> 11.0
            span < 1.0 -> 8.0
            else -> 5.0
        }
    }
    return CameraPosition(target = Position(longitude = centerLng, latitude = centerLat), zoom = zoom)
}

// ---------------------------------------------------------------------------
// GeoJSON serialiser for SpeciesObservation
// ---------------------------------------------------------------------------

private fun List<SpeciesObservation>.toObservationGeoJson(): String {
    val features = JSONArray()
    for (obs in this) {
        val loc = obs.location ?: continue
        val geometry = JSONObject().apply {
            put("type", "Point")
            put("coordinates", JSONArray().apply {
                put(loc.longitude)
                put(loc.latitude)
            })
        }
        val properties = JSONObject().apply {
            put("observationId", obs.id)
            put("confidence", obs.confidence)
            put("outOfRange", obs.outOfRange)
        }
        features.put(
            JSONObject().apply {
                put("type", "Feature")
                put("geometry", geometry)
                put("properties", properties)
            }
        )
    }
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesMapEmptyState(hasObservations: Boolean, modifier: Modifier = Modifier) {
    val message = if (hasObservations) {
        stringResource(R.string.biodex_map_empty_no_location)
    } else {
        stringResource(R.string.biodex_map_empty_no_obs)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        LottieEmptyState(
            title = stringResource(R.string.biodex_detail_tab_map),
            message = message,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
