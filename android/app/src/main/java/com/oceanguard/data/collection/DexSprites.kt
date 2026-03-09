package com.oceanguard.ai.data.collection

import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType

/**
 * Maps each [DebrisType] to its pixel art drawable resource.
 *
 * Replace the placeholder PNGs in `res/drawable/dex_*.png` with your
 * own pixel art sprites (recommended: 64x64 or 128x128 px PNG with
 * transparent background). The app renders them with [FilterQuality.None]
 * for crispy Game Boy-style pixels.
 */
object DexSprites {

    fun getSprite(type: DebrisType): Int = SPRITE_MAP[type] ?: R.drawable.dex_other

    private val SPRITE_MAP: Map<DebrisType, Int> = mapOf(
        DebrisType.BOTTLE to R.drawable.dex_bottle,
        DebrisType.CAN to R.drawable.dex_can,
        DebrisType.FISHING_NET to R.drawable.dex_fishing_net,
        DebrisType.GLOVE to R.drawable.dex_glove,
        DebrisType.MASK to R.drawable.dex_mask,
        DebrisType.METAL_DEBRIS to R.drawable.dex_metal_debris,
        DebrisType.PLASTIC_DEBRIS to R.drawable.dex_plastic_debris,
        DebrisType.TIRE to R.drawable.dex_tire,
        DebrisType.FABRIC_DEBRIS to R.drawable.dex_fabric_debris,
        DebrisType.GLASS_DEBRIS to R.drawable.dex_glass_debris,
        DebrisType.OTHER to R.drawable.dex_other,
    )
}
