package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SessionDatesHelper
import com.oceanguard.ai.ui.components.DataDotDatePickerDialog
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.ui.screens.map.MapFabControls
import com.oceanguard.ai.ui.screens.map.MapFilterBar
import com.oceanguard.ai.ui.screens.map.MapFilterState
import com.oceanguard.ai.ui.screens.map.MapStyles
import com.oceanguard.ai.ui.screens.map.OceanGuardMap
import com.oceanguard.ai.ui.screens.map.ZoneMapBottomSheet
import com.oceanguard.ai.ui.theme.healthScoreColor
import com.oceanguard.ai.utils.toZoneGeoJson
import kotlinx.coroutines.launch

/** Available map styles for cycling via the Layers FAB. */
private val MAP_STYLE_CYCLE = listOf(
    MapStyles.POSITRON,
    MapStyles.DARK,
    MapStyles.LIBERTY,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    // Collect live data
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val darkMode by viewModel.settingsRepository.darkMode.collectAsStateWithLifecycle(initialValue = true)

    val mapContext = LocalContext.current
    val app = remember(mapContext) { mapContext.applicationContext as OceanGuardApp }
    val mapScope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.MAP)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.MAP))
        .collectAsStateWithLifecycle(initialValue = true)

    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    // Sessions with GPS coordinates
    val locatedSessions = remember(sessions) {
        sessions.filter { it.location != null }
    }

    // Map filters
    var mapFilterState by remember { mutableStateOf(MapFilterState()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datesWithData = remember(sessions) { SessionDatesHelper.datesWithData(sessions) }
    val detectedTypes = remember(locatedSessions) {
        locatedSessions.flatMap { s -> s.debrisList.map { it.type } }.distinct().sorted()
    }

    // Apply filters → cluster → GeoJSON
    val filteredSessions = remember(locatedSessions, mapFilterState) {
        mapFilterState.applyTo(locatedSessions)
    }
    val zoneClusters = remember(filteredSessions) {
        ZoneAggregator.cluster(filteredSessions)
    }
    val geoJson = remember(zoneClusters) {
        zoneClusters.toZoneGeoJson()
    }

    // Map state
    var showHeatmap by remember { mutableStateOf(false) }
    var selectedZone by remember { mutableStateOf<ZoneCluster?>(null) }
    var fitAllTrigger by remember { mutableIntStateOf(0) }

    // Style: starts from dark mode preference, FAB cycles through all styles
    val defaultStyle = MapStyles.forDarkMode(darkMode)
    var styleUrl by remember(darkMode) { mutableStateOf(defaultStyle) }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.map_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (locatedSessions.isNotEmpty()) {
                            val zoneCount = zoneClusters.size
                            val subtitle = buildString {
                                append("${locatedSessions.size} session${if (locatedSessions.size != 1) "s" else ""}")
                                if (zoneCount > 0) append(" · $zoneCount zone${if (zoneCount != 1) "s" else ""}")
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.map_cd_go_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Filter bar (only when there are located sessions)
            if (locatedSessions.isNotEmpty()) {
                MapFilterBar(
                    filterState = mapFilterState,
                    onFilterChanged = { mapFilterState = it },
                    availableDebrisTypes = detectedTypes,
                    onDateRangeClick = { showDatePicker = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (locatedSessions.isEmpty()) {
                    EmptyMapContent(
                        modifier = Modifier.align(Alignment.Center),
                        hasAnySessions = sessions.isNotEmpty(),
                    )
                } else {
                    OceanGuardMap(
                        geoJson = geoJson,
                        styleUrl = styleUrl,
                        showHeatmap = showHeatmap,
                        zoneClusters = zoneClusters,
                        fitAllTrigger = fitAllTrigger,
                        onZoneClick = { zoneIndex ->
                            selectedZone = zoneClusters.getOrNull(zoneIndex)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .spotlightTarget("map_view", boundsMap),
                    )

                    MapFabControls(
                        showHeatmap = showHeatmap,
                        onToggleHeatmap = { showHeatmap = !showHeatmap },
                        onFitAll = { fitAllTrigger++ },
                        onToggleStyle = {
                            val currentIndex = MAP_STYLE_CYCLE.indexOf(styleUrl)
                            val nextIndex = (currentIndex + 1) % MAP_STYLE_CYCLE.size
                            styleUrl = MAP_STYLE_CYCLE[nextIndex]
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        boundsMap = boundsMap,
                    )

                    if (zoneClusters.isNotEmpty() && !tourController.isActive) {
                        ZoneHealthSummary(
                            clusterCount = zoneClusters.size,
                            avgScore = zoneClusters.map { it.aggregateHealthScore }.average().toInt(),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp),
                        )
                    }

                    if (!tourController.isActive) {
                        MapLegend(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        // Date range picker dialog
        if (showDatePicker) {
            DataDotDatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                initialStartDateMillis = mapFilterState.dateRangeStartMs,
                initialEndDateMillis = mapFilterState.dateRangeEndMs,
                datesWithData = datesWithData,
                onConfirm = { start, end ->
                    mapFilterState = mapFilterState.copy(
                        dateRangeStartMs = start,
                        dateRangeEndMs = end,
                    )
                    showDatePicker = false
                },
            )
        }

        // Bottom sheet for selected zone cluster
        selectedZone?.let { zone ->
            ZoneMapBottomSheet(
                zone = zone,
                onDismiss = { selectedZone = null },
                onSessionClick = { sessionId ->
                    selectedZone = null
                    navController.navigate("session/$sessionId")
                },
                onGenerateReport = { lat, lon, name ->
                    selectedZone = null
                    val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
                    navController.navigate("reports?lat=$lat&lon=$lon&name=$encodedName")
                },
            )
        }
    }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                mapScope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.MAP)
                    )
                    if (guidedTourActive) {
                        showTransitionDialog = true
                    } else {
                        app.launchDemoCleanupIfComplete()
                    }
                }
            },
            isGuidedTour = guidedTourActive,
            onSkipTutorial = {
                mapScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "map",
                onContinue = {
                    showTransitionDialog = false
                    navController.navigate("reports") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    mapScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    } // end Box
}

/**
 * Compact overlay card showing aggregate zone health for all clusters.
 * Positioned at the top-start of the map so it does not overlap FAB controls.
 */
@Composable
private fun ZoneHealthSummary(
    clusterCount: Int,
    avgScore: Int,
    modifier: Modifier = Modifier,
) {
    val scoreColor = healthScoreColor(avgScore)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(scoreColor, CircleShape),
            )
            Column {
                Text(
                    text = if (clusterCount != 1) stringResource(R.string.map_zone_count_plural, clusterCount)
                           else stringResource(R.string.map_zone_count_singular, clusterCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.map_zone_avg_health, avgScore),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scoreColor,
                )
            }
        }
    }
}

/**
 * Collapsible legend showing health score color scale.
 */
@Composable
private fun MapLegend(
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) {
                        stringResource(R.string.map_legend_cd_collapse)
                    } else {
                        stringResource(R.string.map_legend_cd_expand)
                    },
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.map_legend_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                LegendRow(color = Color(0xFF4CAF50), label = stringResource(R.string.map_legend_excellent))
                LegendRow(color = Color(0xFF8BC34A), label = stringResource(R.string.map_legend_good))
                LegendRow(color = Color(0xFFFFC107), label = stringResource(R.string.map_legend_moderate))
                LegendRow(color = Color(0xFFFF9800), label = stringResource(R.string.map_legend_poor))
                LegendRow(color = Color(0xFFF44336), label = stringResource(R.string.map_legend_critical))
            }
        }
    }
}

@Composable
private fun LegendRow(
    color: Color,
    label: String,
) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color = color, shape = CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Empty state shown when there are no sessions with GPS coordinates.
 */
@Composable
private fun EmptyMapContent(
    modifier: Modifier = Modifier,
    hasAnySessions: Boolean,
) {
    val title = if (hasAnySessions) {
        stringResource(R.string.map_empty_no_location_title)
    } else {
        stringResource(R.string.map_empty_no_sessions_title)
    }
    val message = if (hasAnySessions) {
        stringResource(R.string.map_empty_no_location_message)
    } else {
        stringResource(R.string.map_empty_no_sessions_message)
    }

    LottieEmptyState(
        title = title,
        message = message,
        modifier = modifier.padding(horizontal = 32.dp),
    )
}
