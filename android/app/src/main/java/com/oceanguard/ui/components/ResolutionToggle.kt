package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Three-option segmented toggle for inference input resolution.
 * Allows the user to trade speed vs accuracy.
 */
@Composable
fun ResolutionToggle(
    currentResolution: Int,
    onResolutionChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        320 to "Fast",
        480 to "Medium",
        640 to "High",
    )

    Row(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (resolution, label) ->
            FilterChip(
                selected = currentResolution == resolution,
                onClick = { onResolutionChanged(resolution) },
                label = {
                    Text(
                        text = "$label ${resolution}px",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.White.copy(alpha = 0.25f),
                    selectedLabelColor = Color.White,
                    labelColor = Color.White.copy(alpha = 0.7f),
                    containerColor = Color.Transparent,
                ),
            )
        }
    }
}
