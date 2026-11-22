package io.github.ezn24.droidcar.replay

import io.github.ezn24.droidcar.airplay.VirtualIap2Link
import io.github.ezn24.droidcar.iap2.Iap2Csm
import io.github.ezn24.droidcar.iap2.Iap2LinkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Tunnel replay regression**: recorded head-unit tunnel CSMs (IAPLINK rx) are fed one by one to the real
 * [VirtualIap2Link] (MIB3 form); the CSM message ID sequence we reply with must match the recorded
 * tunnel tx sequence (0x4E0C/0x4E0B etc. contain random content, so compare message IDs and first-occurrence order).
 *
 * The head-unit side is played by the engine (reusing the frame-swapping method of VirtualIap2LinkTest); recorded CSM bodies
 * are injected via the engine -- focusing on the control-session state machine, not replaying the link layer.
 */
class TunnelReplayTest {
    @Test
    fun recordedTunnelCsmReplaysIdentically() {
        val fixtures = CarFixture.loadDirectory()
        val usable = fixtures.filter { it.tunnelRx.isNotEmpty() }
        if (usable.isEmpty()) {
            println("REPLAY: no tunnel fixtures -- skipping")
            return
        }
        val failures = ArrayList<String>()
        usable.forEach { fixture -> replay(fixture, failures) }
        assertTrue("Tunnel replay regression failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private class Harness {
        val controlAll = ArrayList<ByteArray>()
        val eventAll = ArrayList<ByteArray>()
        var clock = 0L
        var engineClock = 1L
        val car = Iap2LinkEngine()
        val pending = ArrayList<ByteArray>()
        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = {
                    eventAll.add(it)
                    true
                },
                sendControlIap = {
                    controlAll.add(it)
                    pending.add(it)
                },
                tunnelWriter = { null },
                alive = { false },
                nowMillis = { clock },
            )

        fun exchange() {
            var guard = 0
            while (guard++ < 8) {
                val out = car.takeOutput()
                if (out.isNotEmpty()) link.onCarBytes(out)
                drainPendingToCar()
                engineClock += 600
                clock += 600
                car.advanceTime(engineClock)
                drainPendingToCar()
                if (car.state == Iap2LinkEngine.State.NORMAL && pending.isEmpty()) return
            }
        }

        private fun drainPendingToCar() {
            while (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                batch.forEach { car.feed(it, engineClock) }
            }
        }
    }

    private fun replay(
        fixture: CarFixture,
        failures: MutableList<String>,
    ) {
        val h = Harness()
        h.link.begin()
        h.link.armMib3()
        h.car.start(h.engineClock)
        h.exchange()
        assertEquals("${fixture.car}: link did not reach NORMAL", Iap2LinkEngine.State.NORMAL, h.car.state)

        fixture.tunnelRx.forEach { (messageId, body) ->
            assertTrue(
                "${fixture.car}: injecting 0x${messageId.toString(16)} failed (engine rejected the enqueue)",
                h.car.sendControl(Iap2Csm.encode(messageId, body), h.engineClock),
            )
            h.exchange()
        }

        val replayCore = distinctCsmIds(extractCsmPayloads(h.controlAll))
        val recordedCore = fixture.tunnelTxIds.distinct()
        // Replies recorded by an older version may be fewer than current code's (intentional additions, e.g. the MIB3 burst) --
        // assert "recorded = ordered subsequence of replay": missing/out-of-order/reordered replies go red, intentional additions pass
        val subsequenceOk = isOrderedSubsequence(recordedCore, replayCore)
        if (!subsequenceOk) {
            failures.add(
                "${fixture.car}: tunnel reply sequence drift (recorded should be an ordered subsequence of replay)\n" +
                    "  recorded=${recordedCore.joinToString { "0x%04x".format(it) }}\n" +
                    "  replay=${replayCore.joinToString { "0x%04x".format(it) }}",
            )
        } else {
            println("REPLAY ✓ ${fixture.car} tunnel sequence matches (recorded ${recordedCore.size} ⊆ replay ${replayCore.size})")
        }
    }

    private fun isOrderedSubsequence(
        needle: List<Int>,
        haystack: List<Int>,
    ): Boolean {
        var i = 0
        haystack.forEach { id ->
            if (i < needle.size && needle[i] == id) i += 1
        }
        return i == needle.size
    }

    private fun distinctCsmIds(payloads: List<ByteArray>): List<Int> {
        val seen = LinkedHashSet<Int>()
        payloads.forEach { payload ->
            Iap2Csm.Parser().offer(payload).forEach { frame -> seen.add(frame.messageId) }
        }
        return seen.toList()
    }
}
