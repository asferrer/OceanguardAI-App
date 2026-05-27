package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.InferenceAnimationOverlay
import com.oceanguard.ai.ui.components.OceanGradientButton
import com.oceanguard.ai.ui.components.OnGradientColor
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.theme.OceanGreen
import java.util.Locale

/**
 * SpeciesResultScreen — immersive result display for the BioDex single-capture flow.
 *
 * Mirrors [CameraResultScreen] visually and behaviourally:
 *  - Full-screen image backdrop on [MaterialTheme.colorScheme.background].
 *  - [InferenceAnimationOverlay] while the pipeline runs.
 *  - Bottom action bar with result summary + "New Scan" / "View BioDex" actions.
 *  - Auto-return countdown (10 s) fires after identification completes, matching
 *    the debris single-capture auto-return.
 *  - Transparent back button in the top-start corner.
 *  - Error overlay with Retry / Back actions.
 *
 * Navigation:
 *  - "New Scan" → back to the camera in species mode.
 *  - "View BioDex" → biodex (species dex gallery).
 *  - Auto-return after countdown → same as "New Scan".
 */
@Composable
fun SpeciesResultScreen(
    navController: NavController,
    viewModel: SpeciesViewModel,
    catalog: SpeciesCatalog,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val capturedUri by viewModel.capturedImageUri.collectAsStateWithLifecycle()

    val isComplete = uiState is SpeciesUiState.Complete
    val isIdentifying = uiState is SpeciesUiState.Identifying

    val onNewScan: () -> Unit = {
        viewModel.resetState()
        navController.navigate("camera?mode=species") {
            popUpTo("home") { inclusive = false }
        }
    }

    var autoReturnCountdown by remember { mutableIntStateOf(10) }
    LaunchedEffect(isComplete) {
        if (isComplete) {
            autoReturnCountdown = 10
            repeat(10) { tick ->
                delay(1_000L)
                autoReturnCountdown = 9 - tick
            }
            onNewScan()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Full-screen image backdrop
        if (capturedUri != null) {
            AsyncImage(
                model = capturedUri,
                contentDescription = stringResource(R.string.session_detail_cd_annotated_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Inference animation while running
        AnimatedVisibility(
            visible = isIdentifying,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            InferenceAnimationOverlay(
                statusText = stringResource(R.string.species_identifying),
                subText = null,
                accentColor = OceanGreen,
            )
        }

        // Top back button (transparent circle)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            IconButton(
                onClick = {
                    viewModel.resetState()
                    navController.popBackStack()
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.biodex_cd_go_back),
                    tint = Color.White,
                )
            }
        }

        // Bottom action bar — shown while identifying (spinner chip) or complete (summary + buttons)
        AnimatedVisibility(
            visible = isIdentifying || isComplete,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SpeciesBottomActionBar(
                uiState = uiState,
                catalog = catalog,
                autoReturnCountdown = autoReturnCountdown,
                onNewScan = onNewScan,
                onViewBioDex = {
                    viewModel.resetState()
                    navController.navigate("biodex") {
                        popUpTo("home") { inclusive = false }
                    }
                },
            )
        }

        // Error overlay
        val errorState = uiState as? SpeciesUiState.Error
        if (errorState != null) {
            SpeciesErrorCard(
                message = errorState.message,
                onRetry = {
                    capturedUri?.let { viewModel.identifyFromImage(it) }
                },
                onBack = {
                    viewModel.resetState()
                    navController.popBackStack()
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom action bar
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesBottomActionBar(
    uiState: SpeciesUiState,
    catalog: SpeciesCatalog,
    autoReturnCountdown: Int,
    onNewScan: () -> Unit,
    onViewBioDex: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (uiState) {
            is SpeciesUiState.Identifying -> IdentifyingChip()
            is SpeciesUiState.Complete    -> {
                SpeciesResultSummary(results = uiState.results, catalog = catalog)
                Spacer(modifier = Modifier.height(10.dp))
                SpeciesActionButtons(onNewScan = onNewScan, onViewBioDex = onViewBioDex)
                if (autoReturnCountdown > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.species_result_auto_return, autoReturnCountdown),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
            else -> {}
        }
    }
}

@Composable
private fun IdentifyingChip() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = OceanGreen.copy(alpha = 0.85f),
    ) {
        Text(
            text = stringResource(R.string.species_identifying),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun SpeciesResultSummary(results: List<IdentificationResult>, catalog: SpeciesCatalog) {
    if (results.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = OceanGreen.copy(alpha = 0.85f),
        ) {
            Text(
                text = stringResource(R.string.biodex_result_none),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(results, key = { "${it.speciesKey}_${it.cosineScore}" }) { result ->
                CompactSpeciesResultCard(result = result, catalog = catalog)
            }
        }
    }
}

@Composable
private fun CompactSpeciesResultCard(result: IdentificationResult, catalog: SpeciesCatalog) {
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
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = result.scientificName,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.biodex_result_confidence, confidencePct),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IdSourceBadgeRow(result = result)
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
        val containerColor = when (result.idSource) {
            SpeciesIdSource.RAG_CORE      -> MaterialTheme.colorScheme.primaryContainer
            SpeciesIdSource.RAG_TENTATIVE -> MaterialTheme.colorScheme.secondaryContainer
            SpeciesIdSource.VLM_OPENVOCAB -> MaterialTheme.colorScheme.tertiaryContainer
        }
        val labelColor = when (result.idSource) {
            SpeciesIdSource.RAG_CORE      -> MaterialTheme.colorScheme.onPrimaryContainer
            SpeciesIdSource.RAG_TENTATIVE -> MaterialTheme.colorScheme.onSecondaryContainer
            SpeciesIdSource.VLM_OPENVOCAB -> MaterialTheme.colorScheme.onTertiaryContainer
        }
        AssistChip(
            onClick = {},
            label = { Text(text = sourceLabel, style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = containerColor,
                labelColor = labelColor,
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
    }
}

@Composable
private fun SpeciesActionButtons(onNewScan: () -> Unit, onViewBioDex: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onNewScan,
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .pressableScale(),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
        ) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.species_result_btn_new_scan),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        OceanGradientButton(
            onClick = onViewBioDex,
            modifier = Modifier.weight(1f),
            height = 42.dp,
            cornerRadius = 12.dp,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = OnGradientColor,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.species_result_btn_view_biodex),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = OnGradientColor,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Error overlay card
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesErrorCard(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.results_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                ) {
                    Text(stringResource(R.string.common_back))
                }
                OceanGradientButton(
                    onClick = onRetry,
                    height = 44.dp,
                    cornerRadius = 12.dp,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = OnGradientColor,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.results_btn_retry_analysis),
                        style = MaterialTheme.typography.labelLarge,
                        color = OnGradientColor,
                    )
                }
            }
        }
    }
}
