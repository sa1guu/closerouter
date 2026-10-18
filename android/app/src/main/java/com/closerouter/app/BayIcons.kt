package com.closerouter.app

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

internal object BayIcons {
    private fun parse(data: String): Path = PathParser().parsePathString(data).toPath()

    val lightning = parse("M14 1 L4 13 H10 L8 23 L21 9 H14 Z")
    val chevron = parse("M7 3 L16 12 L7 21")
    val heart = parse("M12 21 C10 19 2 13 2 7 C2 1 9 0 12 6 C15 0 22 1 22 7 C22 13 14 19 12 21 Z")
}
