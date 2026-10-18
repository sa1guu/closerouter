package com.closerouter.app

internal enum class CableStyle { STRAIGHT, CURVED }
internal data class CableRun(val from: Int, val to: Int, val middle: Int? = null)

internal object RackLayout {
    const val CABINET_DEPTH = 3.35f
    const val SHELL_RADIUS = 0.18f
    const val SIDE_DISPLAY_Y_SHIFT = 0.24f
    const val SIDE_DISPLAY_Z_SHIFT = -0.18f
    const val CABLE_START = 0.20f
    const val TOP_FAN_Y = CABINET_DEPTH / 2f + 0.30f
    const val TOP_ROW_Y = 0.44f
    const val TOP_LIGHT_END_X = 2.70f
    const val TOP_LABEL_X = 2.98f
    const val TOP_LABEL_Y = 0.27f
    const val TOP_LABEL_SIZE = 0.26f
    const val TOP_USB_X = 5.15f
    const val TOP_TYPE_C_X = 4.30f
    const val POWER_RADIUS = 0.50f
    const val POWER_TOP_OFFSET = 0.84f

    fun connections(lit: List<Boolean>): List<Pair<Int, Int>> =
        lit.indices.filter { lit[it] }.zipWithNext()

    fun cableRuns(lit: List<Boolean>, extension: FloatArray): List<CableRun> {
        val active = lit.indices.filter { lit[it] }
        val runs = mutableListOf<CableRun>()
        var index = 0
        while (index < active.lastIndex) {
            val from = active[index]
            val middle = active.getOrNull(index + 1)
            val to = active.getOrNull(index + 2)
            if (middle != null && to != null && extension[middle] > 0.004f &&
                extension[from] <= 0.004f && extension[to] <= 0.004f) {
                runs += CableRun(from, to, middle)
                index += 2
            } else {
                runs += CableRun(from, active[index + 1])
                index++
            }
        }
        return runs
    }

    fun arcCubicControls(start: Float, middle: Float, end: Float): Pair<Float, Float> {
        val center = (start + end) * 0.5f
        val outward = (middle - center) * 4f / 3f
        val spread = (end - start) * 0.70f
        return center + outward - spread to center + outward + spread
    }

    fun cableStyle(from: Int, to: Int, fromPopped: Boolean, toPopped: Boolean): CableStyle =
        if (to - from == 1 && !fromPopped && !toPopped) CableStyle.STRAIGHT else CableStyle.CURVED

    fun flowDotCount(style: CableStyle): Int =
        if (style == CableStyle.STRAIGHT) 2 else 3
}
