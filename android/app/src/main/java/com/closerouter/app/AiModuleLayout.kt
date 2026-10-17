package com.closerouter.app

internal data class AiPanelBounds(val y0: Float, val y1: Float, val z0: Float, val z1: Float) {
    fun contains(y: Float, z: Float): Boolean = y in y0..y1 && z in z0..z1

    fun expand(side: Float, bottom: Float, top: Float = bottom) =
        AiPanelBounds(y0 - side, y1 + side, z0 - bottom, z1 + top)
}

internal object AiModuleLayout {
    const val DEPTH = 1f
    const val MAX_EXTENSION = 1.15f
    const val SCREW_RADIUS = 0.035f

    val screen = AiPanelBounds(1.02f, 2.52f, 5.5f, 6.82f)
    // The entire original side instrument, including its lower bezel, slides as one rigid face.
    val frame = screen.expand(side = 0.15f, bottom = 0.25f, top = 0.12f)
    val outerBezel = screen.expand(side = 0.055f, bottom = 0.045f)
    val innerBezel = screen.expand(side = 0.025f, bottom = 0.02f)
    val screws = listOf(
        frame.y0 + 0.06f to frame.z0 + 0.06f,
        frame.y1 - 0.06f to frame.z0 + 0.06f,
        frame.y0 + 0.06f to frame.z1 - 0.06f,
        frame.y1 - 0.06f to frame.z1 - 0.06f
    )

    fun extension(progress: Float): Float = progress.coerceIn(0f, MAX_EXTENSION) * DEPTH
}
