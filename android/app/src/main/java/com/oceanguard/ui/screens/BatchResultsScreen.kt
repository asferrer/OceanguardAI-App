package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import coil3.compose.AsyncImage
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.service.BatchItemResult
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.theme.healthScoreColor
import com.oceanguard.ai.ui.UiState
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Batch item model
// ---------------------------------------------------------------------------

/**
 * Represents the processing state of a single image within a batch job.
 *
 * The state transitions from [Pending] -> [Analyzing] -> [Done] (or [Failed]).
 * The UI grid renders each item according to its current state, providing
 * progressive disclosure as results arrive.
 */
private sealed class BatchItemState {
    /** Image is queued but analysis has not started yet. */
    data object Pending : BatchItemState()

    /** Analysis is currently running for this image. */
    data object Analyzing : BatchItemState()

    /**
     * Analysis finished successfully.
     *
     * @param result Complete dual-pipeline result for this image.
     * @param sessionId Room-generated ID for navigation to session detail.
     * @param annotatedUri URI of the annotated image with bounding boxes.
     */
    data class Done(
        val result: AnalysisResult,
        val sessionId: Long = 0,
        val annotatedUri: String? = null,
    ) : BatchItemState()

    /**
     * Analysis failed for this image.
     *
     * @param message Human-readable reason for display in the card.
     */
    data class Failed(val message: String) : BatchItemState()
}

/**
 * Carries one image URI and its current [BatchItemState] through the batch
 * run. Stored in a [mutableStateListOf] so Compose tracks mutations
 * efficiently without requiring a full list replacement.
 */
private data class BatchItem(
    val uri: Uri,
    val state: BatchItemState = BatchItemState.Pending,
)

// ---------------------------------------------------------------------------
// Screen entry point
// ---------------------------------------------------------------------------

