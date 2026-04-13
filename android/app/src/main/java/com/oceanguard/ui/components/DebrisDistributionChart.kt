package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.ui.theme.MaterialFabric
import com.oceanguard.ai.ui.theme.MaterialFishingNet
import com.oceanguard.ai.ui.theme.MaterialGlass
import com.oceanguard.ai.ui.theme.MaterialMetal
import com.oceanguard.ai.ui.theme.MaterialOther
import com.oceanguard.ai.ui.theme.MaterialPlastic
import com.oceanguard.ai.ui.theme.MaterialRubber

// ---------------------------------------------------------------------------
// Colour resolution for debris materials
// ---------------------------------------------------------------------------

/**
 * Returns the branded palette colour for a given [DebrisMaterial].
 * Defined locally so this component is self-contained without depending
 * on the internal visibility of helpers from other packages.
 */
private fun debrisMaterialColor(material: DebrisMaterial): Color = when (material) {
    DebrisMaterial.PLASTIC     -> MaterialPlastic
    DebrisMaterial.METAL       -> MaterialMetal
    DebrisMaterial.FABRIC      -> MaterialFabric
    DebrisMaterial.RUBBER      -> MaterialRubber
    DebrisMaterial.GLASS       -> MaterialGlass
    DebrisMaterial.FISHING_NET -> MaterialFishingNet
    DebrisMaterial.WOOD        -> Color(0xFF8D6E63)
    DebrisMaterial.PAPER       -> Color(0xFFD7CCC8)
    DebrisMaterial.CERAMIC     -> Color(0xFFFF8A65)
    DebrisMaterial.CHEMICAL    -> Color(0xFFEF5350)
    DebrisMaterial.OTHER       -> MaterialOther
}

/** Human-readable label for each material enum value. */
private fun DebrisMaterial.displayLabel(): String = when (this) {
    DebrisMaterial.PLASTIC     -> "Plastic"
    DebrisMaterial.METAL       -> "Metal"
    DebrisMaterial.FABRIC      -> "Fabric"
    DebrisMaterial.RUBBER      -> "Rubber"
    DebrisMaterial.GLASS       -> "Glass"
    DebrisMaterial.FISHING_NET -> "Fishing Net"
    DebrisMaterial.WOOD        -> "Wood"
    DebrisMaterial.PAPER       -> "Paper"
    DebrisMaterial.CERAMIC     -> "Ceramic"
    DebrisMaterial.CHEMICAL    -> "Hazardous"
    DebrisMaterial.OTHER       -> "Other"
}

// ---------------------------------------------------------------------------
// Internal chart constants
// ---------------------------------------------------------------------------

/** Vertical spacing between individual bar rows. */
private val ROW_SPACING: Dp = 10.dp

/** Height of each bar rectangle. */
private val BAR_HEIGHT: Dp = 22.dp

/** Width of the label column to the left of the bars. */
private val LABEL_COLUMN_WIDTH: Dp = 88.dp

/** Width of the count text shown to the right of each bar. */
private val COUNT_COLUMN_WIDTH: Dp = 32.dp

/** Minimum fraction of total canvas width a bar occupies even for count = 0. */
private const val BAR_MIN_FRACTION = 0.02f

/** Corner radius applied to bar rectangles. */
private val BAR_CORNER_RADIUS: Dp = 4.dp

// Cached Paint objects — allocated once, reused every Canvas draw.
// Canvas rendering is always on the main thread, so no synchronisation needed.
private val cachedMaterialLabelPaint = android.graphics.Paint().apply {
    isAntiAlias = true
    textAlign   = android.graphics.Paint.Align.LEFT
}

private val cachedCountLabelPaint = android.graphics.Paint().apply {
    isAntiAlias = true
    textAlign   = android.graphics.Paint.Align.LEFT
    typeface    = android.graphics.Typeface.DEFAULT_BOLD
}

// ---------------------------------------------------------------------------
// Public composable
// ---------------------------------------------------------------------------

/**
 * Horizontal bar chart showing the distribution of detected debris by material.
 *
 * Each bar is drawn with the material's branded colour from the OceanGuard
 * palette. Bars are sorted by count descending so the most prevalent material
 * is always at the top.
 *
 * Implementation uses Compose [Canvas] directly rather than a third-party
 * chart library, keeping the dependency footprint minimal and guaranteeing
 * compatibility with the existing Compose BOM.
 *
 * Accessibility: the card carries a merged semantic description listing each
 * material and its count so TalkBack can announce the full breakdown.
 *
 * @param materialBreakdown Map of [DebrisMaterial] to the count of detected
 *                          objects with that material. Empty maps are handled
 *                          gracefully with a placeholder message.
 * @param modifier          Standard Compose modifier.
 */
