package io.github.ezn24.cardeck.neo.nav

import io.github.ezn24.cardeck.neo.iap2.RouteGuidanceUpdate.Lane
import kotlin.math.abs
import kotlin.math.roundToInt

/** Recognizes the arrowheads in one Amap lane cell. A fork can contain several heads with different brightness. */
internal object LaneIcon {
    /**
     * Splits a lane strip into one cell per lane (Google Maps' `lane_guidance_list`, 2026-11-21): columns holding
     * arrow pixels form a lane; thin separators (<= 2 px) and the strip's rounded ends are ignored, and runs closer
     * than [GLYPH_GAP] belong to one lane (arrows sharing a stem, e.g. "left + straight"). Returns column ranges.
     */
    fun splitStrip(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): List<IntRange> {
        if (width < 20 || height < 18 || pixels.size != width * height) return emptyList()
        val counts =
            IntArray(width) { x ->
                var n = 0
                for (y in 0 until height) if (arrowPixel(pixels[y * width + x])) n++
                n
            }
        val runs = ArrayList<IntRange>()
        var x = 0
        while (x < width) {
            if (counts[x] >= 2) {
                val start = x
                while (x < width && counts[x] >= 2) x++
                if (x - start > 2 && start > EDGE && x < width - EDGE) runs += start until x
            } else {
                x++
            }
        }
        val lanes = ArrayList<IntRange>()
        for (run in runs) {
            val last = lanes.lastOrNull()
            if (last != null && run.first - last.last <= GLYPH_GAP) {
                lanes[lanes.size - 1] = last.first..run.last
            } else {
                lanes += run
            }
        }
        return lanes
    }

    /**
     * Rows holding the cell's arrows, with a small margin: Google's strip is taller than its arrows (about 2/3 of
     * the height), while [read]'s proportions were measured on Amap cells the arrows fill.
     */
    fun glyphRows(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): IntRange {
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            var n = 0
            for (x in 0 until width) if (arrowPixel(pixels[y * width + x])) n++
            if (n >= 1) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return 0 until height
        val margin = maxOf(2, (bottom - top) / 12)
        return (top - margin).coerceAtLeast(0)..(bottom + margin).coerceAtMost(height - 1)
    }

