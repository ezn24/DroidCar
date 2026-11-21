package io.github.ezn24.cardeck.neo.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** Compose cadence: with new frames follow the target frame rate; within 1 second of going still 33ms; when still for a long time 1 frame per second. */
class ComposeIntervalTest {
    @Test
    fun newFramesFollowTargetFps() {
        assertEquals(16L, composeIntervalMs(hasNew = true, sinceChangeMs = 0, targetFps = 60))
        assertEquals(33L, composeIntervalMs(hasNew = true, sinceChangeMs = 0, targetFps = 30))
    }

    @Test
    fun justSettledKeepsFastHeartbeat() {
        assertEquals(33L, composeIntervalMs(hasNew = false, sinceChangeMs = 500, targetFps = 60))
    }

    @Test
    fun longIdleDropsToOneFramePerSecond() {
        assertEquals(1_000L, composeIntervalMs(hasNew = false, sinceChangeMs = 1_000, targetFps = 60))
        assertEquals(1_000L, composeIntervalMs(hasNew = false, sinceChangeMs = 60_000, targetFps = 30))
    }
}
