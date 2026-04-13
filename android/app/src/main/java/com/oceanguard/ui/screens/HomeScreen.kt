package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.oceanguard.ai.ModelLoadingState
import com.oceanguard.ai.ModelStatus
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionStatistics
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.UiState
import com.oceanguard.ai.ui.components.AnimatedCounter
import com.oceanguard.ai.ui.components.DebrisDistributionChart
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.HealthTrendChart
import com.oceanguard.ai.ui.components.OceanGradientHeader
import com.oceanguard.ai.ui.components.ShimmerCard
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.theme.CTAGlow
import com.oceanguard.ai.ui.theme.GradientCTAEnd
import com.oceanguard.ai.ui.theme.GradientCTAStart
import com.oceanguard.ai.ui.theme.OceanGreen
import com.oceanguard.ai.ui.theme.OceanGreenLight
import com.oceanguard.ai.ui.theme.healthScoreColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.rememberCoroutineScope
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.EnvironmentalImpact
import com.oceanguard.ai.ui.components.DexSpriteImage
import com.oceanguard.ai.data.collection.ACHIEVEMENT_DEF_MAP
import com.oceanguard.ai.data.collection.MarineDexEntry
import com.oceanguard.ai.data.collection.Achievement
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.utils.UpdateInfo
import kotlinx.coroutines.launch

