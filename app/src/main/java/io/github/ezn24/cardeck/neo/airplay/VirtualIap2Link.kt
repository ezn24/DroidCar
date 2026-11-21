package io.github.ezn24.cardeck.neo.airplay

import android.content.Context
import io.github.ezn24.cardeck.neo.iap2.ArtworkTransfer
import io.github.ezn24.cardeck.neo.iap2.Iap2Csm
import io.github.ezn24.cardeck.neo.iap2.Iap2LinkEngine
import io.github.ezn24.cardeck.neo.iap2.Iap2MessageIds
import io.github.ezn24.cardeck.neo.iap2.MfiLengthMemory
import io.github.ezn24.cardeck.neo.iap2.NowPlayingUpdate
import io.github.ezn24.cardeck.neo.iap2.ReceiverProfile
import io.github.ezn24.cardeck.neo.input.ConsumerKeys
import io.github.ezn24.cardeck.neo.input.HidNavigation
import io.github.ezn24.cardeck.neo.media.CarNowPlaying
import io.github.ezn24.cardeck.neo.nav.CarRouteGuidance
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/**
 * Virtual iAP2 link on the event channel (the "WiFi tunnel" above the AirPlay session); one implementation serves three forms:
 *
 *  - **DiPlay (type-130 tunnel)**: outbound writes go to the tunnel TCP; link negotiation uses "echo first" semantics
 *    (a hard expectation of the peer engine, see [Iap2LinkEngine.echoFirst]);
 *  - **Audi MIB3 (event channel + control-channel POST)**: real-device sequence (AADisplay captured it by hooking
 *    mediaserverd on a jailbroken iPhone with Frida): **after the screen-stream SETUP succeeds the phone sends one
 *    DETECT itself (iAPSendMessage via control-channel POST /command) -> head unit replies SYN (event channel)
 *    -> phone replies SYN|ACK (control channel)**, then the whole control session is rerun: 0x1D00 -> 0x1D01 ->
 *    0x1D02+0xAA00 -> (0xAA06 -> 0xAA00) -> 0xAA02 challenge 20B -> 0xAA03 -> 0xAA05 ->
 *    phone burst messages -> answer the head unit's subscriptions. **If we only echo empty ACKs, MIB3 tears the whole link down after ~10-12 s**
 *    (reproduced at 10.2 s in two real-car logs of this repo on 2026-10-19; it was previously misdiagnosed as missing heartbeats);
 *  - **Old event form / plaintext session**: marker question-and-answer echo (historical behavior kept).
 *
 * Link bytes from head unit to phone all arrive through event-channel iAPSendMessage ([onCarBytes]);
 * phone to head unit is routed by form (tunnel TCP / control-channel POST <-- default). **The directions must not be swapped**:
 * the head unit does not read outbound writes on the event channel (Frida finding; reproduced on Honda HFT 2026-10-20:
 * after moving outbound to the event channel, the head unit sent not a single FF5A sync packet in 20 minutes, only resending the marker at 1 Hz).
 */
