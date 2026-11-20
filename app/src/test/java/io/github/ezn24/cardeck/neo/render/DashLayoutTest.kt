package io.github.ezn24.cardeck.neo.render

import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仪表盘版面（左右分屏 / Dock 在底部 / 竖屏），2026-11-17。 */
class DashLayoutTest {
    @After
    fun reset() = DashMetrics.configure(1920, 720)

    @Test
    fun wideScreensIgnoreThePreference() {
        for (pref in listOf(DashLayout.PREF_SIDE, DashLayout.PREF_MUSIC_BOTTOM, DashLayout.PREF_DOCK_BOTTOM, DashLayout.PREF_AUTO)) {
            assertEquals(pref, DashLayout.SIDE_BY_SIDE, DashLayout.resolve(pref, 1920, 720))
            assertEquals(pref, DashLayout.SIDE_BY_SIDE, DashLayout.resolve(pref, 1280, 488))
        }
    }

    @Test
    fun narrowScreensFollowThePreferenceDefaultingToDockBottom() {
        assertEquals(DashLayout.SIDE_BY_SIDE, DashLayout.resolve(DashLayout.PREF_SIDE, 800, 480))
        assertEquals(DashLayout.DOCK_BOTTOM, DashLayout.resolve(DashLayout.PREF_AUTO, 800, 480)) // 旧「自动」值
        // 已移除的「音乐在底部」旧值按默认（Dock 在底部）处理
        assertEquals(DashLayout.DOCK_BOTTOM, DashLayout.resolve(DashLayout.PREF_MUSIC_BOTTOM, 960, 540))
        assertEquals(DashLayout.DOCK_BOTTOM, DashLayout.resolve(DashLayout.PREF_DOCK_BOTTOM, 1280, 720))
        // 竖屏车机不受偏好影响
        assertEquals(DashLayout.PORTRAIT, DashLayout.resolve(DashLayout.PREF_DOCK_BOTTOM, 1080, 1920))
        assertEquals(DashLayout.PORTRAIT, DashLayout.resolve("garbage", 720, 1280))
    }

    @Test
    fun layoutFlagsAreIndependent() {
        assertEquals(true to false, DashLayout.DOCK_BOTTOM.dockAtBottom to DashLayout.DOCK_BOTTOM.splitVertical)
        assertEquals(true to true, DashLayout.PORTRAIT.dockAtBottom to DashLayout.PORTRAIT.splitVertical)
    }

    /** 竖屏车机（dock 在底部，dockWidth 传 0）几何保持旧行为。 */
    @Test
    fun portraitGeometryUnchanged() {
        DashMetrics.configure(1080, 1920)
        val media = mediaPaneBounds(1080, 1500, 0, 1000f, portrait = true)
        assertEquals(CARD_MARGIN_PX.toFloat(), media.left, 0f)
    }

    /** Dock 在底部的横屏：左右分屏，卡片从 x=边距起、到应用区（dock 之上）为止。 */
    @Test
    fun dockBottomLandscapeSideBySide() {
        DashMetrics.configure(800, 480)
        val appH = 480 - 80
        val f = VirtualLauncher.dashSplitFractionFor(800, appH, portrait = false)
        val geom = VirtualLauncher.dashSplitGeom(800, f)
        val media = mediaPaneBounds(800, appH, 0, geom.splitCenter, portrait = false)
        val map = mapPaneBounds(800, appH, 0, geom.splitCenter, portrait = false, swapped = false)
        assertEquals(CARD_MARGIN_PX.toFloat(), map.left, 0f)
        assertTrue(map.right < media.left)
        assertEquals(appH - CARD_MARGIN_PX.toFloat(), media.bottom, 0f)
    }
}