@Composable
fun DebrisDistributionChart(
    materialBreakdown: Map<DebrisMaterial, Int>,
    modifier: Modifier = Modifier,
) {
    // Sort entries by count descending; produce a stable list for composition.
    val sortedEntries = remember(materialBreakdown) {
        materialBreakdown.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
    }

    // Build a single semantic string for accessibility (WCAG 2.1 SC 1.3.1).
    val a11yDescription = remember(sortedEntries) {
        if (sortedEntries.isEmpty()) {
            "Debris distribution chart: no data available"
        } else {
            val items = sortedEntries.joinToString("; ") {
                "${it.key.displayLabel()} ${it.value}"
            }
            "Debris distribution chart: $items"
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yDescription },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // ----------------------------------------------------------------
            // Card title
            // ----------------------------------------------------------------
            Text(
                text = "Debris Distribution",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Count by material type",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ----------------------------------------------------------------
            // Empty state
            // ----------------------------------------------------------------
            if (sortedEntries.isEmpty()) {
                EmptyChartPlaceholder(message = "No debris data to display")
                return@Card
            }

            // ----------------------------------------------------------------
            // Canvas-based horizontal bar chart
            // ----------------------------------------------------------------
            HorizontalBarChart(
                entries = sortedEntries,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ----------------------------------------------------------------
            // Legend row: coloured dot + label for each material present
            // ----------------------------------------------------------------
            LegendRow(entries = sortedEntries)
        }
    }
}

// ---------------------------------------------------------------------------
// Canvas bar chart
// ---------------------------------------------------------------------------

/**
 * Draws horizontal bars for each (material, count) entry using Compose Canvas.
 * The bar width is proportional to the entry count relative to the maximum.
 */
@Composable
private fun HorizontalBarChart(
    entries: List<Map.Entry<DebrisMaterial, Int>>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    // Pre-convert Dp constants to pixels for use inside the Canvas lambda.
    val rowSpacingPx   = with(density) { ROW_SPACING.toPx() }
    val barHeightPx    = with(density) { BAR_HEIGHT.toPx() }
    val labelColWidthPx = with(density) { LABEL_COLUMN_WIDTH.toPx() }
    val countColWidthPx = with(density) { COUNT_COLUMN_WIDTH.toPx() }
    val cornerRadiusPx  = with(density) { BAR_CORNER_RADIUS.toPx() }
    val textSizePx      = with(density) { 11.sp.toPx() }

    val maxCount = entries.maxOf { it.value }.coerceAtLeast(1)

    // --- Staggered bar entrance animation ---
    var animationTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(entries) { animationTarget = 1f }

    // Per-bar animated fractions with staggered delays
    val barAnimations = entries.mapIndexed { index, _ ->
        animateFloatAsState(
            targetValue = animationTarget,
            animationSpec = tween(
                durationMillis = 600,
                delayMillis = index * 80,
                easing = FastOutSlowInEasing,
            ),
            label = "bar$index",
        )
    }

    // Total canvas height: one row per entry plus spacing between them.
    val totalRows = entries.size
    val canvasHeightDp = with(density) {
        ((barHeightPx * totalRows) + (rowSpacingPx * (totalRows - 1).coerceAtLeast(0)))
            .toDp()
    }

    // Muted label colour for the native text paint.
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier.height(canvasHeightDp),
    ) {
        val availableBarWidth = size.width - labelColWidthPx - countColWidthPx - 8.dp.toPx()

        entries.forEachIndexed { index, entry ->
            val material = entry.key
            val count    = entry.value
            val barColor = debrisMaterialColor(material)
            val animFraction = barAnimations[index].value

            val rowTop = index * (barHeightPx + rowSpacingPx)
            val barFraction = (count.toFloat() / maxCount).coerceAtLeast(BAR_MIN_FRACTION)
            val barWidth = availableBarWidth * barFraction * animFraction

            // Draw the label on the left using nativeCanvas for text.
            drawMaterialLabel(
                label      = material.displayLabel(),
                x          = 0f,
                y          = rowTop + barHeightPx / 2f,
                maxWidth   = labelColWidthPx - 8.dp.toPx(),
                textSizePx = textSizePx,
                color      = labelColor,
            )

            // Glow behind the bar (subtle colored shadow)
            if (barWidth > 0f) {
                drawRoundRect(
                    color       = barColor.copy(alpha = 0.10f * animFraction),
                    topLeft     = Offset(
                        x = labelColWidthPx - 2.dp.toPx(),
                        y = rowTop - 2.dp.toPx(),
                    ),
                    size        = Size(
                        width = barWidth + 4.dp.toPx(),
                        height = barHeightPx + 4.dp.toPx(),
                    ),
                    cornerRadius = CornerRadius(cornerRadiusPx + 2.dp.toPx()),
                )
            }

            // Draw the bar rectangle.
            drawRoundRect(
                color       = barColor,
                topLeft     = Offset(x = labelColWidthPx, y = rowTop),
                size        = Size(width = barWidth, height = barHeightPx),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                alpha       = 0.90f,
            )

            // Draw the track (unfilled portion) for visual context.
            val fullBarWidth = availableBarWidth * barFraction
            drawRoundRect(
                color        = barColor.copy(alpha = 0.15f),
                topLeft      = Offset(x = labelColWidthPx + barWidth, y = rowTop),
                size         = Size(
                    width  = (fullBarWidth - barWidth + availableBarWidth * (1f - barFraction))
                        .coerceAtLeast(0f),
                    height = barHeightPx,
                ),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
            )

            // Draw count value to the right of the track.
            val countX = labelColWidthPx + availableBarWidth + 8.dp.toPx()
            drawCountLabel(
                label      = count.toString(),
                x          = countX,
                y          = rowTop + barHeightPx / 2f,
                textSizePx = textSizePx,
                color      = labelColor,
            )
        }
    }
}

