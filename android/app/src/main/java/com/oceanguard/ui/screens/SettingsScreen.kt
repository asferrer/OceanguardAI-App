package com.oceanguard.ai.ui.screens

import android.widget.Toast
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
                .verticalScroll(settingsScrollState)
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
                // Deep Analysis toggle
                SettingsToggleRow(
                    title = stringResource(R.string.settings_label_deep_analysis),
                    description = stringResource(R.string.settings_desc_deep_analysis),
                    checked = vlmEnabled,
                    onCheckedChange = { scope.launch { settings.setVlmEnabled(it) } },
                    modifier = Modifier.spotlightTarget("settings_deep_analysis", boundsMap),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Confirm capture toggle
                SettingsToggleRow(
                    title = stringResource(R.string.settings_label_confirm_capture),
                    description = stringResource(R.string.settings_desc_confirm_capture),
                    checked = confirmCapture,
                    onCheckedChange = { scope.launch { settings.setConfirmCapture(it) } },
                )
            }

            // ---------------------------------------------------------------
            // App Language
            // ---------------------------------------------------------------
            Box(modifier = Modifier.spotlightTarget("settings_language", boundsMap)) {
                AppLanguageSection(settings = settings, scope = scope)
            }

            // ---------------------------------------------------------------
            // Report Language
            // ---------------------------------------------------------------
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
            }

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
                DeveloperSettingsSection(settings = settings, scope = scope)
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
) {
    val confidenceThreshold by settings.confidenceThreshold.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_CONFIDENCE_THRESHOLD
    )
    val detectorPrecision by settings.detectorPrecision.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_DETECTOR_PRECISION
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

        // Technical info
        Text(
            text = "RT-DETRv2 + Gemma 3n VLM",
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
                    text = stringResource(R.string.settings_about_version),
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