/**
 * HomeScreen — landing screen of OceanGuard AI.
 *
 * Features:
 *   - Animated gradient hero header with wave effects
 *   - Spring-animated action buttons
 *   - Glass-card statistics with animated counters
 *   - Charts for debris distribution and health trends
 *
 * All interactive targets are >= 56 dp tall to accommodate gloved fingers.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel,
    dexEntries: List<MarineDexEntry> = emptyList(),
    discoveredCount: Int = 0,
    latestAchievement: Achievement? = null,
) {
    // Request location permission once on first composition so GPS data
    // is available when saving sessions from gallery picks.
    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    )
    LaunchedEffect(Unit) {
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val totalSessions by viewModel.totalSessions.collectAsStateWithLifecycle(initialValue = 0)
    val avgHealthScore by viewModel.avgHealthScore.collectAsStateWithLifecycle(initialValue = null)
    val statistics by viewModel.statistics.collectAsStateWithLifecycle(
        initialValue = DetectionStatistics(0, 0, 0f, emptyMap(), emptyList(), null)
    )
    val allSessions by viewModel.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())

    val homeContext = LocalContext.current
    val app = remember(homeContext) { homeContext.applicationContext as OceanGuardApp }
    val scope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.HOME)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.HOME))
        .collectAsStateWithLifecycle(initialValue = true)

    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    // Update checker
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        updateInfo = app.updateChecker.check()
    }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    // Gallery picker — single image for quick scan
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.analyzeImage(uri)
            navController.navigate("results")
        }
    }

    // Multi-media picker — gallery, accepts images and videos (batch).
    val mediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.analyzeMedia(homeContext, uris, navController)
        }
    }

    // File picker — file browser, accepts images and videos (batch).
    val filesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.analyzeMedia(homeContext, uris, navController)
        }
    }

    // Gemma 4 download prompt — shown when the user tries to use detection
    // with Gemma 4 selected but the model is not yet downloaded.
    var showGemma4DownloadDialog by remember { mutableStateOf(false) }
    var pendingNavAfterDownload by remember { mutableStateOf<String?>(null) }

    val needsGemma4Download = remember(app) {
        {
            val mode = app.settingsRepository.getDetectorModeSync()
            mode == DetectorType.GEMMA4_VISION.key &&
                !app.vlmModelManager.isGemma4VisionAvailable()
        }
    }

    // Dropdown state for the scan source picker
    var showScanMenu by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ----------------------------------------------------------------
            // Hero section — animated gradient with wave overlay
            // ----------------------------------------------------------------
            OceanGradientHeader(
                title = "OceanGuard AI",
                subtitle = stringResource(R.string.home_header_subtitle),
                modifier = Modifier.graphicsLayer { translationY = scrollState.value * 0.3f },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Content with horizontal padding
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ----------------------------------------------------------------
                // Model status indicator
                // ----------------------------------------------------------------
                val modelState by app.modelLoadingState.collectAsStateWithLifecycle()
                val detectorModeKey by app.settingsRepository.detectorMode.collectAsStateWithLifecycle(
                    initialValue = SettingsRepository.DEFAULT_DETECTOR_MODE
                )
                Box(modifier = Modifier.spotlightTarget("home_model_status", boundsMap)) {
                    ModelStatusIndicator(modelState = modelState, detectorModeKey = detectorModeKey)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ----------------------------------------------------------------
                // Action grid 2×2 with spring entry animations
                // ----------------------------------------------------------------

                Box(modifier = Modifier.spotlightTarget("home_scan_button", boundsMap)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AnimatedActionButton(
                            delay = 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            ActionTile(
                                icon = Icons.Filled.CameraAlt,
                                label = stringResource(R.string.home_btn_take_photo),
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color(0xFF0A0E1A),
                                onClick = {
                                    if (needsGemma4Download()) {
                                        pendingNavAfterDownload = "camera"
                                        showGemma4DownloadDialog = true
                                    } else {
                                        navController.navigate("camera")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                useGradient = true,
                            )
                        }
                        AnimatedActionButton(
                            delay = 50,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box {
                                ActionTile(
                                    icon = Icons.Filled.PhotoLibrary,
                                    label = stringResource(R.string.home_btn_select_gallery),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = GradientCTAStart,
                                    onClick = { showScanMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                DropdownMenu(
                                    expanded = showScanMenu,
                                    onDismissRequest = { showScanMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_btn_gallery_multi)) },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Photo, contentDescription = null)
                                        },
                                        onClick = {
                                            showScanMenu = false
                                            if (needsGemma4Download()) {
                                                pendingNavAfterDownload = null
                                                showGemma4DownloadDialog = true
                                            } else {
                                                mediaLauncher.launch(
                                                    PickVisualMediaRequest(
                                                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                                    )
                                                )
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_btn_files_multi)) },
                                        leadingIcon = {
                                            Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                        },
                                        onClick = {
                                            showScanMenu = false
                                            if (needsGemma4Download()) {
                                                pendingNavAfterDownload = null
                                                showGemma4DownloadDialog = true
                                            } else {
                                                filesLauncher.launch(arrayOf("image/*", "video/*"))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ----------------------------------------------------------------
                // Stats card — glassmorphism with animated counters
                // ----------------------------------------------------------------
                Box(modifier = Modifier.spotlightTarget("home_stats", boundsMap)) {
                    SessionStatsCard(
                        totalSessions = totalSessions,
                        avgHealthScore = avgHealthScore,
                        totalDebris = statistics.totalDebrisDetected,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ----------------------------------------------------------------
                // MarineDex preview card (staggered entry)
                // ----------------------------------------------------------------
                StaggeredEntry(delayMs = 100) {
                    Box(modifier = Modifier.spotlightTarget("home_marinedex", boundsMap)) {
                        MarineDexPreviewCard(
                            dexEntries = dexEntries,
                            discoveredCount = discoveredCount,
                            onClick = { navController.navigate("marinedex") },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ----------------------------------------------------------------
                // Latest achievement card (staggered entry)
                // ----------------------------------------------------------------
                if (latestAchievement != null && latestAchievement.unlockedAt > 0) {
                    StaggeredEntry(delayMs = 200) {
                        LatestAchievementCard(
                            achievement = latestAchievement,
                            onClick = { navController.navigate("achievements") },
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ----------------------------------------------------------------
                // Debris distribution chart (staggered entry)
                // ----------------------------------------------------------------
                if (statistics.materialBreakdown.isNotEmpty()) {
                    StaggeredEntry(delayMs = 300) {
                        DebrisDistributionChart(
                            materialBreakdown = statistics.materialBreakdown,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ----------------------------------------------------------------
                // Health trend chart (staggered entry)
                // ----------------------------------------------------------------
                if (allSessions.isNotEmpty()) {
                    StaggeredEntry(delayMs = 400) {
                        HealthTrendChart(
                            sessions = allSessions,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                scope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.HOME)
                    )
                    if (guidedTourActive) {
                        showTransitionDialog = true
                    } else {
                        app.launchDemoCleanupIfComplete()
                    }
                }
            },
            scrollState = scrollState,
            isGuidedTour = guidedTourActive,
            onSkipTutorial = {
                scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "home",
                onContinue = {
                    showTransitionDialog = false
                    navController.navigate("marinedex")
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }

        updateInfo?.let { info ->
            UpdateAvailableDialog(
                info = info,
                onDownload = {
                    app.updateChecker.downloadApk(homeContext, info)
                    updateInfo = null
                },
                onSkip = {
                    scope.launch { app.settingsRepository.setSkippedVersion(info.versionName) }
                    updateInfo = null
                },
                onDismiss = { updateInfo = null },
            )
        }
        // Gemma 4 model download dialog
        if (showGemma4DownloadDialog) {
            AlertDialog(
                onDismissRequest = { showGemma4DownloadDialog = false },
                title = { Text(stringResource(R.string.vlm_download_dialog_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.vlm_download_dialog_message,
                            TextModelTier.GEMMA4_E2B.sizeLabel,
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showGemma4DownloadDialog = false
                        app.launchVlmDownload(TextModelTier.GEMMA4_E2B)
                        // Navigate anyway -- the model will download in the background
                        pendingNavAfterDownload?.let { navController.navigate(it) }
                        pendingNavAfterDownload = null
                    }) {
                        Text(stringResource(R.string.vlm_download_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showGemma4DownloadDialog = false
                        pendingNavAfterDownload = null
                    }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    } // end Box
}

@Composable
private fun UpdateAvailableDialog(
    info: UpdateInfo,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.update_new_version, info.versionName),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (info.releaseNotes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = info.releaseNotes.take(500),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.update_data_preserved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.update_download))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.update_skip_version))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later))
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Spring-animated button wrapper
// ---------------------------------------------------------------------------

@Composable
private fun AnimatedActionButton(
    delay: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delay.toLong())
        isVisible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "btnScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300, delayMillis = delay),
        label = "btnAlpha",
    )

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Action tile for the 2×2 grid
// ---------------------------------------------------------------------------

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    useGradient: Boolean = false,
) {
    if (useGradient) {
        GradientActionTile(
            icon = icon,
            label = label,
            contentColor = contentColor,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = modifier
                .pressableScale()
                .height(88.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor.copy(alpha = 0.38f),
                disabledContentColor = contentColor.copy(alpha = 0.38f),
            ),
            enabled = enabled,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * CTA-style action tile with a cyan→emerald gradient background and glow,
 * matching the landing page button aesthetic.
 */
