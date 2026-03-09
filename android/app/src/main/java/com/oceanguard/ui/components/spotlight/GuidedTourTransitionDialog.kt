package com.oceanguard.ai.ui.components.spotlight

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.theme.OceanGreen

/**
 * Ordered sequence of the 9 screens visited during the full guided tour.
 *
 * Each [TourStop] maps a logical [screenId] (matching [TourDefinitions.getScreenId])
 * to a display name resource. Navigation between stops is handled by each screen
 * because some routes require dynamic parameters (e.g. `marinedex/BOTTLE`).
 */
object GuidedTourSequence {

    data class TourStop(val screenId: String, val nameRes: Int)

    val STOPS = listOf(
        TourStop("home", R.string.guided_tour_tab_home),
        TourStop("marinedex", R.string.guided_tour_tab_marinedex),
        TourStop("marinedex_detail", R.string.guided_tour_tab_marinedex_detail),
        TourStop("achievements", R.string.guided_tour_tab_achievements),
        TourStop("map", R.string.guided_tour_tab_map),
        TourStop("reports", R.string.guided_tour_tab_reports),
        TourStop("history", R.string.guided_tour_tab_history),
        TourStop("session_detail", R.string.guided_tour_tab_session_detail),
        TourStop("settings", R.string.guided_tour_tab_settings),
    )

    /** Returns the string-resource ID for the next stop's name, or null if last. */
    fun nextNameRes(screenId: String): Int? {
        val idx = STOPS.indexOfFirst { it.screenId == screenId }
        return STOPS.getOrNull(idx + 1)?.nameRes
    }

    /** 1-based step number of [screenId] within the sequence. */
    fun stepNumber(screenId: String): Int {
        val idx = STOPS.indexOfFirst { it.screenId == screenId }
        return if (idx >= 0) idx + 1 else 1
    }

    /** True when [screenId] is the last stop in the sequence. */
    fun isLast(screenId: String): Boolean =
        STOPS.lastOrNull()?.screenId == screenId

    /** Total number of stops in the guided tour. */
    val totalSteps: Int get() = STOPS.size
}

/**
 * Non-dismissible dialog shown between guided-tour screens.
 *
 * - For intermediate tabs: shows "Next up: [Tab Name]" with Continue and Skip Tutorial buttons.
 * - For the last tab (Settings): shows "Tour Complete!" with a single finish button.
 *
 * @param screenId       The [TourDefinitions.getScreenId] of the screen that just finished.
 * @param onContinue     Navigate to the next stop.
 * @param onSkipTutorial Mark all tours complete, clean up demo data, go home.
 */
@Composable
fun GuidedTourTransitionDialog(
    screenId: String,
    onContinue: () -> Unit,
    onSkipTutorial: () -> Unit,
) {
    val isLast = GuidedTourSequence.isLast(screenId)
    val stepNumber = GuidedTourSequence.stepNumber(screenId)
    val totalSteps = GuidedTourSequence.totalSteps

    Dialog(
        onDismissRequest = { /* non-dismissible */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (isLast) {
                    // --- Final screen: Tour Complete ---
                    Text(
                        text = stringResource(R.string.guided_tour_complete_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OceanGreen,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.guided_tour_complete_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.guided_tour_progress, stepNumber, totalSteps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(containerColor = OceanGreen),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.guided_tour_btn_finish))
                    }
                } else {
                    // --- Intermediate screen: Next tab ---
                    val nextNameRes = GuidedTourSequence.nextNameRes(screenId)
                    Text(
                        text = stringResource(R.string.guided_tour_screen_done_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OceanGreen,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (nextNameRes != null) {
                        Text(
                            text = stringResource(R.string.guided_tour_next_desc, stringResource(nextNameRes)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.guided_tour_progress, stepNumber, totalSteps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = onSkipTutorial,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.guided_tour_btn_skip_tutorial),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onContinue,
                            colors = ButtonDefaults.buttonColors(containerColor = OceanGreen),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = stringResource(R.string.guided_tour_btn_continue))
                        }
                    }
                }
            }
        }
    }
}
