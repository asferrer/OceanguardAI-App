package com.oceanguard.ai.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.inference.VlmProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val DEV_MODE_TAP_TARGET = 21

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val settings = viewModel.settingsRepository
    val scope = rememberCoroutineScope()

    // User-facing settings
    val vlmEnabled by settings.vlmEnabled.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_VLM_ENABLED
    )
    val confirmCapture by settings.confirmCapture.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_CONFIRM_CAPTURE
    )
    val language by settings.language.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_LANGUAGE
    )
    val reportAudience by settings.reportAudience.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_REPORT_AUDIENCE
    )
    val darkMode by settings.darkMode.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_DARK_MODE
    )
    val devModeEnabled by settings.devModeEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )

    // Developer-only settings
    val confidenceThreshold by settings.confidenceThreshold.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_CONFIDENCE_THRESHOLD
    )

    // Research contribution settings
    val contributeConsentGiven by settings.contributeConsentGiven.collectAsStateWithLifecycle(
        initialValue = false
    )
    val contributeWifiOnly by settings.contributeWifiOnly.collectAsStateWithLifecycle(
        initialValue = true
    )
    val contributePending by app.contributionRepository.getPendingCountFlow()
        .collectAsStateWithLifecycle(initialValue = 0)
    val contributeDone by app.contributionRepository.getDoneCountFlow()
        .collectAsStateWithLifecycle(initialValue = 0)
    val contributeFailed by app.contributionRepository.getFailedCountFlow()
        .collectAsStateWithLifecycle(initialValue = 0)
    var showContributeConsentDialog by remember { mutableStateOf(false) }

    val settingsScrollState = rememberScrollState()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.SETTINGS)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.SETTINGS))
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
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(settingsScrollState, enabled = !tourController.isActive)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------------------------------------------------------------
            // Detection Settings (user-facing)
            // ---------------------------------------------------------------
            SettingsSection(
                title = stringResource(R.string.settings_section_detection),
                icon = Icons.Filled.Tune,
                modifier = Modifier.spotlightTarget("settings_confidence", boundsMap),
            ) {
                // TODO: Deep Analysis toggle hidden until vision pipeline is implemented
                // (LlamaVisionEngine + mmproj wiring to DetectionOrchestrator)

                // Confirm capture toggle
                SettingsToggleRow(
                    title = stringResource(R.string.settings_label_confirm_capture),
                    description = stringResource(R.string.settings_desc_confirm_capture),
                    checked = confirmCapture,
                    onCheckedChange = { scope.launch { settings.setConfirmCapture(it) } },
                )
            }

            // ---------------------------------------------------------------
            // App Language + Report Language (grouped under single spotlight)
            // ---------------------------------------------------------------
            Column(
                modifier = Modifier.spotlightTarget("settings_language", boundsMap),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppLanguageSection(settings = settings, scope = scope)
                SettingsSection(
                    title = stringResource(R.string.settings_section_reports),
                    icon = Icons.Filled.Language,
                ) {
                Text(
                    text = stringResource(R.string.settings_label_report_language),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.settings_desc_report_language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LanguageDropdown(
                    options = SettingsRepository.SUPPORTED_LANGUAGES,
                    selected = language,
                    onSelect = { scope.launch { settings.setLanguage(it) } },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_label_report_audience),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.settings_desc_report_audience),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val audienceOptions = mapOf(
                    "scientific" to stringResource(R.string.audience_scientific),
                    "ngo"        to stringResource(R.string.audience_ngo),
                    "citizen"    to stringResource(R.string.audience_citizen),
                )
                LanguageDropdown(
                    options = audienceOptions,
                    selected = reportAudience,
                    onSelect = { scope.launch { settings.setReportAudience(it) } },
                )
            }
            } // end settings_language spotlight Column

            // ---------------------------------------------------------------
            // Display Settings
            // ---------------------------------------------------------------
            SettingsSection(
                title = stringResource(R.string.settings_section_display),
                icon = Icons.Filled.DarkMode,
                modifier = Modifier.spotlightTarget("settings_theme", boundsMap),
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_label_dark_mode),
                    description = stringResource(R.string.settings_desc_dark_mode),
                    checked = darkMode,
                    onCheckedChange = { scope.launch { settings.setDarkMode(it) } },
                )
            }

            // ---------------------------------------------------------------
            // Data Management
            // ---------------------------------------------------------------
            SettingsSection(
                title = stringResource(R.string.settings_section_data),
                icon = Icons.Filled.DeleteForever,
                modifier = Modifier.spotlightTarget("settings_data", boundsMap),
            ) {
                DataManagementContent(viewModel = viewModel)
            }

            // ---------------------------------------------------------------
            // Guided Tours
            // ---------------------------------------------------------------
            SettingsSection(
                title = stringResource(R.string.settings_section_guided_tours),
                icon = Icons.Filled.Explore,
                modifier = Modifier.spotlightTarget("settings_tours", boundsMap),
            ) {
                Text(
                    text = stringResource(R.string.settings_desc_guided_tours),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        app.launchTourReset()
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_btn_replay_tours))
                }
            }

            // ---------------------------------------------------------------
            // Developer Settings (hidden until easter egg is activated)
            // ---------------------------------------------------------------
            if (devModeEnabled) {
                DeveloperSettingsSection(
                    settings = settings,
                    scope = scope,
                    vlmModelManager = app.vlmModelManager,
                    onDownloadTier = { app.launchVlmDownload(it) },
                )
            }

            // ---------------------------------------------------------------
            // Research Contribution
            // ---------------------------------------------------------------
            SettingsSection(
                title = stringResource(R.string.contribute_section_title),
                icon = Icons.Filled.CloudUpload,
                modifier = Modifier.spotlightTarget("settings_contribution", boundsMap),
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.contribute_toggle_label),
                    description = if (contributeConsentGiven)
                        stringResource(R.string.contribute_consent_given_desc)
                    else
                        stringResource(R.string.contribute_toggle_desc),
                    checked = contributeConsentGiven,
                    onCheckedChange = { enabled ->
                        if (enabled && !contributeConsentGiven) {
                            showContributeConsentDialog = true
                        } else {
                            scope.launch {
                                settings.setContributeConsentGiven(enabled)
                                if (!enabled) app.contributionRepository.clearPendingQueue()
                            }
                        }
                    },
                )
                AnimatedVisibility(visible = contributeConsentGiven) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SettingsToggleRow(
                            title = stringResource(R.string.contribute_wifi_only_label),
                            description = stringResource(R.string.contribute_wifi_only_desc),
                            checked = contributeWifiOnly,
                            onCheckedChange = { scope.launch { settings.setContributeWifiOnly(it) } },
                        )
                        // Upload status summary
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    if (contributeDone > 0 || contributePending > 0 || contributeFailed > 0) {
                                        Text(
                                            text = stringResource(R.string.contribute_stats_uploaded, contributeDone),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (contributePending > 0) {
                                            Text(
                                                text = stringResource(R.string.contribute_stats_pending, contributePending),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (contributeFailed > 0) {
                                            Text(
                                                text = stringResource(R.string.contribute_stats_failed, contributeFailed),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = stringResource(R.string.contribute_all_synced),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (contributePending > 0) {
                                    TextButton(
                                        onClick = { app.contributionRepository.scheduleImmediateUpload() },
                                    ) {
                                        Text(stringResource(R.string.contribute_upload_now_btn))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showContributeConsentDialog) {
                AlertDialog(
                    onDismissRequest = { showContributeConsentDialog = false },
                    title = { Text(stringResource(R.string.contribute_consent_title)) },
                    text = { Text(stringResource(R.string.contribute_consent_body)) },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch { settings.setContributeConsentGiven(true) }
                            showContributeConsentDialog = false
                        }) { Text(stringResource(R.string.contribute_consent_accept)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showContributeConsentDialog = false }) {
                            Text(stringResource(R.string.contribute_consent_dismiss))
                        }
                    },
                )
            }

            // ---------------------------------------------------------------
            // About
            // ---------------------------------------------------------------
            AboutSection(
                devModeEnabled = devModeEnabled,
                onDevModeActivated = { scope.launch { settings.setDevModeEnabled(true) } },
                onDevModeDeactivated = { scope.launch { settings.setDevModeEnabled(false) } },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                scope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.SETTINGS)
                    )
                    if (guidedTourActive) {
                        showTransitionDialog = true
                    } else {
                        app.launchDemoCleanupIfComplete()
                    }
                }
            },
            scrollState = settingsScrollState,
            isGuidedTour = guidedTourActive,
            onSkipTutorial = {
                scope.launch {
                    app.settingsRepository.markAllGuidedToursComplete()
                }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "settings",
                onContinue = {
                    showTransitionDialog = false
                    scope.launch {
                        app.achievementChecker.checkTutorialComplete()
                        app.settingsRepository.setGuidedTourActive(false)
                    }
                    app.launchDemoCleanup()
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    scope.launch {
                        app.settingsRepository.markAllGuidedToursComplete()
                    }
                    app.launchDemoCleanup()
                },
            )
        }
    } // end Box
}

