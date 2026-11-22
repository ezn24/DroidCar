package io.github.ezn24.droidcar.airplay

import io.github.ezn24.droidcar.iap2.Iap2Csm
import io.github.ezn24.droidcar.iap2.Iap2LinkEngine
import io.github.ezn24.droidcar.iap2.MfiLengthMemory
import io.github.ezn24.droidcar.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIB3 WiFi tunnel handshake (VirtualIap2Link server x bare engine playing the head-unit client).
 *
 * The timing is the real-device behavior AADisplay captured with Frida: phone arm (sends detect once) -> head unit SYN ->
 * phone SYN|ACK -> (link NORMAL) phone 0x1D00 -> head unit 0x1D01 -> phone 0x1D02+0xAA00 ->
 * (0xAA06 -> 0xAA00) -> 0xAA01 -> phone 0xAA02 challenge **20B** -> head unit 0xAA03 -> phone
 * 0xAA05 + burst -> head-unit subscriptions (0x5000/0x4157/0x4154/0x4170) -> matching empty-state replies.
 */

/**
 * Split by link frame header (FF5A + u16 length + 9B header): the engine's single takeOutput may concatenate several frames,
 * markers (6B, not FF5A) are skipped.
 */
private fun splitLinkFrames(chunk: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var off = 0
    while (off + 9 <= chunk.size) {
        if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
            off += 1
            continue
        }
        // Length field = whole frame length (including the 9B header), not the payload length
        val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
        // Frame structure: 9B header (with header checksum) + payload + 1B trailing payload checksum (len = whole frame length);
        // bare ACK/echo frames carry no payload (whole frame exactly 9B) -- skipped rather than aborting, data frames later in the same chunk still parse
        if (frameLen < 9 || off + frameLen > chunk.size) break
        if (frameLen > 9) out.add(chunk.copyOfRange(off + 9, off + frameLen - 1))
        off += frameLen
    }
    return out
}

class VirtualIap2LinkTest {
    private class Harness {
        val controlSent = ArrayList<ByteArray>()
        val allControl = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        val car = Iap2LinkEngine()
        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = {
                    eventSent.add(it)
                    true
                },
                sendControlIap = {
                    controlSent.add(it)
                    allControl.add(it)
                },
                tunnelWriter = { null },
                // Shut down the tick thread: drive deterministically inside the test (no retransmission interference)
                alive = { false },
                nowMillis = { clock },
            )
        var clock = 0L
        var now = 1L

        /** Head unit -> phone: feed bytes and flush the link's outbound. */
        fun carToLink() {
            val out = car.takeOutput()
            if (out.isNotEmpty()) link.onCarBytes(out)
        }

        /** Phone -> head unit: feed the control channel's outbound bytes back to the head-unit engine. */
        fun linkToCar() {
            while (controlSent.isNotEmpty()) {
                val batch = controlSent.toList()
                controlSent.clear()
                batch.forEach { car.feed(it, now) }
            }
        }

        fun exchange() {
            carToLink()
            linkToCar()
            now += 600
            clock += 600
            // Head-unit side drives the timer: after adopting the peer table it sends a bare ACK on timeout (real devices behave the same --
            // in the BT log the head unit replies with an ACK right after our SYN|ACK)
            car.advanceTime(now)
            linkToCar()
        }

        fun exchangeUntilNormal(rounds: Int = 8) {
            repeat(rounds) {
                exchange()
                if (car.state == Iap2LinkEngine.State.NORMAL) return
            }
        }

        /** The head unit sends one CSM; returns the sequence of CSM message IDs the phone then sends over the control channel. */
        fun carSends(
            messageId: Int,
            body: ByteArray = ByteArray(0),
        ): List<Int> {
            val before = controlFrames().size
            assertTrue(car.sendControl(Iap2Csm.encode(messageId, body), now))
            exchange()
            val ids = controlFrames().drop(before).map { it.second }
            return ids
        }

        /** Control channel outbound -> (link frame split) -> complete CSM frame bytes with their message IDs.
         *  Each link frame payload is exactly one CSM: a fresh Parser must be created per frame -- the LSP payload of a SYN|ACK
         *  is not a CSM, and a shared stateful parser would corrupt the boundaries of later frames. */
        fun controlFrames(): List<Pair<ByteArray, Int>> {
            val out = ArrayList<Pair<ByteArray, Int>>()
            for (chunk in allControl) {
                splitLinkFrames(chunk).forEach { payload ->
                    Iap2Csm.Parser().offer(payload).forEach { frame ->
                        out.add(payload to frame.messageId)
                    }
                }
            }
            return out
        }

