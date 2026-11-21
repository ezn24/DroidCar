package io.github.ezn24.cardeck.neo.nav

import kotlin.math.roundToInt

/**
 * A U-turn arrow's incoming shaft is on the opposite side of its downward-pointing head.
 * Look for a continuous shaft well outside the head so antialiasing cannot decide its sign.
 */
internal fun uTurnExitAngle(
    width: Int,
    height: Int,
    headX: Double,
    foreground: (Int, Int) -> Boolean,
): Int? {
    val gap = height * 0.20
    val minLength = (height * 0.22).roundToInt()
    var left = 0
    var right = 0
    for (x in 0 until width) {
        if (x > headX - gap && x < headX + gap) continue
        var continuous = 0
        for (y in (height * 0.48).roundToInt() until height) {
            continuous = if (foreground(x, y)) continuous + 1 else 0
            if (continuous >= minLength) {
                if (x < headX) left++ else right++
                break
            }
        }
    }
    return when {
        right >= 2 && right > left * 2 -> -180
        left >= 2 && left > right * 2 -> 180
        else -> null
    }
}
