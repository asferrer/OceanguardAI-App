package com.oceanguard.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.ui.components.dex.DexItem
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.theme.CoralRed
import com.oceanguard.ai.ui.theme.GradientDeepEnd
import com.oceanguard.ai.ui.theme.GradientDeepMid
import com.oceanguard.ai.ui.theme.GradientDeepStart
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Detail screen for a single species in the BioDex.
 *
 * Structure mirrors [MarineDexDetailScreen]: hero header with floating sprite +
 * favourite toggle, then a 3-tab layout (Info / Gallery / Map).
 *
 * Tab 1 (Gallery): [SpeciesDexGallery] — tapping a thumbnail calls [onObservationClick].
 * Tab 2 (Map): [SpeciesObservationMap] — real MapLibre map, same tile style as the
 *              main MapScreen; tapping a pin calls [onObservationClick].
 *
 * TODO(integration): Add route "biodex/{speciesKey}?tab={tab}" in MainActivity.
 * TODO(integration — M6): Handle [onObservationClick] in MainActivity by navigating
 *   to route "species_observation/{observationId}". The handler should resolve the
 *   observation via SpeciesCollectionRepository.getObservationById(id) and call
 *   SpeciesObservationDetailScreen(observation, catalog, onNavigateBack =
 *   { navController.popBackStack() }).
 */
