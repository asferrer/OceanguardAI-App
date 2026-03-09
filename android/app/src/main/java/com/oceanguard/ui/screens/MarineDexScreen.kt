package com.oceanguard.ai.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
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
import com.oceanguard.ai.ui.theme.GradientDeepEnd
import com.oceanguard.ai.ui.theme.GradientDeepMid
import com.oceanguard.ai.ui.theme.GradientDeepStart
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import com.oceanguard.ai.ui.theme.healthScoreColor
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

private const val TOTAL_DEX_ENTRIES = 11

/**
 * MarineDexScreen — Pokedex-style collection of all detectable debris types.
 *
 * Discovered entries show the debris emoji icon, formatted name, and detection
 * count. Undiscovered entries render as locked silhouettes with "?" glyphs.
 * Cards animate in with a staggered spring on first composition.
 * A Konfetti burst celebrates full collection completion (all 11 discovered).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarineDexScreen(
    collectionRepository: CollectionRepository,
    onNavigateToDetail: (debrisType: String) -> Unit,
    onNavigateBack: () -> Unit,
    navController: NavController? = null,
) {
    val dexEntries by collectionRepository.allDexEntries.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val discoveredCount by collectionRepository.discoveredCount.collectAsStateWithLifecycle(
        initialValue = 0,
    )
    val favoriteCount by collectionRepository.favoriteCount.collectAsStateWithLifecycle(
        initialValue = 0,
    )
    val totalDetections by collectionRepository.totalDetections.collectAsStateWithLifecycle(
        initialValue = 0,
    )

    val dexContext = LocalContext.current
    val app = remember(dexContext) { dexContext.applicationContext as OceanGuardApp }
    val dexScope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.MARINEDEX)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.MARINEDEX))
        .collectAsStateWithLifecycle(initialValue = true)

    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    val discoveredTypes = remember(dexEntries) {
        dexEntries.associateBy { it.debrisType }
    }
    val allComplete = discoveredCount == TOTAL_DEX_ENTRIES

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.marinedex_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.marinedex_cd_go_back),
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
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ----------------------------------------------------------------
                // Header spans full width — progress + stats
                // ----------------------------------------------------------------
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    DexHeader(
                        discoveredCount = discoveredCount,
                        favoriteCount = favoriteCount,
                        totalDetections = totalDetections,
                        modifier = Modifier.spotlightTarget("dex_progress", boundsMap),
                    )
                }

                // ----------------------------------------------------------------
                // One card per DebrisType in enum declaration order
                // ----------------------------------------------------------------
                itemsIndexed(
                    items = DebrisType.entries,
                    key = { _, type -> type.name },
                ) { index, type ->
                    val entry = discoveredTypes[type.name]
                    val cardModifier = if (index == 0) {
                        Modifier.spotlightTarget("dex_grid", boundsMap)
                    } else {
                        Modifier
                    }
                    DexCard(
                        type = type,
                        entry = entry,
                        animationIndex = index,
                        onNavigateToDetail = onNavigateToDetail,
                        modifier = cardModifier,
                    )
                }
            }

            // ----------------------------------------------------------------
            // Konfetti burst when the full dex is completed
            // ----------------------------------------------------------------
            if (allComplete) {
                KonfettiView(
                    modifier = Modifier.fillMaxSize(),
                    parties = buildConfettiParties(),
                )
            }
        }
    }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                dexScope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.MARINEDEX)
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
                dexScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "marinedex",
                onContinue = {
                    showTransitionDialog = false
                    navController?.navigate("marinedex/OTHER")
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    dexScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    } // end Box
}

// ---------------------------------------------------------------------------
// Header — gradient hero with circular progress and stats row
// ---------------------------------------------------------------------------

@Composable
private fun DexHeader(
    discoveredCount: Int,
    favoriteCount: Int,
    totalDetections: Int,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val heroTextColor = if (isDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Gradient hero box with circular progress
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = if (isDark) {
                            listOf(GradientDeepStart, GradientDeepMid, GradientDeepEnd)
                        } else {
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            )
                        },
                    )
                )
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DexCircularProgress(
                    discovered = discoveredCount,
                    total = TOTAL_DEX_ENTRIES,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (discoveredCount == TOTAL_DEX_ENTRIES) {
                        stringResource(R.string.marinedex_collection_complete)
                    } else {
                        "$discoveredCount / $TOTAL_DEX_ENTRIES Discovered"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = heroTextColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stats row inside a glass card
        GlassCard(animate = false) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DexStat(
                    label = stringResource(R.string.marinedex_stat_discovered),
                    value = discoveredCount.toString(),
                    valueColor = OceanGreen,
                )
                VerticalDivider(
                    modifier = Modifier.height(40.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                )
                DexStat(
                    label = stringResource(R.string.marinedex_stat_favorites),
                    value = favoriteCount.toString(),
                    valueColor = OceanBlueLight,
                )
                VerticalDivider(
                    modifier = Modifier.height(40.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                )
                DexStat(
                    label = stringResource(R.string.marinedex_stat_detections),
                    value = totalDetections.toString(),
                    valueColor = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DexCircularProgress(discovered: Int, total: Int) {
    val isDark = isSystemInDarkTheme()
    val progress = (discovered.toFloat() / total).coerceIn(0f, 1f)
    val sweepAngle by animateFloatAsState(
        targetValue = 360f * progress,
        animationSpec = tween(durationMillis = 800),
        label = "dexProgress",
    )
    val progressColor = when {
        progress >= 1f -> OceanGreen
        progress >= 0.6f -> OceanBlueLight
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = if (isDark) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val textColor = if (isDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val subtextColor = if (isDark) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)

    Box(
        modifier = Modifier
            .size(96.dp)
            .drawBehind {
                // Track circle
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx()),
                )
                // Progress arc
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$discovered",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = textColor,
            )
            Text(
                text = "/ $total",
                style = MaterialTheme.typography.labelSmall,
                color = subtextColor,
            )
        }
    }
}

@Composable
private fun DexStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor,
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

// ---------------------------------------------------------------------------
// Individual dex card — discovered or locked
// ---------------------------------------------------------------------------

@Composable
private fun DexCard(
    type: DebrisType,
    entry: MarineDexEntry?,
    animationIndex: Int,
    onNavigateToDetail: (debrisType: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDiscovered = entry != null
    val impact = EnvironmentalImpact.IMPACT_MAP[type]
    val riskScore = impact?.riskScore ?: 0

    // Staggered spring entry animation
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(animationIndex * 40L)
        isVisible = true
    }
    val cardScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.75f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "cardScale_${type.name}",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 250, delayMillis = animationIndex * 40),
        label = "cardAlpha_${type.name}",
    )

    val borderColor = when {
        !isDiscovered -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        else -> OceanGreen.copy(alpha = 0.7f)
    }

    val formattedName = formatDebrisName(type)
    val cardContentDescription = if (isDiscovered) {
        stringResource(R.string.marinedex_card_cd_discovered, formattedName, entry!!.timesDetected)
    } else {
        stringResource(R.string.marinedex_card_cd_locked, formattedName)
    }
    val clickLabel = stringResource(R.string.marinedex_card_click_label, formattedName)

    Card(
        modifier = modifier
            .aspectRatio(0.75f)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
                alpha = cardAlpha
            }
            .then(
                if (isDiscovered) {
                    Modifier
                        .pressableScale()
                        .clickable(onClickLabel = clickLabel) { onNavigateToDetail(type.name) }
                } else {
                    Modifier
                }
            )
            .semantics { this.contentDescription = cardContentDescription },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDiscovered)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDiscovered) 4.dp else 1.dp,
        ),
        border = BorderStroke(
            width = if (isDiscovered) 1.5.dp else 0.5.dp,
            brush = if (isDiscovered) {
                Brush.verticalGradient(
                    colors = listOf(
                        OceanGreen.copy(alpha = 0.8f),
                        OceanBlueLight.copy(alpha = 0.4f),
                    )
                )
            } else {
                Brush.linearGradient(colors = listOf(borderColor, borderColor))
            },
        ),
    ) {
        if (isDiscovered && impact != null) {
            DiscoveredCardContent(
                type = type,
                entry = entry!!,
                riskScore = riskScore,
            )
        } else {
            LockedCardContent(type = type)
        }
    }
}

@Composable
private fun DiscoveredCardContent(
    type: DebrisType,
    entry: MarineDexEntry,
    riskScore: Int,
) {
    val isDark = isSystemInDarkTheme()
    val scoreColor = healthScoreColor(100 - (riskScore * 10).coerceAtMost(100))

    Box(modifier = Modifier.fillMaxSize()) {
        // Subtle gradient glow behind icon
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            OceanGreen.copy(alpha = if (isDark) 0.08f else 0.06f),
                            Color.Transparent,
                        ),
                    )
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Risk score dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(scoreColor, CircleShape),
                )
            }

            // Pixel art sprite
            DexSpriteImage(
                debrisType = type,
                size = 48.dp,
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Debris name
                Text(
                    text = formatDebrisName(type),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Detection count pill — "\u00d7N" keeps the × as a Unicode literal
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = OceanGreen.copy(alpha = 0.18f),
                ) {
                    Text(
                        text = "\u00d7${entry.timesDetected}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = OceanGreen,
                    )
                }
            }
        }
    }
}

@Composable
private fun LockedCardContent(type: DebrisType) {
    val isDark = isSystemInDarkTheme()

    val overlayColors = if (isDark) {
        listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.55f))
    } else {
        listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        )
    }
    val questionMarkColor = if (isDark) {
        Color.White.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = overlayColors)),
        contentAlignment = Alignment.Center,
    ) {
        // Blurred sprite behind the "?" — gives a hint of what's undiscovered
        DexSpriteImage(
            debrisType = type,
            size = 56.dp,
            modifier = Modifier
                .blur(radius = 12.dp)
                .graphicsLayer { alpha = 0.5f },
        )

        // "?" overlay on top — single character kept hardcoded per guidelines
        Text(
            text = "?",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = questionMarkColor,
        )
    }
}

// ---------------------------------------------------------------------------
// Konfetti helpers
// ---------------------------------------------------------------------------

private fun buildConfettiParties(): List<Party> {
    val emitter = Emitter(duration = 3, timeUnit = TimeUnit.SECONDS).perSecond(80)
    return listOf(
        Party(
            emitter = emitter,
            position = Position.Relative(0.0, 0.0),
            angle = 135,
            spread = 60,
        ),
        Party(
            emitter = emitter,
            position = Position.Relative(1.0, 0.0),
            angle = 45,
            spread = 60,
        ),
    )
}

// ---------------------------------------------------------------------------
// Name formatting helper
// ---------------------------------------------------------------------------

private fun formatDebrisName(type: DebrisType): String =
    type.name.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
