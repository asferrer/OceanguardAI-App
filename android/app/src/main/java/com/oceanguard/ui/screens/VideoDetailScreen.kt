package com.oceanguard.ai.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.oceanguard.ai.data.RiskLevel
import com.oceanguard.ai.data.VideoAnalysis
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.theme.healthScoreColor
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    navController: NavController,
    viewModel: MainViewModel,
    analysisId: Long,
) {
    var analysis by remember { mutableStateOf<VideoAnalysis?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(analysisId) {
        analysis = viewModel.getVideoAnalysis(analysisId)
        isLoading = false
    }

    if (showDeleteDialog && analysis != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Video Analysis") },
            text = { Text("This action cannot be undone. The record will be permanently removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteVideoAnalysis(analysis!!)
                        showDeleteDialog = false
                        navController.popBackStack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video Analysis") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (analysis != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            val outputUri = analysis?.outputVideoUri
            if (outputUri != null) {
                FloatingActionButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/*"
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(outputUri))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share video"))
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) { Icon(Icons.Filled.Share, contentDescription = "Share video") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            analysis == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("Analysis not found.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> VideoDetailContent(Modifier.padding(padding), analysis!!)
        }
    }
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

@Composable
private fun VideoDetailContent(modifier: Modifier, analysis: VideoAnalysis) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val classCounts = remember(analysis.classCounts) { parseClassCounts(analysis.classCounts) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VideoPlayerCard(outputUri = analysis.outputVideoUri)
        DetectionResultsCard(analysis = analysis, classCounts = classCounts)
        Text(
            text = dateFormat.format(analysis.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun VideoPlayerCard(outputUri: String?) {
    var isPlaying by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }

    ElevatedCard(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.elevatedCardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (outputUri != null) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).also { vv ->
                            videoView = vv
                            vv.setVideoURI(Uri.parse(outputUri))
                            vv.setOnCompletionListener { isPlaying = false }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            FilledIconButton(
                onClick = {
                    val vv = videoView ?: return@FilledIconButton
                    if (isPlaying) { vv.pause(); isPlaying = false } else { vv.start(); isPlaying = true }
                },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun DetectionResultsCard(analysis: VideoAnalysis, classCounts: Map<String, Int>) {
    val riskLevel = analysis.getRiskLevel()
    val healthColor = healthScoreColor(analysis.healthScore)

    ElevatedCard(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.elevatedCardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Detection Results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${analysis.uniqueDebrisCount}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("unique debris detected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RiskBadge(riskLevel)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Health Score", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${analysis.healthScore}/100", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = healthColor)
                }
                LinearProgressIndicator(
                    progress = { analysis.healthScore / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = healthColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            if (classCounts.isNotEmpty()) {
                HorizontalDivider()
                Text("Debris Breakdown", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                classCounts.forEach { (name, count) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).background(classColor(name), CircleShape))
                        Text(name.replace("_", " "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("\u00d7$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider()
            Text("Processing Info", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            listOf(
                "Total time" to "${TimeUnit.MILLISECONDS.toSeconds(analysis.totalProcessingTimeMs)}s",
                "Frames processed" to "${analysis.processedFrameCount} / ${analysis.totalFrameCount}",
                "Avg time/frame" to "${"%.0f".format(analysis.avgInferenceTimeMs)}ms",
                "Video duration" to "${TimeUnit.MILLISECONDS.toSeconds(analysis.durationMs)}s",
            ).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(riskLevel: RiskLevel) {
    val (label, bg, fg) = when (riskLevel) {
        RiskLevel.LOW      -> Triple("LOW",      Color(0xFF1B5E20), Color(0xFFA5D6A7))
        RiskLevel.MODERATE -> Triple("MODERATE", Color(0xFF4A3A00), Color(0xFFFFCC02))
        RiskLevel.HIGH     -> Triple("HIGH",     Color(0xFF4A1000), Color(0xFFFFAB40))
        RiskLevel.CRITICAL -> Triple("CRITICAL", Color(0xFF4A0000), Color(0xFFEF9A9A))
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

private fun parseClassCounts(json: String): Map<String, Int> = runCatching {
    val obj = JSONObject(json)
    buildMap { obj.keys().forEach { key -> put(key, obj.getInt(key)) } }
}.getOrDefault(emptyMap())

private fun classColor(name: String): Color = when (name.lowercase()) {
    "bottle", "plastic_debris", "mask" -> Color(0xFFE91E63)
    "glove", "fabric_debris"           -> Color(0xFF3F51B5)
    "can", "metal_debris"              -> Color(0xFF9E9E9E)
    "fishing_net"                      -> Color(0xFFFF6F00)
    "tire"                             -> Color(0xFF795548)
    "glass_debris"                     -> Color(0xFF00BCD4)
    else                               -> Color(0xFF607D8B)
}
