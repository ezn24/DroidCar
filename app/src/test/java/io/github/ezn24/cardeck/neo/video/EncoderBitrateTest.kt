package io.github.ezn24.cardeck.neo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Encoder bitrate formula: pixels x8 (4~12Mbps), 60fps x1.5, overall cap 20Mbps (level 4.0). */
class EncoderBitrateTest {
    @Test
    fun vwMib3CanvasGetsMoreThanOldFormula() {
        // Upstream #26: 1264x640@60 old formula ~7.3Mbps
        val bitrate = encoderBitrate(1264, 640, 60)
        assertEquals(9_707_520, bitrate)
        assertTrue(bitrate > 7_300_000)
    }

    @Test
    fun floorAppliesToSmallCanvas() {
        assertEquals(4_000_000, encoderBitrate(800, 480, 30))
        assertEquals(6_000_000, encoderBitrate(800, 480, 60))
    }

    /** HEVC takes 65% of H.264, floor 3Mbps. */
    @Test
    fun hevcUsesLowerTarget() {
        assertEquals(7_800_000, hevcBitrate(12_000_000))
        assertEquals(3_000_000, hevcBitrate(4_000_000))
    }

    /** Overall cap 12Mbps: high-bitrate peaks make WinPlay back-pressure and stutter (a real iPhone peaks at ~13Mbps). */
    @Test
    fun capsAtTwelveMbps() {
        assertEquals(12_000_000, encoderBitrate(1920, 720, 60))
        assertEquals(12_000_000, encoderBitrate(2560, 1440, 30))
        assertEquals(12_000_000, encoderBitrate(4032, 756, 60))
    }
}