class VirtualIap2Link(
    private val log: (String) -> Unit,
    /** Event-channel iAPSendMessage outbound (fallback for the old form). */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** Control-channel iAPSendMessage outbound (the only correct channel for MIB3 phone-side frames). */
    private val sendControlIap: (ByteArray) -> Unit,
    /** DiPlay type-130 tunnel write entry (looked up dynamically on every outbound; may be null). */
    private val tunnelWriter: () -> AirPlayClient.IapTunnelClient?,
    /** Pacer-thread liveness condition (host control connection not closed). */
    private val alive: () -> Boolean,
    /** Persistence context for the MFi length memory; null = in-process memory (unit tests). */
    private val mfiContext: Context? = null,
    /** Clock (injectable in unit tests). */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private val engine = Iap2LinkEngine()
    private val parser = Iap2Csm.Parser()
    private var frameCount = 0
    private var eaCount = 0
    private var started = false

    // ---- DiPlay tunnel path state ----
    private var tunnelCsmStarted = false
    private var tunnelIdentified = false

    /** Pending delivery of the sync echo (tunnel gate: 0x1D00 may only be sent after the echo is actually written to the tunnel TCP). */
    private var pendingEchoFlush = false
    private var tunnelEchoDelivered = false

    // ---- MIB3 path state ----
    private var mib3Armed = false

    /**
     * Head-unit link generation (enum and classification live in [ReceiverProfile]; the basis is printed in the PROFILE/IAPLINK
     * logs).
     *
     * **Second ruling 2026-10-23: marker negotiation is no longer dispatched by generation**. Every head unit must complete link
     * negotiation (the head unit marker means it is waiting for our session table and is resent endlessly at 1 Hz; without a reply the link never reaches
     * NORMAL and the head unit tears it down ~10 s after RECORD: measured 10.17 s on 0.1.9, 10.18 s on 0.1.17).
     * Versions 0.1.16/0.1.17 had ignored markers for CLASSIC_MIB3 on the claim that "Audi proceeds normally when 0.1.9 ignores markers";
     * that claim was disproved by 0.1.9's own log (10.17 s teardown) and by the 0.1.17 owner log.
     * The real cause of the earlier "negotiation hangs the head unit UI on the old screen" was the missing modesChanged re-assertion after negotiation completed
     * (see [onControlSessionComplete]).
     *
     * The generation is now used only for logging and future dispatch; the marker policy is uniform: upgrade to negotiation by threshold (on Honda HFT the
     * media-key carrier is in 0x6802 after the link is up, and on Audi the screen switch requires link NORMAL + resource re-declaration).
     */
    private var generation: ReceiverProfile.LinkGeneration = ReceiverProfile.LinkGeneration.EVENT_LINK
    private var mib3SynAckQueued = false
    private var mib3CsmStarted = false
    private var mib3Identified = false
    private var mib3AuthStage = 0
    private var mib3MarkerIgnores = 0
    private var mib3DeferredLogs = 0
    private var mib3WirelessSessionId: String? = null
    private var mib3ControlSessionNotified = false

    /**
     * Event-channel link mode (added after the Honda HFT measurement on 2026-10-20).
     *
     * The head unit repeatedly sends SYN markers (`FF 55 02 00 EE 10`) on the **event channel** acting as initiator,
     * once per second, hundreds of times in a row, and only continues with sync packets once we answer with a marker.
     * But [armMib3]'s detect goes over the **control channel**, which the head unit cannot see, so it keeps resending forever.
     *
     * Starting from the [EVENT_LINK_MARKER_THRESHOLD]-th consecutive marker we judge that it is really waiting for a reply
     * and switch to this mode: both directions use the event channel, and marker reply + sync-packet negotiation builds the link.
     * Only after the link is up does the head unit send 0x5000 (NowPlaying subscription), 0x6800 (StartHID),
     * 0x6802 (HID report = steering wheel/panel media keys); the track-change keys are carried there.
     */
    private var eventLinkArmed = false
    private var eventLinkMarkers = 0
    private var eventCsmStarted = false

    /**
     * Control-channel negotiation rounds (added after the Honda HFT verdict on 2026-10-20).
     *
     * The head unit's marker arrives **inbound on the event channel**, but its iAP2 stack only reads our bytes from the **control channel** (RTSP
     * POST /command iAPSendMessage); the project's original Frida finding says
     * "the head unit does not read writes on the event channel". After r5/r6 moved outbound to the event channel as well, the head unit did not reply with even one
     * FF5A sync packet and resent the marker at 1 Hz until dawn; this is exactly that mistake.
     *
     * Outbound is back on the control channel: every round, [armMib3] / [restartControlNegotiation] sends once
     * "marker + our session table", and the engine resends internally at 500ms/1s; if [CONTROL_SYN_ROUND_MILLIS] passes
     * without progress we move to the next round, at most [MAX_CONTROL_SYN_ROUNDS] rounds, then stay in NEGOTIATING
     * and wait silently (no more pokes, to avoid confusing the head unit stack).
     */
    private var controlSynRounds = 0
    private var controlSynRoundStartedAtMs = 0L
    private var controlSynGaveUp = false

    /** Count of unhandled control-session messages (observation point for media-key debugging, see [maybeRespondControlCsmLocked]). */
    private var unknownCsmIds = 0

    /** Last link state that was logged (so each state transition appears once in the log). */
    private var lastLinkState = Iap2LinkEngine.State.IDLE

    // ---- Head-unit HID media key (0x6802) state ----
    /** Set of Usages pressed in the previous report (to detect "newly pressed"; cleared automatically on release). */
    private var lastHidUsages: Set<Int> = emptySet()

    /** Start time of the current batch of key presses (auto-repeat is only allowed after the same key is held longer than [HID_HOLD_REPEAT_MILLIS]). */
    private var hidHoldStartedAtMs = 0L

    /** Number of frames whose parameter details were printed (only the first few; afterwards actions are logged as needed). */
    private var hidReportFrames = 0

    // ---- MIB3 tunnel MFi challenge (remembered per head unit; recorded **independently** of the Bluetooth link: on the same car
    // the authentication backends of the two links may differ; measured on MMI 3315, Bluetooth accepts 32B while the Frida tunnel uses 20B) ----
    private var mfiIdentity: String? = null
    private var mfiMemory = MfiLengthMemory.Entry()
    private var mfiOrder: List<Int> = emptyList()
    private var mfiPosition = -1
    private var mfiCurrentLength = 0
    private var mfiWindowMillis = 0L
    private var mfiChallengeSentAtMs = 0L
    private var mfiFinished = false

    init {
        engine.onEaData = { bytes -> handleEaData(bytes) }
        engine.onFileTransferData = { bytes -> CarNowPlaying.post { artworkTransfer.onDatagram(bytes) } }
        engine.onSynchronizationEchoSent = { pendingEchoFlush = true }
        Thread(
            {
                while (alive()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    synchronized(lock) {
                        engine.advanceTime(System.nanoTime() / 1_000_000L)
                        drainLocked()
                    }
                }
            },
            "iap-link-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Event-form start: server stands by (reply marker to marker, wait for the head unit SYN). */
    fun begin() {
        synchronized(lock) {
            if (started) return
            started = true
            engine.enterNegotiating()
        }
    }

    /**
     * MIB3 arming (called after the screen-stream SETUP succeeds, once only): proactively send one DETECT on the **control channel**.
     * It must wait until SETUP completes (sent earlier it gets flushed during the SETUP exchange; Frida finding); after that
     * the head unit's markers are probe resends, and a real MIB3 stops resending once it replies with one SYN.
     *
     * Note: **only a bare marker** is sent here: the MIB3 sequence is "it replies SYN -> we reply SYN|ACK",
     * and pushing the session table early only disturbs its state machine (see the measurements at [Iap2LinkEngine.startResponder]).
     * If the head unit keeps resending markers like Honda HFT does, only then does
     * [startControlRoundLocked] upgrade to "marker + our session table" and retry.
     */
    fun armMib3() {
        synchronized(lock) {
            begin()
            if (mib3Armed) return
            mib3Armed = true
            sendControlIap(Iap2LinkEngine.MARKER)
            log("IAPLINK: MIB3 detect sent (control-channel iAPSendMessage, generation=$generation) - waiting for the head unit")
        }
    }

    /**
     * Explicitly declares the head-unit link generation (before or after arming, idempotent). The generation is now used only for logging and future dispatch;
     * the marker negotiation policy is uniform across generations (second ruling 2026-10-23, see the [generation] docs).
     */
    fun setGeneration(generation: ReceiverProfile.LinkGeneration) {
        synchronized(lock) {
            if (this.generation == generation) return
            this.generation = generation
            log("IAPLINK: link generation -> $generation")
        }
    }

    /**
     * For debugging/special forms: force one control-channel negotiation round (the normal path is triggered by the head unit marker).
     * Once called, later markers are handled in the negotiating state.
     */
    fun forceControlNegotiation(reason: String) {
        synchronized(lock) {
            if (tunnelWriter() != null) return
            eventLinkArmed = true
            startControlRoundLocked(reason)
        }
    }

    /**
     * MIB3 control-session-complete callback (fires once when auth+burst are sent): afterwards the host should re-assert the modesChanged resource declaration
     * with the timing verified by AADisplay r17 (+1.5s first send,
     * +4/+12/+30s re-assertions). **Ruling from 082446**: negotiation/MFi/subscriptions all succeeded but resources were declared only once
     * early in the session -> the head unit stayed healthy yet never switched to the CarPlay picture: the second control
     * session reset the head unit's resource arbitration, the old declaration was void, and it was waiting for a new one.
     * Do not declare while 0xAA00/0xAA05 are in flight (competing with iAP POSTs for the control channel kills the Audi,
     * per the AADisplay comment); wait until the burst completes.
     */
    var onControlSessionComplete: (() -> Unit)? = null

    /**
     * Starts one control-channel negotiation round: marker + our sync packet (sessions 10/11/12), refreshing the engine resend cadence.
     */
    private fun startControlRoundLocked(reason: String) {
        val now = nowMillis()
        controlSynRounds++
        controlSynRoundStartedAtMs = now
        engine.initiate(now)
        log(
            "IAPLINK: control-channel marker + our sync packet (round $controlSynRounds/$MAX_CONTROL_SYN_ROUNDS, $reason)" +
                " - waiting for the head unit sync packet (FF5A)",
        )
        drainLocked()
    }

    /** Entry point for head-unit link bytes (the data of event-channel iAPSendMessage). */
    fun onCarBytes(bytes: ByteArray) {
        synchronized(lock) {
            // With a tunnel present, switch to "echo first" negotiation semantics (a hard expectation of the DiPlay peer engine)
            engine.echoFirst = tunnelWriter() != null
            val isMarker =
                bytes.size == 6 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0x55.toByte()
            if (isMarker && tunnelWriter() == null) {
                if (mib3Armed) {
                    mib3MarkerIgnores++
                    val now = nowMillis()
                    when (generation) {
                        // Ruling 2026-10-23: **every generation must complete link negotiation**. We used to dispatch on "Audi ignores
                        // marker" (0.1.16/0.1.17), which the 0.1.17 owner log disproved: the head unit marker
                        // is resent **endlessly** at 1 Hz (far beyond the old comment's "goes silent after 2~5"), the link never reaches
                        // NORMAL, and 10.18 s after RECORD the head unit tears down the whole session (same 10.17 s as 0.1.9).
                        // Completing negotiation has no side effects (082446 stayed healthy throughout); what was missing is the
                        // modesChanged re-assertion after negotiation completes (see onControlSessionComplete).
                        ReceiverProfile.LinkGeneration.CLASSIC_MIB3,
                        ReceiverProfile.LinkGeneration.EVENT_LINK,
                        -> {
                            if (!eventLinkArmed && mib3MarkerIgnores >= EVENT_LINK_MARKER_THRESHOLD) {
                                eventLinkArmed = true
                                log(
                                    "IAPLINK: head unit sent consecutive SYN markers x$mib3MarkerIgnores" +
                                        " (it is waiting for our session table) -> resend negotiation round on the control channel",
                                )
                                startControlRoundLocked("head unit marker resent")
                            } else if (eventLinkArmed) {
                                eventLinkMarkers++
                                if (engine.state != Iap2LinkEngine.State.NORMAL && !controlSynGaveUp) {
                                    val stale = now - controlSynRoundStartedAtMs >= CONTROL_SYN_ROUND_MILLIS
                                    when {
                                        !stale -> Unit
                                        controlSynRounds >= MAX_CONTROL_SYN_ROUNDS -> {
                                            controlSynGaveUp = true
                                            engine.stopNegotiationRetries()
                                            log(
                                                "IAPLINK: no reply after $MAX_CONTROL_SYN_ROUNDS control-channel negotiation rounds, " +
                                                    "stopping auto resend; " +
                                                    "link stays at ${engine.state} (continues automatically if the head unit sends 0x1D01)",
                                            )
                                        }

                                        else -> startControlRoundLocked("no reply in the previous ${CONTROL_SYN_ROUND_MILLIS}ms round")
                                    }
                                }
                                // The engine pacer thread also resends; flush pending bytes immediately here
                                drainLocked()
                                if (eventLinkMarkers <= 3 || eventLinkMarkers % 20 == 0) {
                                    log(
                                        "IAPLINK: head unit marker (#$eventLinkMarkers) link state=${engine.state}" +
                                            " (control-channel round $controlSynRounds)",
                                    )
                                }
                                return
                            } else if (mib3MarkerIgnores <= 3) {
                                log(
                                    "IAPLINK: head unit marker (#$mib3MarkerIgnores) - resending negotiation once $EVENT_LINK_MARKER_THRESHOLD have accumulated",
                                )
                            }
                        }
                    }
                } else {
                    // Before arming (screen SETUP not yet complete): do not respond. A real device does not detect before SETUP,
                    // and an echo on the event channel is meaningless to MIB3 (it only honors the control channel); old plaintext receivers
                    // do not use these link semantics
                    if (mib3DeferredLogs < 2) {
                        mib3DeferredLogs++
                        log("IAPLINK: head unit marker before SETUP not answered yet (detect proactively after arming)")
                    }
                    return
                }
            }
            engine.feed(bytes, System.nanoTime() / 1_000_000L)
            drainLocked()
        }
    }

    /**
     * Outbound link bytes: tunnel TCP (DiPlay) -> control-channel POST /command (MIB3 / Honda HFT) ->
     * event channel (fallback for the old plaintext form).
     *
     * **The split is fixed**: head-unit link bytes come **in** on event-channel `iAPSendMessage`, and our
     * outbound bytes may only be written to the control channel; the head unit does not read writes on the event channel (Frida finding, 2026-10-20
     * reproduced on Honda HFT: after moving outbound to the event channel, the head unit sent not a single FF5A in 20 minutes).
     */
    private fun sendLinkBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> tunnel.writeIapBytes(bytes)
            eventLinkArmed -> sendControlIap(bytes)
            mib3Armed -> sendControlIap(bytes)
            else -> sendEventIap(bytes)
        }
    }

    /**
     * Engine outbound bytes landing: the CSM may only start after SYN|ACK has actually been delivered to the matching channel;
     * a peer engine that receives a bare ACK before seeing SYN|ACK dies immediately (measured on DiPlay).
     */
    private fun flushOutputLocked() {
        val out = engine.takeOutput()
        if (out.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> {
                val ok = tunnel.writeIapBytes(out)
                if (ok && pendingEchoFlush) {
                    pendingEchoFlush = false
                    if (!tunnelEchoDelivered) {
                        tunnelEchoDelivered = true
                        log("IAPLINK: ✓ sync echo written to the tunnel (link negotiation done, sender CSM ready)")
                    }
                }
            }

            eventLinkArmed -> {
                // Inbound on the event channel / outbound on the control channel: replies, sync packets and CSM all go to the control channel
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    log("IAPLINK: ✓ SYN|ACK delivered on the control channel - link negotiation done, starting the control session")
                }
            }

            mib3Armed -> {
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    mib3SynAckQueued = true
                    log("IAPLINK: ✓ MIB3 SYN|ACK delivered on the control channel - waiting for link confirmation before starting CSM")
                }
            }

            else -> {
                sendEventIap(out)
                pendingEchoFlush = false
            }
        }
    }

    private fun drainLocked() {
        while (true) {
            when (val event = engine.pollEvent()) {
                null -> break
                is Iap2LinkEngine.Event.Control ->
                    handleFrameLocked(event.bytes)

                is Iap2LinkEngine.Event.Dead -> {
                    log("IAPLINK: virtual link terminated - ${event.reason ?: "EOF"}")
                    return
                }
            }
        }
        noteLinkStateLocked()
        // Flush first (the echo must reach the peer first), then start the CSM; a timeout step-down may queue new frames, so flush once more at the end
        flushOutputLocked()
        maybeStartControlCsmLocked()
        checkMfiTimeoutLocked()
        flushOutputLocked()
    }

    /** Link state transitions are logged only once (IDLE->NEGOTIATING->NORMAL is the dividing line for whether the key path works). */
    private fun noteLinkStateLocked() {
        val state = engine.state
        if (state == lastLinkState) return
        lastLinkState = state
        log("IAPLINK: link state -> $state (only NORMAL can receive the head unit's 0x6800/0x6802 media keys)")
    }

    /** MFi challenge silent timeout: record it in the head unit's memory and retry with the next length (driven every 200ms by the pacer thread). */
    private fun checkMfiTimeoutLocked() {
        val identity = mfiIdentity ?: return
        if (mfiFinished || mfiCurrentLength == 0) return
        if (nowMillis() - mfiChallengeSentAtMs < mfiWindowMillis) return
        val failed = mfiCurrentLength
        MfiLengthMemory.recordSilent(mfiContext, identity, failed)
        log("IAPLINK: tunnel MFi ${failed}B silent for ${mfiWindowMillis}ms - recorded in memory, retrying with the next length")
        mfiCurrentLength = 0
        startNextMfiChallengeLocked()
    }

    /**
     * Next MFi challenge (order = reordered by the head unit's memory; cold-start baseline prefers 20B, the Frida real-device value).
     * When every length goes unanswered, same semantics as the Bluetooth path: skip verification and go straight to 0xAA05, judged by later head unit behavior.
     */
    private fun startNextMfiChallengeLocked() {
        val identity = mfiIdentity ?: return
        if (mfiOrder.isEmpty()) {
            mfiMemory = MfiLengthMemory.load(mfiContext, identity)
            mfiOrder = MfiLengthMemory.orderAttempts(TUNNEL_MFI_BASELINE, mfiMemory)
            if (!mfiMemory.isEmpty) {
                log(
                    "IAPLINK: tunnel MFi per head unit memory: ${MfiLengthMemory.describe(mfiMemory)}" +
                        " -> order this round ${mfiOrder.joinToString("/")}B",
                )
            }
        }
        mfiPosition += 1
        if (mfiPosition >= mfiOrder.size) {
            log(
                "IAPLINK: tunnel MFi challenges all unanswered - skipping verification, straight to 0xaa05 (judged by later head unit behavior)",
            )
            mfiFinished = true
            mib3AuthStage = 4
            sendCsmLocked(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
            sendMib3BurstLocked()
            return
        }
        mfiCurrentLength = mfiOrder[mfiPosition]
        val base = TUNNEL_MFI_WINDOWS[mfiCurrentLength] ?: TUNNEL_MFI_SILENT_WINDOW_MS
        mfiWindowMillis =
            if (mfiCurrentLength in mfiMemory.silent && mfiCurrentLength != mfiMemory.succeeded) {
                minOf(base, TUNNEL_MFI_SILENT_WINDOW_MS)
            } else {
                base
            }
        mfiChallengeSentAtMs = nowMillis()
        val challenge = ByteArray(mfiCurrentLength).also { SecureRandom().nextBytes(it) }
        sendCsmLocked(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
        mib3AuthStage = 3
        log("IAPLINK: tunnel tx=0xaa02 challenge=${mfiCurrentLength}B (attempt ${mfiPosition + 1}, window ${mfiWindowMillis}ms)")
    }

    /**
     * Control-session start (tunnel/MIB3 forms): once the link is NORMAL and SYN|ACK has been delivered, proactively send
     * 0x1D00 StartIdentification. Without this step the peer control endpoint times out after a 5-minute window and tears the whole link down
     * (root cause measured on DiPlay 2026-10-11; MIB3's teardown window is much shorter, ~10 s).
     */
    private fun maybeStartControlCsmLocked() {
        if (engine.state != Iap2LinkEngine.State.NORMAL) return
        val onTunnel = tunnelWriter() != null
        if (onTunnel) {
            if (tunnelCsmStarted || !tunnelEchoDelivered) return
            tunnelCsmStarted = true
        } else if (eventLinkArmed) {
            // Head-unit-initiated form: outbound goes over the control channel; it is enough that the peer session table has been adopted
            if (eventCsmStarted || engine.peerSynchronization == null) return
            eventCsmStarted = true
        } else {
            if (!mib3Armed || mib3CsmStarted || !mib3SynAckQueued) return
            mib3CsmStarted = true
        }
        sendCsmLocked(Iap2MessageIds.START_IDENTIFICATION)
    }

    /** Sending CSM on the link (enters the engine backlog; the engine flushes by send window). */
    private fun sendCsmLocked(
        messageId: Int,
        body: ByteArray = ByteArray(0),
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        log(
            "IAPLINK: tunnel tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B ok=$ok",
        )
    }

    /** A now-playing update from the media feed thread; false once this link is closed. */
    private fun sendNowPlayingUpdate(frame: ByteArray): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            sendRawCsmLocked(frame).also { drainLocked() }
        }

    /** Artwork for this link's now-playing subscription (iAP2 file-transfer session of the tunnel link). */
    private val artworkTransfer =
        ArtworkTransfer(
            send = { datagram ->
                synchronized(lock) {
                    if (closed) return@synchronized false
                    engine.sendFileTransfer(datagram, nowMillis()).also { drainLocked() }
                }
            },
            maxDatagram = { synchronized(lock) { engine.maxSessionPayload() } },
            log = { line -> log(line) },
        )

    /** The AirPlay session that owns this link is closing: stop feeding it. */
    fun close() {
        synchronized(lock) { closed = true }
        CarNowPlaying.unsubscribe(NOW_PLAYING_LINK, "AirPlay session closed")
        CarRouteGuidance.unsubscribe(NOW_PLAYING_LINK, "AirPlay session closed")
    }

    @Volatile
    private var closed = false

    /** Delivers a complete CSM frame directly (byte-exact constants captured with Frida; do not re-encode). */
    private fun sendRawCsmLocked(frame: ByteArray): Boolean {
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        // CSM frame layout: FF 5A [len:2] [msgId:2] ... - the message ID is at [4..5], [2..3] is the payload length.
        // It was once read from [2..3], so the log printed 0xFFFA as 0x32, 0xFFF0 as 0x0E and 0x4158 as 0x64
        // (the length values), which badly misled debugging (it actually did for one round on 2026-10-23).
        val messageId = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        log(
            "IAPLINK: tunnel tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B raw ok=$ok",
        )
        return ok
    }

    /**
     * Control-session replies (tunnel and MIB3 share the identification/MFi skeleton; MIB3 additionally has the burst and subscription replies):
     *   0x1D01 -> 0x1D02 + 0xAA00; 0xAA06 -> send 0xAA00 again;
     *   0xAA01 -> 0xAA02 challenge (MIB3 20B / DiPlay 32B); 0xAA03 -> 0xAA05 (MIB3 sends the burst along with it).
     * Subscriptions (MIB3 only): 0x4157->0x4158, 0x4154->0x4155, 0x4170->0x4171, 0x5000->0x5001 empty state.
     * 0xEA02 (requestAppLaunch)/0xA101 (VehicleStatus)/0xFFFB (PASCD location samples ~1Hz)
     * get link-level ACKs only (done automatically by the engine); **missing an ACK for 0xFFFB makes the head unit close the control channel after ~10 s as well** (Frida finding).
     */
    private fun maybeRespondControlCsmLocked(frame: Iap2Csm.Frame) {
        val onTunnel = tunnelWriter() != null
        // The head-unit-initiated form reuses the identification/MFi skeleton of MIB3 (outbound also goes over the control channel)
        val mib3 = (mib3Armed || eventLinkArmed) && !onTunnel
        if (!onTunnel && !mib3) return
        val ids = Iap2MessageIds
        when (frame.messageId) {
            ids.IDENTIFICATION_INFORMATION -> {
                val identified = if (onTunnel) tunnelIdentified else mib3Identified
                if (identified) return
                if (onTunnel) tunnelIdentified = true else mib3Identified = true
                val params =
                    Iap2Csm
                        .parseParams(frame.body)
                        .associateBy { it.id }
                log(
                    "IAPLINK: tunnel rx 0x1d01 receiver identity " +
                        "name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                        "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                )
                // Params 6/7 = the message lists the head unit declares "it will send / it can receive". Media-key debugging relies on these two lines:
                // if 0x6800/0x6802 are not in the send list, this head unit does not use iAP2 HID for key delivery at all,
                // and there is no point spending more effort on this path (check the Bluetooth/AVRCP side instead).
                params[6]?.value?.let { raw ->
                    val sends = u16List(raw)
                    log(
                        "IAPLINK: head unit send list(${sends.size})=" +
                            sends.joinToString(" ") { "0x%04x".format(it) } +
                            " has HID0x6800=${sends.contains(Iap2MessageIds.START_HID)}" +
                            " has 0x6802=${sends.contains(Iap2MessageIds.ACCESSORY_HID_REPORT)}",
                    )
                }
                params[7]?.value?.let { raw ->
                    val receives = u16List(raw)
                    log(
                        "IAPLINK: head unit receive list(${receives.size})=" +
                            receives.joinToString(" ") { "0x%04x".format(it) },
                    )
                }
                if (mib3) {
                    // Memory key = head unit identity + " tunnel": kept separate from the Bluetooth link's memory so they do not pollute each other
                    mfiIdentity = mfiIdentityOf(params) + " tunnel"
                }
                sendCsmLocked(ids.IDENTIFICATION_ACCEPTED)
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 1
            }

            MFI_AUTH_SERIAL -> {
                if (!mib3 || mib3AuthStage != 1) return
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 2
                log("IAPLINK: MIB3 0xaa06 (auth serial) -> sending 0xaa00 again")
            }

            ids.MFI_AUTHENTICATION_FAILED -> {
                if (!mib3 || mfiFinished) return
                mfiIdentity?.let { MfiLengthMemory.recordRejected(mfiContext, it, mfiCurrentLength) }
                log("IAPLINK: tunnel MFi ${mfiCurrentLength}B rejected (0xaa04) - recorded in memory, next length")
                mfiCurrentLength = 0
                startNextMfiChallengeLocked()
            }

            ids.MFI_CERTIFICATE -> {
                if (mib3) {
                    // Length follows the head unit's memory (cold start prefers 20B, the Frida real-device value; see startNextMfiChallengeLocked)
                    startNextMfiChallengeLocked()
                } else {
                    // The DiPlay CARLINKIT identity only accepts exactly 32B (a wrong length drops the link immediately)
                    val challenge =
                        ByteArray(32).also { SecureRandom().nextBytes(it) }
                    sendCsmLocked(ids.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
                    mib3AuthStage = 3
                }
            }

            ids.MFI_RESPONSE -> {
                if (mib3) {
                    mfiIdentity?.let { MfiLengthMemory.recordSuccess(mfiContext, it, mfiCurrentLength) }
                    log("IAPLINK: tunnel rx=0xaa03 signature ${frame.body.size}B (${mfiCurrentLength}B challenge succeeded, remembered)")
                    mfiCurrentLength = 0
                    mfiFinished = true
                }
                sendCsmLocked(ids.MFI_AUTHENTICATION_SUCCEEDED)
                if (mib3) {
                    mib3AuthStage = 4
                    sendMib3BurstLocked()
                } else {
                    log("IAPLINK: tunnel MFi done - waiting for the peer subscriptions (it then marks the tunnel ready)")
                }
            }

            START_COMMUNICATIONS_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_COMMUNICATIONS_UPDATE_IDLE)
                log("IAPLINK: MIB3 0x4157 -> 0x4158 (empty state)")
            }

            START_CALL_STATE_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    CALL_STATE_UPDATE,
                    Iap2Csm.param(0x0002, byteArrayOf(0)),
                )
                log("IAPLINK: MIB3 0x4154 -> 0x4155 (no call)")
            }

            START_LIST_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    LIST_UPDATE,
                    Iap2Csm.param(0x0000, byteArrayOf(0)) +
                        Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                        Iap2Csm.param(0x0005, byteArrayOf(0)) +
                        Iap2Csm.param(0x0007, byteArrayOf(0, 0)),
                )
                log("IAPLINK: MIB3 0x4170 -> 0x4171 (empty list)")
            }

            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
                // MIB3: the reply is a prerequisite for staying alive. It keeps the captured iPhone attribute set
                // (NowPlayingUpdate.Request.DEFAULT): with nothing playing it encodes to MIB3_NOW_PLAYING_UPDATE_IDLE
                // byte for byte (unit test), so the bring-up reply is unchanged. Tunnel receivers (WinPlay subscribes
                // here; its Bluetooth link is released right after RECORD) get the attributes they asked for.
                val first =
                    CarNowPlaying.subscribe(
                        NOW_PLAYING_LINK,
                        frame.body,
                        fixedRequest = if (mib3) NowPlayingUpdate.Request.DEFAULT else null,
                        artworkTransfer = artworkTransfer,
                    ) { update -> sendNowPlayingUpdate(update) }
                sendRawCsmLocked(first)
                log("IAPLINK: 0x5000 -> 0x5001 (now playing, ${if (mib3) "MIB3" else "tunnel"})")
            }

            Iap2MessageIds.STOP_NOW_PLAYING_UPDATES -> {
                CarNowPlaying.unsubscribe(NOW_PLAYING_LINK, "head unit sent 0x5002")
            }

            Iap2MessageIds.START_ROUTE_GUIDANCE_UPDATES -> {
                val initial =
                    CarRouteGuidance.subscribe(NOW_PLAYING_LINK, frame.body) { update -> sendNowPlayingUpdate(update) }
                // MIB3's bring-up was captured with no 0x5201 reply here: keep it that way until there is a route
                if (!mib3) initial.forEach { sendRawCsmLocked(it) }
            }

            Iap2MessageIds.STOP_ROUTE_GUIDANCE_UPDATES -> {
                CarRouteGuidance.unsubscribe(NOW_PLAYING_LINK, "head unit sent 0x5203")
            }

            ids.START_HID -> {
                // The head unit declares "I am about to send HID reports", usually with a descriptor (which determines the report byte semantics)
                val params = Iap2Csm.parseParams(frame.body)
                val descriptor = params.map { it.value }.firstOrNull { it.size > 8 }
                if (descriptor != null) {
                    hidDescriptor = descriptor
                    log(
                        "IAPLINK: head unit 0x6800 StartHID descriptor ${descriptor.size}B hex=" +
                            descriptor.joinToString(" ") { "%02x".format(it) }.take(120),
                    )
                } else {
                    log("IAPLINK: head unit 0x6800 StartHID (no descriptor) params=${params.map { it.id }}")
                }
            }

            ids.ACCESSORY_HID_REPORT -> {
                // Steering wheel / panel media keys.
                //
                // Frame measured on Honda HFT 2026-10-20 (11B):
                //   00 06 00 00 00 03 | 00 05 00 01 04
                //   param id=0 value 00 03 (report type/description, constant) <- previously mistaken for the report value
                //   param id=1 value 04 (the real HID report)
                // The old code took "the first param of 1~8 bytes" -> always got 00 03 and set no bit in the bitmap,
                // so the log kept printing `usage=none keyCode=0`: keys arrived but triggered no action.
                val params = Iap2Csm.parseParams(frame.body)
                val report = hidReportFromParams(params)
                // Navigation devices (iDrive knob / four-way stick): Wheel/Hat on the Generic Desktop page,
                // injected as DPAD (needs virtual display focus); must not be dispatched globally as media keys.
                if (report != null && HidNavigation.isNavigationDevice(hidDescriptor)) {
                    val navKeys = HidNavigation.handle("iap2-hid", hidDescriptor, report)
                    if (navKeys.isNotEmpty()) {
                        log("IAPLINK: knob/navigation key " + navKeys.joinToString("+") { HidNavigation.nameOf(it) })
                        navKeys.forEach { onNavKey?.invoke(it) }
                    }
                } else {
                    val keyMap = hidDescriptor?.let(ConsumerKeys::parseDescriptor)
                    val usages =
                        when {
                            report == null -> emptyList()
                            keyMap != null -> keyMap.usagesFor(report)
                            // No descriptor: fall back to "a single byte is the Usage ID"
                            else -> listOf(report[0].toInt() and 0xFF).filter { it != 0 }
                        }
                    if (hidReportFrames < HID_REPORT_VERBOSE_FRAMES) {
                        hidReportFrames++
                        log(
                            "IAPLINK: head unit 0x6802 params=" +
                                params.joinToString(",") { "${it.id}=" + it.value.joinToString("") { b -> "%02x".format(b) } } +
                                " report=" + (report?.joinToString(" ") { "%02x".format(it) } ?: "empty") +
                                " bitmap=" + (keyMap?.isArray?.let { if (it) "Array" else "Variable" } ?: "no descriptor"),
                        )
                    }
                    fireHidKeysLocked(usages)
                }
            }

            else -> {
                // Unhandled messages are logged too: if the head unit delivers media keys/buttons under an unusual ID, this is where
                // the message ID shows up in the log first (key observation point when debugging track-change keys).
                // Full hex included: BMW iD9's 0xad00/0xad02/0xad03 family is neither in Apple's public table
                // nor named by any reference implementation, so the body structure is the only way to infer whether it is an input (touch/HID) path.
                unknownCsmIds++
                if (unknownCsmIds <= 20) {
                    log(
                        "IAPLINK: unhandled CSM 0x${frame.messageId.toString(16)} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=" +
                            frame.body.joinToString(" ") { "%02x".format(it) },
                    )
                }
            }
        }
    }

    /**
     * Converts a batch of "Usages pressed this time" into Android media key injections.
     *
     * The head unit's HID reports are **state-based**: holding a key pushes the same bitmap at ~10Hz and releasing pushes 0x00.
     * So we trigger only once when a key "newly appears" (edge-triggered), otherwise one long press would skip dozens of tracks;
     * re-triggering is allowed only when the same key stays held longer than [HID_HOLD_REPEAT_MILLIS],
     * preserving the steering wheel long-press "keep skipping" feel.
     */
    private fun fireHidKeysLocked(usages: List<Int>) {
        val now = nowMillis()
        if (usages.isEmpty()) {
            lastHidUsages = emptySet()
            hidHoldStartedAtMs = 0L
            return
        }
        val fresh = usages.filter { it !in lastHidUsages }
        val repeat =
            fresh.isEmpty() &&
                hidHoldStartedAtMs > 0 &&
                now - hidHoldStartedAtMs >= HID_HOLD_REPEAT_MILLIS
        lastHidUsages = usages.toSet()
        if (fresh.isNotEmpty()) hidHoldStartedAtMs = now
        if (fresh.isEmpty() && !repeat) return
        if (repeat) hidHoldStartedAtMs = now
        val firing = if (fresh.isNotEmpty()) fresh else usages
        for (usage in firing) {
            val keyCode = ConsumerKeys.keyCodeFor(usage)
            log(
                "IAPLINK: head unit media key ${ConsumerKeys.nameOf(usage)}(0x%02x) -> keyCode=".format(usage) +
                    (if (keyCode != 0) "$keyCode injected" else "no matching Android key (ignored)"),
            )
            if (keyCode != 0) onMediaKey?.invoke(keyCode)
        }
    }

    /**
     * The burst after 0xAA05 (Frida: a real device sends this batch right after authentication succeeds, ahead of the head unit's subscription requests).
     * 0xFFFA/0xFFF0/0x4158/0x5001 use byte-exact constants: their field layouts exist only in real-device captures,
     * and re-encoding easily distorts them (compare the Frida constants in AADisplay WifiIap2Link).
     */
    private fun sendMib3BurstLocked() {
        val sessionId =
            mib3WirelessSessionId ?: UUID.randomUUID().toString().uppercase().also {
                mib3WirelessSessionId = it
            }
        sendCsmLocked(
            WIRELESS_SESSION_ID,
            Iap2Csm.param(0, sessionId.toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(AUX_STATUS_NOTIFICATION, Iap2Csm.param(0x0006, ByteArray(0)))
        sendCsmLocked(
            DEVICE_LANGUAGE,
            Iap2Csm.param(0, "zh".toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(DEVICE_TIME, mib3DeviceTimeParams())
        sendCsmLocked(
            DEVICE_INFORMATION_UPDATE,
            Iap2Csm.param(0, "iPhone".toByteArray(Charsets.UTF_8)),
        )
        sendRawCsmLocked(MIB3_START_LOCATION_INFORMATION)
        sendRawCsmLocked(MIB3_GPRMC_DATA_STATUS)
        log("IAPLINK: MIB3 burst sent (session id $sessionId) - waiting for the head unit subscriptions")
        if (!mib3ControlSessionNotified) {
            mib3ControlSessionNotified = true
            onControlSessionComplete?.invoke()
        }
    }

    /** 0x4E0B: u64 seconds + s16 timezone offset (minutes) + u8 daylight saving. */
    private fun mib3DeviceTimeParams(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(now) / 60_000
        val dst = if (zone.inDaylightTime(java.util.Date(now))) 1 else 0
        val seconds = ByteArray(8)
        for (i in 0 until 8) {
            seconds[i] = (now / 1000L ushr ((7 - i) * 8)).toByte()
        }
        val tz =
            byteArrayOf(
                (offsetMin shr 8).toByte(),
                offsetMin.toByte(),
            )
        return Iap2Csm.param(0, seconds) + Iap2Csm.param(1, tz) + Iap2Csm.param(2, byteArrayOf(dst.toByte()))
    }

    /** Big-endian u16 array (the message ID lists inside the identification info). */
    private fun u16List(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map {
            ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF)
        }

    /** 0x1D01 identity params (name/model/manufacturer) -> memory key material (NULs fully stripped, empty slots skipped). */
    private fun mfiIdentityOf(params: Map<Int, Iap2Csm.Param>): String =
        listOf(0, 1, 2)
            .mapNotNull { id ->
                params[id]
                    ?.value
                    ?.decodeToString()
                    ?.trimEnd('\u0000')
                    ?.takeIf { it.isNotBlank() }
            }.joinToString(" ")

    private fun handleFrameLocked(bytes: ByteArray) {
        frameCount++
        val frames = parser.offer(bytes)
        for (frame in frames) {
            val hex = frame.body.joinToString(" ") { "%02x".format(it) }
            if (frameCount <= 30 || frameCount % 100 == 0) {
                log(
                    "IAPLINK: rx 0x${frame.messageId.toString(16)} " +
                        "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=${hex.take(160)}",
                )
            }
            maybeRespondControlCsmLocked(frame)
        }
        if (frames.isEmpty() && frameCount <= 10) {
            log("IAPLINK: rx ${bytes.size}B (non-CSM) hex=" + bytes.joinToString(" ") { "%02x".format(it) }.take(160))
        }
    }

    /** Engine callback: EA session (no. 2) data frame, a likely carrier for touch HID. */
    var onEaData: ((ByteArray) -> Unit)? = null

    /**
     * Engine callback: Android media keys parsed from HID reports delivered by the iAP2 control session.
     *
     * The head unit's steering wheel/panel previous/next keys travel as `0x6802 AccessoryHIDReport` in CarPlay,
     * and only arrive once the iAP2 link is really established (see [eventLinkArmed]).
     */
    var onMediaKey: ((Int) -> Unit)? = null

    /** Navigation keys (iDrive knob / four-way): handed to the session layer to inject into the virtual display (DPAD_*), not global media keys. */
    var onNavKey: ((Int) -> Unit)? = null

    /** HID descriptor the head unit provides in 0x6800 StartHID (used to parse reports; null = not provided). */
    private var hidDescriptor: ByteArray? = null

    fun handleEaData(bytes: ByteArray) {
        eaCount++
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        if (eaCount <= 30 || eaCount % 100 == 0) {
            log("IAPLINK: EA data #$eaCount ${bytes.size}B hex=${hex.take(200)}")
        }
        onEaData?.invoke(bytes)
    }

    companion object {
        /** Subscription name of this link in CarNowPlaying (the Bluetooth control session holds its own). */
        const val NOW_PLAYING_LINK = "Wi-Fi tunnel"

        /** Tunnel MFi cold-start baseline (Frida real-device value, 20B first; the order is reshuffled by the head unit memory). */
        val TUNNEL_MFI_BASELINE = listOf(20, 32, 16)
        val TUNNEL_MFI_WINDOWS = mapOf(20 to 6_000L, 32 to 3_000L, 16 to 3_000L)
        const val TUNNEL_MFI_SILENT_WINDOW_MS = 1_500L

        /**
         * After how many consecutive SYN markers on the event channel we judge "it is waiting for our marker reply".
         * A real device (MIB3) stops resending after our proactive detect; Honda HFT was measured sending hundreds of times;
         * 2 avoids MIB3's one probe resend without making the head unit wait too long.
         */
        const val EVENT_LINK_MARKER_THRESHOLD = 2

        /**
         * Control-channel negotiation: how long without progress before opening the next round, and how many rounds at most.
         * The head unit marker is 1 Hz and a round is 4 s => each round sees 4 resend chances; 5 rounds ~ 20 s,
         * then stop and wait silently (if it does send 0x1D01 it is still processed normally).
         */
        const val CONTROL_SYN_ROUND_MILLIS = 4_000L
        const val MAX_CONTROL_SYN_ROUNDS = 5

        /**
         * Parameter ID in the 0x6802 AccessoryHIDReport message.
         *
         * Measured on Honda HFT: param 0 = report type/description (`00 03`, never changes),
         * param 1 = the real HID report (e.g. `04` = next track). A wrong param ID decodes every key as "none".
         */
        const val HID_REPORT_DATA_PARAM = 1

        /** Parameter details are printed only for the first few frames (every later frame triggers a key action, so the log is already sufficient). */
        const val HID_REPORT_VERBOSE_FRAMES = 8

        /** How long the same key must stay held before it may trigger again (emulates long-press auto-repeat; a short press never repeats). */
        const val HID_HOLD_REPEAT_MILLIS = 1_500L

        /**
         * 0x6802 param table -> HID report.
         *
         * Prefer param [HID_REPORT_DATA_PARAM]; if absent (some head units give only one parameter) fall back to
         * "the last param of 1~8 bytes". Note that **the first one must not be taken**: on Honda HFT param 0 is always
         * `00 03` (report type), and treating it as the report value makes every key decode as "none".
         */
        fun hidReportFromParams(params: List<Iap2Csm.Param>): ByteArray? =
            params.firstOrNull { it.id == HID_REPORT_DATA_PARAM }?.value?.takeIf { it.isNotEmpty() }
                ?: params.lastOrNull { it.value.isNotEmpty() && it.value.size <= 8 }?.value

        // ---- MIB3 message IDs (a small set with no official names / not in Iap2MessageIds) ----
        const val MFI_AUTH_SERIAL = 0xAA06
        const val WIRELESS_SESSION_ID = 0x4E0C
        const val DEVICE_TIME = 0x4E0B
        const val DEVICE_LANGUAGE = 0x4E0A
        const val DEVICE_INFORMATION_UPDATE = 0x4E09
        const val AUX_STATUS_NOTIFICATION = 0xA100
        const val START_COMMUNICATIONS_UPDATES = 0x4157
        const val CALL_STATE_UPDATE = 0x4155
        const val START_CALL_STATE_UPDATES = 0x4154
        const val START_LIST_UPDATES = 0x4170
        const val LIST_UPDATE = 0x4171

        /** ---- Byte-exact constants below come from Frida captures (MIB3 real iPhone -> Audi MMI) ---- */
        private val HEX_DIGITS = "0123456789abcdef"

        /** 0xFFFA StartLocationInformation. */
        internal val MIB3_START_LOCATION_INFORMATION: ByteArray =
            hex(
                "40400032fffa00080000000000140004000100088001000000fa" +
                    "0004000200088002000000fa000400040008800400000014",
            )

        /** 0xFFF0 GPRMCDataStatusValuesNotification. */
        internal val MIB3_GPRMC_DATA_STATUS: ByteArray =
            hex("4040000efff00004000000040001")

        /** 0x5001 NowPlayingUpdate empty state (no media / not playing). */
        internal val MIB3_NOW_PLAYING_UPDATE_IDLE: ByteArray =
            hex(
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000",
            )

        /** 0x4158 CommunicationsUpdate empty state (usable placeholder when there is no SIM / no signal). */
        internal val MIB3_COMMUNICATIONS_UPDATE_IDLE: ByteArray =
            hex(
                "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                    "00000500050100050006010005000700000500080000050009000005000a000005" +
                    "000b000005000c010005000d000005000e000005000f0000050010000005001100",
            )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                val hi = HEX_DIGITS.indexOf(clean[i * 2])
                val lo = HEX_DIGITS.indexOf(clean[i * 2 + 1])
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
