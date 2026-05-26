package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import com.oceanguard.ai.data.BoundingBox

/**
 * An organism bounding box + crop extracted from a full frame.
 *
 * @param bbox        Bounding box in original image coordinates (pixels).
 * @param crop        Cropped sub-bitmap ready for [SpeciesEmbedder.embed].
 * @param confidence  Localiser confidence for this detection.
 */
data class OrganismCrop(
    val bbox: BoundingBox,
    val crop: Bitmap,
    val confidence: Float,
)

/**
 * Locates marine organisms in an image and returns one or more [OrganismCrop]s.
 *
 * ## v1 strategy (pragmatic default): single-subject, full-frame
 * For the first phase, the default behaviour is to return a single
 * [OrganismCrop] that is a center-square crop of the full frame. This costs
 * zero localisation overhead and works well when the subject fills most of
 * the frame (typical for intentional wildlife photography / dive video stills).
 *
 * ## v2 strategy (planned): Gemma "organism mode"
 * A second system prompt that allows `box_2d` annotations for fauna,
 * re-using `parseDetections` from `Gemma4VisionDetector` but without the
 * `LABEL_TO_TYPE` debris mapping. The VLM engine and parsing code must NOT
 * be modified in this file — they are owned by another agent.
 *
 * TODO(Phase 2): wire [com.oceanguard.ai.inference.LiteRTTextEngine.generateWithImage]
 *   with an "organism mode" system prompt and parse `box_2d` JSON into crops.
 *   See plan section §3 for the prompt and parsing logic.
 *
 * TODO(Phase 4): add a lightweight class-agnostic TFLite "organism" detector
 *   cloning [com.oceanguard.ai.inference.RTDETRInference] with 1-class output
 *   for quasi-live detection.
 */
class OrganismLocator {

    /**
     * Locate organisms in [bitmap] and return a list of [OrganismCrop]s.
     *
     * Current implementation (v1): returns a single full-frame center-square
     * crop with confidence 1.0.
     *
     * @param bitmap  Full input frame.
     * @return        List of organism crops. Never empty (falls back to full frame).
     */
    fun locate(bitmap: Bitmap): List<OrganismCrop> {
        val size = minOf(bitmap.width, bitmap.height)
        val xOff = (bitmap.width - size) / 2
        val yOff = (bitmap.height - size) / 2
        val crop = Bitmap.createBitmap(bitmap, xOff, yOff, size, size)
        val bbox = BoundingBox(
            x      = xOff.toFloat(),
            y      = yOff.toFloat(),
            width  = size.toFloat(),
            height = size.toFloat(),
        )
        return listOf(OrganismCrop(bbox = bbox, crop = crop, confidence = 1.0f))
    }
}
