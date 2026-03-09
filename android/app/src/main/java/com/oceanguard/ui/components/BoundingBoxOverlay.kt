package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.theme.MaterialFabric
import com.oceanguard.ai.ui.theme.MaterialFishingNet
import com.oceanguard.ai.ui.theme.MaterialGlass
import com.oceanguard.ai.ui.theme.MaterialMetal
import com.oceanguard.ai.ui.theme.MaterialOther
import com.oceanguard.ai.ui.theme.MaterialPlastic
import com.oceanguard.ai.ui.theme.MaterialRubber

// ---------------------------------------------------------------------------
// Colour mapping: RT-DETR class name -> material colour
// Mirrors the DebrisMaterial enum colour palette so overlays are consistent
// with DebrisCard material badges.
// ---------------------------------------------------------------------------

private val CLASS_COLOR_MAP: Map<String, Color> = mapOf(
    // Plastic-family
    "bottle"         to MaterialPlastic,
    "plastic_debris" to MaterialPlastic,
    "mask"           to MaterialPlastic,
    "glove"          to MaterialFabric,
    // Metal-family
    "can"            to MaterialMetal,
    "metal_debris"   to MaterialMetal,
    // Fishing gear
    "fishing_net"    to MaterialFishingNet,
    // Fabric
    "fabric_debris"  to MaterialFabric,
    // Rubber
    "tire"           to MaterialRubber,
    // Glass
    "glass_debris"   to MaterialGlass,
)

/**
 * Resolve an overlay colour for a given [className] (case-insensitive).
 * Falls back to [MaterialOther] when no exact match is found.
 */
internal fun colorForClass(className: String): Color =
    CLASS_COLOR_MAP[className.lowercase()] ?: MaterialOther

// ---------------------------------------------------------------------------
// Core drawing helpers
// ---------------------------------------------------------------------------

/**
 * Draw L-shaped corner brackets for a bounding box.
 *
 * Each corner gets two perpendicular lines with rounded caps.
 * Line length is proportional to the box dimensions, clamped to avoid
 * overshoot on tiny boxes or excessive length on large ones.
 */
private fun DrawScope.drawCornerBrackets(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    strokeWidth: Float,
    bracketFraction: Float = 0.15f,
) {
    val boxW = right - left
    val boxH = bottom - top
    val arm = (minOf(boxW, boxH) * bracketFraction).coerceIn(6f, 40f)
    val cap = StrokeCap.Round

    // Top-left corner
    drawLine(color, Offset(left, top), Offset(left + arm, top), strokeWidth, cap)
    drawLine(color, Offset(left, top), Offset(left, top + arm), strokeWidth, cap)

    // Top-right corner
    drawLine(color, Offset(right, top), Offset(right - arm, top), strokeWidth, cap)
    drawLine(color, Offset(right, top), Offset(right, top + arm), strokeWidth, cap)

    // Bottom-left corner
    drawLine(color, Offset(left, bottom), Offset(left + arm, bottom), strokeWidth, cap)
    drawLine(color, Offset(left, bottom), Offset(left, bottom - arm), strokeWidth, cap)

    // Bottom-right corner
    drawLine(color, Offset(right, bottom), Offset(right - arm, bottom), strokeWidth, cap)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - arm), strokeWidth, cap)
}

/**
 * Draw a multi-layer outer glow around corner brackets.
 *
 * Three concentric layers with increasing stroke width and decreasing
 * alpha produce a subtle neon-like glow behind the main brackets.
 * Must be called BEFORE drawCornerBrackets so glow renders underneath.
 */
private fun DrawScope.drawGlow(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    baseStroke: Float,
) {
    val layers = listOf(
        Pair(baseStroke + 6f, 0.03f),  // outermost: 3% alpha, widest
        Pair(baseStroke + 4f, 0.05f),  // middle:    5% alpha
        Pair(baseStroke + 2f, 0.08f),  // innermost: 8% alpha
    )
    for ((stroke, alpha) in layers) {
        drawCornerBrackets(
            left = left, top = top, right = right, bottom = bottom,
            color = color.copy(alpha = alpha),
            strokeWidth = stroke,
        )
    }
}

