package com.closerouter.app

internal data class OpticCableOutlet(val x: Float, val y: Float, val z: Float)

internal enum class OpticState(val moduleExtension: Float, val cableExtension: Float) {
    CONNECTED(0f, 0f),
    CABLE_UNPLUGGED(0f, 1f),
    MODULE_WITHDRAWN(1f, 1f);

    fun next(): OpticState = entries[(ordinal + 1) % entries.size]
}

internal object RackHardwareLayout {
    const val DRIVE_COUNT = 7
    const val DRIVE_X0 = 0.50f
    const val DRIVE_PITCH = 0.75f
    const val DRIVE_THICKNESS = 0.60f
    const val DRIVE_Y0 = 0.58f
    const val DRIVE_Y1 = RackLayout.CABINET_DEPTH - 0.47f
    const val DRIVE_HEIGHT = 1.36f
    const val DRIVE_TRAVEL = 0.90f
    const val CAVITY_DEPTH = 1.50f

    const val BASE_BOTTOM = -0.37f
    const val OPTIC_COUNT = 6
    const val OPTIC_PITCH = 0.72f
    const val OPTIC_WIDTH = 0.54f
    const val OPTIC_Z0 = -0.30f
    const val OPTIC_Z1 = 0.045f
    const val OPTIC_FRONT = RackLayout.CABINET_DEPTH + 0.80f
    const val OPTIC_TRAVEL = 2.62f
    const val OPTIC_BODY_LENGTH = 2.65f
    const val OPTIC_HEAD_LENGTH = 0.62f
    const val OPTIC_CONNECTOR_END = 0.98f
    const val OPTIC_CONNECTOR_INSERTION = 0.18f
    const val OPTIC_CABLE_TRAVEL = 0.52f
    const val OPTIC_STRAND_COUNT = 2
    const val OPTIC_STRAND_PITCH = 0.115f
    const val OPTIC_SOCKET_HALF_WIDTH = 0.145f
    const val OPTIC_PLUG_HALF_WIDTH = 0.11f
    const val OPTIC_INSERT_MILLIS = 700
    const val OPTIC_ENTRANCE_DISTANCE = 11f

    fun opticFlowEnabled(running: Boolean, state: OpticState, entrance: Float,
        moduleExtension: Float, cableExtension: Float): Boolean =
        running && state == OpticState.CONNECTED && entrance >= 1f &&
            kotlin.math.abs(moduleExtension) <= 0.001f && kotlin.math.abs(cableExtension) <= 0.001f

    fun opticEntranceDepth(progress: Float): Float =
        (1f - progress.coerceIn(0f, 1f)) * OPTIC_ENTRANCE_DISTANCE

    fun driveX(index: Int): Float = DRIVE_X0 + index * DRIVE_PITCH
    fun opticX(index: Int): Float = (6f - (OPTIC_COUNT - 1) * OPTIC_PITCH) * 0.5f + index * OPTIC_PITCH
    fun opticFront(progress: Float): Float = OPTIC_FRONT + 0.10f + progress.coerceIn(0f, 1.08f) * OPTIC_TRAVEL
    fun opticBodyBack(moduleProgress: Float, entranceProgress: Float): Float = maxOf(
        OPTIC_FRONT + 0.012f - opticEntranceDepth(entranceProgress),
        opticFront(moduleProgress) - OPTIC_BODY_LENGTH
    )
    fun opticConnectorOrigin(moduleProgress: Float, cableProgress: Float): Float =
        opticFront(moduleProgress) - OPTIC_CONNECTOR_INSERTION +
            cableProgress.coerceIn(0f, 1.08f) * OPTIC_CABLE_TRAVEL
    fun opticStrandX(index: Int, strand: Int): Float =
        opticX(index) + (strand - (OPTIC_STRAND_COUNT - 1) * 0.5f) * OPTIC_STRAND_PITCH
    fun opticCableOutlet(index: Int, moduleProgress: Float, cableProgress: Float = 0f, strand: Int = 0) = OpticCableOutlet(
        opticStrandX(index, strand), opticConnectorOrigin(moduleProgress, cableProgress) + OPTIC_CONNECTOR_END,
        (OPTIC_Z0 + OPTIC_Z1) * 0.5f
    )
}
