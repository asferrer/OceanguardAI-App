package com.oceanguard.ai.ui.screens

import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.EnvironmentalImpact
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.UiState
import com.oceanguard.ai.ui.components.BoundingBoxOverlay
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.HealthScoreGauge
import com.oceanguard.ai.ui.components.ShimmerLoadingScreen
import com.oceanguard.ai.ui.theme.*
import com.oceanguard.ai.ui.theme.healthScoreColor
import com.oceanguard.ai.ui.theme.materialColor
import com.oceanguard.ai.utils.BitmapAnnotator
import com.oceanguard.ai.utils.ImageGallerySaver
import androidx.compose.ui.platform.LocalContext
import com.oceanguard.ai.ui.components.OceanGradientButton
import com.oceanguard.ai.ui.components.OnGradientColor
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.HealthScoreFactors
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.MainViewModel.ContributePromptState
import com.oceanguard.ai.ui.components.ContributeBottomSheet
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

/**
 * ResultsScreen — displays the progressive output of the dual detection pipeline.
 *
 * State machine handled:
 *   Detecting       -> indeterminate spinner
 *   DetectionsReady -> image + bounding-box placeholder + "Analysing with AI..." banner
 *   AnalyzingDeep   -> same image + progress bar for VLM stage
 *   AnalysisComplete -> full result: image, health score, debris list, material chips, actions
 *   Error           -> inline error card with retry option
 *
 * The image URI is captured from the ViewModel state on first composition so
 * the thumbnail persists across the Detecting -> Complete transition.
 *
 * Bounding-box overlay rendering (BoundingBoxOverlay) is left as a
 * placeholder text comment; the composable will be created in a separate task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Observe the captured image URI from the ViewModel so the photo stays
    // visible while the pipeline progresses through its stages.
    val capturedImageUri by viewModel.capturedImageUri.collectAsStateWithLifecycle()

    // Track whether the session has been saved to avoid double-saves.
    var sessionSaved by remember { mutableStateOf(false) }

    // Snackbar for save confirmation feedback.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val resultsListState = rememberLazyListState()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.RESULTS)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.RESULTS))
        .collectAsStateWithLifecycle(initialValue = true)

    LaunchedEffect(tourComplete, uiState) {
        if (!tourComplete && uiState is UiState.AnalysisComplete) tourController.start()
    }

    // Mark session as saved when analysis completes.
    // InferenceService is the single source of truth for persistence — it saves
    // the session before emitting SingleComplete. No fallback save needed here.
    LaunchedEffect(uiState) {
        if (uiState is UiState.AnalysisComplete && !sessionSaved) {
            sessionSaved = true
        }
    }

    // Contribution upload completion snackbar
    val uploadResult by app.contributionUploadResult.collectAsStateWithLifecycle()
    LaunchedEffect(uploadResult) {
        uploadResult?.let { result ->
            val msg = when {
                result.hadFailures && result.uploadedCount > 0 ->
                    context.getString(R.string.contribute_upload_partial, result.uploadedCount)
                result.hadFailures ->
                    context.getString(R.string.contribute_upload_failed)
                result.uploadedCount > 0 ->
                    context.getString(R.string.contribute_upload_success, result.uploadedCount)
                else -> null
            }
            msg?.let { snackbarHostState.showSnackbar(it) }
            app.contributionUploadResult.value = null
        }
    }

    // Contribution prompt
    val contributePrompt by viewModel.contributePrompt.collectAsStateWithLifecycle()
    var showContributeSheet by remember { mutableStateOf(false) }
    LaunchedEffect(contributePrompt) {
        when (val prompt = contributePrompt) {
            is ContributePromptState.ShowPrompt -> showContributeSheet = true
            is ContributePromptState.ShowInfo -> {
                val msg = context.getString(R.string.contribute_queued_message, prompt.count)
                val result = snackbarHostState.showSnackbar(
                    message = msg,
                    actionLabel = context.getString(R.string.contribute_upload_now_btn),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    scope.launch { app.contributionRepository.scheduleImmediateUpload() }
                }
                viewModel.dismissContributePrompt()
            }
            else -> {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.results_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->

        when (val state = uiState) {

            // ----------------------------------------------------------------
            // Detecting: detector pass is running. Wording adapts to which
            // detector is active — RT-DETRv2 (~4 s) vs Gemma 4 Vision (~22 s).
            // ----------------------------------------------------------------
            is UiState.Detecting, is UiState.ModelLoading -> {
                val app = LocalContext.current.applicationContext as OceanGuardApp
                val detectorModeKey by app.settingsRepository.detectorMode
                    .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_DETECTOR_MODE)
                val isGemma4 = detectorModeKey == DetectorType.GEMMA4_VISION.key

                // Diagnostic — log once per state transition so we can confirm
                // from logcat that this composition path is reached.
                LaunchedEffect(state, detectorModeKey) {
                    android.util.Log.i(
                        "ResultsScreenUI",
                        "Detecting branch entered. state=${state::class.simpleName} " +
                            "detectorMode=$detectorModeKey isGemma4=$isGemma4",
                    )
                }

                val message = when {
                    state is UiState.ModelLoading -> state.progress
                    isGemma4 -> stringResource(R.string.results_status_detecting_gemma4)
                    else -> stringResource(R.string.results_status_detecting)
                }
                val subMessage = if (isGemma4) {
                    stringResource(R.string.results_status_gemma4_subtitle)
                } else {
                    stringResource(R.string.results_status_fast_detection)
                }

                // Tick every second so the user sees that the app is alive
                // during the long Gemma 4 pass.
                var elapsedSec by remember { mutableIntStateOf(0) }
                LaunchedEffect(state) {
                    elapsedSec = 0
                    while (true) {
                        delay(1_000)
                        elapsedSec += 1
                    }
                }
                val timerLine = if (isGemma4 && elapsedSec > 0) {
                    stringResource(R.string.results_status_elapsed, elapsedSec)
                } else null

                LoadingContent(
                    modifier = Modifier.padding(paddingValues),
                    message = message,
                    subMessage = subMessage,
                    extraLine = timerLine,
                )
            }

            // ----------------------------------------------------------------
            // DetectionsReady: bounding boxes available, VLM pending
            // ----------------------------------------------------------------
            is UiState.DetectionsReady -> {
                PartialResultContent(
                    modifier = Modifier.padding(paddingValues),
                    imageUri = capturedImageUri,
                    detections = state.detections,
                    statusMessage = stringResource(R.string.results_status_deep_analysis),
                    showProgress = true,
                    progressFraction = null, // indeterminate
                )
            }

            // ----------------------------------------------------------------
            // AnalyzingDeep: VLM pass is generating the analysis
            // ----------------------------------------------------------------
            is UiState.AnalyzingDeep -> {
                PartialResultContent(
                    modifier = Modifier.padding(paddingValues),
                    imageUri = capturedImageUri,
                    detections = state.detections,
                    statusMessage = stringResource(R.string.results_status_generating),
                    showProgress = true,
                    progressFraction = null,
                )
            }

            // ----------------------------------------------------------------
            // AnalysisComplete: show full results
            // ----------------------------------------------------------------
            is UiState.AnalysisComplete -> {
                // Konfetti celebration for excellent results
                var showKonfetti by remember { mutableStateOf(false) }
                LaunchedEffect(state.result) {
                    if (state.result.healthScore >= 80) {
                        showKonfetti = true
                    }
                }

                Box(modifier = Modifier.padding(paddingValues)) {
                    FullResultContent(
                        modifier = Modifier,
                        imageUri = capturedImageUri,
                        result = state.result,
                        sessionSaved = sessionSaved,
                        boundsMap = boundsMap,
                        listState = resultsListState,
                        onSave = {
                            if (!sessionSaved) {
                                scope.launch {
                                    val uriStr = capturedImageUri?.toString() ?: ""
                                    val location = viewModel.resolveLocation(uriStr)
                                    viewModel.saveSession(
                                        imageUri = uriStr,
                                        result = state.result,
                                        location = location,
                                    )
                                    sessionSaved = true
                                }
                            }
                        },
                        onNewScan = {
                            viewModel.resetState()
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = true }
                            }
                        },
                    )

                    if (showKonfetti) {
                        KonfettiView(
                            modifier = Modifier.fillMaxSize(),
                            parties = listOf(
                                Party(
                                    speed = 0f,
                                    maxSpeed = 30f,
                                    damping = 0.9f,
                                    spread = 360,
                                    colors = listOf(
                                        0xFF00A896.toInt(),
                                        0xFF006994.toInt(),
                                        0xFF4A9FBD.toInt(),
                                        0xFF05C9A8.toInt(),
                                    ),
                                    emitter = Emitter(duration = 2, TimeUnit.SECONDS).perSecond(50),
                                    position = Position.Relative(0.5, 0.0),
                                ),
                            ),
                        )
                    }
                }
            }

            // ----------------------------------------------------------------
            // Error
            // ----------------------------------------------------------------
            is UiState.Error -> {
                ErrorContent(
                    modifier = Modifier.padding(paddingValues),
                    message = state.message,
                    onRetry = {
                        capturedImageUri?.let { uri ->
                            viewModel.analyzeImage(uri)
                        } ?: run {
                            viewModel.resetState()
                            navController.popBackStack()
                        }
                    },
                    onBack = {
                        viewModel.resetState()
                        navController.popBackStack()
                    },
                )
            }

            // Idle should not normally appear here (user is navigated away),
            // but handle defensively.
            is UiState.Idle -> {
                LoadingContent(
                    modifier = Modifier.padding(paddingValues),
                    message = stringResource(R.string.results_status_preparing),
                    subMessage = stringResource(R.string.results_status_please_wait),
                )
            }
        }
    }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                scope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.RESULTS)
                    )
                }
            },
            onScrollToTarget = { targetId ->
                val index = when (targetId) {
                    "results_image" -> 0
                    "results_health" -> 1
                    "results_actions" -> resultsListState.layoutInfo.totalItemsCount - 1
                    else -> null
                }
                if (index != null && index >= 0) {
                    resultsListState.animateScrollToItem(index)
                }
            },
        )

        if (showContributeSheet) {
            val promptState = contributePrompt
            val sessionIds = if (promptState is ContributePromptState.ShowPrompt) promptState.sessionIds else emptyList()
            ContributeBottomSheet(
                imageCount = sessionIds.size,
                onUploadOnWifi = {
                    showContributeSheet = false
                    viewModel.enqueueSessions(sessionIds, wifiOnly = true)
                },
                onUploadNow = {
                    showContributeSheet = false
                    viewModel.enqueueSessions(sessionIds, wifiOnly = false)
                },
                onNotNow = {
                    showContributeSheet = false
                    scope.launch { app.settingsRepository.incrementContributeDeclineCount() }
                    viewModel.dismissContributePrompt()
                },
                onDismiss = {
                    showContributeSheet = false
                    scope.launch { app.settingsRepository.incrementContributeDeclineCount() }
                    viewModel.dismissContributePrompt()
                },
            )
        }
    } // end Box
}

// ---------------------------------------------------------------------------
// Loading state
// ---------------------------------------------------------------------------

@Composable
private fun LoadingContent(
    modifier: Modifier,
    message: String,
    subMessage: String,
    extraLine: String? = null,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShimmerLoadingScreen(itemCount = 2)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (extraLine != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = extraLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Partial result (detections ready / analysing deep)
// ---------------------------------------------------------------------------

@Composable
private fun PartialResultContent(
    modifier: Modifier,
    imageUri: Uri?,
    detections: List<DetectionResult>,
    statusMessage: String,
    showProgress: Boolean,
    progressFraction: Float?,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // Image with bounding-box overlay placeholder
            ImageWithOverlay(
                imageUri = imageUri,
                detections = detections,
            )
        }

        item {
            // AI status banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (showProgress) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (progressFraction != null) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }

        item {
            // Preliminary detection count
            if (detections.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.results_detections_running, detections.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Full result content
// ---------------------------------------------------------------------------

@Composable
private fun FullResultContent(
    modifier: Modifier,
    imageUri: Uri?,
    result: AnalysisResult,
    sessionSaved: Boolean,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect> = mutableMapOf(),
    listState: LazyListState = rememberLazyListState(),
    onSave: () -> Unit,
    onNewScan: () -> Unit,
) {
    val materialBreakdown = result.vlmAnalysis.getMaterialBreakdown()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ----------------------------------------------------------------
        // Image with detections overlay placeholder
        // ----------------------------------------------------------------
        item {
            Box(modifier = Modifier.spotlightTarget("results_image", boundsMap)) {
                ImageWithOverlay(
                    imageUri = imageUri,
                    detections = result.rtdetrDetections,
                    showSaveButton = true,
                )
            }
        }

        // ----------------------------------------------------------------
        // Health score gauge with score breakdown factors
        // ----------------------------------------------------------------
        item {
            val factors = ZoneAggregator.computeFactors(result.vlmAnalysis.debrisList)
            Box(modifier = Modifier.spotlightTarget("results_health", boundsMap)) {
                HealthScoreCard(score = result.healthScore, factors = factors)
            }
        }

        // ----------------------------------------------------------------
        // Summary stats row
        // ----------------------------------------------------------------
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatChip(
                    label = stringResource(R.string.results_stat_total_debris),
                    value = result.totalDebrisCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatChip(
                    label = stringResource(R.string.results_stat_processing),
                    value = "${result.processingTimeMs}ms",
                    modifier = Modifier.weight(1f),
                )
                StatChip(
                    label = stringResource(R.string.results_stat_deep_analysis),
                    value = if (result.hasVLMAnalysis) stringResource(R.string.results_stat_yes) else stringResource(R.string.results_stat_no),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ----------------------------------------------------------------
        // Material breakdown chips
        // ----------------------------------------------------------------
        if (materialBreakdown.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.results_label_material_breakdown),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    items(materialBreakdown.entries.toList()) { (material, count) ->
                        MaterialChip(material = material, count = count)
                    }
                }
            }
        }

        // ----------------------------------------------------------------
        // Detected debris list
        // ----------------------------------------------------------------
        if (result.vlmAnalysis.debrisList.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.results_label_detected_objects),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            items(result.vlmAnalysis.debrisList) { debris ->
                DebrisListItem(debris = debris)
            }
        }

        // ----------------------------------------------------------------
        // Action buttons
        // ----------------------------------------------------------------
        item {
            Column(
                modifier = Modifier.spotlightTarget("results_actions", boundsMap),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!sessionSaved) {
                    OceanGradientButton(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                        height = 60.dp,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = OnGradientColor,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.results_btn_save_history),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = OnGradientColor,
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.results_session_saved),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onNewScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .pressableScale(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.results_btn_new_scan),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Error content
// ---------------------------------------------------------------------------

@Composable
private fun ErrorContent(
    modifier: Modifier,
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Error,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.results_error_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        OceanGradientButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            height = 60.dp,
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = OnGradientColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.results_btn_retry_analysis),
                style = MaterialTheme.typography.titleMedium,
                color = OnGradientColor,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .pressableScale(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.common_back), style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable sub-components
// ---------------------------------------------------------------------------

/**
 * Renders the captured image with bounding box overlays drawn by the
 * [BoundingBoxOverlay] composable. Each detection is drawn as a coloured
 * rectangle with a label chip showing the class name and confidence.
 *
 * When [showSaveButton] is true, a save-to-gallery FAB appears in the
 * bottom-start corner. The annotated JPEG is rendered on-the-fly via
 * [BitmapAnnotator] and saved to MediaStore.
 */
