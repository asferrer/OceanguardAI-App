package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.ui.components.GlassCard
import java.util.Locale

/**
 * Displays the species identification results produced by [SpeciesViewModel].
 *
 * Observes [SpeciesViewModel.uiState] and renders one of four branches:
 *   - [SpeciesUiState.Identifying] → indeterminate spinner.
 *   - [SpeciesUiState.Complete]    → scrollable list of result cards, or empty-state.
 *   - [SpeciesUiState.Error]       → error message.
 *   - [SpeciesUiState.Idle]        → empty; navigation should not reach here.
 *
 * The "Done" button always calls [navController.popBackStack] to return to BioDex.
 *
 * TODO(integration): Register the route "species_result" in MainActivity.kt and
 *   pass the [SpeciesViewModel] (scoped to the same NavBackStackEntry or via
 *   activityViewModel) together with [SpeciesCatalog] from the DI graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeciesResultScreen(
    navController: NavController,
    viewModel: SpeciesViewModel,
    catalog: SpeciesCatalog,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { ResultTopBar(onBack = { navController.popBackStack() }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val state = uiState) {
                is SpeciesUiState.Identifying -> IdentifyingContent()
                is SpeciesUiState.Complete    -> CompleteContent(
                    results = state.results,
                    catalog = catalog,
                    onDone = { navController.popBackStack() },
                )
                is SpeciesUiState.Error       -> ErrorContent(
                    message = state.message,
                    onDone = { navController.popBackStack() },
                )
                is SpeciesUiState.Idle        -> { /* no-op; should not be reached */ }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top app bar
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.biodex_result_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.biodex_cd_go_back),
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
// Identifying branch
// ---------------------------------------------------------------------------

@Composable
private fun IdentifyingContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.species_identifying),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Complete branch
// ---------------------------------------------------------------------------

@Composable
private fun CompleteContent(
    results: List<IdentificationResult>,
    catalog: SpeciesCatalog,
    onDone: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (results.isEmpty()) {
            item {
                EmptyResultsMessage()
            }
        } else {
            items(results, key = { "${it.speciesKey}_${it.cosineScore}" }) { result ->
                ResultCard(result = result, catalog = catalog)
            }
        }
        item {
            DoneButton(onClick = onDone)
        }
    }
}

@Composable
private fun EmptyResultsMessage() {
    Text(
        text = stringResource(R.string.biodex_result_none),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 24.dp),
    )
}

@Composable
private fun ResultCard(
    result: IdentificationResult,
    catalog: SpeciesCatalog,
) {
    val lang = Locale.getDefault().language
    val entry = catalog.byKey(result.speciesKey)
    val commonName = entry?.commonNames?.get(lang)
        ?: entry?.commonNames?.get("en")
        ?: result.scientificName
    val confidencePct = (result.confidence * 100).toInt()

    GlassCard(animate = false) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = commonName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = result.scientificName,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.biodex_result_confidence, confidencePct),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            IdSourceBadgeRow(result = result)
            result.vlmDescription?.let { desc ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IdSourceBadgeRow(result: IdentificationResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val sourceLabel = when (result.idSource) {
            SpeciesIdSource.RAG_CORE      -> "RAG Core"
            SpeciesIdSource.RAG_TENTATIVE -> "RAG Tentative"
            SpeciesIdSource.VLM_OPENVOCAB -> "Open Vocab"
        }
        val sourceColor = when (result.idSource) {
            SpeciesIdSource.RAG_CORE      -> MaterialTheme.colorScheme.primaryContainer
            SpeciesIdSource.RAG_TENTATIVE -> MaterialTheme.colorScheme.secondaryContainer
            SpeciesIdSource.VLM_OPENVOCAB -> MaterialTheme.colorScheme.tertiaryContainer
        }
        val sourceOnColor = when (result.idSource) {
            SpeciesIdSource.RAG_CORE      -> MaterialTheme.colorScheme.onPrimaryContainer
            SpeciesIdSource.RAG_TENTATIVE -> MaterialTheme.colorScheme.onSecondaryContainer
            SpeciesIdSource.VLM_OPENVOCAB -> MaterialTheme.colorScheme.onTertiaryContainer
        }
        AssistChip(
            onClick = {},
            label = { Text(text = sourceLabel, style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = sourceColor,
                labelColor = sourceOnColor,
            ),
            shape = RoundedCornerShape(20.dp),
        )
        if (result.outOfRange) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(R.string.biodex_badge_out_of_range),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = RoundedCornerShape(20.dp),
            )
        }
        if (result.uncatalogued) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(R.string.biodex_result_uncatalogued),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Error branch
// ---------------------------------------------------------------------------

@Composable
private fun ErrorContent(message: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        DoneButton(onClick = onDone)
    }
}

// ---------------------------------------------------------------------------
// Shared — Done button
// ---------------------------------------------------------------------------

@Composable
private fun DoneButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = stringResource(R.string.biodex_result_done),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
