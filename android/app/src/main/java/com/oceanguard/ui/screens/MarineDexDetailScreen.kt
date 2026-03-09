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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisImpactInfo
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.EnvironmentalImpact
import com.oceanguard.ai.data.collection.CollectionRepository
import com.oceanguard.ai.data.collection.MarineDexEntry
import com.oceanguard.ai.ui.components.DexSpriteImage
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import androidx.navigation.NavController
import com.oceanguard.ai.ui.theme.CoralRed
import com.oceanguard.ai.ui.theme.GradientDeepEnd
import com.oceanguard.ai.ui.theme.GradientDeepMid
import com.oceanguard.ai.ui.theme.GradientDeepStart
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import com.oceanguard.ai.ui.theme.healthScoreColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

@Composable
fun MarineDexDetailScreen(
    debrisType: String,
    collectionRepository: CollectionRepository,
    onNavigateBack: () -> Unit,
    onNavigateToSession: (Long) -> Unit = {},
    navController: NavController? = null,
) {
    val allEntries by collectionRepository.allDexEntries.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val entry = allEntries.firstOrNull { it.debrisType == debrisType }
    val parsedType = remember(debrisType) { DebrisType.fromString(debrisType) }
    val impact = remember(parsedType) { EnvironmentalImpact.getImpact(parsedType) }

    val app = LocalContext.current.applicationContext as OceanGuardApp
    val sessions by app.repository.getSessionsByDebrisType(parsedType)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val coroutineScope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.MARINEDEX_DETAIL)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.MARINEDEX_DETAIL))
        .collectAsStateWithLifecycle(initialValue = true)

    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    var contentVisible by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { contentVisible = true }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeroHeader(
                debrisType = debrisType,
                entry = entry,
                impact = impact,
                onNavigateBack = onNavigateBack,
                onToggleFavorite = {
                    coroutineScope.launch { collectionRepository.toggleFavorite(debrisType) }
                },
                modifier = Modifier.spotlightTarget("dex_detail_sprite", boundsMap),
            )

            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    animationSpec = tween(400),
                    initialOffsetY = { it / 3 },
                ),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    DetailTabRow(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.spotlightTarget("dex_detail_tabs", boundsMap),
                    )
                    when (selectedTab) {
                        0 -> InfoTabContent(
                            debrisType = debrisType,
                            entry = entry,
                            impact = impact,
                            parsedType = parsedType,
                            boundsMap = boundsMap,
                        )
                        1 -> MarineDexGallery(
                            debrisType = parsedType,
                            sessions = sessions,
                            onSessionClick = onNavigateToSession,
                        )
                        2 -> MarineDexTypeMap(
                            debrisType = parsedType,
                            sessions = sessions,
                            onSessionClick = onNavigateToSession,
                        )
                    }
                }
            }
        }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                coroutineScope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.MARINEDEX_DETAIL)
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
                coroutineScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "marinedex_detail",
                onContinue = {
                    showTransitionDialog = false
                    navController?.navigate("achievements") {
                        popUpTo("home")
                    }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    coroutineScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tab row
// ---------------------------------------------------------------------------

@Composable
private fun DetailTabRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        stringResource(R.string.marinedex_detail_tab_info),
        stringResource(R.string.marinedex_detail_tab_gallery),
        stringResource(R.string.marinedex_detail_tab_map),
    )
    TabRow(
        selectedTabIndex = selectedTab,
        modifier = modifier,
    ) {
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
// Info tab content (extracted from original DetailContent)
// ---------------------------------------------------------------------------

@Composable
private fun InfoTabContent(
    debrisType: String,
    entry: MarineDexEntry?,
    impact: DebrisImpactInfo,
    parsedType: DebrisType,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect> = mutableMapOf(),
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val displayName = remember(debrisType) {
        debrisType.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
    }
    val materialLabel = debrisToMaterial(parsedType)
    val detectedCount = entry?.timesDetected ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TitleSection(displayName = displayName, materialLabel = materialLabel)
        Text(
            text = if (detectedCount != 1) stringResource(R.string.marinedex_detail_detected_plural, detectedCount)
                   else stringResource(R.string.marinedex_detail_detected_singular, detectedCount),
            style = MaterialTheme.typography.bodyMedium,
            color = OceanBlueLight,
        )
        StatsRow(entry = entry, dateFormat = dateFormat)
        Box(modifier = Modifier.spotlightTarget("dex_detail_impact", boundsMap)) {
            EnvironmentalImpactCard(impact = impact)
        }
    }
}

