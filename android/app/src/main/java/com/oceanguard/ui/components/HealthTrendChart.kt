package com.oceanguard.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oceanguard.ai.data.DetectionSession
import java.text.SimpleDateFormat
import java.util.Locale

import com.oceanguard.ai.ui.theme.healthScoreColor

// ---------------------------------------------------------------------------
// Layout constants
// ---------------------------------------------------------------------------

/** Height of the chart drawing area. */
private val CHART_HEIGHT = 180.dp

/** Padding inside the canvas area (all sides). */
private val CHART_PADDING = 12.dp

/** Left axis label column width. */
private val Y_AXIS_WIDTH = 28.dp

/** Bottom date label row height. */
private val X_AXIS_HEIGHT = 24.dp

/** Radius of the dot drawn at each data point. */
private val DOT_RADIUS = 5.dp

/** Stroke width of the line connecting data points. */
private val LINE_STROKE = 2.dp

/** Stroke width of the dashed grid lines. */
private val GRID_STROKE = 1.dp

/** Y-axis fixed range: health score 0-100. */
private const val Y_MIN = 0f
private const val Y_MAX = 100f

// ---------------------------------------------------------------------------
// Public composable
// ---------------------------------------------------------------------------

/**
 * Line chart composable showing how the ecosystem health score has evolved
 * across detection sessions ordered by timestamp.
 *
 * Each data point is rendered as a circle whose fill colour maps to the
 * health band for that score (Critical -> Excellent), and the line connecting
 * them uses the colour of the most recent session's score.
 *
 * Dashed horizontal grid lines are drawn at 25, 50, and 75 to give visual
 * reference without cluttering a dark background.
 *
 * Implementation uses Compose [Canvas] exclusively, requiring no additional
 * chart library beyond the existing Compose BOM.
 *
 * Accessibility: a merged semantic description lists up to 5 recent sessions
 * with their scores and dates so TalkBack can announce the trend.
 *
 * @param sessions List of [DetectionSession]s to plot. The list is sorted
 *                 ascending by timestamp internally. Empty and single-point
 *                 states are handled gracefully.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun HealthTrendChart(
    sessions: List<DetectionSession>,
    modifier: Modifier = Modifier,
) {
    // Sort ascending by time and take a maximum of 30 points for legibility.
    val points = remember(sessions) {
        sessions.sortedBy { it.timestamp }.takeLast(30)
    }

    val dateFormatter = remember { SimpleDateFormat("M/d", Locale.getDefault()) }

    // Accessibility description listing the 5 most recent entries.
    val a11yDescription = remember(points) {
        if (points.isEmpty()) {
            "Health trend chart: no session data available"
        } else {
            val recent = points.takeLast(5).joinToString("; ") { session ->
                "${dateFormatter.format(session.timestamp)}: ${session.healthScore}"
            }
            "Health score trend. Most recent sessions: $recent"
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Health Score Trend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (points.isEmpty()) {
                            "No analyses recorded"
                        } else {
                            "${points.size} analys${if (points.size != 1) "es" else "is"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Current average badge
                if (points.isNotEmpty()) {
                    val avg = points.map { it.healthScore }.average().toInt()
                    val avgColor = healthScoreColor(avg)
                    Text(
                        text = "avg $avg",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = avgColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ----------------------------------------------------------------
            // Chart or empty state
            // ----------------------------------------------------------------
            when {
                points.isEmpty() -> {
                    EmptyTrendPlaceholder()
                }
                points.size == 1 -> {
                    SinglePointPlaceholder(session = points.first(), formatter = dateFormatter)
                }
                else -> {
                    TrendLineChart(
                        points = points,
                        dateFormatter = dateFormatter,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Canvas line chart
// ---------------------------------------------------------------------------

/**
 * Draws a line chart for [points] using Compose Canvas.
 *
 * The drawing area is divided into:
 * - Left column for Y-axis labels (0, 25, 50, 75, 100)
 * - Bottom row for X-axis date labels (first, middle, last when > 2 points)
 * - Remaining area for the chart body with grid + line + dots
 */
