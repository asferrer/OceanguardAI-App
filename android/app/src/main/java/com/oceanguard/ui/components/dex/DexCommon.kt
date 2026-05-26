package com.oceanguard.ai.ui.components.dex

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.theme.GradientDeepEnd
import com.oceanguard.ai.ui.theme.GradientDeepMid
import com.oceanguard.ai.ui.theme.GradientDeepStart
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen

// ---------------------------------------------------------------------------
// Shared data model — lightweight transfer object for dex grid cells.
// Parametrizes both MarineDex and BioDex card/header composables.
// ---------------------------------------------------------------------------

/**
 * Lightweight UI model used by shared dex composables.
 *
 * @param key           Stable unique identifier (e.g. speciesKey or debris type name).
 * @param displayName   Localised common name resolved by the caller.
 * @param spritePath    Relative path under getExternalFilesDir for species sprites,
 *                      or null to fall back to the placeholder drawable.
 * @param discovered    Whether the user has seen this entry at least once.
 * @param count         Times observed/detected.
 * @param isFavorite    Whether the user has marked this entry as a favourite.
 * @param subtitle      Optional second line shown in the card (e.g. scientific name).
 */
data class DexItem(
    val key: String,
    val displayName: String,
    val spritePath: String?,
    val discovered: Boolean,
    val count: Int,
    val isFavorite: Boolean,
    val subtitle: String? = null,
)

// ---------------------------------------------------------------------------
// DexCollectionHeader — gradient hero block with circular progress + stats
// ---------------------------------------------------------------------------

/**
 * Full-width hero header showing collection progress.
 * Clones the MarineDex header pattern, parametrised by total catalog size.
 */
@Composable
fun DexCollectionHeader(
    discoveredCount: Int,
    totalCount: Int,
    favoriteCount: Int,
    observationCount: Int,
    discoveredLabel: String,
    favoritesLabel: String,
    observationsLabel: String,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val heroTextColor = if (isDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = if (isDark) {
                            listOf(GradientDeepStart, GradientDeepMid, GradientDeepEnd)
                        } else {
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            )
                        },
                    )
                )
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DexCircularProgress(discovered = discoveredCount, total = totalCount)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "$discoveredCount / $totalCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = heroTextColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        DexStatsRow(
            discoveredCount = discoveredCount,
            favoriteCount = favoriteCount,
            observationCount = observationCount,
            discoveredLabel = discoveredLabel,
            favoritesLabel = favoritesLabel,
            observationsLabel = observationsLabel,
        )
    }
}

