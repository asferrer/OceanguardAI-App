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
        "Bottle", "Can", "Fishing_Net", "Glove", "Mask",
        "Metal_Debris", "Plastic_Debris", "Tire",
    )

    /** Explicit mapping from DebrisType enum to COCO category index.
     *  Types not in the 8-class model are mapped to the closest match. */
    private val CATEGORY_ID_MAP: Map<DebrisType, Int> = mapOf(
        DebrisType.BOTTLE         to 0,
        DebrisType.CAN            to 1,
        DebrisType.FISHING_NET    to 2,
        DebrisType.GLOVE          to 3,
        DebrisType.MASK           to 4,
        DebrisType.METAL_DEBRIS   to 5,
        DebrisType.PLASTIC_DEBRIS to 6,
        DebrisType.TIRE           to 7,
        // Types outside the 8-class model → closest COCO category
        DebrisType.FABRIC_DEBRIS  to 6, // → Plastic_Debris (generic debris)
        DebrisType.GLASS_DEBRIS   to 5, // → Metal_Debris (hard sharp debris)
        DebrisType.OTHER          to 6, // → Plastic_Debris (generic fallback)
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
