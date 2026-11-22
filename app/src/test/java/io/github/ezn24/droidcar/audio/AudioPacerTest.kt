package io.github.ezn24.droidcar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Timing math of the audio pacer and the sync-packet anchor. */
class AudioPacerTest {
    private val tick = 1024L * 1_000_000_000L / 48_000 // one AAC frame at 48 kHz, 21.33 ms

    @Test
    fun shortStallCatchesUpInsteadOfSkipping() {
        assertEquals(0L, AudioSender.resyncSkipPackets(900_000_000L, tick, AudioSender.RESYNC_BEHIND_NANOS))
    }

    @Test
    fun longStallSkipsWholeTicks() {
        // 1.5 s behind = 70 whole ticks of 21.33 ms
        assertEquals(70L, AudioSender.resyncSkipPackets(1_500_000_000L, tick, AudioSender.RESYNC_BEHIND_NANOS))
    }

    @Test
    fun paceAnchorExtrapolatesInSamplesNotPackets() {
        // 48 000 samples after the last sent packet = exactly one second later
        assertEquals(
            5_000_000_000L + 1_000_000_000L,
            AudioSender.paceAnchorNanosOf(sampleTs = 49_024, lastSentTs = 1_024, lastSentNanos = 5_000_000_000L, sampleRate = 48_000),
        )
    }

    @Test
    fun paceAnchorIsNullBeforeTheFirstPacket() {
        assertNull(AudioSender.paceAnchorNanosOf(1_024, lastSentTs = -1, lastSentNanos = 0, sampleRate = 48_000))
    }

    @Test
    fun captureTimeUsesTheMeasuredSlopeOfTheLastTwoAnchors() {
        val sender = AudioSender(host = "127.0.0.1", dataPort = 5000, streamKey = ByteArray(32), log = {})
        assertNull(sender.captureTimeOf(0))
        // Capture ran 1% slow: 1024 samples took 21.55 ms instead of 21.33 ms
        sender.setCaptureAnchorForTest(prevTs = 1_024, prevNanos = 1_000_000_000L, ts = 2_048, nanos = 1_021_550_000L)
        assertEquals(1_043_100_000L, sender.captureTimeOf(3_072))
    }
}
