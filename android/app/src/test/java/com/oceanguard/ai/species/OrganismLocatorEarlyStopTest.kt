package com.oceanguard.ai.species

import com.oceanguard.ai.inference.species.OrganismLocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OrganismLocator.jsonArrayClosed] — the early-stop predicate
 * that lets the locator abort the VLM decode the moment the `box_2d` JSON array
 * is balanced and closed, instead of waiting for the model's EOS. Pure logic,
 * runs on plain JVM.
 */
class OrganismLocatorEarlyStopTest {

    @Test
    fun `empty array closes immediately`() {
        assertTrue(OrganismLocator.jsonArrayClosed("[]"))
    }

    @Test
    fun `single complete object array is detected as closed`() {
        val out = """[{"box_2d":[10,10,900,900],"label":"fish"}]"""
        assertTrue(OrganismLocator.jsonArrayClosed(out))
    }

    @Test
    fun `nested box array does not falsely close on the inner bracket`() {
        // The inner box_2d `]` must NOT trigger the stop — only the top-level
        // array close at depth 0 counts.
        val partial = """[{"box_2d":[10,10,900,900],"label":"fish"}"""
        assertFalse("inner ] should not close the top-level array", OrganismLocator.jsonArrayClosed(partial))
    }

    @Test
    fun `open array without close is not yet closed`() {
        assertFalse(OrganismLocator.jsonArrayClosed("[{\"box_2d\":[1,2,3"))
    }

    @Test
    fun `no array at all is never closed`() {
        assertFalse(OrganismLocator.jsonArrayClosed("thinking about the image"))
        assertFalse(OrganismLocator.jsonArrayClosed(""))
    }

    @Test
    fun `trailing prose after a closed array still counts as closed`() {
        val out = """[{"box_2d":[0,0,500,500],"label":"crab"}]  done."""
        assertTrue(OrganismLocator.jsonArrayClosed(out))
    }

    @Test
    fun `multiple objects in one array close at the final bracket`() {
        val out = """[{"box_2d":[0,0,100,100],"label":"a"},{"box_2d":[200,200,400,400],"label":"b"}]"""
        assertTrue(OrganismLocator.jsonArrayClosed(out))
        // Mid-stream (before the closing ]) must be open.
        val mid = """[{"box_2d":[0,0,100,100],"label":"a"},{"box_2d":[200,200,400,400],"label":"b"}"""
        assertFalse(OrganismLocator.jsonArrayClosed(mid))
    }
}
