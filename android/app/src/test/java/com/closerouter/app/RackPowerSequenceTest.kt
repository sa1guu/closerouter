package com.closerouter.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RackPowerSequenceTest {
    private class Rig {
        val progress = FloatArray(RackHardwareLayout.OPTIC_COUNT)
        val events = mutableListOf<String>()
        var stage = RackPowerStage.OFF
        var bays = 0

        fun sequence(wait: suspend (Int) -> Unit = { events += "wait:$it" }) = RackPowerSequence(
            entrance = { progress[it] },
            resetOptic = { events += "reset:$it" },
            animateOptic = { index, target, duration ->
                events += "optic:$index:$target:$duration"
                progress[index] = target
            },
            stage = { stage = it; events += "stage:$it" },
            bays = { bays = it; events += "bays:$it" },
            wait = wait
        )
    }

    @Test
    fun startupSeatsAllOpticsBeforePoweringTheOtherComponents() = runBlocking {
        val rig = Rig()
        rig.sequence().play(true)
        val optics = rig.events.filter { it.startsWith("optic:") }
        assertEquals((0..5).map { "optic:$it:1.0:700" }, optics)
        assertTrue(rig.events.indexOf(optics.last()) < rig.events.indexOf("stage:BASE"))
        val stages = rig.events.filter { it.startsWith("stage:") }
        assertEquals(RackPowerStage.entries.drop(1).map { "stage:$it" }, stages)
        assertEquals((0..7).map { "bays:$it" }, rig.events.filter { it.startsWith("bays:") })
        assertEquals(RackPowerStage.CABLES, rig.stage)
    }

    @Test
    fun shutdownWithdrawsOneOpticAtATimeBeforeHidingIt() = runBlocking {
        val rig = Rig()
        rig.progress.fill(1f)
        rig.sequence().play(false)
        assertEquals((5 downTo 0).map { "optic:$it:0.0:430" }, rig.events.filter { it.startsWith("optic:") })
        assertTrue(rig.progress.all { it == 0f })
        assertEquals(RackPowerStage.OFF, rig.stage)
        assertEquals(0, rig.bays)
    }

    @Test
    fun shutdownDuringInsertionOnlyWithdrawsVisibleOptics() = runBlocking {
        val rig = Rig()
        rig.progress[0] = 1f
        rig.progress[1] = 0.5f
        rig.sequence().play(false)
        assertEquals(listOf("optic:1:0.0:215", "optic:0:0.0:430"), rig.events.filter { it.startsWith("optic:") })
    }

    @Test
    fun restartContinuesPartialTravelWithoutTeleportingSeatedOptics() = runBlocking {
        val rig = Rig()
        rig.progress[0] = 1f
        rig.progress[1] = 0.5f
        rig.sequence().play(true)
        assertFalse(rig.events.any { it.startsWith("optic:0:") })
        assertTrue(rig.events.contains("optic:1:1.0:350"))
        assertTrue(rig.progress.all { it == 1f })
    }

    @Test
    fun cancellingStartupPreventsLaterStagesFromStarting() = runBlocking {
        val rig = Rig()
        val reachedBase = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val sequence = rig.sequence {
            if (rig.stage == RackPowerStage.BASE) {
                reachedBase.complete(Unit)
                blocked.await()
            }
        }
        val job = launch { sequence.play(true) }
        reachedBase.await()
        job.cancelAndJoin()
        sequence.play(false)
        assertFalse(rig.events.contains("stage:RECEIVER"))
        assertEquals(RackPowerStage.OFF, rig.stage)
        assertTrue(rig.progress.all { it == 0f })
    }

    @Test
    fun emptyShutdownDoesNotCreateOrMoveOptics() = runBlocking {
        val rig = Rig()
        rig.sequence().play(false)
        assertFalse(rig.events.any { it.startsWith("optic:") })
    }
}
