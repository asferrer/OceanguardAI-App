package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.theme.OceanGreen
import java.util.Locale

/**
 * SpeciesBatchResultScreen — processes and displays species identification for
 * a list of images selected from the gallery.
 *
 * Mirrors [BatchResultsScreen] in structure and styling:
 *  - Progress header with animated linear bar.
 *  - Thumbnail grid: Pending / Analyzing / Done / Failed states per cell.
 *  - Summary card when all items complete (species count, success rate, time).
 *  - "New Batch" and "BioDex" action buttons.
 *
 * Species data lives in [SpeciesViewModel.batchUiState] — no
 * [InferenceServiceState] or debris pipeline involvement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeciesBatchResultScreen(
    navController: NavController,
    viewModel: SpeciesViewModel,
    catalog: SpeciesCatalog,
    uriList: List<Uri>,
) {
    val batchState by viewModel.batchUiState.collectAsStateWithLifecycle()

    // Kick off identification on first composition.
    LaunchedEffect(uriList) {
        val current = batchState
        if (current is SpeciesBatchUiState.Idle && uriList.isNotEmpty()) {
            viewModel.identifyBatch(uriList)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.species_batch_title),
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
            uriList.isEmpty() -> {
                SpeciesBatchEmptyContent(
                    modifier = Modifier.padding(paddingValues),
                    onBack = { navController.popBackStack() },
                )
            }
            batchState is SpeciesBatchUiState.Error -> {
                SpeciesBatchErrorContent(
                    modifier = Modifier.padding(paddingValues),
                    message = (batchState as SpeciesBatchUiState.Error).message,
                    onRetry = { viewModel.identifyBatch(uriList) },
                    onBack = {
                        viewModel.resetState()
                        navController.popBackStack()
                    },
                )
            }
            else -> {
                val items = when (val s = batchState) {
                    is SpeciesBatchUiState.Running  -> s.items
                    is SpeciesBatchUiState.Complete -> s.items
                    else -> uriList.map { SpeciesBatchItem(it) }
                }
                val currentIndex = (batchState as? SpeciesBatchUiState.Running)?.currentIndex ?: items.size
                val allComplete = batchState is SpeciesBatchUiState.Complete
                val totalTimeMs = if (batchState is SpeciesBatchUiState.Complete) {
                    val c = batchState as SpeciesBatchUiState.Complete
                    c.endTimeMs - c.startTimeMs
                } else 0L

                SpeciesBatchGridContent(
                    modifier = Modifier.padding(paddingValues),
                    items = items,
                    currentIndex = currentIndex,
                    allComplete = allComplete,
                    totalTimeMs = totalTimeMs,
                    catalog = catalog,
                    onNewBatch = {
                        viewModel.resetState()
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onViewBioDex = {
                        navController.navigate("biodex")
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Grid content
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesBatchGridContent(
    modifier: Modifier,
    items: List<SpeciesBatchItem>,
    currentIndex: Int,
    allComplete: Boolean,
    totalTimeMs: Long,
    catalog: SpeciesCatalog,
    onNewBatch: () -> Unit,
    onViewBioDex: () -> Unit,
) {
    val totalCount = items.size
    val completedCount = items.count {
        it.state is SpeciesBatchItemState.Done || it.state is SpeciesBatchItemState.Failed
    }
    val failedCount = items.count { it.state is SpeciesBatchItemState.Failed }
    val totalSpeciesFound = items.sumOf { item ->
        (item.state as? SpeciesBatchItemState.Done)?.results?.size ?: 0
    }
    val progressFraction = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount
    val animatedProgress by animateFloatAsState(targetValue = progressFraction, label = "species_batch_progress")

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            SpeciesBatchProgressHeader(
                currentIndex = currentIndex,
                totalCount = totalCount,
                completedCount = completedCount,
                allComplete = allComplete,
                animatedProgress = animatedProgress,
            )
        }

        if (allComplete) {
            item(span = { GridItemSpan(2) }) {
                AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                    SpeciesBatchSummaryCard(
                        totalImages = totalCount,
                        totalSpecies = totalSpeciesFound,
                        failedCount = failedCount,
                        totalTimeMs = totalTimeMs,
                    )
                }
            }
        }

        items(items = items, key = { it.uri.toString() }) { item ->
            SpeciesBatchThumbnailCard(item = item, catalog = catalog)
        }

        if (allComplete) {
            item(span = { GridItemSpan(2) }) {
                SpeciesBatchActionButtons(
                    onNewBatch = onNewBatch,
                    onViewBioDex = onViewBioDex,
                )
            }
        }

        item(span = { GridItemSpan(2) }) {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Progress header
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesBatchProgressHeader(
    currentIndex: Int,
    totalCount: Int,
    completedCount: Int,
    allComplete: Boolean,
    animatedProgress: Float,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                            text = stringResource(
                                R.string.batch_status_analyzing,
                                currentIndex + 1,
                                totalCount,
                            ),
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
                color = if (allComplete) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onPrimaryContainer,
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
private fun SpeciesBatchSummaryCard(
    totalImages: Int,
    totalSpecies: Int,
    failedCount: Int,
    totalTimeMs: Long,
) {
    val totalSeconds = totalTimeMs / 1000L
    GlassCard(modifier = Modifier.fillMaxWidth()) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SpeciesSummaryStat(
                    label = stringResource(R.string.species_batch_summary_species),
                    value = totalSpecies.toString(),
                    valueColor = OceanGreen,
                    modifier = Modifier.weight(1f),
                )
                SpeciesSummaryStat(
                    label = stringResource(R.string.batch_summary_total_time),
                    value = if (totalSeconds >= 60L)
                        "${totalSeconds / 60}m ${totalSeconds % 60}s"
                    else "${totalSeconds}s",
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SpeciesSummaryStat(
                    label = stringResource(R.string.batch_summary_success_rate),
                    value = if (totalImages == 0) "—"
                    else "${((totalImages - failedCount) * 100) / totalImages}%",
                    valueColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                SpeciesSummaryStat(
                    label = stringResource(R.string.species_batch_summary_images),
                    value = totalImages.toString(),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SpeciesSummaryStat(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
// Thumbnail grid cell
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesBatchThumbnailCard(item: SpeciesBatchItem, catalog: SpeciesCatalog) {
    val state = item.state
    val borderColor: Color = when (state) {
        is SpeciesBatchItemState.Done   -> OceanGreen
        is SpeciesBatchItemState.Failed -> MaterialTheme.colorScheme.error
        else                            -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (state is SpeciesBatchItemState.Done ||
        state is SpeciesBatchItemState.Failed) 2.dp else 1.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.uri,
                contentDescription = stringResource(R.string.cd_batch_image_thumbnail),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )

            if (state is SpeciesBatchItemState.Pending) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.60f),
                            RoundedCornerShape(14.dp),
                        ),
                )
            }

            if (state is SpeciesBatchItemState.Analyzing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                            RoundedCornerShape(14.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = OceanGreen,
                    )
                }
            }

            if (state is SpeciesBatchItemState.Done) {
                val results = state.results
                if (results.isNotEmpty()) {
                    val lang = Locale.getDefault().language
                    val top = results[0]
                    val entry = catalog.byKey(top.speciesKey)
                    val name = entry?.commonNames?.get(lang)
                        ?: entry?.commonNames?.get("en")
                        ?: top.scientificName

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Color.Black.copy(alpha = 0.65f),
                                RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (results.size > 1) {
                                Text(
                                    text = "+${results.size - 1} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        color = OceanGreen.copy(alpha = 0.80f),
                        shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.biodex_result_none_short),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            if (state is SpeciesBatchItemState.Failed) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.80f),
                            RoundedCornerShape(14.dp),
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
// Action buttons
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesBatchActionButtons(onNewBatch: () -> Unit, onViewBioDex: () -> Unit) {
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
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.species_batch_btn_new_batch),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        OutlinedButton(
            onClick = onViewBioDex,
            modifier = Modifier
                .fillMaxWidth()
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
                text = stringResource(R.string.species_batch_btn_view_biodex),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesBatchEmptyContent(modifier: Modifier, onBack: () -> Unit) {
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
private fun SpeciesBatchErrorContent(
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
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
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
