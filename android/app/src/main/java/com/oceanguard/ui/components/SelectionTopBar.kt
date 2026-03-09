package com.oceanguard.ai.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R

/**
 * Contextual top bar displayed when the user has entered multi-select mode
 * on the History screen.
 *
 * Shows the number of currently selected sessions and provides bulk-action
 * shortcuts for date editing, location editing, deletion, and select-all.
 *
 * @param selectedCount Number of sessions currently selected.
 * @param onClose       Called when the user taps the close icon to exit selection mode.
 * @param onEditDate    Called when the user taps the calendar icon to bulk-edit dates.
 * @param onEditLocation Called when the user taps the location icon to bulk-edit locations.
 * @param onDelete      Called when the user taps the delete icon to bulk-delete sessions.
 * @param onSelectAll   Called when the user taps the select-all icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onEditDate: () -> Unit,
    onEditLocation: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_exit_selection),
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.bulk_selected_count, selectedCount),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = {
            IconButton(onClick = onEditDate) {
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = stringResource(R.string.cd_edit_date),
                )
            }
            IconButton(onClick = onEditLocation) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = stringResource(R.string.cd_edit_location),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete_session),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Filled.DoneAll,
                    contentDescription = stringResource(R.string.bulk_select_all),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}