@Composable
private fun TrendLineChart(
    points: List<DetectionSession>,
    dateFormatter: SimpleDateFormat,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    val chartPaddingPx = with(density) { CHART_PADDING.toPx() }
    val yAxisWidthPx   = with(density) { Y_AXIS_WIDTH.toPx() }
    val xAxisHeightPx  = with(density) { X_AXIS_HEIGHT.toPx() }
    val dotRadiusPx    = with(density) { DOT_RADIUS.toPx() }
    val lineStrokePx   = with(density) { LINE_STROKE.toPx() }
    val gridStrokePx   = with(density) { GRID_STROKE.toPx() }
    val labelTextPx    = with(density) { 9.sp.toPx() }

    // Colour tokens read outside the lambda (Compose rule: no @Composable in Canvas).
    val onSurfaceVariant    = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val lineColor           = healthScoreColor(points.last().healthScore)

    Canvas(
        modifier = modifier.height(CHART_HEIGHT),
    ) {
        // Bounds of the actual chart body (excluding axis label gutters).
        val bodyLeft   = yAxisWidthPx + chartPaddingPx
        val bodyRight  = size.width - chartPaddingPx
        val bodyTop    = chartPaddingPx
        val bodyBottom = size.height - xAxisHeightPx - chartPaddingPx
        val bodyWidth  = (bodyRight - bodyLeft).coerceAtLeast(1f)
        val bodyHeight = (bodyBottom - bodyTop).coerceAtLeast(1f)

        // ----------------------------------------------------------------
        // Grid lines at Y = 25, 50, 75
        // ----------------------------------------------------------------
        val gridValues = listOf(25, 50, 75)
        gridValues.forEach { gridScore ->
            val gridY = scoreToY(gridScore.toFloat(), bodyTop, bodyHeight)
            drawLine(
                color       = surfaceVariantColor,
                start       = Offset(bodyLeft, gridY),
                end         = Offset(bodyRight, gridY),
                strokeWidth = gridStrokePx,
                pathEffect  = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }

        // ----------------------------------------------------------------
        // Y-axis labels: 0, 25, 50, 75, 100
        // ----------------------------------------------------------------
        val yLabels = listOf(0, 25, 50, 75, 100)
        yLabels.forEach { labelValue ->
            val labelY = scoreToY(labelValue.toFloat(), bodyTop, bodyHeight)
            drawYLabel(
                label     = labelValue.toString(),
                x         = 0f,
                y         = labelY,
                maxWidth  = yAxisWidthPx,
                textSizePx = labelTextPx,
                color     = onSurfaceVariant,
            )
        }

        // ----------------------------------------------------------------
        // Compute pixel coordinates for each data point
        // ----------------------------------------------------------------
        val coords: List<Offset> = points.mapIndexed { index, session ->
            val x = bodyLeft + (index.toFloat() / (points.size - 1).coerceAtLeast(1)) * bodyWidth
            val y = scoreToY(session.healthScore.toFloat(), bodyTop, bodyHeight)
            Offset(x, y)
        }

        // ----------------------------------------------------------------
        // Draw the connecting line as a Path
        // ----------------------------------------------------------------
        val linePath = Path().apply {
            moveTo(coords.first().x, coords.first().y)
            coords.drop(1).forEach { pt -> lineTo(pt.x, pt.y) }
        }
        drawPath(
            path  = linePath,
            color = lineColor.copy(alpha = 0.85f),
            style = Stroke(
                width = lineStrokePx,
                cap   = StrokeCap.Round,
                join  = StrokeJoin.Round,
            ),
        )

        // ----------------------------------------------------------------
        // Draw dots at each data point, coloured by that session's score
        // ----------------------------------------------------------------
        points.forEachIndexed { index, session ->
            val pt    = coords[index]
            val color = healthScoreColor(session.healthScore)
            // Outer ring (white/dark contrast border)
            drawCircle(
                color  = Color.White.copy(alpha = 0.9f),
                radius = dotRadiusPx + 1.5f,
                center = pt,
            )
            // Inner coloured fill
            drawCircle(
                color  = color,
                radius = dotRadiusPx,
                center = pt,
            )
        }

        // ----------------------------------------------------------------
        // X-axis date labels: first, middle, last
        // ----------------------------------------------------------------
        val xLabelIndices: List<Int> = when {
            points.size <= 2 -> points.indices.toList()
            else -> listOf(0, points.size / 2, points.size - 1)
        }
        xLabelIndices.forEach { idx ->
            val session = points[idx]
            val x       = coords[idx].x
            val label   = dateFormatter.format(session.timestamp)
            drawXLabel(
                label      = label,
                centerX    = x,
                y          = bodyBottom + chartPaddingPx,
                textSizePx = labelTextPx,
                color      = onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Coordinate helpers
// ---------------------------------------------------------------------------

/**
 * Maps a health [score] to a canvas Y pixel within the body drawing area.
 * Score 100 maps to [bodyTop], score 0 maps to [bodyTop] + [bodyHeight].
 */
private fun scoreToY(score: Float, bodyTop: Float, bodyHeight: Float): Float {
    val clamped = score.coerceIn(Y_MIN, Y_MAX)
    val fraction = 1f - (clamped - Y_MIN) / (Y_MAX - Y_MIN)
    return bodyTop + fraction * bodyHeight
}

// ---------------------------------------------------------------------------
// Native canvas text helpers
// ---------------------------------------------------------------------------

/** Draws a right-aligned Y-axis label centred on [y]. */
private fun DrawScope.drawYLabel(
    label: String,
    x: Float,
    y: Float,
    maxWidth: Float,
    textSizePx: Float,
    color: Color,
) {
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            this.color       = color.copy(alpha = 0.75f).toArgb()
            this.textSize    = textSizePx
            this.isAntiAlias = true
            this.textAlign   = android.graphics.Paint.Align.RIGHT
        }
        val metrics = paint.fontMetrics
        val textY   = y - (metrics.ascent + metrics.descent) / 2f
        drawText(label, x + maxWidth - 4f, textY, paint)
    }
}

/** Draws a centre-aligned X-axis date label below the chart body. */
private fun DrawScope.drawXLabel(
    label: String,
    centerX: Float,
    y: Float,
    textSizePx: Float,
    color: Color,
) {
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            this.color       = color.copy(alpha = 0.75f).toArgb()
            this.textSize    = textSizePx
            this.isAntiAlias = true
            this.textAlign   = android.graphics.Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        val textY   = y - metrics.ascent
        drawText(label, centerX, textY, paint)
    }
}

// ---------------------------------------------------------------------------
// Edge case placeholders
// ---------------------------------------------------------------------------

@Composable
private fun EmptyTrendPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No analysis data available.\nComplete a scan to see the trend.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shown when only a single session exists — a line chart with one point
 * carries no meaningful trend, so a score badge is shown instead.
 */
@Composable
private fun SinglePointPlaceholder(
    session: DetectionSession,
    formatter: SimpleDateFormat,
) {
    val scoreColor = healthScoreColor(session.healthScore)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = session.healthScore.toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = scoreColor,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatter.format(session.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Record more analyses to see the trend",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
