package com.oceanguard.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.ui.theme.ShimmerBase
import com.oceanguard.ai.utils.PhotonGeocoderClient
import com.oceanguard.ai.utils.toPlaceLabel
import com.valentinilk.shimmer.shimmer

/**
 * Displays a reverse-geocoded place name for the given [location].
 *
 * While the network call is in-flight a shimmer placeholder is shown.
 * If reverse geocoding fails, falls back to formatted coordinates.
 */
@Composable
fun PlaceNameText(
    location: Location?,
    geocoder: PhotonGeocoderClient,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = 1,
) {
    if (location == null) return

    val context = LocalContext.current
    var placeName by remember(location) { mutableStateOf<String?>(null) }
    var isLoading by remember(location) { mutableStateOf(true) }

    LaunchedEffect(location) {
        isLoading = true
        val result = geocoder.reverse(location.latitude, location.longitude)
        placeName = result?.toPlaceLabel()
            ?: context.getString(R.string.zone_name_unknown)
        isLoading = false
    }

    if (isLoading) {
        Box(
            modifier = modifier
                .width(120.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer()
                .background(ShimmerBase),
        )
    } else {
        Text(
            text = placeName ?: "",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
