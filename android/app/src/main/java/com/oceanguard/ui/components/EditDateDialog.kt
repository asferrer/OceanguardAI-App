package com.oceanguard.ai.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R

/**
 * Modal date picker dialog for bulk-editing the capture date of multiple
 * detection sessions at once.
 *
 * The confirm button is disabled until the user picks a date. Callers receive
 * the selected date as a UTC epoch millisecond value via [onConfirm].
 *
 * @param selectedCount Number of sessions that will be affected — used to
 *                      localise the confirm button label (e.g. "Apply to 3").
 * @param onConfirm     Called with the chosen date in UTC epoch milliseconds.
 * @param onDismiss     Called when the dialog is cancelled or dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDateDialog(
    selectedCount: Int,
    onConfirm: (dateMillis: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val datePickerState = rememberDatePickerState()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = datePickerState.selectedDateMillis ?: return@TextButton
                    onConfirm(millis)
                },
                enabled = datePickerState.selectedDateMillis != null,
            ) {
                Text(text = stringResource(R.string.bulk_edit_apply, selectedCount))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}
