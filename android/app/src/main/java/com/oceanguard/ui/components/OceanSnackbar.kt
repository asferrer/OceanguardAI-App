package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.theme.CoralRed
import com.oceanguard.ai.ui.theme.OceanBlue
import com.oceanguard.ai.ui.theme.OceanGreen

/**
 * Snackbar variant for consistent ocean-themed feedback.
 */
enum class OceanSnackbarType(
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector,
) {
    SUCCESS(
        containerColor = OceanGreen.copy(alpha = 0.9f),
        contentColor = Color.White,
        icon = Icons.Filled.CheckCircle,
    ),
    ERROR(
        containerColor = CoralRed.copy(alpha = 0.9f),
        contentColor = Color.White,
        icon = Icons.Filled.Error,
    ),
    INFO(
        containerColor = OceanBlue.copy(alpha = 0.9f),
        contentColor = Color.White,
        icon = Icons.Filled.Info,
    ),
}

/**
 * Ocean-themed Snackbar with leading icon and rounded shape.
 *
 * Wrap a [SnackbarData] in this composable inside `SnackbarHost`:
 * ```
 * SnackbarHost(hostState) { data ->
 *     OceanSnackbar(data = data, type = OceanSnackbarType.SUCCESS)
 * }
 * ```
 */
@Composable
fun OceanSnackbar(
    data: SnackbarData,
    type: OceanSnackbarType = OceanSnackbarType.INFO,
    modifier: Modifier = Modifier,
) {
    Snackbar(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = type.containerColor,
        contentColor = type.contentColor,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = type.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = type.contentColor,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = type.contentColor,
            )
        }
    }
}
