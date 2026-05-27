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
 * Integer pixel rectangle (top-left origin) within an image. Output of
 * [OrganismLocator.boxToPixelRect]; kept Android-free so the crop math can be
 * unit-tested on plain JVM.
 */
data class PixelRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
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
         * detector rules: organisms are targets, debris is excluded. Kept short
         * on purpose — long closed-class lists inflate prefill latency and
         * degrade Gemma 4 E2B box_2d output (memory: feedback_detector_prompt.md).
         * The full taxon enumeration that used to live here did not improve
         * recall but added ~70 prompt tokens to every locate() call.
         */
        internal const val ORGANISM_SYSTEM_MESSAGE =
            "Marine organism detector for underwater photos. Detect ONLY living organisms " +
            "(fish, invertebrates, marine mammals, corals, plants). NEVER label man-made " +
            "objects or debris. Output ONLY valid JSON arrays, no markdown or prose."

        /**
         * Detection prompt requesting `box_2d` JSON (1000-grid, same schema
         * as Gemma4VisionDetector). Label field is kept for debugging but is
         * not used in the OrganismCrop — only the box coordinates matter here.
         */
        internal const val ORGANISM_PROMPT =
            "Detect every marine organism. Output ONLY a JSON array of:\n" +
            "{\"box_2d\":[y_min,x_min,y_max,x_max],\"label\":\"<name>\"}\n" +
            "Coords are integers 0-1000. If none: []."

        /**
         * Advisory output cap for the box_2d JSON. ~25 tokens per box object, so
         * 192 comfortably fits ~6 organisms — more than any single underwater
         * frame realistically contains. The real bound is [jsonArrayClosed]
         * early-stop; this cap is the fallback when the JSON never closes.
         */
        private const val LOCATOR_MAX_TOKENS = 192

        /**
         * Greedy decoding for the structured box_2d output: temperature 0 + topK 1
         * is fully deterministic and the fastest sampler path on Exynos 2200. The
         * boxes are coordinates, not prose — no entropy is wanted.
         */
        private const val LOCATOR_TEMPERATURE = 0.0
        private const val LOCATOR_TOP_K = 1

        /**
         * Long-edge the frame is downscaled to before the VLM call. 448 keeps
         * LiteRT-LM Gemma 4 on the vision_140 path (4 tiles) instead of vision_280
         * (16 tiles), which drops the encoder pass from ~12 s to ~3 s on Exynos
         * 2200 with no recall loss for whole-organism localisation. The crop is
         * still taken from the ORIGINAL full-resolution bitmap (box_2d coords are
         * a resolution-independent 0-1000 grid), so subject detail is preserved
         * for the downstream ONNX embedder.
         */
        internal const val VLM_INPUT_LONG_EDGE = 448

        /** Minimum box area fraction of total image to keep a crop (noise filter). */
        private const val MIN_AREA_FRACTION = 0.01f

        /** Padding applied around each detected box, as a fraction of image dimension. */
        private const val BOX_PADDING_FRACTION = 0.05f

        /**
         * Early-stop predicate: true once the accumulated output contains a
         * balanced, closed JSON array (every '[' matched by a ']' and at least
         * one top-level array closed). Lets [locate] abort the decode the moment
         * the box list is complete instead of waiting for the model's EOS.
         */
        internal fun jsonArrayClosed(text: String): Boolean {
            var depth = 0
            var sawOpen = false
            for (c in text) {
                when (c) {
                    '[' -> { depth++; sawOpen = true }
                    ']' -> {
                        depth--
                        if (sawOpen && depth <= 0) return true
                    }
                }
            }
            return false
        }
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

        // Downscale ONLY the frame fed to the VLM (vision_140 path). box_2d
        // coordinates are a resolution-independent 0-1000 grid, so the crop is
        // still taken from the original full-res `bitmap` below — no detail lost.
        val vlmFrame = downscaleForVlm(bitmap)
        val raw = try {
            vlm.generateWithImage(
                bitmap        = vlmFrame,
                prompt        = ORGANISM_PROMPT,
                systemMessage = ORGANISM_SYSTEM_MESSAGE,
                maxTokens     = LOCATOR_MAX_TOKENS,
                temperature   = LOCATOR_TEMPERATURE,
                topK          = LOCATOR_TOP_K,
                stopWhen      = ::jsonArrayClosed,
            )
        } catch (e: Exception) {
            Log.w(TAG, "VLM organism detection failed: ${e.message}")
            return listOf(centerSquareCrop(bitmap))
        } finally {
            if (vlmFrame !== bitmap) vlmFrame.recycle()
        }

        val crops = parseOrganismBoxes(raw, bitmap)
        if (crops.isEmpty()) {
            Log.d(TAG, "No organism boxes parsed — fallback to center crop")
            return listOf(centerSquareCrop(bitmap))
        }
        Log.d(TAG, "Organism-mode: found ${crops.size} crop(s)")
        return crops
    }

    /**
     * Returns a copy of [bitmap] scaled so its long edge is [VLM_INPUT_LONG_EDGE],
     * preserving aspect ratio. Returns the input unchanged when it is already at
     * or below that size (no upscaling). Pure presentation for the vision encoder.
     */
    internal fun downscaleForVlm(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= VLM_INPUT_LONG_EDGE) return bitmap
        val scale = VLM_INPUT_LONG_EDGE.toFloat() / longEdge
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
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

        val rect = boxToPixelRect(
            yMin1k = box[0].asFloat,
            xMin1k = box[1].asFloat,
            yMax1k = box[2].asFloat,
            xMax1k = box[3].asFloat,
            imgW   = bitmap.width,
            imgH   = bitmap.height,
        ) ?: return null

        val cropBitmap = Bitmap.createBitmap(bitmap, rect.x, rect.y, rect.width, rect.height)
        val bbox = BoundingBox(
            x      = rect.x.toFloat(),
            y      = rect.y.toFloat(),
            width  = rect.width.toFloat(),
            height = rect.height.toFloat(),
        )
        return OrganismCrop(bbox = bbox, crop = cropBitmap, confidence = 0.85f)
    }

    /**
     * Pure conversion of a `box_2d` (0-1000 grid, [y_min,x_min,y_max,x_max]) into
     * a padded, clamped pixel rectangle within an [imgW]×[imgH] image.
     *
     * Returns null when the box is degenerate (zero/negative area) or below the
     * [MIN_AREA_FRACTION] noise threshold. Extracted from [parseSingleBox] so the
     * crop-from-bbox arithmetic is unit-testable without [Bitmap.createBitmap].
     */
    internal fun boxToPixelRect(
        yMin1k: Float,
        xMin1k: Float,
        yMax1k: Float,
        xMax1k: Float,
        imgW: Int,
        imgH: Int,
    ): PixelRect? {
        if (imgW <= 0 || imgH <= 0) return null
        val w = imgW.toFloat()
        val h = imgH.toFloat()

        val yMin = (clamp(yMin1k) / 1000f * h).toInt()
        val xMin = (clamp(xMin1k) / 1000f * w).toInt()
        val yMax = (clamp(yMax1k) / 1000f * h).toInt()
        val xMax = (clamp(xMax1k) / 1000f * w).toInt()

        if (yMax <= yMin || xMax <= xMin) return null

        val boxArea = (xMax - xMin).toFloat() * (yMax - yMin).toFloat()
        if (boxArea / (w * h) < MIN_AREA_FRACTION) return null

        val padX = (w * BOX_PADDING_FRACTION).toInt()
        val padY = (h * BOX_PADDING_FRACTION).toInt()
        val x0 = (xMin - padX).coerceAtLeast(0)
        val y0 = (yMin - padY).coerceAtLeast(0)
        val x1 = (xMax + padX).coerceAtMost(imgW)
        val y1 = (yMax + padY).coerceAtMost(imgH)

        return PixelRect(x = x0, y = y0, width = x1 - x0, height = y1 - y0)
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
