package io.github.ezn24.cardeck.neo.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two link engines complete the handshake and data round trip by directly exchanging output bytes (no threads, no I/O). */
class Iap2LinkEngineTest {
    @Test
    fun handshakeReachesNormalOnBothSides() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()

        phone.start(0)
        accessory.start(0)

        exchange(phone, accessory)
        exchange(phone, accessory)
        exchange(phone, accessory)

        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(Iap2LinkEngine.State.NORMAL, accessory.state)
        assertEquals(4, accessory.peerSynchronization?.maxOutgoing)
        assertTrue(phone.peerSynchronization?.sessions?.any { it.id == Iap2LinkEngine.SESSION_CONTROL } == true)
    }

    /** The initial sequence number is injectable: the first SYN uses it (real cars pass a random value; Ford SYNC+ does not accept a fixed 99). */
    @Test
    fun initialSequenceIsUsedForFirstSyn() {
        val phone = Iap2LinkEngine(initialSequence = 200)
        phone.start(0)
        assertEquals(200, parseFrames(phone.takeOutput()).first().sequence)
        repeat(50) { assertTrue(Iap2LinkEngine.randomInitialSequence() in 0..255) }
    }

    /**
     * Cadillac CT5 form (upstream #27): after the link is NORMAL the head unit sends consecutive markers asking for renegotiation.
     * The phone replies "marker + new-sequence SYN", then echoes the head unit's new SYN (SYN|ACK); the link stays NORMAL.
     */
    @Test
    fun repeatedPeerMarkersInNormalTriggerRenegotiation() {
        val phone = Iap2LinkEngine().apply { renegotiateOnPeerMarker = true }
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)
        val before = parseFrames(phone.takeOutput()).lastOrNull()?.sequence

        val t = tick()
        phone.feed(Iap2LinkEngine.MARKER, t)
        assertTrue("A single marker must not trigger renegotiation", phone.takeOutput().isEmpty())
        phone.feed(Iap2LinkEngine.MARKER, t + 1_000)
        val out = phone.takeOutput()
        assertArrayEquals("The reply must start with a marker", Iap2LinkEngine.MARKER, out.copyOfRange(0, 6))
        val syn = parseFrames(out).single()
        assertTrue("Must be a pure SYN", syn.control and 0x80 != 0 && syn.control and 0x40 == 0)
        if (before != null) assertTrue("The renegotiation SYN must use a new sequence number", syn.sequence != before)

        // The head unit replies with its own new SYN -> the phone echoes SYN|ACK, still NORMAL
        val carRestart = Iap2LinkEngine(initialSequence = 52)
        carRestart.start(0)
        phone.feed(carRestart.takeOutput(), t + 1_100)
        val echo = parseFrames(phone.takeOutput())
        assertTrue("Must echo SYN|ACK", echo.any { it.control and 0xC0 == 0xC0 && it.ack == 52 })
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
    }

    /** When not enabled (tunnel/test-bench default), a marker in NORMAL is always dropped, behavior identical to the old version. */
    @Test
    fun peerMarkersIgnoredWhenRenegotiationDisabled() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)
        phone.takeOutput()
        val t = tick()
        phone.feed(Iap2LinkEngine.MARKER, t)
        phone.feed(Iap2LinkEngine.MARKER, t + 500)
        assertTrue(phone.takeOutput().isEmpty())
    }

    /**
     * Event channel form: the head unit only repeatedly sends markers and not a single sync packet (Honda HFT measured 2026-10-20,
     * 60 markers in 60 seconds), meaning it is stuck in probe state, waiting for **our** session table.
     * When already NEGOTIATING, [Iap2LinkEngine.initiate] must still be able to emit "marker + FF5A SYN",
     * after which both sides converge to NORMAL -- this is the entry of the track-skip key path.
     */
    @Test
    fun initiateFromNegotiatingSendsMarkerAndSynThenReachesNormal() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        // Both sides are silently standing by (only sending markers, not starting negotiation)
        phone.enterNegotiating()
        accessory.enterNegotiating()

        phone.initiate(0)
        val out = phone.takeOutput()
        assertArrayEquals("The first frame must be a marker", Iap2LinkEngine.MARKER, out.copyOfRange(0, 6))
        val frames = parseFrames(out)
        assertEquals("The marker must be followed immediately by one sync packet", 1, frames.size)
        assertTrue("The sync packet control must carry the SYN bit (0x80)", frames[0].control and 0x80 != 0)

        // Head-unit side receives "marker + our-table SYN" -> replies SYN|ACK (this is the progress that should appear in the log)
        accessory.feed(out, tick())
        assertEquals("The head-unit side must enter NORMAL after receiving our session table", Iap2LinkEngine.State.NORMAL, accessory.state)
        phone.feed(accessory.takeOutput(), tick())
        assertEquals("Actual phone=${phone.state}", Iap2LinkEngine.State.NORMAL, phone.state)
        assertTrue(
            "Both sides must adopt the other's session table",
            phone.peerSynchronization != null && accessory.peerSynchronization != null,
        )
    }

    /**
     * Must be able to stop once rounds are exhausted: endless 500ms pings on the control channel would flood the head unit's CarPlay stack,
     * so the upper layer reopens per round and calls [Iap2LinkEngine.stopNegotiationRetries] after rounds are exhausted.
     * After stopping it must produce no further outbound bytes, and the state stays NEGOTIATING (waiting for the head unit to send CSM itself).
     */
    @Test
    fun stopNegotiationRetriesSilencesResends() {
        val phone = Iap2LinkEngine()
        phone.enterNegotiating()
        phone.initiate(0)
        phone.takeOutput()

        phone.advanceTime(1_000L)
        assertTrue("Before stopping it must still resend marker/sync packets", phone.takeOutput().isNotEmpty())

        phone.stopNegotiationRetries()
        phone.advanceTime(60_000L)
        assertTrue("After stopping there must be no outbound bytes", phone.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
    }

    @Test
    fun controlPayloadRoundTripBothDirections() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val phoneToAccessory = Iap2Csm.encode(0x1D00)
        assertTrue(phone.sendControl(phoneToAccessory, 1))
        exchange(phone, accessory)
        val receivedAtAccessory = pollControls(accessory)
        assertEquals(1, receivedAtAccessory.size)
        assertArrayEquals(phoneToAccessory, receivedAtAccessory[0])

        val accessoryToPhone = Iap2Csm.encode(0x1D01, Iap2Csm.string(0, "CarPlay HU"))
        assertTrue(accessory.sendControl(accessoryToPhone, 2))
        exchange(phone, accessory)
        val receivedAtPhone = pollControls(phone)
        assertEquals(1, receivedAtPhone.size)
        assertArrayEquals(accessoryToPhone, receivedAtPhone[0])
    }

    @Test
    fun duplicateFrameIsReAcknowledgedNotRedelivered() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val payload = Iap2Csm.encode(0x1D02)
        assertTrue(phone.sendControl(payload, 1))
        // Intercept the data frames the phone outputs and feed them to the accessory twice
        val dataFrame = phone.takeOutput()
        accessory.feed(dataFrame, 2)
        accessory.feed(dataFrame, 3)

        assertEquals(1, pollControls(accessory).size)
    }

    @Test
    fun windowBacklogFlushesAfterAcknowledgement() {
        val phone = Iap2LinkEngine(maxOutgoing = 2)
        val accessory = Iap2LinkEngine(maxOutgoing = 8)
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val frames = (0 until 5).map { Iap2Csm.encode(0x5000 + it) }
        frames.forEach { assertTrue(phone.sendControl(it, 10)) }
        // Window 2: only the first 2 frames go out immediately, the rest are queued; flushed by handleAcknowledgement after ACK
        var now = 11L
        while (pollControls(accessory).size < 5) {
            exchange(phone, accessory)
            now += 1
            phone.advanceTime(now)
            accessory.advanceTime(now)
            assertTrue(!phone.isDead && !accessory.isDead)
        }
    }

    // ---- Negotiation detail regressions ----

    /**
     * SYN-ACK must use a **new** send sequence number (the peer validates against `SYN.seq + 1`).
     *
     * 2026-10-19 BMW 87866 real-car log: our SYN and SYN-ACK both had seq=99, the head unit judged it a duplicate frame and
     * dropped it; all 14 negotiation rounds timed out, 133 SYNs never got a single ACK back. catplay uses `take_own_seq()`
     * to consume a new sequence number; this locks that behavior in.
     */
    @Test
    fun synAckUsesFreshSequenceNumber() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput() // marker + our SYN (seq=99)

        phone.feed(synFrame(peerSequence = 161), 1)
        val frames = parseFrames(phone.takeOutput())
        val synAck = frames.single { it.control == 0xC0 }

        assertEquals("ack must echo the peer's SYN sequence number", 161, synAck.ack)
        assertEquals("SYN-ACK sequence number must be SYN's plus 1", 100, synAck.sequence)
    }

    /**
     * **Every** SYN during negotiation must get a SYN|ACK.
     *
     * BMW reopens negotiation with a new sequence number every retry round; the old implementation only echoed on first adoption and replied to the rest with bare ACKs,
     * so the head unit could never finish negotiating. catplay `on_recv_neg_host` sends SYN+ACK for every SYN.
     */
    @Test
    fun everySynGetsASynAckReply() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        // The head unit reopens negotiation three rounds in a row with different sequence numbers (replicating the log 161 -> 191 -> 40)
        val sequences = listOf(161, 191, 40)
        val replies = ArrayList<Pair<Int, Int>>() // (synAckSeq, ack)
        sequences.forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            parseFrames(phone.takeOutput())
                .filter { it.control == 0xC0 }
                .forEach { replies += it.sequence to it.ack }
        }

        assertEquals("Every SYN should get a SYN|ACK", sequences.size, replies.size)
        assertEquals(sequences, replies.map { it.second })
        // Sequence numbers increase one by one (99 -> 100 -> 101 -> 102)
        assertEquals(listOf(100, 101, 102), replies.map { it.first })
    }

    /** A byte-level resend of the same SYN must be idempotent: the whole frame pair is replayed as-is, without advancing the sequence number or producing a new one. */
    @Test
    fun retransmittedSynKeepsTheSameEcho() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        phone.feed(synFrame(peerSequence = 77), 1)
        val first = phone.takeOutput()
        phone.feed(synFrame(peerSequence = 77), 2)
        val again = phone.takeOutput()

        assertArrayEquals("Retransmitting the same SYN should replay as-is", first, again)
        assertEquals(100, parseFrames(first).single { it.control == 0xC0 }.sequence)
    }

    /** A bare ACK must not collide in sequence number with an already-sent SYN|ACK (BMW drops same-sequence frames as duplicates). */
    @Test
    fun ackSequenceNeverDuplicatesTheSynAck() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        val emitted = ArrayList<Int>()
        listOf(161, 191, 40).forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            emitted += parseFrames(phone.takeOutput()).map { it.sequence }
        }
        assertEquals("Sequence numbers must be strictly increasing without duplicates", emitted.size, emitted.distinct().size)
        assertEquals(emitted.sorted(), emitted)
    }

    // ---- Utilities ----

    private class ParsedFrame(
        val control: Int,
        val sequence: Int,
        val ack: Int,
        val frameLength: Int,
    )

    /** Parses all FF5A frames in an output (skipping the leading marker). */
    private fun parseFrames(bytes: ByteArray): List<ParsedFrame> {
        val frames = ArrayList<ParsedFrame>()
        var offset = 0
        while (offset + 9 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte() || bytes[offset + 1] != 0x5A.toByte()) {
                offset++
                continue
            }
            val frame = parseFrame(bytes, offset)
            frames += frame
            offset += frame.frameLength
        }
        return frames
    }

    /** Parses one FF5A frame (length includes the 9-byte header + payload + payload checksum). */
    private fun parseFrame(
        bytes: ByteArray,
        offset: Int,
    ): ParsedFrame {
        require(bytes[offset] == 0xFF.toByte() && bytes[offset + 1] == 0x5A.toByte()) {
            "offset=$offset is not an FF5A frame header"
        }
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        return ParsedFrame(
            control = bytes[offset + 4].toInt() and 0xFF,
            sequence = bytes[offset + 5].toInt() and 0xFF,
            ack = bytes[offset + 6].toInt() and 0xFF,
            frameLength = length,
        )
    }

    /**
     * Builds a head-unit-side SYN frame (including the marker).
     *
     * The frame header is **9 bytes**: `FF5A | u16 length | u8 control | u8 seq | u8 ack | u8 session | u8 header checksum`,
     * so seq is at in-frame offset 5 and the header checksum at offset 8 (not 7 -- that byte is the session ID).
     */
    private fun synFrame(peerSequence: Int): ByteArray {
        val engine = Iap2LinkEngine()
        engine.start(0)
        val raw = engine.takeOutput()
        val synOffset = 6 // The leading marker is 6 bytes
        raw[synOffset + 5] = peerSequence.toByte()
        // Header checksum = complement of the sum of the first 8 bytes
        var sum = 0
        for (index in synOffset until synOffset + 8) sum += raw[index].toInt() and 0xFF
        raw[synOffset + 8] = (-sum and 0xFF).toByte()
        return raw
    }

    private fun exchange(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        b.feed(a.takeOutput(), tick())
        a.feed(b.takeOutput(), tick())
    }

    private fun exchangeUntilNormal(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        var guard = 0
        while ((a.state != Iap2LinkEngine.State.NORMAL || b.state != Iap2LinkEngine.State.NORMAL) &&
            guard++ < 10
        ) {
            exchange(a, b)
        }
    }

    private fun pollControls(engine: Iap2LinkEngine): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            when (val event = engine.pollEvent() ?: return out) {
                is Iap2LinkEngine.Event.Control -> out += event.bytes
                is Iap2LinkEngine.Event.Dead -> error("engine died: ${event.reason}")
            }
        }
    }

    private var clock = 0L

    private fun tick(): Long = ++clock
}
