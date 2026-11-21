package io.github.ezn24.cardeck.neo.nav

import kotlin.math.abs
import kotlin.math.roundToInt

/** Recognizes a bright route and a separate, dimmer branch converging on the same stem. */
internal object ForkIcon {
    fun preferredAngle(
        width: Int,
        height: Int,
        background: Int,
        luminance: (Int, Int) -> Int,
    ): Int? {
        if (background !in 0..120) return null
        val bright = (0 until height).map { y -> (0 until width).filter { luminance(it, y) >= background + 105 } }
        val top = bright.indexOfFirst { it.isNotEmpty() }
        val span = bright.indexOfLast { it.isNotEmpty() } - top
        if (top < 0 || span < height * 0.55) return null

        fun brightCenter(
            start: Double,
            end: Double,
        ): Double = (top + (span * start).roundToInt()..top + (span * end).roundToInt()).flatMap { bright[it] }.average()
        val stem = brightCenter(0.74, 1.0)
        val tip = brightCenter(0.10, 0.32)
        if (!stem.isFinite() || !tip.isFinite() || abs(stem - tip) < width * 0.065) return null

        val side = if (tip < stem) 1 else -1
        val centers = mutableListOf<Double>()
        for (y in top + (span * 0.07).roundToInt() until top + (span * 0.65).roundToInt()) {
            val route = bright[y]
            if (route.isEmpty()) continue
            val edge = if (side > 0) route.last() else route.first()
            val length = maxOf(2, (width * 0.06).roundToInt())
            var run = 0
            var longest = 0
            var center = 0.0
            for (x in 0 until width) {
                val separated = (x - edge) * side > width * 0.04 && (x - stem) * side > width * 0.055
                val dim = luminance(x, y) - background in 26..85
                run = if (separated && dim) run + 1 else 0
                if (run >= length && run > longest) {
                    longest = run
                    center = x - (run - 1) / 2.0
                }
            }
            if (longest > 0) centers += center
        }
        if (centers.size < maxOf(8.0, span * 0.22)) return null
        val convergence = (centers.take(4).average() - centers.takeLast(4).average()) * side
        return if (convergence >= width * 0.045) -side * 30 else null
    }
}
