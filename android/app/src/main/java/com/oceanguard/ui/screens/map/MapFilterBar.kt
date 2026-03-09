package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Horizontal scrollable row of filter chips for the map screen.
 * Mirrors the pattern from HistoryScreen's FilterChipRow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapFilterBar(
    filterState: MapFilterState,
    onFilterChanged: (MapFilterState) -> Unit,
    availableDebrisTypes: List<DebrisType>,
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
        // Debris type chip
        var debrisExpanded by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = filterState.selectedDebrisType != null,
                onClick = {
                    if (filterState.selectedDebrisType != null) {
                        onFilterChanged(filterState.copy(selectedDebrisType = null))
                    } else {
                        debrisExpanded = true
                    }
                },
                label = {
                    Text(
                        filterState.selectedDebrisType?.name?.lowercase()
                            ?.replaceFirstChar { it.uppercase() }
                            ?.replace("_", " ")
                            ?: stringResource(R.string.map_filter_debris_type),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = if (filterState.selectedDebrisType != null) {
                    { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
                } else null,
            )
            DropdownMenu(
                expanded = debrisExpanded,
                onDismissRequest = { debrisExpanded = false },
            ) {
                availableDebrisTypes.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                type.name.lowercase()
                                    .replaceFirstChar { it.uppercase() }
                                    .replace("_", " "),
                            )
                        },
                        onClick = {
                            onFilterChanged(filterState.copy(selectedDebrisType = type))
                            debrisExpanded = false
                        },
                    )
                }
            }
        }

        // Date range chip
        val hasDateRange = filterState.dateRangeStartMs != null || filterState.dateRangeEndMs != null
        FilterChip(
            selected = hasDateRange,
            onClick = {
                if (hasDateRange) {
                    onFilterChanged(
                        filterState.copy(dateRangeStartMs = null, dateRangeEndMs = null),
                    )
                } else {
                    onDateRangeClick()
                }
            },
            label = {
                if (hasDateRange) {
                    val s = filterState.dateRangeStartMs
                        ?.let { dateFormatter.format(Date(it)) } ?: "…"
                    val e = filterState.dateRangeEndMs
                        ?.let { dateFormatter.format(Date(it)) } ?: "…"
                    Text("$s – $e", maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(stringResource(R.string.map_filter_date_range), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            leadingIcon = if (hasDateRange) {
                { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
            } else null,
        )
    }
}
