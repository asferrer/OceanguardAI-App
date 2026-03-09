package com.oceanguard.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.collection.Achievement
import com.oceanguard.ai.data.collection.AchievementCategory
import com.oceanguard.ai.data.collection.AchievementDef
import com.oceanguard.ai.data.collection.ALL_ACHIEVEMENTS
import com.oceanguard.ai.data.collection.CollectionRepository
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
import com.oceanguard.ai.ui.theme.OceanGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val TOTAL_ACHIEVEMENTS = 21

// ---------------------------------------------------------------------------
// Screen entry point
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    collectionRepository: CollectionRepository,
    onNavigateBack: () -> Unit,
    navController: NavController? = null,
) {
    val achievements by collectionRepository.allAchievements
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unlockedCount by collectionRepository.unlockedAchievementCount
        .collectAsStateWithLifecycle(initialValue = 0)

    val achContext = LocalContext.current
    val app = remember(achContext) { achContext.applicationContext as OceanGuardApp }
    val achScope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.ACHIEVEMENTS)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.ACHIEVEMENTS))
        .collectAsStateWithLifecycle(initialValue = true)

    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.achievements_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.achievements_cd_go_back),
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
        AchievementsContent(
            achievements = achievements,
            unlockedCount = unlockedCount,
            modifier = Modifier.padding(paddingValues),
            boundsMap = boundsMap,
        )
    }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                achScope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.ACHIEVEMENTS)
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
                achScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "achievements",
                onContinue = {
                    showTransitionDialog = false
                    navController?.navigate("map") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    achScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    } // end Box
}

// ---------------------------------------------------------------------------
// Main scrollable content
// ---------------------------------------------------------------------------

