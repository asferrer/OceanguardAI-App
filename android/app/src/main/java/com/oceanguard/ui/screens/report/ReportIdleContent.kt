package com.oceanguard.ai.ui.screens.report

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.components.pressableScale
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
internal fun IdleContent(
    hasSessions: Boolean,
    savedReports: List<GeneratedReport>,
    onViewReport: (GeneratedReport) -> Unit,
    onDeleteReport: (GeneratedReport) -> Unit,
    highlightReportId: Long? = null,
    onHighlightConsumed: () -> Unit = {},
) {
    if (savedReports.isEmpty()) {
        LottieEmptyState(
            title = stringResource(R.string.report_empty_title),
            message = if (hasSessions) {
                stringResource(R.string.report_empty_message_with_sessions)
            } else {
                stringResource(R.string.report_empty_message_no_sessions)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        )
    } else {
        val listState = rememberLazyListState()

        LaunchedEffect(highlightReportId) {
            if (highlightReportId != null) {
                listState.animateScrollToItem(0)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.report_saved_title, savedReports.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(savedReports, key = { it.id }) { report ->
                val isHighlighted = report.id == highlightReportId
                SavedReportCard(
                    report = report,
                    onClick = { onViewReport(report) },
                    onDelete = { onDeleteReport(report) },
                    highlight = isHighlighted,
                    onHighlightFinished = {
                        if (isHighlighted) onHighlightConsumed()
                    },
                    modifier = Modifier.animateItem(),
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
internal fun SavedReportCard(
    report: GeneratedReport,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    highlight: Boolean = false,
    onHighlightFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    val highlightAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlight) {
        if (highlight) {
            highlightAlpha.snapTo(1f)
            delay(200)
            highlightAlpha.animateTo(0f, tween(1200, easing = LinearEasing))
            onHighlightFinished()
        }
    }
    val highlightBorder = if (highlightAlpha.value > 0f) {
        Modifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha.value),
            shape = RoundedCornerShape(16.dp),
        )
    } else {
        Modifier
    }

    GlassCard(
        modifier = modifier
            .then(highlightBorder)
            .pressableScale()
            .fillMaxWidth()
            .clickable(onClick = onClick),
        animate = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // AI/Template icon badge
            Surface(
                shape = CircleShape,
                color = if (report.usedAi) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (report.usedAi) Icons.Filled.SmartToy
                        else Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = if (report.usedAi) {
                            stringResource(R.string.report_card_cd_ai)
                        } else {
                            stringResource(R.string.report_card_cd_template)
                        },
                        modifier = Modifier.size(20.dp),
                        tint = if (report.usedAi) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (report.usedAi) {
                        stringResource(R.string.report_card_ai_label)
                    } else {
                        stringResource(R.string.report_card_template_label)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                // Location badge (if available)
                report.locationName?.let { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Text(
                    text = if (report.sessionCount != 1) stringResource(R.string.report_card_meta_plural, report.sessionCount, report.language.uppercase(), dateFormat.format(report.timestamp))
                           else stringResource(R.string.report_card_meta_singular, report.sessionCount, report.language.uppercase(), dateFormat.format(report.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = report.text.take(120).replace("\n", " ") + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.report_card_delete_cd),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
