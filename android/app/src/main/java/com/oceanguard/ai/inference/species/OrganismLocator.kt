package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.JsonParser
import com.oceanguard.ai.data.BoundingBox

/**
 * An organism bounding box + crop extracted from a full frame.
 *
 * @param bbox        Bounding box in original image coordinates (pixels).
 * @param crop        Cropped sub-bitmap ready for [SpeciesEmbedder.embed].
 * @param confidence  Localiser confidence for this detection (0..1).
 */
data class OrganismCrop(
    val bbox: BoundingBox,
    val crop: Bitmap,
    val confidence: Float,
)

/**
 * Locates marine organisms in an image and returns one or more [OrganismCrop]s.
 *
 * ## Strategy
 *
 * **Primary (organism mode):** asks the VLM via [VlmImageEngine] to emit a
 * `box_2d` JSON array for every living marine organism visible in the frame,
 * using a system prompt that is the inverse of the debris detector — organisms
 * are the target; man-made debris is explicitly excluded.
 *
 * **Fallback:** when the engine is not ready, returns no boxes, or the parse
 * fails, falls back to a single center-square crop of the full frame with
 * confidence 1.0. This matches the v1 behaviour described in the plan (§3)
 * and guarantees a non-empty result.
 *
 * The [ORGANISM_SYSTEM_MESSAGE] and [ORGANISM_PROMPT] deliberately mirror the
 * structure of `Gemma4VisionDetector.SYSTEM_MESSAGE` / `DETECTION_PROMPT` but
 * with inverted semantics — [Gemma4VisionDetector] is NEVER modified by this
 * class.
 *
 * @param engine  VLM engine adapter. When null the locator always falls back
 *                to the center-square crop (useful for Phase-0 benchmarking).
 */
