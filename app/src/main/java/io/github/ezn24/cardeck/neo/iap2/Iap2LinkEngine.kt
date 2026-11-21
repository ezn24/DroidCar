package io.github.ezn24.cardeck.neo.iap2

import java.io.ByteArrayOutputStream

/** Send window declared when the phone echoes SYN|ACK (same 0x7F as a real iPhone / AADisplay). */
private const val PHONE_DECLARED_MAX_OUTGOING = 0x7F

/**
 * iAP2 link engine (sender implementation; the link layer protocol is symmetric, this implementation works as the "initiator").
 *
 * Handshake (wireless mode, phone is the initiator; DiPlay/xcertplay openWireless waits for the phone with
 * initiateNegotiation=false):
 *  1. start() sends the marker `FF 55 02 00 EE 10`, then immediately the sync packet (FF5A frame,
 *     control=0x80, payload = SynchronizationPayload)
 *  2. After receiving the marker the peer enters negotiation and sends its own sync packet; each side ACKs a received sync packet
 *  3. An ACK received while negotiating enters NORMAL (control session 10 usable)
 *
 * Data frame: control=0x40 (piggybacked ACK), seq = send sequence (u8 wrapping, initial 99),
 * ack = latest in-order sequence received, sessionId=10. The peer marker is naturally dropped by the frame header scan.
 *
 * Protocol semantics follow reference/xcertplay transport/Iap2LinkEngine.kt (independent rewrite).
 * Simplifications (RFCOMM is a reliable ordered stream): no out-of-order buffering and no proactive EAK; duplicate frame
 * detection, the send window and the retransmit timer are kept.
 */
