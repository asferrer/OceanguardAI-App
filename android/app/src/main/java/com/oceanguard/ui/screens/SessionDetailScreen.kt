package com.oceanguard.ai.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.EnvironmentalImpact
import com.oceanguard.ai.data.HealthScoreFactors
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.BoundingBoxOverlay
import com.oceanguard.ai.ui.components.PlaceNameText
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.HealthScoreGauge
import com.oceanguard.ai.ui.components.ShimmerLoadingScreen
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.theme.*
import com.oceanguard.ai.utils.ImageGallerySaver
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Detail screen for a past detection session loaded from Room.
 * Shows the captured image with bounding box overlay, health score,
 * material breakdown, and individual debris items with environmental impact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    navController: NavController,
    viewModel: MainViewModel,
    sessionId: Long,
    onNavigateToLocationPicker: (Long) -> Unit = {},
) {
    var session by remember { mutableStateOf<DetectionSession?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val detailContext = LocalContext.current
    val app = remember(detailContext) { detailContext.applicationContext as OceanGuardApp }
    val detailScope = rememberCoroutineScope()
    val detailListState = rememberLazyListState()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.SESSION_DETAIL)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.SESSION_DETAIL))
        .collectAsStateWithLifecycle(initialValue = true)

    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    LaunchedEffect(sessionId) {
        session = viewModel.getSession(sessionId)
        isLoading = false
    }

    // Date picker dialog
    if (showDatePicker && session != null) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = session!!.timestamp.time,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis
                        if (millis != null) {
                            val tzOffset = java.util.TimeZone.getDefault().getOffset(millis).toLong()
                            val updated = session!!.copy(
                                timestamp = java.util.Date(millis + tzOffset),
                            )
                            detailScope.launch {
                                app.repository.updateSession(updated)
                                session = updated
                            }
                        }
                        showDatePicker = false
                    },
                ) { Text(stringResource(R.string.location_picker_btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog && session != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.session_detail_dialog_delete_title)) },
            text = { Text(stringResource(R.string.session_detail_dialog_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSession(session!!)
                        showDeleteDialog = false
                        navController.popBackStack()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.session_detail_btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.session_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.session_detail_cd_back))
                    }
                },
                actions = {
                    if (session != null) {
                        IconButton(onClick = { onNavigateToLocationPicker(sessionId) }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.session_detail_cd_edit_location),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.session_detail_cd_delete_session),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        when {
            isLoading -> {
                ShimmerLoadingScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                )
            }
            session == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.session_detail_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                SessionDetailContent(
                    modifier = Modifier.padding(paddingValues),
                    session = session!!,
                    onEditLocation = { onNavigateToLocationPicker(sessionId) },
                    onEditDate = { showDatePicker = true },
                    boundsMap = boundsMap,
                    listState = detailListState,
                )
            }
        }
    }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                detailScope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.SESSION_DETAIL)
                    )
                    if (guidedTourActive) {
                        showTransitionDialog = true
                    } else {
                        app.launchDemoCleanupIfComplete()
                    }
                }
            },
            onScrollToTarget = { targetId ->
                val index = when (targetId) {
                    "detail_image" -> 0
                    "detail_location" -> 1
                    "detail_health" -> 2
                    else -> null
                }
                if (index != null) detailListState.animateScrollToItem(index)
            },
            isGuidedTour = guidedTourActive,
            onSkipTutorial = {
                detailScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "session_detail",
                onContinue = {
                    showTransitionDialog = false
                    navController.navigate("settings") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    detailScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    } // end Box
}