/**
 * Draw one detection's bounding box (brackets + glow + fill) and pill label.
 *
 * Visual pipeline per detection:
 * 1. Semi-transparent fill inside the box area
 * 2. Outer glow (3 concentric bracket layers)
 * 3. Corner brackets (foreground, rounded caps)
 * 4. Pill-shaped label chip with white border
 *
 * Confidence modulates visual intensity: higher confidence = brighter
 * brackets/fill/glow. Labels stay at fixed 85% alpha for readability.
 *
 * @param detection     The detection to render.
 * @param scaleX        Horizontal scale factor (display width; coords are [0,1]).
 * @param scaleY        Vertical scale factor (display height; coords are [0,1]).
 * @param strokeWidthPx Stroke width in pixels (caller converts from dp).
 * @param pulseAlpha    Animated alpha multiplier for the pulse effect [0.4, 1.0].
 */
internal fun DrawScope.drawDetection(
    detection: DetectionResult,
    scaleX: Float,
    scaleY: Float,
    strokeWidthPx: Float,
    pulseAlpha: Float = 1f,
) {
    val baseColor = colorForClass(detection.className)

    // Confidence-based intensity: map [0,1] -> [0.5, 1.0]
    // Low-confidence boxes appear dimmer but never invisible.
    val confidenceIntensity = 0.5f + (detection.confidence.coerceIn(0f, 1f) * 0.5f)

    // Combine pulse animation with confidence for final alpha
    val effectiveAlpha = (pulseAlpha * confidenceIntensity).coerceIn(0f, 1f)
    val color = baseColor.copy(alpha = effectiveAlpha)

    // Scale normalized [0,1] coordinates to display pixels
    val left   = detection.x1 * scaleX
    val top    = detection.y1 * scaleY
    val right  = detection.x2 * scaleX
    val bottom = detection.y2 * scaleY

    // 1. Semi-transparent fill inside the box
    val fillAlpha = 0.08f * confidenceIntensity * pulseAlpha
    drawRect(
        color   = baseColor.copy(alpha = fillAlpha),
        topLeft = Offset(left, top),
        size    = Size(right - left, bottom - top),
    )

    // 2. Outer glow (behind brackets)
    drawGlow(
        left = left, top = top, right = right, bottom = bottom,
        color = baseColor.copy(alpha = effectiveAlpha),
        baseStroke = strokeWidthPx,
    )

    // 3. Corner brackets (foreground)
    drawCornerBrackets(
        left = left, top = top, right = right, bottom = bottom,
        color = color,
        strokeWidth = strokeWidthPx,
    )

    // 4. Pill-shaped label chip
    val label      = "${detection.className} ${(detection.confidence * 100).toInt()}%"
    val textSizePx = 12.dp.toPx()
    val paddingH   = 6.dp.toPx()
    val paddingV   = 3.dp.toPx()

    drawIntoCanvas { canvas ->
        val textPaint = android.graphics.Paint().apply {
            this.color       = android.graphics.Color.WHITE
            this.textSize    = textSizePx
            this.isAntiAlias = true
            this.typeface    = android.graphics.Typeface.DEFAULT_BOLD
        }

        val textWidth  = textPaint.measureText(label)
        val chipHeight = textSizePx + paddingV * 2
        val chipRadius = chipHeight / 2f   // full pill shape

        // Position above the box with a small gap, clamped to canvas top
        val chipTop    = (top - chipHeight - 4.dp.toPx()).coerceAtLeast(0f)
        val chipBottom = chipTop + chipHeight
        val chipLeft   = left
        val chipRight  = (left + textWidth + paddingH * 2).coerceAtMost(size.width)

        val chipRect = android.graphics.RectF(chipLeft, chipTop, chipRight, chipBottom)

        // Pill background with material color at 85% alpha
        val bgPaint = android.graphics.Paint().apply {
            this.color     = baseColor.copy(alpha = 0.85f).toArgb()
            this.style     = android.graphics.Paint.Style.FILL
            this.isAntiAlias = true
        }
        canvas.nativeCanvas.drawRoundRect(chipRect, chipRadius, chipRadius, bgPaint)

        // Thin white border on the pill (20% white, 1px)
        val borderPaint = android.graphics.Paint().apply {
            this.color       = android.graphics.Color.argb(51, 255, 255, 255)
            this.style       = android.graphics.Paint.Style.STROKE
            this.strokeWidth = 1f
            this.isAntiAlias = true
        }
        canvas.nativeCanvas.drawRoundRect(chipRect, chipRadius, chipRadius, borderPaint)

        // White label text centered vertically within the pill
        canvas.nativeCanvas.drawText(
            label,
            chipLeft + paddingH,
            chipBottom - paddingV,
            textPaint,
        )
    }
}

// ---------------------------------------------------------------------------
// Overload 1 — ImageBitmap
// ---------------------------------------------------------------------------