        /** Complete CSM frame bytes of the latest message with the given ID (for byte-by-byte comparison with Frida constants). */
        fun lastRawFrameOf(messageId: Int): ByteArray? = controlFrames().lastOrNull { it.second == messageId }?.first

        /** Lengths of 0xAA02 challenges sent (param 0 length; in order of appearance when several). */
        fun challengeLengths(): List<Int> =
            controlFrames()
                .filter { it.second == 0xAA02 }
                .map { (raw, _) ->
                    Iap2Csm
                        .parseParams(raw.copyOfRange(6, raw.size))
                        .first()
                        .value.size
                }
    }

    @Test
    fun mib3ArmSendsExactlyOneDetectOnControlChannel() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.link.armMib3()
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
        assertTrue(h.eventSent.isEmpty())
    }

    @Test
    fun preArmMarkerGetsNoReply() {
        val h = Harness()
        h.link.begin()
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertTrue(h.controlSent.isEmpty())
        assertTrue(h.eventSent.isEmpty())
    }

    /**
     * Honda HFT (measured 2026-10-20): the head unit repeatedly sends markers on the event channel at 1Hz, meaning it is stuck in
     * probe state, waiting for **our** session table. From the 2nd consecutive marker we must upgrade to "marker + our table
     * sync packet", and **only over the control channel** -- the head unit does not read outbound writes on the event channel (a trap hit in r6).
     *
     * 2026-10-23 second conclusion: this behavior applies to **all generations** (no longer dispatched by EVENT_LINK) -- Audi
     * likewise requires the link to reach NORMAL, otherwise it is torn down ~10s after RECORD (confirmed by both 0.1.9/0.1.17 logs).
     * The real cause of the earlier "negotiation hangs the Audi UI" was a missing modesChanged re-assertion after negotiation completed.
     */
    @Test
    fun repeatedMarkersUpgradeToControlChannelHandshake() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.EVENT_LINK)
        h.link.armMib3()
        val detect = h.controlSent.size
        // 1st: not judged yet (MIB3 will resend the probe once more)
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals("The first marker must not send extra bytes", detect, h.controlSent.size)
        // 2nd: upgrade to marker + sync packet
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        val sent = h.controlSent.drop(detect)
        assertTrue("Negotiation bytes must be sent after a repeated marker", sent.isNotEmpty())
        val payload = sent.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        assertTrue(
            "The first batch sent must contain the marker (FF 55)",
            payload.size >= 6 && payload[0] == 0xFF.toByte() && payload[1] == 0x55.toByte(),
        )
        assertTrue(
            "The marker must be followed immediately by the FF5A session table (that is what the head unit waits for)",
            payload.size >= 12 && payload[6] == 0xFF.toByte() && payload[7] == 0x5A.toByte(),
        )
        assertTrue(
            "The session table must carry the SYN bit (0x80)",
            (payload[10].toInt() and 0x80) != 0,
        )
        assertTrue("Outbound must never land on the event channel", h.eventSent.isEmpty())
    }

    /**
     * 2026-10-23 second conclusion: **every generation must answer the marker to complete link negotiation**. The head unit's
     * marker is resent endlessly at 1Hz (0.1.17 owner log: far beyond the old comment's "goes silent after 2~5"),
     * without a reply the link never reaches NORMAL and the head unit tears down the whole session ~10s after RECORD (0.1.9 measured
     * 10.17s, 0.1.17 measured 10.18s). The real cause of the earlier "negotiation hangs the Audi UI" was a missing
     * modesChanged re-assertion after negotiation completed (SessionController.scheduleModesReclaim).
     */
    @Test
    fun markersEscalateRegardlessOfGeneration() {
        for (generation in ReceiverProfile.LinkGeneration.entries) {
            val h = Harness()
            h.link.setGeneration(generation)
            h.link.armMib3()
            val detect = h.controlSent.size
            // 1st: not judged yet (the head unit will resend the probe)
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            assertEquals("$generation first marker must not send extra bytes", detect, h.controlSent.size)
            // 2nd: upgrade to marker + session table
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            assertTrue(
                "$generation 2nd marker must trigger negotiation bytes (0.1.17 disproved the 'ignore' approach)",
                h.controlSent.size > detect,
            )
            assertTrue("$generation outbound must not land on the event channel", h.eventSent.isEmpty())
        }
    }

    @Test
    fun fullMib3HandshakeWithBurstAndSubscriptions() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        assertEquals(Iap2LinkEngine.State.NORMAL, h.car.state)
        // detect(1) + SYN|ACK + 0x1D00 have gone over the control channel; the head unit has received 0x1D00
        assertTrue(h.controlFrames().map { it.second }.contains(0x1D00))

        // 0x1D01 → 0x1D02 + 0xAA00
        assertEquals(
            listOf(0x1D02, 0xAA00),
            h.carSends(0x1D01, Iap2Csm.string(0, "Audi_MMI_3315") + Iap2Csm.string(1, "AUDI 5163")),
        )

        // 0xAA06 (auth serial) -> send 0xAA00 again
        assertEquals(listOf(0xAA00), h.carSends(0xAA06, Iap2Csm.string(0, "serial")))

        // 0xAA01 (certificate) -> 0xAA02 challenge 20B (MIB3 real-device length; DiPlay form is 32B)
        val challengeFrames = h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(0xAA02), challengeFrames)
        val challengeBody =
            h
                .lastRawFrameOf(0xAA02)!!
                .copyOfRange(6, h.lastRawFrameOf(0xAA02)!!.size)
                .let { Iap2Csm.parseParams(it).first() }
        assertEquals(20, challengeBody.value.size)

        // 0xAA03 (signature reply) -> 0xAA05 + burst (0x4E0C/0xA100/0x4E0A/0x4E0B/0x4E09/0xFFFA/0xFFF0)
        assertEquals(
            listOf(0xAA05, 0x4E0C, 0xA100, 0x4E0A, 0x4E0B, 0x4E09, 0xFFFA, 0xFFF0),
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96))),
        )

        // Subscription replies: 0x5000 -> 0x5001 empty state; 0x4157 -> 0x4158; 0x4154 -> 0x4155; 0x4170 -> 0x4171
        assertEquals(listOf(0x5001), h.carSends(0x5000))
        assertEquals(listOf(0x4158), h.carSends(0x4157))
        assertEquals(listOf(0x4155), h.carSends(0x4154))
        assertEquals(listOf(0x4171), h.carSends(0x4170))
    }

    @Test
    fun nowPlayingIdleReplyIsTheFridaConstantVerbatim() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "x"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.carSends(0x5000)
        // Byte-for-byte equal to the Frida capture constants -- the field layout exists only in real-device behavior, re-encoding easily drifts
        assertEquals(
            VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE.toList(),
            h.lastRawFrameOf(0x5001)!!.toList(),
        )
        assertEquals(
            VirtualIap2Link.MIB3_COMMUNICATIONS_UPDATE_IDLE.toList(),
            run {
                h.carSends(0x4157)
                h.lastRawFrameOf(0x4158)!!.toList()
            },
        )
    }

    @Test
    fun tunnelMfiUsesRememberedLengthPerCar() {
        // Use an identity different from other tests to avoid in-process memory crosstalk
        val identity = "MemCar MemModel MemMfr tunnel"
        MfiLengthMemory.recordSuccess(null, identity, 32)
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "MemCar") + Iap2Csm.string(1, "MemModel") + Iap2Csm.string(2, "MemMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        // Memory hit: 32B goes first, no longer starting from 20B
        assertEquals(listOf(32), h.challengeLengths())
    }

    @Test
    fun silentChallengeRetriesNextLengthWithinSession() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "SilCar") + Iap2Csm.string(1, "SilMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(20), h.challengeLengths())
        // 20B silent: advance the clock past the 6s window and feed a marker to trigger the tick check (drainLocked)
        h.clock += 7_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(20, 32), h.challengeLengths())
        // 32B gets a reply -> 0xAA05 + burst as usual
        val after = h.controlFrames().size
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        assertTrue(
            "frames=${h.controlFrames().drop(after).map { it.second }}",
            h
                .controlFrames()
                .drop(after)
                .map { it.second }
                .containsAll(listOf(0xAA05, 0x4E0C, 0xFFFA)),
        )
    }

    /**
     * 0x6802 parameter extraction: Honda HFT measured frame `00 06 00 00 00 03 | 00 05 00 01 04`.
     *
     * param 0 is always `00 03` (report type), param 1 is the HID report (0x04 = next track).
     * The old implementation took "the first short parameter" -> always got 00 03 -> bitmap all 0 -> key presses produced no action.
     */
    @Test
    fun hidReportComesFromParamOneNotParamZero() {
        // Written on a single line: ktlint requires "one parameter per line when multiline" for this rule; a single line is closer to the readable form of the original frame
        val body = byteArrayOf(0x00, 0x06, 0x00, 0x00, 0x00, 0x03, 0x00, 0x05, 0x00, 0x01, 0x04)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x04), report?.toList())
    }

    @Test
    fun hidReportFallsBackToLastShortParamWhenOnlyOneGiven() {
        val body = byteArrayOf(0x00, 0x05, 0x00, 0x00, 0x08)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x08), report?.toList())
    }
}
