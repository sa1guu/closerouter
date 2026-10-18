package com.closerouter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RackLayoutTest {
    @Test
    fun connectsAdjacentActiveUnitsThroughSharedSingleNodes() {
        assertEquals(
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6),
            RackLayout.connections(List(7) { true })
        )
    }

    @Test
    fun skipsInactiveUnitsWithoutCreatingExtraNodes() {
        assertEquals(listOf(0 to 3, 3 to 6), RackLayout.connections(listOf(true, false, false, true, false, false, true)))
    }

    @Test
    fun emptyAndSingleActiveUnitNeedNoCable() {
        assertTrue(RackLayout.connections(emptyList()).isEmpty())
        assertTrue(RackLayout.connections(List(7) { false }).isEmpty())
        assertTrue(RackLayout.connections(listOf(false, true, false)).isEmpty())
    }

    @Test
    fun routerAndPortsShareTheRearRowWithSpacing() {
        val labelWidth = 6 * RackLayout.TOP_LABEL_SIZE * 0.62f
        assertTrue(RackLayout.TOP_LIGHT_END_X + 0.2f < RackLayout.TOP_LABEL_X)
        assertTrue(RackLayout.TOP_LABEL_X + labelWidth + 0.1f < RackLayout.TOP_TYPE_C_X - 0.225f)
        assertTrue(RackLayout.TOP_TYPE_C_X + 0.225f + 0.1f < RackLayout.TOP_USB_X - 0.245f)
        assertTrue(RackLayout.TOP_USB_X + 0.245f < 5.6f)
    }

    @Test
    fun trimsOnlyTheNearEndOfPowerCable() {
        assertTrue("只裁剪近端约 20%", RackLayout.CABLE_START in 0.15f..0.25f)
        assertTrue("远端保留大部分线缆", 1f - RackLayout.CABLE_START > 0.7f)
    }

    @Test
    fun ejectedMiddleUnitUsesOneRunFromFirstToThird() {
        assertEquals(listOf(CableRun(0, 2, 1)),
            RackLayout.cableRuns(listOf(true, true, true), floatArrayOf(0f, 1.4f, 0f)))
        assertEquals(listOf(CableRun(0, 1), CableRun(1, 2)),
            RackLayout.cableRuns(listOf(true, true, true), floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun mergedArcKeepsTheRemainingCableRuns() {
        assertEquals(listOf(CableRun(0, 1), CableRun(1, 3, 2), CableRun(3, 4)),
            RackLayout.cableRuns(List(5) { true }, floatArrayOf(0f, 0f, 1.4f, 0f, 0f)))
        assertEquals(listOf(CableRun(0, 3, 1)),
            RackLayout.cableRuns(listOf(true, true, false, true), floatArrayOf(0f, 1.4f, 0f, 0f)))
    }

    @Test
    fun singleArcPassesThroughTheEjectedPointAtItsMidpoint() {
        val start = 2f
        val middle = -1.5f
        val end = 4f
        val (control1, control2) = RackLayout.arcCubicControls(start, middle, end)
        assertEquals(middle, 0.125f * start + 0.375f * control1 + 0.375f * control2 + 0.125f * end, 0.0001f)
    }

    @Test
    fun mergedArcUsesWideHandlesForARoundedBend() {
        val (height1, height2) = RackLayout.arcCubicControls(1f, 0f, -1f)
        assertTrue(height1 > 1f)
        assertTrue(height2 < -1f)
        val (depth1, depth2) = RackLayout.arcCubicControls(0f, 1.4f, 0f)
        assertEquals(depth1, depth2, 0.0001f)
        assertTrue(depth1 > 1.4f)
    }

    @Test
    fun endpointEjectionOrTooFewNodesKeepsTheOriginalRun() {
        assertTrue(RackLayout.cableRuns(emptyList(), floatArrayOf()).isEmpty())
        assertTrue(RackLayout.cableRuns(listOf(true), floatArrayOf(1f)).isEmpty())
        assertEquals(listOf(CableRun(0, 1)),
            RackLayout.cableRuns(listOf(true, true), floatArrayOf(1.4f, 0f)))
        assertEquals(listOf(CableRun(0, 1), CableRun(1, 2)),
            RackLayout.cableRuns(List(3) { true }, floatArrayOf(1.4f, 0f, 0f)))
    }

    @Test
    fun adjacentLiveUnitsAreJoinedWithAStraightCable() {
        assertEquals(CableStyle.STRAIGHT, RackLayout.cableStyle(0, 1, false, false))
        assertEquals(CableStyle.STRAIGHT, RackLayout.cableStyle(5, 6, false, false))
    }

    @Test
    fun straightCableUsesFewerFlowDotsThanTheCurvedCable() {
        assertEquals(2, RackLayout.flowDotCount(CableStyle.STRAIGHT))
        assertEquals(3, RackLayout.flowDotCount(CableStyle.CURVED))
    }

    @Test
    fun darkGapOrAnEjectedUnitKeepsTheCableCurved() {
        // A dark unit between two live nodes leaves a visible gap.
        assertEquals(CableStyle.CURVED, RackLayout.cableStyle(0, 2, false, false))
        // Either endpoint moving out of the rack needs slack.
        assertEquals(CableStyle.CURVED, RackLayout.cableStyle(0, 1, true, false))
        assertEquals(CableStyle.CURVED, RackLayout.cableStyle(0, 1, false, true))
    }

    @Test
    fun topPortsClearTheFanTray() {
        assertTrue(RackLayout.TOP_ROW_Y + 0.20f + 0.1f < RackLayout.TOP_FAN_Y - 1.08f)
        assertTrue(RackLayout.TOP_FAN_Y + 1.08f < RackLayout.CABINET_DEPTH - 0.25f)
    }

    @Test
    fun powerButtonClearsBothEdgesOfItsOrangePanel() {
        val topOffset = RackLayout.POWER_TOP_OFFSET - RackLayout.POWER_RADIUS
        val bottomOffset = RackLayout.POWER_TOP_OFFSET + RackLayout.POWER_RADIUS
        assertTrue(topOffset > RackLayout.SHELL_RADIUS + 0.04f + 0.08f)
        assertTrue(bottomOffset < 1.45f - 0.08f)
    }
}