/**
 * BatchResultsScreen — processes and displays results for a list of images.
 *
 * Architecture notes:
 *   - Accepts a [uriList] parameter because batch-specific state does not
 *     exist yet in [UiState]. The screen manages its own local batch state
 *     while delegating individual image analysis to [viewModel.analyzeImage].
 *   - Analysis runs sequentially (one image at a time) to avoid saturating
 *     the on-device GPU. Sequential processing also allows the Gemma 3n
 *     session to remain warm between images.
 *   - When all images complete, a summary card is displayed above the action
 *     buttons. The grid remains visible so the user can review individual
 *     results.
 *
 * Future integration note:
 *   When batch-specific UiState variants are added to [UiState] (e.g.
 *   BatchAnalyzing, BatchComplete), replace the local [batchItems] list
 *   with observations of the ViewModel StateFlow.
 *
 * @param navController Navigation controller for back-stack management.
 * @param viewModel     Shared ViewModel — provides [analyzeImage] and [uiState].
 * @param uriList       Ordered list of content URIs to process in this batch.
 *                      Defaults to an empty list; in production this is
 *                      supplied by the photo picker or camera burst flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchResultsScreen(
    navController: NavController,
    viewModel: MainViewModel,
    uriList: List<Uri> = emptyList(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = remember { context.applicationContext as OceanGuardApp }
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.BATCH)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.BATCH))
        .collectAsStateWithLifecycle(initialValue = true)

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    // Observe app-scoped service state for batch progress
    val serviceState by app.inferenceServiceState.collectAsStateWithLifecycle(
        initialValue = InferenceServiceState.Idle,
    )

    // -----------------------------------------------------------------------
    // Local batch state (display model, populated from service state)
    // -----------------------------------------------------------------------

    val batchItems = remember(uriList) {
        mutableStateListOf(*uriList.map { BatchItem(it) }.toTypedArray())
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    var batchStartTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isBatchRunning by remember { mutableStateOf(false) }

    // Derived counts
    val totalCount = batchItems.size
    val doneCount = batchItems.count { it.state is BatchItemState.Done }
    val failedCount = batchItems.count { it.state is BatchItemState.Failed }
    val completedCount = doneCount + failedCount
    val allComplete = totalCount > 0 && completedCount == totalCount

    // Summary statistics (computed only when all items are done)
    val batchTotalDebris = remember(allComplete, batchItems.size) {
        if (!allComplete) 0
        else batchItems.sumOf { item ->
            (item.state as? BatchItemState.Done)?.result?.totalDebrisCount ?: 0
        }
    }
    val batchAvgHealth = remember(allComplete, batchItems.size) {
        if (!allComplete) 0f
        else {
            val doneItems = batchItems.filter { it.state is BatchItemState.Done }
            if (doneItems.isEmpty()) 0f
            else doneItems.map { (it.state as BatchItemState.Done).result.healthScore }
                .average().toFloat()
        }
    }
    val batchTotalTimeMs = remember(allComplete) {
        if (!allComplete) 0L else System.currentTimeMillis() - batchStartTimeMs
    }

    // -----------------------------------------------------------------------
    // Start batch via foreground service
    // -----------------------------------------------------------------------

    LaunchedEffect(uriList) {
        if (uriList.isEmpty()) return@LaunchedEffect
        // Only start if no batch is already running for these URIs
        val current = app.inferenceServiceState.value
        if (current !is InferenceServiceState.BatchRunning && current !is InferenceServiceState.BatchComplete) {
            batchStartTimeMs = System.currentTimeMillis()
            viewModel.startBatchInference(uriList)
            isBatchRunning = true
        }
    }

    // -----------------------------------------------------------------------
    // Sync service state to local batchItems for display
    // -----------------------------------------------------------------------

    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is InferenceServiceState.BatchRunning -> {
                currentIndex = state.currentIndex
                isBatchRunning = true
                // Apply completed items from the service
                for (item in state.completedItems) {
                    val idx = uriList.indexOfFirst { it.toString() == item.uri.toString() }
                    if (idx >= 0) {
                        batchItems[idx] = batchItems[idx].copy(
                            state = when (item) {
                                is BatchItemResult.Done -> BatchItemState.Done(item.result, item.sessionId, item.annotatedUri)
                                is BatchItemResult.Failed -> BatchItemState.Failed(item.message)
                            },
                        )
                    }
                }
                // Mark current item as Analyzing
                if (state.currentIndex < batchItems.size) {
                    val currentItem = batchItems[state.currentIndex]
                    if (currentItem.state is BatchItemState.Pending) {
                        batchItems[state.currentIndex] = currentItem.copy(
                            state = BatchItemState.Analyzing,
                        )
                    }
                }
            }
            is InferenceServiceState.BatchComplete -> {
                isBatchRunning = false
                for (item in state.results) {
                    val idx = uriList.indexOfFirst { it.toString() == item.uri.toString() }
                    if (idx >= 0) {
                        batchItems[idx] = batchItems[idx].copy(
                            state = when (item) {
                                is BatchItemResult.Done -> BatchItemState.Done(item.result, item.sessionId, item.annotatedUri)
                                is BatchItemResult.Failed -> BatchItemState.Failed(item.message)
                            },
                        )
                    }
                }
            }
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Scaffold
    // -----------------------------------------------------------------------

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.batch_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.resetState()
                            navController.popBackStack()
                        },
                    ) {
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
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->

        when {
            // ------------------------------------------------------------------
            // Empty: no URIs were provided
            // ------------------------------------------------------------------
            totalCount == 0 -> {
                EmptyBatchContent(
                    modifier = Modifier.padding(paddingValues),
                    onBack = { navController.popBackStack() },
                )
            }

            // ------------------------------------------------------------------
            // Error path: ViewModel is in Error state before batch starts
            // ------------------------------------------------------------------
            uiState is UiState.Error && !isBatchRunning && completedCount == 0 -> {
                BatchErrorContent(
                    modifier = Modifier.padding(paddingValues),
                    message = (uiState as UiState.Error).message,
                    onRetry = {
                        scope.launch {
                            viewModel.resetState()
                            isBatchRunning = false
                            // Reset all items to Pending so the LaunchedEffect re-runs
                            for (i in batchItems.indices) {
                                batchItems[i] = batchItems[i].copy(state = BatchItemState.Pending)
                            }
                        }
                    },
                    onBack = {
                        viewModel.resetState()
                        navController.popBackStack()
                    },
                )
            }

            // ------------------------------------------------------------------
            // Main grid (in-progress or complete)
            // ------------------------------------------------------------------
            else -> {
                BatchGridContent(
                    modifier = Modifier.padding(paddingValues),
                    batchItems = batchItems,
                    currentIndex = currentIndex,
                    totalCount = totalCount,
                    completedCount = completedCount,
                    allComplete = allComplete,
                    batchTotalDebris = batchTotalDebris,
                    batchAvgHealth = batchAvgHealth,
                    batchTotalTimeMs = batchTotalTimeMs,
                    failedCount = failedCount,
                    boundsMap = boundsMap,
                    onNewBatch = {
                        viewModel.resetState()
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onViewHistory = {
                        navController.navigate("history")
                    },
                    onViewMap = {
                        navController.navigate("map")
                    },
                    onItemClick = { sessionId ->
                        navController.navigate("session/$sessionId")
                    },
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
                        TourDefinitions.getScreenId(TourDefinitions.BATCH)
                    )
                }
            },
        )
    } // end Box
}

// ---------------------------------------------------------------------------
// Grid content (in-progress + complete)
// ---------------------------------------------------------------------------

@Composable
private fun BatchGridContent(
    modifier: Modifier,
    batchItems: List<BatchItem>,
    currentIndex: Int,
    totalCount: Int,
    completedCount: Int,
    allComplete: Boolean,
    batchTotalDebris: Int,
    batchAvgHealth: Float,
    batchTotalTimeMs: Long,
    failedCount: Int,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect> = mutableMapOf(),
    onNewBatch: () -> Unit,
    onViewHistory: () -> Unit,
    onViewMap: () -> Unit = {},
    onItemClick: (Long) -> Unit = {},
) {
    val progressFraction = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        label = "batch_progress",
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {

        // ------------------------------------------------------------------
        // Progress header — full width
        // ------------------------------------------------------------------
        item(span = { GridItemSpan(2) }) {
            BatchProgressHeader(
                currentIndex = currentIndex,
                totalCount = totalCount,
                completedCount = completedCount,
                allComplete = allComplete,
                animatedProgress = animatedProgress,
                modifier = Modifier.spotlightTarget("batch_grid", boundsMap),
            )
        }

        // ------------------------------------------------------------------
        // Summary card — full width, shown only when complete
        // ------------------------------------------------------------------
        if (allComplete) {
            item(span = { GridItemSpan(2) }) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    BatchSummaryCard(
                        totalDebris = batchTotalDebris,
                        avgHealth = batchAvgHealth,
                        totalTimeMs = batchTotalTimeMs,
                        totalImages = totalCount,
                        failedCount = failedCount,
                        modifier = Modifier.spotlightTarget("batch_summary", boundsMap),
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // Thumbnail grid — one cell per image
        // ------------------------------------------------------------------
        items(
            items = batchItems,
            key = { item -> item.uri.toString() },
        ) { item ->
            BatchThumbnailCard(
                item = item,
                onItemClick = onItemClick,
            )
        }

        // ------------------------------------------------------------------
        // Action buttons — full width, shown only when complete
        // ------------------------------------------------------------------
        if (allComplete) {
            item(span = { GridItemSpan(2) }) {
                BatchActionButtons(
                    onNewBatch = onNewBatch,
                    onViewHistory = onViewHistory,
                    onViewMap = onViewMap,
                )
            }
        }

        // Bottom padding item
        item(span = { GridItemSpan(2) }) {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Progress header
// ---------------------------------------------------------------------------

@Composable
private fun BatchProgressHeader(
    currentIndex: Int,
    totalCount: Int,
    completedCount: Int,
    allComplete: Boolean,
    animatedProgress: Float,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (allComplete)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (allComplete) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = stringResource(R.string.batch_status_complete),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        text = stringResource(R.string.batch_images_count, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.batch_status_analyzing, currentIndex + 1, totalCount),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        text = stringResource(R.string.batch_status_done_count, completedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (allComplete)
                    MaterialTheme.colorScheme.secondary
                else
                    MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = if (allComplete)
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.20f)
                else
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.20f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Summary card
// ---------------------------------------------------------------------------

@Composable
private fun BatchSummaryCard(
    totalDebris: Int,
    avgHealth: Float,
    totalTimeMs: Long,
    totalImages: Int,
    failedCount: Int,
    modifier: Modifier = Modifier,
) {
    val avgHealthInt = avgHealth.toInt()
    val healthColor = healthScoreColor(avgHealthInt)
    val riskLabel = when {
        avgHealthInt >= 80 -> stringResource(R.string.batch_health_excellent)
        avgHealthInt >= 60 -> stringResource(R.string.batch_health_good)
        avgHealthInt >= 40 -> stringResource(R.string.batch_health_fair)
        avgHealthInt >= 20 -> stringResource(R.string.batch_health_poor)
        else               -> stringResource(R.string.batch_health_critical)
    }
    val totalSeconds = totalTimeMs / 1000L

    GlassCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.batch_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Four summary stats in a 2x2 grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryStat(
                    label = stringResource(R.string.batch_summary_total_debris),
                    value = totalDebris.toString(),
                    valueColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    label = stringResource(R.string.batch_summary_avg_health),
                    value = "$avgHealthInt / 100",
                    valueColor = healthColor,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryStat(
                    label = stringResource(R.string.batch_summary_total_time),
                    value = if (totalSeconds >= 60L)
                        "${totalSeconds / 60}m ${totalSeconds % 60}s"
                    else
                        "${totalSeconds}s",
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    label = stringResource(R.string.batch_summary_success_rate),
                    value = if (totalImages == 0) "\u2014"
                    else "${((totalImages - failedCount) * 100) / totalImages}%",
                    valueColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
            }

            // Health label strip
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = healthColor.copy(alpha = 0.12f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(healthColor, CircleShape),
                    )
                    Text(
                        text = stringResource(R.string.batch_summary_ocean_health, riskLabel),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = healthColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
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
                color = valueColor,
                textAlign = TextAlign.Center,
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

// ---------------------------------------------------------------------------
// Thumbnail card (grid cell)
// ---------------------------------------------------------------------------

/**
 * Single cell in the batch results grid.
 *
 * Visual behaviour by state:
 *   Pending   — dimmed image placeholder, no badge
 *   Analyzing — pulsing CircularProgressIndicator overlay
 *   Done      — full thumbnail, coloured border from health score,
 *               health score badge (top-right), debris count chip (bottom)
 *   Failed    — error icon overlay with a short failure reason label
 *
 * The border colour is driven by [healthScoreColor] using the same mapping
 * function shared across ResultsScreen and HistoryScreen.
 */
