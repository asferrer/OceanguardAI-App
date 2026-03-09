package com.oceanguard.ai.ui.components.spotlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Registers this composable as a spotlight target with the given [id].
 *
 * The composable's **window-relative** bounding box is stored in [boundsMap]
 * whenever its global position changes. The overlay converts these window
 * coordinates to its own local space, so cutouts align correctly regardless
 * of composition-tree depth, system-bar offsets, or scroll position.
 */
fun Modifier.spotlightTarget(
    id: String,
    boundsMap: MutableMap<String, Rect>,
): Modifier = this.onGloballyPositioned { coordinates ->
    boundsMap[id] = coordinates.boundsInWindow()
}

/**
 * Creates and remembers a mutable snapshot state map for tracking spotlight
 * target bounds. Using a [SnapshotStateMap] ensures Compose reacts to bound
 * updates and redraws the overlay automatically.
 */
@Composable
fun rememberSpotlightBounds(): SnapshotStateMap<String, Rect> =
    remember { mutableListOf<Pair<String, Rect>>().toMutableStateMap() }
