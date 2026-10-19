package com.closerouter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RackHardwareLayoutTest {
    @Test
    fun sevenDrivesFillTheCavityWithoutTouchingItsWalls() {
        val layout = RackHardwareLayout
        val end = layout.driveX(layout.DRIVE_COUNT - 1) + layout.DRIVE_THICKNESS
        assertEquals(7, layout.DRIVE_COUNT)
        assertTrue(layout.driveX(0) > 0.3f)
        assertTrue(end < 5.7f)
        assertTrue(layout.DRIVE_Y0 > 0.3f)
        assertTrue(layout.DRIVE_Y1 < RackLayout.CABINET_DEPTH - 0.3f)
        assertTrue(layout.DRIVE_THICKNESS < layout.DRIVE_PITCH)
        assertTrue(layout.DRIVE_HEIGHT + 0.04f < layout.CAVITY_DEPTH)
        assertTrue((layout.DRIVE_Y1 - layout.DRIVE_Y0) / (RackLayout.CABINET_DEPTH - 0.6f) > 0.8f)
    }

    @Test
    fun opticRowFitsInsideTheThickerBase() {
        val layout = RackHardwareLayout
        assertEquals(6, layout.OPTIC_COUNT)
        assertTrue(layout.opticX(0) - layout.OPTIC_WIDTH / 2f > -0.1f)
        assertTrue(layout.opticX(layout.OPTIC_COUNT - 1) + layout.OPTIC_WIDTH / 2f < 6.1f)
        assertTrue(layout.OPTIC_WIDTH < layout.OPTIC_PITCH)
        assertTrue(layout.OPTIC_PITCH - layout.OPTIC_WIDTH in 0.15f..0.20f)
        assertEquals(6f, layout.opticX(0) + layout.opticX(layout.OPTIC_COUNT - 1), 0.0001f)
        assertTrue(layout.OPTIC_Z0 > layout.BASE_BOTTOM)
        assertTrue(layout.OPTIC_Z1 < 0.25f)
    }

    @Test
    fun cableOutletStaysCenteredOnItsBootThroughoutWithdrawal() {
        val layout = RackHardwareLayout
        for (index in 0 until layout.OPTIC_COUNT) {
            for (progress in listOf(0f, 0.5f, 1f, 1.08f)) {
                for (cableProgress in listOf(0f, 0.5f, 1f)) {
                    for (strand in 0 until layout.OPTIC_STRAND_COUNT) {
                        val outlet = layout.opticCableOutlet(index, progress, cableProgress, strand)
                        assertEquals(layout.opticStrandX(index, strand), outlet.x, 0f)
                        assertEquals(layout.opticConnectorOrigin(progress, cableProgress) + layout.OPTIC_CONNECTOR_END, outlet.y, 0f)
                        assertEquals((layout.OPTIC_Z0 + layout.OPTIC_Z1) * 0.5f, outlet.z, 0f)
                    }
                }
            }
        }
    }

    @Test
    fun oneSquarePlugFitsTheSocketAndHasTwoSymmetricCableOutlets() {
        val layout = RackHardwareLayout
        assertEquals(2, layout.OPTIC_STRAND_COUNT)
        assertTrue(layout.OPTIC_PLUG_HALF_WIDTH < layout.OPTIC_SOCKET_HALF_WIDTH - 0.022f)
        for (index in 0 until layout.OPTIC_COUNT) {
            val left = layout.opticCableOutlet(index, 0f, 0f, 0)
            val right = layout.opticCableOutlet(index, 0f, 0f, 1)
            assertEquals(layout.opticX(index), (left.x + right.x) * 0.5f, 0.0001f)
            assertEquals(layout.OPTIC_STRAND_PITCH, right.x - left.x, 0.0001f)
            assertEquals(left.y, right.y, 0f)
            assertEquals(left.z, right.z, 0f)
        }
    }

    @Test
    fun opticsCycleThroughConnectedCableUnpluggedAndModuleWithdrawn() {
        var state = OpticState.CONNECTED
        assertEquals(0f, state.moduleExtension, 0f)
        assertEquals(0f, state.cableExtension, 0f)
        state = state.next()
        assertEquals(OpticState.CABLE_UNPLUGGED, state)
        assertEquals(0f, state.moduleExtension, 0f)
        assertEquals(1f, state.cableExtension, 0f)
        state = state.next()
        assertEquals(OpticState.MODULE_WITHDRAWN, state)
        assertEquals(1f, state.moduleExtension, 0f)
        assertEquals(1f, state.cableExtension, 0f)
        assertEquals(OpticState.CONNECTED, state.next())
    }

    @Test
    fun connectedCableIsSeatedAndFirstClickRevealsAGap() {
        val layout = RackHardwareLayout
        val module = layout.opticFront(0f)
        val connectedTip = layout.opticConnectorOrigin(0f, 0f) + 0.015f
        val unpluggedTip = layout.opticConnectorOrigin(0f, 1f) + 0.015f
        assertTrue(connectedTip < module)
        assertTrue(unpluggedTip > module + 0.3f)
    }

    @Test
    fun secondClickMovesModuleAndCableTogetherWithoutChangingTheirGap() {
        val layout = RackHardwareLayout
        val cableOnly = layout.opticConnectorOrigin(0f, 1f) - layout.opticFront(0f)
        val bothOut = layout.opticConnectorOrigin(1f, 1f) - layout.opticFront(1f)
        assertEquals(cableOnly, bothOut, 0.0001f)
        val firstOutlet = layout.opticCableOutlet(2, 0f, 1f)
        val secondOutlet = layout.opticCableOutlet(2, 1f, 1f)
        assertEquals(layout.OPTIC_TRAVEL, secondOutlet.y - firstOutlet.y, 0.0001f)
        assertEquals(firstOutlet.x, secondOutlet.x, 0f)
        assertEquals(firstOutlet.z, secondOutlet.z, 0f)
    }

    @Test
    fun entranceApproachesTheSocketFromOutsideAndClampsAtBothEnds() {
        val layout = RackHardwareLayout
        assertEquals(layout.OPTIC_ENTRANCE_DISTANCE, layout.opticEntranceDepth(0f), 0f)
        assertEquals(0f, layout.opticEntranceDepth(1f), 0f)
        assertEquals(layout.opticEntranceDepth(0f), layout.opticEntranceDepth(-1f), 0f)
        assertEquals(layout.opticEntranceDepth(1f), layout.opticEntranceDepth(2f), 0f)
        assertTrue(layout.opticEntranceDepth(0.25f) > layout.opticEntranceDepth(0.75f))
        assertTrue(layout.OPTIC_INSERT_MILLIS >= 500)
    }

    @Test
    fun incomingModuleHasAFullMetalBodyInsteadOfOnlyItsFrontFrame() {
        val layout = RackHardwareLayout
        for (progress in listOf(0f, 0.4f, 0.75f)) {
            val front = layout.opticFront(0f)
            val rear = layout.opticBodyBack(0f, progress)
            assertEquals(layout.OPTIC_BODY_LENGTH, front - rear, 0.0001f)
        }
    }

    @Test
    fun moduleBodyIsHiddenGraduallyAsItEntersTheBase() {
        val layout = RackHardwareLayout
        val front = layout.opticFront(0f)
        val near = front - layout.opticBodyBack(0f, 0.95f)
        val seated = front - layout.opticBodyBack(0f, 1f)
        assertTrue(near > seated && near < layout.OPTIC_BODY_LENGTH)
        assertEquals(layout.OPTIC_FRONT + 0.012f, layout.opticBodyBack(0f, 1f), 0f)
        val withdrawn = layout.opticFront(1f) - layout.opticBodyBack(1f, 1f)
        assertTrue(withdrawn > 0.9f && withdrawn <= layout.OPTIC_BODY_LENGTH)
    }

    @Test
    fun completeOpticUsesLongPlainHousingAndAShortFrontLabelHead() {
        val layout = RackHardwareLayout
        assertTrue(layout.OPTIC_BODY_LENGTH / layout.OPTIC_WIDTH in 4.5f..5.5f)
        assertTrue(layout.OPTIC_HEAD_LENGTH / layout.OPTIC_BODY_LENGTH in 0.18f..0.28f)
        val fullFront = layout.opticFront(1f)
        assertEquals(layout.OPTIC_BODY_LENGTH, fullFront - layout.opticBodyBack(1f, 1f), 0.0001f)
        assertTrue(fullFront - layout.OPTIC_BODY_LENGTH > layout.OPTIC_FRONT)
    }

    @Test
    fun flowStopsImmediatelyWhenEitherComponentIsRequestedToWithdraw() {
        val layout = RackHardwareLayout
        assertTrue(layout.opticFlowEnabled(true, OpticState.CONNECTED, 1f, 0f, 0f))
        assertFalse(layout.opticFlowEnabled(true, OpticState.CABLE_UNPLUGGED, 1f, 0f, 0f))
        assertFalse(layout.opticFlowEnabled(true, OpticState.MODULE_WITHDRAWN, 1f, 0f, 0f))
        assertFalse(layout.opticFlowEnabled(false, OpticState.CONNECTED, 1f, 0f, 0f))
    }

    @Test
    fun flowWaitsForStartupAndBothComponentsToBeFullySeated() {
        val layout = RackHardwareLayout
        assertFalse(layout.opticFlowEnabled(true, OpticState.CONNECTED, 0.95f, 0f, 0f))
        assertFalse(layout.opticFlowEnabled(true, OpticState.CONNECTED, 1f, 0.02f, 0f))
        assertFalse(layout.opticFlowEnabled(true, OpticState.CONNECTED, 1f, 0f, 0.02f))
        assertFalse(layout.opticFlowEnabled(true, OpticState.CONNECTED, 1f, -0.02f, 0f))
        assertFalse(layout.opticFlowEnabled(true, OpticState.CONNECTED, 1f, 0f, -0.02f))
        assertTrue(layout.opticFlowEnabled(true, OpticState.CONNECTED, 1f, 0.0001f, -0.0001f))
    }

    @Test
    fun opticWithdrawalOnlyMovesItsDepthAndKeepsAClampedTravel() {
        val layout = RackHardwareLayout
        val seated = layout.opticFront(0f)
        assertEquals(seated, layout.opticFront(-1f), 0f)
        assertTrue(layout.opticFront(0.5f) > seated)
        assertEquals(layout.OPTIC_TRAVEL, layout.opticFront(1f) - seated, 0.0001f)
        assertEquals(layout.opticFront(1.08f), layout.opticFront(5f), 0f)
    }
}
