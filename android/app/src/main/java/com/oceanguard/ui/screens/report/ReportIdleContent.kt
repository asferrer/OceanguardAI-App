package com.oceanguard.ai.ui.screens.report

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Masks
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material.icons.filled.Recycling
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.ui.components.DataDotDatePickerDialog
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.components.pressableScale
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// ---------------------------------------------------------------------------
// Debris icon configs — one per detectable class, ocean-palette themed
// ---------------------------------------------------------------------------

internal data class DebrisIconCfg(val icon: ImageVector, val containerColor: Color, val tint: Color)

internal val debrisIcons = listOf(
    DebrisIconCfg(Icons.Filled.LocalDrink,  Color(0xFF0E2438), Color(0xFF4FC3F7)), // Bottle
    DebrisIconCfg(Icons.Filled.Recycling,   Color(0xFF0A2A22), Color(0xFF4DB6AC)), // Can
    DebrisIconCfg(Icons.Filled.Phishing,    Color(0xFF092028), Color(0xFF00D4FF)), // Fishing Net
    DebrisIconCfg(Icons.Filled.Masks,       Color(0xFF281A08), Color(0xFFFFB74D)), // Mask
    DebrisIconCfg(Icons.Filled.ShoppingBag, Color(0xFF280A18), Color(0xFFF48FB1)), // Plastic Debris
)

internal fun debrisIconConfig(reportId: Long) =
    debrisIcons[(reportId % debrisIcons.size).toInt().coerceAtLeast(0)]

/** Strips markdown syntax to produce a clean plain-text preview snippet. */
private fun stripMarkdownForPreview(text: String): String = text
    .replace(Regex("#{1,6}\\s+"), "")               // ## Heading → Heading
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")       // **bold** → bold
    .replace(Regex("__(.+?)__"), "$1")               // __bold__ → bold
    .replace(Regex("\\*(.+?)\\*"), "$1")             // *italic* → italic
    .replace(Regex("_(.+?)_"), "$1")                 // _italic_ → italic
    .replace(Regex("`(.+?)`"), "$1")                 // `code` → code
    .replace(Regex("^>\\s*", RegexOption.MULTILINE), "") // > blockquote
    .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "") // list items
    .replace(Regex("\\|.+"), "")                     // table rows
    .replace(Regex("\\n+"), " ")                     // newlines → spaces
    .trim()

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

        // Filter state — mirrors the HistoryScreen.FilterChipRow pattern, only
        // without "debris type" because GeneratedReport doesn't store
        // per-debris details (the underlying sessions are denormalised away
        // by the time a report is saved).
        var selectedLocationName by remember { mutableStateOf<String?>(null) }
        var dateRangeStart by remember { mutableStateOf<Long?>(null) }
        var dateRangeEnd by remember { mutableStateOf<Long?>(null) }
        var showDatePicker by remember { mutableStateOf(false) }

        val availableLocations = remember(savedReports) {
            savedReports.mapNotNull { it.locationName?.takeIf { n -> n.isNotBlank() } }
                .distinct()
                .sorted()
        }
        // Calendar dots in the date picker correspond to days where reports
        // exist — same UX as History/Map. Convert millis → LocalDate at the
        // boundary because DataDotDatePickerDialog works with LocalDate.
        val datesWithData = remember(savedReports) {
            val zone = ZoneId.systemDefault()
            savedReports
                .map { Instant.ofEpochMilli(it.timestamp.time).atZone(zone).toLocalDate() }
                .toSet()
        }

        val filteredReports by remember(
            savedReports,
            selectedLocationName,
            dateRangeStart,
            dateRangeEnd,
        ) {
            derivedStateOf {
                savedReports.filter { report ->
                    val matchesLocation = selectedLocationName == null ||
                        report.locationName == selectedLocationName
                    val matchesDate = (dateRangeStart == null && dateRangeEnd == null) ||
                        (report.timestamp.time >= (dateRangeStart ?: 0L) &&
                            report.timestamp.time <= (dateRangeEnd ?: Long.MAX_VALUE))
                    matchesLocation && matchesDate
                }
            }
        }

        LaunchedEffect(highlightReportId) {
            if (highlightReportId != null) {
                listState.animateScrollToItem(0)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Filter chip row — sticks above the list so the LazyColumn can
            // scroll independently.
            SavedReportsFilterChipRow(
                selectedLocationName = selectedLocationName,
                availableLocations = availableLocations,
                onLocationSelected = { selectedLocationName = it },
                dateRangeStart = dateRangeStart,
                dateRangeEnd = dateRangeEnd,
                onDateRangeCleared = {
                    dateRangeStart = null
                    dateRangeEnd = null
                },
                onDateRangeClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            )

            if (showDatePicker) {
                DataDotDatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    initialStartDateMillis = dateRangeStart,
                    initialEndDateMillis = dateRangeEnd,
                    datesWithData = datesWithData,
                    onConfirm = { start, end ->
                        dateRangeStart = start
                        dateRangeEnd = end
                        showDatePicker = false
                    },
                )
            }

            if (filteredReports.isEmpty()) {
                // Filters narrowed everything out. Show a small empty hint
                // rather than the full Lottie empty state, since the user is
                // actively filtering.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.saved_reports_empty_filtered),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.report_saved_title, filteredReports.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(filteredReports, key = { it.id }) { report ->
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
    }
}

