package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Material 3 [AlertDialog] wrapper around [DataDotDateRangePicker].
 *
 * Handles millisecond <-> [LocalDate] conversion at the boundary so callers
 * that work in epoch-millis (e.g. Room, GeneratedReport) never need to import
 * java.time directly.
 *
 * Confirm button is enabled only once the user has picked at least a start
 * date. Cancel dismisses without invoking [onConfirm].
 *
 * @param onDismissRequest       Called when the user taps outside or presses
 *                               Cancel.
 * @param initialStartDateMillis Epoch-millis for the pre-selected start, or
 *                               null.
 * @param initialEndDateMillis   Epoch-millis for the pre-selected end, or
 *                               null.
 * @param datesWithData          Set of [LocalDate]s that should show a dot.
 * @param onConfirm              Called with the selected range as epoch-millis
 *                               when the user taps OK. Either value may be null
 *                               only if no selection was made (which cannot
 *                               happen because the button is disabled then, but
 *                               the signature is kept nullable for safety).
 */
@Composable
fun DataDotDatePickerDialog(
    onDismissRequest: () -> Unit,
    initialStartDateMillis: Long?,
    initialEndDateMillis: Long?,
    datesWithData: Set<LocalDate>,
    onConfirm: (startMillis: Long?, endMillis: Long?) -> Unit,
) {
    // ------------------------------------------------------------------
    // Millis -> LocalDate helpers
    // ------------------------------------------------------------------
    fun millisToLocalDate(millis: Long?): LocalDate? {
        millis ?: return null
        return Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }

    fun localDateToMillis(date: LocalDate?): Long? {
        date ?: return null
        // Use start-of-day in the system default zone, then convert to UTC
        // millis so the value is consistent with how Room stores timestamps.
        return date
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // ------------------------------------------------------------------
    // Internal selection state: updated by the picker via onRangeChanged
    // ------------------------------------------------------------------
    var pendingStart by remember {
        mutableStateOf(millisToLocalDate(initialStartDateMillis))
    }
    var pendingEnd by remember {
        mutableStateOf(millisToLocalDate(initialEndDateMillis))
    }

    val confirmEnabled = pendingStart != null

    // ------------------------------------------------------------------
    // Dialog
    // ------------------------------------------------------------------
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text  = "Select date range",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            DataDotDateRangePicker(
                initialStartDate = millisToLocalDate(initialStartDateMillis),
                initialEndDate   = millisToLocalDate(initialEndDateMillis),
                datesWithData    = datesWithData,
                onRangeChanged   = { start, end ->
                    pendingStart = start
                    pendingEnd   = end
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick  = {
                    onConfirm(
                        localDateToMillis(pendingStart),
                        localDateToMillis(pendingEnd),
                    )
                },
                enabled  = confirmEnabled,
            ) {
                Text(
                    text  = stringResource(android.R.string.ok),
                    color = if (confirmEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(
                    text  = stringResource(android.R.string.cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
    )
}
