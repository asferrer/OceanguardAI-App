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
    val confidence: Float,
    /**
     * Raw `snake_case` label the VLM actually emitted (Gemma 4 vision detector).
     * `null` when the detection comes from RT-DETRv2 (canonical-only).
     * Shown verbatim on the bounding-box overlay so the user sees what the
     * model recognised — not the canonical bucket.
     */
    val rawLabel: String? = null,
    /**
     * Fine-grained [DebrisType] sub-type after alias resolution (`PLASTIC_BAG`,
     * `GLASS_JAR`, `CIGARETTE_BUTT`). Always `subType.canonical() == type`.
     * `null` for RT-DETRv2 (canonical-only). The UI uses `subType ?: type` for
     * per-object icons and labels, while [type] continues to drive achievements,
     * MarineDex unlocks and report aggregations.
     */
    val subType: DebrisType? = null,
) {
    /**
     * Get risk score based on material and type
     * Higher score = more dangerous to marine life
     */
    fun getRiskScore(): Int {
        val materialRisk = when (material) {
            DebrisMaterial.PLASTIC -> 5      // High risk - microplastics, ingestion
            DebrisMaterial.FABRIC -> 4       // High risk - entanglement
            DebrisMaterial.FISHING_NET -> 5  // Very high risk - entanglement
            DebrisMaterial.METAL -> 3        // Medium risk - sharp edges
            DebrisMaterial.RUBBER -> 3       // Medium risk
            DebrisMaterial.GLASS -> 4        // High risk - cuts
            DebrisMaterial.WOOD -> 2         // Low risk
            DebrisMaterial.PAPER -> 1        // Minimal risk, biodegrades fast
            DebrisMaterial.CERAMIC -> 3      // Medium risk - sharp edges
            DebrisMaterial.CHEMICAL -> 5     // Very high risk - toxicity
            DebrisMaterial.OTHER -> 2        // Unknown risk
        }

        val typeRisk = when (type) {
            DebrisType.FISHING_NET, DebrisType.FISHING_LINE -> 5
            DebrisType.FISHING_TRAP -> 5
            DebrisType.SIX_PACK_RING -> 5   // Entanglement hazard
            DebrisType.SYRINGE, DebrisType.CHEMICAL_DRUM -> 5
            DebrisType.MASK, DebrisType.GLOVE -> 4
            DebrisType.PLASTIC_DEBRIS, DebrisType.PLASTIC_BAG -> 4
            DebrisType.CIGARETTE_BUTT -> 4  // Toxic leachate
            DebrisType.BATTERY -> 5         // Heavy metal contamination
            DebrisType.TIRE -> 3
            DebrisType.BOTTLE, DebrisType.GLASS_BOTTLE -> 3
            DebrisType.STYROFOAM -> 4       // Fragments into microplastics
            DebrisType.CAN -> 2
            DebrisType.CARDBOARD, DebrisType.PAPER -> 1
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
 * Material types -- covers all major marine debris material categories
 * per NOAA MDMAP, OSPAR, and MSFD D10 classification systems.
 */
enum class DebrisMaterial {
    PLASTIC,
    METAL,
    FABRIC,
    RUBBER,
    GLASS,
    FISHING_NET,
    WOOD,
    PAPER,
    CERAMIC,
    CHEMICAL,
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
 * Specific debris types -- the first 8 match RT-DETRv2 trained classes (indices 0-7).
 * Extended types (from GEMMA4_VISION+) are detected by Gemma 4 open-vocabulary.
 * Room stores the enum name as a String, so new entries are backward-compatible.
 */
enum class DebrisType {
    // --- RT-DETRv2 core classes (0-7) ---
    BOTTLE,
    CAN,
    FISHING_NET,
    GLOVE,
    MASK,
    METAL_DEBRIS,
    PLASTIC_DEBRIS,
    TIRE,
    // --- Extended types (Gemma 4 open-vocabulary) ---
    FABRIC_DEBRIS,
    GLASS_DEBRIS,
    // Plastic sub-types
    BOTTLE_CAP,
    PLASTIC_BAG,
    FOOD_WRAPPER,
    STYROFOAM,
    PLASTIC_CUP,
    STRAW,
    PLASTIC_UTENSIL,
    SIX_PACK_RING,
    PLASTIC_SHEETING,
    DIAPER,
    // Cigarette / smoking
    CIGARETTE_BUTT,
    CIGARETTE_LIGHTER,
    // Fishing gear
    FISHING_LINE,
    ROPE,
    FISHING_BUOY,
    FISHING_TRAP,
    // Metal sub-types
    AEROSOL_CAN,
    METAL_DRUM,
    WIRE_CABLE,
    BATTERY,
    ELECTRONICS,
    // Glass sub-types
    GLASS_BOTTLE,
    GLASS_JAR,
    GLASS_FRAGMENT,
    LIGHT_BULB,
    // Rubber sub-types
    FLIP_FLOP,
    RUBBER_HOSE,
    // Textile / fabric
    CLOTHING,
    SHOE,
    // Paper / cardboard
    CARDBOARD,
    PAPER,
    // Wood
    WOOD_PALLET,
    LUMBER,
    // Ceramic
    CERAMIC_FRAGMENT,
    BRICK,
    // Hazardous
    PAINT_CAN,
    OIL_CONTAINER,
    SYRINGE,
    CHEMICAL_DRUM,
    // Catch-all
    OTHER;

    /**
     * Collapse this debris type to one of the 11 canonical types shown in the
     * MarineDex, achievements, and reports. Extended types map to their family
     * parent (e.g. PLASTIC_BAG -> PLASTIC_DEBRIS); canonical types map to
     * themselves.
     */
    fun canonical(): DebrisType = CANONICAL_PARENT[this] ?: this

    companion object {
        /** Core RT-DETRv2 class count (indices 0-7). */
        const val RTDETR_CLASS_COUNT = 8

        /** Number of canonical types shown in MarineDex and Achievements. */
        const val CANONICAL_COUNT = 11

        /**
         * The 11 canonical debris types. Order matches the MarineDex grid.
         * These are the only types persisted in marine_dex_entries and counted
         * by dex_* achievements after canonicalization.
         */
        val CANONICAL: List<DebrisType> = listOf(
            BOTTLE, CAN, FISHING_NET, GLOVE, MASK, METAL_DEBRIS,
            PLASTIC_DEBRIS, TIRE, FABRIC_DEBRIS, GLASS_DEBRIS, OTHER,
        )

        /**
         * Maps non-canonical types to their canonical parent. Canonical types
         * are intentionally absent (they fall through to `this` in [canonical]).
         *
         * Rationale: cigarette butts/lighters and naturals (wood, paper, ceramic)
         * map to OTHER so users can see hazardous-or-natural debris separately
         * from generic plastic. Hazardous metal/glass containers map by physical
         * material (BATTERY -> METAL_DEBRIS) so reports reflect composition.
         */
        private val CANONICAL_PARENT: Map<DebrisType, DebrisType> = mapOf(
            // plastic family -> PLASTIC_DEBRIS (10)
            BOTTLE_CAP        to PLASTIC_DEBRIS,
            PLASTIC_BAG       to PLASTIC_DEBRIS,
            FOOD_WRAPPER      to PLASTIC_DEBRIS,
            STYROFOAM         to PLASTIC_DEBRIS,
            PLASTIC_CUP       to PLASTIC_DEBRIS,
            STRAW             to PLASTIC_DEBRIS,
            PLASTIC_UTENSIL   to PLASTIC_DEBRIS,
            SIX_PACK_RING     to PLASTIC_DEBRIS,
            PLASTIC_SHEETING  to PLASTIC_DEBRIS,
            DIAPER            to PLASTIC_DEBRIS,
            // metal family -> METAL_DEBRIS (8)
            AEROSOL_CAN       to METAL_DEBRIS,
            METAL_DRUM        to METAL_DEBRIS,
            WIRE_CABLE        to METAL_DEBRIS,
            BATTERY           to METAL_DEBRIS,
            ELECTRONICS       to METAL_DEBRIS,
            PAINT_CAN         to METAL_DEBRIS,
            OIL_CONTAINER     to METAL_DEBRIS,
            CHEMICAL_DRUM     to METAL_DEBRIS,
            // glass family -> GLASS_DEBRIS (4)
            GLASS_BOTTLE      to GLASS_DEBRIS,
            GLASS_JAR         to GLASS_DEBRIS,
            GLASS_FRAGMENT    to GLASS_DEBRIS,
            LIGHT_BULB        to GLASS_DEBRIS,
            // fishing family -> FISHING_NET (4)
            FISHING_LINE      to FISHING_NET,
            ROPE              to FISHING_NET,
            FISHING_BUOY      to FISHING_NET,
            FISHING_TRAP      to FISHING_NET,
            // rubber family -> TIRE (2)
            FLIP_FLOP         to TIRE,
            RUBBER_HOSE       to TIRE,
            // fabric family -> FABRIC_DEBRIS (2)
            CLOTHING          to FABRIC_DEBRIS,
            SHOE              to FABRIC_DEBRIS,
            // hazardous & natural -> OTHER (9)
            CIGARETTE_BUTT    to OTHER,
            CIGARETTE_LIGHTER to OTHER,
            SYRINGE           to OTHER,
            CARDBOARD         to OTHER,
            PAPER             to OTHER,
            WOOD_PALLET       to OTHER,
            LUMBER            to OTHER,
            CERAMIC_FRAGMENT  to OTHER,
            BRICK             to OTHER,
        )

        fun fromString(value: String): DebrisType {
            return try {
                valueOf(value.uppercase().replace(" ", "_"))
            } catch (e: IllegalArgumentException) {
                OTHER
            }
        }

        /** Returns true if this type is within the RT-DETRv2 8-class set. */
        fun isRtDetrClass(type: DebrisType): Boolean = type.ordinal < RTDETR_CLASS_COUNT

        /** Returns true if this type is one of the 11 canonical types. */
        fun isCanonical(type: DebrisType): Boolean = type in CANONICAL
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
