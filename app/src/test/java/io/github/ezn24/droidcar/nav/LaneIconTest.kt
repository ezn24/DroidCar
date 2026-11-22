package io.github.ezn24.droidcar.nav

import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate.Lane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaneIconTest {
    /** Amap cells cut from a WinPlay-session screenshot (2026-11-21), resized to the 46x63 cell the service reads. */
    private fun cell(name: String): Lane? {
        val text =
            javaClass.classLoader!!
                .getResourceAsStream("lanes/$name.txt")!!
                .bufferedReader()
                .readText()
                .trim()
                .split(Regex("[ \\r\\n]+"))
        val w = text[0].toInt()
        val h = text[1].toInt()
        return LaneIcon.read(IntArray(w * h) { (0xFF shl 24) or text[2 + it].toInt(16) }, w, h)
    }

    @Test
    fun busDrawnUnderTheStemStillReadsAsRecommendedStraight() {
        assertEquals(Lane(listOf(0), 0), cell("amap_bus_straight"))
        // Real 46x63 crop from the car display: the bus shortens the arrow, so its stem ends 19 px above the bottom
        assertEquals(Lane(listOf(0), 0), cell("amap_bus_straight_real"))
    }

    @Test
    fun leftPlusUTurnKeepsBothArrows() {
        assertEquals(Lane(listOf(-90, -180), null), cell("amap_left_uturn"))
    }

    @Test
    fun signWithoutStemIsNotALane() {
        // A bus-lane style cell: a short block of "text" near the top and a small pictogram near the bottom, no stem
        val w = 50
        val h = 50
        val px = IntArray(w * h) { 0xFF161C2A.toInt() }
        val grey = 0xFF9AA0A8.toInt()
        for (y in 6..16) for (x in 10..40) px[y * w + x] = grey
        for (y in 30..42) for (x in 14..36) px[y * w + x] = grey
        assertNull(LaneIcon.read(px, w, h))
    }

    @Test
    fun stripSplitsIntoLanesIgnoringSeparatorsAndEnds() {
        // 300x60 strip on a teal card: lane A = two strokes 4 px apart (one lane, shared stem), a 1 px separator,
        // lane B = one stroke, and bright rounded ends at both edges that must not count as lanes
        val w = 300
        val h = 60
        val px = IntArray(w * h) { 0xFF005050.toInt() }

        fun bar(
            x0: Int,
            x1: Int,
            color: Int,
        ) {
            for (y in 10..50) for (x in x0 until x1) px[y * w + x] = color
        }
        val dim = 0xFF8A9A9A.toInt()
        bar(0, 6, -1) // edge
        bar(60, 68, dim)
        bar(72, 80, dim)
        bar(150, 151, dim) // separator
        bar(200, 210, -1)
        bar(294, 300, -1) // edge
        val lanes = LaneIcon.splitStrip(px, w, h)
        org.junit.Assert.assertEquals(listOf(60..79, 200..209), lanes)
        val rows = LaneIcon.glyphRows(px, w, h)
        org.junit.Assert.assertTrue(rows.first in 6..10 && rows.last in 50..54)
    }
}
