package com.oceanguard.ai.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.ui.components.GlassCard
import com.oceanguard.ai.ui.components.pressableScale
import com.oceanguard.ai.ui.theme.CoralRed
import com.oceanguard.ai.ui.theme.OceanBlueLight
import com.oceanguard.ai.ui.theme.OceanGreen
import java.text.SimpleDateFormat
import java.util.Locale

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/**
 * Full-screen detail for a single [SpeciesObservation].
 *
 * Shows the observation image, common + scientific name, confidence badge,
 * status badges (idSource, outOfRange, uncatalogued), VLM description,
 * date and location (lat/lon) when present.
 *
 * Navigation wiring is the responsibility of MainActivity.
 * TODO(integration): Register route "species_observation/{observationId}" in
 *   MainActivity. Retrieve the [SpeciesObservation] by id from
 *   [SpeciesCollectionRepository.getObservationById] and pass it here.
 *   Signature expected:
 *     SpeciesObservationDetailScreen(
 *         observation = observation,
 *         catalog     = catalog,
 *         onNavigateBack = { navController.popBackStack() },
 *     )
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeciesObservationDetailScreen(
    observation: SpeciesObservation,
    catalog: SpeciesCatalog,
    onNavigateBack: () -> Unit,
) {
    val lang = remember { Locale.getDefault().language }
    val catalogEntry = remember(observation.commonNameKey) {
        observation.commonNameKey?.let { catalog.byKey(it) }
    }
    val displayName = remember(catalogEntry, lang) {
        catalogEntry?.commonNames?.get(lang)
            ?: catalogEntry?.commonNames?.get("en")
            ?: observation.scientificName
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.biodex_obs_detail_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.pressableScale(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.biodex_obs_detail_cd_go_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ObservationImage(imageUri = observation.imageUri)
            ObservationNameSection(
                displayName = displayName,
                scientificName = observation.scientificName,
            )
            ObservationBadgesRow(observation = observation)
            ObservationMetaCard(observation = observation)
            observation.vlmDescription?.let { desc ->
                ObservationDescriptionCard(description = desc)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Image
// ---------------------------------------------------------------------------

@Composable
private fun ObservationImage(imageUri: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        AsyncImage(
            model = imageUri,
            contentDescription = stringResource(R.string.biodex_obs_detail_cd_image),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------------------
// Name section
// ---------------------------------------------------------------------------

@Composable
private fun ObservationNameSection(displayName: String, scientificName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = scientificName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Badges row
// ---------------------------------------------------------------------------

@Composable
private fun ObservationBadgesRow(observation: SpeciesObservation) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ObsBadge(
            text = observation.idSource,
            containerColor = OceanBlueLight.copy(alpha = 0.18f),
            contentColor = OceanBlueLight,
        )
        if (observation.outOfRange) {
            ObsBadge(
                text = stringResource(R.string.biodex_obs_detail_badge_out_of_range),
                containerColor = CoralRed.copy(alpha = 0.18f),
                contentColor = CoralRed,
            )
        }
        if (observation.uncatalogued) {
            ObsBadge(
                text = stringResource(R.string.biodex_obs_detail_badge_uncatalogued),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ObsBadge(text: String, containerColor: Color, contentColor: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = containerColor) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Meta card (confidence, date, location)
// ---------------------------------------------------------------------------

@Composable
private fun ObservationMetaCard(observation: SpeciesObservation) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()) }
    val confidencePct = (observation.confidence * 100).toInt()
    val confColor = when {
        confidencePct >= 75 -> OceanGreen
        confidencePct >= 50 -> OceanBlueLight
        else -> CoralRed
    }

    GlassCard(animate = false) {
        MetaRow(
            label = stringResource(R.string.biodex_obs_detail_label_confidence),
            value = "$confidencePct%",
            valueColor = confColor,
        )
        Spacer(modifier = Modifier.height(10.dp))
        MetaRow(
            label = stringResource(R.string.biodex_obs_detail_label_date),
            value = dateFormat.format(observation.timestamp),
        )
        observation.location?.let { loc ->
            Spacer(modifier = Modifier.height(10.dp))
            MetaRow(
                label = stringResource(R.string.biodex_obs_detail_label_location),
                value = "%.4f, %.4f".format(loc.latitude, loc.longitude),
            )
        }
    }
}

@Composable
private fun MetaRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}

// ---------------------------------------------------------------------------
// VLM description card
// ---------------------------------------------------------------------------

@Composable
private fun ObservationDescriptionCard(description: String) {
    GlassCard(animate = false) {
        Text(
            text = stringResource(R.string.biodex_obs_detail_label_description),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
