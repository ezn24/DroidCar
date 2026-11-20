package io.github.ezn24.cardeck.neo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编码码率公式：像素 ×8（4~12Mbps），60fps ×1.5，总上限 20Mbps（level 4.0）。 */
class EncoderBitrateTest {
    @Test
    fun vwMib3CanvasGetsMoreThanOldFormula() {
        // 上游 #26：1264×640@60 旧公式 ~7.3Mbps
        val bitrate = encoderBitrate(1264, 640, 60)
        assertEquals(9_707_520, bitrate)
        assertTrue(bitrate > 7_300_000)
    }

    @Test
    fun floorAppliesToSmallCanvas() {
        assertEquals(4_000_000, encoderBitrate(800, 480, 30))
        assertEquals(6_000_000, encoderBitrate(800, 480, 60))
    }

    /** HEVC 取 H.264 的 65%，下限 3Mbps。 */
    @Test
    fun hevcUsesLowerTarget() {
        assertEquals(7_800_000, hevcBitrate(12_000_000))
        assertEquals(3_000_000, hevcBitrate(4_000_000))
    }

    /** 总上限 12Mbps：高码率峰值让 WinPlay 背压卡顿（真 iPhone 峰值 ~13Mbps）。 */
    @Test
    fun capsAtTwelveMbps() {
        assertEquals(12_000_000, encoderBitrate(1920, 720, 60))
        assertEquals(12_000_000, encoderBitrate(2560, 1440, 30))
        assertEquals(12_000_000, encoderBitrate(4032, 756, 60))
    }
}
