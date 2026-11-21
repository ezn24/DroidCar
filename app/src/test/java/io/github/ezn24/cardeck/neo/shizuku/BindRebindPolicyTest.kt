package io.github.ezn24.cardeck.neo.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UserService bind-resend policy (regression pin for the 2026-10-22 Redmi K60E "touch injection inevitably deadlocks").
 *
 * The scene: video streams fine and touch events reach the phone, but injection fails from the very first attempt, with the failure count climbing to
 * 1700+; the log flushes "binding stuck state cleared (binding timed out without callback), retrying immediately" every 2~3 seconds.
 *
 * The root cause is that the judgment window (1.5s) is of the same order as the normal callback delay, and after declaring it dead it calls unbindUserService(remove=true)
 * -- that parameter **kills the remote service process**. So "initiate -> 1.5s declared dead -> kill process -> respawn ->
 * callback is late again" forms a self-sustaining jitter that never converges.
 */
class BindRebindPolicyTest {
    private val timeout = 8_000L

    @Test
    fun idleStateIsReadyToRebind() {
        // No binding currently in progress -> can initiate directly
        assertTrue(shouldRebind(binding = false, waitedMs = 0, stuckTimeoutMs = timeout))
    }

    @Test
    fun freshlyIssuedBindingKeepsWaiting() {
        // Just initiated (including the 1.5s point the old implementation misjudged) must keep waiting -- this is the root of the deadlock
        listOf(0L, 100L, 1_500L, 1_501L, 3_000L, 7_999L).forEach { waited ->
            assertFalse(
                "Waiting ${waited}ms should not be judged stuck (the old implementation declared it dead at 1.5s and killed the service process)",
                shouldRebind(binding = true, waitedMs = waited, stuckTimeoutMs = timeout),
            )
        }
    }

    @Test
    fun onlyGenuinelyStuckBindingIsRebound() {
        assertTrue(shouldRebind(binding = true, waitedMs = timeout, stuckTimeoutMs = timeout))
        assertTrue(shouldRebind(binding = true, waitedMs = timeout * 3, stuckTimeoutMs = timeout))
    }

    @Test
    fun zeroBindingSinceIsTreatedAsJustIssued() {
        // When bindingSince is not recorded (0), it must not be treated as "waited a long time" and resent immediately
        assertFalse(shouldRebind(binding = true, waitedMs = 0, stuckTimeoutMs = timeout))
    }
}