// ---------------------------------------------------------------------------
// Reusable toggle row
// ---------------------------------------------------------------------------

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------------------------------------------------------------------
// Data Management
// ---------------------------------------------------------------------------

@Composable
private fun DataManagementContent(viewModel: MainViewModel) {
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_dialog_clear_title)) },
            text = { Text(stringResource(R.string.settings_dialog_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearAllData(); showClearDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_dialog_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Text(stringResource(R.string.settings_label_reset_all_data), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Text(stringResource(R.string.settings_desc_reset_all_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedButton(
        onClick = { showClearDialog = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.settings_btn_reset_all_data))
    }
}

// ---------------------------------------------------------------------------
// Developer settings (hidden behind easter egg)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperSettingsSection(
    settings: SettingsRepository,
    scope: CoroutineScope,
    vlmModelManager: VlmModelManager,
    onDownloadTier: (TextModelTier) -> Unit,
) {
    val confidenceThreshold by settings.confidenceThreshold.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_CONFIDENCE_THRESHOLD
    )
    val detectorPrecision by settings.detectorPrecision.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_DETECTOR_PRECISION
    )
    val vlmModelTierKey by settings.vlmModelTier.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_VLM_MODEL_TIER
    )
    val downloadState by vlmModelManager.downloadState.collectAsStateWithLifecycle(
        initialValue = VlmDownloadState.Idle
    )

    SettingsSection(
        title = stringResource(R.string.settings_section_developer),
        icon = Icons.Filled.Build,
    ) {
        // Confidence threshold slider
        Text(
            text = stringResource(R.string.settings_label_confidence_threshold),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.settings_desc_confidence_threshold),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = confidenceThreshold,
                onValueChange = { scope.launch { settings.setConfidenceThreshold(it) } },
                valueRange = 0f..1f,
                steps = 19,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = "%.0f%%".format(confidenceThreshold * 100),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Detector precision selector
        var precisionExpanded by remember { mutableStateOf(false) }
        var showRestartHint by remember { mutableStateOf(false) }
        val precisionLabel = SettingsRepository.DETECTOR_PRECISIONS[detectorPrecision]
            ?: detectorPrecision

        Text(
            text = stringResource(R.string.settings_label_detector_precision),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.settings_desc_detector_precision),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = precisionExpanded,
            onExpandedChange = { precisionExpanded = it },
        ) {
            OutlinedTextField(
                value = precisionLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = precisionExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(12.dp),
            )
            ExposedDropdownMenu(
                expanded = precisionExpanded,
                onDismissRequest = { precisionExpanded = false },
            ) {
                SettingsRepository.DETECTOR_PRECISIONS.forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(text = label) },
                        onClick = {
                            scope.launch { settings.setDetectorPrecision(key) }
                            precisionExpanded = false
                            if (key != detectorPrecision) showRestartHint = true
                        },
                    )
                }
            }
        }

        if (showRestartHint) {
            Text(
                text = stringResource(R.string.settings_hint_restart),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // VLM provider selector (Qwen 3.5 / Gemma 4)
        val vlmProviderKey by settings.vlmProvider.collectAsStateWithLifecycle(
            initialValue = SettingsRepository.DEFAULT_VLM_PROVIDER
        )
        var providerExpanded by remember { mutableStateOf(false) }
        val currentProvider = VlmProvider.fromKey(vlmProviderKey)

        Text(
            text = stringResource(R.string.settings_label_vlm_provider),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.settings_desc_vlm_provider),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = it },
        ) {
            OutlinedTextField(
                value = currentProvider.displayLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(12.dp),
            )
            ExposedDropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false },
            ) {
                VlmProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayLabel) },
                        onClick = {
                            scope.launch {
                                settings.setVlmProvider(provider.key)
                                // Auto-select best tier for the new provider
                                val bestTier = TextModelTier.forProvider(provider).firstOrNull()
                                if (bestTier != null) {
                                    settings.setVlmModelTier(bestTier.name.lowercase())
                                }
                            }
                            providerExpanded = false
                        },
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // VLM model tier selector (filtered by selected provider)
        var tierExpanded by remember { mutableStateOf(false) }
        val currentTier = TextModelTier.fromKey(vlmModelTierKey)
        val providerTiers = TextModelTier.forProvider(currentProvider)

        Text(
            text = stringResource(R.string.settings_label_vlm_tier),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.settings_desc_vlm_tier),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        val currentAvailable = vlmModelManager.isModelAvailable(currentTier)
        val notDownloaded = stringResource(R.string.settings_vlm_not_downloaded)
        val isDownloading = downloadState is VlmDownloadState.Downloading ||
            downloadState is VlmDownloadState.Preparing ||
            downloadState is VlmDownloadState.Installing

        ExposedDropdownMenuBox(
            expanded = tierExpanded,
            onExpandedChange = { tierExpanded = it },
        ) {
            OutlinedTextField(
                value = "${currentTier.displayName} — ${currentTier.sizeLabel}" +
                    if (!currentAvailable) " ($notDownloaded)" else "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tierExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(12.dp),
            )
            ExposedDropdownMenu(
                expanded = tierExpanded,
                onDismissRequest = { tierExpanded = false },
            ) {
                providerTiers.forEach { tier ->
                    val tierAvailable = vlmModelManager.isModelAvailable(tier)
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${tier.displayName} — ${tier.sizeLabel}")
                                if (!tierAvailable) {
                                    Text(
                                        text = notDownloaded,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            scope.launch { settings.setVlmModelTier(tier.name.lowercase()) }
                            tierExpanded = false
                        },
                    )
                }
            }
        }

        if (!currentAvailable) {
            Spacer(modifier = Modifier.height(4.dp))
            if (isDownloading) {
                val progress = (downloadState as? VlmDownloadState.Downloading)?.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%  ${currentTier.sizeLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else {
                OutlinedButton(
                    onClick = { onDownloadTier(currentTier) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Download ${currentTier.displayName} (${currentTier.sizeLabel})")
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Gemma 4 mmproj download (vision support)
        if (currentProvider == VlmProvider.GEMMA4 &&
            vlmModelManager.isModelAvailable(TextModelTier.GEMMA4_E2B) &&
            !vlmModelManager.isGemma4MmprojAvailable()
        ) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(R.string.settings_label_gemma4_mmproj),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.settings_desc_gemma4_mmproj),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (isDownloading) {
                val progress = (downloadState as? VlmDownloadState.Downloading)?.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            vlmModelManager.downloadGemma4Mmproj()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Download mmproj (${vlmModelManager.getGemma4MmprojSizeLabel()})")
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Technical info
        val techLabel = if (currentProvider == VlmProvider.GEMMA4) {
            "RT-DETRv2 + Gemma 4 (llama.cpp)"
        } else {
            "RT-DETRv2 + Qwen3.5 (llama.cpp)"
        }
        Text(
            text = techLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_about_powered_by),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// About section with easter egg
// ---------------------------------------------------------------------------

@Composable
private fun AboutSection(
    devModeEnabled: Boolean,
    onDevModeActivated: () -> Unit,
    onDevModeDeactivated: () -> Unit,
) {
    val context = LocalContext.current
    var tapCount by remember { mutableIntStateOf(0) }

    SettingsSection(title = stringResource(R.string.settings_section_about), icon = Icons.Filled.Info) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = stringResource(R.string.settings_cd_logo),
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) {
                        tapCount++
                        val remaining = DEV_MODE_TAP_TARGET - tapCount
                        when {
                            tapCount >= DEV_MODE_TAP_TARGET && !devModeEnabled -> {
                                onDevModeActivated()
                                tapCount = 0
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_dev_mode_activated),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            remaining in 1..5 -> {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_dev_mode_countdown, remaining),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
            )
            Column {
                Text(
                    text = "OceanGuard AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "v${com.oceanguard.ai.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(R.string.settings_about_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Developer mode toggle (only visible when already activated)
        if (devModeEnabled) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            OutlinedButton(
                onClick = onDevModeDeactivated,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.settings_dev_mode_disable))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared components
// ---------------------------------------------------------------------------

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
            )
        }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    options: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = options[selected] ?: selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = displayName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val APP_LANGUAGES = mapOf(
    "" to "System Default",
    "en" to "English",
    "es" to "Espa\u00f1ol",
    "fr" to "Fran\u00e7ais",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Portugu\u00eas",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLanguageSection(
    settings: SettingsRepository,
    scope: CoroutineScope,
) {
    val appLanguage by settings.appLanguage.collectAsStateWithLifecycle(initialValue = "")

    SettingsSection(title = stringResource(R.string.settings_section_app_language), icon = Icons.Filled.Language) {
        Text(
            text = stringResource(R.string.settings_label_interface_language),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.settings_desc_interface_language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        var expanded by remember { mutableStateOf(false) }
        val displayName = APP_LANGUAGES[appLanguage] ?: appLanguage

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(12.dp),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                APP_LANGUAGES.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            scope.launch { settings.setAppLanguage(code) }
                            val locales = if (code.isEmpty()) {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(code)
                            }
                            AppCompatDelegate.setApplicationLocales(locales)
                        },
                    )
                }
            }
        }
    }
}
