package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import com.oceanguard.ai.data.BoundingBox

/**
 * Adaptive crop preparation for the BioDex visual RAG.
 *
 * Trade-off addressed: the VLM bounding box should be as tight to the organism
 * as possible (for the on-screen overlay and the embedder signal/background
 * ratio), but the encoder needs a 224×224 input. Naively cropping the tight box
 * and resizing means upscaling whenever the box is smaller than 224 — that
 * upscale invents detail and degrades retrieval.
 *
 * Strategy:
 *  - If the bbox short side ≥ [targetSize]: pick the square circumscribing the
 *    bbox (using the **long** side, not the short — keeps both flanks of an
 *    elongated fish), add a [contextRatio] margin, clamp to the frame, downscale
 *    bilinearly to [targetSize]. No upscale, only true detail.
 *  - If the bbox short side < [targetSize]: expand the square around the bbox
 *    centre to at least [targetSize] pixels of **natural** frame, plus margin,
 *    clamp. The encoder still sees a real-pixel crop, not interpolated mush.
 *  - When the frame itself is smaller than [targetSize] in one or both axes,
 *    fall through to a non-square crop that fills as much as possible and
 *    bilinearly resize — last resort, can't avoid some upscale.
 *
 * Pure math + a single Bitmap pixel op (createBitmap + createScaledBitmap).
 * The geometry is computed by [computeCropRect] which is JVM-pure for tests.
 */
object RagCropPreparer {

    /** Default ONNX input edge for CLIP / BioCLIP visual towers. */
    const val DEFAULT_TARGET_SIZE = 224

    /**
     * Default extra context as a fraction of the square side, e.g. 0.15 ⇒ 15 %.
     * CLIP-family encoders were pretrained on crops with mild surrounding context
     * (typical [scale 0.5..1.0] augmentation), so leaving zero margin is colder
     * than the training distribution; ~15 % moves the input closer to it.
     */
    const val DEFAULT_CONTEXT_RATIO = 0.15f

    /**
     * Produce a [targetSize]×[targetSize] [Bitmap] ready for the encoder.
     *
     * @param fullBitmap  Original full-resolution frame the bbox was detected on.
     * @param bbox        Tight bounding box in [fullBitmap] pixel coordinates.
     * @param targetSize  ONNX input edge (default 224).
     * @param contextRatio Fraction of the square side added as natural margin.
     */
    fun cropForRag(
        fullBitmap: Bitmap,
        bbox: BoundingBox,
        targetSize: Int = DEFAULT_TARGET_SIZE,
        contextRatio: Float = DEFAULT_CONTEXT_RATIO,
    ): Bitmap {
        val rect = computeCropRect(
            imgW = fullBitmap.width,
            imgH = fullBitmap.height,
            bboxX = bbox.x,
            bboxY = bbox.y,
            bboxW = bbox.width,
            bboxH = bbox.height,
            targetSize = targetSize,
            contextRatio = contextRatio,
        )
        val cropped = Bitmap.createBitmap(
            fullBitmap, rect.x, rect.y, rect.width, rect.height,
        )
        if (cropped.width == targetSize && cropped.height == targetSize) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /**
     * Pure geometry — used by [cropForRag] and exercised by JVM unit tests
     * without [Bitmap]. Always returns a rectangle fully inside the frame.
     */
    internal fun computeCropRect(
        imgW: Int,
        imgH: Int,
        bboxX: Float,
        bboxY: Float,
        bboxW: Float,
        bboxH: Float,
        targetSize: Int,
        contextRatio: Float,
    ): PixelRect {
        require(imgW > 0 && imgH > 0) { "image dims must be positive" }
        require(targetSize > 0) { "targetSize must be positive" }

        // 1. Square that circumscribes the bbox via the LONG side (keeps both
        //    flanks of an elongated subject). Centred on the bbox centroid.
        val cx = bboxX + bboxW / 2f
        val cy = bboxY + bboxH / 2f
        val longSide = maxOf(bboxW, bboxH).coerceAtLeast(1f)

        // 2. Guarantee at least [targetSize] of natural pixels — avoid upscale
        //    by widening the crop into the frame whenever the bbox is small.
        var sq = maxOf(longSide, targetSize.toFloat())

        // 3. Add context margin (multiplicative on the side).
        sq *= (1f + contextRatio.coerceAtLeast(0f))

        // 4. Cap by the frame: cannot pick a square larger than the shorter
        //    image edge. When the frame is smaller than [targetSize] in some
        //    axis the resulting crop will be non-square and resized later;
        //    that is the unavoidable upscale path.
        val maxSquare = minOf(imgW, imgH).toFloat()
        sq = sq.coerceAtMost(maxSquare)
        val side = sq.toInt().coerceAtLeast(1)

        // 5. Translate so the square stays fully inside the frame, biased to
        //    keep the bbox centre when possible.
        val x = (cx - side / 2f).toInt().coerceIn(0, imgW - side)
        val y = (cy - side / 2f).toInt().coerceIn(0, imgH - side)

        // 6. Special case: frame smaller than targetSize in one axis → emit a
        //    rectangle hugging that axis. The caller will bilinearly resize
        //    (upscale) to the square target; nothing else we can do.
        if (imgW < targetSize || imgH < targetSize) {
            return PixelRect(0, 0, imgW, imgH)
        }

        return PixelRect(x = x, y = y, width = side, height = side)
    }
}
