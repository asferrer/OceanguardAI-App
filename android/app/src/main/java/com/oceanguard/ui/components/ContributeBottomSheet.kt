package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R

/**
 * Bottom sheet shown after analysis when the user hasn't given contribution consent yet.
 *
 * Actions:
 *  - upload on WiFi (primary)
 *  - upload now (secondary)
 *  - not now (tertiary, dismiss this prompt only)
 *  - don't ask again (last, permanent opt-out — reversible in Settings)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributeBottomSheet(
    imageCount: Int,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    onUploadOnWifi: () -> Unit,
    onUploadNow: () -> Unit,
    onNotNow: () -> Unit,
    onDontAskAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.contribute_prompt_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.contribute_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy bullets
            val bullets = listOf(
                Icons.Filled.Lock to stringResource(R.string.contribute_privacy_no_location),
                Icons.Filled.NoPhotography to stringResource(R.string.contribute_privacy_no_personal),
                Icons.Filled.Science to stringResource(R.string.contribute_privacy_research_only),
            )
            bullets.forEach { (icon, text) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 0.dp),
                    )
                    Text(
                        text = "  $text",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Primary action: upload on WiFi
            FilledTonalButton(
                onClick = onUploadOnWifi,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.contribute_prompt_wifi))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary action: upload now (any network)
            OutlinedButton(
                onClick = onUploadNow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.contribute_prompt_now))
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Tertiary: not now (dismiss this prompt only, may show again later)
            TextButton(
                onClick = onNotNow,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(text = stringResource(R.string.contribute_prompt_skip))
            }

            // Final: don't ask again — permanent opt-out, reversible in Settings.
            TextButton(
                onClick = onDontAskAgain,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                ),
            ) {
                Text(text = stringResource(R.string.contribute_prompt_dont_ask_again))
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