// ---------------------------------------------------------------------------
// Hero header with gradient background and floating sprite
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroHeader(
    debrisType: String,
    entry: MarineDexEntry?,
    impact: DebrisImpactInfo,
    onNavigateBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isFavorite = entry?.isFavorite ?: false
    val floatTransition = rememberInfiniteTransition(label = "emojiFloat")
    val floatY by floatTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatY",
    )
    val heartScale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        GradientDeepStart,
                        GradientDeepMid,
                        GradientDeepEnd.copy(alpha = 0.3f),
                    ),
                ),
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val parsedType = remember(debrisType) { DebrisType.fromString(debrisType) }
            DexSpriteImage(
                debrisType = parsedType,
                size = 120.dp,
                modifier = Modifier.graphicsLayer { translationY = floatY },
            )
        }
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onNavigateBack, modifier = Modifier.pressableScale()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.marinedex_detail_cd_go_back),
                        tint = Color.White,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
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
                            stringResource(R.string.marinedex_detail_cd_remove_favorite)
                        } else {
                            stringResource(R.string.marinedex_detail_cd_add_favorite)
                        },
                        tint = if (isFavorite) CoralRed else Color.White,
                        modifier = Modifier.size(28.dp).graphicsLayer {
                            scaleX = heartScale.value
                            scaleY = heartScale.value
                        },
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
    }
}

// ---------------------------------------------------------------------------
// Title section
// ---------------------------------------------------------------------------

@Composable
private fun TitleSection(displayName: String, materialLabel: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .background(color = OceanGreen.copy(alpha = 0.18f), shape = RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = materialLabel,
                style = MaterialTheme.typography.labelSmall,
                color = OceanGreen,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Stats row
// ---------------------------------------------------------------------------

@Composable
private fun StatsRow(entry: MarineDexEntry?, dateFormat: SimpleDateFormat) {
    val firstSeen = remember(entry?.firstSeenAt) {
        entry?.firstSeenAt?.let { dateFormat.format(it) } ?: "--"
    }
    val lastSeen = remember(entry?.lastSeenAt) {
        entry?.lastSeenAt?.let { dateFormat.format(it) } ?: "--"
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(
            label = stringResource(R.string.marinedex_detail_stat_times_detected),
            value = (entry?.timesDetected ?: 0).toString(),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.marinedex_detail_stat_first_seen),
            value = firstSeen,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.marinedex_detail_stat_last_seen),
            value = lastSeen,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, animate = false) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = OceanBlueLight, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------------------
// Environmental impact card
// ---------------------------------------------------------------------------

@Composable
private fun EnvironmentalImpactCard(impact: DebrisImpactInfo) {
    GlassCard {
        Text(
            text = stringResource(R.string.marinedex_detail_impact_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = OceanBlueLight, modifier = Modifier.size(20.dp))
            Column {
                Text(
                    text = stringResource(R.string.marinedex_detail_impact_degradation_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = impact.degradationTime, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        RiskLevelBar(riskScore = impact.riskScore)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = impact.primaryRisk, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        impact.annualVolumeOcean?.let { volume ->
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.WaterDrop, contentDescription = null, tint = OceanGreen, modifier = Modifier.size(20.dp))
                Column {
                    Text(
                        text = stringResource(R.string.marinedex_detail_impact_annual_volume_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = volume, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun RiskLevelBar(riskScore: Int) {
    val clampedScore = riskScore.coerceIn(0, 10)
    val fraction = clampedScore / 10f
    val barColor = healthScoreColor(score = (100 - clampedScore * 10).coerceAtLeast(0))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.marinedex_detail_impact_risk_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "$clampedScore", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = barColor)
                Text(text = "/ 10", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp), color = barColor, trackColor = MaterialTheme.colorScheme.surfaceVariant)
    }
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

@Composable
private fun debrisToMaterial(type: DebrisType): String = when (type) {
    DebrisType.BOTTLE, DebrisType.PLASTIC_DEBRIS -> stringResource(R.string.material_plastic)
    DebrisType.CAN, DebrisType.METAL_DEBRIS -> stringResource(R.string.material_metal)
    DebrisType.FISHING_NET -> stringResource(R.string.material_nylon)
    DebrisType.GLOVE, DebrisType.MASK -> stringResource(R.string.material_mixed)
    DebrisType.TIRE -> stringResource(R.string.material_rubber)
    DebrisType.FABRIC_DEBRIS -> stringResource(R.string.material_fabric)
    DebrisType.GLASS_DEBRIS -> stringResource(R.string.material_glass)
    DebrisType.OTHER -> stringResource(R.string.material_unknown)
}
