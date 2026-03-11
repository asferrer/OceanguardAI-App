package com.oceanguard.ai.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SessionDatesHelper
import com.oceanguard.ai.ui.components.DataDotDatePickerDialog
import com.oceanguard.ai.ReportGenerationState
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.inference.ZoneReportInput
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.screens.report.DateLanguageRow
import com.oceanguard.ai.ui.screens.report.ErrorContent
import com.oceanguard.ai.ui.screens.report.GenerateButton
import com.oceanguard.ai.ui.screens.report.GeneratingBanner
import com.oceanguard.ai.ui.screens.report.VlmDownloadBanner
import com.oceanguard.ai.ui.screens.report.IdleContent
import com.oceanguard.ai.ui.screens.report.ReportFullScreenView
import com.oceanguard.ai.ui.screens.report.ZonePreviewCard
import com.oceanguard.ai.ui.screens.report.ZoneSelector
import com.oceanguard.ai.ui.screens.report.shareMarkdownReport
import com.oceanguard.ai.ui.screens.report.sharePdfReport
import com.oceanguard.ai.utils.toPlaceLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Screen-level state sealed class
// ---------------------------------------------------------------------------

private sealed class ReportScreenState {
    data object Idle : ReportScreenState()
    data class ViewingReport(val report: GeneratedReport) : ReportScreenState()
    data class Error(val message: String) : ReportScreenState()
}

