package com.oceanguard.ai.species

import com.oceanguard.ai.inference.species.SpeciesConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SpeciesConfidence].
 *
 * Verifies the Platt sigmoid calibration, threshold decisions, and
 * `isUnknown` logic against hand-computed reference values.
 */
class SpeciesConfidenceTest {

    private val sut = SpeciesConfidence(
        plattA  = 10.0f,
        plattB  = -3.5f,
        tauHigh = 0.60f,
        tauLow  = 0.30f,
        delta   = 0.05f,
    )

    // -----------------------------------------------------------------------
    // Platt sigmoid
    // -----------------------------------------------------------------------

    @Test
    fun `calibrate score 0 returns near-zero probability`() {
        // sigmoid(10*0 + (-3.5)) = sigmoid(-3.5) ≈ 0.0293
        val cal = sut.calibrate(0.0f)
        assertEquals(0.0293f, cal, 5e-3f)
    }

    @Test
    fun `calibrate score 0_35 is above tauLow`() {
        // sigmoid(10*0.35 - 3.5) = sigmoid(0) = 0.5
        val cal = sut.calibrate(0.35f)
        assertEquals(0.5f, cal, 1e-4f)
    }

    @Test
    fun `calibrate score 1_0 returns near-one probability`() {
        // sigmoid(10 - 3.5) = sigmoid(6.5) ≈ 0.9985
        val cal = sut.calibrate(1.0f)
        assertEquals(0.9985f, cal, 5e-3f)
    }

    @Test
    fun `calibrated probability is always in 0 to 1 range`() {
        for (score in listOf(0.0f, 0.1f, 0.3f, 0.5f, 0.7f, 1.0f)) {
            val cal = sut.calibrate(score)
            assertTrue("calibrate($score) = $cal must be in [0,1]", cal in 0f..1f)
        }
    }

    // -----------------------------------------------------------------------
    // idSource
    // -----------------------------------------------------------------------

    @Test
    fun `idSource returns RAG_CORE for high confidence and large margin`() {
        // confidence=0.8 > tauHigh=0.6, margin=0.1 > delta=0.05
        assertEquals("RAG_CORE", sut.idSource(confidence = 0.8f, margin = 0.1f))
    }

    @Test
    fun `idSource returns RAG_TENTATIVE for high confidence but small margin`() {
        // confidence=0.8 > tauHigh but margin=0.02 < delta
        assertEquals("RAG_TENTATIVE", sut.idSource(confidence = 0.8f, margin = 0.02f))
    }

    @Test
    fun `idSource returns RAG_TENTATIVE for mid-range confidence`() {
        // tauLow <= 0.45 < tauHigh
        assertEquals("RAG_TENTATIVE", sut.idSource(confidence = 0.45f, margin = 0.1f))
    }

    @Test
    fun `idSource returns null for below-threshold confidence`() {
        // confidence=0.1 < tauLow=0.3
        assertNull(sut.idSource(confidence = 0.1f, margin = 0.1f))
    }

    @Test
    fun `idSource boundary - confidence exactly tauHigh with margin exactly delta`() {
        assertEquals("RAG_CORE", sut.idSource(confidence = 0.60f, margin = 0.05f))
    }

    @Test
    fun `idSource boundary - confidence exactly tauLow`() {
        assertEquals("RAG_TENTATIVE", sut.idSource(confidence = 0.30f, margin = 0.1f))
    }

    // -----------------------------------------------------------------------
    // isUnknown
    // -----------------------------------------------------------------------

    @Test
    fun `isUnknown true when confidence below tauLow`() {
        assertTrue(sut.isUnknown(0.10f))
        assertTrue(sut.isUnknown(0.0f))
    }

    @Test
    fun `isUnknown false when confidence at or above tauLow`() {
        assertFalse(sut.isUnknown(0.30f))
        assertFalse(sut.isUnknown(0.50f))
        assertFalse(sut.isUnknown(1.0f))
    }

    // -----------------------------------------------------------------------
    // Custom parameters
    // -----------------------------------------------------------------------

    @Test
    fun `custom Platt parameters change calibration`() {
        // Steeper slope should push mid-range scores toward extremes
        val steep = SpeciesConfidence(plattA = 20.0f, plattB = -5.0f)
        val default = SpeciesConfidence()
        val score = 0.4f
        val calSteep = steep.calibrate(score)
        val calDefault = default.calibrate(score)
        // Both should be valid but different
        assertFalse("Steep calibration should differ from default", calSteep == calDefault)
    }
}
