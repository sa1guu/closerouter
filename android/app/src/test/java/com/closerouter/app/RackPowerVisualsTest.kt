package com.closerouter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RackPowerVisualsTest {
    @Test
    fun startupBeginsWithNoIlluminatedPixels() {
        assertEquals(0f, RackPowerVisuals.bootProgress(0f), 0f)
        assertEquals(0f, RackPowerVisuals.bootProgress(-1f), 0f)
    }

    @Test
    fun startupRevealsPixelsProgressively() {
        val first = RackPowerVisuals.bootProgress(0.5f)
        val middle = RackPowerVisuals.bootProgress(1f)
        val last = RackPowerVisuals.bootProgress(1.5f)
        assertTrue(first > 0f && first < middle)
        assertTrue(middle < last && last < 1f)
    }

    @Test
    fun startupFinishesBeforeConversationAnimation() {
        assertEquals(1f, RackPowerVisuals.bootProgress(2f), 0f)
        assertEquals(1f, RackPowerVisuals.bootProgress(20f), 0f)
    }

    @Test
    fun fanAcceleratesInsteadOfJumpingToFullSpeed() {
        val first = RackPowerVisuals.advanceFanSpeed(0f, true, 0.1f)
        val next = RackPowerVisuals.advanceFanSpeed(first, true, 0.1f)
        assertTrue(first > 0f && first < next && next < 1f)
        assertEquals(1f, RackPowerVisuals.advanceFanSpeed(0f, true, 2f), 0f)
    }

    @Test
    fun stoppedFanCoastsDownBeforeBecomingStationary() {
        var speed = 1f
        repeat(10) {
            val next = RackPowerVisuals.advanceFanSpeed(speed, false, 0.1f)
            assertTrue(next > 0f && next < speed)
            speed = next
        }
        repeat(20) { speed = RackPowerVisuals.advanceFanSpeed(speed, false, 0.1f) }
        assertEquals(0f, speed, 0f)
        assertEquals(0f, RackPowerVisuals.advanceFanSpeed(speed, false, 1f), 0f)
    }

    @Test
    fun restartingDuringCoastReversesTheRampWithoutJumping() {
        val slowing = RackPowerVisuals.advanceFanSpeed(0.7f, false, 0.1f)
        val restarting = RackPowerVisuals.advanceFanSpeed(slowing, true, 0.1f)
        assertTrue(slowing < 0.7f && restarting > slowing && restarting < 1f)
    }

    @Test
    fun negativeFrameDurationDoesNotReverseFanState() {
        assertEquals(0.5f, RackPowerVisuals.advanceFanSpeed(0.5f, false, -1f), 0f)
        assertEquals(0.5f, RackPowerVisuals.advanceFanSpeed(0.5f, true, -1f), 0f)
    }

    @Test
    fun receiverDescendsFromAboveAndSettlesAtTheUsbSocket() {
        assertEquals(2.2f, RackPowerVisuals.receiverHeight(0f), 0f)
        val high = RackPowerVisuals.receiverHeight(0.2f)
        val low = RackPowerVisuals.receiverHeight(0.65f)
        assertTrue(high > low && low > 0f)
        assertEquals(0f, RackPowerVisuals.receiverHeight(0.9f), 0f)
        assertEquals(0f, RackPowerVisuals.receiverHeight(1f), 0f)
        assertEquals(2.2f, RackPowerVisuals.receiverHeight(-1f), 0f)
    }

    @Test
    fun shutdownShutterClosesContinuouslyAndEndsAtBlack() {
        val initial = RackPowerVisuals.shutdownAperture(0f)
        val middle = RackPowerVisuals.shutdownAperture(RackPowerVisuals.SHUTDOWN_SECONDS / 2f)
        val final = RackPowerVisuals.shutdownAperture(RackPowerVisuals.SHUTDOWN_SECONDS)
        assertEquals(1f, initial, 0f)
        assertTrue(middle > 0f && middle < initial)
        assertEquals(0f, final, 0f)
        assertEquals(0f, RackPowerVisuals.shutdownAperture(20f), 0f)
        assertEquals(1f, RackPowerVisuals.shutdownAperture(-1f), 0f)
    }
}
