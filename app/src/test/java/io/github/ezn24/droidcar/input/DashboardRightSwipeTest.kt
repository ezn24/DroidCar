package io.github.ezn24.droidcar.input

import io.github.ezn24.droidcar.input.DashboardSwipeGesture.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRightSwipeTest {
    private val slop = 18f

    @Test fun dashboardOnlyAdvancesToAppsOnLeftSwipe() {
        assertTrue(dashboardLeftSwipe(-100f, 2f, 72f))
        assertFalse(dashboardLeftSwipe(100f, 2f, 72f))
        assertFalse(dashboardLeftSwipe(-30f, 2f, 72f))
        assertFalse(dashboardLeftSwipe(-100f, 200f, 72f))
    }

    /** Press does not inject; dragging left past the slop claims it, after which displacement is given segment by segment, a 0,0 lift does not contribute displacement. */
    @Test fun leftDragFollowsFingerAndIgnoresZeroRelease() {
        val swipe = DashboardSwipeGesture()
        assertEquals(Result.CONSUME, swipe.update(0, true, 1400f, 240f, true, slop))
        assertEquals(Result.CONSUME, swipe.update(0, true, 1390f, 242f, false, slop))
        assertEquals(Result.BEGIN_DRAG, swipe.update(0, true, 1370f, 245f, false, slop))
        assertEquals(-30f, swipe.dragDelta, 0f)
        assertEquals(Result.DRAG, swipe.update(0, true, 1300f, 250f, false, slop))
        assertEquals(-70f, swipe.dragDelta, 0f)
        assertEquals(Result.DRAG, swipe.update(0, true, 1320f, 250f, false, slop))
        assertEquals(20f, swipe.dragDelta, 0f)
        assertEquals(Result.END_DRAG, swipe.update(0, false, 0f, 0f, false, slop))
        assertFalse(swipe.tracks(0))
    }

    /** Anti-mistouch: lifting with barely any movement = tap, the caller replays down+up at the start point. */
    @Test fun shortPressBecomesTapAtStartPoint() {
        val swipe = DashboardSwipeGesture()
        assertEquals(Result.CONSUME, swipe.update(0, true, 900f, 200f, true, slop))
        assertEquals(Result.CONSUME, swipe.update(0, true, 905f, 203f, false, slop))
        assertEquals(Result.TAP, swipe.update(0, false, 905f, 203f, false, slop))
        assertEquals(900f, swipe.startX, 0f)
        assertEquals(200f, swipe.startY, 0f)
        assertFalse(swipe.tracks(0))
    }

    /** Vertical swipes and right swipes are handed back to the app (list scrolling and rightward progress-bar drags are unaffected). */
    @Test fun verticalAndRightMovesAreReleasedToTheApp() {
        val swipe = DashboardSwipeGesture()
        swipe.update(0, true, 900f, 200f, true, slop)
        assertEquals(Result.RELEASE, swipe.update(0, true, 902f, 260f, false, slop))
        assertEquals(Result.NONE, swipe.update(0, true, 850f, 300f, false, slop))
        assertEquals(Result.NONE, swipe.update(0, false, 850f, 300f, false, slop))
        assertFalse(swipe.tracks(0))

        swipe.update(0, true, 100f, 200f, true, slop)
        assertEquals(Result.RELEASE, swipe.update(0, true, 160f, 205f, false, slop))
    }

    @Test fun otherSlotsAndDisabledStartsPassThrough() {
        val swipe = DashboardSwipeGesture()
        assertEquals(Result.NONE, swipe.update(0, true, 900f, 200f, false, slop))
        swipe.update(0, true, 900f, 200f, true, slop)
        assertEquals(Result.NONE, swipe.update(1, false, 0f, 0f, false, slop))
        assertTrue(swipe.tracks(0))
        assertEquals(Result.BEGIN_DRAG, swipe.update(0, true, 850f, 205f, false, slop))
    }
}
