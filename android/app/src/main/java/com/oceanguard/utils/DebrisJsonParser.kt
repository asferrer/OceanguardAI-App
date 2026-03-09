package com.oceanguard.ai.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.oceanguard.ai.data.*

/**
 * DebrisJsonParser - Robust JSON parsing for model responses
 *
 * Handles various response formats and gracefully degrades when JSON is malformed.
 * The LLM may return responses in different formats, so this parser is defensive.
 */
class DebrisJsonParser {

    companion object {
        private const val TAG = "DebrisJsonParser"
    }

    private val gson = Gson()

    /**
     * Parse debris detection response from model
     *
     * Expected format:
     * {
     *   "debris_detected": [
     *     {
     *       "bbox": [x, y, width, height],
     *       "material": "Plastic",
     *       "type": "Bottle",
     *       "confidence": 0.95
     *     }
     *   ],
     *   "total_count": 1,
     *   "image_quality": "good"
     * }
     */
    fun parseDetection(response: String): DebrisDetection {
        try {
            Log.d(TAG, "Parsing detection response...")

            // Extract JSON from response (model may include text before/after JSON)
            val jsonString = extractJson(response)

            if (jsonString == null) {
                Log.w(TAG, "No JSON found in response, assuming no debris")
                return DebrisDetection(
                    debrisList = emptyList(),
                    totalCount = 0,
                    imageQuality = ImageQuality.FAIR
                )
            }

            // Parse JSON
            val jsonObject = JsonParser.parseString(jsonString).asJsonObject

            // Extract debris list
            val debrisList = mutableListOf<Debris>()

            val debrisArray = jsonObject.getAsJsonArray("debris_detected")
                ?: jsonObject.getAsJsonArray("debris")
                ?: jsonObject.getAsJsonArray("objects")

            debrisArray?.forEach { element ->
                try {
                    val debrisObj = element.asJsonObject
                    val debris = parseDebrisObject(debrisObj)
                    if (debris != null) {
                        debrisList.add(debris)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse debris object: $element", e)
                }
            }

            // Extract total count
            val totalCount = when {
                jsonObject.has("total_count") -> jsonObject.get("total_count").asInt
                jsonObject.has("count") -> jsonObject.get("count").asInt
                else -> debrisList.size
            }

            // Extract image quality
            val qualityString = jsonObject.get("image_quality")?.asString
                ?: jsonObject.get("quality")?.asString
                ?: "fair"

            val imageQuality = ImageQuality.fromString(qualityString)

            Log.i(TAG, "Parsed ${debrisList.size} debris objects")

            return DebrisDetection(
                debrisList = debrisList,
                totalCount = totalCount,
                imageQuality = imageQuality
            )

        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "JSON parsing error", e)
            // Return empty detection rather than crashing
            return DebrisDetection(
                debrisList = emptyList(),
                totalCount = 0,
                imageQuality = ImageQuality.POOR
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error parsing detection", e)
            throw DebrisParsingException("Failed to parse debris detection: ${e.message}", e)
        }
    }

    /**
     * Parse counting response
     *
     * Expected format:
     * {
     *   "total_count": 10,
     *   "by_material": {
     *     "Plastic": 6,
     *     "Metal": 2
     *   }
     * }
     */
    fun parseCounts(response: String): Map<String, Any> {
        try {
            val jsonString = extractJson(response)
                ?: return mapOf("total_count" to 0)

            val jsonObject = JsonParser.parseString(jsonString).asJsonObject

            val totalCount = jsonObject.get("total_count")?.asInt ?: 0

            val byMaterial = mutableMapOf<String, Int>()
            val materialObj = jsonObject.getAsJsonObject("by_material")
                ?: jsonObject.getAsJsonObject("breakdown")

            materialObj?.entrySet()?.forEach { (key, value) ->
                byMaterial[key] = value.asInt
            }

            return mapOf(
                "total_count" to totalCount,
                "by_material" to byMaterial
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse counts", e)
            return mapOf("total_count" to 0)
        }
    }

    /**
     * Parse individual debris object
     */
    private fun parseDebrisObject(jsonObject: JsonObject): Debris? {
        try {
            // Parse bounding box
            val bboxArray = jsonObject.getAsJsonArray("bbox")
                ?: jsonObject.getAsJsonArray("bounding_box")
                ?: return null

            if (bboxArray.size() < 4) {
                Log.w(TAG, "Invalid bbox array size: ${bboxArray.size()}")
                return null
            }

            val bbox = BoundingBox(
                x = bboxArray[0].asFloat,
                y = bboxArray[1].asFloat,
                width = bboxArray[2].asFloat,
                height = bboxArray[3].asFloat
            )

            // Parse material
            val materialString = jsonObject.get("material")?.asString ?: "Other"
            val material = DebrisMaterial.fromString(materialString)

            // Parse type
            val typeString = jsonObject.get("type")?.asString
                ?: jsonObject.get("debris_type")?.asString
                ?: "Other"
            val debrisType = DebrisType.fromString(typeString)

            // Parse confidence
            val confidence = jsonObject.get("confidence")?.asFloat
                ?: jsonObject.get("score")?.asFloat
                ?: 0.0f

            return Debris(
                bbox = bbox,
                material = material,
                type = debrisType,
                confidence = confidence
            )

        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse debris object", e)
            return null
        }
    }

    /**
     * Extract JSON object/array from text that may contain other content
     */
    private fun extractJson(text: String): String? {
        // Find first { or [
        val startIndex = text.indexOfFirst { it == '{' || it == '[' }
        if (startIndex == -1) {
            return null
        }

        // Find matching closing bracket
        val openChar = text[startIndex]
        val closeChar = if (openChar == '{') '}' else ']'

        var depth = 0
        var endIndex = -1

        for (i in startIndex until text.length) {
            when (text[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) {
                        endIndex = i
                        break
                    }
                }
            }
        }

        if (endIndex == -1) {
            return null
        }

        return text.substring(startIndex, endIndex + 1)
    }

    /**
     * Validate bounding box coordinates
     */
    private fun validateBbox(bbox: BoundingBox, maxWidth: Int = 4096, maxHeight: Int = 4096): Boolean {
        return bbox.x >= 0 && bbox.y >= 0 &&
                bbox.width > 0 && bbox.height > 0 &&
                bbox.x + bbox.width <= maxWidth &&
                bbox.y + bbox.height <= maxHeight
    }

    /**
     * Clean and normalize debris list
     */
    fun cleanDebrisList(debrisList: List<Debris>): List<Debris> {
        return debrisList
            .filter { it.confidence >= 0.3f }  // Filter low-confidence detections
            .sortedByDescending { it.confidence }  // Sort by confidence
            .distinctBy { "${it.bbox.x},${it.bbox.y},${it.type}" }  // Remove duplicates
    }
}

/**
 * Exception thrown when JSON parsing fails critically
 */
class DebrisParsingException(message: String, cause: Throwable? = null) : Exception(message, cause)
