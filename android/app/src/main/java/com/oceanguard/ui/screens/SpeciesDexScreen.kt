package com.oceanguard.ai.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.ui.components.dex.DexCard
import com.oceanguard.ai.ui.components.dex.DexCollectionHeader
import com.oceanguard.ai.ui.components.dex.DexItem
import java.util.Locale

/**
 * BioDex — Pokédex-style grid of discovered marine species.
 *
 * Mirrors [MarineDexScreen] visually but is driven by [SpeciesCollectionRepository]
 * and [SpeciesCatalog]. Undiscovered entries from the catalog are appended as
 * locked cells so the user sees the full catalog size at a glance.
 *
 * Navigation into the detail screen is delegated via [onNavigateToDetail] so
 * this composable stays route-agnostic (MainActivity wires the route at
 * integration time).
 *
 * TODO(integration): Wire navigation routes "biodex" / "biodex/{speciesKey}?tab="
 *                    in MainActivity.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeciesDexScreen(
    repo: SpeciesCollectionRepository,
    catalog: SpeciesCatalog,
    onNavigateToDetail: (speciesKey: String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val vm: SpeciesDexViewModel = viewModel(factory = SpeciesDexViewModel.Factory(repo, catalog))

    val dexItems by vm.dexItems.collectAsStateWithLifecycle()
    val discoveredCount by vm.discoveredCount.collectAsStateWithLifecycle()
    val catalogTotal = vm.catalogTotal

    // Build the full grid: discovered items first, then locked placeholders for
    // entries in the catalog that the user has not yet seen.
    val discoveredKeys = dexItems.map { it.key }.toSet()
    val lockedItems = buildLockedItems(catalog, discoveredKeys)
    val allItems = dexItems + lockedItems

    // Derive favourites count from the already-collected items.
    val favoriteCount = dexItems.count { it.isFavorite }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.biodex_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                DexCollectionHeader(
                    discoveredCount = discoveredCount,
                    totalCount = catalogTotal,
                    favoriteCount = favoriteCount,
                    observationCount = dexItems.sumOf { it.count },
                    discoveredLabel = stringResource(R.string.biodex_stat_discovered),
                    favoritesLabel = stringResource(R.string.biodex_stat_favorites),
                    observationsLabel = stringResource(R.string.biodex_stat_observations),
                )
            }

            if (allItems.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    BioDexEmptyState()
                }
            }

            itemsIndexed(items = allItems, key = { _, item -> item.key }) { index, item ->
                DexCard(
                    item = item,
                    animationIndex = index,
                    onNavigateToDetail = onNavigateToDetail,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helper — produce locked DexItem stubs for catalog entries not yet seen
// ---------------------------------------------------------------------------

/**
 * Produces locked [DexItem] stubs for catalog entries the user has not yet seen.
 *
 * TODO(integration): Requires SpeciesCatalog.allKeys(): List<String> — a read-only
 * enumeration of every speciesKey in the loaded catalog JSON. Add this alongside
 * SpeciesCatalog.byKey() and SpeciesCatalog.totalCount().
 */
private fun buildLockedItems(
    catalog: SpeciesCatalog,
    discoveredKeys: Set<String>,
): List<DexItem> {
    val lang = Locale.getDefault().language
    return catalog.allKeys()
        .filter { key -> key !in discoveredKeys }
        .map { key ->
            val entry = catalog.byKey(key)
            val displayName = entry?.commonNames?.get(lang)
                ?: entry?.commonNames?.get("en")
                ?: entry?.scientificName
                ?: "???"
            DexItem(
                key = key,
                displayName = displayName,
                spritePath = entry?.spritePath,
                discovered = false,
                count = 0,
                isFavorite = false,
                subtitle = entry?.scientificName,
            )
        }
}

// ---------------------------------------------------------------------------
// Empty state — shown when no species discovered and no catalog downloaded yet
// ---------------------------------------------------------------------------

@Composable
private fun BioDexEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_fish),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.biodex_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.biodex_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