/** Draws a left-aligned material label truncated to [maxWidth]. */
private fun DrawScope.drawMaterialLabel(
    label: String,
    x: Float,
    y: Float,
    maxWidth: Float,
    textSizePx: Float,
    color: Color,
) {
    drawContext.canvas.nativeCanvas.apply {
        cachedMaterialLabelPaint.color    = color.toArgb()
        cachedMaterialLabelPaint.textSize = textSizePx
        // Truncate label to fit maxWidth.
        val truncated   = truncateText(label, cachedMaterialLabelPaint, maxWidth)
        // Centre vertically within the bar row.
        val fontMetrics = cachedMaterialLabelPaint.fontMetrics
        val textHeight  = fontMetrics.descent - fontMetrics.ascent
        val textY       = y - textHeight / 2f - fontMetrics.ascent
        drawText(truncated, x, textY, cachedMaterialLabelPaint)
    }
}

/** Draws a right-aligned count label. */
private fun DrawScope.drawCountLabel(
    label: String,
    x: Float,
    y: Float,
    textSizePx: Float,
    color: Color,
) {
    drawContext.canvas.nativeCanvas.apply {
        cachedCountLabelPaint.color    = color.toArgb()
        cachedCountLabelPaint.textSize = textSizePx
        val fontMetrics = cachedCountLabelPaint.fontMetrics
        val textHeight  = fontMetrics.descent - fontMetrics.ascent
        val textY       = y - textHeight / 2f - fontMetrics.ascent
        drawText(label, x, textY, cachedCountLabelPaint)
    }
}

/** Truncates [text] to fit within [maxWidthPx] using the given [paint]. */
private fun truncateText(
    text: String,
    paint: android.graphics.Paint,
    maxWidthPx: Float,
): String {
    if (paint.measureText(text) <= maxWidthPx) return text
    val ellipsis = "\u2026"
    var truncated = text
    while (truncated.isNotEmpty() &&
        paint.measureText(truncated + ellipsis) > maxWidthPx
    ) {
        truncated = truncated.dropLast(1)
    }
    return truncated + ellipsis
}

// ---------------------------------------------------------------------------
// Legend
// ---------------------------------------------------------------------------

/**
 * Horizontal wrapping legend row showing a coloured dot and label for each
 * material present in the chart. Wraps naturally using a [Row] + [Column]
 * layout without a third-party flow layout dependency.
 */
@Composable
private fun LegendRow(entries: List<Map.Entry<DebrisMaterial, Int>>) {
    // Split into two columns of equal length for compact display.
    val half = (entries.size + 1) / 2
    val firstColumn  = entries.take(half)
    val secondColumn = entries.drop(half)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendColumn(items = firstColumn, modifier = Modifier.weight(1f))
        if (secondColumn.isNotEmpty()) {
            LegendColumn(items = secondColumn, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LegendColumn(
    items: List<Map.Entry<DebrisMaterial, Int>>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { entry ->
            LegendItem(material = entry.key, count = entry.value)
        }
    }
}

@Composable
private fun LegendItem(material: DebrisMaterial, count: Int) {
    val color = debrisMaterialColor(material)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Text(
            text = "${material.displayLabel()} ($count)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Empty state placeholder (reusable within this file)
// ---------------------------------------------------------------------------

@Composable
private fun EmptyChartPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