// ---------------------------------------------------------------------------
// Public screen composable
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    navController: NavController,
    viewModel: MainViewModel,
    preselectedLat: Double? = null,
    preselectedLon: Double? = null,
    preselectedName: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.REPORTS)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.REPORTS))
        .collectAsStateWithLifecycle(initialValue = true)
    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    val sessions by viewModel.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val savedReports by app.database.generatedReportDao().getAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val generationState by app.reportGenerationState.collectAsStateWithLifecycle()

    val persistedLanguage by viewModel.settingsRepository.language.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_LANGUAGE,
    )
    var selectedLanguage by rememberSaveable { mutableStateOf(persistedLanguage) }
    var screenState by remember { mutableStateOf<ReportScreenState>(ReportScreenState.Idle) }
    var reportToDelete by remember { mutableStateOf<GeneratedReport?>(null) }
    var newlyGeneratedReportId by remember { mutableStateOf<Long?>(null) }
    var isExportingPdf by remember { mutableStateOf(false) }
    // Cache report for exit animation (persists while overlay slides out)
    var cachedViewingReport by remember { mutableStateOf<GeneratedReport?>(null) }
    if (screenState is ReportScreenState.ViewingReport) {
        cachedViewingReport = (screenState as ReportScreenState.ViewingReport).report
    }
    var dateRangeStartMs by remember { mutableStateOf<Long?>(null) }
    var dateRangeEndMs by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datesWithData = remember(sessions) { SessionDatesHelper.datesWithData(sessions) }

    // VLM download state
    val vlmDownloadState by app.vlmModelManager.downloadState.collectAsStateWithLifecycle()
    var showDownloadDialog by remember { mutableStateOf(false) }
    val isDownloading = vlmDownloadState is VlmDownloadState.Downloading ||
        vlmDownloadState is VlmDownloadState.Preparing ||
        vlmDownloadState is VlmDownloadState.Installing

    // Background generation state (inline banner, not full-screen)
    val isGenerating = generationState is ReportGenerationState.Generating ||
        generationState is ReportGenerationState.LoadingModel
    val isLoadingModel = generationState is ReportGenerationState.LoadingModel

    // -----------------------------------------------------------------------
    // Zone clustering + geocoded names (parallel)
    // -----------------------------------------------------------------------
    val locatedSessions = remember(sessions) { sessions.filter { it.location != null } }
    val zoneClusters = remember(locatedSessions) { ZoneAggregator.cluster(locatedSessions) }

    val geocoder = remember { app.geocoder }
    var zoneNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var zoneNamesLoading by remember { mutableStateOf(false) }
    LaunchedEffect(zoneClusters) {
        if (zoneClusters.isEmpty()) {
            zoneNames = emptyList()
            return@LaunchedEffect
        }
        zoneNamesLoading = true
        val names = zoneClusters.mapIndexed { idx, zone ->
            async {
                val result = geocoder.reverse(zone.centroidLat, zone.centroidLon)
                result?.toPlaceLabel() ?: context.getString(R.string.report_zone_fallback, idx + 1)
            }
        }.awaitAll()
        zoneNames = names
        zoneNamesLoading = false
    }

    var selectedZoneIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    // Auto-select zone from nav params (map → report)
    LaunchedEffect(preselectedLat, preselectedLon, zoneClusters) {
        if (preselectedLat != null && preselectedLon != null &&
            zoneClusters.isNotEmpty() && selectedZoneIndex == null
        ) {
            val closest = zoneClusters.withIndex().minByOrNull { (_, zone) ->
                ZoneAggregator.haversineDistance(
                    preselectedLat, preselectedLon,
                    zone.centroidLat, zone.centroidLon,
                )
            }
            if (closest != null) {
                val dist = ZoneAggregator.haversineDistance(
                    preselectedLat, preselectedLon,
                    closest.value.centroidLat, closest.value.centroidLon,
                )
                if (dist < 1500.0) selectedZoneIndex = closest.index
            }
        }
    }

    // Override coordinate-only name with preselected name from map
    LaunchedEffect(preselectedName, selectedZoneIndex, zoneNames) {
        if (preselectedName != null && selectedZoneIndex != null &&
            selectedZoneIndex!! < zoneNames.size
        ) {
            val current = zoneNames[selectedZoneIndex!!]
            if (current.startsWith("Zone ") || current.matches(Regex("-?\\d+\\.\\d+, -?\\d+\\.\\d+"))) {
                zoneNames = zoneNames.toMutableList().apply {
                    set(selectedZoneIndex!!, preselectedName)
                }
            }
        }
    }

    // Selected zone data
    val selectedZone = selectedZoneIndex?.let { zoneClusters.getOrNull(it) }
    val dayGroups = remember(selectedZone) {
        selectedZone?.let { ZoneAggregator.groupByDay(it) } ?: emptyList()
    }
    val filteredZoneSessions = remember(selectedZone, dateRangeStartMs, dateRangeEndMs) {
        (selectedZone?.sessions ?: emptyList()).filter { session ->
            (dateRangeStartMs == null || session.timestamp.time >= dateRangeStartMs!!) &&
                (dateRangeEndMs == null || session.timestamp.time <= dateRangeEndMs!!)
        }
    }
    val filteredDayGroups = remember(dayGroups, dateRangeStartMs, dateRangeEndMs) {
        dayGroups.filter { day ->
            (dateRangeStartMs == null || day.date.time >= dateRangeStartMs!!) &&
                (dateRangeEndMs == null || day.date.time <= dateRangeEndMs!!)
        }
    }
    val selectedZoneName = selectedZoneIndex?.let { zoneNames.getOrNull(it) } ?: ""

    // Background generation: sync app state → snackbar + auto-view
    LaunchedEffect(generationState) {
        when (val state = generationState) {
            is ReportGenerationState.Idle -> {}
            is ReportGenerationState.LoadingModel,
            is ReportGenerationState.Generating -> {} // inline banner handles this
            is ReportGenerationState.Complete -> {
                val completedReport = state.report
                screenState = ReportScreenState.Idle
                newlyGeneratedReportId = completedReport.id
                app.reportGenerationState.value = ReportGenerationState.Idle
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.report_snackbar_generated),
                    actionLabel = context.getString(R.string.report_snackbar_view_action),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    screenState = ReportScreenState.ViewingReport(completedReport)
                }
            }
            is ReportGenerationState.Error -> {
                screenState = ReportScreenState.Error(state.message)
                app.reportGenerationState.value = ReportGenerationState.Idle
            }
        }
    }

    // Auto-launch generation after download completes
    LaunchedEffect(vlmDownloadState) {
        if (vlmDownloadState is VlmDownloadState.Complete && selectedZone != null) {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.vlm_download_complete_snackbar),
                duration = SnackbarDuration.Short,
            )
        }
    }

    // Helper lambda: build ZoneReportInput and launch generation
    val launchGeneration = {
        if (selectedZone != null) {
            if (!app.vlmModelManager.isModelAvailable()) {
                showDownloadDialog = true
            } else {
                app.launchZoneReportGeneration(
                    ZoneReportInput(
                        locationName = selectedZoneName,
                        centroidLat = selectedZone.centroidLat,
                        centroidLon = selectedZone.centroidLon,
                        sessions = filteredZoneSessions,
                        dayGroups = filteredDayGroups,
                        trend = selectedZone.trend,
                        dateRangeStartMs = dateRangeStartMs,
                        dateRangeEndMs = dateRangeEndMs,
                    ),
                    selectedLanguage,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dialogs
    // -----------------------------------------------------------------------
    reportToDelete?.let { report ->
        DeleteReportDialog(
            onConfirm = {
                scope.launch { app.database.generatedReportDao().delete(report) }
                reportToDelete = null
                if (screenState is ReportScreenState.ViewingReport &&
                    (screenState as ReportScreenState.ViewingReport).report.id == report.id
                ) screenState = ReportScreenState.Idle
            },
            onDismiss = { reportToDelete = null },
        )
    }

    // VLM download dialog
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text(stringResource(R.string.vlm_download_dialog_title)) },
            text = { Text(stringResource(R.string.vlm_download_dialog_message, app.vlmModelManager.getModelSizeLabel())) },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadDialog = false
                    app.launchVlmDownload()
                }) {
                    Text(stringResource(R.string.vlm_download_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showDatePicker) {
        DataDotDatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            initialStartDateMillis = dateRangeStartMs,
            initialEndDateMillis = dateRangeEndMs,
            datesWithData = datesWithData,
            onConfirm = { start, end ->
                dateRangeStartMs = start
                dateRangeEndMs = end
                showDatePicker = false
            },
        )
    }

    // -----------------------------------------------------------------------
    // Scaffold
    // -----------------------------------------------------------------------
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                ReportTopBar(
                    onBack = { navController.popBackStack() },
                    boundsMap = boundsMap,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // Zone selector
                if (zoneClusters.isNotEmpty() && zoneNames.size == zoneClusters.size) {
                    Box(modifier = Modifier.spotlightTarget("reports_zone", boundsMap)) {
                        ZoneSelector(
                            zoneNames = zoneNames,
                            selectedIndex = selectedZoneIndex,
                            onZoneSelected = { selectedZoneIndex = it },
                            enabled = !isGenerating,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (zoneClusters.isNotEmpty() && zoneNamesLoading) {
                    Box(modifier = Modifier.spotlightTarget("reports_zone", boundsMap)) {
                        ZoneSelector(
                            zoneNames = emptyList(),
                            selectedIndex = null,
                            onZoneSelected = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (sessions.isNotEmpty() && locatedSessions.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.report_no_zones_message),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                // Zone preview card
                if (selectedZone != null && selectedZoneIndex!! < zoneNames.size) {
                    Box(modifier = Modifier.spotlightTarget("reports_preview", boundsMap)) {
                        ZonePreviewCard(
                            zone = selectedZone,
                            zoneName = selectedZoneName,
                            dayCount = dayGroups.size,
                        )
                    }
                }

                // Date range + language row
                DateLanguageRow(
                    dateRangeStartMs = dateRangeStartMs,
                    dateRangeEndMs = dateRangeEndMs,
                    onDateRangeCleared = { dateRangeStartMs = null; dateRangeEndMs = null },
                    onDateRangeClick = { showDatePicker = true },
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { selectedLanguage = it },
                    enabled = !isGenerating,
                    boundsMap = boundsMap,
                )

                // Generate button
                GenerateButton(
                    isGenerating = isGenerating || isDownloading,
                    hasZoneSelected = selectedZone != null,
                    sessionCount = filteredZoneSessions.size,
                    onGenerate = launchGeneration,
                    boundsMap = boundsMap,
                )

                // Inline generating banner (background generation)
                AnimatedVisibility(
                    visible = isGenerating,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    GeneratingBanner(isLoadingModel = isLoadingModel)
                }

                // VLM download progress banner
                AnimatedVisibility(
                    visible = isDownloading,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    VlmDownloadBanner(
                        downloadState = vlmDownloadState,
                        onCancel = { app.vlmModelManager.cancelDownload() },
                    )
                }

                HorizontalDivider()

                // Content area (Idle + Error only; report viewing is a full-screen overlay)
                AnimatedContent(
                    targetState = screenState,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "report_content_transition",
                ) { state ->
                    when (state) {
                        is ReportScreenState.Idle,
                        is ReportScreenState.ViewingReport -> IdleContent(
                            hasSessions = sessions.isNotEmpty(),
                            savedReports = savedReports,
                            onViewReport = {
                                newlyGeneratedReportId = null
                                screenState = ReportScreenState.ViewingReport(it)
                            },
                            onDeleteReport = { reportToDelete = it },
                            highlightReportId = newlyGeneratedReportId,
                            onHighlightConsumed = { newlyGeneratedReportId = null },
                        )
                        is ReportScreenState.Error -> ErrorContent(
                            message = state.message,
                            onRetry = launchGeneration,
                        )
                    }
                }
            }
        }

        // Full-screen report overlay (slides up from bottom)
        AnimatedVisibility(
            visible = screenState is ReportScreenState.ViewingReport,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            cachedViewingReport?.let { report ->
                ReportFullScreenView(
                    report = report,
                    onClose = { screenState = ReportScreenState.Idle },
                    onShare = {
                        shareMarkdownReport(context, report.text, snackbarHostState, scope)
                    },
                    onDownloadPdf = {
                        isExportingPdf = true
                        scope.launch {
                            sharePdfReport(
                                context, report, filteredZoneSessions, snackbarHostState,
                            )
                            isExportingPdf = false
                        }
                    },
                    isExportingPdf = isExportingPdf,
                    sessions = filteredZoneSessions,
                )
            }
        }

        // Spotlight / guided tour
        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                scope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.REPORTS),
                    )
                    if (guidedTourActive) showTransitionDialog = true
                    else app.launchDemoCleanupIfComplete()
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
                screenId = "reports",
                onContinue = {
                    showTransitionDialog = false
                    navController.navigate("history") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Top app bar
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportTopBar(
    onBack: () -> Unit,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect>,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.report_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.report_cd_go_back),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

// ---------------------------------------------------------------------------
// Delete confirmation dialog
// ---------------------------------------------------------------------------

@Composable
private fun DeleteReportDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_dialog_delete_title)) },
        text = { Text(stringResource(R.string.report_dialog_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.report_btn_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
