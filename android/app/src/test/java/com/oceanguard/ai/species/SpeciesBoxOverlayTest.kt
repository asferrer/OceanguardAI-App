package com.oceanguard.ai.species

import com.oceanguard.ai.ui.components.bboxToNormBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [bboxToNormBox] — the pure pixel-bbox → normalised [0,1]
 * conversion that lets the BioDex overlay scale an organism box to whatever
 * size the analysed image is laid out at. Android-free (no Bitmap/Canvas), so
 * it runs on the plain JVM harness, mirroring [OrganismLocatorTest].
 */
class SpeciesBoxOverlayTest {

    private val delta = 1e-4f

    @Test
    fun `centered box normalises to expected fractions`() {
        // Box at x=250,y=200 sized 500x400 inside a 1000x800 image →
        // left=0.25, top=0.25, right=0.75, bottom=0.75.
        val box = bboxToNormBox(
            x = 250f, y = 200f, width = 500f, height = 400f,
            imgWidth = 1000, imgHeight = 800,
            label = "Clownfish", confidence = 0.9f,
        )
        assertNotNull(box)
        box!!
        assertEquals(0.25f, box.left, delta)
        assertEquals(0.25f, box.top, delta)
        assertEquals(0.75f, box.right, delta)
        assertEquals(0.75f, box.bottom, delta)
        assertEquals("Clownfish", box.label)
        assertEquals(0.9f, box.confidence, delta)
    }

    @Test
    fun `full-frame box normalises to the unit square`() {
        val box = bboxToNormBox(
            x = 0f, y = 0f, width = 640f, height = 480f,
            imgWidth = 640, imgHeight = 480,
            label = "x", confidence = 1f,
        )
        assertNotNull(box)
        box!!
        assertEquals(0f, box.left, delta)
        assertEquals(0f, box.top, delta)
        assertEquals(1f, box.right, delta)
        assertEquals(1f, box.bottom, delta)
    }

    @Test
    fun `box overshooting image bounds is clamped to 0,1`() {
        // Padded crop can extend a few px past the frame; must clamp, not exceed 1.
        val box = bboxToNormBox(
            x = -10f, y = -20f, width = 1100f, height = 900f,
            imgWidth = 1000, imgHeight = 800,
            label = "x", confidence = 0.5f,
        )
        assertNotNull(box)
        box!!
        assertEquals(0f, box.left, delta)
        assertEquals(0f, box.top, delta)
        assertEquals(1f, box.right, delta)
        assertEquals(1f, box.bottom, delta)
    }

    @Test
    fun `non-square image keeps independent x and y scaling`() {
        // 1920x1080 frame: a 480x540 box at (960,0).
        val box = bboxToNormBox(
            x = 960f, y = 0f, width = 480f, height = 540f,
            imgWidth = 1920, imgHeight = 1080,
            label = "x", confidence = 0f,
        )
        assertNotNull(box)
        box!!
        assertEquals(0.5f, box.left, delta)
        assertEquals(0f, box.top, delta)
        assertEquals(0.75f, box.right, delta)
        assertEquals(0.5f, box.bottom, delta)
    }

    @Test
    fun `non-positive image dimensions return null`() {
        assertNull(
            bboxToNormBox(0f, 0f, 10f, 10f, 0, 100, "x", 1f),
        )
        assertNull(
            bboxToNormBox(0f, 0f, 10f, 10f, 100, 0, "x", 1f),
        )
    }

    @Test
    fun `degenerate zero-area box returns null`() {
        assertNull(
            bboxToNormBox(10f, 10f, 0f, 50f, 100, 100, "x", 1f),
        )
        assertNull(
            bboxToNormBox(10f, 10f, 50f, 0f, 100, 100, "x", 1f),
        )
    }
}
