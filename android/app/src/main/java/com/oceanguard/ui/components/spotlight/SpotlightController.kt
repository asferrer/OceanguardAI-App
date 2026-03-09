package com.oceanguard.ai.ui.components.spotlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * State holder that manages the progression of a guided tour spotlight sequence.
 *
 * Use [rememberSpotlightController] to create an instance tied to the
 * composition lifetime. Call [start] to begin the tour, [next] to advance
 * to the next step, and [skip] to end the tour immediately.
 *
 * @param steps Ordered list of [SpotlightStep] definitions for this tour.
 */
class SpotlightController(
    val steps: List<SpotlightStep>,
) {
    var currentIndex by mutableIntStateOf(-1)
        private set

    /** The currently active [SpotlightStep], or null when the tour is inactive. */
    val currentStep: SpotlightStep?
        get() = steps.getOrNull(currentIndex)

    /** True while the tour is active (currentIndex is within the steps range). */
    val isActive: Boolean
        get() = currentIndex in steps.indices

    /** Fraction [0, 1] indicating how far through the tour the user is. */
    val progress: Float
        get() = if (steps.isEmpty()) 1f else (currentIndex + 1f) / steps.size

    /** Begin the tour from the first step. */
    fun start() {
        currentIndex = 0
    }

    /** Advance to the next step. When past the last step the tour becomes inactive. */
    fun next() {
        currentIndex++
    }

    /** End the tour immediately by jumping past the last step. */
    fun skip() {
        currentIndex = steps.size
    }
}

/**
 * Creates and remembers a [SpotlightController] scoped to the composition.
 * The controller is recreated whenever [steps] changes identity.
 */
@Composable
fun rememberSpotlightController(steps: List<SpotlightStep>): SpotlightController =
    remember(steps) { SpotlightController(steps) }
