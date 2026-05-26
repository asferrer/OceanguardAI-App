package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.theme.CoralRed
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 2-column grid of [SpeciesObservation] thumbnails for a single species.
 *
 * Each cell shows the observation image via Coil 3, a confidence badge,
 * an optional "out-of-range" warning, and the observation date.
 * Mirrors [MarineDexGallery] in structure and visual style.
 *
 * @param observations Observations for the currently displayed species.
 * @param onObservationClick Invoked with the observation id when a cell is tapped.
 */
@Composable
fun SpeciesDexGallery(
    observations: List<SpeciesObservation>,
    speciesDisplayName: String,
    onObservationClick: (observationId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (observations.isEmpty()) {
        SpeciesGalleryEmptyState(speciesDisplayName = speciesDisplayName, modifier = modifier.fillMaxSize())
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = observations, key = { it.id }) { obs ->
            SpeciesGalleryItem(
                observation = obs,
                onClick = { onObservationClick(obs.id) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Grid item
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesGalleryItem(
    observation: SpeciesObservation,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val imageUri = observation.thumbnailUri ?: observation.imageUri

    val confidencePct = (observation.confidence * 100).toInt()
    val borderColor = when {
        observation.outOfRange -> CoralRed.copy(alpha = 0.8f)
        observation.confidence >= 0.75f -> OceanGreen.copy(alpha = 0.8f)
        else -> Color.Transparent
    }

    GlassCard(
        animate = false,
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = stringResource(R.string.biodex_gallery_cd_observation),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            GalleryItemScrim(
                confidencePct = confidencePct,
                outOfRange = observation.outOfRange,
                date = dateFormat.format(observation.timestamp),
            )
        }
    }
}

@Composable
private fun GalleryItemScrim(
    confidencePct: Int,
    outOfRange: Boolean,
    date: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xCC000000)),
                    startY = 0.5f,
                )
            ),
    ) {
        // Out-of-range badge — top-right corner
        if (outOfRange) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 8.dp),
                color = CoralRed.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = stringResource(R.string.biodex_badge_out_of_range),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        // Bottom scrim: confidence + date
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val confColor = when {
                    confidencePct >= 75 -> OceanGreen
                    confidencePct >= 50 -> OceanBlueLight
                    else -> CoralRed
                }
                Text(
                    text = "$confidencePct%",
                    color = confColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = date,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesGalleryEmptyState(
    speciesDisplayName: String,
    modifier: Modifier = Modifier,
) {
    LottieEmptyState(
        title = stringResource(R.string.biodex_gallery_empty_title),
        message = stringResource(R.string.biodex_gallery_empty_message, speciesDisplayName),
        modifier = modifier.padding(horizontal = 32.dp),
    )
}
