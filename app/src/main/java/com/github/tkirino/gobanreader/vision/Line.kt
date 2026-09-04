package com.github.tkirino.gobanreader.vision

import kotlin.math.*

data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val length get() = hypot(x2 - x1, y2 - y1)
    val angleDeg: Double get() {
        val deg = Math.toDegrees(atan2(y2 - y1, x2 - x1))
        return if (deg < 0) deg + 180 else deg
    }
}
