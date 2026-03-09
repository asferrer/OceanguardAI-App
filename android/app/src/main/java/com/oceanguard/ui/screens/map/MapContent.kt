package com.oceanguard.ai.ui.screens.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.oceanguard.ai.data.ZoneCluster
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.seconds

/** Default camera: central Pacific Ocean. */
private val DEFAULT_POSITION = CameraPosition(
    target = Position(longitude = -160.0, latitude = 0.0),
    zoom = 3.0,
)

/**
 * Core map composable wrapping [MaplibreMap] with OceanGuard configuration.
 *
 * Displays zone-aggregated data: each point is a zone (1 km grouping) with
 * aggregate health score and total debris count. No MapLibre clustering is
 * used — zones are pre-aggregated by [com.oceanguard.ai.data.ZoneAggregator].
 *
 * @param geoJson GeoJSON FeatureCollection string of zone clusters.
 * @param styleUrl Full URL of the vector tile style to use.
 * @param showHeatmap Whether to show heatmap mode or pin mode.
 * @param zoneClusters Zone clusters for camera fit calculation.
 * @param fitAllTrigger Incremented to re-trigger camera fit animation.
 * @param onZoneClick Callback when a zone marker is tapped.
 */
@Composable
fun OceanGuardMap(
    geoJson: String,
    styleUrl: String,
    showHeatmap: Boolean,
    zoneClusters: List<ZoneCluster>,
    fitAllTrigger: Int,
    onZoneClick: (zoneIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraState = rememberCameraState(firstPosition = DEFAULT_POSITION)

    AnimateCameraToFit(
        cameraState = cameraState,
        zoneClusters = zoneClusters,
        fitAllTrigger = fitAllTrigger,
    )

    MaplibreMap(
        modifier = modifier,
        cameraState = cameraState,
        baseStyle = BaseStyle.Uri(styleUrl),
        options = MapOptions(
            ornamentOptions = OrnamentOptions(
                isLogoEnabled = true,
                isCompassEnabled = true,
                isScaleBarEnabled = true,
                isAttributionEnabled = false,
            ),
        ),
    ) {
        val stableData = remember(geoJson) { GeoJsonData.JsonString(geoJson) }
        val zonesSource = rememberGeoJsonSource(data = stableData)

        ZoneMapLayers(
            source = zonesSource,
            showHeatmap = showHeatmap,
            onZoneClick = onZoneClick,
        )
    }
}

/**
 * Animates the camera to fit all zone centroids with appropriate zoom.
 */
@Composable
private fun AnimateCameraToFit(
    cameraState: CameraState,
    zoneClusters: List<ZoneCluster>,
    fitAllTrigger: Int,
) {
    LaunchedEffect(zoneClusters, fitAllTrigger) {
        if (zoneClusters.isEmpty()) return@LaunchedEffect

        val lats = zoneClusters.map { it.centroidLat }
        val lngs = zoneClusters.map { it.centroidLon }

        val centerLat = lats.average()
        val centerLng = lngs.average()

        val zoom = if (zoneClusters.size == 1) {
            14.0
        } else {
            val latSpan = lats.max() - lats.min()
            val lngSpan = lngs.max() - lngs.min()
            val maxSpan = maxOf(latSpan, lngSpan)
            when {
                maxSpan < 0.01 -> 14.0
                maxSpan < 0.1 -> 12.0
                maxSpan < 1.0 -> 9.0
                maxSpan < 10.0 -> 6.0
                else -> 3.0
            }
        }

        cameraState.animateTo(
            finalPosition = CameraPosition(
                target = Position(longitude = centerLng, latitude = centerLat),
                zoom = zoom,
            ),
            duration = 1.seconds,
        )
    }
}