@Composable
private fun GradientActionTile(
    icon: ImageVector,
    label: String,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(GradientCTAStart, GradientCTAEnd),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .pressableScale()
            .height(88.dp)
            .drawBehind {
                // Glow shadow behind the button (cyan tint)
                drawRoundRect(
                    color = CTAGlow,
                    topLeft = Offset(4.dp.toPx(), 6.dp.toPx()),
                    size = Size(size.width - 8.dp.toPx(), size.height - 4.dp.toPx()),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        enabled = enabled,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = contentColor,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ModelStatusIndicator — shows real loading / warm-up / ready state
// ---------------------------------------------------------------------------

@Composable
private fun ModelStatusIndicator(
    modelState: ModelLoadingState,
    detectorModeKey: String = SettingsRepository.DEFAULT_DETECTOR_MODE,
) {
    val isGemma4 = detectorModeKey == DetectorType.GEMMA4_VISION.key
    val detectorStatus = if (isGemma4) modelState.gemma4Vision else modelState.rtdetr
    val detectorReady = detectorStatus == ModelStatus.Ready

    val readyText = stringResource(R.string.home_model_ready)
    val detectorName = if (isGemma4) "Gemma 4" else "AI Detection"

    // Glass style matching landing page: rgba(15,23,42,0.5) bg + rgba(148,163,184,0.1) border
    val glassColor = Color(0x800F172A)
    val glassBorderColor = Color(0x1A94A3B8)

    if (detectorReady) {
        ReadyBanner(text = readyText)
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, glassBorderColor, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = glassColor,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModelStatusRow(name = detectorName, status = detectorStatus)
            }
        }
    }
}

@Composable
private fun ReadyBanner(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0x1A94A3B8), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = Color(0x800F172A),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            ReadyBannerContent(text = text)
        }
    }
}

