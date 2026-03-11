package com.oceanguard.ai.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.oceanguard.ai.R
import com.oceanguard.ai.data.VideoAnalysis
import com.oceanguard.ai.inference.VideoProgress
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.theme.healthScoreColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoResultsScreen(navController: NavController, viewModel: MainViewModel) {
    val serviceState by viewModel.inferenceServiceState.collectAsStateWithLifecycle()
    val videoProcessor by viewModel.currentVideoProcessor.collectAsStateWithLifecycle()
    val videoProgress = videoProcessor?.progress?.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_results_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = serviceState) {
                is InferenceServiceState.VideoRunning -> ProcessingContent(
                    state = state,
                    liveFrame = videoProgress?.value?.liveFrame,
                    queuePending = state.queueInfo.pendingCount,
                    onCancel = { viewModel.cancelVideoAnalysis() },
                )
                is InferenceServiceState.VideoComplete -> CompleteContent(state, viewModel) { navController.popBackStack() }
                is InferenceServiceState.Error -> ErrorContent(state.message) { navController.popBackStack() }
                else -> WaitingForServiceContent()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Waiting for service to start (Idle state before VideoRunning)
// ---------------------------------------------------------------------------

@Composable
private fun WaitingForServiceContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.video_preparing),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Processing state
// ---------------------------------------------------------------------------

@Composable
private fun ProcessingContent(
    state: InferenceServiceState.VideoRunning,
    liveFrame: Bitmap?,
    queuePending: Int,
    onCancel: () -> Unit,
) {
    val progress = if (state.totalFrames > 0) {
        state.currentFrame.toFloat() / state.totalFrames
    } else 0f

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Live preview frame
        Card(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (liveFrame != null && !liveFrame.isRecycled) {
                    Image(
                        bitmap = liveFrame.asImageBitmap(),
                        contentDescription = stringResource(R.string.video_live_preview_cd),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.video_waiting_first_frame),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Frame counter overlay
                if (state.currentFrame > 0) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = "${state.currentFrame}/${state.totalFrames}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "~${formatDuration(state.estimatedRemainingMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            stringResource(R.string.video_elapsed, formatDuration(state.elapsedTimeMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Queue info
        if (queuePending > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.video_queue_pending, queuePending),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Cancel button
        OutlinedButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.video_cancel))
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Complete state
// ---------------------------------------------------------------------------

@Composable
private fun CompleteContent(
    state: InferenceServiceState.VideoComplete,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var analysis by remember { mutableStateOf<VideoAnalysis?>(null) }
    LaunchedEffect(state.analysisId) { scope.launch { analysis = viewModel.getVideoAnalysis(state.analysisId) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { VideoPlayerCard(outputVideoUri = state.outputVideoUri) }
        item { MetricsCard(state = state, analysis = analysis) }
        item { ActionButtons(outputVideoUri = state.outputVideoUri, onBack = onBack) }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun VideoPlayerCard(outputVideoUri: String?) {
    var isPlaying by remember { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (outputVideoUri != null) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).also {
                            videoViewRef = it
                            it.setVideoURI(Uri.parse(outputVideoUri))
                            it.setOnCompletionListener { _ -> isPlaying = false }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                FloatingActionButton(
                    onClick = {
                        videoViewRef?.let { vv ->
                            if (isPlaying) { vv.pause(); isPlaying = false } else { vv.start(); isPlaying = true }
                        }
                    },
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text("Annotated video unavailable", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MetricsCard(state: InferenceServiceState.VideoComplete, analysis: VideoAnalysis?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.uniqueDebrisCount}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("unique debris detected", style = MaterialTheme.typography.bodyLarge)
            }

            analysis?.let { va ->
                val classCounts = parseClassCounts(va.classCounts)
                if (classCounts.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    classCounts.forEach { (label, count) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(modifier = Modifier.size(10.dp).background(classColor(label), CircleShape))
                            Text(label.replace("_", " "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                MetricRow("Processing time", formatDuration(state.processingTimeMs))
                MetricRow("Frames processed", "${va.processedFrameCount} / ${va.totalFrameCount}")
                MetricRow("Video duration", formatSeconds(va.durationMs))
                MetricRow("Avg inference / frame", "%.0fms".format(va.avgInferenceTimeMs))
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                val scoreColor = healthScoreColor(va.healthScore)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Health score", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${va.healthScore} / 100", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = scoreColor)
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(progress = { va.healthScore / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = scoreColor, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            } ?: run {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                MetricRow("Processing time", formatDuration(state.processingTimeMs))
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ActionButtons(outputVideoUri: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (outputVideoUri != null) {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(outputVideoUri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share video"))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share Video", style = MaterialTheme.typography.titleMedium)
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Error state
// ---------------------------------------------------------------------------

@Composable
private fun ErrorContent(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Analysis failed", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun formatSeconds(ms: Long): String = "%.1fs".format(ms / 1000.0)

private fun parseClassCounts(json: String): Map<String, Int> = runCatching {
    Gson().fromJson<Map<String, Int>>(json, object : TypeToken<Map<String, Int>>() {}.type) ?: emptyMap()
}.getOrDefault(emptyMap())

private val classColorPalette = listOf(
    Color(0xFFE91E63), Color(0xFF00BCD4), Color(0xFFFF9800), Color(0xFF4CAF50),
    Color(0xFF9C27B0), Color(0xFF2196F3), Color(0xFFFF5722), Color(0xFF607D8B),
)

private fun classColor(label: String): Color =
    classColorPalette[label.hashCode().and(0x7FFFFFFF) % classColorPalette.size]