class OrganismLocator(
    private val engine: VlmImageEngine? = null,
) : CropProvider {
    companion object {
        private const val TAG = "OrganismLocator"

        /**
         * System prompt for organism-mode detection. Inverts the debris
         * detector rules: organisms are targets, debris is excluded.
         */
        internal const val ORGANISM_SYSTEM_MESSAGE =
            "Marine organism detector for underwater photos. Detect ONLY living organisms: " +
            "fish, sharks, rays, marine mammals, sea turtles, octopuses, squids, crabs, " +
            "shrimps, sea urchins, starfish, corals, jellyfish, eels, seagrass, and all " +
            "other marine fauna and flora. NEVER label man-made objects, debris, trash, " +
            "fishing gear, plastic, metal, or any artificial item. " +
            "Output ONLY valid JSON arrays, no markdown or prose."

        /**
         * Detection prompt requesting `box_2d` JSON (1000-grid, same schema
         * as Gemma4VisionDetector). Label field is kept for debugging but is
         * not used in the OrganismCrop — only the box coordinates matter here.
         */
        internal const val ORGANISM_PROMPT =
            "Detect every marine organism visible (fish, coral, invertebrates, marine mammals, " +
            "sea plants, etc.). Output ONLY a JSON array of:\n" +
            "{\"box_2d\":[y_min,x_min,y_max,x_max],\"label\":\"<common_name>\"}\n" +
            "Coords are integers 0-1000. If no organism is visible: []."

        /** Minimum box area fraction of total image to keep a crop (noise filter). */
        private const val MIN_AREA_FRACTION = 0.01f

        /** Padding applied around each detected box, as a fraction of image dimension. */
        private const val BOX_PADDING_FRACTION = 0.05f
    }

    /**
     * Locate organisms in [bitmap] and return a list of [OrganismCrop]s.
     *
     * Never returns an empty list: falls back to a single center-square crop.
     *
     * @param bitmap Full input frame (any size).
     */
    override suspend fun locate(bitmap: Bitmap): List<OrganismCrop> {
        val vlm = engine
        if (vlm == null || !vlm.isReady) {
            Log.d(TAG, "Engine not ready — using fallback center crop")
            return listOf(centerSquareCrop(bitmap))
        }

        val raw = try {
            vlm.generateWithImage(
                bitmap        = bitmap,
                prompt        = ORGANISM_PROMPT,
                systemMessage = ORGANISM_SYSTEM_MESSAGE,
                maxTokens     = 256,
                temperature   = 0.2,
            )
        } catch (e: Exception) {
            Log.w(TAG, "VLM organism detection failed: ${e.message}")
            return listOf(centerSquareCrop(bitmap))
        }

        val crops = parseOrganismBoxes(raw, bitmap)
        if (crops.isEmpty()) {
            Log.d(TAG, "No organism boxes parsed — fallback to center crop")
            return listOf(centerSquareCrop(bitmap))
        }
        Log.d(TAG, "Organism-mode: found ${crops.size} crop(s)")
        return crops
    }

    // -----------------------------------------------------------------------
    // Parsing — adapted from Gemma4VisionDetector.parseDetections, sans
    // LABEL_TO_TYPE debris mapping.
    // -----------------------------------------------------------------------

    /**
     * Parses the VLM's JSON response into a list of [OrganismCrop]s.
     *
     * Robust to markdown code fences, leading prose, partial arrays. On any
     * parse error returns an empty list (caller falls back to center crop).
     *
     * `box_2d` layout: [y_min, x_min, y_max, x_max] on 1000-grid.
     */
    internal fun parseOrganismBoxes(raw: String, bitmap: Bitmap): List<OrganismCrop> {
        val json = extractJsonArray(raw) ?: return emptyList()
        return try {
            JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
                runCatching { parseSingleBox(element.asJsonObject, bitmap) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse organism JSON: ${e.message}")
            emptyList()
        }
    }

    private fun parseSingleBox(
        obj: com.google.gson.JsonObject,
        bitmap: Bitmap,
    ): OrganismCrop? {
        val box = obj.getAsJsonArray("box_2d") ?: return null
        if (box.size() < 4) return null

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // Convert 0-1000 grid → pixel coordinates
        val yMin = (clamp(box[0].asFloat) / 1000f * h).toInt()
        val xMin = (clamp(box[1].asFloat) / 1000f * w).toInt()
        val yMax = (clamp(box[2].asFloat) / 1000f * h).toInt()
        val xMax = (clamp(box[3].asFloat) / 1000f * w).toInt()

        if (yMax <= yMin || xMax <= xMin) return null

        // Area noise filter
        val boxArea = (xMax - xMin).toFloat() * (yMax - yMin).toFloat()
        if (boxArea / (w * h) < MIN_AREA_FRACTION) return null

        // Padding with clamp to image bounds
        val padX = (w * BOX_PADDING_FRACTION).toInt()
        val padY = (h * BOX_PADDING_FRACTION).toInt()
        val x0 = (xMin - padX).coerceAtLeast(0)
        val y0 = (yMin - padY).coerceAtLeast(0)
        val x1 = (xMax + padX).coerceAtMost(bitmap.width)
        val y1 = (yMax + padY).coerceAtMost(bitmap.height)

        val cropBitmap = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
        val bbox = BoundingBox(
            x      = x0.toFloat(),
            y      = y0.toFloat(),
            width  = (x1 - x0).toFloat(),
            height = (y1 - y0).toFloat(),
        )
        return OrganismCrop(bbox = bbox, crop = cropBitmap, confidence = 0.85f)
    }

    private fun clamp(v: Float): Float = v.coerceIn(0f, 1000f)

    /** Extracts the first JSON array from a potentially noisy response. */
    private fun extractJsonArray(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return trimmed.substringBeforeLast("]") + "]"
        val fencePattern = Regex("```(?:json)?\\s*\\n?(\\[.*?])\\s*```", RegexOption.DOT_MATCHES_ALL)
        fencePattern.find(trimmed)?.let { return it.groupValues[1] }
        val start = trimmed.indexOf('[')
        val end   = trimmed.lastIndexOf(']')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else null
    }

    // -----------------------------------------------------------------------
    // Fallback — center-square crop
    // -----------------------------------------------------------------------

    private fun centerSquareCrop(bitmap: Bitmap): OrganismCrop {
        val size = minOf(bitmap.width, bitmap.height)
        val xOff = (bitmap.width  - size) / 2
        val yOff = (bitmap.height - size) / 2
        val crop = Bitmap.createBitmap(bitmap, xOff, yOff, size, size)
        val bbox = BoundingBox(
            x      = xOff.toFloat(),
            y      = yOff.toFloat(),
            width  = size.toFloat(),
            height = size.toFloat(),
        )
        return OrganismCrop(bbox = bbox, crop = crop, confidence = 1.0f)
    }
}