@Composable
private fun ImageWithOverlay(
    imageUri: Uri?,
    detections: List<DetectionResult>,
    showSaveButton: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
        ) {
            BoundingBoxOverlay(
                imageUri = imageUri?.toString(),
                detections = detections,
                modifier = Modifier.fillMaxSize(),
            )

            // Detection count badge
            if (detections.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                ) {
                    Text(
                        text = stringResource(R.string.results_detection_count, detections.size),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }

            // Save to gallery button
            if (showSaveButton && imageUri != null && detections.isNotEmpty()) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            // Render annotated image and save to gallery
                            val annotatedPath = BitmapAnnotator.annotateAndSave(
                                context = context,
                                imageUri = imageUri,
                                detections = detections,
                            )
                            val saved = annotatedPath?.let {
                                ImageGallerySaver.saveToGallery(context, it)
                            }
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
                        .align(Alignment.BottomStart)
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
        }
    }
}

/**
 * Large circular health score display with breakdown factors below the gauge.
 */
@Composable
private fun HealthScoreCard(score: Int, factors: HealthScoreFactors) {
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            HealthScoreGauge(
                score = score,
                size = 120.dp,
                factors = factors,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatChip(
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

/**
 * Coloured chip showing a debris material type and the count of objects with
 * that material. The colour is drawn from the ocean palette in Color.kt.
 */
@Composable
private fun MaterialChip(material: DebrisMaterial, count: Int) {
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

@Composable
private fun DebrisListItem(debris: com.oceanguard.ai.data.Debris) {
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
            // Top row: icon + type + material + confidence
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
                        Text(
                            text = impact.icon,
                            fontSize = 18.sp,
                        )
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

            // Bottom row: degradation time + primary risk
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Degradation time chip
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

