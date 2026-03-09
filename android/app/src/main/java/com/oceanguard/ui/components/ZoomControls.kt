package com.oceanguard.ai.ui.components

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Horizontal zoom slider with ratio display (e.g. "2.5x").
 */
@Composable
fun ZoomSlider(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%.1fx".format(zoomRatio),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.width(48.dp),
        )
        Slider(
            value = zoomRatio,
            onValueChange = onZoomChanged,
            valueRange = 1f..maxZoomRatio.coerceAtLeast(1.1f),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}

/**
 * Modifier extension for pinch-to-zoom gesture detection.
 * Maps pinch gestures to zoom ratio changes via [onZoomChanged].
 */
fun Modifier.pinchToZoom(
    currentZoom: Float,
    maxZoom: Float,
    onZoomChanged: (Float) -> Unit,
): Modifier = this.pointerInput(currentZoom, maxZoom) {
    detectTransformGestures { _, _, zoom, _ ->
        val newZoom = (currentZoom * zoom).coerceIn(1f, maxZoom)
        onZoomChanged(newZoom)
    }
}
