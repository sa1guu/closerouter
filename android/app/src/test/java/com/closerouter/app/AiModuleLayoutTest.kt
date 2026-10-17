package com.closerouter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class AiModuleLayoutTest {
    @Test
    fun frameMatchesTheEntireOriginalSideInstrument() {
        val frame = AiModuleLayout.frame
        assertEquals(0.87f, frame.y0, 0.0001f)
        assertEquals(2.67f, frame.y1, 0.0001f)
        assertEquals(5.25f, frame.z0, 0.0001f)
        assertEquals(6.94f, frame.z1, 0.0001f)
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
    fun entireAssemblyFitsTheCabinetAndClearsTheNextDisplay() {
        val frame = AiModuleLayout.frame
        assertTrue(frame.y0 > 0.08f)
        assertTrue(frame.y1 < 2.7f)
        assertTrue(frame.z1 < 7.08f)
        assertTrue(frame.z0 > 5.08f + 0.05f + 0.1f)
    }
}
