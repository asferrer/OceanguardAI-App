package com.oceanguard.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.theme.HealthCritical
import com.oceanguard.ai.ui.theme.HealthExcellent
import com.oceanguard.ai.ui.theme.HealthFair
import com.oceanguard.ai.ui.theme.materialColor

// ---------------------------------------------------------------------------
// Risk indicator helpers
// ---------------------------------------------------------------------------

private data class RiskIndicator(
    val icon       : ImageVector,
    val tint       : Color,
    val description: String
)

/**
 * Convert a risk score (1-5) returned by [Debris.getRiskScore] into a
 * visual indicator. Scores 4-5 are high-risk, 3 is medium, 1-2 is low.
 */
private fun riskIndicator(riskScore: Int): RiskIndicator = when {
    riskScore >= 4 -> RiskIndicator(
        icon        = Icons.Filled.Warning,
        tint        = HealthCritical,
        description = "High risk"
    )
    riskScore == 3 -> RiskIndicator(
        icon        = Icons.Outlined.Info,
        tint        = HealthFair,
        description = "Medium risk"
    )
    else           -> RiskIndicator(
        icon        = Icons.Outlined.CheckCircle,
        tint        = HealthExcellent,
        description = "Low risk"
    )
}

// ---------------------------------------------------------------------------
// Display name helpers
// ---------------------------------------------------------------------------

private fun DebrisType.displayName(): String = name
    .replace('_', ' ')
    .split(' ')
    .joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercaseChar() }
    }

private fun DebrisMaterial.displayName(): String = name
    .replace('_', ' ')
    .split(' ')
    .joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercaseChar() }
    }

// ---------------------------------------------------------------------------
// DebrisCard — Debris data class
// ---------------------------------------------------------------------------

/**
 * Material 3 card displaying a single detected debris item.
 *
 * Layout:
 * - Left  : filled colour circle indicating [Debris.material].
 * - Center: debris type name (bold), material subtitle, confidence percentage.
 * - Right : risk icon whose colour maps to the risk score returned by
 *           [Debris.getRiskScore()].
 *
 * The card is compact by design so it fits comfortably in a LazyColumn list
 * on both phone and tablet breakpoints.
 *
 * Accessibility: a merged content description is applied so TalkBack reads
 * the essential information in a single announcement.
 *
 * @param debris   The detected debris object to display.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun DebrisCard(
    debris  : Debris,
    modifier: Modifier = Modifier
) {
    val color     = materialColor(debris.material)
    val risk      = riskIndicator(debris.getRiskScore())
    val confidePct = (debris.confidence * 100).toInt()

    val a11yDesc = "${debris.type.displayName()}, " +
            "${debris.material.displayName()} material, " +
            "$confidePct% confidence, ${risk.description}"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yDesc },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // ----------------------------------------------------------------
            // Left: material colour circle
            // ----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                // Initial letter of the material for supplementary cue
                Text(
                    text       = debris.material.name.first().uppercaseChar().toString(),
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // ----------------------------------------------------------------
            // Center: textual information
            // ----------------------------------------------------------------
            Column(
                modifier    = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text       = debris.type.displayName(),
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text  = debris.material.displayName(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text  = "$confidePct% confidence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // ----------------------------------------------------------------
            // Right: risk indicator icon
            // ----------------------------------------------------------------
            Icon(
                imageVector        = risk.icon,
                contentDescription = risk.description,
                tint               = risk.tint,
                modifier           = Modifier.size(24.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// DetectionCard — DetectionResult (RT-DETR output)
// ---------------------------------------------------------------------------

/**
 * Map an RT-DETR [className] string to a [DebrisMaterial] for colour
 * assignment. Returns [DebrisMaterial.OTHER] when no match is found.
 */
private fun classNameToMaterial(className: String): DebrisMaterial = when (className.lowercase()) {
    "bottle", "plastic_debris", "mask", "glove" -> DebrisMaterial.PLASTIC
    "can", "metal_debris"                        -> DebrisMaterial.METAL
    "fishing_net"                                -> DebrisMaterial.FISHING_NET
    "fabric_debris"                              -> DebrisMaterial.FABRIC
    "tire"                                       -> DebrisMaterial.RUBBER
    "glass_debris"                               -> DebrisMaterial.GLASS
    else                                         -> DebrisMaterial.OTHER
}

/**
 * Derive a risk score (1-5) from a confidence value and detected class.
 * Higher-risk material classes and higher confidence scores push the risk up.
 */
private fun detectionRiskScore(detection: DetectionResult): Int {
    val materialRisk = when (classNameToMaterial(detection.className)) {
        DebrisMaterial.PLASTIC     -> 5
        DebrisMaterial.FISHING_NET -> 5
        DebrisMaterial.FABRIC      -> 4
        DebrisMaterial.GLASS       -> 4
        DebrisMaterial.METAL       -> 3
        DebrisMaterial.RUBBER      -> 3
        DebrisMaterial.OTHER       -> 2
    }
    // Boost by 1 when confidence > 0.85 (high certainty makes risk more actionable)
    return if (detection.confidence > 0.85f) (materialRisk + 1).coerceAtMost(5) else materialRisk
}

/**
 * Formats a [DetectionResult.className] string into title case with spaces.
 * "fishing_net" -> "Fishing Net"
 */
private fun formatClassName(raw: String): String = raw
    .replace('_', ' ')
    .split(' ')
    .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }

/**
 * Material 3 card for a single RT-DETR [DetectionResult].
 *
 * Mirrors [DebrisCard] visually but derives material and risk from the
 * detection class name rather than explicit model fields.
 *
 * @param detection The raw detector output to display.
 * @param modifier  Standard Compose modifier.
 */
@Composable
fun DetectionCard(
    detection: DetectionResult,
    modifier : Modifier = Modifier
) {
    val material   = classNameToMaterial(detection.className)
    val color      = materialColor(material)
    val risk       = riskIndicator(detectionRiskScore(detection))
    val confidePct = (detection.confidence * 100).toInt()
    val typeName   = formatClassName(detection.className)

    val boxW = detection.width.toInt()
    val boxH = detection.height.toInt()

    val a11yDesc = "$typeName detected, $confidePct% confidence, " +
            "bounding box ${boxW}x${boxH}px, ${risk.description}"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yDesc },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Left: material colour circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = material.name.first().uppercaseChar().toString(),
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center: labels
            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text       = typeName,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text  = material.displayName(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text  = "$confidePct% confidence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: risk icon
            Icon(
                imageVector        = risk.icon,
                contentDescription = risk.description,
                tint               = risk.tint,
                modifier           = Modifier.size(24.dp)
            )
        }
    }
}