@Composable
private fun AchievementsContent(
    achievements: List<Achievement>,
    unlockedCount: Int,
    modifier: Modifier = Modifier,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect> = mutableMapOf(),
) {
    val achievementMap = remember(achievements) { achievements.associateBy { it.id } }
    val expandedCategories = remember {
        mutableStateMapOf<AchievementCategory, Boolean>().apply {
            AchievementCategory.entries.forEach { put(it, true) }
        }
    }

    val groupedDefs = remember {
        AchievementCategory.entries.associateWith { category ->
            ALL_ACHIEVEMENTS.filter { it.category == category }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box(modifier = Modifier.spotlightTarget("achievements_progress", boundsMap)) {
                ProgressHeader(unlockedCount = unlockedCount)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        AchievementCategory.entries.forEachIndexed { catIndex, category ->
            val defs = groupedDefs[category] ?: return@forEachIndexed
            val isExpanded = expandedCategories[category] ?: true
            val isFirstCategory = catIndex == 0

            item(key = category.name) {
                val categoryModifier = if (isFirstCategory) {
                    Modifier.spotlightTarget("achievements_list", boundsMap)
                } else {
                    Modifier
                }
                Box(modifier = categoryModifier) {
                    CategoryHeader(
                        category = category,
                        defs = defs,
                        achievementMap = achievementMap,
                        isExpanded = isExpanded,
                        onToggle = { expandedCategories[category] = !isExpanded },
                    )
                }
            }

            if (isExpanded) {
                itemsIndexed(
                    items = defs,
                    key = { _, def -> def.id },
                ) { index, def ->
                    val achievement = achievementMap[def.id]
                    AnimatedVisibility(
                        visible = true,
                        enter = expandVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                    ) {
                        AchievementCard(
                            def = def,
                            achievement = achievement,
                            index = index,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Progress header
// ---------------------------------------------------------------------------

@Composable
private fun ProgressHeader(unlockedCount: Int) {
    val isDark = isSystemInDarkTheme()
    val heroTextColor = if (isDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val heroSubtextColor = if (isDark) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    val trackBgColor = if (isDark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

    val animatedProgress by animateFloatAsState(
        targetValue = unlockedCount / TOTAL_ACHIEVEMENTS.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "headerProgress",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(GradientDeepStart, GradientDeepMid, GradientDeepEnd)
                    } else {
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                        )
                    },
                ),
            )
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.achievements_progress_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = heroSubtextColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.achievements_progress_count, unlockedCount, TOTAL_ACHIEVEMENTS),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = heroTextColor,
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = OceanGreen,
                    trackColor = trackBgColor,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.achievements_percent_complete, "%.0f".format(animatedProgress * 100)),
                    style = MaterialTheme.typography.bodySmall,
                    color = OceanGreen,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            CircularProgressRing(
                progress = animatedProgress,
                unlocked = unlockedCount,
            )
        }
    }
}

@Composable
private fun CircularProgressRing(
    progress: Float,
    unlocked: Int,
) {
    val isDark = isSystemInDarkTheme()
    val ringBg = if (isDark) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    val ringTrackColor = if (isDark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val subtextColor = if (isDark) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)

    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(ringBg)
            .border(
                width = 3.dp,
                brush = Brush.sweepGradient(
                    0f to ringTrackColor,
                    progress to OceanGreen,
                    progress to ringTrackColor,
                    1f to ringTrackColor,
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$unlocked",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = OceanGreen,
            )
            Text(
                text = "/ $TOTAL_ACHIEVEMENTS",
                style = MaterialTheme.typography.labelSmall,
                color = subtextColor,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Category header
// ---------------------------------------------------------------------------

@Composable
private fun CategoryHeader(
    category: AchievementCategory,
    defs: List<AchievementDef>,
    achievementMap: Map<String, Achievement>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val categoryUnlocked = defs.count { def ->
        (achievementMap[def.id]?.unlockedAt ?: 0L) > 0L
    }
    val categoryLabel = stringResource(category.labelRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = categoryLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = TextUnit(1f, TextUnitType.Sp),
            )
            Text(
                text = stringResource(R.string.achievements_category_count, categoryUnlocked, defs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (isExpanded) "Collapse $categoryLabel" else "Expand $categoryLabel",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Achievement card
// ---------------------------------------------------------------------------

@Composable
private fun AchievementCard(
    def: AchievementDef,
    achievement: Achievement?,
    index: Int,
) {
    val isUnlocked = (achievement?.unlockedAt ?: 0L) > 0L

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(def.id) {
        kotlinx.coroutines.delay(index * 50L)
        visible = true
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "cardAlpha_${def.id}",
    )
    val animatedTranslationY by animateFloatAsState(
        targetValue = if (visible) 0f else 24f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "cardTranslationY_${def.id}",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer {
                alpha = animatedAlpha
                translationY = animatedTranslationY
            }
            .pressableScale(),
    ) {
        if (isUnlocked) {
            UnlockedAchievementCard(
                def = def,
                achievement = achievement!!,
            )
        } else {
            LockedAchievementCard(
                def = def,
                achievement = achievement,
            )
        }
    }
}

@Composable
private fun UnlockedAchievementCard(
    def: AchievementDef,
    achievement: Achievement,
) {
    val dateString = remember(achievement.unlockedAt) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            .format(Date(achievement.unlockedAt))
    }
    val defName = stringResource(def.nameRes)
    val defDescription = stringResource(def.descriptionRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        // OceanGreen left accent bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(88.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(OceanGreen, OceanGreen.copy(alpha = 0.5f)),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Unlocked icon with glow backdrop
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(OceanGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = def.icon,
                    contentDescription = defName,
                    tint = OceanGreen,
                    modifier = Modifier.size(26.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = defName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = defDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.achievements_unlocked_date, dateString),
                    style = MaterialTheme.typography.labelSmall,
                    color = OceanGreen,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LockedAchievementCard(
    def: AchievementDef,
    achievement: Achievement?,
) {
    val progress = achievement?.progress ?: 0
    val target = def.target
    val progressFraction = if (target > 0) progress / target.toFloat() else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "progress_${def.id}",
    )
    val defName = stringResource(def.nameRes)
    val defDescription = stringResource(def.descriptionRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .graphicsLayer { alpha = 0.6f },
    ) {
        // Dimmed left accent bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(100.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Locked icon — grayed out
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = def.icon,
                    contentDescription = defName,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = defName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = defDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$progress / $target",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}
