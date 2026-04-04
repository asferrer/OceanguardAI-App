package com.oceanguard.ai.utils

import android.content.Context
import android.graphics.BitmapFactory
import com.google.gson.Gson
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

    private val CATEGORIES = listOf(
        "Bottle", "Can", "Fishing_Net", "Glove", "Mask",
        "Metal_Debris", "Plastic_Debris", "Tire",
    )

    private val gson = Gson()

    fun build(context: Context, session: DetectionSession): String {
        val (imgW, imgH) = readDims(session.imageUri)
        val fileName = File(session.imageUri.removePrefix("file://")).name

        val annotations = session.debrisList.mapIndexed { i, debris ->
            mapOf(
                "id" to (i + 1),
                "image_id" to 1,
                "category_id" to debris.type.ordinal,
                "category_name" to debris.type.name,
                "bbox" to listOf(
                    debris.bbox.x * imgW,
                    debris.bbox.y * imgH,
                    debris.bbox.width * imgW,
                    debris.bbox.height * imgH,
                ),
                "area" to (debris.bbox.width * imgW * debris.bbox.height * imgH),
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
