package io.github.ezn24.cardeck.neo.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistWindowParserTest {
    private fun window(
        type: String,
        extra: String,
        displayId: Int = 0,
    ) = """
  Window #7 Window{1a2b3c u0 com.example/Foo}:
    mDisplayId=$displayId rootTaskId=1 mSession=Session{x}
    mAttrs={(0,0)(fillxfill) sim={adjust=pan} ty=$type fmt=TRANSLUCENT}
    $extra
"""

    @Test
    fun visibleVoiceInteractionWindowIsFoundWithFrame() {
        val dump =
            "WINDOW MANAGER WINDOWS (dumpsys window windows)" +
                window("APPLICATION", "isVisible=true frame=[0,0][1080,2400]") +
                window("VOICE_INTERACTION", "Frames: parent=[0,0][1080,2400] frame=[0,1500][1080,2400] last=[0,0][0,0]\n    isVisible=true")
        val w = parseAssistWindow(dump)
        assertTrue(w.visible)
        assertEquals(1500, w.top)
        assertEquals(2400, w.bottom)
    }

    @Test
    fun startingPlaceholderIsIgnored() {
        val dump = window("VOICE_INTERACTION_STARTING", "isVisible=true frame=[0,0][1080,2400]")
        assertFalse(parseAssistWindow(dump).visible)
    }

    @Test
    fun voiceWindowOnCarDisplayReportsItsDisplay() {
        val dump = window("VOICE_INTERACTION", "isVisible=true frame=[0,0][1080,2400]", displayId = 5)
        val w = parseAssistWindow(dump)
        assertTrue(w.visible)
        assertEquals(5, w.displayId)
    }

    @Test
    fun geminiFloatyActivityOnCarDisplayIsFound() {
        // 2026-11-15 S24 Ultra 实测原文（节选）：Gemini 浮层是 BASE_APPLICATION，开在车机屏 43
        val dump =
            """
  Window #1 Window{a5fe2f u0 com.google.android.googlequicksearchbox/com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity}:
    mDisplayId=43 taskId=18756 mSession=Session{8e8bc8a 12336:u0a10268} mClient=android.os.BinderProxy@41140e
    mAttrs={(0,0)(fillxfill) sim={adjust=resize forwardNavigation} layoutInDisplayCutoutMode=always ty=BASE_APPLICATION fmt=TRANSPARENT wanim=0x103031a
    mViewVisibility=0x0 mHaveFrame=true mObscured=false
    mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
    Frames: parent=[0,0][2628,1080] display=[0,0][2628,1080] frame=[0,0][2628,1080] last=[0,0][2628,1080] insetsChanged=false
    isOnScreen=true
    isVisible=true
  Window #2 Window{4cd48a2 u0 io.github.ezn24.cardeck.neo/io.github.ezn24.cardeck.neo.launcher.GridActivity}:
    mDisplayId=43 taskId=18743
    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION wanim=0x103031a
    isVisible=true
"""
        val w = parseAssistWindow(dump)
        assertTrue(w.visible)
        assertEquals(43, w.displayId)
        assertEquals(18756, w.taskId)
        assertEquals(2628, w.right)
    }

    @Test
    fun ordinaryGoogleAppActivityIsNotAnAssistant() {
        val dump =
            """
  Window #1 Window{1 u0 com.google.android.googlequicksearchbox/com.google.android.apps.search.googleapp.GoogleAppActivity}:
    mDisplayId=0
    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION}
    isVisible=true
"""
        assertFalse(parseAssistWindow(dump).visible)
    }

    @Test
    fun hiddenWindowIsNotVisible() {
        val dump = window("VOICE_INTERACTION", "mHasSurface=false mViewVisibility=0x8 frame=[0,0][1080,2400]")
        assertFalse(parseAssistWindow(dump).visible)
    }

    @Test
    fun legacyVisibilitySignalsCount() {
        val dump = window("VOICE_INTERACTION", "mViewVisibility=0x0 mHasSurface=true mFrame=[0,1600][1080,2400]")
        val w = parseAssistWindow(dump)
        assertTrue(w.visible)
        assertEquals(1600, w.top)
    }
}