/**
 * Overlays bounding boxes from [detections] on top of [imageBitmap].
 *
 * Each box is drawn with corner brackets, outer glow, and semi-transparent
 * fill, coloured by the material type inferred from [DetectionResult.className].
 * A pill-shaped label chip above each box shows the class name and confidence.
 *
 * Animations:
 * - Pulse: continuous alpha oscillation (0.4 → 1.0) for a "living" feel.
 * - Entrance: scale + fade when detections first appear.
 *
 * When [imageBitmap] is null a placeholder surface is shown instead.
 */
@Composable
fun BoundingBoxOverlay(
    imageBitmap: ImageBitmap?,
    detections : List<DetectionResult>,
    modifier   : Modifier = Modifier
) {
    var displaySize by remember { mutableStateOf(IntSize.Zero) }

    // --- Animations ---
    val infiniteTransition = rememberInfiniteTransition(label = "bboxPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bboxPulseAlpha",
    )

    // Entrance: triggered when a new detection list arrives
    var entranceTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(detections) {
        entranceTriggered = detections.isNotEmpty()
    }
    val entranceScale by animateFloatAsState(
        targetValue   = if (entranceTriggered) 1f else 0.8f,
        animationSpec = tween(durationMillis = 400, easing = EaseInOut),
        label         = "bboxEntranceScale",
    )
    val entranceAlpha by animateFloatAsState(
        targetValue   = if (entranceTriggered) 1f else 0f,
        animationSpec = tween(durationMillis = 400, easing = EaseInOut),
        label         = "bboxEntranceAlpha",
    )

    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            // Image layer: fills the composable and reports its rendered size.
            androidx.compose.foundation.Image(
                bitmap             = imageBitmap,
                contentDescription = "Captured image for debris analysis",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxSize()
                    .onSizeChanged { displaySize = it }
            )

            // Overlay layer: brackets + glow + animations
            if (displaySize != IntSize.Zero && detections.isNotEmpty()) {
                val scaleX = displaySize.width.toFloat()
                val scaleY = displaySize.height.toFloat()

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = entranceScale,
                            scaleY = entranceScale,
                            alpha  = entranceAlpha,
                        )
                ) {
                    val strokePx = 3.dp.toPx()
                    for (detection in detections) {
                        drawDetection(detection, scaleX, scaleY, strokePx, pulseAlpha)
                    }
                }
            }
        } else {
            // Placeholder when no image is available yet.
            Box(
                modifier         = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text      = "No image available",
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Overload 2 — URI string (loaded with Coil)
// ---------------------------------------------------------------------------

/**
 * URI-based overload: loads the image asynchronously with Coil and renders
 * the bounding box overlay with corner brackets, glow, and animations.
 */
@Composable
fun BoundingBoxOverlay(
    imageUri  : String?,
    detections: List<DetectionResult>,
    modifier  : Modifier = Modifier
) {
    var displaySize by remember { mutableStateOf(IntSize.Zero) }

    // --- Animations ---
    val infiniteTransition = rememberInfiniteTransition(label = "bboxPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bboxPulseAlpha",
    )

    var entranceTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(detections) {
        entranceTriggered = detections.isNotEmpty()
    }
    val entranceScale by animateFloatAsState(
        targetValue   = if (entranceTriggered) 1f else 0.8f,
        animationSpec = tween(durationMillis = 400, easing = EaseInOut),
        label         = "bboxEntranceScale",
    )
    val entranceAlpha by animateFloatAsState(
        targetValue   = if (entranceTriggered) 1f else 0f,
        animationSpec = tween(durationMillis = 400, easing = EaseInOut),
        label         = "bboxEntranceAlpha",
    )

    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (imageUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUri)
                    .build(),
                contentDescription = "Captured image for debris analysis",
                contentScale       = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { displaySize = it }
            )

            // Draw overlay once the display size is known.
            if (displaySize != IntSize.Zero && detections.isNotEmpty()) {
                val scaleX = displaySize.width.toFloat()
                val scaleY = displaySize.height.toFloat()

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = entranceScale,
                            scaleY = entranceScale,
                            alpha  = entranceAlpha,
                        )
                ) {
                    val strokePx = 3.dp.toPx()
                    for (detection in detections) {
                        drawDetection(detection, scaleX, scaleY, strokePx, pulseAlpha)
                    }
                }
            }
        } else {
            // Null URI placeholder
            Box(
                modifier         = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text      = "No image available",
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
