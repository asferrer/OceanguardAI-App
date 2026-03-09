package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.LocationSource
import com.oceanguard.ai.ui.screens.map.MapStyles
import com.oceanguard.ai.utils.GeocodingResult
import com.oceanguard.ai.utils.PhotonGeocoderClient
import com.oceanguard.ai.utils.toPlaceLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.seconds

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

/**
 * Screen that lets the user pick or manually type a location for a session.
 *
 * Accessible via route `location_picker/{sessionId}`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerScreen(
    sessionId: Long,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OceanGuardApp
    val scope = rememberCoroutineScope()
    val geocoder = remember { PhotonGeocoderClient() }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var selectedLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    // Load existing location for the session on entry
    LaunchedEffect(sessionId) {
        val session = app.repository.getSession(sessionId)
        session?.location?.let { loc ->
            selectedLocation = Pair(loc.latitude, loc.longitude)
        }
    }

    // Debounced search — fires 300 ms after the user stops typing
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        isSearching = true
        searchResults = geocoder.search(searchQuery)
        isSearching = false
    }

    val onConfirm = {
        selectedLocation?.let { (lat, lon) ->
            scope.launch {
                val session = app.repository.getSession(sessionId) ?: return@launch
                val updated = session.copy(
                    location = Location(
                        latitude = lat,
                        longitude = lon,
                        accuracy = null,
                        source = LocationSource.MANUAL,
                    )
                )
                app.repository.updateSession(updated)
                onNavigateBack()
            }
        }
        Unit
    }

    Scaffold(
        topBar = {
            LocationPickerTopBar(
                onBack = onNavigateBack,
                onConfirm = onConfirm,
                canConfirm = selectedLocation != null,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LocationSearchBar(
                query = searchQuery,
                results = searchResults,
                isSearching = isSearching,
                onQueryChange = { searchQuery = it },
                onResultSelected = { result ->
                    selectedLocation = Pair(result.latitude, result.longitude)
                    searchQuery = result.toPlaceLabel()
                    searchResults = emptyList()
                },
            )

            Box(modifier = Modifier.weight(1f)) {
                LocationMap(
                    selectedLocation = selectedLocation,
                    onMapTap = { lat, lon -> selectedLocation = Pair(lat, lon) },
                )
            }

            LocationInfoBar(
                selectedLocation = selectedLocation,
                onConfirm = onConfirm,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// TopAppBar
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPickerTopBar(
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    canConfirm: Boolean,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.location_picker_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.location_picker_cd_back))
            }
        },
        actions = {
            IconButton(onClick = onConfirm, enabled = canConfirm) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.location_picker_cd_confirm),
                    tint = if (canConfirm) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

// ---------------------------------------------------------------------------
// Search bar sub-composable
// ---------------------------------------------------------------------------

/**
 * Search input field with a dropdown results list beneath it.
 */
@Composable
fun LocationSearchBar(
    query: String,
    results: List<GeocodingResult>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onResultSelected: (GeocodingResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.location_picker_search_placeholder)) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.location_picker_cd_search))
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
        )

        if (results.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                LazyColumn {
                    items(results.take(5)) { result ->
                        GeocodingResultItem(
                            result = result,
                            onClick = { onResultSelected(result) },
                        )
                        if (result != results.take(5).last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeocodingResultItem(
    result: GeocodingResult,
    onClick: () -> Unit,
) {
    val subtitle = listOfNotNull(result.city, result.country).joinToString(", ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = result.toPlaceLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Map sub-composable
// ---------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
@Composable
private fun LocationMap(
    selectedLocation: Pair<Double, Double>?,
    onMapTap: (lat: Double, lon: Double) -> Unit,
) {
    val initialPosition = selectedLocation?.let { (lat, lon) ->
        CameraPosition(target = Position(longitude = lon, latitude = lat), zoom = 12.0)
    } ?: CameraPosition(target = Position(longitude = 0.0, latitude = 0.0), zoom = 2.0)

    val cameraState = rememberCameraState(firstPosition = initialPosition)

    // Animate to new location when selectedLocation changes externally (search result)
    LaunchedEffect(selectedLocation) {
        selectedLocation ?: return@LaunchedEffect
        val (lat, lon) = selectedLocation
        cameraState.animateTo(
            finalPosition = CameraPosition(
                target = Position(longitude = lon, latitude = lat),
                zoom = 12.0,
            ),
            duration = 0.8.seconds,
        )
    }

    val pinGeoJson = remember(selectedLocation) {
        if (selectedLocation == null) {
            """{"type":"FeatureCollection","features":[]}"""
        } else {
            val (lat, lon) = selectedLocation
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{}}]}"""
        }
    }

    MaplibreMap(
        modifier = Modifier.fillMaxSize(),
        cameraState = cameraState,
        baseStyle = BaseStyle.Uri(MapStyles.LIBERTY),
        options = MapOptions(
            ornamentOptions = OrnamentOptions(
                isLogoEnabled = false,
                isCompassEnabled = true,
                isScaleBarEnabled = false,
                isAttributionEnabled = false,
            ),
        ),
        onMapClick = { position, _ ->
            onMapTap(position.latitude, position.longitude)
            ClickResult.Consume
        },
    ) {
        if (selectedLocation != null) {
            val stableData = remember(pinGeoJson) { GeoJsonData.JsonString(pinGeoJson) }
            val pinSource = rememberGeoJsonSource(data = stableData)

            CircleLayer(
                id = "location-pin-outer",
                source = pinSource,
                color = const(androidx.compose.ui.graphics.Color(0x33006FFF)),
                radius = const(18.dp),
            )
            CircleLayer(
                id = "location-pin-inner",
                source = pinSource,
                color = const(androidx.compose.ui.graphics.Color(0xFF006FFF)),
                radius = const(8.dp),
                strokeWidth = const(2.dp),
                strokeColor = const(androidx.compose.ui.graphics.Color.White),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom info bar sub-composable
// ---------------------------------------------------------------------------

/**
 * Bottom bar showing the selected coordinates and a Confirm button.
 */
@Composable
fun LocationInfoBar(
    selectedLocation: Pair<Double, Double>?,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.location_picker_selected_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (selectedLocation != null) {
                        "Lat: ${"%.4f".format(selectedLocation.first)}, " +
                            "Lon: ${"%.4f".format(selectedLocation.second)}"
                    } else {
                        stringResource(R.string.location_picker_tap_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (selectedLocation != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            TextButton(
                onClick = onConfirm,
                enabled = selectedLocation != null,
            ) {
                Text(stringResource(R.string.location_picker_btn_confirm))
            }
        }
    }
}
