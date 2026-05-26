package com.oceanguard.ai.data.species

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Location
import java.util.Date

/**
 * A single species identification observation persisted in the BioDex track.
 *
 * This entity is fully decoupled from [com.oceanguard.ai.data.DetectionSession];
 * [sessionId] is an optional soft link (no FK constraint) so the debris pipeline
 * is never blocked or affected by this table.
 *
 * [cosineScore] is the raw max-prototype cosine similarity from [com.oceanguard.ai.inference.species.SpeciesReferenceIndex].
 * [confidence] is the calibrated Platt-scaled probability from [com.oceanguard.ai.inference.species.SpeciesConfidence].
 * [idSource] stores the [SpeciesIdSource] enum name so adding new sources is
 * backward-compatible with existing rows.
 * [geoMatchLevel] stores the [com.oceanguard.ai.inference.species.GeoMatchLevel] enum name.
 */
@Entity(
    tableName = "species_observations",
    indices = [Index("sessionId"), Index("aphiaId")],
)
data class SpeciesObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageUri: String,
    val thumbnailUri: String? = null,
    /** Soft link to DetectionSession.id — null for standalone BioDex captures. */
    val sessionId: Long? = null,
    /** WoRMS AphiaID — null for uncatalogued (open-vocab VLM) identifications. */
    val aphiaId: Long? = null,
    val scientificName: String,
    /** i18n key to look up in SpeciesCatalogEntry.commonNames; null if uncatalogued. */
    val commonNameKey: String? = null,
    /** BoundingBox of the organism crop; null = full frame was used. */
    val bbox: BoundingBox? = null,
    /** Raw cosine similarity from index search, in [0, 1]. */
    val cosineScore: Float,
    /** Platt-calibrated confidence in [0, 1]. */
    val confidence: Float,
    /** Name of [SpeciesIdSource] enum entry: RAG_CORE | RAG_TENTATIVE | VLM_OPENVOCAB. */
    val idSource: String,
    /** VLM-generated description / confirmation text. Null when VLM was not invoked. */
    val vlmDescription: String? = null,
    /** True when the organism could not be matched to any catalogued species. */
    val uncatalogued: Boolean = false,
    /** MEOW ecoregion ID resolved from the capture GPS. Null when no GPS available. */
    val ecoregionId: Int? = null,
    /** Name of [com.oceanguard.ai.inference.species.GeoMatchLevel] enum entry. Null when no GPS. */
    val geoMatchLevel: String? = null,
    /** True when the species was identified outside its known distribution range. */
    val outOfRange: Boolean = false,
    val timestamp: Date = Date(),
    val location: Location? = null,
)
