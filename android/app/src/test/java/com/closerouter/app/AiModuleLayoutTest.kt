package com.closerouter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class AiModuleLayoutTest {
    @Test
    fun frameMatchesTheRepositionedSideInstrument() {
        val frame = AiModuleLayout.frame
        assertEquals(1.11f, frame.y0, 0.0001f)
        assertEquals(2.91f, frame.y1, 0.0001f)
        assertEquals(5.07f, frame.z0, 0.0001f)
        assertEquals(6.76f, frame.z1, 0.0001f)
    }

    @Test
    fun screenKeepsItsOriginalSizeInsteadOfBeingScaledForThePopup() {
        val screen = AiModuleLayout.screen
        assertEquals(1.5f, screen.y1 - screen.y0, 0.0001f)
        assertEquals(1.32f, screen.z1 - screen.z0, 0.0001f)
        assertEquals(0f, AiModuleLayout.extension(0f), 0f)
        assertEquals(1f, AiModuleLayout.extension(1f), 0f)
    }

    @Test
    fun allBezelLayersStayInsideTheMovingFace() {
        val layers = listOf(AiModuleLayout.frame, AiModuleLayout.outerBezel, AiModuleLayout.innerBezel, AiModuleLayout.screen)
        for ((outer, inner) in layers.zipWithNext()) {
            assertTrue(inner.y0 > outer.y0)
            assertTrue(inner.y1 < outer.y1)
            assertTrue(inner.z0 > outer.z0)
            assertTrue(inner.z1 < outer.z1)
        }
    }

    @Test
    fun cornerFastenersStayInsideTheFrameAndClearTheScreenBezel() {
        val frame = AiModuleLayout.frame
        val bezel = AiModuleLayout.outerBezel
        val radius = AiModuleLayout.SCREW_RADIUS
        assertEquals(4, AiModuleLayout.screws.distinct().size)
        for ((y, z) in AiModuleLayout.screws) {
            assertTrue(frame.contains(y - radius, z - radius))
            assertTrue(frame.contains(y + radius, z + radius))
            val dy = max(max(bezel.y0 - y, y - bezel.y1), 0f)
            val dz = max(max(bezel.z0 - z, z - bezel.z1), 0f)
            assertTrue(dy * dy + dz * dz > radius * radius)
        }
    }

    @Test
    fun hitTargetIncludesTheFullFrameRatherThanJustTheDisplay() {
        val frame = AiModuleLayout.frame
        for (y in listOf(frame.y0, frame.y1)) {
            for (z in listOf(frame.z0, frame.z1)) assertTrue(frame.contains(y, z))
        }
        assertFalse(frame.contains(frame.y0 - 0.01f, frame.z0))
        assertFalse(frame.contains(frame.y1, frame.z1 + 0.01f))
    }

    @Test
    fun springOvershootOnlyChangesDepth() {
        assertEquals(0f, AiModuleLayout.extension(-0.1f), 0f)
        assertEquals(0.5f, AiModuleLayout.extension(0.5f), 0f)
        assertEquals(1.08f, AiModuleLayout.extension(1.08f), 0f)
        assertEquals(1.15f, AiModuleLayout.extension(2f), 0.0001f)
    }

    @Test
    fun frontAndTopMarginsAreBalancedWithoutResizingThePixelDisplay() {
        val frame = AiModuleLayout.frame
        val screen = AiModuleLayout.screen
        assertTrue(RackLayout.CABINET_DEPTH - frame.y1 in 0.4f..0.5f)
        assertTrue(7.2f - frame.z1 in 0.4f..0.5f)
        assertEquals(1.5f, screen.y1 - screen.y0, 0.0001f)
        assertTrue(frame.y1 + RackLayout.SHELL_RADIUS < RackLayout.CABINET_DEPTH)
    }

    @Test
    fun entireAssemblyFitsTheCabinetAndClearsTheNextDisplay() {
        val frame = AiModuleLayout.frame
        assertTrue(frame.y0 > 0.08f)
        assertTrue(frame.y1 < RackLayout.CABINET_DEPTH - RackLayout.SHELL_RADIUS)
        assertTrue(frame.z1 < 7.2f - RackLayout.SHELL_RADIUS)
        assertTrue(frame.z0 > 5.08f + RackLayout.SIDE_DISPLAY_Z_SHIFT + 0.05f + 0.1f)
    }
}
