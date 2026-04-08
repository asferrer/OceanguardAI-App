package com.oceanguard.ai.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.oceanguard.ai.data.converters.DebrisListConverter
import com.oceanguard.ai.data.converters.LocationConverter
import java.util.Date

/**
 * Data models for OceanGuard AI
 */

/**
 * Debris object detected in an image
 */
data class Debris(
    val bbox: BoundingBox,
    val material: DebrisMaterial,
    val type: DebrisType,
    val confidence: Float
) {
    /**
     * Get risk score based on material and type
     * Higher score = more dangerous to marine life
     */
    fun getRiskScore(): Int {
        val materialRisk = when (material) {
            DebrisMaterial.PLASTIC -> 5  // High risk - microplastics, ingestion
            DebrisMaterial.FABRIC -> 4   // High risk - entanglement
            DebrisMaterial.FISHING_NET -> 5  // Very high risk - entanglement
            DebrisMaterial.METAL -> 3    // Medium risk - sharp edges
            DebrisMaterial.RUBBER -> 3   // Medium risk
            DebrisMaterial.GLASS -> 4    // High risk - cuts
            DebrisMaterial.OTHER -> 2    // Unknown risk
        }

        val typeRisk = when (type) {
            DebrisType.FISHING_NET -> 5
            DebrisType.MASK -> 4
            DebrisType.GLOVE -> 4
            DebrisType.PLASTIC_DEBRIS -> 4
            DebrisType.TIRE -> 3
            DebrisType.BOTTLE -> 3
            DebrisType.CAN -> 2
            else -> 2
        }

        return maxOf(materialRisk, typeRisk)
    }
}

/**
 * Bounding box coordinates (in pixels)
 */
data class BoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    fun centerX() = x + width / 2
    fun centerY() = y + height / 2
}

/**
 * Material types
 */
enum class DebrisMaterial {
    PLASTIC,
    METAL,
    FABRIC,
    RUBBER,
    GLASS,
    FISHING_NET,
    OTHER;

    companion object {
        fun fromString(value: String): DebrisMaterial {
            return try {
                valueOf(value.uppercase().replace(" ", "_"))
            } catch (e: IllegalArgumentException) {
                OTHER
            }
        }
    }
}

/**
 * Specific debris types
 */
enum class DebrisType {
    BOTTLE,
    CAN,
    FISHING_NET,
    GLOVE,
    MASK,
    METAL_DEBRIS,
    PLASTIC_DEBRIS,
    TIRE,
    FABRIC_DEBRIS,
    GLASS_DEBRIS,
    OTHER;

    companion object {
        fun fromString(value: String): DebrisType {
            return try {
                valueOf(value.uppercase().replace(" ", "_"))
            } catch (e: IllegalArgumentException) {
                OTHER
            }
        }
    }
}

/**
 * Complete debris detection result
 */
data class DebrisDetection(
    val debrisList: List<Debris>,
    val totalCount: Int,
    val imageQuality: ImageQuality,
    val processingTimeMs: Long = 0
) {
    /**
     * Calculate ecosystem health score (0-100)
     * Lower score = more pollution
     */
    fun calculateHealthScore(): Int {
        if (debrisList.isEmpty()) return 100

        // Calculate average risk
        val avgRisk = debrisList.map { it.getRiskScore() }.average()

        // Density factor (objects per unit area - normalized)
        val densityFactor = minOf(debrisList.size / 10.0, 1.0)

        // Health score (inverse of risk)
        val healthScore = 100 - (avgRisk * 10 + densityFactor * 30).toInt()

        return maxOf(0, minOf(100, healthScore))
    }

    /**
     * Get material breakdown
     */
    fun getMaterialBreakdown(): Map<DebrisMaterial, Int> {
        return debrisList.groupBy { it.material }
            .mapValues { it.value.size }
    }

    /**
     * Get high-risk debris (risk score >= 4)
     */
    fun getHighRiskDebris(): List<Debris> {
        return debrisList.filter { it.getRiskScore() >= 4 }
    }
}

