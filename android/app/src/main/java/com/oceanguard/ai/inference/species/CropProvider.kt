package com.oceanguard.ai.inference.species

import android.graphics.Bitmap

/**
 * Minimal interface for organism crop extraction.
 *
 * Extracted so that [SpeciesIdentifier] can be unit-tested on plain JVM
 * without Android SDK dependencies. The production implementation is
 * [OrganismLocator]; the test double is [FakeCropProvider].
 */
interface CropProvider {
    /**
     * Return a list of [OrganismCrop]s for [bitmap].
     * Must never return an empty list (fallback to full-frame crop).
     */
    suspend fun locate(bitmap: Bitmap): List<OrganismCrop>
}
