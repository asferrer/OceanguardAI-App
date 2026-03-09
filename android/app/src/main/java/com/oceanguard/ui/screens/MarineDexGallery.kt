package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.LottieEmptyState
import com.oceanguard.ai.ui.theme.materialColor
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 2-column grid of session thumbnails containing the given [debrisType].
 *
 * Each item shows the session image via Coil 3, a semi-transparent bottom
 * scrim with debris count and date, and a colored border for high-confidence
 * detections based on the dominant debris material.
 */
@Composable
fun MarineDexGallery(
    debrisType: DebrisType,
    sessions: List<DetectionSession>,
    onSessionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) {
        GalleryEmptyState(debrisType = debrisType, modifier = modifier.fillMaxSize())
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = sessions, key = { it.id }) { session ->
            GalleryItem(
                session = session,
                debrisType = debrisType,
                onClick = { onSessionClick(session.id) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Grid item
// ---------------------------------------------------------------------------

@Composable
private fun GalleryItem(
    session: DetectionSession,
    debrisType: DebrisType,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val imageUri = session.thumbnailUri ?: session.imageUri

    val matchingDebris = remember(session, debrisType) {
        session.debrisList.filter { it.type == debrisType }
    }
    val highConfidence = remember(matchingDebris) {
        matchingDebris.any { it.confidence >= 0.75f }
    }
    val dominantMaterial = remember(matchingDebris) {
        matchingDebris.groupBy { it.material }
            .maxByOrNull { it.value.size }
            ?.key
    }

    val borderColor = if (highConfidence && dominantMaterial != null) {
        materialColor(dominantMaterial)
    } else {
        Color.Transparent
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
                contentDescription = "Session image for ${debrisType.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            GalleryItemScrim(
                debrisCount = matchingDebris.size,
                date = dateFormat.format(session.timestamp),
            )
        }
    }
}

@Composable
private fun GalleryItemScrim(
    debrisCount: Int,
    date: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xCC000000)),
                    startY = 0.5f,
                ),
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.marinedex_gallery_found_count, debrisCount),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
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
private fun GalleryEmptyState(
    debrisType: DebrisType,
    modifier: Modifier = Modifier,
) {
    val displayName = debrisType.name
        .replace("_", " ")
        .lowercase()
        .replaceFirstChar { it.uppercase() }

    LottieEmptyState(
        title = "No captures yet",
        message = "Capture an image containing $displayName to see it here.",
        modifier = modifier.padding(horizontal = 32.dp),
    )
}
