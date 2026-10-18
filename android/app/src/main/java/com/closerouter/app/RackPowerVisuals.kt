package com.closerouter.app

internal object RackPowerVisuals {
    const val SHUTDOWN_SECONDS = 0.85f

    fun bootProgress(seconds: Float): Float = ((seconds - 0.15f) / 1.8f).coerceIn(0f, 1f)

    fun shutdownProgress(seconds: Float): Float = (seconds / SHUTDOWN_SECONDS).coerceIn(0f, 1f)

    fun shutdownAperture(seconds: Float): Float {
        val remaining = 1f - shutdownProgress(seconds)
        return remaining * remaining
    }

    fun receiverHeight(progress: Float): Float {
        val p = (progress / 0.9f).coerceIn(0f, 1f)
        return 2.2f * (1f - p * p * (3f - 2f * p))
    }

    fun advanceFanSpeed(current: Float, powered: Boolean, dt: Float): Float {
        val duration = if (powered) 1.4f else 2.6f
        val step = dt.coerceAtLeast(0f) / duration
        return (if (powered) current + step else current - step).coerceIn(0f, 1f)
    }
}
