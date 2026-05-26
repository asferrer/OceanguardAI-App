package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.oceanguard.ai.R
import java.io.File

/**
 * Loads a species sprite from [spritePath] inside
 * `getExternalFilesDir/models/species/sprites/`.
 *
 * Uses Coil 3 [AsyncImage] for async disk I/O and crossfade.
 * Falls back to the [R.drawable.dex_species_unknown] placeholder drawable
 * when [spritePath] is null or the file does not exist on disk.
 *
 * NOTE (integration TODO): Add `res/drawable/dex_species_unknown.png`
 * (or equivalent vector drawable). The resource is referenced here but
 * does not exist yet — a compile-time error will surface until the
 * drawable is added by the design/integration step.
 *
 * @param spritePath  Relative path as stored in SpeciesCatalogEntry.spritePath.
 *                    May be null for uncatalogued species.
 * @param size        Rendered size (width == height, square aspect).
 * @param modifier    Additional layout modifiers applied to the image.
 */
@Composable
fun SpeciesSpriteImage(
    spritePath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val context = LocalContext.current
    val spriteFile = spritePath?.let { path ->
        File(context.getExternalFilesDir(null), "models/species/sprites/$path")
    }
    val model = if (spriteFile != null && spriteFile.exists()) {
        ImageRequest.Builder(context)
            .data(spriteFile)
            .crossfade(true)
            .build()
    } else {
        // File absent or spritePath null — fall back to bundled drawable.
        ImageRequest.Builder(context)
            .data(R.drawable.dex_species_unknown) // TODO(integration): add drawable
            .build()
    }

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        placeholder = painterResource(R.drawable.dex_species_unknown), // TODO(integration): add drawable
        error = painterResource(R.drawable.dex_species_unknown),       // TODO(integration): add drawable
        modifier = modifier.size(size),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1929)
@Composable
private fun SpeciesSpriteImagePreview() {
    SpeciesSpriteImage(spritePath = null, size = 64.dp)
}
