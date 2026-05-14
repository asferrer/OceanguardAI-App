package com.oceanguard.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the canonical projection from the 50 [DebrisType] enum entries onto
 * the 11 [DebrisType.CANONICAL] entries that the MarineDex, achievements, and
 * reports operate on.
 *
 * Source of truth: `revisa-en-detalle-como-precious-dewdrop.md` plan A1 mapping
 * (approved by user 2026-05-14). Any change to the mapping must update this
 * test in lockstep so regressions are caught at build time.
 */
class DebrisTypeCanonicalTest {

    @Test
    fun `canonical types map to themselves`() {
        for (type in DebrisType.CANONICAL) {
            assertEquals(
                "Canonical type ${type.name} must be idempotent under canonical()",
                type,
                type.canonical(),
            )
        }
    }

    @Test
    fun `CANONICAL list has exactly 11 entries`() {
        assertEquals(DebrisType.CANONICAL_COUNT, DebrisType.CANONICAL.size)
        assertEquals(11, DebrisType.CANONICAL.size)
    }

    @Test
    fun `every non-canonical type maps to a canonical type`() {
        val nonCanon = DebrisType.entries.filter { it !in DebrisType.CANONICAL }
        for (type in nonCanon) {
            val canon = type.canonical()
            assertTrue(
                "Non-canonical ${type.name} must canonicalize to one of CANONICAL (got ${canon.name})",
                canon in DebrisType.CANONICAL,
            )
            assertNotEquals(
                "Non-canonical ${type.name} must not equal its own canonical()",
                type,
                canon,
            )
        }
    }

    @Test
    fun `plastic family collapses to PLASTIC_DEBRIS`() {
        for (type in listOf(
            DebrisType.BOTTLE_CAP,
            DebrisType.PLASTIC_BAG,
            DebrisType.FOOD_WRAPPER,
            DebrisType.STYROFOAM,
            DebrisType.PLASTIC_CUP,
            DebrisType.STRAW,
            DebrisType.PLASTIC_UTENSIL,
            DebrisType.SIX_PACK_RING,
            DebrisType.PLASTIC_SHEETING,
            DebrisType.DIAPER,
        )) {
            assertEquals("$type should canonicalize to PLASTIC_DEBRIS", DebrisType.PLASTIC_DEBRIS, type.canonical())
        }
    }

    @Test
    fun `metal family collapses to METAL_DEBRIS including hazardous containers`() {
        for (type in listOf(
            DebrisType.AEROSOL_CAN,
            DebrisType.METAL_DRUM,
            DebrisType.WIRE_CABLE,
            DebrisType.BATTERY,
            DebrisType.ELECTRONICS,
            DebrisType.PAINT_CAN,
            DebrisType.OIL_CONTAINER,
            DebrisType.CHEMICAL_DRUM,
        )) {
            assertEquals("$type should canonicalize to METAL_DEBRIS", DebrisType.METAL_DEBRIS, type.canonical())
        }
    }

    @Test
    fun `glass family collapses to GLASS_DEBRIS`() {
        for (type in listOf(
            DebrisType.GLASS_BOTTLE,
            DebrisType.GLASS_JAR,
            DebrisType.GLASS_FRAGMENT,
            DebrisType.LIGHT_BULB,
        )) {
            assertEquals("$type should canonicalize to GLASS_DEBRIS", DebrisType.GLASS_DEBRIS, type.canonical())
        }
    }

    @Test
    fun `fishing family collapses to FISHING_NET`() {
        for (type in listOf(
            DebrisType.FISHING_LINE,
            DebrisType.ROPE,
            DebrisType.FISHING_BUOY,
            DebrisType.FISHING_TRAP,
        )) {
            assertEquals("$type should canonicalize to FISHING_NET", DebrisType.FISHING_NET, type.canonical())
        }
    }

    @Test
    fun `rubber family collapses to TIRE`() {
        assertEquals(DebrisType.TIRE, DebrisType.FLIP_FLOP.canonical())
        assertEquals(DebrisType.TIRE, DebrisType.RUBBER_HOSE.canonical())
    }

    @Test
    fun `fabric family collapses to FABRIC_DEBRIS`() {
        assertEquals(DebrisType.FABRIC_DEBRIS, DebrisType.CLOTHING.canonical())
        assertEquals(DebrisType.FABRIC_DEBRIS, DebrisType.SHOE.canonical())
    }

    @Test
    fun `cigarettes and syringes map to OTHER`() {
        assertEquals(DebrisType.OTHER, DebrisType.CIGARETTE_BUTT.canonical())
        assertEquals(DebrisType.OTHER, DebrisType.CIGARETTE_LIGHTER.canonical())
        assertEquals(DebrisType.OTHER, DebrisType.SYRINGE.canonical())
    }

    @Test
    fun `natural materials map to OTHER`() {
        for (type in listOf(
            DebrisType.CARDBOARD,
            DebrisType.PAPER,
            DebrisType.WOOD_PALLET,
            DebrisType.LUMBER,
            DebrisType.CERAMIC_FRAGMENT,
            DebrisType.BRICK,
        )) {
            assertEquals("$type should canonicalize to OTHER", DebrisType.OTHER, type.canonical())
        }
    }

    @Test
    fun `isCanonical reflects CANONICAL membership`() {
        for (type in DebrisType.entries) {
            val expected = type in DebrisType.CANONICAL
            assertEquals(
                "isCanonical(${type.name}) should be $expected",
                expected,
                DebrisType.isCanonical(type),
            )
        }
    }

    @Test
    fun `every enum entry is covered by canonical()`() {
        // Belt-and-braces: ensures we never add a new DebrisType without
        // also wiring it up in CANONICAL_PARENT (or making it canonical).
        for (type in DebrisType.entries) {
            val canon = type.canonical()
            assertTrue(
                "${type.name} canonicalizes to ${canon.name} which must be in CANONICAL",
                canon in DebrisType.CANONICAL,
            )
        }
    }
}