@Composable
private fun DexStatsRow(
    discoveredCount: Int,
    favoriteCount: Int,
    observationCount: Int,
    discoveredLabel: String,
    favoritesLabel: String,
    observationsLabel: String,
) {
    GlassCard(animate = false) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DexStat(label = discoveredLabel, value = discoveredCount.toString(), valueColor = OceanGreen)
            VerticalDivider(
                modifier = Modifier.height(40.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            DexStat(label = favoritesLabel, value = favoriteCount.toString(), valueColor = OceanBlueLight)
            VerticalDivider(
                modifier = Modifier.height(40.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            DexStat(label = observationsLabel, value = observationCount.toString(), valueColor = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------------------------------------------------------------------------
// DexCircularProgress — animated arc showing fraction discovered
// ---------------------------------------------------------------------------

@Composable
fun DexCircularProgress(discovered: Int, total: Int) {
    val isDark = isSystemInDarkTheme()
    val progress = if (total > 0) (discovered.toFloat() / total).coerceIn(0f, 1f) else 0f
    val sweepAngle by animateFloatAsState(
        targetValue = 360f * progress,
        animationSpec = tween(durationMillis = 800),
        label = "dexProgress",
    )
    val progressColor = when {
        progress >= 1f -> OceanGreen
        progress >= 0.6f -> OceanBlueLight
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = if (isDark) Color.White.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val textColor = if (isDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val subtextColor = textColor.copy(alpha = 0.7f)

    Box(
        modifier = Modifier
            .size(96.dp)
            .drawBehind {
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx()),
                )
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$discovered",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = textColor,
            )
            Text(text = "/ $total", style = MaterialTheme.typography.labelSmall, color = subtextColor)
        }
    }
}

// ---------------------------------------------------------------------------
// DexStat — single label+value column used in the stats row
// ---------------------------------------------------------------------------

@Composable
fun DexStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// DexCard — grid cell for a single DexItem (discovered or locked)
// ---------------------------------------------------------------------------

/**
 * A single Pokédex-style card cell.
 *
 * Discovered entries show the sprite loaded via [SpeciesSpriteImage], display
 * name, optional subtitle and an observation-count pill.
 * Locked entries blur the sprite and overlay a "?" glyph.
 *
 * @param item            The lightweight DexItem to render.
 * @param animationIndex  Stagger index for the spring entry animation.
 * @param onNavigateToDetail Invoked with [DexItem.key] when the card is tapped.
 */
@Composable
fun DexCard(
    item: DexItem,
    animationIndex: Int,
    onNavigateToDetail: (key: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(animationIndex * 40L)
        isVisible = true
    }
    val cardScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.75f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cardScale_${item.key}",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 250, delayMillis = animationIndex * 40),
        label = "cardAlpha_${item.key}",
    )

    val borderBrush = if (item.discovered) {
        Brush.verticalGradient(listOf(OceanGreen.copy(alpha = 0.8f), OceanBlueLight.copy(alpha = 0.4f)))
    } else {
        val locked = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        Brush.linearGradient(listOf(locked, locked))
    }
    val cardCd = if (item.discovered) "${item.displayName}, observed ${item.count} times"
        else "${item.displayName}, not yet discovered"

    Card(
        modifier = modifier
            .aspectRatio(0.75f)
            .graphicsLayer { scaleX = cardScale; scaleY = cardScale; alpha = cardAlpha }
            .then(
                if (item.discovered) {
                    Modifier
                        .pressableScale()
                        .clickable { onNavigateToDetail(item.key) }
                } else Modifier
            )
            .semantics { contentDescription = cardCd },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.discovered) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (item.discovered) 4.dp else 1.dp),
        border = BorderStroke(width = if (item.discovered) 1.5.dp else 0.5.dp, brush = borderBrush),
    ) {
        if (item.discovered) {
            DiscoveredCardContent(item = item)
        } else {
            LockedCardContent(item = item)
        }
    }
}

// ---------------------------------------------------------------------------
// Private card internals
// ---------------------------------------------------------------------------

@Composable
private fun DiscoveredCardContent(item: DexItem) {
    val isDark = isSystemInDarkTheme()
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(OceanGreen.copy(alpha = if (isDark) 0.08f else 0.06f), Color.Transparent)
                    )
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            SpeciesSpriteImage(spritePath = item.spritePath, size = 48.dp)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = OceanGreen.copy(alpha = 0.18f)) {
                    Text(
                        text = "×${item.count}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = OceanGreen,
                    )
                }
            }
        }
    }
}

@Composable
private fun LockedCardContent(item: DexItem) {
    val isDark = isSystemInDarkTheme()
    val overlayColors = if (isDark) {
        listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.55f))
    } else {
        listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        )
    }
    val questionMarkColor = if (isDark) Color.White.copy(alpha = 0.85f)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = overlayColors)),
        contentAlignment = Alignment.Center,
    ) {
        SpeciesSpriteImage(
            spritePath = item.spritePath,
            size = 56.dp,
            modifier = Modifier.blur(radius = 12.dp).graphicsLayer { alpha = 0.5f },
        )
        Text(
            text = "?",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = questionMarkColor,
        )
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF0A1929)
@Composable
private fun DexCardDiscoveredPreview() {
    DexCard(
        item = DexItem(
            key = "acanthaster_planci",
            displayName = "Crown-of-thorns",
            spritePath = null,
            discovered = true,
            count = 5,
            isFavorite = false,
            subtitle = "Acanthaster planci",
        ),
        animationIndex = 0,
        onNavigateToDetail = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1929)
@Composable
private fun DexCardLockedPreview() {
    DexCard(
        item = DexItem(
            key = "unknown_species",
            displayName = "???",
            spritePath = null,
            discovered = false,
            count = 0,
            isFavorite = false,
        ),
        animationIndex = 1,
        onNavigateToDetail = {},
    )
}
