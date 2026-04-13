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
        // Core RT-DETRv2 types (dedicated sprites)
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
        // Plastic sub-types
        DebrisType.BOTTLE_CAP to R.drawable.dex_plastic_debris,
        DebrisType.PLASTIC_BAG to R.drawable.dex_plastic_debris,
        DebrisType.FOOD_WRAPPER to R.drawable.dex_plastic_debris,
        DebrisType.STYROFOAM to R.drawable.dex_plastic_debris,
        DebrisType.PLASTIC_CUP to R.drawable.dex_plastic_debris,
        DebrisType.STRAW to R.drawable.dex_plastic_debris,
        DebrisType.PLASTIC_UTENSIL to R.drawable.dex_plastic_debris,
        DebrisType.SIX_PACK_RING to R.drawable.dex_plastic_debris,
        DebrisType.PLASTIC_SHEETING to R.drawable.dex_plastic_debris,
        DebrisType.DIAPER to R.drawable.dex_plastic_debris,
        // Metal sub-types
        DebrisType.AEROSOL_CAN to R.drawable.dex_metal_debris,
        DebrisType.METAL_DRUM to R.drawable.dex_metal_debris,
        DebrisType.WIRE_CABLE to R.drawable.dex_metal_debris,
        DebrisType.BATTERY to R.drawable.dex_metal_debris,
        DebrisType.ELECTRONICS to R.drawable.dex_metal_debris,
        // Glass sub-types
        DebrisType.GLASS_BOTTLE to R.drawable.dex_glass_debris,
        DebrisType.GLASS_JAR to R.drawable.dex_glass_debris,
        DebrisType.GLASS_FRAGMENT to R.drawable.dex_glass_debris,
        DebrisType.LIGHT_BULB to R.drawable.dex_glass_debris,
        // Rubber sub-types
        DebrisType.FLIP_FLOP to R.drawable.dex_tire,
        DebrisType.RUBBER_HOSE to R.drawable.dex_tire,
        // Fishing sub-types
        DebrisType.FISHING_LINE to R.drawable.dex_fishing_net,
        DebrisType.ROPE to R.drawable.dex_fishing_net,
        DebrisType.FISHING_BUOY to R.drawable.dex_fishing_net,
        DebrisType.FISHING_TRAP to R.drawable.dex_fishing_net,
        // Fabric sub-types
        DebrisType.CLOTHING to R.drawable.dex_fabric_debris,
        DebrisType.SHOE to R.drawable.dex_fabric_debris,
        // All others (paper, wood, ceramic, chemical, cigarette)
        DebrisType.CIGARETTE_BUTT to R.drawable.dex_other,
        DebrisType.CIGARETTE_LIGHTER to R.drawable.dex_other,
        DebrisType.CARDBOARD to R.drawable.dex_other,
        DebrisType.PAPER to R.drawable.dex_other,
        DebrisType.WOOD_PALLET to R.drawable.dex_other,
        DebrisType.LUMBER to R.drawable.dex_other,
        DebrisType.CERAMIC_FRAGMENT to R.drawable.dex_other,
        DebrisType.BRICK to R.drawable.dex_other,
        DebrisType.PAINT_CAN to R.drawable.dex_other,
        DebrisType.OIL_CONTAINER to R.drawable.dex_other,
        DebrisType.SYRINGE to R.drawable.dex_other,
        DebrisType.CHEMICAL_DRUM to R.drawable.dex_other,
    )
}
