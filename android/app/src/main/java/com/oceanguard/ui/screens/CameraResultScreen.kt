package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.UiState
import com.oceanguard.ai.ui.components.BoundingBoxOverlay
import com.oceanguard.ai.ui.components.InferenceAnimationOverlay
import com.oceanguard.ai.ui.components.OceanGradientButton
import com.oceanguard.ai.ui.components.OnGradientColor
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.theme.OceanGreen
import com.oceanguard.ai.ui.theme.healthScoreColor

/**
 * CameraResultScreen — dedicated landing for the camera-capture flow.
 *
 * The captured photo is displayed full-screen on a black backdrop while the
 * detection pipeline runs. An [InferenceAnimationOverlay] (sonar + scan beam +
 * reticle) sits on top so the user gets continuous feedback that work is
 * happening on-device. As soon as the detector emits boxes, they are drawn
 * directly on the photo with the canonical [BoundingBoxOverlay] — large, with
 * class and confidence labels — so the user sees the result on the captured
 * frame itself, not in a card-style list.
 *
 * When the analysis completes the screen swaps the live overlay for the
 * pre-rendered annotated thumbnail (same bake the History detail uses) and
 * surfaces a compact bottom bar with a summary and two actions:
 *   - "View Details" → `session/{id}` (the full analysis card from ResultsScreen)
 *   - "New Scan"     → back to the camera viewfinder
 *
 * Gallery / batch flows continue to use [ResultsScreen]; this screen is
 * reached only when the user navigates from the camera viewfinder via the
 * "camera_result" route.
 */
