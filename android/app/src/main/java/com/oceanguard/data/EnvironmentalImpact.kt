package com.oceanguard.ai.data

/**
 * Environmental impact information for each debris type.
 *
 * Data sourced from NOAA Marine Debris Program, UNEP reports, and
 * the OceanGuard Streamlit demo application.
 */
data class DebrisImpactInfo(
    val degradationTime: String,
    val primaryRisk: String,
    val riskScore: Int,
    val icon: String,
    val annualVolumeOcean: String? = null
)

/**
 * Singleton object containing the environmental impact dictionary
 * for all debris types detected by OceanGuard AI.
 */
object EnvironmentalImpact {

    val IMPACT_MAP: Map<DebrisType, DebrisImpactInfo> = mapOf(
        DebrisType.BOTTLE to DebrisImpactInfo(
            degradationTime = "450+ years",
            primaryRisk = "Ingestion risk, microplastic fragmentation",
            riskScore = 8,
            icon = "\uD83C\uDF7E", // bottle
            annualVolumeOcean = "~1M tonnes/year"
        ),
        DebrisType.CAN to DebrisImpactInfo(
            degradationTime = "200+ years",
            primaryRisk = "Sharp edges, toxic chemical leaching",
            riskScore = 6,
            icon = "\uD83E\uDD6B", // can
            annualVolumeOcean = "~500K tonnes/year"
        ),
        DebrisType.FISHING_NET to DebrisImpactInfo(
            degradationTime = "600+ years",
            primaryRisk = "Ghost fishing, entanglement of marine life",
            riskScore = 10,
            icon = "\uD83D\uDD78\uFE0F", // spider web
            annualVolumeOcean = "~640K tonnes/year"
        ),
        DebrisType.GLOVE to DebrisImpactInfo(
            degradationTime = "100+ years",
            primaryRisk = "Ingestion risk, microplastic release",
            riskScore = 7,
            icon = "\uD83E\uDDE4", // glove
        ),
        DebrisType.MASK to DebrisImpactInfo(
            degradationTime = "450+ years",
            primaryRisk = "Entanglement of small marine organisms",
            riskScore = 7,
            icon = "\uD83D\uDE37", // face with mask
        ),
        DebrisType.METAL_DEBRIS to DebrisImpactInfo(
            degradationTime = "200-500 years",
            primaryRisk = "Sharp edges, chemical leaching, habitat disruption",
            riskScore = 6,
            icon = "\u2699\uFE0F", // gear
        ),
        DebrisType.PLASTIC_DEBRIS to DebrisImpactInfo(
            degradationTime = "450+ years",
            primaryRisk = "Microplastic fragmentation, ingestion by marine fauna",
            riskScore = 8,
            icon = "\uD83D\uDCA7", // droplet
            annualVolumeOcean = "~8M tonnes/year"
        ),
        DebrisType.TIRE to DebrisImpactInfo(
            degradationTime = "2000+ years",
            primaryRisk = "Toxic chemicals (zinc, cadmium, heavy metals)",
            riskScore = 9,
            icon = "\uD83D\uDEDE", // wheel
        ),
        DebrisType.FABRIC_DEBRIS to DebrisImpactInfo(
            degradationTime = "1-200+ years",
            primaryRisk = "Microfiber release, entanglement",
            riskScore = 5,
            icon = "\uD83D\uDC55", // t-shirt
        ),
        DebrisType.GLASS_DEBRIS to DebrisImpactInfo(
            degradationTime = "1,000,000+ years",
            primaryRisk = "Physical injury to marine life",
            riskScore = 4,
            icon = "\uD83E\uDE9F", // window \u2014 glass material
        ),
        DebrisType.OTHER to DebrisImpactInfo(
            degradationTime = "Variable",
            primaryRisk = "Unknown environmental impact",
            riskScore = 3,
            icon = "\u2753", // question mark
        ),
    )

    /**
     * Get the impact info for a given debris type,
     * falling back to OTHER if not found.
     */
    fun getImpact(type: DebrisType): DebrisImpactInfo {
        return IMPACT_MAP[type] ?: IMPACT_MAP[DebrisType.OTHER]!!
    }
}
