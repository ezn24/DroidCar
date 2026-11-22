package io.github.ezn24.droidcar.nav

import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Synthetic maneuver arrows (drawn here, no app icon art): stem up from the bottom, a bend, a head at the end. */
class ManeuverIconTest {
    private fun arrow(
        turnDeg: Double,
        roundabout: Boolean = false,
        transparent: Boolean = false,
        dark: Boolean = false,
    ): Triple<IntArray, Int, Int> {
        val size = 96
        val background =
            if (transparent) {
                0
            } else if (dark) {
                0xFFF0F0F0.toInt()
            } else {
                0xFF141414.toInt()
            }
        val ink = if (dark) 0xFF1E1E1E.toInt() else -1
        val px = IntArray(size * size) { background }
        val cx = size / 2.0
        var bendY = size * 0.55
        val a = Math.toRadians(turnDeg)
        val len = size * 0.32
        val head = 26.0
        val ringY = size * 0.35

        fun segDist(
            x: Double,
            y: Double,
            x1: Double,
            y1: Double,
            x2: Double,
            y2: Double,
        ): Double {
            val dx = x2 - x1
            val dy = y2 - y1
            val t = (((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
        }

        fun sign(
            px1: Double,
            py1: Double,
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
        ) = (px1 - bx) * (ay - by) - (ax - bx) * (py1 - by)
        if (roundabout) bendY = ringY + 18
        val endX = cx + len * sin(a)
        val endY = bendY - len * cos(a)
        val tip = Pair(endX + head * 0.9 * sin(a), endY - head * 0.9 * cos(a))
        val c1 = Pair(endX + head / 2 * cos(a), endY + head / 2 * sin(a))
        val c2 = Pair(endX - head / 2 * cos(a), endY - head / 2 * sin(a))
        for (y in 0 until size) {
            for (x in 0 until size) {
                val fx = x + 0.5
                val fy = y + 0.5
                var on =
                    segDist(fx, fy, cx, size - 6.0, cx, bendY) <= 5 || segDist(fx, fy, cx, bendY, endX, endY) <= 5
                if (roundabout && kotlin.math.abs(hypot(fx - cx, fy - ringY) - 18) <= 5) on = true
                val d1 = sign(fx, fy, tip.first, tip.second, c1.first, c1.second)
                val d2 = sign(fx, fy, c1.first, c1.second, c2.first, c2.second)
                val d3 = sign(fx, fy, c2.first, c2.second, tip.first, tip.second)
                val neg = d1 < 0 || d2 < 0 || d3 < 0
                val pos = d1 > 0 || d2 > 0 || d3 > 0
                if (!(neg && pos)) on = true
                if (on) px[y * size + x] = ink
            }
        }
        return Triple(px, size, size)
    }

    private fun typeOf(
        deg: Double,
        roundabout: Boolean = false,
        transparent: Boolean = false,
        dark: Boolean = false,
    ): Int? {
        val (px, w, h) = arrow(deg, roundabout, transparent, dark)
        return ManeuverIcon.classify(px, w, h)?.type
    }

    @Test
    fun directions() {
        assertEquals(RouteGuidanceUpdate.TYPE_STRAIGHT, typeOf(0.0))
        assertEquals(RouteGuidanceUpdate.TYPE_SLIGHT_RIGHT, typeOf(40.0))
        assertEquals(RouteGuidanceUpdate.TYPE_SLIGHT_LEFT, typeOf(-40.0))
        assertEquals(RouteGuidanceUpdate.TYPE_RIGHT, typeOf(90.0))
        assertEquals(RouteGuidanceUpdate.TYPE_LEFT, typeOf(-90.0))
        assertEquals(RouteGuidanceUpdate.TYPE_SHARP_RIGHT, typeOf(135.0))
        assertEquals(RouteGuidanceUpdate.TYPE_SHARP_LEFT, typeOf(-135.0))
    }

    @Test
    fun styles() {
        // Day theme (dark arrow on light card) and notification icons (arrow on transparent)
        assertEquals(RouteGuidanceUpdate.TYPE_RIGHT, typeOf(90.0, dark = true))
        assertEquals(RouteGuidanceUpdate.TYPE_LEFT, typeOf(-90.0, transparent = true))
    }

    @Test
    fun roundaboutIsJunctionOne() {
        val (px, w, h) = arrow(90.0, roundabout = true)
        val result = ManeuverIcon.classify(px, w, h)!!
        assertEquals(ManeuverIcon.JUNCTION_ROUNDABOUT, result.junction)
        assertEquals(RouteGuidanceUpdate.TYPE_ENTER_ROUNDABOUT, result.type)
    }

    @Test
    fun emptyOrFullCropGivesNothing() {
        assertNull(ManeuverIcon.classify(IntArray(64 * 64) { 0xFF101010.toInt() }, 64, 64))
        assertNull(ManeuverIcon.classify(IntArray(64 * 64) { -1 }, 64, 64))
    }

    @Test
    fun uTurnSideIsOppositeTheIncomingShaft() {
        val left = uTurnExitAngle(46, 63, 12.0) { x, y -> x in 32..38 && y >= 20 }
        val right = uTurnExitAngle(46, 63, 33.0) { x, y -> x in 7..13 && y >= 20 }
        assertEquals(-180, left)
        assertEquals(180, right)
        assertNull(uTurnExitAngle(46, 63, 22.0) { x, y -> x in 18..25 && y >= 20 })
    }

    @Test
    fun angleBands() {
        assertEquals(RouteGuidanceUpdate.TYPE_U_TURN, ManeuverIcon.typeOf(-150))
        assertEquals(RouteGuidanceUpdate.TYPE_STRAIGHT, ManeuverIcon.typeOf(5))
    }

    @Test
    fun symmetricTunnelArchIsStraightNotUTurn() {
        // Double arch like Amap's tunnel entrance: two nested inverted-U strokes open at the bottom
        val size = 96
        val px = IntArray(size * size) { 0xFF141414.toInt() }
        val cx = size / 2.0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x + 0.5 - cx
                for ((radius, top) in listOf(34.0 to 30.0, 20.0 to 44.0)) {
                    val onArc = y + 0.5 < top && kotlin.math.abs(hypot(dx, y + 0.5 - top) - radius) <= 4
                    val onLeg = y + 0.5 >= top && y < size - 6 && kotlin.math.abs(kotlin.math.abs(dx) - radius) <= 4
                    if (onArc || onLeg) px[y * size + x] = -1
                }
            }
        }
        assertEquals(RouteGuidanceUpdate.TYPE_STRAIGHT, ManeuverIcon.classify(px, size, size)?.type)
    }
}
