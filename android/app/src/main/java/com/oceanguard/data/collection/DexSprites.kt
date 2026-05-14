package com.oceanguard.ai.data.collection

import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType

/**
 * Maps each canonical [DebrisType] to its pixel art drawable resource.
 *
 * Sprites cover the 11 canonical types ([DebrisType.CANONICAL]). Extended
 * Gemma 4 types (PLASTIC_BAG, STYROFOAM, ...) collapse to their canonical
 * parent via [DebrisType.canonical] before lookup, so they reuse the parent's
 * sprite (e.g. PLASTIC_BAG -> dex_plastic_debris).
 *
 * Replace the placeholder PNGs in `res/drawable/dex_*.png` with pixel art
 * sprites (recommended: 64x64 or 128x128 px PNG with transparent background).
 * The app renders them with [FilterQuality.None] for crispy Game Boy-style pixels.
 */
object DexSprites {

    fun getSprite(type: DebrisType): Int =
        SPRITE_MAP[type.canonical()] ?: R.drawable.dex_other

    private val SPRITE_MAP: Map<DebrisType, Int> = mapOf(
        DebrisType.BOTTLE         to R.drawable.dex_bottle,
        DebrisType.CAN            to R.drawable.dex_can,
        DebrisType.FISHING_NET    to R.drawable.dex_fishing_net,
        DebrisType.GLOVE          to R.drawable.dex_glove,
        DebrisType.MASK           to R.drawable.dex_mask,
        DebrisType.METAL_DEBRIS   to R.drawable.dex_metal_debris,
        DebrisType.PLASTIC_DEBRIS to R.drawable.dex_plastic_debris,
        DebrisType.TIRE           to R.drawable.dex_tire,
        DebrisType.FABRIC_DEBRIS  to R.drawable.dex_fabric_debris,
        DebrisType.GLASS_DEBRIS   to R.drawable.dex_glass_debris,
        DebrisType.OTHER          to R.drawable.dex_other,
    )
}
