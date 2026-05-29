package com.oceanguard.ai.species

import com.oceanguard.ai.inference.species.RagCropPreparer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RagCropPreparer.computeCropRect] — the adaptive crop
 * geometry used by the BioDex visual RAG. No Android bitmap involved.
 *
 * Behavioural invariants (matching the README of [RagCropPreparer]):
 *  - When the bbox short side ≥ targetSize: the crop is a square inscribing the
 *    bbox's long side + context margin. Downscale only — no upscale.
 *  - When the bbox short side < targetSize: the crop widens around the bbox
 *    centre to at least targetSize natural pixels + margin, so the encoder
 *    receives real detail, not interpolated mush.
 *  - The returned rectangle is always fully inside the frame.
 *  - Elongated bboxes use the LONG side as the square, never losing flanks.
 *  - When the frame itself is smaller than targetSize in some axis, fall
 *    through to a non-square that hugs the frame (resize will upscale).
 */
class RagCropPreparerTest {

    private val targetSize = 224
    private val ctx = 0.15f

    @Test
    fun `large square bbox returns square crop with context margin`() {
        // 500x500 bbox in 4000x3000 frame, centred. Square side = 500, +15% = 575.
        val rect = RagCropPreparer.computeCropRect(
            imgW = 4000, imgH = 3000,
            bboxX = 1750f, bboxY = 1250f, bboxW = 500f, bboxH = 500f,
            targetSize = targetSize, contextRatio = ctx,
        )
        assertEquals("square crop", rect.width, rect.height)
        assertEquals(575, rect.width)
        // Centred on bbox centroid (2000, 1500) → x = 2000 - 287, y = 1500 - 287.
        assertEquals(1712, rect.x)
        assertEquals(1212, rect.y)
        assertTrue("inside frame", rect.x + rect.width <= 4000 && rect.y + rect.height <= 3000)
    }

    @Test
    fun `small bbox expands to natural targetSize + context`() {
        // 80x80 bbox in 4000x3000 frame. We must NOT upscale: side ≥ 224 * 1.15 = 257.
        val rect = RagCropPreparer.computeCropRect(
            imgW = 4000, imgH = 3000,
            bboxX = 1960f, bboxY = 1460f, bboxW = 80f, bboxH = 80f,
            targetSize = targetSize, contextRatio = ctx,
        )
        assertEquals("square crop", rect.width, rect.height)
        // long side 80, max(80, 224) * 1.15 = 257 (floor).
        assertEquals(257, rect.width)
        assertTrue("inside frame", rect.x + rect.width <= 4000 && rect.y + rect.height <= 3000)
        // Bbox centroid (2000, 1500); square expanded around it.
        assertEquals(1871, rect.x)
        assertEquals(1371, rect.y)
    }

    @Test
    fun `elongated bbox uses long side so flanks are preserved`() {
        // Wide fish: 800x200 bbox. The square must use 800 (the long side) to
        // keep both head and tail. With 15 % margin → 920.
        val rect = RagCropPreparer.computeCropRect(
            imgW = 4000, imgH = 3000,
            bboxX = 1600f, bboxY = 1400f, bboxW = 800f, bboxH = 200f,
            targetSize = targetSize, contextRatio = ctx,
        )
        assertEquals(rect.width, rect.height)
        assertEquals(920, rect.width)
        // Centred on bbox centroid (2000, 1500).
        assertEquals(1540, rect.x)
        assertEquals(1040, rect.y)
    }

    @Test
    fun `bbox near edge is clamped without losing the square shape`() {
        // Bbox in top-left corner: 300x300 starting at (10, 10). Square 345 with
        // margin can't centre on (160, 160) because it would go negative; should
        // shift to (0, 0).
        val rect = RagCropPreparer.computeCropRect(
            imgW = 4000, imgH = 3000,
            bboxX = 10f, bboxY = 10f, bboxW = 300f, bboxH = 300f,
            targetSize = targetSize, contextRatio = ctx,
        )
        assertEquals(rect.width, rect.height)
        assertEquals(345, rect.width)   // 300 * 1.15
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
        assertTrue("inside frame", rect.x + rect.width <= 4000 && rect.y + rect.height <= 3000)
    }

    @Test
    fun `bbox bigger than the shorter image edge is capped to the frame`() {
        // 3500x2900 bbox in a 4000x3000 frame: square wants 4025 (3500*1.15)
        // but cannot exceed the shorter image edge (3000).
        val rect = RagCropPreparer.computeCropRect(
            imgW = 4000, imgH = 3000,
            bboxX = 250f, bboxY = 50f, bboxW = 3500f, bboxH = 2900f,
            targetSize = targetSize, contextRatio = ctx,
        )
        assertEquals(rect.width, rect.height)
        assertEquals(3000, rect.width)
        assertTrue("inside frame", rect.x + rect.width <= 4000 && rect.y + rect.height <= 3000)
    }

    @Test
    fun `frame smaller than targetSize falls through to full frame`() {
        // 150x100 tiny frame, 224 target. Cannot get 224 natural pixels —
        // emit the whole frame; downstream bilinear resize will upscale.
        val rect = RagCropPreparer.computeCropRect(
            imgW = 150, imgH = 100,
            bboxX = 30f, bboxY = 20f, bboxW = 60f, bboxH = 50f,
            targetSize = targetSize, contextRatio = ctx,
        )
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
        assertEquals(150, rect.width)
        assertEquals(100, rect.height)
    }

    @Test
    fun `zero context ratio returns the long-side square without padding`() {
        val rect = RagCropPreparer.computeCropRect(
            imgW = 4000, imgH = 3000,
            bboxX = 1600f, bboxY = 1400f, bboxW = 800f, bboxH = 200f,
            targetSize = targetSize, contextRatio = 0f,
        )
        assertEquals(800, rect.width)
        assertEquals(800, rect.height)
    }
}
