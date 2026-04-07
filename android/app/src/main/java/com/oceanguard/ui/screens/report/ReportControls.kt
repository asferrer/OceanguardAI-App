package com.oceanguard.ai.ui.screens.report

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.draw.scale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.components.OceanGradientButton
import com.oceanguard.ai.ui.components.OnGradientColor
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import androidx.compose.material.icons.filled.Download
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateLanguageRow(
    dateRangeStartMs: Long?,
    dateRangeEndMs: Long?,
    onDateRangeCleared: () -> Unit,
    onDateRangeClick: () -> Unit,
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    enabled: Boolean,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect>,
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val hasDateRange = dateRangeStartMs != null || dateRangeEndMs != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = hasDateRange,
            onClick = {
                if (hasDateRange) onDateRangeCleared()
                else onDateRangeClick()
            },
            label = {
                if (hasDateRange) {
                    val s = dateRangeStartMs?.let { dateFormatter.format(Date(it)) } ?: "…"
                    val e = dateRangeEndMs?.let { dateFormatter.format(Date(it)) } ?: "…"
                    Text("$s – $e", maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(stringResource(R.string.report_filter_date_range))
                }
            },
            leadingIcon = if (hasDateRange) {
                { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
            } else null,
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Box(modifier = Modifier.spotlightTarget("reports_language", boundsMap)) {
                LanguageChip(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = onLanguageSelected,
                    enabled = enabled,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageChip(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val languageName = SettingsRepository.SUPPORTED_LANGUAGES[selectedLanguage] ?: selectedLanguage

    Box {
        FilterChip(
            selected = true,
            onClick = { if (enabled) expanded = true },
            label = { Text(languageName, style = MaterialTheme.typography.labelMedium) },
            enabled = enabled,
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SettingsRepository.SUPPORTED_LANGUAGES.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onLanguageSelected(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun GenerateButtonRow(
    isGenerating: Boolean,
    hasZoneSelected: Boolean,
    sessionCount: Int,
    onGenerate: () -> Unit,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect>,
) {
    OceanGradientButton(
        onClick = onGenerate,
        modifier = Modifier
            .fillMaxWidth()
            .spotlightTarget("reports_generate", boundsMap),
        enabled = !isGenerating && hasZoneSelected && sessionCount > 0,
        height = 52.dp,
        cornerRadius = 12.dp,
    ) {
        if (isGenerating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = OnGradientColor,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.report_btn_generating),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OnGradientColor,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = OnGradientColor,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (!hasZoneSelected) {
                    stringResource(R.string.report_zone_no_selection)
                } else {
                    stringResource(R.string.report_btn_generate_zone)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OnGradientColor,
            )
        }
    }
}

/**
 * Animated AutoAwesome icon that pulses independently.
 *
 * Extracted from GeneratingBanner so the 120-FPS animation only recomposes
 * this small composable — NOT the parent banner with its MarkdownText.
 */
@Composable
private fun PulsingAIIcon(tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "icon_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue  = 1.15f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "icon_scale",
    )
    Icon(
        imageVector = Icons.Filled.AutoAwesome,
        contentDescription = null,
        modifier = modifier.scale(scale),
        tint = tint,
    )
}

@Composable
internal fun GeneratingBanner(
    isLoadingModel: Boolean,
    streamingText: String? = null,
    tokenCount: Int = 0,
    tokensPerSec: Float = 0f,
    maxTokens: Int = 0,
    verifyingProgress: Pair<Int, Int>? = null,
    modifier: Modifier = Modifier,
) {
    val currentHeading = remember(streamingText) {
        if (streamingText.isNullOrBlank()) return@remember ""
        val lastIdx = streamingText.lastIndexOf("\n## ")
        if (lastIdx >= 0) {
            val end = streamingText.indexOf('\n', lastIdx + 1).takeIf { it > 0 } ?: streamingText.length
            streamingText.substring(lastIdx + 1, end).removePrefix("## ").trim()
        } else ""
    }

    val hasTokenMetrics = tokenCount > 0 && maxTokens > 0
    val progress = if (hasTokenMetrics) (tokenCount.toFloat() / maxTokens).coerceIn(0f, 1f) else 0f
    val etaSeconds = if (hasTokenMetrics && tokensPerSec > 0.5f) {
        ((maxTokens - tokenCount) / tokensPerSec).toInt()
    } else null

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulsingAIIcon(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = when {
                        verifyingProgress != null ->
                            stringResource(R.string.report_verifying_detections, verifyingProgress.first, verifyingProgress.second)
                        isLoadingModel -> stringResource(R.string.report_loading_engine)
                        else -> stringResource(R.string.report_btn_generating)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (verifyingProgress != null) {
                LinearProgressIndicator(
                    progress = { verifyingProgress.first.toFloat() / verifyingProgress.second.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
            } else if (hasTokenMetrics) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
            }
            // Token metrics row: tok/s + ETA
            if (hasTokenMetrics) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "%.1f tok/s".format(tokensPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                    if (etaSeconds != null) {
                        val etaText = if (etaSeconds >= 60) {
                            "${etaSeconds / 60}m ${etaSeconds % 60}s"
                        } else {
                            "${etaSeconds}s"
                        }
                        Text(
                            text = stringResource(R.string.report_eta, etaText),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            if (currentHeading.isNotBlank()) {
                Text(
                    text = "\u25B6 $currentHeading",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun VlmDownloadBanner(
    downloadState: VlmDownloadState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.vlm_download_banner_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (downloadState is VlmDownloadState.Downloading) {
                        val mbDownloaded = downloadState.downloadedBytes / (1024 * 1024)
                        val mbTotal = downloadState.totalBytes / (1024 * 1024)
                        Text(
                            text = "$mbDownloaded / $mbTotal MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.common_cancel),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            when (downloadState) {
                is VlmDownloadState.Downloading -> LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
                else -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
            }
        }
    }
}
