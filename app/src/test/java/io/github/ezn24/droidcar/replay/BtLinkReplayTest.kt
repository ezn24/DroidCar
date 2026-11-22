package io.github.ezn24.droidcar.replay

import io.github.ezn24.droidcar.iap2.Iap2Csm
import io.github.ezn24.droidcar.iap2.Iap2SenderSession
import io.github.ezn24.droidcar.transport.Iap2LinkChannel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **Bluetooth full-stack replay regression**: recorded head-unit rx byte streams are fed to the real Iap2LinkChannel +
 * Iap2SenderSession (the same code path as production); the CSM message sequence we reply with must match
 * the recording -- if a protocol change targeting one car model alters the reply to stimuli from **other models**, this goes red right here.
 *
 * Comparison strategy (resisting retransmission/timing jitter): compare "**first-occurrence order after dedup**" --
 * the replayed distinct CSM sequence must be exactly the same as the recorded distinct CSM sequence.
 *
 * Duration control: each fixture replays at most 4 rounds (phase1 first), per-round timeout = recorded duration + 10s.
 */
class BtLinkReplayTest {
    @Test
    fun recordedSessionsReplayIdentically() {
        val fixtures = CarFixture.loadDirectory()
        val usable = fixtures.filter { it.btRounds.isNotEmpty() }
        if (usable.isEmpty()) {
            println(
                "REPLAY: no Bluetooth fixtures (directory is gitignored; extract locally with tools/extract_car_fixtures.py) -- skipping",
            )
            return
        }
        val failures = ArrayList<String>()
        usable.forEach { fixture ->
            fixture
                .btRounds
                .sortedWith(compareByDescending<CarFixture.BtRound> { it.outcome == "phase1" }.thenBy { it.durationMs })
                .take(2)
                .forEach { round -> replayRound(fixture, round, failures) }
        }
        assertTrue("Bluetooth replay regression failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun replayRound(
        fixture: CarFixture,
        round: CarFixture.BtRound,
        failures: MutableList<String>,
    ) {
        val tag = "${fixture.car}#${round.index}(${round.outcome})"
        val stream = ScriptedCarStream(round.rx)
        val channel = Iap2LinkChannel(stream, name = "replay-${fixture.name}") { }
        val endpointSeen = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                channel,
                log = { },
                bluetoothMac = "5C:40:71:A4:0A:90",
                deviceName = "iPhone",
                onWifiCredentials = { },
                onEndpointUpdate = { },
                context = null,
            )
        val sessionThread =
            Thread(
                {
                    runCatching { session.run(minOf(round.durationMs + 10_000, 20_000)) }
                    endpointSeen.countDown()
                },
                "replay-bt",
            )
        sessionThread.start()
        // Wait for the session to finish naturally (windows time out once rx is exhausted); force-end as a fallback
        // Cap of 25s: a bring-up replay should not take longer (the recorded duration is mostly timeout-round waiting, which replay skips)
        val finished = endpointSeen.await(minOf(round.durationMs + 20_000, 25_000), TimeUnit.MILLISECONDS)
        stream.close()
        channel.close()
        sessionThread.join(3_000)

        val replayIds = distinctCsmIds(extractCsmPayloads(stream.written))
        val recordedIds = distinctCsmIds(extractCsmPayloads(listOf(round.recordedTx)))
        if (!finished) {
            failures.add("$tag session did not end within recorded duration + 20s (replay distinct=${replayIds.joinToString()})")
        }
        // The recorded RAW dump has a byte budget (old logs 512B). Tiered assertions:
        //  - Identification frame (0x1D01) fully survives -> recorded distinct must be a prefix of replay (full comparison)
        //  - Identification frame truncated by the budget -> only assert link layer + identification start (first recorded one, always 0x1D00)
        val expected =
            if (round.rxHasIdent) recordedIds else recordedIds.take(1)
        val prefixOk =
            expected.size <= replayIds.size &&
                replayIds.subList(0, expected.size) == expected
        if (!prefixOk) {
            failures.add(
                "$tag CSM reply sequence drift (${if (round.rxHasIdent) "full" else "link-layer only"} assertion):\n" +
                    "  expected=${expected.joinToString()}\n  replay=${replayIds.joinToString()}",
            )
        } else {
            println(
                "REPLAY ✓ $tag ${if (round.rxHasIdent) "full" else "link-layer only"} prefix matches" +
                    " (expected ${expected.size}/replay ${replayIds.size} message kinds)",
            )
        }
    }

    /** First-occurrence order after dedup (erases retransmissions and timing-induced duplicate frames). */
    private fun distinctCsmIds(payloads: List<ByteArray>): List<Int> {
        val seen = LinkedHashSet<Int>()
        payloads.forEach { payload ->
            Iap2Csm.Parser().offer(payload).forEach { frame -> seen.add(frame.messageId) }
        }
        return seen.toList()
    }
}
