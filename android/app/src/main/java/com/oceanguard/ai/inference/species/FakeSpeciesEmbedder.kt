package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Deterministic test-double for [SpeciesEmbedder].
 *
 * Produces a reproducible L2-normalised embedding based on the average pixel
 * value of the bitmap, modulated by [seed]. This is NOT a real visual encoder
 * — it is only suitable for unit/integration tests that need a concrete
 * [SpeciesEmbedder] without loading an ONNX model.
 *
 * Determinism guarantee: two calls with bitmaps of the same dimensions and the
 * same [seed] will return identical vectors, regardless of pixel content.
 * When [usePixelContent] is true the output also reflects the bitmap average,
 * useful for tests that want distinct embeddings per image.
 *
 * @param seed             Scalar seed that shifts every embedding dimension.
 * @param usePixelContent  When true, the embedding reflects avg(pixel) / 255.
 *                         When false, only [seed] determines the output.
 */
class FakeSpeciesEmbedder(
    private val seed: Float = 0f,
    private val usePixelContent: Boolean = false,
) : SpeciesEmbedder {

    override val isReady: Boolean = true

    override suspend fun embed(bitmap: Bitmap): FloatArray {
        val avgPixel = if (usePixelContent) computeAvgPixel(bitmap) else 0f
        val raw = FloatArray(SpeciesEmbedder.EMBEDDING_DIM) { i ->
            seed + avgPixel + i * 0.001f
        }
        return l2Normalize(raw)
    }

    override fun close() { /* no-op for test double */ }

    // -----------------------------------------------------------------------

    private fun computeAvgPixel(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0L
        for (p in pixels) {
            sum += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
        }
        return sum.toFloat() / (pixels.size * 3 * 255f)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
        return v
    }
}
