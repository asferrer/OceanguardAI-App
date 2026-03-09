package com.oceanguard.ai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Semantic colours for model readiness states
// ---------------------------------------------------------------------------

/** Model is ready and accepting inference requests. */
private val StatusReady   = Color(0xFF4CAF50)   // HealthExcellent green

/** Model is still loading / warming up. */
private val StatusLoading = Color(0xFFFFC107)   // HealthFair amber

/** Model failed to initialise. */
private val StatusFailed  = Color(0xFFF44336)   // HealthCritical red

// ---------------------------------------------------------------------------
// State enum
// ---------------------------------------------------------------------------

/**
 * Logical readiness state for a single model component.
 *
 * Currently the public API takes two separate Boolean parameters to keep the
 * call site simple. [ModelReadiness] is used internally for colour selection
 * and animation decisions.
 */
private enum class ModelReadiness { READY, LOADING, FAILED }

private fun boolToReadiness(isReady: Boolean, isFailed: Boolean = false): ModelReadiness = when {
    isReady  -> ModelReadiness.READY
    isFailed -> ModelReadiness.FAILED
    else     -> ModelReadiness.LOADING
}

private fun readinessColor(state: ModelReadiness): Color = when (state) {
    ModelReadiness.READY   -> StatusReady
    ModelReadiness.LOADING -> StatusLoading
    ModelReadiness.FAILED  -> StatusFailed
}

private fun readinessLabel(state: ModelReadiness): String = when (state) {
    ModelReadiness.READY   -> "Ready"
    ModelReadiness.LOADING -> "Loading"
    ModelReadiness.FAILED  -> "Failed"
}

// ---------------------------------------------------------------------------
// Single chip composable (internal)
// ---------------------------------------------------------------------------

/**
 * A small pill-shaped chip with a status dot and label.
 *
 * When [readiness] is [ModelReadiness.LOADING] the dot pulses via an
 * infinite alpha animation to communicate ongoing work without cluttering
 * the header area with a full progress indicator.
 *
 * @param modelName Human-readable model label ("VLM" or "Detector").
 * @param readiness Current readiness state of the model.
 * @param modifier  Standard Compose modifier.
 */
@Composable
private fun ModelChip(
    modelName: String,
    readiness : ModelReadiness,
    modifier  : Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue   = readinessColor(readiness),
        animationSpec = tween(durationMillis = 400),
        label         = "${modelName}DotColor"
    )

    // Pulsing alpha only while loading
    val pulseAlpha: Float = if (readiness == ModelReadiness.LOADING) {
        val infiniteTransition = rememberInfiniteTransition(label = "${modelName}Pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue   = 0.35f,
            targetValue    = 1f,
            animationSpec  = infiniteRepeatable(
                animation  = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "${modelName}Alpha"
        )
        alpha
    } else {
        1f
    }

    val a11y = "$modelName model: ${readinessLabel(readiness)}"

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .semantics { contentDescription = a11y },
        shape     = RoundedCornerShape(50),
        color     = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier            = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .alpha(pulseAlpha)
                    .background(dotColor)
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Model label
            Text(
                text       = modelName,
                style      = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Readiness text ("Ready" / "Loading" / "Failed")
            Text(
                text  = readinessLabel(readiness),
                style = MaterialTheme.typography.labelSmall,
                color = dotColor
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Public composable
// ---------------------------------------------------------------------------

/**
 * A compact status bar showing the readiness of both on-device models.
 *
 * Intended to be placed at the top of HomeScreen as a persistent indicator
 * so users always know whether detections will run before capturing an image.
 *
 * Two chips are rendered side by side:
 * - "VLM"      — Gemma 3n + OceanGuard LoRA (slow to load, ~3-5 s)
 * - "Detector" — RT-DETRv2 TFLite (fast to load, ~200-500 ms)
 *
 * Color semantics:
 * - Green  : model ready.
 * - Amber  : model loading (dot pulses).
 * - Red    : model failed to initialise.
 *
 * The component deliberately avoids occupying too much vertical space so it
 * does not compete with the camera viewfinder or image preview.
 *
 * @param isVLMReady    Pass `true` when [OceanGuardInference] has finished
 *                      initialising. Pass `false` while loading or on error.
 * @param isRTDETRReady Pass `true` when [RTDETRInference] reports [isReady].
 * @param modifier      Standard Compose modifier.
 */
@Composable
fun ModelStatusIndicator(
    isVLMReady    : Boolean,
    isRTDETRReady : Boolean,
    modifier      : Modifier = Modifier
) {
    // For this iteration the API only exposes isReady booleans. When a
    // dedicated "failed" state is surfaced from ViewModels the internal
    // ModelReadiness.FAILED branch will activate automatically — the public
    // API signature does not need to change.
    val vlmState    = boolToReadiness(isVLMReady)
    val detectorState = boolToReadiness(isRTDETRReady)

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        ModelChip(
            modelName = "VLM",
            readiness  = vlmState
        )
        ModelChip(
            modelName = "Detector",
            readiness  = detectorState
        )
    }
}

// ---------------------------------------------------------------------------
// Extended overload — explicit failed states
// ---------------------------------------------------------------------------

/**
 * Extended overload that accepts explicit failure flags for each model.
 *
 * Use this version when a ViewModel distinguishes between "still loading"
 * and "init failed" (e.g. GPU not available, model file missing).
 *
 * @param isVLMReady      Whether the VLM model initialised successfully.
 * @param isVLMFailed     Whether the VLM model failed to initialise.
 * @param isRTDETRReady   Whether the detector model initialised successfully.
 * @param isRTDETRFailed  Whether the detector model failed to initialise.
 * @param modifier        Standard Compose modifier.
 */
@Composable
fun ModelStatusIndicator(
    isVLMReady     : Boolean,
    isVLMFailed    : Boolean,
    isRTDETRReady  : Boolean,
    isRTDETRFailed : Boolean,
    modifier       : Modifier = Modifier
) {
    val vlmState      = boolToReadiness(isVLMReady,    isVLMFailed)
    val detectorState = boolToReadiness(isRTDETRReady, isRTDETRFailed)

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        ModelChip(
            modelName = "VLM",
            readiness  = vlmState
        )
        ModelChip(
            modelName = "Detector",
            readiness  = detectorState
        )
    }
}
