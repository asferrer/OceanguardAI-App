package com.oceanguard.ai.ui.components.spotlight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlinx.coroutines.delay

/**
 * Full-screen dimming overlay that highlights a target composable and shows
 * a navigation tooltip for the current guided tour step.
 *
 * Target bounds are stored in **window** coordinates by [spotlightTarget].
 * This composable tracks its own window position and converts to local
 * coordinates, so cutouts align correctly on every display density and
 * regardless of system-bar offsets or Scaffold padding.
 *
 * @param controller        The [SpotlightController] managing tour state.
 * @param targetBounds      Map of target IDs to their window-space [Rect]s.
 * @param onComplete        Called when the tour finishes (last step or skipped).
 * @param scrollState       Optional [ScrollState] for Column+verticalScroll screens;
 *                          the overlay auto-scrolls to bring off-screen targets into view.
 * @param onScrollToTarget  Optional callback for screens with [LazyColumn] or custom
 *                          scroll containers. Called with the target ID when the overlay
 *                          needs to make a target visible (e.g. scrolling a LazyList to
 *                          the correct item index).
 */
@Composable
fun SpotlightOverlay(
    controller: SpotlightController,
    targetBounds: Map<String, Rect>,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    onScrollToTarget: (suspend (String) -> Unit)? = null,
    isGuidedTour: Boolean = false,
    onSkipTutorial: (() -> Unit)? = null,
) {
    if (!controller.isActive) return

    val step = controller.currentStep ?: return
    val density = LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }
    val cutoutPaddingPx = with(density) { 8.dp.toPx() }

    // Track the overlay's own position in window coordinates so we can
    // convert target window-coords → overlay-local coords.
    var overlayWindowOffset by remember { mutableStateOf(Offset.Zero) }
    var overlayHeight by remember { mutableIntStateOf(0) }

    // Convert the current target's window bounds to overlay-local space.
    val windowBounds = targetBounds[step.targetId]
    val localBounds = windowBounds?.translate(
        -overlayWindowOffset.x,
        -overlayWindowOffset.y,
    )

    // Auto-scroll to bring the target into view when the step changes.
    LaunchedEffect(controller.currentIndex) {
        val targetId = controller.currentStep?.targetId ?: return@LaunchedEffect

        // Wait for layout to settle after step change.
        delay(200)

        // Phase 1: If the target is not yet composed (e.g. off-screen in a
        // LazyColumn), ask the host screen to scroll it into view first.
        if (targetBounds[targetId] == null && onScrollToTarget != null) {
            onScrollToTarget(targetId)
            delay(400)
        }

        // Phase 2: Always scroll so the target is centred at ~35 % from the
        // top of the viewport, leaving room for the tooltip underneath.
        val wb = targetBounds[targetId] ?: return@LaunchedEffect
        val localTop = wb.top - overlayWindowOffset.y
        val localBottom = wb.bottom - overlayWindowOffset.y
        val viewH = overlayHeight.toFloat()
        if (viewH <= 0f) return@LaunchedEffect

        val targetCenter = (localTop + localBottom) / 2f
        val idealY = viewH * 0.35f
        val delta = targetCenter - idealY

        // Skip negligible adjustments (< 20 px).
        if (kotlin.math.abs(delta) < 20f) return@LaunchedEffect

        if (scrollState != null) {
            scrollState.animateScrollTo(
                (scrollState.value + delta.toInt()).coerceIn(0, scrollState.maxValue),
            )
        } else if (onScrollToTarget != null) {
            onScrollToTarget(targetId)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                overlayWindowOffset = coords.positionInWindow()
                overlayHeight = coords.size.height
            }
            .pointerInput(Unit) { /* Consume all touch events */ },
    ) {
        SpotlightCanvas(step = step, bounds = localBounds)

        SpotlightTooltip(
            step = step,
            controller = controller,
            onComplete = onComplete,
            isGuidedTour = isGuidedTour,
            onSkipTutorial = onSkipTutorial,
            modifier = if (localBounds != null) {
                Modifier.tooltipNearTarget(
                    targetBounds = localBounds,
                    gapPx = gapPx,
                    cutoutPaddingPx = cutoutPaddingPx,
                )
            } else {
                Modifier.align(Alignment.Center)
            },
        )
    }
}

