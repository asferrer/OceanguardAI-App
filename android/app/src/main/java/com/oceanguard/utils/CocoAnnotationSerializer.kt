package com.oceanguard.ai.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import java.io.File

/**
 * Builds a COCO-format JSON string from a [DetectionSession].
 *
 * BoundingBox coordinates in [DetectionSession.debrisList] are normalized [0,1]
 * (confirmed: DetectionOrchestrator.convertRTDETRToDebrisDetection stores them directly
 * from DetectionResult which keeps coordinates normalized for UI scaling).
 * This serializer converts them to absolute pixels by reading the real image dimensions.
 */
object CocoAnnotationSerializer {

    private const val TAG = "CocoAnnotation"

    private val CATEGORIES = listOf(
        // RT-DETRv2 core (0-7)
        "Bottle", "Can", "Fishing_Net", "Glove", "Mask",
        "Metal_Debris", "Plastic_Debris", "Tire",
        // Extended (Gemma 4 open-vocabulary, 8+)
        "Fabric_Debris", "Glass_Debris",
        "Bottle_Cap", "Plastic_Bag", "Food_Wrapper", "Styrofoam",
        "Plastic_Cup", "Straw", "Plastic_Utensil", "Six_Pack_Ring",
        "Plastic_Sheeting", "Diaper",
        "Cigarette_Butt", "Cigarette_Lighter",
        "Fishing_Line", "Rope", "Fishing_Buoy", "Fishing_Trap",
        "Aerosol_Can", "Metal_Drum", "Wire_Cable", "Battery", "Electronics",
        "Glass_Bottle", "Glass_Jar", "Glass_Fragment", "Light_Bulb",
        "Flip_Flop", "Rubber_Hose",
        "Clothing", "Shoe",
        "Cardboard", "Paper",
        "Wood_Pallet", "Lumber",
        "Ceramic_Fragment", "Brick",
        "Paint_Can", "Oil_Container", "Syringe", "Chemical_Drum",
    )

    /** Explicit mapping from DebrisType enum to COCO category index. */
    private val CATEGORY_ID_MAP: Map<DebrisType, Int> = mapOf(
        DebrisType.BOTTLE            to 0,
        DebrisType.CAN               to 1,
        DebrisType.FISHING_NET       to 2,
        DebrisType.GLOVE             to 3,
        DebrisType.MASK              to 4,
        DebrisType.METAL_DEBRIS      to 5,
        DebrisType.PLASTIC_DEBRIS    to 6,
        DebrisType.TIRE              to 7,
        DebrisType.FABRIC_DEBRIS     to 8,
        DebrisType.GLASS_DEBRIS      to 9,
        DebrisType.BOTTLE_CAP        to 10,
        DebrisType.PLASTIC_BAG       to 11,
        DebrisType.FOOD_WRAPPER      to 12,
        DebrisType.STYROFOAM         to 13,
        DebrisType.PLASTIC_CUP       to 14,
        DebrisType.STRAW             to 15,
        DebrisType.PLASTIC_UTENSIL   to 16,
        DebrisType.SIX_PACK_RING     to 17,
        DebrisType.PLASTIC_SHEETING  to 18,
        DebrisType.DIAPER            to 19,
        DebrisType.CIGARETTE_BUTT    to 20,
        DebrisType.CIGARETTE_LIGHTER to 21,
        DebrisType.FISHING_LINE      to 22,
        DebrisType.ROPE              to 23,
        DebrisType.FISHING_BUOY      to 24,
        DebrisType.FISHING_TRAP      to 25,
        DebrisType.AEROSOL_CAN       to 26,
        DebrisType.METAL_DRUM        to 27,
        DebrisType.WIRE_CABLE        to 28,
        DebrisType.BATTERY           to 29,
        DebrisType.ELECTRONICS       to 30,
        DebrisType.GLASS_BOTTLE      to 31,
        DebrisType.GLASS_JAR         to 32,
        DebrisType.GLASS_FRAGMENT    to 33,
        DebrisType.LIGHT_BULB        to 34,
        DebrisType.FLIP_FLOP         to 35,
        DebrisType.RUBBER_HOSE       to 36,
        DebrisType.CLOTHING          to 37,
        DebrisType.SHOE              to 38,
        DebrisType.CARDBOARD         to 39,
        DebrisType.PAPER             to 40,
        DebrisType.WOOD_PALLET       to 41,
        DebrisType.LUMBER            to 42,
        DebrisType.CERAMIC_FRAGMENT  to 43,
        DebrisType.BRICK             to 44,
        DebrisType.PAINT_CAN         to 45,
        DebrisType.OIL_CONTAINER     to 46,
        DebrisType.SYRINGE           to 47,
        DebrisType.CHEMICAL_DRUM     to 48,
        DebrisType.OTHER             to 6,  // fallback to Plastic_Debris
    )

    private val gson = Gson()

    /**
     * Build COCO JSON for the session. Returns null if the image file is missing
     * or has invalid dimensions (caller should skip enqueue).
     */
    fun build(context: Context, session: DetectionSession): String? {
        val imageFile = File(session.imageUri.removePrefix("file://"))
        if (!imageFile.exists()) {
            Log.w(TAG, "Image file missing, skipping COCO serialization: ${session.imageUri}")
            return null
        }
        val (imgW, imgH) = readDims(session.imageUri)
        if (imgW <= 1 || imgH <= 1) {
            Log.w(TAG, "Invalid image dimensions ${imgW}x${imgH}, skipping: ${session.imageUri}")
            return null
        }
        val fileName = imageFile.name

        val annotations = session.debrisList.mapIndexed { i, debris ->
            val catId = CATEGORY_ID_MAP[debris.type] ?: 6 // fallback to Plastic_Debris
            // Clamp bbox to [0,1] before denormalization
            val bx = debris.bbox.x.coerceIn(0f, 1f)
            val by = debris.bbox.y.coerceIn(0f, 1f)
            val bw = debris.bbox.width.coerceIn(0f, 1f - bx)
            val bh = debris.bbox.height.coerceIn(0f, 1f - by)
            mapOf(
                "id" to (i + 1),
                "image_id" to 1,
                "category_id" to catId,
                "category_name" to debris.type.name,
                "bbox" to listOf(
                    bx * imgW,
                    by * imgH,
                    bw * imgW,
                    bh * imgH,
                ),
                "area" to (bw * imgW * bh * imgH),
                "iscrowd" to 0,
                "score" to debris.confidence,
            )
        }

        val cocoJson = mapOf(
            "info" to mapOf(
                "description" to "OceanGuard AI contribution",
                "version" to "1.0",
                "date_created" to session.timestamp.toInstant().toString(),
            ),
            "images" to listOf(
                mapOf(
                    "id" to 1,
                    "file_name" to fileName,
                    "width" to imgW,
                    "height" to imgH,
                    "date_captured" to session.timestamp.toInstant().toString(),
                )
            ),
            "annotations" to annotations,
            "categories" to CATEGORIES.mapIndexed { i, name ->
                mapOf("id" to i, "name" to name)
            },
        )

        return gson.toJson(cocoJson)
    }

    /** Reads image dimensions without decoding the full bitmap. */
    private fun readDims(fileUri: String): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fileUri.removePrefix("file://"), opts)
        return Pair(opts.outWidth.coerceAtLeast(1), opts.outHeight.coerceAtLeast(1))
    }
}
