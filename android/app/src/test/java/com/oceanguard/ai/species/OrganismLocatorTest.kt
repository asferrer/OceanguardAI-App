package com.oceanguard.ai.species

import com.oceanguard.ai.inference.species.OrganismLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OrganismLocator.boxToPixelRect] — the pure crop-from-bbox
 * arithmetic that converts a VLM `box_2d` (0-1000 grid) into a padded, clamped
 * pixel rectangle. Runs on plain JVM (no [android.graphics.Bitmap]).
 */
class OrganismLocatorTest {

    private val locator = OrganismLocator()  // engine = null

    @Test
    fun `centered box maps to expected padded pixel rect`() {
        // Box covering the central 25%..75% of a 1000x800 image.
        val rect = locator.boxToPixelRect(
            yMin1k = 250f, xMin1k = 250f, yMax1k = 750f, xMax1k = 750f,
            imgW = 1000, imgH = 800,
        )
        assertNotNull(rect)
        rect!!
        // Core box: x 250..750, y 200..600. Padding 5% of each dim: padX=50, padY=40.
        assertEquals(200, rect.x)        // 250 - 50
        assertEquals(160, rect.y)        // 200 - 40
        assertEquals(600, rect.width)    // (750+50) - (250-50)
        assertEquals(480, rect.height)   // (600+40) - (200-40)
    }

    @Test
    fun `padding is clamped to image bounds`() {
        // Full-frame box → padding cannot push the rect outside the image.
        val rect = locator.boxToPixelRect(
            yMin1k = 0f, xMin1k = 0f, yMax1k = 1000f, xMax1k = 1000f,
            imgW = 640, imgH = 480,
        )
        assertNotNull(rect)
        rect!!
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
        assertEquals(640, rect.width)
        assertEquals(480, rect.height)
    }

    @Test
    fun `out-of-range grid coords are clamped to 0-1000`() {
        // Negative min and >1000 max must clamp, not overflow.
        val rect = locator.boxToPixelRect(
            yMin1k = -200f, xMin1k = -200f, yMax1k = 1500f, xMax1k = 1500f,
            imgW = 500, imgH = 500,
        )
        assertNotNull(rect)
        rect!!
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
        assertTrue("width within image", rect.width <= 500)
        assertTrue("height within image", rect.height <= 500)
    }

    @Test
    fun `degenerate box with zero area returns null`() {
        val rect = locator.boxToPixelRect(
            yMin1k = 500f, xMin1k = 500f, yMax1k = 500f, xMax1k = 500f,
            imgW = 1000, imgH = 1000,
        )
        assertNull(rect)
    }

    @Test
    fun `inverted box (max less than min) returns null`() {
        val rect = locator.boxToPixelRect(
            yMin1k = 800f, xMin1k = 800f, yMax1k = 200f, xMax1k = 200f,
            imgW = 1000, imgH = 1000,
        )
        assertNull(rect)
    }

    @Test
    fun `tiny box below area noise threshold returns null`() {
        // 0.5% of the frame < MIN_AREA_FRACTION (1%).
        val rect = locator.boxToPixelRect(
            yMin1k = 0f, xMin1k = 0f, yMax1k = 70f, xMax1k = 70f,
            imgW = 1000, imgH = 1000,
        )
        // 70px * 70px / 1_000_000 = 0.0049 < 0.01 → filtered out.
        assertNull(rect)
    }

    @Test
    fun `zero-size image returns null`() {
        val rect = locator.boxToPixelRect(
            yMin1k = 100f, xMin1k = 100f, yMax1k = 900f, xMax1k = 900f,
            imgW = 0, imgH = 0,
        )
        assertNull(rect)
    }
}
