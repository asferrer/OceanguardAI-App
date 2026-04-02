package com.oceanguard.ai.ui.screens.report

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.MarkdownText
import com.oceanguard.ai.ui.components.ShimmerLoadingScreen
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.utils.DataExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
internal fun GeneratingContent(isLoadingModel: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoadingModel) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = stringResource(R.string.report_loading_engine),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        ShimmerLoadingScreen(
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun ReportContent(
    report: GeneratedReport,
    onShare: () -> Unit,
    onBackToList: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!report.usedAi) {
            TemplateBanner()
        }

        ReportHeader(report = report, dateFormat = dateFormat)

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            animate = false,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                MarkdownText(
                    text = report.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBackToList,
                modifier = Modifier
                    .pressableScale()
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.report_btn_back_to_list),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            Button(
                onClick = onShare,
                modifier = Modifier
                    .pressableScale()
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.report_btn_share),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
internal fun ReportHeader(
    report: GeneratedReport,
    dateFormat: SimpleDateFormat,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val iconCfg = remember(report.id) { debrisIconConfig(report.id) }
            Surface(
                shape = CircleShape,
                color = iconCfg.containerColor,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = iconCfg.icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = iconCfg.tint,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (report.usedAi) {
                        stringResource(R.string.report_header_ai_generated)
                    } else {
                        stringResource(R.string.report_header_template)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (report.sessionCount != 1) stringResource(R.string.report_card_meta_plural, report.sessionCount, report.language.uppercase(), dateFormat.format(report.timestamp))
                           else stringResource(R.string.report_card_meta_singular, report.sessionCount, report.language.uppercase(), dateFormat.format(report.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TemplateBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.report_template_banner_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
internal fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.report_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier
                .pressableScale()
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = stringResource(R.string.report_btn_retry),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/**
 * Full-screen overlay for reading a report. Slides up from the bottom
 * and has its own TopAppBar with close + share actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportFullScreenView(
    report: GeneratedReport,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onDownloadPdf: () -> Unit = {},
    isExportingPdf: Boolean = false,
    sessions: List<DetectionSession> = emptyList(),
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    BackHandler(onBack = onClose)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (report.usedAi) {
                                stringResource(R.string.report_header_ai_generated)
                            } else {
                                stringResource(R.string.report_header_template)
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.report_cd_go_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onDownloadPdf,
                            enabled = !isExportingPdf,
                        ) {
                            if (isExportingPdf) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_picture_as_pdf),
                                    contentDescription = stringResource(R.string.report_cd_download_pdf),
                                )
                            }
                        }
                        IconButton(onClick = onShare) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = stringResource(R.string.report_cd_share),
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                if (!report.usedAi) {
                    TemplateBanner()
                }

                ReportHeader(report = report, dateFormat = dateFormat)

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    animate = false,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Thumbnail gallery
                        if (sessions.isNotEmpty()) {
                            SessionThumbnailStrip(sessions = sessions)
                        }

                        MarkdownText(
                            text = report.text,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Horizontal scrollable strip showing annotated thumbnails from sessions.
 */
@Composable
private fun SessionThumbnailStrip(sessions: List<DetectionSession>) {
    val thumbnails = remember(sessions) {
        sessions.mapNotNull { s ->
            val uri = s.thumbnailUri ?: s.imageUri
            uri.takeIf { it.isNotBlank() }
        }
    }
    if (thumbnails.isEmpty()) return

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.report_images_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            thumbnails.forEach { uriStr ->
                AsyncImage(
                    model = Uri.parse(uriStr),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp),
                        ),
                )
            }
        }
    }
}

internal fun shareMarkdownReport(
    context: Context,
    reportText: String,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    val uri = DataExporter.exportToMarkdown(
        context = context,
        report = reportText,
        locationName = "OceanGuard_Survey",
    )

    if (uri != null) {
        DataExporter.shareFile(context, uri, "text/markdown")
    } else {
        scope.launch {
            snackbarHostState.showSnackbar(
                context.getString(R.string.report_snackbar_export_error)
            )
        }
    }
}

internal suspend fun sharePdfReport(
    context: Context,
    report: GeneratedReport,
    sessions: List<DetectionSession>,
    snackbarHostState: SnackbarHostState,
) {
    val uri = DataExporter.exportToPdf(context, report, sessions)
    if (uri != null) {
        DataExporter.shareFile(context, uri, "application/pdf")
    } else {
        snackbarHostState.showSnackbar(
            context.getString(R.string.report_snackbar_pdf_error)
        )
    }
}
