package com.oceanguard.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.RiskLevel
import com.oceanguard.ai.data.VideoAnalysis
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.data.SessionDatesHelper
import com.oceanguard.ai.ui.components.DataDotDatePickerDialog
import com.oceanguard.ai.ui.components.EditDateDialog
import com.oceanguard.ai.ui.components.EditLocationDialog
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.components.PlaceNameText
import com.oceanguard.ai.ui.components.SelectionTopBar
import com.oceanguard.ai.ui.components.ShimmerLoadingScreen
import com.oceanguard.ai.utils.PhotonGeocoderClient
import com.oceanguard.ai.utils.toPlaceLabel
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.theme.healthScoreColor
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * HistoryScreen — chronological list of all past detection sessions.
 *
 * Each session card shows:
 *   - Thumbnail loaded via Coil from the stored image URI
 *   - Formatted timestamp (date and time, locale-aware)
 *   - Total debris count and ecosystem health score with colour coding
 *   - Risk level badge (LOW / MODERATE / HIGH / CRITICAL)
 *   - Location indicator when GPS data is available
 *
 * A shimmer skeleton is shown on the initial load while data is being fetched.
 * A Lottie empty state is shown when no sessions have been recorded yet.
 *
 * The list is backed by a Room Flow so new sessions appear automatically
 * without requiring a manual refresh.
 *
 * Long-pressing a card enters multi-selection mode. In selection mode the
 * top bar switches to a contextual SelectionTopBar that exposes bulk
 * edit-date, edit-location, and delete actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: MainViewModel,
    onNavigateToLocationPicker: (Long) -> Unit = { id ->
        navController.navigate("location_picker/$id")
    },
) {
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val videoAnalyses by viewModel.allVideoAnalyses.collectAsStateWithLifecycle(initialValue = emptyList())

    // Filter: Images vs Videos
    var showVideos by remember { mutableStateOf(false) }

    // Selection state
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }
    var showEditDateDialog by remember { mutableStateOf(false) }
    var showEditLocationDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Incremented after bulk edit to clear active filters and rebuild location cache
    var filterResetKey by remember { mutableStateOf(0) }

    // Track whether we are still waiting for the first emission from the Flow.
    var isFirstLoad by remember { mutableStateOf(true) }
    LaunchedEffect(sessions) {
        if (sessions.isNotEmpty()) isFirstLoad = false
    }

    val histContext = LocalContext.current
    val app = remember(histContext) { histContext.applicationContext as OceanGuardApp }
    val scope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.HISTORY)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.HISTORY))
        .collectAsStateWithLifecycle(initialValue = true)

    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    // Pressing back in selection mode clears the selection instead of navigating away.
    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
    }

    // ---------------------------------------------------------------------------
    // Bulk-edit dialogs — rendered outside Scaffold so they float above everything.
    // ---------------------------------------------------------------------------

    if (showEditDateDialog) {
        EditDateDialog(
            selectedCount = selectedIds.size,
            onConfirm = { dateMillis ->
                viewModel.updateSessionsTimestamp(selectedIds.toList(), Date(dateMillis))
                selectedIds = emptySet()
                showEditDateDialog = false
                filterResetKey++  // clear stale filters after bulk date edit
            },
            onDismiss = { showEditDateDialog = false },
        )
    }

    if (showEditLocationDialog) {
        EditLocationDialog(
            selectedCount = selectedIds.size,
            geocoder = (histContext.applicationContext as OceanGuardApp).geocoder,
            onConfirm = { location ->
                viewModel.updateSessionsLocation(selectedIds.toList(), location)
                selectedIds = emptySet()
                showEditLocationDialog = false
                filterResetKey++  // clear stale filters & rebuild location cache
            },
            onDismiss = { showEditLocationDialog = false },
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.bulk_delete_title)) },
            text = { Text(stringResource(R.string.bulk_delete_message, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSessions(selectedIds.toList())
                    selectedIds = emptySet()
                    showDeleteConfirmDialog = false
                }) {
                    Text(
                        stringResource(R.string.bulk_delete_title),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = isSelectionMode,
                label = "topbar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(
                        selectedCount = selectedIds.size,
                        onClose = { selectedIds = emptySet() },
                        onEditDate = { showEditDateDialog = true },
                        onEditLocation = { showEditLocationDialog = true },
                        onDelete = { showDeleteConfirmDialog = true },
                        onSelectAll = {
                            selectedIds = sessions.map { it.id }.toSet()
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.history_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_go_back),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->

        Column(modifier = Modifier.padding(paddingValues)) {
            // Filter chips: Images | Videos
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .spotlightTarget("history_media_filter", boundsMap),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showVideos,
                    onClick = { showVideos = false },
                    label = { Text("Images (${sessions.size})") },
                    leadingIcon = if (!showVideos) {
                        { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
                FilterChip(
                    selected = showVideos,
                    onClick = { showVideos = true },
                    label = { Text("Videos (${videoAnalyses.size})") },
                    leadingIcon = if (showVideos) {
                        { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }

            if (showVideos) {
                VideoAnalysisList(
                    videoAnalyses = videoAnalyses,
                    navController = navController,
                )
            } else {
                if (isFirstLoad && sessions.isEmpty()) {
                    ShimmerLoadingScreen(
                        modifier = Modifier,
                        itemCount = 5,
                    )
                } else {
                    SessionList(
                        sessions = sessions,
                        navController = navController,
                        viewModel = viewModel,
                        onNavigateToLocationPicker = onNavigateToLocationPicker,
                        boundsMap = boundsMap,
                        modifier = Modifier,
                        selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        onSelectionChanged = { selectedIds = it },
                        filterResetKey = filterResetKey,
                    )
                }
            }
        }
    }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                scope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.HISTORY)
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
                scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "history",
                onContinue = {
                    showTransitionDialog = false
                    val firstId = sessions.firstOrNull()?.id
                    if (firstId != null) {
                        navController.navigate("session/$firstId")
                    } else {
                        navController.navigate("settings") {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    } // end Box
}

// ---------------------------------------------------------------------------
// Session list
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionList(
    sessions: List<DetectionSession>,
    navController: NavController,
    viewModel: MainViewModel,
    onNavigateToLocationPicker: (Long) -> Unit,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect> = mutableMapOf(),
    modifier: Modifier,
    selectedIds: Set<Long> = emptySet(),
    isSelectionMode: Boolean = false,
    onSelectionChanged: (Set<Long>) -> Unit = {},
    filterResetKey: Int = 0,
) {
    var searchQuery by remember { mutableStateOf("") }

    // Advanced filters — keyed on filterResetKey so they clear after bulk edits
    var selectedDebrisType by remember(filterResetKey) { mutableStateOf<DebrisType?>(null) }
    var selectedPlaceName by remember(filterResetKey) { mutableStateOf<String?>(null) }
    var dateRangeStart by remember(filterResetKey) { mutableStateOf<Long?>(null) }
    var dateRangeEnd by remember(filterResetKey) { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datesWithData = remember(sessions) { SessionDatesHelper.datesWithData(sessions) }

    // Shared geocoder for reverse geocoding
    val ctx = LocalContext.current
    val geocoder = remember(ctx) { (ctx.applicationContext as OceanGuardApp).geocoder }

    // Reverse-geocoded place names for location filter dropdown.
    // locationKeys tracks "lat,lon" per session ID so we re-geocode when a
    // session's location is updated (bulk edit). Both maps are keyed on
    // filterResetKey so they rebuild fully after a bulk location edit.
    val placeNames = remember(filterResetKey) { mutableStateMapOf<Long, String>() }
    val locationKeys = remember(filterResetKey) { mutableStateMapOf<Long, String>() }
    LaunchedEffect(sessions, filterResetKey) {
        sessions.filter { it.location != null }.forEach { session ->
            val loc = session.location ?: return@forEach
            val locKey = "${loc.latitude},${loc.longitude}"
            if (locationKeys[session.id] == locKey) return@forEach
            locationKeys[session.id] = locKey
            val result = geocoder.reverse(loc.latitude, loc.longitude)
            placeNames[session.id] = result?.toPlaceLabel()
                ?: ctx.getString(R.string.zone_name_unknown)
        }
        // Remove stale entries for sessions that were deleted or lost their location
        val locatedIds = sessions.filter { it.location != null }.map { it.id }.toSet()
        placeNames.keys.removeAll { it !in locatedIds }
        locationKeys.keys.removeAll { it !in locatedIds }
    }

    // derivedStateOf avoids re-filtering on every recomposition:
    // - outer remember(sessions) invalidates when the list itself changes
    // - inner derivedStateOf tracks state reads (searchQuery, selectedDebrisType,
    //   selectedPlaceName, dateRangeStart, dateRangeEnd, placeNames) automatically
    val filteredSessions by remember(sessions) {
        derivedStateOf {
            sessions.filter { session ->
                val matchesText = searchQuery.isBlank() ||
                    session.debrisList.any { it.type.name.contains(searchQuery, ignoreCase = true) } ||
                    session.debrisList.any { it.material.name.contains(searchQuery, ignoreCase = true) } ||
                    session.getRiskLevel().name.contains(searchQuery, ignoreCase = true)

                val matchesType = selectedDebrisType == null ||
                    session.debrisList.any { it.type == selectedDebrisType }

                val matchesLocation = selectedPlaceName == null ||
                    placeNames[session.id] == selectedPlaceName

                val matchesDate = (dateRangeStart == null && dateRangeEnd == null) ||
                    (session.timestamp.time >= (dateRangeStart ?: 0L) &&
                     session.timestamp.time <= (dateRangeEnd ?: Long.MAX_VALUE))

                matchesText && matchesType && matchesLocation && matchesDate
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .spotlightTarget("history_search", boundsMap),
            placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear))
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
        )

        // Filter chips — only show debris types present in actual sessions
        if (sessions.isNotEmpty()) {
            val detectedTypes = remember(sessions) {
                sessions.flatMap { s -> s.debrisList.map { it.type } }.distinct().sorted()
            }
            FilterChipRow(
                selectedDebrisType = selectedDebrisType,
                onDebrisTypeSelected = { selectedDebrisType = it },
                availableDebrisTypes = detectedTypes,
                selectedPlaceName = selectedPlaceName,
                availablePlaceNames = placeNames.values.distinct().sorted(),
                onPlaceNameSelected = { selectedPlaceName = it },
                dateRangeStart = dateRangeStart,
                dateRangeEnd = dateRangeEnd,
                onDateRangeCleared = { dateRangeStart = null; dateRangeEnd = null },
                onDateRangeClick = { showDatePicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .spotlightTarget("history_filters", boundsMap),
            )
        }

        // Date range picker dialog
        if (showDatePicker) {
            DataDotDatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                initialStartDateMillis = dateRangeStart,
                initialEndDateMillis = dateRangeEnd,
                datesWithData = datesWithData,
                onConfirm = { start, end ->
                    dateRangeStart = start
                    dateRangeEnd = end
                    showDatePicker = false
                },
            )
        }

        if (sessions.isEmpty()) {
            LottieEmptyState(
                title = stringResource(R.string.history_empty_title),
                message = stringResource(R.string.history_empty_message),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = if (filteredSessions.size != 1) {
                            stringResource(R.string.history_sessions_count_plural, filteredSessions.size)
                        } else {
                            stringResource(R.string.history_sessions_count_singular, filteredSessions.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                items(
                    items = filteredSessions,
                    key = { session -> session.id },
                ) { session ->
                    val isFirst = filteredSessions.firstOrNull()?.id == session.id
                    val animatedModifier = if (isFirst) {
                        Modifier.animateItem().spotlightTarget("history_card", boundsMap)
                    } else {
                        Modifier.animateItem()
                    }
                    val isSelected = session.id in selectedIds

                    if (isSelectionMode) {
                        // Selection mode: tap to toggle, no swipe.
                        Box(modifier = animatedModifier) {
                            SessionCard(
                                session = session,
                                geocoder = geocoder,
                                onClick = {
                                    onSelectionChanged(
                                        if (isSelected) selectedIds - session.id
                                        else selectedIds + session.id,
                                    )
                                },
                            )
                            Icon(
                                imageVector = if (isSelected) Icons.Filled.CheckCircle
                                    else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .size(24.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    } else {
                        // Normal mode: swipe-to-dismiss + long-press to enter selection.
                        @Suppress("DEPRECATION")
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    viewModel.deleteSession(session)
                                    true
                                } else {
                                    false
                                }
                            },
                        )

                        SwipeToDismissBox(
                            modifier = animatedModifier,
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.cd_delete_session),
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            },
                            enableDismissFromStartToEnd = false,
                        ) {
                            SessionCard(
                                session = session,
                                geocoder = geocoder,
                                onClick = { navController.navigate("session/${session.id}") },
                                onLongClick = { onSelectionChanged(setOf(session.id)) },
                                onEditLocation = { onNavigateToLocationPicker(session.id) },
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Session card
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: DetectionSession,
    geocoder: PhotonGeocoderClient,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onEditLocation: () -> Unit = {},
) {
    val riskLevel = session.getRiskLevel()
    val scoreColor = healthScoreColor(session.healthScore)
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Health score accent bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(scoreColor),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ----------------------------------------------------------------
                // Thumbnail
                // ----------------------------------------------------------------
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    // Prefer annotated image (with bounding boxes) over original
                    val thumbnailSource = session.thumbnailUri ?: session.imageUri
                    if (thumbnailSource.isNotEmpty()) {
                        AsyncImage(
                            model = thumbnailSource,
                            contentDescription = stringResource(R.string.cd_session_thumbnail),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                // ----------------------------------------------------------------
                // Session info
                // ----------------------------------------------------------------
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Date / time row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = dateFormatter.format(session.timestamp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = timeFormatter.format(session.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Debris count
                    Text(
                        text = if (session.totalCount != 1) {
                            stringResource(R.string.history_debris_count_plural, session.totalCount)
                        } else {
                            stringResource(R.string.history_debris_count_singular, session.totalCount)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    // Health score + risk level badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Coloured health score
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(scoreColor, CircleShape),
                            )
                            Text(
                                text = stringResource(R.string.history_health_score, session.healthScore),
                                style = MaterialTheme.typography.bodySmall,
                                color = scoreColor,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // Risk level badge
                        RiskBadge(riskLevel = riskLevel)
                    }

                    // Image quality indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.history_image_quality, session.imageQuality.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = imageQualityColor(session.imageQuality),
                        )

                        // Location with place name
                        if (session.location != null) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = stringResource(R.string.cd_gps_location),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            PlaceNameText(
                                location = session.location,
                                geocoder = geocoder,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            IconButton(
                                onClick = onEditLocation,
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.cd_edit_location),
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            IconButton(
                                onClick = onEditLocation,
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = stringResource(R.string.cd_add_location),
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Risk level badge
// ---------------------------------------------------------------------------

@Composable
private fun RiskBadge(riskLevel: RiskLevel) {
    val (label, containerColor, contentColor) = when (riskLevel) {
        RiskLevel.LOW      -> Triple("LOW",      Color(0xFF1B5E20), Color(0xFFA5D6A7))
        RiskLevel.MODERATE -> Triple("MODERATE", Color(0xFF4A3A00), Color(0xFFFFCC02))
        RiskLevel.HIGH     -> Triple("HIGH",     Color(0xFF4A1000), Color(0xFFFFAB40))
        RiskLevel.CRITICAL -> Triple("CRITICAL", Color(0xFF4A0000), Color(0xFFEF9A9A))
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------------------------------------------------------------------------
// Local helpers
// ---------------------------------------------------------------------------

private fun imageQualityColor(quality: ImageQuality): Color = when (quality) {
    ImageQuality.GOOD -> Color(0xFF4CAF50)
    ImageQuality.FAIR -> Color(0xFFFFC107)
    ImageQuality.POOR -> Color(0xFFF44336)
}

// ---------------------------------------------------------------------------
// Filter chips
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    selectedDebrisType: DebrisType?,
    onDebrisTypeSelected: (DebrisType?) -> Unit,
    availableDebrisTypes: List<DebrisType>,
    selectedPlaceName: String?,
    availablePlaceNames: List<String>,
    onPlaceNameSelected: (String?) -> Unit,
    dateRangeStart: Long?,
    dateRangeEnd: Long?,
    onDateRangeCleared: () -> Unit,
    onDateRangeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Debris type chip
        var debrisExpanded by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = selectedDebrisType != null,
                onClick = {
                    if (selectedDebrisType != null) onDebrisTypeSelected(null)
                    else debrisExpanded = true
                },
                label = {
                    Text(
                        selectedDebrisType?.name?.lowercase()
                            ?.replaceFirstChar { it.uppercase() }
                            ?.replace("_", " ")
                            ?: stringResource(R.string.history_filter_debris_type),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = if (selectedDebrisType != null) {
                    { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
                } else null,
            )
            DropdownMenu(
                expanded = debrisExpanded,
                onDismissRequest = { debrisExpanded = false },
            ) {
                availableDebrisTypes.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                type.name.lowercase()
                                    .replaceFirstChar { it.uppercase() }
                                    .replace("_", " "),
                            )
                        },
                        onClick = {
                            onDebrisTypeSelected(type)
                            debrisExpanded = false
                        },
                    )
                }
            }
        }

        // Location chip
        if (availablePlaceNames.isNotEmpty()) {
            var locationExpanded by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = selectedPlaceName != null,
                    onClick = {
                        if (selectedPlaceName != null) onPlaceNameSelected(null)
                        else locationExpanded = true
                    },
                    label = {
                        Text(
                            selectedPlaceName
                                ?: stringResource(R.string.history_filter_location),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (selectedPlaceName != null) {
                        { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
                    } else null,
                )
                DropdownMenu(
                    expanded = locationExpanded,
                    onDismissRequest = { locationExpanded = false },
                ) {
                    availablePlaceNames.forEach { place ->
                        DropdownMenuItem(
                            text = { Text(place) },
                            onClick = {
                                onPlaceNameSelected(place)
                                locationExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Date range chip
        val hasDateRange = dateRangeStart != null || dateRangeEnd != null
        FilterChip(
            selected = hasDateRange,
            onClick = {
                if (hasDateRange) onDateRangeCleared()
                else onDateRangeClick()
            },
            label = {
                if (hasDateRange) {
                    val s = dateRangeStart?.let { dateFormatter.format(java.util.Date(it)) } ?: "…"
                    val e = dateRangeEnd?.let { dateFormatter.format(java.util.Date(it)) } ?: "…"
                    Text("$s – $e", maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(stringResource(R.string.history_filter_date_range), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            leadingIcon = if (hasDateRange) {
                { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
            } else null,
        )
    }
}

// ---------------------------------------------------------------------------
// Video analysis list
// ---------------------------------------------------------------------------

@Composable
private fun VideoAnalysisList(
    videoAnalyses: List<VideoAnalysis>,
    navController: NavController,
) {
    if (videoAnalyses.isEmpty()) {
        LottieEmptyState(
            title = stringResource(R.string.history_empty_title),
            message = stringResource(R.string.history_empty_message),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(videoAnalyses, key = { it.id }) { analysis ->
            VideoAnalysisCard(
                analysis = analysis,
                dateFormatter = dateFormatter,
                onClick = { navController.navigate("video_detail/${analysis.id}") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoAnalysisCard(
    analysis: VideoAnalysis,
    dateFormatter: SimpleDateFormat,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (analysis.thumbnailUri != null) {
                    AsyncImage(
                        model = analysis.thumbnailUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // Play overlay icon
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.White.copy(alpha = 0.9f),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateFormatter.format(analysis.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${analysis.uniqueDebrisCount} unique debris",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${analysis.durationMs / 1000}s video, ${analysis.processedFrameCount} frames",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Health score badge
            val riskLevel = analysis.getRiskLevel()
            val badgeColor = when (riskLevel) {
                RiskLevel.LOW -> Color(0xFF4CAF50)
                RiskLevel.MODERATE -> Color(0xFFFFC107)
                RiskLevel.HIGH -> Color(0xFFFF9800)
                RiskLevel.CRITICAL -> Color(0xFFF44336)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = badgeColor.copy(alpha = 0.15f),
            ) {
                Text(
                    text = "${analysis.healthScore}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                )
            }
        }
    }
}
