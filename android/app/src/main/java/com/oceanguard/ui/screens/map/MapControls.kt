package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget

/**
 * Floating action buttons overlaid on the map for quick controls.
 */
@Composable
fun MapFabControls(
    showHeatmap: Boolean,
    onToggleHeatmap: () -> Unit,
    onFitAll: () -> Unit,
    onToggleStyle: () -> Unit,
    modifier: Modifier = Modifier,
    boundsMap: MutableMap<String, Rect> = mutableMapOf(),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Heatmap / Pin toggle
        SmallFloatingActionButton(
            onClick = onToggleHeatmap,
            modifier = Modifier.spotlightTarget("map_heatmap", boundsMap),
            containerColor = if (showHeatmap) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (showHeatmap) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ) {
            Icon(
                imageVector = if (showHeatmap) Icons.Filled.PushPin else Icons.Filled.Whatshot,
                contentDescription = if (showHeatmap) {
                    stringResource(R.string.map_fab_cd_show_pins)
                } else {
                    stringResource(R.string.map_fab_cd_show_heatmap)
                },
            )
        }

        // Fit all markers in view
        SmallFloatingActionButton(
            onClick = onFitAll,
            modifier = Modifier.spotlightTarget("map_fit_all", boundsMap),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(Icons.Filled.FitScreen, contentDescription = stringResource(R.string.map_fab_cd_fit_all))
        }

        // Map style layers
        SmallFloatingActionButton(
            onClick = onToggleStyle,
            modifier = Modifier.spotlightTarget("map_style", boundsMap),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.map_fab_cd_map_style))
        }
    }
}
