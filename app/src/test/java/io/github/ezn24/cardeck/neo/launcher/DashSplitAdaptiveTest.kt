package io.github.ezn24.cardeck.neo.launcher

import io.github.ezn24.cardeck.neo.render.CARD_MARGIN_PX
import io.github.ezn24.cardeck.neo.render.DASH_GAP_PX
import io.github.ezn24.cardeck.neo.render.DashMetrics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dashboard layout adapts to head-unit resolution (2026-11-17). */
class DashSplitAdaptiveTest {
    @After
    fun reset() = DashMetrics.configure(1920, 720)

    /** Landscape head unit: the dock takes about 168/1920 of the canvas width; returns (app area width, app area height). */
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
        // Canvases that are both 8:3 have a ratio consistent with the baseline
        val (aw, ah) = landscapeApp(1280, 480)
        assertEquals(0.75f, VirtualLauncher.dashSplitFractionFor(aw, ah, portrait = false), 0.01f)
    }

    @Test
    fun narrowerScreensGiveMediaMoreRoom() {
        val (aw, ah) = landscapeApp(1280, 720) // 16:9
        val f = VirtualLauncher.dashSplitFractionFor(aw, ah, portrait = false)
        assertTrue("The 16:9 map ratio should be clearly below 3:1, actual $f", f in 0.55f..0.66f)
        // Media card shape stays close to the baseline (width/height ~ 0.62)
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

    /** Common head-unit resolutions (support list given by the user on 2026-11-17): the ratio falls in the expected range, media card shape unchanged. */
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
            assertEquals("${res.first}x${res.second} media card shape", 0.62f, mediaAspect, 0.03f)
        }
    }

    @Test
    fun portraitUsesItsOwnAspect() {
        DashMetrics.configure(1080, 1920)
        // Portrait: main axis = app area height, the other axis = width
        val f = VirtualLauncher.dashSplitFractionFor(1920 - 300, 1080, portrait = true)
        assertTrue("The portrait map ratio should be within the clamp range, actual $f", f in 0.55f..0.8f)
    }
}
