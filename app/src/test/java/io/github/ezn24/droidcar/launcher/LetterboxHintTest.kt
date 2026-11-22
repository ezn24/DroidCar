package io.github.ezn24.droidcar.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxHintParseTest {
    /** dumpsys fragment from a real device (Honor Magic7 Pro, CarPlayMap #511), keeping only the relevant lines. */
    private val realDump =
        """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #511 (activities from top to bottom):
          * Task{d47b965 #34012 type=standard A=10241:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen translucent=false embedded=false sz=1}
            isSleeping=false
            topResumedActivity=ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
            * Hist  #0: ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
              packageName=com.autonavi.minimap processName=com.autonavi.minimap
              CurrentConfiguration={1.0 460mcc1mnc [zh_CN_#Hans] masculine ldltr sw494dp w494dp h518dp 200dpi nrml compactNeeded port -touch winConfig={ mBounds=Rect(312, 0 - 929, 648) mAppBounds=Rect(312, 0 - 929, 648) mMaxBounds=Rect(0, 0 - 1241, 648) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen}}
              bounds=Rect(312, 0 - 929, 648)
              overrideOrientation=SCREEN_ORIENTATION_PORTRAIT
              requestedOrientation=SCREEN_ORIENTATION_PORTRAIT
              areBoundsLetterboxed=true
              isLetterboxRunning=true
                letterboxReason=FIXED_ORIENTATION
          Resumed activities in task display areas (from top to bottom):
            Resumed: ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
        Display #510 (activities from top to bottom):
          * Task{74f1ce1 #33999 type=standard A=10296:io.github.ezn24.droidcar U=0 visible=true visibleRequested=true mode=fullscreen translucent=true embedded=false sz=1}
            topResumedActivity=ActivityRecord{199877690 u0 io.github.ezn24.droidcar/.launcher.GridActivity t33999}
            * Hist  #0: ActivityRecord{199877690 u0 io.github.ezn24.droidcar/.launcher.GridActivity t33999}
              CurrentConfiguration={1.0 460mcc1mnc [zh_CN_#Hans] masculine ldltr sw431dp w825dp h431dp 361dpi nrml long compactNeeded land winConfig={ mBounds=Rect(0, 0 - 1861, 972) mAppBounds=Rect(0, 0 - 1861, 972) mMaxBounds=Rect(0, 0 - 1861, 972) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen}}
              bounds=Rect(0, 0 - 1861, 972)
              areBoundsLetterboxed=false
              isLetterboxRunning=false
        """.trimIndent()

    @Test
    fun parsesLetterboxRectOfTopActivityOnTargetDisplay() {
        val activity = parseTopLetterboxActivity(511, realDump, 1241, 648)!!
        assertEquals("com.autonavi.minimap", activity.packageName)
        assertEquals("com.autonavi.minimap/com.autonavi.map.activity.SplashActivity", activity.component)
        assertEquals(511, activity.displayId)
        assertTrue(activity.letterboxed)
        assertEquals("FIXED_ORIENTATION", activity.letterboxReason)
        // Both the inner window and the outer frame come from measured dumpsys values (not estimates): 312px on each side
        assertEquals(PixelBounds(312, 0, 929, 648), activity.inner)
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.full)
        assertEquals(312, activity.inner.left - activity.full.left)
        assertEquals(312, activity.full.right - activity.inner.right)
    }

    @Test
    fun picksTopActivityOfRequestedDisplayNotFirstSection() {
        val activity = parseTopLetterboxActivity(510, realDump, 1861, 972)!!
        assertEquals("io.github.ezn24.droidcar", activity.packageName)
        assertFalse(activity.letterboxed)
        assertNull(activity.letterboxReason)
    }

    @Test
    fun fallsBackToDisplaySizeWhenMaxBoundsMissing() {
        val dump =
            """
            Display #509 (activities from top to bottom):
              * Task{aaa #1 type=standard A=10241:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen translucent=false embedded=false sz=1}
                topResumedActivity=ActivityRecord{bbb u0 com.autonavi.minimap/.MainActivity t1}
                * Hist  #0: ActivityRecord{bbb u0 com.autonavi.minimap/.MainActivity t1}
                  bounds=Rect(312, 0 - 929, 648)
                  areBoundsLetterboxed=true
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
            """.trimIndent()
        val activity = parseTopLetterboxActivity(509, dump, 1241, 648)!!
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.full)
        assertEquals(312, activity.inner.left)
    }

    @Test
    fun ignoresDumpWithoutRequestedDisplay() {
        assertNull(parseTopLetterboxActivity(123, realDump, 1241, 648))
        assertNull(parseTopLetterboxActivity(511, "", 1241, 648))
    }

    @Test
    fun ignoresOtherActivityHistBlocks() {
        val dump =
            """
            Display #511 (activities from top to bottom):
              * Task{aaa #1 type=standard A=10241:com.a U=0 visible=true visibleRequested=true mode=fullscreen translucent=false embedded=false sz=1}
                topResumedActivity=ActivityRecord{bbb u0 com.a/.Main t1}
                * Hist  #0: ActivityRecord{bbb u0 com.a/.Main t1}
                  bounds=Rect(0, 0 - 1241, 648)
                  areBoundsLetterboxed=true
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
                * Hist  #1: ActivityRecord{ccc u0 com.b/.Main t1}
                  bounds=Rect(312, 0 - 929, 648)
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
            """.trimIndent()
        val activity = parseTopLetterboxActivity(511, dump, 1241, 648)!!
        assertEquals("com.a", activity.packageName)
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.inner)
    }
}

