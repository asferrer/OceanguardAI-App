package com.oceanguard.ai.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.LocationSource
import com.oceanguard.ai.utils.GeocodingResult
import com.oceanguard.ai.utils.PhotonGeocoderClient
import com.oceanguard.ai.utils.toPlaceLabel
import kotlinx.coroutines.delay

/**
 * Modal dialog for bulk-editing the geographic location of multiple detection
 * sessions at once.
 *
 * The user types a free-text search query; results are fetched from the Photon
 * geocoder with a 500 ms debounce to avoid excessive network requests. Tapping
 * a result selects it and marks it with a checkmark. The confirm button is
 * enabled only once a result has been selected.
 *
 * Location is constructed with [LocationSource.MANUAL] because the coordinate
 * comes from an explicit user choice rather than GPS or EXIF metadata.
 *
 * @param selectedCount Number of sessions that will be affected — used to
 *                      localise the confirm button label (e.g. "Apply to 3").
 * @param geocoder      Photon geocoder client used to search for places.
 * @param onConfirm     Called with the [Location] the user selected.
 * @param onDismiss     Called when the dialog is cancelled or dismissed.
 */
@Composable
fun EditLocationDialog(
    selectedCount: Int,
    geocoder: PhotonGeocoderClient,
    onConfirm: (Location) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var selectedResult by remember { mutableStateOf<GeocodingResult?>(null) }

    // Debounced search: wait 500 ms after the user stops typing before firing.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        try {
            delay(500L)
            results = geocoder.search(query, limit = 5)
        } finally {
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.bulk_edit_location))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(text = stringResource(R.string.bulk_edit_location_hint))
                    },
                    singleLine = true,
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }

                if (results.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(top = 4.dp),
                    ) {
                        items(
                            items = results,
                            key = { result -> "${result.latitude},${result.longitude}" },
                        ) { result ->
                            GeocodingResultItem(
                                result = result,
                                isSelected = selectedResult == result,
                                onClick = { selectedResult = result },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = selectedResult ?: return@TextButton
                    onConfirm(
                        Location(
                            latitude = result.latitude,
                            longitude = result.longitude,
                            source = LocationSource.MANUAL,
                        )
                    )
                },
                enabled = selectedResult != null,
            ) {
                Text(text = stringResource(R.string.bulk_edit_apply, selectedCount))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * A single tappable row representing one geocoding search result.
 *
 * Displays the place label as the headline and city/country as a subtitle.
 * A checkmark trailing icon is shown when [isSelected] is true.
 */
@Composable
private fun GeocodingResultItem(
    result: GeocodingResult,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val subtitle = listOfNotNull(result.city, result.country).joinToString(", ")

    ListItem(
        headlineContent = {
            Text(text = result.toPlaceLabel())
        },
        supportingContent = if (subtitle.isNotBlank()) {
            { Text(text = subtitle) }
        } else null,
        trailingContent = if (isSelected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                )
            }
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