/**
 * Image quality assessment
 */
enum class ImageQuality {
    GOOD,
    FAIR,
    POOR;

    companion object {
        fun fromString(value: String): ImageQuality {
            return try {
                valueOf(value.uppercase())
            } catch (e: IllegalArgumentException) {
                FAIR
            }
        }
    }
}

/**
 * Geographic location
 */
data class Location(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val source: LocationSource = LocationSource.UNKNOWN
)

enum class LocationSource {
    GPS,
    EXIF,
    MANUAL,
    UNKNOWN
}

/**
 * Detection session stored in database
 */
@Entity(
    tableName = "detection_sessions",
    indices = [Index(value = ["imageUri"], unique = true)]
)
@TypeConverters(DebrisListConverter::class, LocationConverter::class)
data class DetectionSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val imageUri: String,
    val thumbnailUri: String? = null,

    val debrisList: List<Debris>,
    val totalCount: Int,
    val healthScore: Int,

    val location: Location? = null,
    val timestamp: Date = Date(),

    val imageQuality: ImageQuality,
    val processingTimeMs: Long,

    val notes: String? = null,
    val tags: String? = null  // Comma-separated tags
) {
    /**
     * Get risk level based on health score
     */
    fun getRiskLevel(): RiskLevel {
        return when {
            healthScore >= 80 -> RiskLevel.LOW
            healthScore >= 60 -> RiskLevel.MODERATE
            healthScore >= 40 -> RiskLevel.HIGH
            else -> RiskLevel.CRITICAL
        }
    }
}

enum class RiskLevel {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}

/**
 * AI-generated environmental report persisted for later viewing.
 */
@Entity(tableName = "generated_reports")
data class GeneratedReport(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val language: String,
    val sessionCount: Int,
    val usedAi: Boolean,
    val timestamp: Date = Date(),
    // v4: location metadata (nullable for backward compat)
    val locationName: String? = null,
    val centroidLat: Double? = null,
    val centroidLon: Double? = null,
    val dateRangeStartMs: Long? = null,
    val dateRangeEndMs: Long? = null,
    // v7: audience used for report generation (nullable for backward compat)
    val audience: String? = null,
    // v9: post-generation validation score (0-100) and JSON details
    val validationScore: Int? = null,
    val validationDetails: String? = null,
    // v10: comma-separated session IDs used for this report
    val sessionIds: String? = null,
)

/**
 * Statistics aggregated from multiple sessions
 */
data class DetectionStatistics(
    val totalSessions: Int,
    val totalDebrisDetected: Int,
    val avgHealthScore: Float,
    val materialBreakdown: Map<DebrisMaterial, Int>,
    val hotspots: List<Location>,
    val dateRange: Pair<Date, Date>?
)

/**
 * Video analysis result stored in database.
 * Contains aggregate metrics from frame-by-frame detection with tracking.
 */
@Entity(tableName = "video_analyses")
@TypeConverters(LocationConverter::class)
data class VideoAnalysis(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sourceVideoUri: String,
    val outputVideoUri: String? = null,
    val thumbnailUri: String? = null,

    val durationMs: Long,
    val totalFrameCount: Int,
    val processedFrameCount: Int,

    val uniqueDebrisCount: Int,
    val classCounts: String,              // JSON: {"Bottle":3,"Can":1}
    val totalProcessingTimeMs: Long,
    val avgInferenceTimeMs: Float,
    val healthScore: Int,

    val location: Location? = null,
    val timestamp: Date = Date(),
    val status: String = "processing",    // processing | complete | failed
    val tags: String? = null
) {
    fun getRiskLevel(): RiskLevel {
        return when {
            healthScore >= 80 -> RiskLevel.LOW
            healthScore >= 60 -> RiskLevel.MODERATE
            healthScore >= 40 -> RiskLevel.HIGH
            else -> RiskLevel.CRITICAL
        }
    }
}