class LetterboxHintPolicyTest {
    private fun activity(
        pkg: String = "com.ss.android.article.news",
        letterboxed: Boolean = true,
        reason: String? = "FIXED_ORIENTATION",
    ) = LetterboxActivity(
        packageName = pkg,
        component = "$pkg/.Main",
        displayId = 510,
        letterboxed = letterboxed,
        letterboxReason = reason,
        inner = PixelBounds(312, 0, 929, 648),
        full = PixelBounds(0, 0, 1241, 648),
    )

    @Test
    fun showsOnlyForForceLandscapePackageWithFixedOrientationLetterbox() {
        val enabled = setOf("com.ss.android.article.news")
        assertTrue(shouldShowLetterboxHint(activity(), enabled, emptySet()))
        // Not on the list: the user intends a portrait window, do not disturb
        assertFalse(shouldShowLetterboxHint(activity(pkg = "com.other"), enabled, emptySet()))
        // Letterbox not caused by an orientation lock (aspect ratio etc.): not the scenario of this hint
        assertFalse(shouldShowLetterboxHint(activity(reason = "BOOK_ASPECT_RATIO"), enabled, emptySet()))
        assertFalse(shouldShowLetterboxHint(activity(letterboxed = false), enabled, emptySet()))
    }

    @Test
    fun respectsUserDismissalForCurrentSession() {
        val enabled = setOf("com.ss.android.article.news")
        assertFalse(
            shouldShowLetterboxHint(
                activity(),
                enabled,
                setOf("com.ss.android.article.news"),
            ),
        )
    }

    @Test
    fun prefLeftSideButFallsBackToRightWhenLeftTooNarrow() {
        // Left has room -> left (closer to the driver's seat)
        assertEquals(LetterboxSide.LEFT, chooseLetterboxSide(leftPx = 312f, rightPx = 312f, neededPx = 240f))
        // Left has only a few pixels, right is enough -> right
        assertEquals(LetterboxSide.RIGHT, chooseLetterboxSide(leftPx = 8f, rightPx = 312f, neededPx = 240f))
        // Neither side has room -> do not show (bars so narrow they are indistinguishable from fullscreen landscape)
        assertNull(chooseLetterboxSide(leftPx = 40f, rightPx = 40f, neededPx = 240f))
        // Left is wider but right is also enough: still prefer left
        assertEquals(LetterboxSide.LEFT, chooseLetterboxSide(leftPx = 500f, rightPx = 260f, neededPx = 240f))
    }
}

class LetterboxCardGestureTest {
    @Test
    fun tapOnlyWhenPressStartsAndEndsOnCard() {
        // Press lands on the card -> the card takes over
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, false, true, true))
        // While held, moves are always swallowed and never leak to the app
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, true, true, true))
        // Lift still inside the card -> counts as one tap
        assertEquals(CardGesture.TAP, resolveCardGesture(true, true, false, true))
    }

    @Test
    fun dragOffCardCancelsTapButStillConsumes() {
        // Dragged out of the card after pressing: on lift hit=false -> swallowed but not triggered
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, true, false, false))
    }

    @Test
    fun passesThroughWhenPressStartsOutsideCard() {
        // Press that missed -> hand back to normal routing (inject into the app)
        assertEquals(CardGesture.NOT_HANDLED, resolveCardGesture(true, false, true, false))
        assertEquals(CardGesture.NOT_HANDLED, resolveCardGesture(true, false, false, false))
    }

    @Test
    fun doesNotStealGestureDraggedInFromApp() {
        // App gesture in progress (not on the card at press) dragged into the card area: must not take over,
        // otherwise the app never receives the lift and stays in the pressed state
        assertEquals(
            CardGesture.NOT_HANDLED,
            resolveCardGesture(true, false, true, true, appGestureActive = true),
        )
    }

    @Test
    fun releasesStuckHoldWhenCardDisappears() {
        // Card already collapsed but the gesture is still held (probe cleared the hint): swallow this lift to avoid leaking into an app click
        assertEquals(CardGesture.CONSUME, resolveCardGesture(false, true, false, false))
    }
}
