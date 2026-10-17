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
    fun identifierAndIndicatorsShareOneRowWithSpacing() {
        val labelWidth = 5 * RackLayout.TOP_LABEL_SIZE * 0.65f
        assertTrue(RackLayout.TOP_LABEL_X + labelWidth + 0.1f < RackLayout.TOP_DOT_X)
        assertEquals(RackLayout.TOP_LABEL_Y + RackLayout.TOP_LABEL_SIZE * 0.6f, RackLayout.TOP_DOT_Y, 0.0001f)
        assertTrue(RackLayout.TOP_DOT_X + (RackLayout.TOP_DOT_COUNT - 1) * RackLayout.TOP_DOT_PITCH + 0.1f < 5.6f)
    }

    @Test
    fun trimsOnlyTheNearEndOfPowerCable() {
        assertTrue("只裁剪近端约 20%", RackLayout.CABLE_START in 0.15f..0.25f)
        assertTrue("远端保留大部分线缆", 1f - RackLayout.CABLE_START > 0.7f)
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
    fun topIdentifierClearsTheRearFan() {
        // Fan 3 center (RW-1.15, RD/2), radius .81; indicator row must sit behind its rear edge.
        val fanRearEdge = 2.8f / 2f - 0.81f
        assertTrue(RackLayout.TOP_DOT_Y < fanRearEdge - 0.15f)
    }
}