    /** Grey-to-white pixels: lane arrows (white = recommended, dim grey = not) on a colored or dark strip. */
    private fun arrowPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 255
        val g = (pixel shr 8) and 255
        val b = pixel and 255
        val low = minOf(r, g, b)
        return maxOf(r, g, b) - low < 72 && low >= 74
    }

    private const val EDGE = 14
    private const val GLYPH_GAP = 6

    fun read(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): Lane? {
        if (width < 12 || height < 18 || pixels.size != width * height) return null
        val brightness = IntArray(pixels.size)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 255
            val g = (pixels[i] shr 8) and 255
            val b = pixels[i] and 255
            val low = minOf(r, g, b)
            if (maxOf(r, g, b) - low < 72) brightness[i] = low
        }
        val glyph = Glyph(brightness, width, height)
        // Every lane arrow grows out of the bottom of its cell. A bus-lane / special-lane sign ("巴士" text over a
        // small bus, Amap 2026-11-21) has no such stem and was read as a left turn.
        if (!glyph.hasStem()) return null
        val directions = mutableListOf<Int>()
        val preferred = mutableListOf<Int>()
        val found = mutableListOf<Head>()
        for (axis in axes) {
            val head = glyph.head(axis) ?: continue
            // The sloped sides of a straight / sideways head can pass the diagonal test at the same spot: a diagonal
            // head only counts away from the heads already found (axis-aligned axes come first)
            if (axis.diagonal && found.any { kotlin.math.hypot(it.x - head.x, it.y - head.y) < height * 0.22 }) continue
            found += head
            val angle =
                if (axis.angle == 180) {
                    uTurnExitAngle(width, height, head.x, glyph::on) ?: return null
                } else {
                    axis.angle
                }
            directions += angle
            if (head.highlighted) preferred += angle
        }
        // A slight turn on the side opposite a full turn in the same lane is a sloped edge, not a head (seen on a
        // hooked right-turn icon in Google Maps); real lanes do not combine "slight left" with "right"
        val turns = directions.filter { it == 90 || it == -90 }
        val opposite = directions.filter { d -> (d == 45 || d == -45) && turns.any { it * d < 0 } }
        directions.removeAll(opposite)
        preferred.removeAll(opposite)
        if (directions.isEmpty() || preferred.size > 1) return null
        return Lane(directions, preferred.singleOrNull())
    }

    private data class Axis(
        val x: Int,
        val y: Int,
        val angle: Int,
    ) {
        val diagonal: Boolean get() = x != 0 && y != 0

        /** One step along a diagonal is sqrt(2) px: pixel thresholds shrink by this factor when counted in steps. */
        val scale: Double get() = if (diagonal) 0.7071 else 1.0
    }

    private data class Head(
        val x: Double,
        val y: Double,
        val highlighted: Boolean,
    )

    // The axis points from the arrow tip toward its stem. Positive angles turn right. Diagonals = slight turns
    // (Google Maps' "slight left / right" lanes, 2026-11-21); they are tried after the axis-aligned ones.
    private val axes =
        listOf(
            Axis(0, 1, 0),
            Axis(1, 0, -90),
            Axis(-1, 0, 90),
            Axis(0, -1, 180),
            Axis(1, 1, -45),
            Axis(-1, 1, 45),
        )

    private class Glyph(
        val brightness: IntArray,
        val width: Int,
        val height: Int,
    ) {
        private fun at(
            x: Int,
            y: Int,
        ): Int = if (x in 0 until width && y in 0 until height) brightness[y * width + x] else 0

        fun on(
            x: Int,
            y: Int,
        ): Boolean = at(x, y) >= 74

        /**
         * A continuous vertical run starting in the bottom 40% of the cell and reaching at least 35% of its height.
         * The start is that high because a bus lane that also allows straight/right draws a small bus under the stem
         * (Amap 2026-11-21); a bus sign alone has text above the bus with a gap, so its runs stay short.
         */
        fun hasStem(): Boolean {
            val startBelow = (height * 0.60).roundToInt()
            val need = (height * 0.35).roundToInt()
            for (x in (width * 0.15).roundToInt() until (width * 0.85).roundToInt()) {
                // Every run whose lower end lies in the bottom part counts, not only the lowest one: the bus under a
                // bus-lane arrow is a separate short run below the stem (the lowest-run-only check read it as no stem)
                var y = height - 1
                while (y >= 0) {
                    while (y >= 0 && !on(x, y)) y--
                    if (y < startBelow) break
                    var run = 0
                    while (y >= 0 && on(x, y)) {
                        run++
                        y--
                    }
                    if (run >= need) return true
                }
            }
            return false
        }

        /** Count both wings of a head at a given depth. A stem has no wide, symmetric section. */
        private fun wing(
            x: Int,
            y: Int,
            dx: Int,
            dy: Int,
        ): Int {
            if (!on(x, y)) return 0
            var length = 1
            while (on(x + dx * length, y + dy * length)) length++
            return length
        }

        private fun tipDepth(
            axis: Axis,
            x: Int,
            y: Int,
        ): Int? {
            val h = height * axis.scale
            val stemGap = (h * 0.048).roundToInt().coerceAtLeast(2)
            for (depth in (h * 0.13).roundToInt()..(h * 0.29).roundToInt()) {
                val tail = depth + stemGap
                val tailX = x + axis.x * tail
                val tailY = y + axis.y * tail
                if (!on(tailX, tailY) || !on(tailX + axis.x * 2, tailY + axis.y * 2)) continue

                val footX = x + axis.x * depth
                val footY = y + axis.y * depth
                val left = wing(footX, footY, axis.y, -axis.x)
                val right = wing(footX, footY, -axis.y, axis.x)
                if (minOf(left, right) < h * 0.085 || abs(left - right) > h * 0.095) continue

                val middleX = x + axis.x * (depth / 2)
                val middleY = y + axis.y * (depth / 2)
                val middleLeft = wing(middleX, middleY, axis.y, -axis.x)
                val middleRight = wing(middleX, middleY, -axis.y, axis.x)
                if (minOf(middleLeft, middleRight) < 2 || abs(middleLeft - middleRight) > h * 0.085) continue
                val span = left + right
                if (span < middleLeft + middleRight + h * 0.075) continue

                val stem = wing(tailX, tailY, axis.y, -axis.x) + wing(tailX, tailY, -axis.y, axis.x)
                if (stem >= h * 0.027 && stem <= h * 0.20 && span >= stem + h * 0.083) return depth
            }
            return null
        }

        fun head(axis: Axis): Head? {
            var matches = 0
            var tipX = 0
            var tipY = 0
            var whiteMatches = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (!on(x, y) || on(x - axis.x, y - axis.y)) continue
                    // A sideways head can look like a tiny up/down triangle near its base.
                    // Straight uses the long stem below it (0.55 of the cell: a bus drawn under a bus-lane arrow shortens it); a U-turn returns into the lower half of the cell.
                    if (axis.angle == 0 && !on(x, y + (height * 0.55).roundToInt())) continue
                    if (axis.angle == 180 && y < height * 0.56) continue
                    // A slight turn points up: its tip sits in the upper part of the cell
                    if (axis.diagonal && y > height * 0.55) continue
                    val depth = tipDepth(axis, x, y) ?: continue
                    matches++
                    tipX += x
                    tipY += y
                    val core = at(x + axis.x * (depth / 2), y + axis.y * (depth / 2))
                    if (core >= 190) whiteMatches++
                }
            }
            return if (matches >= 2) Head(tipX.toDouble() / matches, tipY.toDouble() / matches, whiteMatches >= 2) else null
        }
    }
}