@Composable
fun SpeciesDexDetailScreen(
    speciesKey: String,
    repo: SpeciesCollectionRepository,
    catalog: SpeciesCatalog,
    onNavigateBack: () -> Unit,
    onObservationClick: (observationId: Long) -> Unit = {},
    initialTab: Int = 0,
) {
    val vm: SpeciesDexViewModel = viewModel(factory = SpeciesDexViewModel.Factory(repo, catalog))
    val scope = rememberCoroutineScope()

    val catalogEntry = remember(speciesKey) { vm.catalogEntry(speciesKey) }
    val observations by vm.observationsForSpecies(speciesKey)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val dexEntry = vm.dexItems.collectAsStateWithLifecycle().value
        .firstOrNull { it.key == speciesKey }

    val lang = remember { Locale.getDefault().language }
    val displayName = remember(catalogEntry, lang) {
        catalogEntry?.commonNames?.get(lang)
            ?: catalogEntry?.commonNames?.get("en")
            ?: catalogEntry?.scientificName
            ?: speciesKey
    }

    var contentVisible by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    LaunchedEffect(Unit) { contentVisible = true }

    Column(modifier = Modifier.fillMaxSize()) {
        SpeciesHeroHeader(
            spritePath = catalogEntry?.spritePath,
            isFavorite = dexEntry?.isFavorite ?: false,
            onNavigateBack = onNavigateBack,
            onToggleFavorite = {
                scope.launch {
                    vm.toggleFavorite(speciesKey, dexEntry?.isFavorite ?: false)
                }
            },
        )

        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(400)) + slideInVertically(
                animationSpec = tween(400),
                initialOffsetY = { it / 3 },
            ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SpeciesTabRow(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
                when (selectedTab) {
                    0 -> SpeciesInfoTabContent(
                        speciesKey = speciesKey,
                        displayName = displayName,
                        catalogEntry = catalogEntry,
                        dexEntry = dexEntry,
                        lang = lang,
                    )
                    1 -> SpeciesDexGallery(
                        observations = observations,
                        speciesDisplayName = displayName,
                        onObservationClick = onObservationClick,
                    )
                    2 -> SpeciesObservationMap(
                        observations = observations,
                        onObservationClick = onObservationClick,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hero header
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeciesHeroHeader(
    spritePath: String?,
    isFavorite: Boolean,
    onNavigateBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val floatTransition = rememberInfiniteTransition(label = "spriteFloat")
    val floatY by floatTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatY",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(GradientDeepStart, GradientDeepMid, GradientDeepEnd.copy(alpha = 0.3f))
                )
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SpeciesSpriteImage(
                spritePath = spritePath,
                size = 120.dp,
                modifier = Modifier.graphicsLayer { translationY = floatY },
            )
        }
        HeroTopBar(
            isFavorite = isFavorite,
            onNavigateBack = onNavigateBack,
            onToggleFavorite = onToggleFavorite,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroTopBar(
    isFavorite: Boolean,
    onNavigateBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val heartScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onNavigateBack, modifier = Modifier.pressableScale()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.biodex_detail_cd_go_back),
                    tint = Color.White,
                )
            }
        },
        actions = {
            IconButton(
                onClick = {
                    scope.launch {
                        heartScale.animateTo(0.8f, spring(stiffness = Spring.StiffnessHigh))
                        heartScale.animateTo(1.2f, spring(stiffness = Spring.StiffnessMedium))
                        heartScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                    onToggleFavorite()
                },
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) {
                        stringResource(R.string.biodex_detail_cd_remove_favorite)
                    } else {
                        stringResource(R.string.biodex_detail_cd_add_favorite)
                    },
                    tint = if (isFavorite) CoralRed else Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer { scaleX = heartScale.value; scaleY = heartScale.value },
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

// ---------------------------------------------------------------------------
// Tab row
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf(
        stringResource(R.string.biodex_detail_tab_info),
        stringResource(R.string.biodex_detail_tab_gallery),
        stringResource(R.string.biodex_detail_tab_map),
    )
    PrimaryTabRow(selectedTabIndex = selectedTab) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                text = { Text(title) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Info tab
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesInfoTabContent(
    speciesKey: String,
    displayName: String,
    catalogEntry: SpeciesCatalogEntry?,
    dexEntry: DexItem?,
    lang: String,
) {
    val description = catalogEntry?.descriptions?.get(lang)
        ?: catalogEntry?.descriptions?.get("en")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SpeciesTitleSection(
            displayName = displayName,
            scientificName = catalogEntry?.scientificName,
            iucnStatus = catalogEntry?.iucnStatus,
            cosmopolitan = catalogEntry?.cosmopolitan ?: false,
        )

        description?.let { desc ->
            GlassCard(animate = false) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (dexEntry != null) {
            SpeciesStatsRow(dexItem = dexEntry)
        }
    }
}

@Composable
private fun SpeciesTitleSection(
    displayName: String,
    scientificName: String?,
    iucnStatus: String?,
    cosmopolitan: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        scientificName?.let { sci ->
            Text(
                text = sci,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            iucnStatus?.let { status -> IucnStatusBadge(status = status) }
            if (cosmopolitan) CosmopolitanBadge()
        }
    }
}

@Composable
private fun IucnStatusBadge(status: String) {
    val color = iucnStatusColor(status)
    Surface(shape = RoundedCornerShape(20.dp), color = color.copy(alpha = 0.18f)) {
        Text(
            text = "IUCN: $status",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CosmopolitanBadge() {
    Surface(shape = RoundedCornerShape(20.dp), color = OceanBlueLight.copy(alpha = 0.18f)) {
        Text(
            text = stringResource(R.string.biodex_badge_cosmopolitan),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = OceanBlueLight,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SpeciesStatsRow(dexItem: DexItem) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SpeciesStatCard(
            label = stringResource(R.string.biodex_detail_stat_observations),
            value = dexItem.count.toString(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SpeciesStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, animate = false) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OceanBlueLight,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
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
// IUCN status colour helper
// ---------------------------------------------------------------------------

private fun iucnStatusColor(status: String): Color = when (status.uppercase()) {
    "EX", "EW"         -> Color(0xFF4A0000)   // extinct — very dark red
    "CR"               -> Color(0xFFB71C1C)   // critically endangered
    "EN"               -> Color(0xFFE65100)   // endangered
    "VU"               -> Color(0xFFF57F17)   // vulnerable
    "NT"               -> Color(0xFF827717)   // near threatened
    "LC"               -> Color(0xFF2E7D32)   // least concern
    "DD"               -> Color(0xFF546E7A)   // data deficient
    else               -> Color(0xFF607D8B)   // not evaluated / unknown
}