class Iap2LinkEngine(
    private val maxOutgoing: Int = 4,
    private val retransmissionTimeoutMillis: Long = 4_000L,
    private val maxRetransmissions: Int = 4,
    /**
     * Our first link sequence number. The default 99 only keeps tests/replays comparable; real-car Bluetooth links pass
     * [randomInitialSequence]:
     * a real iPhone randomizes every time, and Ford SYNC+ 2.0 never answers a fixed-99 SYN but works once the value changes
     * (owner-measured, upstream shaonialife/cardeck#25).
     */
    initialSequence: Int = DEFAULT_INITIAL_SEQUENCE,
) {
    enum class State { IDLE, NEGOTIATING, NORMAL, DEAD }

    data class SessionDescriptor(
        val id: Int,
        val kind: Int,
        val version: Int,
    )

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * 3)
            out.write(SYNCHRONIZATION_VERSION)
            out.write(maxOutgoing)
            writeU16(out, maxLength)
            writeU16(out, retransmissionTimeoutMillis)
            writeU16(out, acknowledgementTimeoutMillis)
            out.write(maxRetransmissions)
            out.write(maxAcknowledgements)
            sessions.forEach {
                out.write(it.id)
                out.write(it.kind)
                out.write(it.version)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? {
                if (bytes.size < 10 || u8(bytes[0]) != SYNCHRONIZATION_VERSION) return null
                val sessions = ArrayList<SessionDescriptor>()
                var offset = 10
                while (offset + 3 <= bytes.size) {
                    sessions +=
                        SessionDescriptor(
                            u8(bytes[offset]),
                            u8(bytes[offset + 1]),
                            u8(bytes[offset + 2]),
                        )
                    offset += 3
                }
                return SynchronizationPayload(
                    maxOutgoing = u8(bytes[1]),
                    maxLength = readU16(bytes, 2),
                    retransmissionTimeoutMillis = readU16(bytes, 4),
                    acknowledgementTimeoutMillis = readU16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxAcknowledgements = u8(bytes[9]),
                    sessions = sessions,
                )
            }
        }
    }

    sealed class Event {
        class Control(
            val bytes: ByteArray,
        ) : Event()

        class Dead(
            val reason: String?,
        ) : Event()
    }

    private class Packet(
        var sequence: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long = 0,
        val awaitAck: Boolean = true,
        /** Link session to send on; null = the control session (all traffic except file transfer). */
        val sessionId: Int? = null,
    )

    var state: State = State.IDLE
        private set
    var peerSynchronization: SynchronizationPayload? = null
        private set

    private var sentSequence = initialSequence and 0xFF
    private var lastSentAcknowledged: Int? = null
    private var lastReceivedInOrder = 0

    /**
     * Peer control session ID adopted (decided when the peer sync payload is received).
     * Audi real-car byte-level measurement (2026-10-09): the head unit (MIB3) sends SYN as initiator declaring the
     * session table {1:control, 2:EA}; after the phone echoes its table it talks on **session 1**,
     * and CSM sent on session 10 is silently dropped (the link layer still ACKs, which is very misleading).
     * Test benches DiPlay/xcertplay declare 10, which matches our default table after adoption, so behavior is unchanged.
     */
    private var adoptedPeerControlSessionId: Int? = null

    /** Peer sync payload adopted (for negotiation echo / diagnostics). */
    private var adoptedSynchronization: SynchronizationPayload? = null

    /**
     * Tunnel (type-130) negotiation mode. DiPlay's tunnel link engine (Iap2LinkEngine.openTunnel,
     * initiateNegotiation=true) has two hard expectations (2026-08 byte-level comparison with the source):
     *  1. On "ACK received but peer SYN not yet received" it fails the link at once (die), so the echo must be sent **before**
     *     a bare ACK (an echo frame with control=SYN|ACK carries the ack bit, one frame completes peer negotiation);
     *  2. Its tunnel is zero-ACK mode (maxRetransmissions=0/maxAcknowledgements=0) and never sends
     *     any ACK, so waiting for a "peer ACK" to finish negotiation deadlocks; the echo counts as negotiation complete.
     * Default false: Bluetooth (RFCOMM/event channel) path semantics unchanged (Audi needs ACK+echo and waits for the final ACK).
     */
    var echoFirst: Boolean = false

    /** Diagnostics: called when the peer session table is first adopted (prints the table the peer proposed, for real-car debugging). */
    var onPeerSynchronizationAdopted: ((SynchronizationPayload) -> Unit)? = null

    /** Diagnostics: called when a data frame with an unknown session ID is dropped ((sessionId, bytes)). */
    var onUnknownSessionData: ((Int, Int) -> Unit)? = null

    /**
     * Callback for EA session (session 2) data frames: when set, EA frames are no longer dropped as unknown sessions
     * but handed to this callback (the touch HID carrier on the event channel virtual link).
     */
    var onEaData: ((bytes: ByteArray) -> Unit)? = null

    /**
     * Datagrams on the file-transfer session (kind 1): the head unit's Start / Success / Failure answers to a
     * now-playing artwork transfer. Unset = such frames are dropped as unknown sessions, as before.
     */
    var onFileTransferData: ((bytes: ByteArray) -> Unit)? = null

    /** Diagnostics: summary of every inbound link frame (control/seq/ack/sessionId/payload length), for real-car debugging. */
    var onFrameReceived: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    /** Diagnostics: summary of every outbound link frame (link layer frames only, to compare with real-car negotiation timing). */
    var onFrameSent: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    private var unknownSessionDropped = 0

    private val unacknowledged = ArrayList<Packet>()
    private val sendBacklog = ArrayDeque<Packet>()
    private val pendingEvents = ArrayDeque<Event>()
    private val output = ByteArrayOutputStream()
    private val receive = ReceiveBuffer()

    private var markerDeadline: Long? = null
    private var syncDeadline: Long? = null
    private var retransmitDeadline: Long? = null

    /** Peer SYN sequence and our sequence at the last SYN echo (replayed verbatim when the same SYN is retransmitted). */
    private var lastEchoPeerSequence: Int? = null
    private var lastEchoOwnSequence = 0

    val isDead: Boolean get() = state == State.DEAD

    /** Whether this engine has sent a SYN (start = initiator; enterNegotiating/startResponder = responder). */
    private var originatedSyn = false

    /**
     * **Send window we declared** (LSP maxOutgoing semantics = the declarer's own unacknowledged packet cap):
     * the send window is counted by this, not by the peer-declared value; when echoing we declare 0x7F (ownSyncOf),
     * and counting by the peer value would jam a burst inside the peer's small window (MIB3 burst of 8 x peer 4).
     */
    private var declaredMaxOutgoing = maxOutgoing

    /** Retransmit timer and limit: constructor parameters by default; adapter mode uses the values declared in the box SYN. */
    private var retransmitMillis = retransmissionTimeoutMillis
    private var retransmitLimit = maxRetransmissions

    /**
     * Wireless CarPlay adapter (smartBox) responder mode, per the field-tested reference sender
     * (unapple-phone link.rs AwaitPeer + echo_peer_parameters): we never send our own SYN; the box's
     * first SYN payload is echoed byte-for-byte in a SYN|ACK, its parameters (one-packet window,
     * retransmit timeout/limit, session IDs) drive our sending, and the link is NORMAL only once
     * the box ACKs that SYN|ACK. Default false: every other path is unchanged.
     */
    var adapterResponder: Boolean = false

    /**
     * While the link is already NORMAL, consecutive peer markers = a renegotiation request: reply "marker + our new-sequence SYN",
     * then echo
     * the peer's following SYN (SYN|ACK), and the peer's final ACK closes the loop. Cadillac CT5 (GM/Cinemo) measured: after
     * 0x4300 it sends a 1Hz marker on the Bluetooth link from ~260ms on and never stops if left unanswered; answering as above
     * closes the loop within 66ms
     * and the marker storm stops (upstream shaonialife/cardeck#27). Enabled only on the Bluetooth head unit link; tunnel and adapter
     * each have their own marker handling. A single marker does not trigger it (avoids misjudging).
     */
    var renegotiateOnPeerMarker: Boolean = false

    /** Diagnostics: called when renegotiation triggers. */
    var onPeerRenegotiation: ((String) -> Unit)? = null

    private var lastPeerMarkerMillis: Long? = null
    private var renegotiateUntilMillis = 0L

    /** Adapter-mode phase log lines (marker/SYN/SYN|ACK echo/link ready). */
    var onAdapterEvent: ((String) -> Unit)? = null

    private var adapterSynPayload: ByteArray? = null
    private var adapterSynSequence: Int? = null
    private var adapterEchoSequence = DEFAULT_INITIAL_SEQUENCE

    /** Enter negotiation: send marker + sync packet (sender = initiator, does not wait for a peer marker). */
    fun start(nowMillis: Long) {
        if (state != State.IDLE) return
        originatedSyn = true
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
    }

    /**
     * Re-send the negotiation opener: marker + our sync packet (may re-trigger from [State.NEGOTIATING]).
     *
     * For the shape where "the peer only sends markers and no sync packet": it is waiting for **our** session table.
     * Honda HFT (Alpine 8A250-30A) measured on the event channel 2026-10-20: the head unit sent 60 markers
     * `FF 55 02 00 EE 10` in 60 seconds and not one FF5A sync packet (it was stuck in probing state),
     * and we used to reply with a marker only, so the head unit kept resending and the link never came up.
     * Compared with the same head unit's Bluetooth iAP2 link: the phone's first frame is exactly "marker + FF5A SYN", and the head unit
     * answered with its own session table within 50ms; supplying the same combination on the event channel gets it moving.
     */
    fun initiate(nowMillis: Long) {
        if (state == State.DEAD) return
        originatedSyn = true
        if (state == State.IDLE) state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
    }

    /**
     * Stop the automatic resend during negotiation (marker / sync packet); state is unchanged.
     *
     * For the case where "the peer never answers with a sync packet": endless 500ms ticking on the control channel
     * confuses the peer CarPlay stack (pitfall measured on the event channel 2026-10-11), so the upper layer reopens by round and
     * explicitly
     * stops after the rounds run out.
     */
    fun stopNegotiationRetries() {
        markerDeadline = null
        syncDeadline = null
    }

    /**
     * Responder mode: send only the marker, **not SYN**, and wait for the peer (head unit) to send SYN itself, then ACK + echo.
     * Audi event channel virtual link measured (2026-10-11): after the head unit sends the opening marker it initiates SYN;
     * our own SYN (amplified by the 500ms resend flood) confuses the head unit CarPlay stack:
     * the event channel stops responding altogether and even the Bluetooth link ACKs stop.
     */
    fun startResponder(nowMillis: Long) {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        // syncDeadline stays null: no proactive SYN, no SYN resend
    }

    /**
     * Enter negotiation silently (DETECT semantics, follows the catplay LinkLayer server role):
     * send no bytes; the peer (client) resends DETECT every 200ms and we **answer once per receipt**
     * and never resend proactively (a server resending DETECT knocks the client state machine back to Detecting so
     * SYN never comes out; measured on the event channel 2026-10-11).
     */
    fun enterNegotiating() {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
    }

    fun feed(
        bytes: ByteArray,
        nowMillis: Long,
    ) {
        if (state == State.DEAD) return
        var offset = 0
        while (offset < bytes.size && state != State.DEAD) {
            val room = receive.remainingCapacity()
            if (room == 0) {
                die("inbound frame exceeds limit")
                return
            }
            val count = minOf(room, bytes.size - offset)
            receive.append(bytes, offset, count)
            offset += count
            while (parseAvailable(nowMillis) && state != State.DEAD) {
                // Drain all complete frames
            }
        }
    }

    fun feedEof() = die(null)

    fun takeOutput(): ByteArray {
        val bytes = output.toByteArray()
        output.reset()
        return bytes
    }

    fun pollEvent(): Event? = if (pendingEvents.isEmpty()) null else pendingEvents.removeFirst()

    fun nextDeadlineMillis(): Long? = listOfNotNull(markerDeadline, syncDeadline, retransmitDeadline).minOrNull()

    fun advanceTime(nowMillis: Long) {
        if (state == State.DEAD) return
        if (markerDeadline != null && markerDeadline!! <= nowMillis) {
            markerDeadline = null
            if (state == State.NEGOTIATING && appendOutput(MARKER)) {
                markerDeadline = nowMillis + MARKER_RESEND_MILLIS
            }
        }
        if (syncDeadline != null && syncDeadline!! <= nowMillis) {
            syncDeadline = null
            if (state == State.NEGOTIATING) {
                // Peer table already adopted: only supply a bare ACK (the echo is sent only once, avoiding inflated negotiation rounds);
                // not adopted: keep resending our table SYN (test bench timing)
                if (adapterResponder) {
                    // The reference re-sends its SYN|ACK every 500 ms until the box ACKs it
                    sendAdapterEcho()
                } else if (adoptedSynchronization != null) {
                    sendAcknowledgement()
                } else {
                    sendSynchronization()
                }
                syncDeadline = nowMillis + SYNC_RESEND_MILLIS
            }
        }
        if (retransmitDeadline != null && retransmitDeadline!! <= nowMillis) {
            retransmitDeadline = null
            if (state == State.NORMAL) retransmitDue(nowMillis)
        }
    }

    /**
     * Enqueue one complete control session payload. When the window is full it goes to the backlog queue, retried on ACK or by the pump.
     * Returns false only when the link is dead.
     *
     * [awaitAck]=false: fire and forget; not counted in the unacknowledged list, not retransmitted, and **the link does not
     * die because it was not ACKed**. For messages the Audi "accepts but never ACKs" in measurements (0x4E0E transport identifier:
     * the head unit
     * ACKs other packets within milliseconds but not this one, and the retransmit watchdog once killed the whole Bluetooth link at
     * +20s because of it).
     */
    fun sendControl(
        bytes: ByteArray,
        nowMillis: Long,
        awaitAck: Boolean = true,
    ): Boolean {
        if (state == State.DEAD) return false
        val limit = (peerSynchronization?.maxLength ?: 0xFFFF) - HEADER_BYTES - 1
        if (limit <= 0 || bytes.size > limit) {
            die("control payload ${bytes.size} exceeds negotiated limit $limit")
            return false
        }
        sendBacklog += Packet(0, bytes.copyOf(), awaitAck = awaitAck)
        drainSendWindow(nowMillis)
        return true
    }

    /**
     * Queues one datagram on the file-transfer session (shares the link sequence space and send window with control
     * traffic). Returns false when the link is not up or the peer's session table has no file-transfer session.
     */
    fun sendFileTransfer(
        bytes: ByteArray,
        nowMillis: Long,
    ): Boolean {
        if (state != State.NORMAL) return false
        val sessionId = fileTransferSessionId() ?: return false
        if (bytes.size > maxSessionPayload()) return false
        sendBacklog += Packet(0, bytes.copyOf(), sessionId = sessionId)
        drainSendWindow(nowMillis)
        return true
    }

    /** Largest payload one link packet can carry with the negotiated maximum length. */
    fun maxSessionPayload(): Int = (peerSynchronization?.maxLength ?: 0xFFFF) - HEADER_BYTES - 1

    /**
     * File-transfer session ID from the negotiated table (WinPlay and our own table: 12). A table without a kind-1
     * session means the peer cannot receive files.
     */
    fun fileTransferSessionId(): Int? {
        val sync = adoptedSynchronization ?: peerSynchronization ?: return SESSION_FILE_TRANSFER
        return sync.sessions.firstOrNull { it.kind == FILE_TRANSFER_KIND }?.id
    }

    // ---- Receive parsing ----

    private fun parseAvailable(nowMillis: Long): Boolean {
        // Drop stray bytes before the frame header (including the peer marker FF55...)
        while (receive.size >= 2 &&
            !(receive[0] == 0xFF.toByte() && receive[1] == 0x5A.toByte())
        ) {
            if (receive.size >= MARKER.size && MARKER.indices.all { receive[it] == MARKER[it] }) {
                receive.discard(MARKER.size)
                onPeerMarker(nowMillis)
                continue
            }
            receive.discard(1)
        }
        if (receive.size < HEADER_BYTES) return false
        val header = receive.peek(HEADER_BYTES)
        if (!checksumValid(header)) {
            receive.discard(1)
            return true
        }
        val length = readU16(header, 2)
        if (length < HEADER_BYTES) {
            receive.discard(1)
            return true
        }
        if (receive.size < length) return false
        val frame = receive.take(length)
        val payloadWithChecksum = frame.copyOfRange(HEADER_BYTES, length)
        if (payloadWithChecksum.isNotEmpty() && !checksumValid(payloadWithChecksum)) {
            receive.discard(1)
            return true
        }
        val payload =
            if (payloadWithChecksum.isEmpty()) {
                null
            } else {
                payloadWithChecksum.copyOf(payloadWithChecksum.size - 1)
            }
        processFrame(
            control = u8(frame[4]),
            sequence = u8(frame[5]),
            ack = u8(frame[6]),
            sessionId = u8(frame[7]),
            payload = payload,
            nowMillis = nowMillis,
        )
        return true
    }

    private fun processFrame(
        control: Int,
        sequence: Int,
        ack: Int,
        sessionId: Int,
        payload: ByteArray?,
        nowMillis: Long,
    ) {
        onFrameReceived?.invoke(control, sequence, ack, sessionId, payload?.size ?: 0)
        if (control and CONTROL_RESET != 0) {
            die("peer sent reset")
            return
        }
        if (control and CONTROL_SYNCHRONIZE != 0) {
            val sync = SynchronizationPayload.decode(payload ?: ByteArray(0))
            // In echoFirst mode keep handling retransmitted peer SYNs even if this engine is already NORMAL: the peer engine
            // (DiPlay openTunnel) only reads from TCP, and if its SYN arrives before our tunnel is up
            // the first echo goes down the wrong channel; its 500ms SYN resend lets us supply the echo once the tunnel is ready
            // (the frame scan of parseAvailable drops the FF55 marker in the resend, no side effect).
            //
            // **Only a pure SYN (no ACK bit) gets SYN|ACK back**: a SYN|ACK means the peer is answering our
            // initiator SYN, i.e. the "it has answered" signal (see the ACK branch below); replying again would create
            // an endless ping-pong of SYN|ACK both ways (exposed by a unit test: negotiation never converged when two engines fed
            // each other frames).
            val isPureSyn = (control and CONTROL_ACKNOWLEDGEMENT) == 0
            if (adapterResponder) {
                if (sync != null && isPureSyn) handleAdapterSyn(sync, payload!!, sequence, nowMillis)
            } else if (sync != null &&
                isPureSyn &&
                (state == State.NEGOTIATING || echoFirst || nowMillis <= renegotiateUntilMillis)
            ) {
                if (state == State.NORMAL && nowMillis <= renegotiateUntilMillis) {
                    renegotiateUntilMillis = 0L
                    onPeerRenegotiation?.invoke("rx peer renegotiation SYN seq=$sequence -> echo SYN|ACK")
                }
                val firstAdoption = adoptedSynchronization == null
                peerSynchronization = sync
                if (firstAdoption) adoptedSynchronization = sync
                // Audi real car measured (2026-10-09, byte-level comparison): the head unit sends SYN as initiator declaring its
                // session table
                // {1:control,2:EA}; the phone must "ACK + echo its table verbatim (SYN|ACK)", and only on seeing the echo does the
                // head unit send the
                // final ACK to complete negotiation (with a bare ACK only, it never ACKs). The echo declares "I adopt your table",
                // after which CSM uses the adopted control session ID (1); sending on 10 is silently dropped.
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                lastReceivedInOrder = sequence
                // **Every** SYN gets exactly one SYN|ACK frame back (no bare ACK):
                //  1. Complete semantics: SYN|ACK already carries the ack bit and ack field, a bare ACK is redundant
                //     (catplay `on_recv_neg_host` sends only SYN+ACK);
                //  2. Sequence numbers must not collide: a bare ACK uses the current sentSequence while SYN|ACK increments it, so the next
                //     bare ACK would reuse the previous SYN|ACK sequence, and BMW drops same-sequence frames as duplicates;
                //  3. Retransmitting the same SYN replays the whole frame verbatim, byte-level idempotent.
                // BMW 87866 ruling, 2026-10-19: the old code sent only a bare ACK after the first adoption, and that head unit
                // reopens negotiation
                // with a new sequence on every retry (161->191->40...), so all 14 rounds stuck in negotiation
                // and never reached a single 0x1D00 (log: 133 SYN / 0 ACK).
                sendSynchronizationEcho(sync, sequence)
                if (firstAdoption) onPeerSynchronizationAdopted?.invoke(sync)
                // For a responder (never sent SYN) the echo counts as negotiation complete: after seeing SYN|ACK the peer considers the
                // link usable (a real iPhone on MIB3 sends 0x1D00 right after SYN|ACK without waiting for an ACK);
                // an initiator still waits for the peer ACK/data (original semantics)
                if (echoFirst || !originatedSyn) completeNegotiationIfPending(nowMillis)
            } else if (sync != null &&
                !isPureSyn &&
                adoptedSynchronization == null &&
                state == State.NEGOTIATING
            ) {
                // Initiator receives SYN|ACK (the peer's answer to our SYN, payload = peer table):
                // only adopt, **do not echo** (echoing creates a SYN|ACK ping-pong). It used to adopt only on a pure
                // SYN, so when the peer was a "pure responder" the initiator never reached NORMAL;
                // the MIB3 virtual link (phone arms detect -> head unit SYN -> phone SYN|ACK) has exactly this shape:
                // this branch converges the head unit side, and a bare ACK is supplied afterwards by the timeout mechanism.
                peerSynchronization = sync
                adoptedSynchronization = sync
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                // Watermark must also advance (the pure SYN branch does the same): when left at 0,
                // the initiator treats the responder's first data frame as an "expired frame", only re-sending ack=0 —
                // the responder's send window gets poisoned by this bogus ack (backlog never flushes, unit test located)
                lastReceivedInOrder = sequence
                completeNegotiationIfPending(nowMillis)
            }
        }
        if (control and CONTROL_ACKNOWLEDGEMENT != 0) {
            handleAcknowledgement(ack, nowMillis)
        }
        val isDataFrame =
            payload != null &&
                (control and CONTROL_ACKNOWLEDGEMENT) != 0 &&
                (control and CONTROL_SYNCHRONIZE) == 0 &&
                (control and CONTROL_EXTENDED_ACKNOWLEDGEMENT) == 0
        if (isDataFrame) {
            // Session ID of control messages: our own table 10 or the adopted table (Audi=1; test bench has both equal)
            if (sessionId == SESSION_CONTROL || sessionId == adoptedPeerControlSessionId) {
                // Receiving peer data means the peer considers the link usable; if the ACK frame was lost/skipped, this is the
                // fallback into NORMAL
                completeNegotiationIfPending(nowMillis)
                handleData(sequence, payload!!)
            } else if (onFileTransferData != null && sessionId == fileTransferSessionId()) {
                handleFileTransferData(sequence, payload!!)
            } else if (sessionId == SESSION_EA && onEaData != null) {
                // EA session data (including touch HID): the peer's sequence accounting is independent of the control session, pass
                // it up directly
                onEaData?.invoke(payload!!)
                sendAcknowledgement()
            } else {
                reportUnknownSessionData(sessionId, payload!!.size)
            }
        }
    }

    private fun onPeerMarker(nowMillis: Long) {
        if (!renegotiateOnPeerMarker || adapterResponder || echoFirst || state != State.NORMAL) return
        if (nowMillis <= renegotiateUntilMillis) return // already answered, waiting for the peer SYN
        val previous = lastPeerMarkerMillis
        lastPeerMarkerMillis = nowMillis
        if (previous == null || nowMillis - previous > PEER_MARKER_PAIR_MILLIS) return
        lastPeerMarkerMillis = null
        // The new SYN must use a new sequence: reusing the last data frame's sequence would be dropped as a duplicate (same lesson as BMW)
        sentSequence = nextSequence(sentSequence)
        appendOutput(MARKER)
        sendSynchronization()
        renegotiateUntilMillis = nowMillis + RENEGOTIATION_WINDOW_MILLIS
        onPeerRenegotiation?.invoke(
            "link already NORMAL yet peer marker keeps coming: reply marker + SYN(seq=$sentSequence) to " +
                "renegotiate",
        )
    }

    private fun completeNegotiationIfPending(nowMillis: Long) {
        if (state == State.NEGOTIATING && peerSynchronization != null) {
            state = State.NORMAL
            markerDeadline = null
            syncDeadline = null
            if (adapterResponder) onAdapterEvent?.invoke("rx ACK of our SYN|ACK (ack=$lastSentAcknowledged) - link NORMAL")
            drainSendWindow(nowMillis)
        }
    }

    /**
     * Adapter-mode answer to a pure SYN. smartBox-136F field capture (reference iap2.log,
     * 2026-10-26): box SYN `01 01 0080 140b 1017 10 03 01 00 02` (window 1, 128-byte packets,
     * control session 1/v2) -> phone SYN|ACK seq=99 with exactly that payload -> box ACK.
     */
    private fun handleAdapterSyn(
        sync: SynchronizationPayload,
        payload: ByteArray,
        sequence: Int,
        nowMillis: Long,
    ) {
        if (state == State.NORMAL) {
            // A late copy of the SYN we answered (no box data yet) gets the identical echo;
            // anything else is a restart the bootstrap can't recover from, so only report it.
            if (sequence == adapterSynSequence && lastReceivedInOrder == sequence) {
                sendAdapterEcho()
                onAdapterEvent?.invoke("rx duplicate SYN seq=$sequence on the established link - re-sent the same SYN|ACK")
            } else {
                onAdapterEvent?.invoke("rx SYN seq=$sequence on the established link - ignored (adapter restarted its link?)")
            }
            return
        }
        if (state != State.NEGOTIATING) return
        if (adapterSynPayload == null) {
            adapterSynPayload = payload.copyOf()
            adapterSynSequence = sequence
            adapterEchoSequence = sentSequence
            peerSynchronization = sync
            adoptedSynchronization = sync
            sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let { adoptedPeerControlSessionId = it.id }
            declaredMaxOutgoing = sync.maxOutgoing.coerceAtLeast(1)
            if (sync.retransmissionTimeoutMillis > 0) retransmitMillis = sync.retransmissionTimeoutMillis.toLong()
            retransmitLimit = sync.maxRetransmissions
            onPeerSynchronizationAdopted?.invoke(sync)
        }
        lastReceivedInOrder = sequence
        markerDeadline = null
        sendAdapterEcho()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
        onAdapterEvent?.invoke(
            "rx SYN seq=$sequence -> tx SYN|ACK seq=$adapterEchoSequence ack=$sequence echoing the first SYN " +
                "byte-for-byte (${adapterSynPayload!!.joinToString("") { "%02x".format(it) }}); window=$declaredMaxOutgoing " +
                "retransmit=${retransmitMillis}ms x$retransmitLimit maxLen=${sync.maxLength} " +
                "control session=${adoptedPeerControlSessionId ?: SESSION_CONTROL}",
        )
    }

    private fun sendAdapterEcho() {
        val echo = adapterSynPayload ?: return
        writePacket(echo, adapterEchoSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
        onSynchronizationEchoSent?.invoke()
    }

    private fun reportUnknownSessionData(
        sessionId: Int,
        size: Int,
    ) {
        unknownSessionDropped++
        if (unknownSessionDropped <= 5) onUnknownSessionData?.invoke(sessionId, size)
    }

    /**
     * Diagnostics: outbound sync packet echo generated (in tunnel mode, the gate that allows CSM only after the echo has been
     * delivered).
     */
    var onSynchronizationEchoSent: (() -> Unit)? = null

    /**
     * Echo the peer sync (SYN|ACK, payload identical to the peer's), declaring "the phone adopts the head unit session table".
     *
     * **Must consume a new send sequence**: the peer validates replies against its own sequence accounting (catplay
     * `NegotiationTx::SynAck` increments with `take_own_seq()`). The old code reused `sentSequence`
     * (never incremented after sending SYN), so SYN and SYN-ACK both had seq=99 and BMW dropped it as a duplicate frame:
     * all 14 negotiation rounds timed out and the head unit never returned a single ACK (real-car log ruling, 2026-10-19).
     *
     * A retransmission of the **same** SYN stays byte-level idempotent: replay the previous frame, do not advance the sequence.
     * DiPlay tunnel mode resends the same SYN every 500ms and relies on this to keep its original behavior.
     */

    /**
     * "Our parameters" used in the echo: session table/timeouts follow the peer declaration (no special need on the phone side),
     * but **maxOutgoing is raised to 0x7F**: echoing verbatim would pin our own send window to the peer's
     * small window (MIB3 burst of 8 backs up at maxOutgoing=4; a real iPhone/AADisplay both declare
     * 0x7F). maxLength keeps the peer value (= our receive cap, do not enlarge).
     */
    private fun ownSyncOf(peer: SynchronizationPayload): SynchronizationPayload =
        peer.copy(maxOutgoing = PHONE_DECLARED_MAX_OUTGOING).also {
            declaredMaxOutgoing = PHONE_DECLARED_MAX_OUTGOING
        }

    private fun sendSynchronizationEcho(
        sync: SynchronizationPayload,
        peerSequence: Int,
    ) {
        if (lastEchoPeerSequence == peerSequence) {
            writePacket(ownSyncOf(sync).encode(), lastEchoOwnSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
            onSynchronizationEchoSent?.invoke()
            return
        }
        val ownSequence = nextSequence(sentSequence).also { sentSequence = it }
        writePacket(ownSyncOf(sync).encode(), ownSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
        lastEchoPeerSequence = peerSequence
        lastEchoOwnSequence = ownSequence
        onSynchronizationEchoSent?.invoke()
    }

    private fun handleAcknowledgement(
        acknowledgement: Int,
        nowMillis: Long,
    ) {
        // Adapter mode: record the ACK first so the one-packet window applies from the first data frame
        if (adapterResponder) lastSentAcknowledged = acknowledgement
        completeNegotiationIfPending(nowMillis)
        lastSentAcknowledged = acknowledgement
        val window = (peerSynchronization?.maxOutgoing ?: maxOutgoing) + ACK_SLACK
        unacknowledged.removeAll { sequenceDistance(it.sequence, acknowledgement) !in 1..window }
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
        drainSendWindow(nowMillis)
    }

    private fun handleData(
        sequence: Int,
        payload: ByteArray,
    ) {
        val distance = sequenceDistance(sequence, lastReceivedInOrder)
        val peerWindow = peerSynchronization?.maxOutgoing ?: maxOutgoing
        if (distance == 0 || distance > peerWindow + ACK_SLACK) {
            sendAcknowledgement() // duplicate or stale: re-acknowledge the current sequence
            return
        }
        // RFCOMM is a reliable ordered stream and drops nothing; a sequence jump can only mean the peer counted ACK/SYN into the sequence
        // (implementations account differently). So accept any forward jump and advance the watermark, avoiding deadlock-style frame drops.
        lastReceivedInOrder = sequence
        if (pendingEvents.size >= 256) {
            die("event queue overflow")
            return
        }
        pendingEvents += Event.Control(payload)
        sendAcknowledgement()
    }

    /** Same sequence accounting as [handleData]; the datagram goes to [onFileTransferData] instead of the CSM queue. */
    private fun handleFileTransferData(
        sequence: Int,
        payload: ByteArray,
    ) {
        val distance = sequenceDistance(sequence, lastReceivedInOrder)
        val peerWindow = peerSynchronization?.maxOutgoing ?: maxOutgoing
        if (distance == 0 || distance > peerWindow + ACK_SLACK) {
            sendAcknowledgement()
            return
        }
        lastReceivedInOrder = sequence
        onFileTransferData?.invoke(payload)
        sendAcknowledgement()
    }

    // ---- Send ----

    private fun drainSendWindow(nowMillis: Long) {
        if (state != State.NORMAL) return
        while (sendBacklog.isNotEmpty()) {
            val packet = sendBacklog.first()
            val peer = peerSynchronization
            val window = declaredMaxOutgoing
            if (lastSentAcknowledged != null &&
                sequenceDistance(sentSequence, lastSentAcknowledged!!) >= window
            ) {
                return
            }
            sendBacklog.removeFirst()
            sentSequence = nextSequence(sentSequence)
            packet.sequence = sentSequence
            packet.deadlineMillis = nowMillis + retransmitMillis
            writePacket(packet.payload, packet.sequence, CONTROL_ACKNOWLEDGEMENT, packet.sessionId ?: controlSessionId())
            if (!packet.awaitAck) continue // fire and forget: not tracked, not retransmitted (see the sendControl comment)
            if (peer == null || peer.maxRetransmissions > 0) {
                unacknowledged += packet
                retransmitDeadline = packet.deadlineMillis
            } else {
                lastSentAcknowledged = sentSequence
            }
        }
    }

    /** Session ID of outbound control messages: the adopted peer table's control ID if adopted (Audi=1), otherwise our own table 10. */
    private fun controlSessionId(): Int = adoptedPeerControlSessionId ?: SESSION_CONTROL

    private fun retransmitDue(nowMillis: Long) {
        val next = unacknowledged.minByOrNull { it.deadlineMillis } ?: return
        next.deadlineMillis = nowMillis + retransmitMillis
        next.retransmissions += 1
        if (next.retransmissions > retransmitLimit) {
            die("packet ${next.sequence} not acknowledged")
            return
        }
        writePacket(next.payload, next.sequence, CONTROL_ACKNOWLEDGEMENT, next.sessionId ?: controlSessionId())
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
    }

    private fun sendSynchronization() {
        val payload =
            SynchronizationPayload(
                maxOutgoing = maxOutgoing,
                maxLength = 0xFFFF,
                retransmissionTimeoutMillis = retransmissionTimeoutMillis.toInt().coerceIn(0, 0xFFFF),
                acknowledgementTimeoutMillis = 500,
                maxRetransmissions = maxRetransmissions,
                maxAcknowledgements = 3,
                sessions =
                    listOf(
                        SessionDescriptor(SESSION_CONTROL, 0, 2),
                        SessionDescriptor(SESSION_EA, 2, 1),
                        SessionDescriptor(SESSION_FILE_TRANSFER, 1, 2),
                    ),
            ).encode()
        writePacket(payload, sentSequence, CONTROL_SYNCHRONIZE, 0)
    }

    private fun sendAcknowledgement() {
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    private fun writePacket(
        payload: ByteArray?,
        sequence: Int,
        control: Int,
        sessionId: Int,
    ) {
        if (state == State.DEAD) return
        onFrameSent?.invoke(control, sequence, lastReceivedInOrder, sessionId, payload?.size ?: 0)
        val length = payload?.size?.plus(HEADER_BYTES + 1) ?: HEADER_BYTES
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        frame[1] = 0x5A
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        frame[4] = control.toByte()
        frame[5] = sequence.toByte()
        frame[6] = lastReceivedInOrder.toByte()
        frame[7] = sessionId.toByte()
        var sum = 0
        for (index in 0 until HEADER_BYTES - 1) sum += u8(frame[index])
        frame[HEADER_BYTES - 1] = (-sum and 0xFF).toByte()
        if (payload != null) {
            payload.copyInto(frame, HEADER_BYTES)
            var payloadSum = 0
            payload.forEach { payloadSum += u8(it) }
            frame[frame.size - 1] = (-payloadSum and 0xFF).toByte()
        }
        appendOutput(frame)
    }

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        state = State.DEAD
        markerDeadline = null
        syncDeadline = null
        retransmitDeadline = null
        output.reset()
        pendingEvents.clear()
        sendBacklog.clear()
        pendingEvents += Event.Dead(reason)
    }

    private fun appendOutput(bytes: ByteArray): Boolean {
        if (state == State.DEAD) return false
        output.write(bytes)
        return true
    }

    private class ReceiveBuffer {
        private val bytes = ByteArray(0xFFFF + 8)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return bytes.size - tail
        }

        operator fun get(index: Int): Byte = bytes[head + index]

        fun append(
            source: ByteArray,
            offset: Int,
            count: Int,
        ) {
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun peek(count: Int): ByteArray = bytes.copyOfRange(head, head + count)

        fun take(count: Int): ByteArray {
            val out = peek(count)
            discard(count)
            return out
        }

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val SESSION_CONTROL = 10
        const val SESSION_EA = 11
        const val SESSION_FILE_TRANSFER = 12

        /** Type field in the session descriptor: 0 = control session (xcertplay/extractor/catplay agree). */
        private const val CONTROL_KIND = 0

        /** Session descriptor type 1 = file transfer (WinPlay table: 12/k1/v2). */
        private const val FILE_TRANSFER_KIND = 1

        val MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        const val HEADER_BYTES = 9
        private const val SYNCHRONIZATION_VERSION = 1
        private const val CONTROL_SYNCHRONIZE = 0x80
        private const val CONTROL_ACKNOWLEDGEMENT = 0x40
        private const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 0x20
        private const val CONTROL_RESET = 0x10
        const val DEFAULT_INITIAL_SEQUENCE = 99

        /** While the link is NORMAL, two peer markers no more than this far apart count as a "renegotiation request". */
        private const val PEER_MARKER_PAIR_MILLIS = 3_000L

        /** After sending the renegotiation SYN, a new peer SYN is accepted and echoed within this window. */
        private const val RENEGOTIATION_WINDOW_MILLIS = 5_000L

        fun randomInitialSequence(): Int = java.security.SecureRandom().nextInt(0x100)

        private const val MARKER_RESEND_MILLIS = 1_000L
        private const val SYNC_RESEND_MILLIS = 500L
        private const val ACK_SLACK = 10

        private fun sequenceDistance(
            sequence: Int,
            previous: Int,
        ): Int = (sequence - previous) and 0xFF

        private fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xFF

        private fun checksumValid(bytes: ByteArray): Boolean {
            var sum = 0
            bytes.forEach { sum += u8(it) }
            return sum and 0xFF == 0
        }

        private fun readU16(
            bytes: ByteArray,
            offset: Int,
        ): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

        private fun writeU16(
            out: ByteArrayOutputStream,
            value: Int,
        ) {
            out.write(value ushr 8)
            out.write(value)
        }

        private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
    }
}