@Composable
private fun BatchThumbnailCard(
    item: BatchItem,
    onItemClick: (Long) -> Unit = {},
) {
    val state = item.state
    val borderColor: Color = when (state) {
        is BatchItemState.Done   -> healthScoreColor(state.result.healthScore)
        is BatchItemState.Failed -> MaterialTheme.colorScheme.error
        else                     -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (state is BatchItemState.Done || state is BatchItemState.Failed) 2.dp else 1.dp
    val isClickable = state is BatchItemState.Done && state.sessionId > 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(14.dp),
            )
            .then(
                if (isClickable) Modifier.clickable {
                    onItemClick((state as BatchItemState.Done).sessionId)
                } else Modifier,
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ----------------------------------------------------------------
            // Thumbnail image — show annotated version (with bboxes) when available
            // ----------------------------------------------------------------
            val imageModel = if (state is BatchItemState.Done && state.annotatedUri != null) {
                state.annotatedUri
            } else {
                item.uri
            }
            AsyncImage(
                model = imageModel,
                contentDescription = stringResource(R.string.cd_batch_image_thumbnail),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )

            // ----------------------------------------------------------------
            // Pending: semi-transparent scrim so the image looks inactive
            // ----------------------------------------------------------------
            if (state is BatchItemState.Pending) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.60f),
                            shape = RoundedCornerShape(14.dp),
                        ),
                )
            }

            // ----------------------------------------------------------------
            // Analyzing: spinner centred on image
            // ----------------------------------------------------------------
            if (state is BatchItemState.Analyzing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(14.dp),
                        ),
                    contentAlignment = Alignment.Center,
                )  {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ----------------------------------------------------------------
            // Done: health score badge (top-right) + debris count (bottom)
            // ----------------------------------------------------------------
            if (state is BatchItemState.Done) {
                val score = state.result.healthScore
                val scoreColor = healthScoreColor(score)

                // Health score badge — top-right corner
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(36.dp)
                        .background(
                            color = scoreColor.copy(alpha = 0.90f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = score.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 10.sp,
                    )
                }

                // Debris count chip — bottom strip
                val debrisCount = state.result.totalDebrisCount
                if (debrisCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                color = Color.Black.copy(alpha = 0.60f),
                                shape = RoundedCornerShape(
                                    bottomStart = 14.dp,
                                    bottomEnd = 14.dp,
                                ),
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.batch_debris_count, debrisCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ----------------------------------------------------------------
            // Failed: error overlay
            // ----------------------------------------------------------------
            if (state is BatchItemState.Failed) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.80f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = stringResource(R.string.cd_analysis_failed),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Action buttons (shown when batch is complete)
// ---------------------------------------------------------------------------

@Composable
private fun BatchActionButtons(
    onNewBatch: () -> Unit,
    onViewHistory: () -> Unit,
    onViewMap: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onNewBatch,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.batch_btn_new_batch),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onViewHistory,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.batch_btn_view_history),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            OutlinedButton(
                onClick = onViewMap,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.batch_btn_view_map),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state (no URIs provided)
// ---------------------------------------------------------------------------

@Composable
private fun EmptyBatchContent(
    modifier: Modifier,
    onBack: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LottieEmptyState(
            title = stringResource(R.string.batch_empty_title),
            message = stringResource(R.string.batch_empty_message),
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = stringResource(R.string.batch_btn_back),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Error state
// ---------------------------------------------------------------------------

@Composable
private fun BatchErrorContent(
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
            text = stringResource(R.string.batch_error_title),
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

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.batch_btn_retry),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = stringResource(R.string.batch_btn_back),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