@Composable
private fun ReadyBannerContent(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotPulse",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulseAlpha }
                .background(
                    color = OceanGreenLight,
                    shape = RoundedCornerShape(4.dp),
                ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModelStatusRow(name: String, status: ModelStatus) {
    val loadingText = stringResource(R.string.home_model_loading)
    val warmingUpText = stringResource(R.string.home_model_warming_up)
    val readyText = stringResource(R.string.home_model_status_ready)
    val failedText = stringResource(R.string.home_model_status_failed)
    val onDemandText = stringResource(R.string.home_model_status_on_demand)
    val notDownloadedText = stringResource(R.string.home_model_status_not_downloaded)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot
        val dotColor = when (status) {
            ModelStatus.Ready -> OceanGreenLight
            ModelStatus.Error -> MaterialTheme.colorScheme.error
            ModelStatus.Standby -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.primary
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, RoundedCornerShape(4.dp)),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 100.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        when (status) {
            ModelStatus.NotLoaded -> {
                Text(
                    text = notDownloadedText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            ModelStatus.Loading -> {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = loadingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            ModelStatus.WarmingUp -> {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = warmingUpText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            ModelStatus.Ready -> {
                Text(
                    text = readyText,
                    style = MaterialTheme.typography.labelSmall,
                    color = OceanGreenLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            ModelStatus.Error -> {
                Text(
                    text = failedText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            ModelStatus.Standby -> {
                Text(
                    text = onDemandText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SessionStatsCard — glassmorphism with animated counters
// ---------------------------------------------------------------------------

@Composable
private fun SessionStatsCard(
    totalSessions: Int,
    avgHealthScore: Float?,
    totalDebris: Int = 0,
) {
    val healthColor = avgHealthScore?.let { healthScoreColor(it.toInt()) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant

    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedStatItem(
                label = stringResource(R.string.home_stat_sessions),
                value = totalSessions,
                valueColor = MaterialTheme.colorScheme.primary,
                delay = 0,
            )

            VerticalDivider(
                modifier = Modifier.height(48.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )

            AnimatedStatItem(
                label = stringResource(R.string.home_stat_debris),
                value = totalDebris,
                valueColor = MaterialTheme.colorScheme.error,
                delay = 150,
            )

            VerticalDivider(
                modifier = Modifier.height(48.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )

            if (avgHealthScore != null) {
                AnimatedStatItem(
                    label = stringResource(R.string.home_stat_avg_health),
                    value = avgHealthScore.toInt(),
                    valueColor = healthColor,
                    delay = 300,
                )
            } else {
                StatPlaceholder(
                    label = stringResource(R.string.home_stat_avg_health),
                )
            }
        }
    }
}

@Composable
private fun AnimatedStatItem(
    label: String,
    value: Int,
    valueColor: Color,
    delay: Int = 0,
) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delay.toLong())
        isVisible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "statScale",
    )

    Column(
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedCounter(
            targetValue = value,
            color = valueColor,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatPlaceholder(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "--",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// MarineDex Preview Card
// ---------------------------------------------------------------------------

@Composable
private fun MarineDexPreviewCard(
    dexEntries: List<MarineDexEntry>,
    discoveredCount: Int,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = OceanGreen,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_marinedex_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Recent discoveries as pixel art sprite row
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val recent = dexEntries.take(5)
                    for (entry in recent) {
                        val type = try {
                            DebrisType.valueOf(entry.debrisType)
                        } catch (_: Exception) { null }
                        if (type != null) {
                            DexSpriteImage(
                                debrisType = type,
                                size = 28.dp,
                            )
                        }
                    }
                    if (dexEntries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_marinedex_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Progress bar
                LinearProgressIndicator(
                    progress = { discoveredCount / 11f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = OceanGreen,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_marinedex_progress, discoveredCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.cd_view_marinedex),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Staggered entry animation wrapper
// ---------------------------------------------------------------------------

@Composable
private fun StaggeredEntry(
    delayMs: Int,
    content: @Composable () -> Unit,
) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        isVisible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "staggerAlpha",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (isVisible) 0f else 20f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "staggerOffset",
    )

    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            translationY = offsetY
        },
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Latest Achievement Card
// ---------------------------------------------------------------------------

@Composable
private fun LatestAchievementCard(
    achievement: Achievement,
    onClick: () -> Unit,
) {
    val def = ACHIEVEMENT_DEF_MAP[achievement.id]

    GlassCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = def?.icon ?: Icons.Filled.EmojiEvents,
                contentDescription = null,
                tint = OceanGreen,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_achievement_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = def?.nameRes?.let { stringResource(it) } ?: achievement.id,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (def != null) {
                    Text(
                        text = stringResource(def.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.cd_view_achievements),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
