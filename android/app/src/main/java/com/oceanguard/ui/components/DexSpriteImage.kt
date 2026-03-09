package com.oceanguard.ai.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.collection.DexSprites

/**
 * Renders the pixel art sprite for a [DebrisType].
 *
 * Uses [FilterQuality.None] via [BitmapPainter] to preserve crispy pixel
 * edges without anti-aliasing — authentic Game Boy style.
 *
 * Replace the placeholder PNGs in `res/drawable/dex_*.png` with your
 * own pixel art to see them here.
 */
@Composable
fun DexSpriteImage(
    debrisType: DebrisType,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val spriteRes = DexSprites.getSprite(debrisType)
    val context = LocalContext.current
    val painter = remember(spriteRes) {
        val bmp = BitmapFactory.decodeResource(context.resources, spriteRes)
        BitmapPainter(bmp.asImageBitmap(), filterQuality = FilterQuality.None)
    }
    Image(
        painter = painter,
        contentDescription = formatDebrisName(debrisType),
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

private fun formatDebrisName(type: DebrisType): String =
    type.name.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