@Composable
private fun SessionDetailContent(
    modifier: Modifier,
    session: DetectionSession,
    onEditLocation: () -> Unit = {},
    onEditDate: () -> Unit = {},
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect> = mutableMapOf(),
    listState: LazyListState = rememberLazyListState(),
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val materialBreakdown = session.debrisList
        .groupBy { it.material }
        .mapValues { it.value.size }

    // Use pre-rendered annotated image if available, otherwise fall back
    // to live BoundingBoxOverlay with pseudo-detections from stored Debris.
    val hasAnnotatedImage = session.thumbnailUri != null
    val pseudoDetections = remember(session) {
        if (hasAnnotatedImage) emptyList()
        else session.debrisList.map { debris ->
            DetectionResult(
                x1 = debris.bbox.x,
                y1 = debris.bbox.y,
                x2 = debris.bbox.x + debris.bbox.width,
                y2 = debris.bbox.y + debris.bbox.height,
                classId = debris.type.ordinal,
                className = debris.type.name.lowercase(),
                confidence = debris.confidence,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Image with bounding boxes + save-to-gallery button
        item {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val app = remember(context) { context.applicationContext as OceanGuardApp }
            val uploadStatus by app.contributionRepository
                .getStatusFlowForUri(session.imageUri)
                .collectAsStateWithLifecycle(initialValue = null)
            val imageToSave = session.thumbnailUri ?: session.imageUri

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .spotlightTarget("detail_image", boundsMap),
            ) {
                if (hasAnnotatedImage) {
                    // Show pre-rendered annotated image — fills width, height adapts
                    AsyncImage(
                        model = session.thumbnailUri,
                        contentDescription = stringResource(R.string.session_detail_cd_annotated_image),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    // Fallback: render overlay live from stored debris data
                    BoundingBoxOverlay(
                        imageUri = session.imageUri,
                        detections = pseudoDetections,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                    )
                }

                // Save to gallery button
                if (imageToSave.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val saved = ImageGallerySaver.saveToGallery(
                                    context = context,
                                    sourceUri = imageToSave,
                                )
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val msg = if (saved != null) {
                                        context.getString(R.string.save_to_gallery_success)
                                    } else {
                                        context.getString(R.string.save_to_gallery_error)
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SaveAlt,
                            contentDescription = stringResource(R.string.save_to_gallery_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // Upload to research button
                FilledTonalIconButton(
                    enabled = uploadStatus == null || uploadStatus == "FAILED",
                    onClick = {
                        if (uploadStatus == null || uploadStatus == "FAILED") {
                            scope.launch { app.contributionRepository.enqueueSession(session) }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    ),
                ) {
                    when (uploadStatus) {
                        "PENDING" -> Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = stringResource(R.string.contribute_status_pending),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        "DONE" -> Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.contribute_status_done),
                            tint = OceanGreen,
                        )
                        "FAILED" -> Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.contribute_status_failed),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = stringResource(R.string.contribute_upload_btn),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Date and location info
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .spotlightTarget("detail_location", boundsMap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = dateFormat.format(session.timestamp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onEditDate, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.session_detail_cd_edit_date),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                SessionLocationRow(
                    location = session.location,
                    onEditLocation = onEditLocation,
                )
            }
        }

        // Health score with factor breakdown
        item {
            val factors = ZoneAggregator.computeFactors(session.debrisList)
            Box(modifier = Modifier.spotlightTarget("detail_health", boundsMap)) {
                SessionHealthScoreCard(score = session.healthScore, factors = factors)
            }
        }

        // Summary stats
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SessionStatChip(
                    label = stringResource(R.string.session_detail_label_total_debris),
                    value = session.totalCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                SessionStatChip(
                    label = stringResource(R.string.session_detail_label_processing),
                    value = "${session.processingTimeMs}ms",
                    modifier = Modifier.weight(1f),
                )
                SessionStatChip(
                    label = stringResource(R.string.session_detail_label_quality),
                    value = session.imageQuality.name,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Material breakdown chips
        if (materialBreakdown.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.session_detail_section_material_breakdown),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(materialBreakdown.entries.toList()) { (material, count) ->
                        val chipColor = materialColor(material)
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = chipColor.copy(alpha = 0.18f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(chipColor, CircleShape),
                                )
                                Text(
                                    text = "${material.name.replace("_", " ")} ($count)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = chipColor,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Debris list with environmental impact
        if (session.debrisList.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.session_detail_section_detected_objects),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            items(session.debrisList) { debris ->
                SessionDebrisItem(debris = debris)
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun SessionHealthScoreCard(score: Int, factors: HealthScoreFactors) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            HealthScoreGauge(
                score = score,
                size = 150.dp,
                factors = factors,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SessionLocationRow(
    location: com.oceanguard.ai.data.Location?,
    onEditLocation: () -> Unit,
) {
    val ctx = LocalContext.current
    val geocoder = remember(ctx) { (ctx.applicationContext as OceanGuardApp).geocoder }

    if (location != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.LocationOn, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            PlaceNameText(
                location = location,
                geocoder = geocoder,
                style = MaterialTheme.typography.labelSmall,
            )
            IconButton(onClick = onEditLocation, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Edit, stringResource(R.string.session_detail_cd_edit_location_inline), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    } else {
        TextButton(onClick = onEditLocation) {
            Icon(Icons.Filled.LocationOn, null, Modifier.size(16.dp))
            Text(stringResource(R.string.session_detail_location_add), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun SessionStatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SessionDebrisItem(debris: Debris) {
    val impact = EnvironmentalImpact.getImpact(debris.type)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = materialColor(debris.material).copy(alpha = 0.15f),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = impact.icon, fontSize = 18.sp)
                    }
                    Column {
                        Text(
                            text = debris.type.name.replace("_", " "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = debris.material.name.replace("_", " "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "%.0f%%".format(debris.confidence * 100f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            ) {
                Text(
                    text = impact.degradationTime,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = impact.primaryRisk,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
