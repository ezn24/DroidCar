package io.github.ezn24.cardeck.neo.launcher

import io.github.ezn24.cardeck.neo.render.CARD_MARGIN_PX
import io.github.ezn24.cardeck.neo.render.DASH_GAP_PX
import io.github.ezn24.cardeck.neo.render.DashMetrics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仪表盘版式随车机分辨率自适应（2026-11-17）。 */
class DashSplitAdaptiveTest {
    @After
    fun reset() = DashMetrics.configure(1920, 720)

    /** 横屏车机：dock 约占画布宽 168/1920；返回 (应用区宽, 应用区高)。 */
    private fun landscapeApp(
        w: Int,
        h: Int,
    ): Pair<Int, Int> {
        DashMetrics.configure(w, h)
        val dock = (h * 168f / 720f).toInt()
        return (w - dock) to h
    }

    @Test
    fun baselineIsUnchanged() {
        val (aw, ah) = landscapeApp(1920, 720)
        assertEquals(16, CARD_MARGIN_PX)
        assertEquals(24f, DASH_GAP_PX, 0f)
        assertEquals(0.75f, VirtualLauncher.dashSplitFractionFor(aw, ah, portrait = false), 0.01f)
    }

    @Test
    fun metricsScaleWithShortSide() {
        landscapeApp(1280, 480)
        assertEquals(10, CARD_MARGIN_PX)
        assertEquals(16f, DASH_GAP_PX, 0.01f)
        // 同为 8:3 的画布，比例与基准一致
        val (aw, ah) = landscapeApp(1280, 480)
        assertEquals(0.75f, VirtualLauncher.dashSplitFractionFor(aw, ah, portrait = false), 0.01f)
    }

    @Test
    fun narrowerScreensGiveMediaMoreRoom() {
        val (aw, ah) = landscapeApp(1280, 720) // 16:9
        val f = VirtualLauncher.dashSplitFractionFor(aw, ah, portrait = false)
        assertTrue("16:9 地图比例应明显小于 3:1，实际 $f", f in 0.55f..0.66f)
        // 媒体卡形状仍接近基准（宽/高 ≈ 0.62）
        val usable = aw - CARD_MARGIN_PX * 2 - DASH_GAP_PX
        val mediaW = (1f - f) * usable
        assertEquals(0.62f, mediaW / (ah - CARD_MARGIN_PX * 2), 0.03f)
    }

    @Test
    fun extremesAreClamped() {
        val (wideW, wideH) = landscapeApp(2560, 640)
        assertEquals(0.8f, VirtualLauncher.dashSplitFractionFor(wideW, wideH, portrait = false), 0.001f)
        val (narrowW, narrowH) = landscapeApp(800, 600)
        assertEquals(0.55f, VirtualLauncher.dashSplitFractionFor(narrowW, narrowH, portrait = false), 0.001f)
    }

    /** 常见车机分辨率（2026-11-17 用户给出的支持清单）：比例落在预期、媒体卡形状不变。 */
    @Test
    fun commonHeadUnitResolutions() {
        val expected =
            mapOf(
                (800 to 480) to 0.56f,
                (1280 to 720) to 0.60f,
                (960 to 540) to 0.60f,
                (1920 to 720) to 0.75f,
                (1280 to 488) to 0.74f,
            )
        for ((res, fraction) in expected) {
            val (aw, ah) = landscapeApp(res.first, res.second)
            val f = VirtualLauncher.dashSplitFractionFor(aw, ah, portrait = false)
            assertEquals("${res.first}x${res.second}", fraction, f, 0.015f)
            val usable = aw - CARD_MARGIN_PX * 2 - DASH_GAP_PX
            val mediaAspect = (1f - f) * usable / (ah - CARD_MARGIN_PX * 2)
            assertEquals("${res.first}x${res.second} 媒体卡形状", 0.62f, mediaAspect, 0.03f)
        }
    }

    @Test
    fun portraitUsesItsOwnAspect() {
        DashMetrics.configure(1080, 1920)
        // 竖屏：主轴 = 应用区高，另一轴 = 宽
        val f = VirtualLauncher.dashSplitFractionFor(1920 - 300, 1080, portrait = true)
        assertTrue("竖屏地图比例应在钳制域内，实际 $f", f in 0.55f..0.8f)
    }
}
