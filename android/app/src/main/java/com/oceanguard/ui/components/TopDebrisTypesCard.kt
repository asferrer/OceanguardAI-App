package com.oceanguard.ai.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.collection.DexSprites
import com.oceanguard.ai.inference.ReportGenerator

/**
 * Home-screen card showing the **top 3 debris types** detected in the last
 * 7 days. Replaces the previous health-score trend chart. Same vertical
 * footprint (~220 dp including header + card padding) so the surrounding
 * staggered-entry layout doesn't reflow.
 *
 * Each card displays:
 *   • Sprite from [DexSprites] (reusing the MarineDex pixel-art catalogue)
 *   • Translated debris type name in the user's locale
 *   • Total count of items of that type in the window
 *
 * Sessions older than 7 days are excluded so the card always reflects the
 * recent state — a stale top-3 from a year ago would not be actionable. The
 * window threshold is recomputed on every composition so the card is fresh
 * without a tick timer.
 *
 * Debris types are bucketed using [DebrisType.canonical] so extended Gemma 4
 * sub-types (PLASTIC_BAG, BOTTLE_CAP, etc.) collapse to their canonical parent
 * (PLASTIC_DEBRIS, BOTTLE) — otherwise the top 3 would fragment across
 * synonyms.
 *
 * Empty state: when no sessions exist in the last 7 days, renders a placeholder
 * matching the styling of the previous chart's empty state.
 *
 * @param sessions Full list of [DetectionSession] from the ViewModel. The card
 *                 filters internally.
 * @param language ISO code ("en", "es", "fr", "de", "it", "pt") used to
 *                 translate debris-type names via
 *                 [ReportGenerator.translateType].
 */
@Composable
fun TopDebrisTypesCard(
    sessions: List<DetectionSession>,
    language: String,
    /**
     * Tap callback fired with the canonical [DebrisType] of the item that
     * was clicked. HomeScreen routes this to the MarineDex detail screen
     * (Gallery tab) so users can browse every image where that debris type
     * was detected. Default no-op keeps the card usable as a read-only widget.
     */
    onItemClick: (DebrisType) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 7-day window in millis. Recomputed each composition — the card is on the
    // home screen which already re-renders on session changes, so we don't
    // need a side-effect to refresh "now".
    val cutoffMs = remember { System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
    val topTypes: List<Pair<DebrisType, Int>> = remember(sessions, cutoffMs) {
        sessions
            .asSequence()
            .filter { it.timestamp.time >= cutoffMs }
            .flatMap { it.debrisList.asSequence() }
            .groupingBy { it.type.canonical() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key to it.value }
            .toList()
    }

    val a11y = remember(topTypes, language) {
        if (topTypes.isEmpty()) {
            "Top debris types this week: no detections recorded"
        } else {
            "Top debris types this week: " + topTypes.joinToString(", ") { (type, count) ->
                "${ReportGenerator.translateType(type.name, language)} $count"
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11y },
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
            // Header — matches HealthTrendChart styling so the swap is
            // visually a content change, not a layout disruption.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.home_top_debris_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_top_debris_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (topTypes.isNotEmpty()) {
                    val total = topTypes.sumOf { it.second }
                    Text(
                        text = stringResource(R.string.home_top_debris_total, total),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (topTypes.isEmpty()) {
                EmptyTopDebrisPlaceholder()
            } else {
                TopDebrisRow(topTypes = topTypes, language = language, onItemClick = onItemClick)
            }
        }
    }
}

@Composable
private fun TopDebrisRow(
    topTypes: List<Pair<DebrisType, Int>>,
    language: String,
    onItemClick: (DebrisType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        topTypes.forEachIndexed { idx, (type, count) ->
            TopDebrisItem(
                rank = idx + 1,
                type = type,
                count = count,
                language = language,
                onClick = { onItemClick(type) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            )
        }
        // Pad with empty slots if fewer than 3 unique types so the rank-1 card
        // doesn't stretch to full width when only 1-2 types were detected.
        repeat(3 - topTypes.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TopDebrisItem(
    rank: Int,
    type: DebrisType,
    count: Int,
    language: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = remember(type, language) {
        ReportGenerator.translateType(type.name, language)
    }
    val sprite: Painter = painterResource(DexSprites.getSprite(type))
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Rank chip (#1 with primary tint, #2 secondary, #3 tertiary)
            Surface(
                shape = CircleShape,
                color = when (rank) {
                    1 -> MaterialTheme.colorScheme.primary
                    2 -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.size(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "#$rank",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when (rank) {
                            1 -> MaterialTheme.colorScheme.onPrimary
                            2 -> MaterialTheme.colorScheme.onSecondary
                            else -> MaterialTheme.colorScheme.onTertiary
                        },
                    )
                }
            }

            // Pixel sprite from the MarineDex catalogue. Painter overload
            // doesn't take filterQuality directly; the BitmapPainter under
            // the hood preserves the source's native scaling — good enough
            // for our 48-dp render size.
            Image(
                painter = sprite,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )

            // Count (big) + label (small)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyTopDebrisPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.home_top_debris_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
