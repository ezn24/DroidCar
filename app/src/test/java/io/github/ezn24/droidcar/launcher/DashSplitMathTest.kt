package io.github.ezn24.droidcar.launcher

import io.github.ezn24.droidcar.render.CARD_MARGIN_PX
import io.github.ezn24.droidcar.render.DASH_GAP_PX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for the dashboard split geometry: the split ratio is adjusted by dragging the handle, and the geometry derivation (tile size / handle center /
 * music card start) must agree across all four parties (composer drawing, map display resize, music card margin, handle layout).
 * Historically the four parties each wrote the formula bare and caused silent misalignment (see the CARD_MARGIN_PX contract comment); this pins
 * the derivation of [VirtualLauncher.dashSplitGeom].
 */
class DashSplitMathTest {
    private val appLen = 1000

    @Test
    fun dashboardUsesFixedThreeToOneShareAcrossDisplaySizes() {
        for (length in listOf(360, 540, 1000, 1920)) {
            val geometry = VirtualLauncher.dashSplitGeom(length, VirtualLauncher.DASH_SPLIT_DEFAULT_FRACTION)
            val usable = length - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()
            assertEquals(3f / 4f, geometry.fraction, 0.001f)
            assertTrue(kotlin.math.abs(geometry.tileLen - usable * 3f / 4f) < 1f)
        }
    }

    @Test
    fun defaultFractionCentersHandle() {
        // 0.5 makes the handle center = midpoint of the app area (visually consistent with the old midline split)
        val g = VirtualLauncher.dashSplitGeom(appLen, 0.5f)
        assertEquals(500f, g.splitCenter, 0.001f)
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0.2f, VirtualLauncher.clampDashSplitFraction(0.05f), 0.001f)
        assertEquals(0.8f, VirtualLauncher.clampDashSplitFraction(0.95f), 0.001f)
        assertEquals(0.6f, VirtualLauncher.clampDashSplitFraction(0.6f), 0.001f)
        // geom side clamps too (another entry point for the "card dragged to 0" regression)
        assertEquals(0.2f, VirtualLauncher.dashSplitGeom(appLen, 0.05f).fraction, 0.001f)
        assertEquals(0.8f, VirtualLauncher.dashSplitGeom(appLen, 0.99f).fraction, 0.001f)
    }

    @Test
    fun tileLenMatchesUsableShare() {
        val usable = appLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()
        assertEquals((0.2f * usable).toInt(), VirtualLauncher.dashSplitGeom(appLen, 0.2f).tileLen)
        assertEquals((0.8f * usable).toInt(), VirtualLauncher.dashSplitGeom(appLen, 0.8f).tileLen)
    }

    @Test
    fun geometryRelationsHold() {
        // Handle center = tile edge + half handle; music card start = tile edge + whole handle (gap = handle width)
        for (f in listOf(0.2f, 0.35f, 0.5f, 0.66f, 0.8f)) {
            val g = VirtualLauncher.dashSplitGeom(appLen, f)
            assertEquals(DASH_GAP_PX / 2f, g.splitCenter - g.tileEdge, 0.001f)
            assertEquals(DASH_GAP_PX, g.otherStart - g.tileEdge, 0.001f)
        }
    }

    @Test
    fun commitRoundTripsFraction() {
        // The commit's inverse path (handle center stream pixels -> ratio) agrees with geom's forward path:
        // streamPos = main-axis offset + margin + tileLen + half handle -> fraction restored
        val offset = 126f // Hypothetical dock width
        for (f in listOf(0.2f, 0.5f, 0.8f)) {
            val g = VirtualLauncher.dashSplitGeom(appLen, f)
            val streamPos = offset + CARD_MARGIN_PX + g.tileLen + DASH_GAP_PX / 2f
            val derived =
                (streamPos - offset - CARD_MARGIN_PX - DASH_GAP_PX / 2f) /
                    (appLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt())
            assertEquals(f, derived, 0.001f)
        }
    }
}