/**
 * Custom layout modifier that positions the tooltip directly above or below
 * the spotlight target using overlay-local pixel coordinates.
 */
private fun Modifier.tooltipNearTarget(
    targetBounds: Rect,
    gapPx: Float,
    cutoutPaddingPx: Float,
): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, minHeight = 0),
    )

    layout(constraints.maxWidth, constraints.maxHeight) {
        val parentW = constraints.maxWidth
        val parentH = constraints.maxHeight

        // Vertical: pick whichever side has more room
        val spaceAbove = targetBounds.top - cutoutPaddingPx - gapPx
        val spaceBelow = parentH - targetBounds.bottom - cutoutPaddingPx - gapPx

        val y = if (spaceBelow >= placeable.height || spaceBelow >= spaceAbove) {
            (targetBounds.bottom + cutoutPaddingPx + gapPx).toInt()
                .coerceAtMost(parentH - placeable.height)
        } else {
            (targetBounds.top - cutoutPaddingPx - gapPx).toInt() - placeable.height
        }.coerceIn(0, (parentH - placeable.height).coerceAtLeast(0))

        // Horizontal: centre on the target, clamped to screen edges
        val targetCenterX = targetBounds.center.x.toInt()
        val maxX = (parentW - placeable.width).coerceAtLeast(0)
        val x = (targetCenterX - placeable.width / 2).coerceIn(0, maxX)

        placeable.place(x, y)
    }
}

@Composable
private fun SpotlightCanvas(
    step: SpotlightStep,
    bounds: Rect?,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawRect(color = Color.Black.copy(alpha = 0.75f))

        if (bounds == null) return@Canvas

        val padding = 8.dp.toPx()

        when (step.shape) {
            SpotlightShape.ROUNDED_RECT -> {
                val cutoutLeft = bounds.left - padding
                val cutoutTop = bounds.top - padding
                val cutoutWidth = bounds.width + padding * 2
                val cutoutHeight = bounds.height + padding * 2
                val radius = 12.dp.toPx()

                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(cutoutLeft, cutoutTop),
                    size = Size(cutoutWidth, cutoutHeight),
                    cornerRadius = CornerRadius(radius),
                    blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    color = OceanGreen.copy(alpha = 0.6f),
                    topLeft = Offset(cutoutLeft, cutoutTop),
                    size = Size(cutoutWidth, cutoutHeight),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            SpotlightShape.CIRCLE -> {
                val radius = maxOf(bounds.width, bounds.height) / 2 + padding
                drawCircle(
                    color = Color.Transparent,
                    radius = radius,
                    center = bounds.center,
                    blendMode = BlendMode.Clear,
                )
                drawCircle(
                    color = OceanGreen.copy(alpha = 0.6f),
                    radius = radius,
                    center = bounds.center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun SpotlightTooltip(
    step: SpotlightStep,
    controller: SpotlightController,
    onComplete: () -> Unit,
    isGuidedTour: Boolean = false,
    onSkipTutorial: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val totalSteps = controller.steps.size
    val currentStep = controller.currentIndex + 1
    val isLastStep = currentStep == totalSteps

    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 380.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(step.titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(step.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$currentStep / $totalSteps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        controller.skip()
                        if (isGuidedTour && onSkipTutorial != null) {
                            onSkipTutorial()
                        } else {
                            onComplete()
                        }
                    },
                ) {
                    Text(
                        text = if (isGuidedTour) {
                            stringResource(R.string.guided_tour_btn_skip_tutorial)
                        } else {
                            stringResource(R.string.tour_btn_skip)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        controller.next()
                        if (!controller.isActive) onComplete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OceanGreen),
                ) {
                    Text(
                        text = if (isLastStep) {
                            stringResource(R.string.tour_btn_done)
                        } else {
                            stringResource(R.string.tour_btn_next)
                        },
                    )
                }
            }
        }
    }
}