/**
 * Two-chip filter row for the Saved Reports list: Location and Date Range.
 * Mirrors the visual + interaction pattern of HistoryScreen's FilterChipRow
 * (HistoryScreen.kt:841) so users get a consistent filter UX across screens.
 * Debris-type chip is omitted because GeneratedReport doesn't carry per-debris
 * data — the report is a summary of N sessions, not a single observation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedReportsFilterChipRow(
    selectedLocationName: String?,
    availableLocations: List<String>,
    onLocationSelected: (String?) -> Unit,
    dateRangeStart: Long?,
    dateRangeEnd: Long?,
    onDateRangeCleared: () -> Unit,
    onDateRangeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (availableLocations.isNotEmpty()) {
            var locationExpanded by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = selectedLocationName != null,
                    onClick = {
                        if (selectedLocationName != null) onLocationSelected(null)
                        else locationExpanded = true
                    },
                    label = {
                        Text(
                            text = selectedLocationName
                                ?: stringResource(R.string.history_filter_location),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (selectedLocationName != null) {
                        { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
                    } else null,
                )
                DropdownMenu(
                    expanded = locationExpanded,
                    onDismissRequest = { locationExpanded = false },
                ) {
                    availableLocations.forEach { place ->
                        DropdownMenuItem(
                            text = { Text(place) },
                            onClick = {
                                onLocationSelected(place)
                                locationExpanded = false
                            },
                        )
                    }
                }
            }
        }

        val hasDateRange = dateRangeStart != null || dateRangeEnd != null
        FilterChip(
            selected = hasDateRange,
            onClick = {
                if (hasDateRange) onDateRangeCleared()
                else onDateRangeClick()
            },
            label = {
                if (hasDateRange) {
                    val s = dateRangeStart?.let { dateFormatter.format(java.util.Date(it)) } ?: "…"
                    val e = dateRangeEnd?.let { dateFormatter.format(java.util.Date(it)) } ?: "…"
                    Text("$s – $e", maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        stringResource(R.string.history_filter_date_range),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingIcon = if (hasDateRange) {
                { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
            } else null,
        )
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
            // Debris icon badge — deterministic per report ID
            val iconCfg = remember(report.id) { debrisIconConfig(report.id) }
            Surface(
                shape = CircleShape,
                color = iconCfg.containerColor,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = iconCfg.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = iconCfg.tint,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                    report.audience?.let { key ->
                        val label = when (key) {
                            "ngo_manager" -> stringResource(R.string.audience_ngo)
                            "citizen" -> stringResource(R.string.audience_citizen)
                            else -> stringResource(R.string.audience_scientific)
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }

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
                val previewSnippet = remember(report.id) {
                    stripMarkdownForPreview(report.text).take(140) + "\u2026"
                }
                Text(
                    text = previewSnippet,
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
