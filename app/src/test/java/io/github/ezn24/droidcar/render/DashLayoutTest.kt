package io.github.ezn24.droidcar.render

import io.github.ezn24.droidcar.launcher.VirtualLauncher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dashboard layout (side by side / Dock at bottom / portrait), 2026-11-17. */
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
        assertEquals(DashLayout.DOCK_BOTTOM, DashLayout.resolve(DashLayout.PREF_AUTO, 800, 480)) // Old "auto" value
        // The removed old "music at bottom" value is treated as the default (Dock at bottom)
        assertEquals(DashLayout.DOCK_BOTTOM, DashLayout.resolve(DashLayout.PREF_MUSIC_BOTTOM, 960, 540))
        assertEquals(DashLayout.DOCK_BOTTOM, DashLayout.resolve(DashLayout.PREF_DOCK_BOTTOM, 1280, 720))
        // Portrait head units are unaffected by the preference
        assertEquals(DashLayout.PORTRAIT, DashLayout.resolve(DashLayout.PREF_DOCK_BOTTOM, 1080, 1920))
        assertEquals(DashLayout.PORTRAIT, DashLayout.resolve("garbage", 720, 1280))
    }

    @Test
    fun layoutFlagsAreIndependent() {
        assertEquals(true to false, DashLayout.DOCK_BOTTOM.dockAtBottom to DashLayout.DOCK_BOTTOM.splitVertical)
        assertEquals(true to true, DashLayout.PORTRAIT.dockAtBottom to DashLayout.PORTRAIT.splitVertical)
    }

    /** Portrait head unit (dock at bottom, dockWidth passed as 0): geometry keeps the old behavior. */
    @Test
    fun portraitGeometryUnchanged() {
        DashMetrics.configure(1080, 1920)
        val media = mediaPaneBounds(1080, 1500, 0, 1000f, portrait = true)
        assertEquals(CARD_MARGIN_PX.toFloat(), media.left, 0f)
    }

    /** Landscape with Dock at bottom: side by side, cards start at x = margin and end at the app area (above the dock). */
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
