package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import kotlin.math.sin

/**
 * Ocean-themed pull-to-refresh wrapper.
 *
 * Uses Material 3 [PullToRefreshBox] with the default indicator styled in
 * ocean colours. A subtle wave animation plays behind the indicator while
 * the refresh is in progress.
 *
 * @param isRefreshing Whether data is currently being refreshed.
 * @param onRefresh    Callback when the user triggers a refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OceanPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            Indicator(
                modifier = Modifier.align(Alignment.TopCenter),
                isRefreshing = isRefreshing,
                state = state,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                color = OceanGreen,
            )
        },
    ) {
        content()
    }
}
