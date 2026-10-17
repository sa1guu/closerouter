package com.closerouter.app

internal object RackLayout {
    const val CABLE_START = 0.20f
    const val TOP_LABEL_X = 4.02f
    const val TOP_LABEL_Y = 0.20f
    const val TOP_LABEL_SIZE = 0.16f
    const val TOP_DOT_X = 4.70f
    const val TOP_DOT_PITCH = 0.16f
    const val TOP_DOT_COUNT = 5
    const val TOP_DOT_Y = 0.296f

    fun connections(lit: List<Boolean>): List<Pair<Int, Int>> =
        lit.indices.filter { lit[it] }.zipWithNext()
}