@Composable
fun CameraResultScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val capturedImageUri by viewModel.capturedImageUri.collectAsStateWithLifecycle()
    val annotatedThumbnailUri by viewModel.annotatedThumbnailUri.collectAsStateWithLifecycle()
    val serviceState by viewModel.inferenceServiceState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val detectorModeKey by app.settingsRepository.detectorMode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_DETECTOR_MODE)
    val isGemma4 = detectorModeKey == DetectorType.GEMMA4_VISION.key

    // Elapsed-seconds tick for the long Gemma 4 path.
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(uiState) {
        elapsedSec = 0
        while (uiState is UiState.Detecting ||
            uiState is UiState.ModelLoading ||
            uiState is UiState.DetectionsReady ||
            uiState is UiState.AnalyzingDeep
        ) {
            delay(1_000)
            elapsedSec += 1
        }
    }

    val currentDetections: List<DetectionResult> = when (val state = uiState) {
        is UiState.DetectionsReady -> state.detections
        is UiState.AnalyzingDeep -> state.detections
        is UiState.AnalysisComplete -> state.result.rtdetrDetections
        else -> emptyList()
    }
    val currentResult: AnalysisResult? = (uiState as? UiState.AnalysisComplete)?.result
    val completedSessionId: Long? = (serviceState as? InferenceServiceState.SingleComplete)?.sessionId

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Use the exact same `colorScheme.background` that every other
            // screen (Home, History, Map…) uses for its Scaffold container, so
            // the letterbox bands around a non-matching photo aspect ratio are
            // pixel-identical to the rest of the app's chrome.
            .background(MaterialTheme.colorScheme.background),
    ) {
        CapturedImageWithDetections(
            imageUri = capturedImageUri,
            annotatedThumbnailUri = annotatedThumbnailUri,
            detections = currentDetections,
            showLiveOverlay = uiState !is UiState.AnalysisComplete,
            modifier = Modifier.fillMaxSize(),
        )

        // Live inference animation — only while pipeline is running.
        val showOverlay = uiState is UiState.Detecting ||
            uiState is UiState.ModelLoading ||
            uiState is UiState.AnalyzingDeep ||
            (uiState is UiState.DetectionsReady && isGemma4.not())
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val statusText = inferenceStatusText(uiState, isGemma4)
            // Only show elapsed-time line; we no longer surface the "~20-30 s"
            // teaser so the overlay reads as a clean activity indicator.
            val subText = if (elapsedSec > 0) {
                stringResource(R.string.results_status_elapsed, elapsedSec)
            } else null
            InferenceAnimationOverlay(
                statusText = statusText,
                subText = subText,
                accentColor = OceanGreen,
            )
        }

        // Top bar: minimal transparent back button.
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
                    contentDescription = stringResource(R.string.cd_go_back),
                    tint = Color.White,
                )
            }
        }

        // Bottom bar: live count chip while running, full summary when done.
        AnimatedVisibility(
            visible = uiState is UiState.AnalysisComplete || currentDetections.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomActionBar(
                result = currentResult,
                liveDetectionCount = currentDetections.size,
                isComplete = uiState is UiState.AnalysisComplete,
                onViewDetails = if (completedSessionId != null && completedSessionId > 0) {
                    {
                        viewModel.resetState()
                        navController.navigate("session/$completedSessionId") {
                            popUpTo("home") { inclusive = false }
                        }
                    }
                } else null,
                onNewScan = {
                    viewModel.resetState()
                    navController.navigate("camera") {
                        popUpTo("home") { inclusive = false }
                    }
                },
            )
        }

        // Error overlay
        val errorState = uiState as? UiState.Error
        if (errorState != null) {
            ErrorCard(
                message = errorState.message,
                onRetry = {
                    capturedImageUri?.let { viewModel.analyzeImage(it) }
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

@Composable
private fun CapturedImageWithDetections(
    imageUri: Uri?,
    annotatedThumbnailUri: String?,
    detections: List<DetectionResult>,
    showLiveOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            // Final state with a pre-rendered annotated thumbnail — render that
            // as the source of truth so the screen matches the History detail
            // pixel-for-pixel and we don't double-draw boxes on top of baked ones.
            !showLiveOverlay && annotatedThumbnailUri != null -> {
                AsyncImage(
                    model = annotatedThumbnailUri,
                    contentDescription = stringResource(R.string.session_detail_cd_annotated_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Live (in-progress) or final without baked thumbnail — draw boxes
            // dynamically on top of the captured photo. BoundingBoxOverlay uses
            // ContentScale.Fit internally, so the photo keeps its aspect ratio
            // and the boxes line up exactly with the displayed image.
            imageUri != null -> {
                BoundingBoxOverlay(
                    imageUri = imageUri.toString(),
                    detections = detections,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.results_status_preparing),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun inferenceStatusText(state: UiState, isGemma4: Boolean): String = when (state) {
    is UiState.ModelLoading -> state.progress
    is UiState.Detecting -> if (isGemma4) {
        stringResource(R.string.results_status_detecting_gemma4)
    } else {
        stringResource(R.string.results_status_detecting)
    }
    is UiState.DetectionsReady,
    is UiState.AnalyzingDeep -> stringResource(R.string.results_status_generating)
    else -> stringResource(R.string.results_status_preparing)
}

@Composable
private fun BottomActionBar(
    result: AnalysisResult?,
    liveDetectionCount: Int,
    isComplete: Boolean,
    onViewDetails: (() -> Unit)?,
    onNewScan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Tinted in the exact theme background colour so the bar reads as a
            // continuation of the chrome rather than a black band on the photo.
            .background(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
            )
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isComplete && result != null) {
            CompleteSummary(result = result)
        } else if (liveDetectionCount > 0) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = OceanGreen.copy(alpha = 0.85f),
            ) {
                Text(
                    text = stringResource(R.string.camera_result_live_count, liveDetectionCount),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }

        if (isComplete) {
            Spacer(modifier = Modifier.height(10.dp))
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
                        text = stringResource(R.string.camera_result_btn_new_scan),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (onViewDetails != null) {
                    OceanGradientButton(
                        onClick = onViewDetails,
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
                            text = stringResource(R.string.camera_result_btn_view_details),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = OnGradientColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompleteSummary(result: AnalysisResult) {
    val noDebris = result.totalDebrisCount == 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (noDebris) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = OceanGreen.copy(alpha = 0.85f),
            ) {
                Text(
                    text = stringResource(R.string.camera_result_no_debris),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        } else {
            SummaryChip(
                label = stringResource(R.string.camera_result_summary_debris),
                value = result.totalDebrisCount.toString(),
                tint = Color.White,
            )
            Spacer(modifier = Modifier.width(10.dp))
            SummaryChip(
                label = stringResource(R.string.camera_result_summary_health),
                value = "${result.healthScore}%",
                tint = healthScoreColor(result.healthScore),
            )
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun ErrorCard(
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
