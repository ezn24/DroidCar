package io.github.ezn24.cardeck.neo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conditions under which the auto dim curtain takes effect.
 *
 * 2026-10-19 user feedback: "after the dim curtain is enabled, even after the session has disconnected, it still starts the dim curtain after a while".
 * The root cause was that attach scheduled a 45-second timer at app startup, while the head-unit-side disconnect goes through
 * `VideoSender.onEnded` -- which does not release VirtualLauncher, so the timer fired as usual.
 *
 * Now the auto dim curtain must satisfy all of: setting enabled + session active + attached (overlay permission held).
 * If any one is unmet the curtain must not show; manual "fake screen-off" is not limited by the session (explicit user action).
 */
class ScreenDimmerTest {
    @Test
    fun autoDimRequiresActiveSession() {
        assertFalse(
            "The dim curtain must never show again after the session ends (this bug)",
            ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = false, attached = true),
        )
        assertTrue(
            ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = true, attached = true),
        )
    }

    @Test
    fun autoDimRequiresSettingEnabled() {
        assertFalse(ScreenDimmer.shouldAutoDim(enabled = false, sessionActive = true, attached = true))
    }

    @Test
    fun autoDimRequiresAttachment() {
        // Without the overlay permission windowManager is null, and showDim cannot add a window either
        assertFalse(ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = true, attached = false))
    }

    @Test
    fun allConditionsMustHold() {
        val cases =
            listOf(
                Triple(true, true, true) to true,
                Triple(true, true, false) to false,
                Triple(true, false, true) to false,
                Triple(true, false, false) to false,
                Triple(false, true, true) to false,
                Triple(false, true, false) to false,
                Triple(false, false, true) to false,
                Triple(false, false, false) to false,
            )
        cases.forEach { (input, expected) ->
            val (enabled, session, attached) = input
            assertTrue(
                "enabled=$enabled session=$session attached=$attached expected $expected",
                ScreenDimmer.shouldAutoDim(enabled, session, attached) == expected,
            )
        }
    }
}
