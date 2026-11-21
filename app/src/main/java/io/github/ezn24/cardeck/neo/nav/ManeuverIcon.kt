package io.github.ezn24.cardeck.neo.nav

import io.github.ezn24.cardeck.neo.iap2.RouteGuidanceUpdate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal fun NavInfo.withIcon(result: ManeuverIcon.Result?): NavInfo {
    if (result == null || (maneuverType != null && maneuverType != result.type)) return this
    return copy(
        maneuverType = maneuverType ?: result.type,
        junction = junction ?: result.junction,
        exitAngle = exitAngle ?: result.exitAngle,
        junctionAngles = if (junctionAngles.isEmpty()) result.junctionAngles else junctionAngles,
    )
}

/**
 * Turns a navigation app's maneuver arrow (a picture, no text) into an iAP2 ManeuverType: the head unit only takes
 * the type number and draws its own arrow, so the image itself cannot be forwarded.
 *
 * Pure geometry, no bundled icon art:
 *   1. foreground = pixels that differ clearly from the border (background) luminance;
 *   2. arrowhead = the thickest part of the shape (chamfer distance transform maximum: the head triangle is wider
 *      than the stem in every map style seen);
 *   3. heading = from the stem just behind the head (a ring 1.8-3 head radii around it) to the head center;
 *   4. the heading angle (0 = up, + = right) picks straight / slight / turn / sharp / U-turn;
 *   5. an enclosed hole of real size = roundabout (junction type 1).
 * Synthetic arrows: straight 1 deg, slight 31-47, turn 89, sharp 107, U-turn 135. Real icons may need threshold
 * tuning (collected with NAV-DUMP samples). Returns null when there is no clear shape.
 */
object ManeuverIcon {
    data class Result(
        val type: Int?,
        val junction: Int,
        val angle: Int,
        val exitAngle: Int? = null,
        val junctionAngles: List<Int> = emptyList(),
    )

    const val JUNCTION_INTERSECTION = 0
    const val JUNCTION_ROUNDABOUT = 1

