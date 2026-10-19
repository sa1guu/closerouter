package com.closerouter.app

import kotlinx.coroutines.delay

internal enum class RackPowerStage {
    OFF, POWER, OPTICS, BASE, RECEIVER, FANS, TOP, BAYS, AI, DATA, CABLES;

    fun includes(stage: RackPowerStage): Boolean = ordinal >= stage.ordinal
}

internal object RackStartupTiming {
    const val POWER_MILLIS = 220
    const val BASE_MILLIS = 320
    const val RECEIVER_MILLIS = 1050
    const val FAN_LEAD_MILLIS = 380
    const val TOP_MILLIS = 300
    const val BAY_MILLIS = 240
    const val AI_MILLIS = 2050
    const val DATA_MILLIS = 420
    const val OPTIC_EXIT_MILLIS = 430

    fun opticDuration(progress: Float, inserting: Boolean): Int {
        val remaining = if (inserting) 1f - progress else progress
        val full = if (inserting) RackHardwareLayout.OPTIC_INSERT_MILLIS else OPTIC_EXIT_MILLIS
        return (full * remaining.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
    }
}

internal class RackPowerSequence(
    private val entrance: (Int) -> Float,
    private val resetOptic: (Int) -> Unit,
    private val animateOptic: suspend (Int, Float, Int) -> Unit,
    private val stage: (RackPowerStage) -> Unit,
    private val bays: (Int) -> Unit,
    private val wait: suspend (Int) -> Unit = { delay(it.toLong()) }
) {
    suspend fun play(powered: Boolean) {
        bays(0)
        if (!powered) {
            stage(RackPowerStage.OFF)
            for (k in (0 until RackHardwareLayout.OPTIC_COUNT).reversed()) {
                val progress = entrance(k)
                if (progress > 0f) {
                    animateOptic(k, 0f, RackStartupTiming.opticDuration(progress, false))
                    resetOptic(k)
                }
            }
            return
        }
        stage(RackPowerStage.POWER)
        wait(RackStartupTiming.POWER_MILLIS)
        stage(RackPowerStage.OPTICS)
        for (k in 0 until RackHardwareLayout.OPTIC_COUNT) {
            val progress = entrance(k)
            if (progress < 1f) {
                resetOptic(k)
                animateOptic(k, 1f, RackStartupTiming.opticDuration(progress, true))
            }
        }
        stage(RackPowerStage.BASE)
        wait(RackStartupTiming.BASE_MILLIS)
        stage(RackPowerStage.RECEIVER)
        wait(RackStartupTiming.RECEIVER_MILLIS)
        stage(RackPowerStage.FANS)
        wait(RackStartupTiming.FAN_LEAD_MILLIS)
        stage(RackPowerStage.TOP)
        wait(RackStartupTiming.TOP_MILLIS)
        stage(RackPowerStage.BAYS)
        for (k in 1..BAY_COUNT) {
            bays(k)
            wait(RackStartupTiming.BAY_MILLIS)
        }
        stage(RackPowerStage.AI)
        wait(RackStartupTiming.AI_MILLIS)
        stage(RackPowerStage.DATA)
        wait(RackStartupTiming.DATA_MILLIS)
        stage(RackPowerStage.CABLES)
    }
}
