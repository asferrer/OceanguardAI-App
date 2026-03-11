package com.oceanguard.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.theme.CTAGlow
import com.oceanguard.ai.ui.theme.GradientCTAEnd
import com.oceanguard.ai.ui.theme.GradientCTAStart

/**
 * Primary CTA button with a cyan-to-emerald gradient and subtle glow,
 * matching the landing page "Download APK" button aesthetic.
 *
 * Use for primary actions: "Save to History", "Start Scanning", "Retry",
 * "Generate Report", "Confirm".
 *
 * Content is dark text/icons against the bright gradient background.
 */
@Composable
fun OceanGradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    cornerRadius: Dp = 14.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(GradientCTAStart, GradientCTAEnd),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val shape = RoundedCornerShape(cornerRadius)

    Surface(
        onClick = onClick,
        modifier = modifier
            .pressableScale()
            .height(height)
            .drawBehind {
                // Cyan glow shadow behind the button
                drawRoundRect(
                    color = if (enabled) CTAGlow else Color.Transparent,
                    topLeft = Offset(4.dp.toPx(), 5.dp.toPx()),
                    size = Size(size.width - 8.dp.toPx(), size.height - 2.dp.toPx()),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                )
            },
        shape = shape,
        color = Color.Transparent,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(
                    brush = if (enabled) gradientBrush
                    else Brush.linearGradient(
                        listOf(
                            GradientCTAStart.copy(alpha = 0.38f),
                            GradientCTAEnd.copy(alpha = 0.38f),
                        ),
                    ),
                    shape = shape,
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

/** Dark colour for text/icons on gradient buttons (matches landing --bg-primary). */
val OnGradientColor = Color(0xFF0A0E1A)