    /** [argb] row-major pixels of the cropped icon. Large crops are subsampled to at most [MAX_SIDE]. */
    fun classify(
        argb: IntArray,
        width: Int,
        height: Int,
    ): Result? {
        if (width < 8 || height < 8 || argb.size < width * height) return null
        val step = maxOf(1, maxOf(width, height) / MAX_SIDE)
        val w = width / step
        val h = height / step
        val background = borderLuminance(argb, width, step, w, h)
        val mask = foreground(argb, width, step, w, h, background) ?: return null
        val roundabout = holeArea(mask, w, h) > w * h * HOLE_FRACTION
        // A left-right symmetric picture is not a turn: every turn, U-turn and fork arrow is lopsided, while Amap's
        // tunnel entrance (a double arch, 2026-11-21) was taken for a U-turn because its "head" points down. A plain
        // straight arrow is symmetric too, which gives the same answer.
        if (!roundabout && mirrorSimilarity(mask, w, h) >= SYMMETRIC) {
            return Result(RouteGuidanceUpdate.TYPE_STRAIGHT, JUNCTION_INTERSECTION, 0)
        }
        val dist = chamfer(mask, w, h)
        val max = dist.maxOrNull() ?: 0
        if (max < MIN_HEAD) return null
        var hx = 0.0
        var hy = 0.0
        var count = 0
        for (i in dist.indices) {
            if (dist[i] >= max * 0.85) {
                hx += i % w
                hy += i / w
                count++
            }
        }
        hx /= count
        hy /= count
        val radius = max / 3.0
        var nx = 0.0
        var ny = 0.0
        var ringCount = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            val d = hypot(i % w - hx, i / w - hy)
            if (d >= 1.8 * radius && d <= 3.0 * radius) {
                nx += i % w
                ny += i / w
                ringCount++
            }
        }
        if (ringCount == 0) return null
        nx /= ringCount
        ny /= ringCount
        val angle = Math.toDegrees(atan2(hx - nx, -(hy - ny))).toInt()
        if (!roundabout && abs(angle) < 62) {
            val preferred = ForkIcon.preferredAngle(w, h, background) { x, y -> luminance(argb[y * step * width + x * step]) }
            if (preferred != null) {
                val type = if (preferred < 0) RouteGuidanceUpdate.TYPE_KEEP_LEFT else RouteGuidanceUpdate.TYPE_KEEP_RIGHT
                return Result(type, JUNCTION_INTERSECTION, angle, preferred, listOf(-30, 30))
            }
        }
        val type =
            if (roundabout) {
                RouteGuidanceUpdate.TYPE_ENTER_ROUNDABOUT
            } else {
                typeOf(angle)
            }
        val exitAngle =
            if (type == RouteGuidanceUpdate.TYPE_U_TURN) {
                uTurnExitAngle(w, h, hx) { x, y -> mask[y * w + x] }
            } else {
                null
            }
        return Result(type, if (roundabout) JUNCTION_ROUNDABOUT else JUNCTION_INTERSECTION, angle, exitAngle)
    }

    /** Overlap (IoU) of the shape with its mirror image around the shape's own center column. */
    internal fun mirrorSimilarity(
        mask: BooleanArray,
        w: Int,
        h: Int,
    ): Double {
        var minX = w
        var maxX = -1
        for (i in mask.indices) {
            if (!mask[i]) continue
            val x = i % w
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        if (maxX < minX) return 0.0
        var both = 0
        var either = 0
        for (y in 0 until h) {
            for (x in minX..maxX) {
                val a = mask[y * w + x]
                val b = mask[y * w + (minX + maxX - x)]
                if (a && b) both++
                if (a || b) either++
            }
        }
        return if (either == 0) 0.0 else both.toDouble() / either
    }

    internal fun typeOf(angle: Int): Int {
        val a = abs(angle)
        val right = angle > 0
        return when {
            a < 15 -> RouteGuidanceUpdate.TYPE_STRAIGHT
            a < 62 -> if (right) RouteGuidanceUpdate.TYPE_SLIGHT_RIGHT else RouteGuidanceUpdate.TYPE_SLIGHT_LEFT
            a < 100 -> if (right) RouteGuidanceUpdate.TYPE_RIGHT else RouteGuidanceUpdate.TYPE_LEFT
            a < 125 -> if (right) RouteGuidanceUpdate.TYPE_SHARP_RIGHT else RouteGuidanceUpdate.TYPE_SHARP_LEFT
            else -> RouteGuidanceUpdate.TYPE_U_TURN
        }
    }

    private fun luminance(pixel: Int): Int {
        val alpha = pixel ushr 24
        if (alpha < 128) return -1
        return (((pixel shr 16) and 0xFF) * 3 + ((pixel shr 8) and 0xFF) * 6 + (pixel and 0xFF)) / 10
    }

    private fun borderLuminance(
        argb: IntArray,
        stride: Int,
        step: Int,
        w: Int,
        h: Int,
    ): Int {
        fun lum(
            x: Int,
            y: Int,
        ) = luminance(argb[(y * step) * stride + x * step])
        val border = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) {
            border += lum(x, 0)
            border += lum(x, h - 1)
        }
        for (y in 0 until h) {
            border += lum(0, y)
            border += lum(w - 1, y)
        }
        border.sort()
        return border[border.size / 2]
    }

    private fun foreground(
        argb: IntArray,
        stride: Int,
        step: Int,
        w: Int,
        h: Int,
        background: Int,
    ): BooleanArray? {
        val mask = BooleanArray(w * h)
        var on = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val l = luminance(argb[y * step * stride + x * step])
                // Transparent background (notification icons): the shape is whatever is opaque
                if (l >= 0 && (background < 0 || abs(l - background) > CONTRAST)) {
                    mask[y * w + x] = true
                    on++
                }
            }
        }
        // Nothing, or nearly everything (crop missed the icon): no answer
        return if (on < w * h / 50 || on > w * h * 3 / 4) null else mask
    }

    /** 3-4 chamfer distance to the nearest background pixel (outside the crop counts as background). */
    private fun chamfer(
        mask: BooleanArray,
        w: Int,
        h: Int,
    ): IntArray {
        val inf = Int.MAX_VALUE / 2
        val d = IntArray(w * h) { if (mask[it]) inf else 0 }

        fun at(
            x: Int,
            y: Int,
        ) = if (x in 0 until w && y in 0 until h) d[y * w + x] else 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (d[i] == 0) continue
                d[i] = minOf(d[i], at(x, y - 1) + 3, at(x - 1, y) + 3, at(x - 1, y - 1) + 4, at(x + 1, y - 1) + 4)
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (d[i] == 0) continue
                d[i] = minOf(d[i], at(x, y + 1) + 3, at(x + 1, y) + 3, at(x + 1, y + 1) + 4, at(x - 1, y + 1) + 4)
            }
        }
        return d
    }

    /** Background pixels not reachable from the crop border (the inside of a roundabout ring). */
    private fun holeArea(
        mask: BooleanArray,
        w: Int,
        h: Int,
    ): Int {
        val reached = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()

        fun push(
            x: Int,
            y: Int,
        ) {
            if (x !in 0 until w || y !in 0 until h) return
            val i = y * w + x
            if (mask[i] || reached[i]) return
            reached[i] = true
            stack.addLast(i)
        }
        for (x in 0 until w) {
            push(x, 0)
            push(x, h - 1)
        }
        for (y in 0 until h) {
            push(0, y)
            push(w - 1, y)
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            push(x + 1, y)
            push(x - 1, y)
            push(x, y + 1)
            push(x, y - 1)
        }
        var holes = 0
        for (i in mask.indices) if (!mask[i] && !reached[i]) holes++
        return holes
    }

    private const val MAX_SIDE = 96

    /** Synthetic turn arrows score 0.3-0.6, a straight arrow or double arch above 0.9. */
    private const val SYMMETRIC = 0.88
    private const val CONTRAST = 70
    private const val MIN_HEAD = 6
    private const val HOLE_FRACTION = 0.02
}
