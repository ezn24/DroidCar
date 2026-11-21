package io.github.ezn24.cardeck.neo.airplay

import android.content.Context
import android.os.SystemClock
import io.github.ezn24.cardeck.neo.audio.AudioCodec
import io.github.ezn24.cardeck.neo.audio.AudioProfile
import io.github.ezn24.cardeck.neo.audio.AudioSender
import io.github.ezn24.cardeck.neo.audio.AudioTuning
import io.github.ezn24.cardeck.neo.bt.AdapterMode
import io.github.ezn24.cardeck.neo.iap2.AdapterTransportId
import io.github.ezn24.cardeck.neo.iap2.Iap2Csm
import io.github.ezn24.cardeck.neo.iap2.Iap2MessageIds
import io.github.ezn24.cardeck.neo.iap2.Iap2SenderSession
import io.github.ezn24.cardeck.neo.iap2.ReceiverProfile
import io.github.ezn24.cardeck.neo.net.CarNetwork
import io.github.ezn24.cardeck.neo.net.EndpointHosts
import io.github.ezn24.cardeck.neo.net.TcpLiveness
import io.github.ezn24.cardeck.neo.shizuku.Shell
import io.github.ezn24.cardeck.neo.util.DeviceIdentity
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * AirPlay client (Phase 2):
 *  - fetching the /info capability list and parsing the bplist
 *  - full /pair-setup + /pair-verify pairing (TLV8 POSTs in sequence on a persistent RTSP connection)
 * The controller long-term identity (ed25519 + pairing ID) is generated once and stored in SharedPreferences.
 * The SETUP/RECORD streaming session is layered on top of this.
 */
class AirPlayClient(
    initialEndpoint: Iap2SenderSession.CarPlayEndpoint,
    private val log: (String) -> Unit,
    private val context: Context? = null,
    /**
     * Head-unit link generation (from the Bluetooth-side 0x1D01 profile, see [ReceiverProfile.linkGenerationOf]).
     * Used for logging only since the second ruling of 2026-10-23: the marker negotiation policy is uniform across generations (without completing link negotiation
     * the head unit tears the link down after ~10 s; the screen switch relies on the modesChanged re-assertion after negotiation completes).
     */
    private val linkGeneration: ReceiverProfile.LinkGeneration? = null,
    /**
     * Endpoint refresher during the pairing window (called before every retry; must not block): returns the endpoint after merging new mDNS results,
     * null = nothing new. The Audi MIB3 `_airplay._tcp` often comes online only after the first resolution;
     * the early implementation queried once before pairing and, after a failure, could only spin on the gateway fallback address until the 120s window ran out.
     */
    private val endpointRefresher: ((Iap2SenderSession.CarPlayEndpoint) -> Iap2SenderSession.CarPlayEndpoint?)? = null,
    /**
     * Low-frequency hook during pairing retries (about every 10 s; must not block): the controller uses it to re-announce the ctrl mDNS
     * and report the mDNS discovery state.
     *
     * Why it is needed: in the 2026-10-19 Audi 0.1.7 log the head unit **never probed ctrl and never broadcast
     * `_airplay._tcp`**, while after the 4 s invite window timed out we only spun on the gateway `:7000` for 54 s
     * (`ECONNREFUSED`), neither re-announcing nor reporting "no mDNS discovered at all".
     * The head unit's Bonjour may miss the first announcement; replaying it periodically is what a real iPhone does
     * (catplay force_announces every 1 s).
     */
    private val retryTick: ((attempt: Int) -> Unit)? = null,
) {
    /**
     * The receiver endpoint currently in use. Hot-updated by the controller when 0x4301 arrives late / mDNS comes online later;
     * the pairing loop re-reads it on every retry, so a change takes effect on the next round.
     */
    @Volatile
    private var endpoint: Iap2SenderSession.CarPlayEndpoint = initialEndpoint

    /** Endpoint generation: ensureConnected uses it to detect "the endpoint was swapped mid-connect", avoiding sending requests meant for the new endpoint to the old address. */
    @Volatile
    private var endpointGeneration = 0

    private var socket: Socket? = null
    private var cSeq = 0

    /**
     * Address used the last time we successfully connected to the receiver (string form; IPv6 link-local addresses carry `%<iface>`).
     *
     * The head unit's (especially Audi's) `_airplay._tcp` / 0x4301 often gives only an **IPv6 link-local**;
     * the old implementation only recognized IPv4 (`contains('.')`), so such endpoints were treated as "no address" and fell back to
     * gateway:7000; on 4xxx firmware the head unit gateway/port is not necessarily .1:7000, so it could never connect.
     * Now, like catplay, we use the address given by Bonjour/0x4301 directly, IPv4 first and IPv6 as fallback,
     * and hand the **address that actually connected** to the video/audio/event channels (they must use the same address as pairing).
     */
    @Volatile
    var activeHost: String? = null
        private set

    /** Resolved result of the connected address (the video/audio ports reuse the same InetAddress to avoid a second resolution failure). */
    @Volatile
    private var activeAddress: InetAddress? = null

    /**
     * Timing service (advertised in the initial SETUP; the head unit NTP-pings it to sync its media clock).
     * Started lazily and kept across retries (close() does not stop it, since the UDP socket has no session state).
     */
    private val timingServer = TimingServer(log)

    /** Enabled after pair-verify: every later RTSP message is wrapped in encryption as a whole. */
    private var cipher: ControlCipher? = null

    /** /info cached during the handshake (for audio negotiation: audioType/format/latency chosen from what the head unit declares). */
    @Volatile
    private var negotiatedInfo: Map<*, *>? = null

    // ---- External flow ----

    /** Whether pair-setup was rejected with 403 (a real-car trait); the controller uses it to decide on the BT retire hack. */
    @Volatile
    var pairSetupRejected: Boolean = false
        private set

    /**
     * A 403 appearing at **any stage** of pairing (pair-setup / pair-verify / the authentication segment).
     *
     * Ruled by real-device capture (2026-10-21 Audi MMI 3315_5GHz sysdiagnose): on first connection a real iPhone is
     * rejected with 403 by the head unit after pair-verify passes (MFi authentication segment), and the head unit **re-sends the
     * ctrl invite by itself after 18~20 s, succeeding the second time**, without ever dropping Bluetooth. So any 403 means "the head unit that iAP2 trust
     * already covers is waiting for its own state machine to become ready"; dropping BT or downgrading to a plaintext session would both be the wrong move,
     * and the right approach is to keep retrying the original flow within the window (the controller avoids both wrong turns by this flag).
     */
    @Volatile
    var pairingForbidden: Boolean = false
        private set

    /** Whether pairing really began this round (TCP connected to the receiver). Enabling condition for the DiPlay link-drop hack. */
    @Volatile
    var attemptedPairing: Boolean = false
        private set

    /**
     * The receiver **does not support** HomeKit pairing at all (/pair-setup answers 404/405/501 or 455).
     *
     * These are pre-HomeKit old head units (CarPlay 210.81 era, receiver class 1 in catplay):
     * they only accept an `et=NONE` plaintext session + AES-CTR screen stream. Retrying inside the pairing window for
     * 120 seconds is pointless; users actually saw "never connects" (2026-10-19 Audi 4xxx).
     */
    @Volatile
    var pairingUnsupported: Boolean = false
        private set

    /** Whether the control channel is encrypted (pair-verify done). false = plaintext session (old head unit / unpaired). */
    val controlEncrypted: Boolean get() = cipher != null

    /**
     * Skips HomeKit pairing and goes straight to the `et=NONE` plaintext session (pre-HomeKit old head units).
     *
     * Based on the `_airplay._tcp` TXT features not declaring bit38 HK_PAIRING_AND_ENCRYPT
     * (catplay receiver class 1: "pre-HomeKit: has no /pair-setup and no
     * HK_PAIRING_AND_ENCRYPT Bonjour flag (v210 era +/-)").
     * Not trying pair-setup saves a request that is bound to fail; if the TXT judgment is wrong, the caller still falls back
     * after SETUP is rejected (never worse than the old behavior).
     */
    fun skipPairing(reason: String) {
        pairingUnsupported = true
        log("AIRPLAY: skipping HomeKit pairing ($reason) - using et=NONE plaintext session + AES-CTR screen stream")
    }

    /** Re-establishes the control channel (after pairing fails or is skipped, a fresh connection is required before SETUP). */
    fun reconnect(timeoutMillis: Int = 5_000): Boolean = ensureConnected(timeoutMillis)

    /**
     * "Encryption upgrade" retry after a plaintext SETUP is rejected (same meaning as catplay `handle_setup_result`):
     * start with `et=NONE` as for pre-HomeKit head units, and if SETUP is rejected and pairing was not tried before,
     * do pair-setup/pair-verify once and SETUP again. A wrong TXT features judgment then does not fail outright.
     */
    fun retryWithPairing(windowMillis: Long = 30_000): PairVerifyClient.ControlKeys? {
        pairingUnsupported = false
        close()
        log("AIRPLAY: plaintext SETUP rejected - trying encryption upgrade (doing the HomeKit pairing now)")
        return runPairing(windowMillis)
    }

    /**
     * Pairing: try pair-setup first (register the long-term public key) -> pair-verify (derive the control keys);
     * if pair-setup is rejected with **403** (measured on a real Audi MIB3: MFi trust was already established in the iAP2 phase and
     * an anonymous SRP pairing window is not accepted), switch to the "already paired device fast path" and go straight to pair-verify.
     * Every retry uses a brand-new TCP connection (some receivers keep their state machine per connection).
     *
     * /info is not pre-warmed: measured on a real car (2026-10-11), the head unit answers /info on an unpaired connection with 455
     * and the pair-setup on that same connection then gets 403; Apple clients and catplay both go straight to pairing.
     * Pairing request headers follow catplay PairingHelperTx byte for byte (X-Apple-HKP / X-Apple-PD).
     */

    /** Cancel flag (set when the session retires): the pairing retry loop exits immediately instead of running out the whole wait window. */
    @Volatile
    var cancelled: Boolean = false

    /** A session-level SETUP succeeded on the current control connection (gates [teardownSession]); cleared by [close]. */
    @Volatile
    private var sessionSetUp = false

    /** Whether the pairing retry loop is still running (the controller uses it to decide whether a "late endpoint" should hot-update or be ignored). */
    @Volatile
    var pairingInProgress: Boolean = false
        private set

    /** Current endpoint (used as a fallback when the controller merges a late endpoint, so a usable address is not wiped when 0x4301 carries no IP). */
    val currentEndpoint: Iap2SenderSession.CarPlayEndpoint get() = endpoint

    /**
     * Hot-updates the receiver endpoint (0x4301 arriving late / new mDNS results during pairing).
     * When the address or port changes, closes the current TCP so the pairing loop reconnects to the new address next round.
     *
     * @return true = the endpoint really changed (switched); false = same as the current one, nothing done
     */
    fun updateEndpoint(
        newEndpoint: Iap2SenderSession.CarPlayEndpoint,
        reason: String,
    ): Boolean {
        val old = endpoint
        val unchanged =
            old.ipAddresses == newEndpoint.ipAddresses &&
                old.airPlayPort == newEndpoint.airPlayPort &&
                old.publicKey == newEndpoint.publicKey &&
                old.deviceIdentifier == newEndpoint.deviceIdentifier
        if (unchanged) return false
        endpoint = newEndpoint
        endpointGeneration++
        log(
            "AIRPLAY: endpoint update ($reason)" +
                "${old.ipAddresses.firstOrNull()}:${old.airPlayPort} → " +
                "${newEndpoint.ipAddresses.firstOrNull()}:${newEndpoint.airPlayPort}" +
                " (the pairing loop uses the new address next round)",
        )
        close()
        return true
    }

    fun runPairing(windowMillis: Long = PAIRING_WINDOW_MILLIS): PairVerifyClient.ControlKeys? {
        pairingInProgress = true
        try {
            return runPairingLoop(windowMillis)
        } finally {
            pairingInProgress = false
        }
    }

    private fun runPairingLoop(windowMillis: Long): PairVerifyClient.ControlKeys? {
        val identity = loadOrCreateIdentity()
        log("AIRPLAY: controller identity pairingId=${identity.pairingId}")
        // The head unit AirPlay window only opens after "the user taps the phone icon on the head unit / a ctrl invite is received"
        // (measured on Audi: the port changes from Connection refused to open). So retries are time-limited:
        // by default every 2 s within 120 s, so a user tapping on the head unit is not given up on early.
        val deadline = System.currentTimeMillis() + windowMillis
        var attempt = 0
        while (System.currentTimeMillis() < deadline && !cancelled) {
            attempt++
            // Absorb new mDNS results before each retry: when the gateway fallback address is unreachable, the head unit's
            // _airplay._tcp may already have been discovered in the background, and switching promptly beats spinning for 120 s.
            endpointRefresher?.invoke(endpoint)?.let { refreshed ->
                updateEndpoint(refreshed, "mDNS refresh during pairing")
            }
            // Low-frequency hook: the controller re-announces ctrl mDNS through it (the head unit Bonjour may miss the first announcement)
            if (attempt == 1 || attempt % RETRY_TICK_ATTEMPTS == 0) {
                runCatching { retryTick?.invoke(attempt) }
            }
            close()
            if (!ensureConnected(2_000)) {
                if (attempt == 1 || attempt % 5 == 0) {
                    log(
                        "AIRPLAY: head unit AirPlay port not open (waiting for you to tap the phone icon on the head unit / the head unit to send a ctrl invite)... retry #$attempt",
                    )
                }
                Thread.sleep(2_000)
                continue
            }
            attemptedPairing = true
            val transport = TlvTransport { path, body -> postPairing(path, body) }
            try {
                val setupStatus =
                    try {
                        PairSetupClient(identity, transport, log).run()
                        null
                    } catch (error: IOException) {
                        val message = error.message.orEmpty()
                        val forbidden = message.contains("403")
                        if (forbidden) {
                            pairSetupRejected = true
                            pairingForbidden = true
                        }
                        val unsupported = PAIRING_UNSUPPORTED_STATUS.any { message.contains(it) }
                        log(
                            "AIRPLAY: pair-setup " +
                                when {
                                    forbidden -> "403 (no anonymous pairing; iAP2 trust exists) -> paired fast path"
                                    unsupported -> "not supported ($message); pre-HomeKit head unit, switching to a plaintext session"
                                    else -> "failed ($message)"
                                },
                        )
                        if (unsupported) {
                            pairingUnsupported = true
                            close()
                            return null
                        }
                        if (forbidden) "403" else "failed"
                    }
                // After a 403 the state machine of the old connection is unusable: use a brand-new connection for the fast path
                if (setupStatus == "403") {
                    close()
                    if (!ensureConnected(2_000)) continue
                    val fastPathTransport = TlvTransport { path, body -> postPairing(path, body) }
                    val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                    val keys = PairVerifyClient(identity, fastPathTransport, log).run(receiverStaticKey)
                    cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                    log("AIRPLAY: control-channel encryption enabled (paired fast path)")
                    return keys
                }
                val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                val keys = PairVerifyClient(identity, transport, log).run(receiverStaticKey)
                // Both sides enable encryption immediately after pair-verify (the receiver turns it on after the M4 reply)
                cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                log("AIRPLAY: control-channel encryption enabled")
                return keys
            } catch (error: IOException) {
                val message = error.message.orEmpty()
                // A 403 in the verify/authentication segment has the same origin as the pair-setup 403 (head unit trust is already established);
                // see the pairingForbidden comment: once flagged, the controller no longer drops BT / downgrades to plaintext
                if (message.contains("403")) pairingForbidden = true
                log("AIRPLAY: pairing attempt #$attempt failed - $message")
                if (PAIRING_UNSUPPORTED_STATUS.any { message.contains(it) }) {
                    pairingUnsupported = true
                    log("AIRPLAY: receiver offers no HomeKit pairing channel - stopping retries, switching to plaintext session (et=NONE)")
                    close()
                    return null
                }
                // 403 backoff (real-device capture: after the head unit rejects the first connection it needs 18~20 s to become acceptable;
                // meanwhile frequent retries only add load on the head unit and may keep resetting its readiness state machine);
                // other failures keep polling quickly until the port opens
                Thread.sleep(pairingRetryBackoffMs(message))
            }
        }
        log("AIRPLAY: pairing finally failed (receiver RTSP not answering)")
        return null
    }

    /** Fetches and parses /info (note: the receiver may not answer during an active session). */
    fun fetchInfo(timeoutMillis: Int = 5_000): Map<*, *>? {
        if (!ensureConnected()) return null
        return try {
            val (statusLine, body) = requestInfo(timeoutMillis)
            log("AIRPLAY: ✓ /info [$statusLine] body=${body.size} bytes")
            if (body.isEmpty()) return null
            val root = BinaryPlist.parse(body)
            if (root is Map<*, *>) {
                logInfoSummary(root)
                root
            } else {
                null
            }
        } catch (error: IOException) {
            log("AIRPLAY: /info failed - ${error.message}")
            null
        }
    }

    /**
     * KeepAlive UDP heartbeat (see catplay KeepAliveClient): sends a 4-byte
     * random UDP packet to the head unit's keepAlivePort; the protocol has no reply. **Without it the head unit 10 s watchdog tears down the whole session**.
     *
     * The interval is **5 s** rather than catplay's 10 s: 10 s equals the head unit watchdog, and the
     * millisecond-level jitter of `Thread.sleep` makes every heartbeat arrive 1 ms after the deadline (measured twice on Audi 3315, 2026-10-19:
     * ping #2 came exactly 10.001 s after #1, then within 213~247 ms the whole session was reset, 10.2 s after RECORD).
     * catplay's 10 s only suits its own receiver; against real cars at least half the margin must be left.
     */
    fun startKeepAlive(
        host: String,
        port: Int,
    ) {
        if (port <= 0) return
        Thread({
            var udp: java.net.DatagramSocket? = null
            try {
                udp = java.net.DatagramSocket().also { CarNetwork.bind(it, "keepAlive UDP") }
                val address = java.net.InetAddress.getByName(host)
                var sent = 0
                var lastSentAt = 0L
                while (isSessionAlive() && udp?.isClosed == false) {
                    val now = SystemClock.uptimeMillis()
                    val gap = if (lastSentAt == 0L) 0L else now - lastSentAt
                    lastSentAt = now
                    val payload = ByteArray(4).also(java.security.SecureRandom()::nextBytes)
                    udp.send(java.net.DatagramPacket(payload, payload.size, address, port))
                    sent++
                    if (sent <= 3 || sent % 60 == 0) {
                        log("AIRPLAY: keepAlive ping #$sent -> $host:$port (interval ${gap}ms)")
                    }
                    Thread.sleep(KEEPALIVE_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
            } catch (error: IOException) {
                log("AIRPLAY: keepAlive ended - ${error.message}")
            } finally {
                udp?.close()
            }
        }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * RTSP /feedback heartbeat (see catplay FEEDBACK_INTERVAL=1000ms): POSTs an empty body to
     * /feedback every second, the receiver's activity watchdog. RTSP transactions are serialized by [rtspLock].
     */
    fun startFeedbackLoop() {
        Thread({
            var sent = 0
            while (isSessionAlive()) {
                try {
                    val (status, body) = request("POST", "/feedback", ByteArray(0))
                    sent++
                    if (sent <= 6 || sent % 30 == 0) {
                        log("AIRPLAY: feedback #$sent [$status]${if (body.isNotEmpty()) " body=${body.size}B" else ""}")
                    }
                    // The head unit feedback carries per-stream status (received or not, underrun): when audio is silent this is the most direct
                    // evidence, telling "the head unit received no packets" apart from "received but not playing" (2026-10-17).
                    // The first 8 are all logged (covering about 8 s after the stream starts, exactly the 6.5 s teardown watchdog window):
                    // whether the audio sampleTime advances from 0 is hard evidence for "packets are being consumed".
                    if (body.isNotEmpty() && (sent <= 8 || sent % 30 == 0)) {
                        logFeedbackBody(body)
                    }
                } catch (_: IOException) {
                    break // connection gone (session ended)
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "airplay-feedback").apply {
            isDaemon = true
            start()
        }
    }

    /** Parses and prints the /feedback reply (bplist). Fields follow catplay `StreamFeedback`:
     *  sr / type / streamConnectionID / sampleTime / timestamp / timestampRawNs
     *  (no packet count: the head unit returns its own-side playback clock, from which one can tell whether the audio stream is advancing). */
    private fun logFeedbackBody(body: ByteArray) {
        val root =
            runCatching { BinaryPlist.parse(body) as? Map<*, *> }.getOrNull()
        if (root == null) {
            log("AIRPLAY: feedback body parse failed (${body.size}B) hex=${body.joinToString("") { "%02x".format(it) }}")
            return
        }
        val streams = root["streams"] as? List<*>
        if (streams == null) {
            log("AIRPLAY: feedback has no streams key (keys=${root.keys}) all fields=$root")
            return
        }
        streams.forEachIndexed { index, entry ->
            val stream = entry as? Map<*, *> ?: return@forEachIndexed
            log(
                "AIRPLAY: feedback.stream[$index] type=${stream["type"]} sr=${stream["sr"]} " +
                    "connId=${stream["streamConnectionID"]} sampleTime=${stream["sampleTime"]} " +
                    "ts=${stream["timestamp"]} tsRawNs=${stream["timestampRawNs"]} all fields=$stream",
            )
        }
    }

    /** Liveness criterion of the heartbeat thread: the RTSP socket is still connected. */
    private fun isSessionAlive(): Boolean = socket?.isClosed == false

    /**
     * Ends the session on the receiver before the control channel is closed (chengshl/cardeck T42), the way an iPhone
     * does when CarPlay is stopped. Without it the head unit only learns the session is gone from its own watchdog, and
     * some units refuse or delay the next SETUP while they still hold the old one.
     *
     * Sent only when a session-level SETUP succeeded on this connection, and bounded by [timeoutMillis] so a head unit
     * that has already gone away cannot stall shutdown. Network I/O: never call on the main thread.
     */
    fun teardownSession(timeoutMillis: Int = TEARDOWN_TIMEOUT_MS): Boolean {
        if (!sessionSetUp || socket?.isClosed != false) return false
        sessionSetUp = false
        return try {
            val (statusLine, _) =
                request(
                    "TEARDOWN",
                    "/",
                    BinaryPlist.encode(linkedMapOf<String, Any?>()),
                    timeoutMillis = timeoutMillis,
                    contentType = "application/x-apple-binary-plist",
                )
            log("AIRPLAY: session TEARDOWN [$statusLine]")
            true
        } catch (error: IOException) {
            log("AIRPLAY: session TEARDOWN not answered - ${error.message}")
            false
        }
    }

    fun close() {
        sessionSetUp = false
        runCatching { socket?.close() }
        socket = null
        activeHost = null
        activeAddress = null
        // A new connection always starts in **plaintext** (encryption is switched on only after pair-verify succeeds): the old connection's cipher must
        // be cleared, otherwise after reconnecting the reader thread decodes plaintext replies as ciphertext frames and every later request times out.
        cipher = null
        synchronized(rtspWriteLock) {
            readerSocket = null
            readerThread = null
            readerGeneration++
        }
        failAllPending("control channel closed")
        runCatching { iapTunnel?.socket?.close() }
        iapTunnel = null
        iapLink?.close()
        iapLink = null
        resetAdapterSessionState()
        // Note: controlIapTx is not shut down here: close() is reused by retry paths on the same instance
        // (retryWithPairing etc.), and killing the executor would break MIB3 outbound after a reconnect;
        // the cost of a resident daemon thread is negligible (one per client instance)
    }

    /**
     * Declares the HID input mode (hidSetInputMode, FromDevice direction: the touch-enable command from phone to head unit).
     * Follows catplay CommandHidSetInputMode {hidInputMode u8, uuid string};
     * uuid is the hex string of /info hidDevices[].uuid (same uid.toString(16) as DiPlay sendHidReport).
     * Audi has display features=0x08 (high-fidelity touch) and a complete hidDevices, so the prime suspect for touch sending no reports
     * is that this arming step is missing.
     */
    fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int = 0,
    ): Boolean {
        if (AdapterMode.active) {
            // Apple's 320.17 receiver reads requests only on the control channel; the reference
            // sender never sends this and the smartBox's touch works without it.
            log("AIRPLAY: adapter mode - skipping hidSetInputMode uuid=$deviceUuidHex (event channel)")
            return false
        }
        val sent =
            postEventCommand(
                linkedMapOf(
                    "type" to "hidSetInputMode",
                    "params" to
                        linkedMapOf<String, Any?>(
                            "hidInputMode" to mode.toLong(),
                            "uuid" to deviceUuidHex,
                        ),
                ),
            )
        log("AIRPLAY: hidSetInputMode(event channel) uuid=$deviceUuidHex mode=$mode ok=$sent")
        return sent
    }

    /**
     * Declares resource ownership (modesChanged): the phone holds the main screen + main audio (see catplay assert_modes).
     * Order verified on real cars: modesChanged -> screen-stream SETUP. Without this declaration the Audi MIB3
     * answers the screen-stream SETUP with 400 right away (resource arbitration not passed). appStates: phone/navigation go to None (0),
     * speech None (-1); resources: main screen (1) / main audio (2), entity=permanent=Controller (1).
     */

    // ---- Event-channel command sending (see DiPlay sendCommandLocked) ----
    // Head-unit commands go both ways as POST /command on the event channel socket (not the RTSP control connection!):
    // iAPSendMessage (virtual link bytes), hidSetInputMode, modesChanged, etc.
    // Async fire-and-forget: no waiting for a reply (same as DiPlay), with its own CSeq counter.

    @Volatile
    private var eventCommandSocket: Socket? = null

    /** null = plaintext event channel (an et=NONE old head unit session, catplay `EventsServer::unencrypted`). */
    @Volatile
    private var eventCommandCipher: ControlCipher? = null

    private val eventCommandLock = Any()
    private var eventCseq = 0

    /** Count of unknown requests (legitimate requests other than /command): the first 10 are logged, then silent. */
    @Volatile
    private var unknownEventRequests = 0

    /** Count of /command plists that could not be decoded: the first 8 are logged with raw hex (for debugging the head unit command path). */
    @Volatile
    private var badEventCommands = 0

    /**
     * "Body fingerprint" (length + first 8 bytes) of /command bodies that could not be decoded.
     *
     * The head unit usually resends the same batch of commands every second; deduplicating by fingerprint, full forensics are done only for **newly seen** body shapes,
     * which neither floods the log nor misses any kind (earlier we truncated by count only, so on another car model we saw only the first 8).
     */
    private val badEventSignatures = LinkedHashSet<String>()

    /** Called by connectEventChannel after the event channel is established. cipher=null means a plaintext channel. */
    private fun registerEventCommandChannel(
        socket: Socket,
        cipher: ControlCipher?,
    ) {
        eventCommandSocket = socket
        eventCommandCipher = cipher
    }

    /** Sends POST /command on the event channel (encrypted when the channel is encrypted). Returns false if the channel is not ready. */
    fun postEventCommand(command: Map<String, Any?>): Boolean {
        val socket = eventCommandSocket ?: return false
        val cipher = eventCommandCipher
        return try {
            synchronized(eventCommandLock) {
                eventCseq++
                val body = BinaryPlist.encode(command)
                val head =
                    "POST /command RTSP/1.0\r\n" +
                        "Content-Type: application/x-apple-binary-plist\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "CSeq: $eventCseq\r\n\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + body
                val bytes = cipher?.encrypt(plaintext) ?: plaintext
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            }
            true
        } catch (error: IOException) {
            log("EVENT: command send failed - ${error.message}")
            false
        }
    }

    /**
     * Event-channel request replies: reply RTSP/1.0 200 OK only to **head unit requests**, echoing their CSeq
     * (same as DiPlay buildResponse, see RtspMessage.kt:76). Pitfall record (2026-10-11):
     * we used to answer every inbound message with 200; answering an "answer" ping-pongs with DiPlay: DiPlay parses our
     * extra reply as a request ("RTSP/1.0" stripped as the method, so protocol takes the third token),
     * emitting a malformed line "OK 200 OK"; we reply again -> endless echo, a log avalanche and a needlessly busy encrypted channel.
     * Channel writes share one lock with postEventCommand so that encrypted frames of two threads do not interleave.
     */
    private fun respondEventRequest(cseq: String?) {
        val socket = eventCommandSocket ?: return
        val cipher = eventCommandCipher
        try {
            synchronized(eventCommandLock) {
                val head =
                    "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n" +
                        (cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().apply {
                    write(cipher?.encrypt(plaintext) ?: plaintext)
                    flush()
                }
            }
        } catch (_: IOException) {
        }
    }

    fun assertModes(): Boolean {
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "type" to "modesChanged",
                    "params" to
                        linkedMapOf(
                            "appStates" to
                                listOf(
                                    linkedMapOf<String, Any?>("appStateID" to 2L, "entity" to 0L),
                                    linkedMapOf<String, Any?>("appStateID" to 3L, "entity" to 0L),
                                    linkedMapOf<String, Any?>("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
                                ),
                            "resources" to
                                listOf(
                                    linkedMapOf<String, Any?>("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L),
                                    linkedMapOf<String, Any?>("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L),
                                ),
                        ),
                ),
            )
        return try {
            val (statusLine, _) =
                request(
                    "POST",
                    "/command",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            log("AIRPLAY: modesChanged (declaring ownership of main screen/main audio resources) [$statusLine]")
            statusLine.contains("200")
        } catch (error: IOException) {
            log("AIRPLAY: modesChanged failed - ${error.message}")
            false
        }
    }

    /**
     * SETUP #1: sends the session info, receives eventPort/timingPort/enabledFeatures.
     *
     * features must send the "full iPhone capability list": the receiver computes
     * enabledFeatures = head unit supported ∩ phone proposed, and **any capability not in the proposal is never enabled**.
     * Lesson from Audi on 2026-10-11: proposing only ["videoPlayback"] made the head unit return
     * enabledFeatures=[] (video pipeline not enabled), and the following screen-stream SETUP got 400.
     * The list follows catplay (a sender verified on real cars, including the video-required items such as viewAreas/uiContext).
     */
    fun runSetup(): SetupInfo? {
        // Timing port: the real catplay implementation always binds a real UDP timing server and advertises it to the head unit
        // (media clock sync source); timingPort=0 was our prime suspect for the screen-stream 400.
        if (!timingServer.start()) {
            log("AIRPLAY: timing service not started (continuing, but the media clock cannot be synced)")
        }
        val deviceId = setupDeviceId()
        val adapterMac =
            if (AdapterMode.active) {
                wifiMacAddress().also { mac ->
                    log(
                        "AIRPLAY: adapter mode SETUP identity deviceID=$deviceId macAddress=" +
                            (mac ?: "$DEFAULT_SETUP_MAC_ADDRESS (Wi-Fi interface MAC not readable)"),
                    )
                }
            } else {
                null
            }
        val body =
            buildSessionSetupBody(
                deviceId,
                timingServer.port,
                encrypted = cipher != null,
                macAddress = adapterMac ?: DEFAULT_SETUP_MAC_ADDRESS,
            )
        log(
            "AIRPLAY: initial SETUP body=${body.size}B timingPort=${timingServer.port} " +
                "deviceID=$deviceId encrypted=${if (cipher != null) "yes" else "no (et=NONE)"}",
        )
        val (statusLine, responseBody) =
            try {
                // The request URI uses the relative path `/setup` (same as catplay AirPlayRtspClient::initial_setup):
                // some old firmware (210.81 era) only accepts relative URIs, and an absolute `rtsp://host:port/` yields 404/400.
                request(
                    "SETUP",
                    "/setup",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: SETUP failed - ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log("AIRPLAY: SETUP reply [$statusLine] (self-reported deviceID=$deviceId)")
            if (statusLine.contains("403")) {
                // The two causes of 403 ordered by likelihood: device filtering (WinPlay and others that "only accept a specified
                // iPhone") is the most common, followed by a pairing/trust state the receiver does not accept. Printing this phone's
                // Bluetooth address directly lets the user self-check against the receiver settings.
                log(
                    "AIRPLAY: 403 diagnosis - if the receiver has device filtering enabled, " +
                        "select this phone's Bluetooth address on the receiver " +
                        "$deviceId" +
                        if (deviceId == DeviceIdentity.PLACEHOLDER_MAC) {
                            "(currently a placeholder: the real Bluetooth MAC could not be resolved; is Shizuku ready?)"
                        } else {
                            ""
                        },
                )
            }
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody)
            } catch (error: IllegalArgumentException) {
                log("AIRPLAY: SETUP reply plist parse failed (${responseBody.size} bytes)")
                null
            } as? Map<*, *>
        if (root == null) {
            log("AIRPLAY: SETUP reply is not a dictionary")
            return null
        }
        // Fetch the main display parameters (follow the receiver screen orientation; a landscape head unit should be pushed a landscape picture, with touch coordinates aligned accordingly)
        // This **must not fail silently**: if /info is down, display geometry, HID devices (touch) and audio capabilities
        // all fall back to hard-coded values. BMW iD9 on 2026-10-20 failed silently right here, and the log kept only the two results
        // `hidDevices uuids=[]` / `displayUuid=null`, with no visible cause.
        var infoRoot: Map<*, *>? = null
        val infoMap = loadInfoWithDiagnostics()
        infoRoot = infoMap
        val display = (infoMap?.get("displays") as? List<*>)?.firstOrNull() as? Map<*, *>
        if (display == null) {
            log(
                "AIRPLAY: no /info displays obtained -> display parameters fall back to the phone resolution" +
                    " (the head unit may show a small picture/black borders); empty HID devices -> the head unit will not send hidSendReport (touch unavailable)",
            )
        }
        // Full /info dump: look for Audi's private data stream UUID (candidate for the type-130 clientTypeUUID),
        // HID device descriptions (touch), and any capability declaration we have not implemented
        // uuid is kept verbatim (Audi sends the strings "0"/"1", DiPlay uses integers; hidSetInputMode echoes it back verbatim)
        val parsedHidUuids = ArrayList<String>()
        // uuid -> HID descriptor (media-key records need the Usage by index; touch records do not, but are kept too)
        val parsedHidDescriptors = HashMap<String, ByteArray>()
        infoRoot?.let { root ->
            val skipped = setOf("displays")
            val summary =
                root.entries.joinToString("; ") { (key, value) ->
                    if (key in skipped) "$key=[${(value as? List<*>)?.size ?: "?"} items]" else "$key=${value.toString().take(200)}"
                }
            log("AIRPLAY: /info fields: $summary")
            // displays is already printed entry by entry in logInfoSummary (with key/value details), so not repeated here
            // Audio capabilities are printed entry by entry (the whole /info line is cut by take(200), and audioFormats breaks exactly in the middle):
            // audioType can only be decided from here: whether Audi type=100 declares compatibility or media
            (root["audioFormats"] as? List<*>)?.forEachIndexed { index, entry ->
                val format = entry as? Map<*, *> ?: return@forEachIndexed
                val output = (format["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                val input = (format["audioInputFormats"] as? Number)?.toLong() ?: 0L
                log(
                    "AIRPLAY: audioFormat[$index] type=${format["type"]} audioType=${format["audioType"]} " +
                        "output=0x${output.toString(16)} input=0x${input.toString(16)}" +
                        " (PCM44.1k/48k stereo=" +
                        "${if ((output and (1L shl 11)) != 0L) "✓" else "✗"}" +
                        "${if ((output and (1L shl 15)) != 0L) "✓" else "✗"}, " +
                        "AAC-LC 44.1k=${if ((output and (1L shl 22)) != 0L) "✓" else "✗"})",
                )
            }
            (root["audioLatencies"] as? List<*>)?.forEachIndexed { index, entry ->
                val latency = entry as? Map<*, *> ?: return@forEachIndexed
                log(
                    "AIRPLAY: audioLatency[$index] type=${latency["type"]} audioType=${latency["audioType"]} " +
                        "in=${latency["inputLatencyMicros"]} out=${latency["outputLatencyMicros"]}",
                )
            }
            for (hidKey in listOf("hidDevices", "hids", "hid")) {
                (root[hidKey] as? List<*>)?.forEachIndexed { index, h ->
                    log("AIRPLAY: /info $hidKey[$index]=$h")
                    if (h is Map<*, *>) {
                        // Measured on Audi: uuid is the string "0"/"1" (13:38 diagnostics uuidClass=String);
                        // DiPlay uses an integer. Everything is converted to a string, and hidSetInputMode/hidSendReport echo it back verbatim
                        val uuid =
                            when (val raw = h["uuid"]) {
                                is Number -> raw.toString()
                                is String -> raw
                                else -> null
                            }
                        if (uuid != null) parsedHidUuids += uuid
                        (h["hidDescriptor"] as? ByteArray)?.let { descriptor ->
                            log(
                                "AIRPLAY: hidDescriptor[$index] ${descriptor.size}B hex=" +
                                    descriptor.take(96).joinToString(" ") { "%02x".format(it) },
                            )
                            // The Usage array order of media keys (Consumer Control) can only be obtained from the descriptor:
                            // the bytes of an Array report are "indexes", not Usage IDs (see the ConsumerKeys comment)
                            if (uuid != null) parsedHidDescriptors[uuid] = descriptor
                        }
                    }
                }
            }
        }
        log("AIRPLAY: hidDevices parse result uuids=$parsedHidUuids")
        sessionSetUp = true
        val info =
            SetupInfo(
                eventPort = portOf(root["eventPort"]),
                timingPort = portOf(root["timingPort"]),
                keepAlivePort = root["keepAlivePort"]?.let(::portOf)?.takeIf { it != 0 },
                enabledFeatures = (root["enabledFeatures"] as? List<*>)?.map { it.toString() }.orEmpty(),
                displayWidth = ((display?.get("widthPixels") as? Long) ?: 1080L).toInt(),
                displayHeight = ((display?.get("heightPixels") as? Long) ?: 2320L).toInt(),
                // Display UUID of /info displays[0] (a real iPhone fills it back into the screen-stream SETUP;
                // the key name is lowercase "uuid", with uppercase "UUID" as a fallback)
                displayUuid =
                    uuidText(
                        display?.get("uuid")
                            ?: display?.get("UUID")
                            ?: display?.get("displayUUID"),
                    ),
                maxFps = ((display?.get("maxFPS") as? Long) ?: 30L).toInt(),
                hidDeviceUuids = parsedHidUuids,
                hidDescriptors = parsedHidDescriptors,
                // Audi MMI 3315 declares true: the screen-stream keepAlive uses opcode=5 with a bplist stats body
                keepAliveSendStatsAsBody = (infoRoot?.get("keepAliveSendStatsAsBody") as? Boolean) == true,
                // HEVC needs both halves: the empty dictionary hevcInfo in /info + enabledFeatures of the session SETUP reply containing "hevc"
                // (our proposal list CONTROLLER_FEATURES already carries hevc). Protocol facts come from CarPlayStation
                // (GPL, read-only comparison): HEVC is marked neither in the features bitmap nor in extendedFeatures.
                hevc =
                    infoRoot?.containsKey("hevcInfo") == true &&
                        (root["enabledFeatures"] as? List<*>)?.any { it.toString() == "hevc" } == true,
            )
        sessionEnabledFeatures = info.enabledFeatures
        log(
            "AIRPLAY: ✓ SETUP done eventPort=${info.eventPort} timingPort=${info.timingPort} " +
                "keepAlive=${info.keepAlivePort ?: "none"} features=${info.enabledFeatures} " +
                "display=${info.displayWidth}x${info.displayHeight}@${info.maxFps} hevc=${info.hevc} " +
                "displayUuid=${info.displayUuid}",
        )
        return info
    }

    /**
     * Fetches `/info` with full forensics (status line / body length / parse exception / raw hex).
     *
     * The earlier code was `catch (_: Exception) { null }`: once the head unit refused, everything went completely silent (2026-10-20
     * BMW iD9: the `/info fields` log line never appeared, only `uuids=[]` and `displayUuid=null`).
     * When refused inside a session, an extra **separate short connection** probe is made: the receiver's AirPlay service usually allows `/info` (capability query)
     * for unbound sessions, whereas `/info` inside an active session is often rejected with 455/403.
     */
    private fun loadInfoWithDiagnostics(): Map<*, *>? {
        var statusLine = "not requested"
        var body = ByteArray(0)
        try {
            val first = requestInfo()
            statusLine = first.first
            body = first.second
            if (!statusLine.contains("200") || body.isEmpty()) {
                log("AIRPLAY: /info first attempt [$statusLine] body=${body.size}B -> retrying once after 800ms")
                Thread.sleep(800)
                ensureConnected(2_000)
                val retry = requestInfo(5_000)
                log("AIRPLAY: /info retry [${retry.first}] body=${retry.second.size}B")
                if (retry.first.contains("200") && retry.second.isNotEmpty()) {
                    statusLine = retry.first
                    body = retry.second
                }
            }
        } catch (error: Exception) {
            log("AIRPLAY: /info request exception - ${error.javaClass.simpleName}: ${error.message}")
        }
        parseInfoBody(statusLine, body)?.let { return it }
        scheduleSideProbe()
        return null
    }

    /**
     * Delays by [delayMillis], then goes through the side channel `/info`.
     *
     * Why not probe immediately: the first 20 s after the session is established are the most critical forensic window (head unit subscriptions, `/command` every second,
     * tunnel CSM). If the side connection made the head unit tear down the main session we would lose the whole round of evidence. A delayed probe, even if something goes wrong,
     * still lets us come back with a complete log to pinpoint it.
     *
     * As soon as a HID device is found, `hidSetInputMode` is sent right away: touch may take effect immediately (the display geometry is already fixed this round and
     * cannot change, but the input path can be added without rebuilding the session).
     */
    private fun scheduleSideProbe(delayMillis: Long = 25_000) {
        Thread(
            {
                runCatching { Thread.sleep(delayMillis) }
                val info = probeInfoOnSideConnection() ?: return@Thread
                val uuids =
                    (info["hidDevices"] as? List<*>).orEmpty().mapNotNull { entry ->
                        val hid = entry as? Map<*, *> ?: return@mapNotNull null
                        when (val raw = hid["uuid"]) {
                            is Number -> raw.toString()
                            is String -> raw
                            else -> null
                        }
                    }
                if (uuids.isEmpty()) {
                    log(
                        "AIRPLAY: side-channel /info succeeded but the head unit declares no HID device -> touch does not use the AirPlay HID path",
                    )
                    return@Thread
                }
                log("AIRPLAY: side channel found HID devices $uuids -> sending hidSetInputMode again (touch may take effect immediately)")
                uuids.forEach { uuid -> runCatching { sendHidSetInputMode(uuid, mode = 0) } }
            },
            "info-side-probe",
        ).apply { isDaemon = true }.start()
    }

    /** `/info` message -> dictionary. On failure logs the reason and raw hex, and caches into negotiatedInfo. */
    private fun parseInfoBody(
        statusLine: String,
        body: ByteArray,
    ): Map<*, *>? {
        log("AIRPLAY: /info reply [$statusLine] body=${body.size}B")
        if (body.isEmpty()) return null
        val info =
            try {
                val result = BinaryPlist.parseRecovered(body)
                result.note?.let { log("AIRPLAY: /info $it") }
                result.value as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                log("AIRPLAY: /info plist parse failed - ${error.message}")
                log("AIRPLAY: /info raw body (${body.size}B) hex=" + hexDump(body, 320))
                null
            }
        if (info == null) return null
        logInfoSummary(info)
        negotiatedInfo = info
        return info
    }

    /**
     * Probes `/info` over a separate short connection (unencrypted, sessionless): the fallback when it is refused inside a session.
     *
     * Opens a fresh TCP only, sends `GET /info`, reads one message and closes; it does not touch the control channel state machine,
     * so if the head unit does not accept it (455/403/timeout) it is merely a wasted trip and does not affect the already running video stream.
     */
    private fun probeInfoOnSideConnection(timeoutMillis: Int = 4_000): Map<*, *>? {
        val target = endpoint
        if (target.airPlayPort <= 0) return null
        val host =
            activeHost ?: EndpointHosts.candidates(target.ipAddresses).firstOrNull() ?: return null
        var probe: Socket? = null
        return try {
            log("AIRPLAY: /info side-channel probe -> $host:${target.airPlayPort} (separate short connection, session untouched)")
            probe =
                Socket().apply {
                    CarNetwork.bind(this, "/info probe")
                    connect(InetSocketAddress(resolveAddress(host), target.airPlayPort), timeoutMillis)
                    soTimeout = timeoutMillis
                }
            val payload = BinaryPlist.encode(linkedMapOf<String, Any?>())
            val head =
                "GET /info RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
                    "Content-Type: application/x-apple-binary-plist\r\n" +
                    "Content-Length: ${payload.size}\r\n\r\n"
            probe.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII) + payload)
                flush()
            }
            val input = probe.getInputStream()
            val collected = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var expected = -1
            while (true) {
                val count =
                    try {
                        input.read(chunk)
                    } catch (_: java.net.SocketTimeoutException) {
                        -1
                    }
                if (count < 0) break
                collected.write(chunk, 0, count)
                val bytes = collected.toByteArray()
                if (expected < 0) {
                    val separator = indexOf(bytes, HEAD_SEPARATOR)
                    if (separator >= 0) {
                        val (_, contentLength) =
                            parseRtspHead(String(bytes, 0, separator, Charsets.ISO_8859_1))
                        expected = separator + HEAD_SEPARATOR.size + contentLength
                    }
                }
                if (expected >= 0 && bytes.size >= expected) break
            }
            val bytes = collected.toByteArray()
            val separator = indexOf(bytes, HEAD_SEPARATOR)
            if (separator < 0) {
                log("AIRPLAY: /info side channel got no valid reply (${bytes.size}B; the head unit may only serve bound sessions)")
                return null
            }
            val headText = String(bytes, 0, separator, Charsets.ISO_8859_1)
            val (line, contentLength) = parseRtspHead(headText)
            val bodyStart = separator + HEAD_SEPARATOR.size
            val infoBody = bytes.copyOfRange(bodyStart, minOf(bodyStart + contentLength, bytes.size))
            return parseInfoBody("side channel $line", infoBody)
        } catch (error: Exception) {
            log("AIRPLAY: /info side channel failed - ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            runCatching { probe?.close() }
        }
    }

    class SetupInfo(
        val eventPort: Int,
        val timingPort: Int,
        val keepAlivePort: Int?,
        val enabledFeatures: List<String>,
        /** Main display parameters (/info displays[0], following the receiver's current screen orientation). */
        val displayWidth: Int,
        val displayHeight: Int,
        val displayUuid: String?,
        val maxFps: Int,
        /** /info hidDevices[].uuid list (0=touch, 1=knob; same numbering as DiPlay). */
        val hidDeviceUuids: List<String> = emptyList(),
        /**
         * uuid -> HID descriptor. The Array report bytes of media keys (Consumer Control) are Usage array indexes,
         * so the descriptor must be parsed to get the right mapping (measured on Audi: pressing "next track" yields 0x05=usages[5]=0xB5).
         */
        val hidDescriptors: Map<String, ByteArray> = emptyMap(),
        /**
         * `/info.keepAliveSendStatsAsBody`: whether screen-stream keepAlive frames carry a stats body
         * (true -> opcode=5 + bplist; false -> opcode=2 empty frame). true on Audi 3315.
         */
        val keepAliveSendStatsAsBody: Boolean = false,
        /** The receiver declares and accepts HEVC (see where [hevc] is assigned). */
        val hevc: Boolean = false,
    )

    /** Screen-stream negotiation result. */
    class ScreenStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        /** Frame encryption: ChaCha20 (paired) or AES-128-CTR (unpaired session). */
        val cipher: StreamFrameCipher,
    )

    /**
     * iAP tunnel (type-130 DataStream, see DiPlay IapTunnel): the iAP2
     * EA data channel of modern CarPlay; **iAP2 messages from head unit to phone (including touch HID) travel over this encrypted TCP**,
     * and phone to head unit uses the event-channel iAPSendMessage command ([sendIap]).
     * Wire format: ChaCha frame (2B LE length as AAD + ciphertext + 16B tag, nonce = 8B LE counter,
     * key = HKDF(shared, "DataStream-Salt$seed", "DataStream-Output-Encryption-Key"))
     * containing an APTransportPackage (4B BE total length, 32B header, type="comm" at offset 16) -> body = iAP2 message.
     */
    inner class IapTunnelClient(
        val socket: Socket,
        private val readKey: ByteArray,
        private val logPrefix: String = "TUNNEL",
        private val writeKey: ByteArray = readKey,
    ) {
        @Volatile
        var messageCount = 0
            private set

        /** Callback for head-unit-to-phone iAP2 messages (CSM frame bytes). */
        var onIapMessage: ((Iap2Csm.Frame) -> Unit)? = null

        private val parser =
            Iap2Csm
                .Parser()

        private val writeLock = Any()
        private var writeCounter = 0L
        private var writeFailureLogged = false

        /**
         * Phone-to-receiver (tunnel) writes: the DiPlay tunnel link engine **only reads from the TCP socket**
         * (AirPlayIapTunnelStream has inbound=IapTunnel/TCP, outbound=event channel),
         * so the sender must write iAP2 link bytes into that socket as "ChaCha frame (2B LE length as AAD + ciphertext + 16B tag)
         * containing an APTransportPackage (4B BE total length, type="comm" at offset 16)".
         * The key comes from the same source as the read direction (OUTPUT label = phone-to-receiver direction, confirmed against the source in 2026-08).
         * Returns whether it was written (false when the socket is closed/throws; the caller logs only once).
         */
        fun writeIapBytes(bytes: ByteArray): Boolean {
            if (socket.isClosed || !socket.isConnected || bytes.isEmpty()) return false
            return try {
                val size = TUNNEL_PACKAGE_HEADER_BYTES + bytes.size
                val plain = ByteArray(size)
                plain[0] = (size ushr 24).toByte()
                plain[1] = (size ushr 16).toByte()
                plain[2] = (size ushr 8).toByte()
                plain[3] = size.toByte()
                plain[16] = 'c'.code.toByte()
                plain[17] = 'o'.code.toByte()
                plain[18] = 'm'.code.toByte()
                plain[19] = 'm'.code.toByte()
                bytes.copyInto(plain, TUNNEL_PACKAGE_HEADER_BYTES)
                val aad =
                    byteArrayOf(
                        (size and 0xFF).toByte(),
                        ((size ushr 8) and 0xFF).toByte(),
                    )
                // The counter increment must share the lock with the socket write: a reordered ChaCha nonce means the peer fails to decrypt
                synchronized(writeLock) {
                    val sealed =
                        AirPlayCrypto.chachaSeal(
                            writeKey,
                            AirPlayCrypto.nonce64(writeCounter++),
                            plain,
                            aad,
                        )
                    val output = socket.getOutputStream()
                    output.write(aad)
                    output.write(sealed)
                    output.flush()
                }
                writeFailureLogged = false
                true
            } catch (error: IOException) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    log("$logPrefix: write failed - ${error.message}")
                }
                false
            }
        }

        fun startReading() {
            Thread({
                val input = socket.getInputStream()
                var pending = ByteArray(0)
                var plaintext = ByteArray(0)
                val buffer = ByteArray(16 * 1024)
                try {
                    while (socket.isClosed == false) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        pending += buffer.copyOf(count)
                        val decrypted = decryptFrames(pending)
                        pending = decrypted.second
                        plaintext += decrypted.first
                        plaintext = parsePackages(plaintext)
                    }
                    log("$logPrefix: channel closed (EOF), $messageCount iAP2 messages in total")
                } catch (error: IOException) {
                    log("$logPrefix: read ended - ${error.message}")
                }
            }, "iap-tunnel-reader").apply {
                isDaemon = true
                start()
            }
        }

        /** ChaCha frame decryption (same frame format as ControlCipher, with independent counter/key). */
        private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
            val output = ArrayList<ByteArray>()
            var offset = 0
            while (buffer.size - offset >= 2) {
                val length = (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
                val frameLength = 2 + length + 16
                if (buffer.size - offset < frameLength) break
                val aad = buffer.copyOfRange(offset, offset + 2)
                val sealed = buffer.copyOfRange(offset + 2, offset + frameLength)
                val plain =
                    AirPlayCrypto.chachaOpen(
                        readKey,
                        AirPlayCrypto.nonce64(tunnelReadCounter++),
                        sealed,
                        aad,
                    )
                output += plain
                offset += frameLength
            }
            val rest = buffer.copyOfRange(offset, buffer.size)
            return if (output.isEmpty()) ByteArray(0) to rest else output.reduce { a, b -> a + b } to rest
        }

        /** APTransportPackage parsing: 4B BE total length, 32B header, type="comm" at offset 16 -> body=iAP2. */
        private fun parsePackages(buffer: ByteArray): ByteArray {
            var offset = 0
            while (buffer.size - offset >= 32) {
                val size =
                    ((buffer[offset].toInt() and 0xFF) shl 24) or
                        ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                        ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                        (buffer[offset + 3].toInt() and 0xFF)
                if (size < 32 || buffer.size - offset < size) break
                val messageType = String(buffer, offset + 16, 4, Charsets.US_ASCII)
                if (messageType == "comm") {
                    val body = buffer.copyOfRange(offset + 32, offset + size)
                    onIapBytes(body)
                }
                offset += size
            }
            return buffer.copyOfRange(offset, buffer.size)
        }

        private fun onIapBytes(bytes: ByteArray) {
            messageCount++
            val frames = parser.offer(bytes)
            for (frame in frames) {
                // hex formatting has a cost: do it only in the branch that really logs (previously computed for every message)
                if (messageCount <= 20) {
                    val hex = bytes.joinToString(" ") { "%02x".format(it) }
                    log(
                        "$logPrefix: iAP2 msg #$messageCount id=0x${frame.messageId.toString(16)} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)}) body=${frame.body.size}B " +
                            "hex=${hex.take(160)}",
                    )
                } else if (messageCount % 100 == 0) {
                    log("$logPrefix: iAP2 msg #$messageCount id=0x${frame.messageId.toString(16)} body=${frame.body.size}B")
                }
                onIapMessage?.invoke(frame)
            }
        }

        private var tunnelReadCounter = 0L
    }

    /**
     * SETUPs the type-130 iAP tunnel and connects to the head unit dataPort. Returns null on failure (the session continues, touch unavailable).
     * The phone-to-head-unit direction uses [sendIap] (event-channel iAPSendMessage).
     */
    fun runIapTunnel(sessionKey: ByteArray?): IapTunnelClient? {
        // The tunnel socket is encrypted with the DataStream key, and an unpaired session has no shared secret to derive it from -> skip.
        if (sessionKey == null) {
            log("TUNNEL: unpaired session has no shared secret - skipping the iAP tunnel (touch unavailable)")
            return null
        }
        if (AdapterMode.active && "iAPChannel" !in sessionEnabledFeatures) {
            // Apple's 320.17 has no stream type 130 and a failed SetUpStreams fires sessionFailed;
            // the reference only sets it up when the receiver enables iAPChannel.
            log("TUNNEL: adapter mode, iAPChannel not enabled ($sessionEnabledFeatures) - Wi-Fi iAP2 runs over the control channel")
            return null
        }
        val host = receiverHost() ?: return null
        val seed = (System.currentTimeMillis() % 0x7FFF_FFFFL) + 1L
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to 130L,
                                "clientTypeUUID" to IAP_DATASTREAM_UUID,
                                "seed" to seed,
                                "streamConnectionID" to connectionId,
                            ),
                        ),
                ),
            )
        val (statusLine, responseBody) =
            try {
                request("SETUP", "/", body, contentType = "application/x-apple-binary-plist")
            } catch (error: IOException) {
                log("TUNNEL: SETUP failed - ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log("TUNNEL: SETUP reply [$statusLine] (response head: ${lastResponseHeadOfRequest()})")
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (_: IllegalArgumentException) {
                null
            } ?: run {
                log("TUNNEL: SETUP reply parse failed")
                return null
            }
        val streams = root["streams"] as? List<*>
        val stream = streams?.firstOrNull() as? Map<*, *>
        val dataPort = ((stream?.get("dataPort") as? Long) ?: 0L).toInt() and 0xFFFF
        if (dataPort == 0) {
            log("TUNNEL: SETUP reply has no dataPort (streams=$streams)")
            return null
        }
        val key =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        val tcp =
            try {
                Socket().also { CarNetwork.bind(it, "iAP tunnel") }
            } catch (error: IOException) {
                return null
            }
        return try {
            tcp.tcpNoDelay = true
            tcp.connect(java.net.InetSocketAddress(host, dataPort), 5_000)
            log("TUNNEL: ✓ iAP tunnel established $host:$dataPort (seed=$seed)")
            val client = IapTunnelClient(tcp, key)
            client.startReading()
            iapTunnel = client
            client
        } catch (error: IOException) {
            log("TUNNEL: TCP connection failed - ${error.message}")
            runCatching { tcp.close() }
            null
        }
    }

    /**
     * Tunnel client reference (DiPlay form): the **outbound direction** of the event-channel virtual link ([VirtualIap2Link])
     * must be written into the type-130 TCP (the DiPlay tunnel link engine only reads from TCP; its outbound goes over the event channel,
     * matching our passive receiving). Without a tunnel (Audi event-channel form) outbound keeps using the event channel [sendIap].
     */
    @Volatile
    private var iapTunnel: IapTunnelClient? = null

    private var sendIapCount = 0

    /**
     * Phone-to-head-unit iAP2 messages: the event-channel iAPSendMessage command (see DiPlay sendIapMessage,
     * params envelope + data bytes, same as catplay CommandIApSendMessage).
     */
    fun sendIap(data: ByteArray): Boolean {
        val sent =
            postEventCommand(
                linkedMapOf(
                    "type" to "iAPSendMessage",
                    "params" to linkedMapOf<String, Any?>("data" to data),
                ),
            )
        sendIapCount++
        if (sendIapCount <= 10) {
            log(
                "IAPLINK: tx(event channel) ${data.size}B ok=$sent hex=" +
                    data.joinToString(" ") { "%02x".format(it) }.take(120),
            )
        }
        return sent
    }

    // ---- Virtual iAP2 link on the event channel ----
    //
    // Measured on Audi (2026-10-11): the head unit sends an iAP2 link-layer marker via iAPSendMessage
    // (ff 55 02 00 ee 10); it opened a virtual iAP2 link on the event channel (the type-130 TCP
    // was refused by the head unit: 200 but no dataPort). EA data (touch HID) travels over this link.
    // Reuses [io.github.ezn24.cardeck.neo.iap2.Iap2LinkEngine] for the handshake and frame exchange.

    @Volatile
    private var iapLink: VirtualIap2Link? = null

    /**
     * Head-unit media key callback (steering wheel/panel previous/next track).
     *
     * Path: event channel -> iAPSendMessage -> iAP2 control session `0x6802 AccessoryHIDReport`
     * -> mapped by [ConsumerKeys] to an Android KeyEvent -> this callback. SessionController injects
     * `dispatchMediaKey` (handled together with the direct HID path).
     */
    var onMediaKey: ((Int) -> Unit)? = null

    /** Navigation keys (iDrive knob / four-way stick): need virtual display focus; the session layer injects DPAD. */
    var onNavKey: ((Int) -> Unit)? = null

    /**
     * MIB3 tunnel control session complete (auth+burst sent) callback: the host schedules the modesChanged
     * resource re-assertion burst from it (AADisplay r17 timing +1.5/4/12/30s). Ruling from 082446: without re-assertion
     * the head unit stays healthy throughout but never switches to the CarPlay picture.
     */
    var onTunnelControlSessionComplete: (() -> Unit)? = null

    /** Whether the MIB3 virtual link is armed (armMib3 succeeded); for teardown attribution: armed but torn down before the control session completed = link watchdog, unrelated to the audio profile. */
    var mib3LinkArmed: Boolean = false
        private set

    /** Whether the MIB3 control session ever completed (auth+burst sent). */
    var mib3ControlSessionDone: Boolean = false
        private set

    /** The single virtual link instance (outbound routing / three-form semantics: see [VirtualIap2Link]). */
    private fun obtainIapLink(): VirtualIap2Link =
        iapLink ?: synchronized(this) {
            iapLink
                ?: VirtualIap2Link(
                    log = { line -> log(line) },
                    sendEventIap = ::sendIap,
                    sendControlIap = ::postControlIap,
                    tunnelWriter = { iapTunnel },
                    alive = { socket?.isClosed == false },
                ).also {
                    it.onMediaKey = { keyCode -> onMediaKey?.invoke(keyCode) }
                    it.onNavKey = { keyCode -> onNavKey?.invoke(keyCode) }
                    it.onControlSessionComplete = {
                        mib3ControlSessionDone = true
                        onTunnelControlSessionComplete?.invoke()
                    }
                    it.begin()
                    iapLink = it
                }
        }

    /** Entry point for head-unit iAPSendMessage bytes (called by the SessionController event command handler). */
    fun onIapEventBytes(data: ByteArray) {
        if (AdapterMode.active) {
            // Open the control-channel gate before the link answers (see allowsControlIapPost).
            boxIapSeen = true
            obtainAdapterIapLink().onCarBytes(data)
            return
        }
        obtainIapLink().onCarBytes(data)
    }

    /**
     * MIB3 arming: called after the screen-stream SETUP succeeds; proactively sends one
     * DETECT via **control-channel** POST /command, then waits for the head unit SYN (real-device Frida timing, see [VirtualIap2Link.armMib3]).
     * Plaintext sessions (pre-HomeKit old head units) have no WiFi tunnel semantics and are skipped.
     */
    private fun armMib3VirtualLink() {
        if (cipher == null) return
        if (iapTunnel != null) return // DiPlay tunnel form: not needed (and must not be armed)
        if (AdapterMode.active) {
            log("IAPLINK: adapter mode - no DETECT; the Wi-Fi iAP2 tunnel waits for the box's first iAPSendMessage")
            return
        }
        val generation = linkGeneration ?: ReceiverProfile.LinkGeneration.EVENT_LINK
        obtainIapLink().setGeneration(generation)
        obtainIapLink().armMib3()
        mib3LinkArmed = true
    }

    /**
     * Control-channel iAPSendMessage outbound (MIB3): phone-side iAP2 link frames **must** go over POST /command on the RTSP control
     * connection; MIB3 does not read writes on the event channel at all (DiPlay is tolerant, Audi
     * is not; AADisplay Frida finding). Serial executor: does not block the link pacer thread, and shares the
     * CSeq/write lock of request() with the /feedback heartbeat (thread-safety design: see AirPlayClient).
     */
    private val controlIapTx =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mib3-iap-tx").apply { isDaemon = true }
        }
    private var controlIapCount = 0

    fun postControlIap(frame: ByteArray) {
        if (!allowsControlIapPost()) {
            prematureIapDropped++
            if (prematureIapDropped <= 3) {
                log("IAPLINK: adapter mode - dropped ${frame.size}B control-channel iAPSendMessage before the box spoke first")
            }
            return
        }
        controlIapTx.execute {
            try {
                val body =
                    BinaryPlist.encode(
                        linkedMapOf(
                            "type" to "iAPSendMessage",
                            "params" to linkedMapOf<String, Any?>("data" to frame),
                        ),
                    )
                val (statusLine, reply) =
                    request(
                        "POST",
                        "/command",
                        body,
                        contentType = "application/x-apple-binary-plist",
                    )
                controlIapCount++
                val head =
                    "IAPLINK: tx(control channel) #$controlIapCount ${frame.size}B [$statusLine]" +
                        (if (reply.isEmpty()) "" else " resp=${reply.size}B hex=" + hexPrefix(reply))
                if (controlIapCount <= 10 || controlIapCount % 50 == 0 || reply.isNotEmpty()) {
                    log(head)
                }
                // The head unit may stuff link bytes directly into the reply body (instead of sending them on the event channel): if recognized, feed them to
                // the link engine instead of leaving them idle in the response.
                if (reply.isNotEmpty() && looksLikeIapLink(reply)) {
                    log("IAPLINK: control-channel reply contains link bytes ${reply.size}B -> feeding the link engine")
                    if (AdapterMode.active) adapterIapLink?.onCarBytes(reply) else iapLink?.onCarBytes(reply)
                }
            } catch (error: IOException) {
                log("IAPLINK: control-channel POST failed - ${error.message}")
            }
        }
    }

    // ---- Wireless CarPlay adapter mode (AdapterMode.active only) ----
    // Wireless transition as in the reference sender (unapple-phone phone.rs): RECORD, the
    // receive-driven Wi-Fi iAP2 tunnel, disableBluetooth{deviceID = BT MAC}, then RFCOMM release.

    /** `disableBluetooth` answered 200 (the box now starts its Wi-Fi iAP2 tunnel). Once per session. */
    var onDisableBluetoothAccepted: (() -> Unit)? = null

    /** Inner Wi-Fi iAP2 control session is up (authenticated, 0xAA05 burst sent). Once per session. */
    var onIapTunnelReady: (() -> Unit)? = null

    /**
     * Convenience for the RFCOMM owner: fires once when both of the above happened, or 10 s after
     * `disableBluetooth` was accepted if the tunnel never came up (reference behaviour).
     */
    var onBluetoothReleasable: ((reason: String) -> Unit)? = null

    /**
     * The box sent `requestUI` (the screen was already re-taken with modesChanged): the video must
     * be re-primed with VideoConfig + a fresh IDR (reference 853f400/1d7a4e6).
     */
    var onRequestUI: (() -> Unit)? = null

    /** 0x4E0E USB transport id on the Wi-Fi tunnel; null = [AdapterTransportId] (the same id the Bluetooth session sends). */
    @Volatile
    var iapTransportId: String? = null

    @Volatile
    var disableBluetoothAccepted: Boolean = false
        private set

    @Volatile
    var iapTunnelReady: Boolean = false
        private set

    @Volatile
    private var boxIapSeen = false

    @Volatile
    private var disableBluetoothSent = false

    @Volatile
    private var adapterSessionEpoch = 0

    private val bluetoothReleaseFired =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val requestUiQueued =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private var prematureIapDropped = 0

    @Volatile
    private var adapterIapLink: AdapterIapLink? = null

    /** enabledFeatures of the current session SETUP (adapter mode gates type-130 on iAPChannel). */
    @Volatile
    private var sessionEnabledFeatures: List<String> = emptyList()

    private val adapterUiTx =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "adapter-ui").apply { isDaemon = true }
        }

    /**
     * Receive-driven gate: in adapter mode no control-channel `iAPSendMessage` may leave before the
     * box sent one (posting first makes the 320.17 box close the control connection).
     */
    internal fun allowsControlIapPost(): Boolean = !AdapterMode.active || boxIapSeen

    private fun resetAdapterSessionState() {
        adapterSessionEpoch++
        adapterIapLink = null
        boxIapSeen = false
        disableBluetoothSent = false
        disableBluetoothAccepted = false
        iapTunnelReady = false
        bluetoothReleaseFired.set(false)
        prematureIapDropped = 0
        sessionEnabledFeatures = emptyList()
    }

    private fun obtainAdapterIapLink(): AdapterIapLink =
        adapterIapLink ?: synchronized(this) {
            adapterIapLink ?: run {
                val owner = socket
                val epoch = adapterSessionEpoch
                AdapterIapLink(
                    log = { line -> log(line) },
                    post = ::postControlIap,
                    bluetoothId = { setupDeviceId().uppercase() },
                    transportId = { iapTransportId ?: AdapterTransportId.get(context) },
                    alive = { owner != null && !owner.isClosed && socket === owner },
                ).also { link ->
                    link.onReady = { onAdapterTunnelReady(epoch) }
                    adapterIapLink = link
                }
            }
        }

    private fun onAdapterTunnelReady(epoch: Int) {
        if (epoch != adapterSessionEpoch) return
        iapTunnelReady = true
        dispatchAdapterCallback {
            onIapTunnelReady?.invoke()
            // The second control session resets the box's arbitration: restart the modesChanged
            // re-assert burst (+1.5/4/12/30 s) as the reference does when the tunnel is up.
            onTunnelControlSessionComplete?.invoke()
        }
        maybeReleaseBluetooth(epoch, "disableBluetooth accepted and Wi-Fi iAP2 tunnel up")
    }

    private fun maybeReleaseBluetooth(
        epoch: Int,
        reason: String,
    ) {
        if (epoch != adapterSessionEpoch || !disableBluetoothAccepted) return
        if (!bluetoothReleaseFired.compareAndSet(false, true)) return
        log("AIRPLAY: adapter mode - Bluetooth RFCOMM may be released ($reason)")
        dispatchAdapterCallback { onBluetoothReleasable?.invoke(reason) }
    }

    /** Callbacks leave the link/RTSP threads (they may block or call back into this client). */
    private fun dispatchAdapterCallback(block: () -> Unit) {
        Thread({ runCatching(block).onFailure { log("AIRPLAY: adapter callback failed - ${it.message}") } }, "adapter-callback")
            .apply { isDaemon = true }
            .start()
    }

    /**
     * `POST /command {type: disableBluetooth, params: {deviceID: <our BT MAC>}}` on the control
     * channel, once per session. The box stops its Bluetooth iAP2 and starts the Wi-Fi tunnel only
     * after this returns 200 (reference run 043046Z). Called automatically after RECORD in adapter mode.
     */
    fun sendDisableBluetooth(): Boolean {
        if (disableBluetoothSent) return disableBluetoothAccepted
        disableBluetoothSent = true
        val epoch = adapterSessionEpoch
        val mac = setupDeviceId().uppercase()
        if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
            log("AIRPLAY: ⚠ disableBluetooth with placeholder deviceID $mac (real Bluetooth MAC not resolved) - the box may ignore it")
        }
        return try {
            val (statusLine, reply) =
                request(
                    "POST",
                    "/command",
                    BinaryPlist.encode(disableBluetoothCommand(mac)),
                    timeoutMillis = 5_000,
                    contentType = "application/x-apple-binary-plist",
                )
            val ok = statusLine.contains("200")
            log(
                "AIRPLAY: disableBluetooth deviceID=$mac [$statusLine]" +
                    (if (reply.isEmpty()) "" else " resp=${reply.size}B") +
                    if (ok) " - waiting for the box's Wi-Fi iAP2 tunnel" else "",
            )
            if (ok && epoch == adapterSessionEpoch) {
                disableBluetoothAccepted = true
                dispatchAdapterCallback { onDisableBluetoothAccepted?.invoke() }
                if (iapTunnelReady) {
                    maybeReleaseBluetooth(epoch, "Wi-Fi iAP2 tunnel already up")
                } else {
                    Thread({
                        runCatching { Thread.sleep(BLUETOOTH_RELEASE_FALLBACK_MS) }
                        maybeReleaseBluetooth(epoch, "${BLUETOOTH_RELEASE_FALLBACK_MS / 1000}s after disableBluetooth, tunnel not up")
                    }, "adapter-bt-release").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
            ok
        } catch (error: IOException) {
            log("AIRPLAY: disableBluetooth failed - ${error.message}")
            false
        }
    }

    /** Box `requestUI`: take the screen again, then let the host re-prime the video. Coalesces bursts. */
    private fun onAdapterRequestUI() {
        if (!requestUiQueued.compareAndSet(false, true)) return
        adapterUiTx.execute {
            requestUiQueued.set(false)
            log("AIRPLAY: adapter mode - box requestUI → modesChanged (take screen) + video re-prime")
            assertModes()
            runCatching { onRequestUI?.invoke() }.onFailure { log("AIRPLAY: requestUI re-prime failed - ${it.message}") }
        }
    }

    /**
     * Wi-Fi interface MAC for the session SETUP `macAddress` (reference: the interface address,
     * Bluetooth MAC stays in deviceID). Android 11+ hides it from apps, so fall back to the Shizuku
     * shell reading sysfs; null if neither works.
     */
    private fun wifiMacAddress(): String? {
        val local = socket?.localAddress
        val nic =
            local?.let { runCatching { NetworkInterface.getByInetAddress(it) }.getOrNull() }
                ?: wifiInterface()
                ?: return null
        runCatching { nic.hardwareAddress }
            .getOrNull()
            ?.takeIf { it.size == 6 }
            ?.let { raw -> normalizeMacAddress(raw.joinToString(":") { "%02X".format(it) }) }
            ?.let { return it }
        val name = nic.name.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: return null
        val result = runCatching { Shell.exec("cat /sys/class/net/$name/address", timeoutMs = 2_000) }.getOrNull()
        return normalizeMacAddress(result?.output)
    }

    /** Hex prefix of a reply body (for diagnostics, truncated to 24 bytes). */
    private fun hexPrefix(
        bytes: ByteArray,
        limit: Int = 24,
    ): String = bytes.take(limit).joinToString(" ") { "%02x".format(it) } + if (bytes.size > limit) "…" else ""

    /**
     * Full hex of a message (for failure forensics): at most 480 bytes by default, 48 bytes per line.
     * When debugging a head unit plist that cannot be decoded, a 24-byte prefix does not reveal the offending object, so the whole body is needed.
     */
    private fun hexDump(
        bytes: ByteArray,
        limit: Int = 480,
        perLine: Int = 48,
    ): String {
        if (bytes.isEmpty()) return "(empty)"
        val shown = minOf(bytes.size, limit)
        val builder = StringBuilder(shown * 3)
        var index = 0
        while (index < shown) {
            if (index > 0) builder.append("\n    ")
            val end = minOf(index + perLine, shown)
            for (position in index until end) {
                builder.append("%02x".format(bytes[position]))
                if (position != end - 1) builder.append(' ')
            }
            index = end
        }
        if (bytes.size > shown) builder.append(" ... (${bytes.size}B total, showing only the first $shown)")
        return builder.toString()
    }

    /** Whether a reply body is an iAP2 link frame (marker 0xFF55 / sync packet 0xFF5A). */
    private fun looksLikeIapLink(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 0xFF.toByte() &&
            (bytes[1] == 0x5A.toByte() || bytes[1] == 0x55.toByte())

    /** Audio stream negotiation result (RTP over UDP; keys follow the video stream rules, salt uses the audio connection ID). */
    class AudioStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        val controlPort: Int,
        val streamKey: ByteArray,
        /** Negotiated sample rate (Hz): chosen by what the head unit declares; AudioSender must capture at the same value. */
        val sampleRate: Int = 48_000,
    )

    /** plist ports are u16 but encoded as 2-byte signed integers (>32767 reads as negative); mask by bits to get the real value. */
    private fun portOf(value: Any?): Int = ((value as? Long) ?: 0L).toInt() and 0xFFFF

    private companion object {
        /** Main screen video stream type (see receiver STREAM_TYPE_MAIN_SCREEN; 100 is audio, 130 is the data/iAP tunnel). */
        const val SCREEN_STREAM_TYPE = 110L

        /** Main audio stream type (DiPlay STREAM_TYPE_MAIN_AUDIO; PCM path). */
        const val AUDIO_STREAM_TYPE = 100L

        /**
         * High-quality main audio stream type (catplay `StreamType::MainHighAudio`).
         *
         * On some head units (measured 2026-10-20 on Honda HFT / Alpine 8A250-30A) the **real media path** is this one:
         * `type=102 media` only declares AAC-LC while `type=100 media` only declares PCM; when PCM is pushed to 100,
         * the head unit answers SETUP 200 and gives a dataPort but does not consume it (`/feedback` sampleTime stays 0),
         * and tears down the whole session after ~6.5 s.
         */
        const val AUDIO_STREAM_TYPE_MAIN_HIGH = 102L

        /** Audio format bit: PCM 48000Hz 16bit stereo (catplay AudioFormat definition 1<<15). */
        const val AUDIO_FORMAT_PCM_48K_STEREO = 1L shl 15

        /** Audio format bit: PCM 44100Hz 16bit stereo (1<<11; the fallback when 48k is unavailable). */
        const val AUDIO_FORMAT_PCM_44K_STEREO = 1L shl 11

        /** AAC-LC 44100Hz stereo (1<<22; catplay `AAC_LC_44100_STEREO`). */
        const val AUDIO_FORMAT_AAC_LC_44K_STEREO = 1L shl 22

        /** AAC-LC 48000Hz stereo (1<<23; catplay `AAC_LC_48000_STEREO`). */
        const val AUDIO_FORMAT_AAC_48K_STEREO = 1L shl 23

        /**
         * audioType of the media audio channel (a real iPhone sends media audio through this one).
         * Which one to pick is decided by [negotiateAudio] from the audioFormats in the head unit /info; this is only
         * the criterion for "prefer it when a media entry exists" and its protocol baseline value when /info is missing.
         */
        const val AUDIO_TYPE_MEDIA = "media"

        /**
         * Fallback audio latency (ms): used when the head unit /info audioLatencies gives no outputLatencyMicros.
         * catplay comment: AirPlay streaming is only stable from 250 ms up (clamp 32..2000), so the fallback is 250.
         */
        const val AUDIO_LATENCY_FALLBACK_MS = 250L

        /** A head unit volume <= this value (dB) counts as the mute position (-144 in AirPlay is roughly full mute). */
        private const val MUTE_THRESHOLD_DB = -100f

        /** Volume (dB, 0 = max) raised to when the mute position is detected (-25 is medium-low, not blasting). */
        private const val SAFE_VOLUME_DB = -25.0f

        /** Parses text parameters such as `volume: -12.345678`. */
        private val VOLUME_REGEX = Regex("volume:\\s*(-?[0-9.]+)")

        /**
         * iPhone capability list in SETUP (the receiver computes enabledFeatures as "head unit supported ∩ proposed",
         * and a missing capability has its whole feature turned off; measured on Audi 2026-10-11: with videoPlayback proposed alone
         * the video pipeline was not enabled and the screen-stream got 400). The list follows the catplay real-car sender.
         */
        val CONTROLLER_FEATURES =
            listOf(
                "uiContext",
                "viewAreas",
                "cornerMasks",
                "focusTransfer",
                "h.264Level5.1",
                "mainBuffered",
                "altScreen",
                "enhancedSiri",
                "hevc",
                "sessionManagement",
                "logTransfer",
                "iAPChannel",
                "vehicleStateProtocol",
                "videoPlayback",
            )

        /** Total time to wait for the head unit to open the AirPlay window (the wait window for the user tapping the phone icon on the head unit). */
        private const val PAIRING_WINDOW_MILLIS = 120_000L

        /** Interval (in rounds) for calling [retryTick] during pairing retries: each round returns in 2 s, so 10 rounds is about one re-announcement every 20 s. */
        private const val RETRY_TICK_ATTEMPTS = 10

        /**
         * HTTP statuses where the receiver explicitly says "no such endpoint". A hit means a pre-HomeKit old head unit,
         * so we switch to a plaintext session immediately instead of running out the whole 120 s pairing window.
         */
        private val PAIRING_UNSUPPORTED_STATUS = listOf("404", "405", "455", "501")

        /** AirPlay version self-reported by the sender (value verified on real cars by catplay, iOS 15.0.2 era; do not use the head unit's version). */
        private const val SENDER_SOURCE_VERSION = "566.25.21"

        /**
         * UDP keepAlive interval. catplay uses 10 s, but that is the basis of its own receiver; the real car (Audi
         * MMI 3315) watchdog is also 10 s, so equal length is a sure loss (sleep jitter makes the heartbeat arrive 1 ms late).
         */
        private const val KEEPALIVE_INTERVAL_MS = 5_000L

        /**
         * Upper bound for the session TEARDOWN reply: a live head unit answers in well under 100 ms, and one that has gone
         * away must not hold up the next round (retireSession also runs at the start of every pairing round).
         */
        private const val TEARDOWN_TIMEOUT_MS = 800

        /** Sentinel status line delivered to waiters when the reader thread ends / the connection closes (not a real RTSP reply). */
        private const val RTSP_CLOSED_MARK = "__closed__"

        /** Screen stream latency (measured value from catplay c2a car_manager). */
        private const val SCREEN_LATENCY_MS = 25L

        /** clientTypeUUID of the iAP DataStream (DiPlay IAP_DATASTREAM_UUID, same as a real iPhone). */
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"

        /** Tunnel APTransportPackage header length (4B BE total length + 12B reserved + 4B type="comm" + 12B reserved). */
        private const val TUNNEL_PACKAGE_HEADER_BYTES = 32

        /** Session SETUP `macAddress` when no Wi-Fi MAC is known (unchanged legacy value). */
        const val DEFAULT_SETUP_MAC_ADDRESS = "aa:bb:cc:dd:ee:ff"

        /** Adapter mode: release RFCOMM this long after disableBluetooth even if the tunnel is not up (reference). */
        private const val BLUETOOTH_RELEASE_FALLBACK_MS = 10_000L

        /** RTSP message header terminator and header-field regexes (shared by the request/reply reassembly paths; do not inline again). */
        private val HEAD_SEPARATOR = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

        private val CONTENT_LENGTH_REGEX = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        private val CSEQ_REGEX = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        /** Parses the RTSP message header: first line + Content-Length (shared by the two reassembly paths). */
        fun parseRtspHead(headText: String): Pair<String, Int> {
            val contentLength =
                CONTENT_LENGTH_REGEX
                    .find(headText)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: 0
            val firstLine = headText.lineSequence().firstOrNull() ?: ""
            return firstLine to contentLength
        }

        fun indexOf(
            source: ByteArray,
            target: ByteArray,
        ): Int {
            outer@ for (index in 0..source.size - target.size) {
                for (offset in target.indices) {
                    if (source[index + offset] != target[offset]) continue@outer
                }
                return index
            }
            return -1
        }
    }

    /**
     * SETUP: negotiates the main audio stream (type 100, RTP over UDP).
     *
     * The wire format is of the same family as video but the transport differs: the receiver opens **UDP** dataPort/controlPort in its reply,
     * and the phone sends PCM frames there in the [AudioSender] format. Key = HKDF(shared,
     * "DataStream-Salt<audio connId>", "DataStream-Output-Encryption-Key").
     *
     * Fields are aligned with the catplay sender (`StreamDescriptionAudio::new`):
     *  - `audioLatencyMs` is required (the receiver reads it; omitting it makes strict implementations tear the session down)
     *  - `audioType` is **negotiated from what the head unit /info declares** (see [negotiateAudio]): media audio prefers the
     *    `media` channel; when it was hard-coded to `compatibility`, the head unit accepted SETUP, gave a port and received packets,
     *    but did not connect them to the playback path (silence); this was one of the main root causes on 2026-10-17
     *  - `streamConnectionID` uses a random positive i64 (same as catplay `rand::random::<u64>()`): a timestamp
     *    remainder collides on quick reconnects
     *  - no extra GET /info (measured ~2.5 s extra; /info was already fetched during the handshake and the cache is reused)
     *
     * Format: prefer PCM 48k stereo (bit15), fall back to bit11 when the head unit only declares 44.1k; the sample rate is passed to
     * [AudioSender] to stay consistent.
     */
    fun runAudioStream(
        sharedSecret: ByteArray,
        profile: AudioProfile,
        tuning: AudioTuning = AudioTuning.RAW_SPF,
    ): AudioStreamInfo? {
        // The profile is chosen by the caller from "candidates declared in the head unit /info + this head unit's history memory" and passed in:
        // the availability of different audioType profiles differs completely on the same head unit (see the AudioProfile comment),
        // so when one profile fails we move to the next instead of blindly retrying the same one on every connection.
        val negotiation = profile
        log(
            "AIRPLAY: audio negotiation -> audioType=${profile.audioType} " +
                "format=0x${profile.formatBit.toString(16)} ${profile.sampleRate}Hz " +
                "latency=${profile.latencyMs}ms (profile=${profile.label}, " +
                "packaging=${tuning.detail})",
        )
        // Random positive i64: BinaryPlist encodes signed integers, so mask the sign bit to avoid negatives
        val connectionId = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1L)
        // spf value: one AAC frame is 1024 samples; PCM stays consistent with the sender's 352 frames per packet
        val spfValue =
            tuning.spf?.let {
                if (profile.codec == AudioCodec.AAC_LC) it.toLong() else AudioSender.SAMPLES_PER_PACKET.toLong()
            }
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>().apply {
                                put("type", profile.streamType)
                                put("streamConnectionID", connectionId)
                                put("audioFormat", negotiation.formatBit)
                                put("audioType", negotiation.audioType)
                                put("audioLatencyMs", negotiation.latencyMs)
                                // Aligned field by field with the catplay sender (StreamDescriptionAudio::new):
                                // input=false (downlink only, no microphone uplink),
                                // supportsRTPPacketRedundancy=false (we do not pack redundant frames).
                                // Without these two booleans some strict receivers treat "undeclared = unsupported"
                                // and give up on the stream during audio path initialization.
                                put("input", false)
                                put("supportsRTPPacketRedundancy", false)
                                put("supportsHighAccuracyTimestamps", tuning.highAccuracyTimestamps)
                                // spf = sample frames per packet: 1024 for AAC, 352 for PCM.
                                // Measured on Honda 2026-10-20: without declaring spf the head unit still receives packets and consumes them in
                                // real time at 44.1k, yet the speakers are silent; implementations that "schedule playback by a fixed SPF" need this value.
                                spfValue?.let { put("spf", it) }
                            },
                        ),
                ),
            )
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val startedAt = SystemClock.uptimeMillis()
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "rtsp://$host:${endpoint.airPlayPort}/",
                    body,
                    // 8s: the Audi audio SETUP was measured at up to ~2.7 s (head-unit-side resource arbitration); the async reader thread
                    // keeps the feedback heartbeat going during the wait, so a long wait no longer triggers the head unit watchdog
                    timeoutMillis = 8_000,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: audio stream negotiation failed - ${error.message}")
                return null
            }
        log("AIRPLAY: audio SETUP round trip ${SystemClock.uptimeMillis() - startedAt}ms [$statusLine]")
        if (!statusLine.contains("200")) {
            log("AIRPLAY: audio stream negotiation reply [$statusLine]")
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                null
            } ?: run {
                log("AIRPLAY: audio stream negotiation reply parse failed")
                return null
            }
        val streams =
            root["streams"] as? List<*> ?: run {
                log("AIRPLAY: audio stream negotiation reply missing streams")
                return null
            }
        val audio =
            streams.firstOrNull { entry ->
                ((entry as? Map<*, *>)?.get("type") as? Long) == profile.streamType
            } as? Map<*, *> ?: run {
                log("AIRPLAY: audio stream rejected (streams=$streams)")
                return null
            }
        val dataPort = portOf(audio["dataPort"])
        if (dataPort == 0) {
            log("AIRPLAY: audio stream returned no dataPort ($audio)")
            return null
        }
        val streamKey =
            AirPlayCrypto.hkdfSha512(
                sharedSecret,
                "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        log(
            "AIRPLAY: ✓ audio stream negotiated (type=${profile.streamType} ${negotiation.audioType} " +
                "format=0x${negotiation.formatBit.toString(16)} ${negotiation.sampleRate}Hz " +
                "latency=${negotiation.latencyMs}ms)" +
                "dataPort=$dataPort controlPort=${portOf(audio["controlPort"])} connId=$connectionId" +
                " all fields=$audio",
        )
        // The audio path is built, but one very common cause of "the head unit is receiving data yet the speakers are silent" remains:
        // the receiver's AirPlay volume sits at the mute position (-144dB); a real iPhone proactively SET_PARAMETERs
        // volume. Here we query once first (also recording the value in the log), and raise it to -25dB if it is at the mute position.
        probeAndFixVolume(host)
        return AudioStreamInfo(
            streamConnectionId = connectionId,
            dataPort = dataPort,
            controlPort = portOf(audio["controlPort"]),
            streamKey = streamKey,
            sampleRate = negotiation.sampleRate,
        )
    }

    /** Negotiation result of the head unit microphone stream (uplink). */
    data class MicStreamInfo(
        val streamConnectionId: Long,
        val inputKey: ByteArray,
        val format: CarMic.Format,
        /** Downlink dataPort given by the head unit (for sending silence keepalive; 0 = not given by the head unit). */
        val carDataPort: Int,
        val outputKey: ByteArray,
    )

    /** Microphone format usable in the current /info (null = the head unit declares no speech input path). */
    fun carMicFormat(): CarMic.Format? = CarMic.pickFormat(negotiatedInfo)

    /**
     * SETUP of the head unit microphone stream: `type=100 audioType=speechRecognition input=true`, with **the UDP port the phone itself
     * listens on** put into `dataPort`; the head unit sends microphone packets to this port (DiPlay head-unit-side implementation:
     * `wantsMicrophone` opens the mic for speechRecognition/telephony alike, target = session peer address:dataPort).
     * Uplink and downlink share one audioFormat; the downlink (assistant reply) still uses the existing media audio stream, and this one is receive-only.
     */
    fun runMicStream(
        sharedSecret: ByteArray,
        format: CarMic.Format,
        localPort: Int,
    ): MicStreamInfo? {
        // Limited to within 2^53: receivers written in JS (WinPlay) read plist integers as Number, and beyond 2^53 they round,
        // so the salt "DataStream-Salt<id>" no longer matches on both sides -> every microphone packet fails authentication. The iPhone side has no length requirement on the ID.
        val connectionId = (SecureRandom().nextLong() and ((1L shl 53) - 1)).coerceAtLeast(1L)
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to CarMic.STREAM_TYPE_MAIN_AUDIO,
                                "streamConnectionID" to connectionId,
                                "audioFormat" to format.bit,
                                "audioType" to CarMic.AUDIO_TYPE_SPEECH,
                                "audioLatencyMs" to format.latencyMs.toLong(),
                                "input" to true,
                                "dataPort" to localPort.toLong(),
                                "supportsRTPPacketRedundancy" to false,
                                "supportsHighAccuracyTimestamps" to false,
                            ),
                        ),
                ),
            )
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val startedAt = SystemClock.uptimeMillis()
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "rtsp://$host:${endpoint.airPlayPort}/",
                    body,
                    timeoutMillis = 8_000,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("CARMIC: microphone stream SETUP failed - ${error.message}")
                return null
            }
        log(
            "CARMIC: microphone stream SETUP round trip ${SystemClock.uptimeMillis() - startedAt}ms [$statusLine] " +
                "format=0x${format.bit.toString(16)} ${format.sampleRate}Hz local port=$localPort",
        )
        if (!statusLine.contains("200")) return null
        val reply = runCatching { BinaryPlist.parse(responseBody) as? Map<*, *> }.getOrNull()
        log("CARMIC: SETUP reply streams=${reply?.get("streams")}")
        val stream = (reply?.get("streams") as? List<*>)?.firstOrNull() as? Map<*, *>
        return MicStreamInfo(
            streamConnectionId = connectionId,
            inputKey = CarMic.inputKey(sharedSecret, connectionId),
            format = format,
            carDataPort = portOf(stream?.get("dataPort")),
            outputKey = CarMic.outputKey(sharedSecret, connectionId),
        )
    }

    /**
     * Ends the head unit microphone stream. Carries streamConnectionID and audioType: implementations like DiPlay look only at
     * type on TEARDOWN and would tear down the type=100 media stream too (the caller decides from this whether to rebuild the media audio).
     */
    fun teardownMicStream(info: MicStreamInfo) {
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to CarMic.STREAM_TYPE_MAIN_AUDIO,
                                "streamConnectionID" to info.streamConnectionId,
                                "audioType" to CarMic.AUDIO_TYPE_SPEECH,
                            ),
                        ),
                ),
            )
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        try {
            val (statusLine, _) =
                request(
                    "TEARDOWN",
                    "rtsp://$host:${endpoint.airPlayPort}/",
                    body,
                    timeoutMillis = 4_000,
                    contentType = "application/x-apple-binary-plist",
                )
            log("CARMIC: microphone stream TEARDOWN [$statusLine]")
        } catch (error: IOException) {
            log("CARMIC: microphone stream TEARDOWN failed - ${error.message}")
        }
    }

    /**
     * Queries and corrects the receiver volume (AirPlay `GET_PARAMETER`/`SET_PARAMETER` + `volume`).
     *
     * Why it is needed (measured on Honda 2026-10-20): after the AAC-LC path is built the head unit **consumes samples in real time**
     * (`/feedback` `sampleTime` advances at 44.1k), but the speakers are completely silent. A real iPhone proactively sends
     * `SET_PARAMETER volume` during the session; if the receiver's AirPlay gain stays at the mute position
     * (-144dB), the symptom is exactly "data flowing, no sound".
     *
     * Only two things are done here: (1) record the head unit current volume in the log (direct evidence for judging the "mute position");
     * (2) if it really is at the mute position (<= [MUTE_THRESHOLD_DB]), raise it to [SAFE_VOLUME_DB].
     * A failed query / an unsupported head unit does not affect the main link.
     */
    private fun probeAndFixVolume(host: String) {
        val url = "rtsp://$host:${endpoint.airPlayPort}/"
        val volume: Float? =
            try {
                val (status, body) =
                    request(
                        "GET_PARAMETER",
                        url,
                        "volume".toByteArray(Charsets.US_ASCII),
                        timeoutMillis = 4_000,
                        contentType = "text/parameters",
                    )
                val text = body.toString(Charsets.UTF_8).trim()
                log("AIRPLAY: head unit volume query $status body=${text.take(160)}")
                VOLUME_REGEX
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
            } catch (error: Exception) {
                log("AIRPLAY: head unit volume query failed - ${error.message} (ignored, audio stream unaffected)")
                null
            }
        // Found and not at the mute position: respect the head unit current volume and do not overwrite it.
        if (volume != null && volume > MUTE_THRESHOLD_DB) return
        // When it cannot be found (Honda measured returning 501 Not Implemented), still send once:
        // the original implementation simply returned, which means the session never set the receiver volume at all; if the head unit AirPlay
        // gain sits at the mute position, the symptom is "data flowing, speakers silent", and we would not even get a log.
        try {
            val payload = String.format(java.util.Locale.US, "volume: %.6f", SAFE_VOLUME_DB)
            val (status, body) =
                request(
                    "SET_PARAMETER",
                    url,
                    payload.toByteArray(Charsets.US_ASCII),
                    timeoutMillis = 4_000,
                    contentType = "text/parameters",
                )
            log(
                if (volume == null) {
                    "AIRPLAY: head unit does not support volume query -> still sending volume $SAFE_VOLUME_DB dB ($status ${body.size}B)"
                } else {
                    "AIRPLAY: head unit volume at the mute position ($volume dB) -> set to $SAFE_VOLUME_DB dB ($status ${body.size}B)"
                },
            )
        } catch (error: Exception) {
            log("AIRPLAY: setting head unit volume failed - ${error.message}")
        }
    }

    /**
     * Builds the (ordered) **candidate audio profiles** from the head unit `/info` audioFormats / audioLatencies.
     *
     * Difference from the old implementation (which returned a single "negotiation result"; measured on Honda HFT 2026-10-19):
     * a head unit often "accepts SETUP for a profile, gives a dataPort and receives UDP packets, yet its internal audio path does not come up",
     * and the whole session is torn down seconds later. So the caller must be able to **switch profile and retry**, and all legal profiles are returned here.
     *
     * Rules:
     *  1. only `type=100` (main audio) is considered;
     *  2. each `audioType` profile matches a PCM format by its own `audioOutputFormats` bitmap
     *     (48k stereo bit15 first, then 44.1k stereo bit11);
     *  3. ordering: by default the `media` profile comes first (the real iPhone media audio path, the only profile that produced sound on Audi);
     *     with [preferCompat]=true the order flips and non-media (compatibility etc.) comes first,
     *     because on some head units the media profile only declares 44.1k and its audio path only takes 48k, so only the compatibility profile works;
     *  4. latency is the outputLatencyMicros of that (type, audioType) in audioLatencies,
     *     falling back to [AUDIO_LATENCY_FALLBACK_MS] if unavailable.
     * When /info cannot be fetched, fall back to the protocol baseline (media + PCM48k + 250ms), same as before the change.
     */
    fun audioCandidates(preferCompat: Boolean = false): List<AudioProfile> {
        val baseline =
            listOf(
                AudioProfile(AUDIO_TYPE_MEDIA, AUDIO_FORMAT_PCM_48K_STEREO, 48_000, AUDIO_LATENCY_FALLBACK_MS),
            )
        val info = negotiatedInfo ?: return baseline
        val declared =
            (info["audioFormats"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                .orEmpty()
        if (declared.isEmpty()) {
            log("AIRPLAY: head unit /info declares no audioFormats -> trying the protocol baseline")
            return baseline
        }

        // ---- 1) AAC-LC path: MainHighAudio(102) first, then type=100 ----
        // If the head unit declares AAC-LC on 102/media, it has a separate high-quality media audio path
        // (a real iPhone sends media audio through it). Pushing PCM to 100 shows as: SETUP answers 200, gives a dataPort,
        // but controlPort=0, /feedback sampleTime stays 0, and the whole session is torn down after ~6.5 s.
        val aac = mutableListOf<AudioProfile>()
        val aacOptions =
            listOf(
                Triple(AUDIO_STREAM_TYPE_MAIN_HIGH, AUDIO_FORMAT_AAC_LC_44K_STEREO, 44_100),
                Triple(AUDIO_STREAM_TYPE_MAIN_HIGH, AUDIO_FORMAT_AAC_48K_STEREO, 48_000),
                Triple(AUDIO_STREAM_TYPE, AUDIO_FORMAT_AAC_LC_44K_STEREO, 44_100),
                Triple(AUDIO_STREAM_TYPE, AUDIO_FORMAT_AAC_48K_STEREO, 48_000),
            )
        aacOptions.forEach { (streamType, bit, rate) ->
            declared
                .filter { ((it["type"] as? Number)?.toLong()) == streamType }
                .forEach { channel ->
                    val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                    if ((output and bit) == 0L) return@forEach
                    val audioType = (channel["audioType"] as? String)?.lowercase() ?: return@forEach
                    // Media audio only goes through the media profile; compatibility/default are for alert tones / compatibility scenarios
                    if (audioType != AUDIO_TYPE_MEDIA) return@forEach
                    aac +=
                        AudioProfile(
                            audioType = audioType,
                            formatBit = bit,
                            sampleRate = rate,
                            latencyMs = latencyFor(info, audioType, streamType),
                            streamType = streamType,
                            codec = AudioCodec.AAC_LC,
                        )
                }
        }

        // ---- 2) PCM path (exists only in type=100) ----
        val formats = declared.filter { ((it["type"] as? Number)?.toLong()) == AUDIO_STREAM_TYPE }
        val pcm = mutableListOf<AudioProfile>()
        // Only "media-class" profiles are considered: head units also declare telephony / speechRecognition / alert profiles,
        // whose bitmaps likewise contain PCM48k, but pushing a media stream into the voice/alert path only yields garbled sound or silence.
        // media / compatibility / default are the profiles that both ends treat as media paths.
        val mediaTypes =
            formats.filter { channel ->
                val type = (channel["audioType"] as? String)?.lowercase()
                type == AUDIO_TYPE_MEDIA || type == "compatibility" || type == "default"
            }
        val pool = mediaTypes.ifEmpty { formats }
        // Outer loop by format (48k -> 44.1k), inner loop by the head unit declaration order: within the same profile 48k naturally comes first
        listOf(AUDIO_FORMAT_PCM_48K_STEREO to 48_000, AUDIO_FORMAT_PCM_44K_STEREO to 44_100)
            .forEach { (bit, rate) ->
                pool.forEach { channel ->
                    val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                    if ((output and bit) == 0L) return@forEach
                    val type = (channel["audioType"] as? String)?.lowercase() ?: return@forEach
                    pcm += AudioProfile(type, bit, rate, latencyFor(info, type, AUDIO_STREAM_TYPE))
                }
            }
        // The media profile is top priority; the rest are ordered by familiarity compatibility -> default (48k first within a group)
        val priority = { type: String ->
            when {
                type == AUDIO_TYPE_MEDIA && !preferCompat -> 0
                type == "compatibility" && !preferCompat -> 1
                type == "compatibility" && preferCompat -> 0
                type == AUDIO_TYPE_MEDIA && preferCompat -> 2
                else -> 3
            }
        }
        // "Compatibility profile" = force the legacy PCM path (skip AAC): on some head units AAC-LC gets rejected right at SETUP,
        // so a manual entry is kept, so that the automatic candidates do not hit the wall first on every connection.
        val ordered =
            (if (preferCompat) emptyList() else aac.distinctBy { it.key }) +
                pcm.sortedBy { priority(it.audioType) }
        if (ordered.isEmpty()) {
            log(
                "AIRPLAY: head unit declares no usable AAC-LC / PCM stereo format" +
                    " (${declared.size} profiles in total) -> still trying the baseline",
            )
            return baseline
        }
        log("AIRPLAY: audio candidates (by preference)=${ordered.map { it.label }}")
        return ordered.distinctBy { it.key }
    }

    /** Output latency (ms) declared by that (type, audioType) profile; the fallback is used when there is no valid value. */
    private fun latencyFor(
        info: Map<*, *>,
        audioType: String,
        streamType: Long = AUDIO_STREAM_TYPE,
    ): Long {
        val declaredMs =
            (info["audioLatencies"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull {
                    ((it["type"] as? Number)?.toLong()) == streamType &&
                        (it["audioType"] as? String)?.lowercase() == audioType
                }?.let { (it["outputLatencyMicros"] as? Number)?.toLong() }
                ?.takeIf { it > 0 }
                ?.let { it / 1000 }
        // Lower bound 250ms: the outputLatencyMicros declared by the head unit is only its own output latency (measured 35ms),
        // and using it as the end-to-end buffer turns WiFi jitter straight into underruns. catplay likewise uses
        // `max(declared value, 250ms)` for AirPlay mirroring.
        return declaredMs?.coerceAtLeast(AUDIO_LATENCY_FALLBACK_MS) ?: AUDIO_LATENCY_FALLBACK_MS
    }

    /**
     * Head unit identity text (source of the key for the audio profile memory): uses only the stable model + manufacturer from /info;
     * the Bluetooth name / firmware string changes across reconnects and must not enter the key.
     */
    fun receiverIdentity(): String {
        val info = negotiatedInfo ?: return "unknown"
        val manufacturer = (info["manufacturer"] as? String).orEmpty().trim()
        val model = (info["model"] as? String).orEmpty().trim()
        return "$manufacturer $model".trim().ifBlank { "unknown" }
    }

    /**
     * SETUP #2: negotiates the main screen video stream (type 110). streamConnectionID is chosen by the client,
     * both ends derive the stream key themselves according to the session encryption mode; the receiver opens a TCP data port and returns it in the reply.
     *
     * [sessionKey]: shared secret derived by pair-verify; null = unpaired session (`et=NONE`),
     * which follows catplay: AES-128-CTR + all-zero session key.
     */
    fun runScreenStream(
        sessionKey: ByteArray?,
        displayUuid: String? = null,
    ): ScreenStreamInfo? {
        // Follows catplay (real-car verified): streamConnectionID = random u64 (+1 to guarantee non-zero)
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val streamUuid =
            displayUuid ?: java.util.UUID
                .randomUUID()
                .toString()
        log(
            "AIRPLAY: main screen stream SETUP uuid=$streamUuid " +
                if (displayUuid == null) {
                    " (WARNING: /info provided no displayUuid, so a random value is used; the head unit may accept the stream but not bind it to any screen)"
                } else {
                    " (from /info displays[].uuid)"
                },
        )
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to SCREEN_STREAM_TYPE, // 110 = main screen (100 is audio, for which DiPlay opens a UDP port)
                                "streamConnectionID" to connectionId,
                                // Real iPhone / catplay sender: uuid and latencyMs must both be present.
                                // uuid must be the display UUID declared in the receiver /info displays[] (real cars validate it;
                                // a random value once made the Audi answer 400 and tear down the session). latencyMs=25 is the catplay measured value.
                                "uuid" to streamUuid,
                                "latencyMs" to SCREEN_LATENCY_MS,
                            ),
                        ),
                ),
            )
        // The request path is exactly the same as catplay: stream SETUP uses "/" (the initial SETUP uses "/setup").
        // Some receivers (Audi firmware) are path-sensitive, and a mismatched path gets the whole message a 400.
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "/",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: stream negotiation failed - ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log(
                "AIRPLAY: stream negotiation reply [$statusLine] (response head: ${lastResponseHeadOfRequest()})\n" +
                    "  -> request body (${body.size}B)=" + body.joinToString("") { "%02x".format(it) },
            )
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                null
            } ?: run {
                log("AIRPLAY: stream negotiation reply parse failed")
                return null
            }
        val streams =
            root["streams"] as? List<*> ?: run {
                log("AIRPLAY: stream negotiation reply missing streams")
                return null
            }
        val screen = streams.firstOrNull() as? Map<*, *>
        val dataPort = portOf(screen?.get("dataPort"))
        if (screen == null || dataPort == 0) {
            log("AIRPLAY: main screen stream rejected (streams=$streams)")
            return null
        }
        val cipher =
            if (sessionKey != null) {
                StreamFrameCipher.ChaCha(AirPlayStreamKeys.chachaStreamKey(sessionKey, connectionId))
            } else {
                val (key, iv) = AirPlayStreamKeys.aesStreamKeys(AirPlayStreamKeys.ZERO_SESSION_KEY, connectionId)
                StreamFrameCipher.AesCtr(key, iv)
            }
        log(
            "AIRPLAY: ✓ main screen stream negotiated dataPort=$dataPort connId=$connectionId " +
                "encryption=${if (cipher is StreamFrameCipher.ChaCha) "ChaCha20" else "AES-128-CTR (unpaired session)"}",
        )
        // MIB3: after the screen-stream SETUP 200 gives the dataPort, a real device sends detect on the control channel (Frida
        // timing: screen SETUP -> phone detect on control -> HU SYN on control). See armMib3VirtualLink.
        armMib3VirtualLink()
        return ScreenStreamInfo(connectionId, dataPort, cipher)
    }

    /** RECORD: activates the session (receiver onSessionActive). The path is the relative `/` (same as catplay record()). */
    fun record(): Boolean =
        try {
            val (statusLine, _) =
                request(
                    "RECORD",
                    "/",
                    BinaryPlist.encode(linkedMapOf<String, Any?>()),
                    contentType = "application/x-apple-binary-plist",
                )
            val ok = statusLine.contains("200")
            log(if (ok) "AIRPLAY: ✓ RECORD done - session activated" else "AIRPLAY: RECORD reply [$statusLine]")
            // Adapter wireless order: disableBluetooth right after the session is live. CarDeck's
            // screen SETUP and modesChanged already ran before RECORD, so this is the earliest point.
            if (ok && AdapterMode.active && !disableBluetoothSent) sendDisableBluetooth()
            ok
        } catch (error: IOException) {
            log("AIRPLAY: RECORD failed - ${error.message}")
            false
        }

    /**
     * Connects the event channel (the receiver waits for us on the eventPort from the SETUP reply).
     *
     * When [sessionKey] is non-null the Events key encrypts (note the direction label is the opposite of intuition: the receiver
     * reads our data with Events-Read); **null = unpaired session, the channel stays plaintext throughout** (same as catplay
     * `EventsServer::unencrypted`; pre-HomeKit old head units only accept this).
     * The reader thread reassembles plaintext into RTSP messages, parses plist commands (such as hidSendReport touch) and calls back [onCommand].
     */
    fun connectEventChannel(
        sessionKey: ByteArray?,
        setup: SetupInfo,
        onCommand: (Map<*, *>) -> Unit = {},
    ) {
        if (setup.eventPort == 0) {
            log("EVENT: SETUP provided no eventPort, skipping")
            return
        }
        val host = receiverHost() ?: return
        try {
            val socket = Socket().also { CarNetwork.bind(it, "event channel") }
            socket.connect(InetSocketAddress(resolveAddress(host), setup.eventPort), 5_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            // The event channel is also a long-lived connection: it must detect on its own when the peer disappears (see the TcpLiveness comment).
            TcpLiveness.configure(socket, "event channel", log)
            log("EVENT: ✓ event channel connected $host:${setup.eventPort} (${if (sessionKey != null) "encrypted" else "plaintext"})")
            val cipher =
                sessionKey?.let { secret ->
                    val phoneWriteKey =
                        AirPlayCrypto.hkdfSha512(
                            secret,
                            "Events-Salt".toByteArray(),
                            "Events-Read-Encryption-Key".toByteArray(),
                        )
                    val phoneReadKey =
                        AirPlayCrypto.hkdfSha512(
                            secret,
                            "Events-Salt".toByteArray(),
                            "Events-Write-Encryption-Key".toByteArray(),
                        )
                    ControlCipher(phoneReadKey, phoneWriteKey)
                }
            registerEventCommandChannel(socket, cipher)
            Thread({
                val input = socket.getInputStream()
                val accumulator = RtspAccumulator()
                var pending = ByteArray(0)
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count =
                            try {
                                input.read(buffer)
                            } catch (_: java.net.SocketTimeoutException) {
                                // A 30 s idle is the normal state (the event channel is low-frequency): it is only a wake-up tick and must never exit;
                                // if the reader thread exits, the receiver sees the event connection as dropped and tears down the whole session (including video/Bluetooth)
                                continue
                            }
                        if (count < 0) {
                            log("EVENT: channel closed (EOF)")
                            break
                        }
                        pending += buffer.copyOf(count)
                        val plain =
                            if (cipher == null) {
                                val all = pending
                                pending = ByteArray(0)
                                all
                            } else {
                                val decrypted = cipher.decrypt(pending)
                                pending = decrypted.rest
                                decrypted.data
                            }
                        var eventOtherLogged = 0
                        for (message in accumulator.offer(plain)) {
                            val firstLine = message.firstLine
                            // (a) A status line ("RTSP/1.0 200 OK" etc.) = the head unit's reply to **our command**:
                            //     skip silently: do not answer, do not log (answering triggers a ping-pong echo, see respondEventRequest)
                            if (firstLine.startsWith("RTSP/") || firstLine.startsWith("HTTP/")) continue
                            // (b) Request check: method token + a path starting with "/". A malformed line (such as "OK 200 OK":
                            //     the 2nd token is a number) counts as a non-request -> dropped silently, no reply, no log
                            val tokens = firstLine.split(" ")
                            val isRequest = tokens.size >= 2 && tokens[1].startsWith("/")
                            if (!isRequest) continue
                            // Head unit request: reply 200 immediately (echoing the request CSeq); /command is dispatched, the rest is throttled and logged
                            val cseq = CSEQ_REGEX.find(message.head)?.groupValues?.get(1)
                            respondEventRequest(cseq)
                            if (!firstLine.contains("/command")) {
                                unknownEventRequests++
                                if (unknownEventRequests <= 10) {
                                    log("EVENT: unknown request [$firstLine] body=${message.body.size}B")
                                }
                                continue
                            }
                            var commandError: String? = null
                            val parsed =
                                try {
                                    val result = BinaryPlist.parseRecovered(message.body)
                                    result.note?.let { note ->
                                        log("EVENT: /command tolerant parse succeeded - $note")
                                    }
                                    result.value
                                } catch (error: Throwable) {
                                    // Catching only IllegalArgumentException is not enough: an out-of-range/length exception escaping from
                                    // here would kill the event reader thread outright, and the head unit would tear down the whole session.
                                    commandError = "${error.javaClass.simpleName}: ${error.message}"
                                    null
                                }
                            val command = parsed as? Map<*, *>
                            if (command == null) {
                                // The head unit POSTs an undecodable /command every second: deduplicate by "body fingerprint",
                                // keep one full dump per shape (cause + message head + full hex), and only count the rest.
                                badEventCommands++
                                val signature =
                                    message.body.size.toString() + ":" + hexPrefix(message.body, 8)
                                if (badEventSignatures.add(signature) && badEventSignatures.size <= 4) {
                                    log(
                                        "EVENT: command not recognized [$firstLine] body=${message.body.size}B " +
                                            "cause=" + (commandError ?: "parse result=${parsed?.javaClass?.simpleName}"),
                                    )
                                    log(
                                        "EVENT: command message head=" +
                                            message.head.replace("\r\n", " | ").take(300),
                                    )
                                    log("EVENT: command message body (${message.body.size}B) hex=" + hexDump(message.body))
                                }
                                if (badEventCommands % 120 == 0) {
                                    log("EVENT: $badEventCommands /command parse failures so far (silenced, counting only)")
                                }
                                continue
                            }
                            try {
                                if (AdapterMode.active && command["type"] == "requestUI") onAdapterRequestUI()
                                onCommand(command)
                            } catch (error: Exception) {
                                log("EVENT: command handling exception - ${error.message}")
                            }
                        }
                    }
                } catch (error: IOException) {
                    log("EVENT: read ended - ${error.message}")
                }
            }, "event-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: IOException) {
            log("EVENT: connection failed - ${error.message}")
        }
    }

    /** Streaming RTSP message reassembler: feed arbitrary plaintext fragments, pop complete messages (first line + body). */
    private class RtspAccumulator {
        /** Complete message: first line (for classification), full header text (to extract CSeq), body. */
        class Message(
            val firstLine: String,
            val head: String,
            val body: ByteArray,
        )

        private var buffer = ByteArray(0)

        fun offer(bytes: ByteArray): List<Message> {
            if (bytes.isEmpty()) return emptyList()
            buffer += bytes
            val messages = ArrayList<Message>()
            while (true) {
                val index = indexOf(buffer, HEAD_SEPARATOR)
                if (index < 0) break
                val headText = String(buffer, 0, index, Charsets.ISO_8859_1)
                val (firstLine, contentLength) = parseRtspHead(headText)
                val bodyStart = index + HEAD_SEPARATOR.size
                if (buffer.size - bodyStart < contentLength) break
                messages +=
                    Message(
                        firstLine,
                        headText,
                        buffer.copyOfRange(bodyStart, bodyStart + contentLength),
                    )
                buffer = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            }
            return messages
        }
    }

    /**
     * `GET /info` (same as catplay `AirPlayRtspClient::info`):
     * the body is an **empty bplist dictionary** (not zero-length), and Content-Type must be
     * `application/x-apple-binary-plist`. Old firmware (210.81 era) answers /info with 400/455 when the body is missing or the
     * Content-Type is wrong, and the following SETUP fails outright.
     */
    private fun requestInfo(timeoutMillis: Int = 10_000): Pair<String, ByteArray> =
        request(
            "GET",
            "/info",
            BinaryPlist.encode(linkedMapOf<String, Any?>()),
            timeoutMillis,
            contentType = "application/x-apple-binary-plist",
        )

    // ---- RTSP requests ----

    private fun ensureConnected(timeoutMillis: Int = 5_000): Boolean {
        if (socket?.isConnected == true && socket?.isClosed == false) return true
        // Snapshot the endpoint and generation: the controller may swap the endpoint during connect (0x4301 arriving late);
        // if a connection built to the old address is then used for a request, it keeps hitting the wrong machine until this round times out.
        val target = endpoint
        val generation = endpointGeneration
        val candidates = EndpointHosts.candidates(target.ipAddresses)
        if (candidates.isEmpty()) {
            log("AIRPLAY: 0x4301/mDNS provided no usable address, cannot connect")
            return false
        }
        if (target.airPlayPort <= 0) {
            log("AIRPLAY: port unknown (${target.airPlayPort}), cannot connect")
            return false
        }
        val failures = ArrayList<String>(candidates.size)
        for (host in candidates) {
            val address =
                try {
                    resolveAddress(host)
                } catch (error: Exception) {
                    failures += "$host (resolve failed: ${error.message})"
                    continue
                }
            try {
                val newSocket = Socket().also { CarNetwork.bind(it, "control channel") }
                newSocket.connect(InetSocketAddress(address, target.airPlayPort), timeoutMillis)
                if (generation != endpointGeneration) {
                    // The endpoint was updated mid-connect: discard this old connection and reconnect to the new address next round
                    runCatching { newSocket.close() }
                    return false
                }
                // Reading is the job of the dedicated reader thread (blocks waiting for data), and request timeouts are controlled by polling the pending table;
                // leaving a soTimeout here would make the reader thread periodically get SocketTimeoutException and exit
                newSocket.soTimeout = 0
                // Peer health check: the kernel default keepalive waits 2 hours before probing, while when the head unit is switched off /
                // the phone leaves the hotspot the link often sends no RST, and the reader thread would hang forever (picture frozen, watchdog not triggered).
                TcpLiveness.configure(newSocket, "control channel", log)
                socket = newSocket
                activeAddress = address
                activeHost = addressLiteral(address)
                log("AIRPLAY: TCP connected $activeHost:${target.airPlayPort} (local ${newSocket.localAddress.hostAddress})")
                return true
            } catch (error: IOException) {
                failures += "$host (${error.message})"
            }
        }
        log(
            "AIRPLAY: connection failed - ${failures.joinToString("; ")}\n" +
                "  -> if it keeps failing: confirm the phone is currently on the receiver hotspot \"${target.ssid}\", " +
                "not the home/office WiFi (with the wrong network bound, the head unit subnet is unreachable)",
        )
        return false
    }

    /**
     * Resolves the connect address. An IPv6 link-local must carry a scope (`%wlan0`) for Java to use it:
     * mDNS usually returns scoped addresses, while `fe80::...` in 0x4301 has none; the current
     * WiFi interface is added here, otherwise `connect` reports Network is unreachable.
     */
    private fun resolveAddress(host: String): InetAddress {
        val address = InetAddress.getByName(host)
        if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0) {
            val zone = host.substringAfter('%', "").takeIf { it.isNotEmpty() }
            val iface = zone?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() } ?: wifiInterface()
            if (iface != null) {
                return Inet6Address.getByAddress(null, address.address, iface)
            }
        }
        return address
    }

    /** Finds the interface carrying the head unit hotspot traffic (for the IPv6 link-local scope). */
    private fun wifiInterface(): NetworkInterface? {
        val candidates =
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            }.getOrDefault(emptyList())
        return candidates.firstOrNull { nic ->
            nic.isUp &&
                !nic.isLoopback &&
                (nic.name.startsWith("wlan") || nic.name.startsWith("ap") || nic.name.startsWith("eth"))
        } ?: candidates.firstOrNull { nic -> nic.isUp && !nic.isLoopback }
    }

    /**
     * Address text: an IPv6 link-local must carry a scope to be usable by a later `InetAddress.getByName`.
     * Always outputs the **numeric scope id** (`fe80::1%3`); the interface-name form is not parsed consistently across ROMs.
     */
    private fun addressLiteral(address: InetAddress): String {
        val text = address.hostAddress ?: return address.toString()
        if (address is Inet6Address && address.scopeId != 0) {
            return "${text.substringBefore('%')}%${address.scopeId}"
        }
        return text
    }

    private fun post(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val (statusLine, responseBody) = request("POST", path, body)
        if (!statusLine.contains("200")) {
            throw IOException("POST $path reply [$statusLine]")
        }
        return responseBody
    }

    /**
     * Pairing requests (pair-setup / pair-verify): headers identical to the Apple client and catplay PairingHelperTx.
     * Measured on real CarPlay cars (the catplay TX direction works against real head units):
     *  - Content-Type: application/pairing+tlv8
     *  - X-Apple-HKP: 2 (HomeKit pairing v2, the identifying header of Apple clients)
     *  - X-Apple-PD: 1 (Pair-Derive, for pair-verify only)
     * 2026-10-11: we used to send the default application/octet-stream without these two headers, and the Audi answered
     * every pair-setup/pair-verify with 403 (rejected at the HTTP layer, never reaching TLV parsing).
     */
    private fun postPairing(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val extra =
            buildList {
                add("X-Apple-HKP" to "2")
                if (path.endsWith("pair-verify")) add("X-Apple-PD" to "1")
            }
        val (statusLine, responseBody) =
            request(
                "POST",
                path,
                body,
                contentType = "application/pairing+tlv8",
                extraHeaders = extra,
            )
        // Diagnostics (real-car 403 debugging): request length + reply status line/length/header bytes
        val headHex = responseBody.take(16).joinToString(" ") { "%02x".format(it) }
        log(
            "AIRPLAY: $path req=${body.size}B -> [$statusLine] resp=${responseBody.size}B" +
                if (responseBody.isNotEmpty()) " head=[$headHex]" else "",
        )
        if (!statusLine.contains("200")) {
            throw IOException("POST $path reply [$statusLine]")
        }
        return responseBody
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray,
        timeoutMillis: Int = 10_000,
        contentType: String = "application/octet-stream",
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): Pair<String, ByteArray> {
        val client = socket ?: throw IOException("not connected")
        val startedAt = SystemClock.uptimeMillis()
        startReader(client)
        val queue = java.util.concurrent.LinkedBlockingQueue<Pair<String, ByteArray>>(1)
        val cseq: Int
        synchronized(rtspWriteLock) {
            cseq = ++cSeq
            // Register first, then write: the response may arrive before write returns
            pendingResponses[cseq] = queue
            try {
                val head =
                    "$method $path RTSP/1.0\r\n" +
                        "CSeq: $cseq\r\n" +
                        "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
                        "Content-Type: $contentType\r\n" +
                        extraHeaders.joinToString("") { "${it.first}: ${it.second}\r\n" } +
                        "Content-Length: ${body.size}\r\n" +
                        "\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + body
                val encryption = cipher
                val wire =
                    if (encryption != null) {
                        synchronized(encryption) { encryption.encrypt(plaintext) }
                    } else {
                        plaintext
                    }
                val output = client.getOutputStream()
                output.write(wire)
                output.flush()
            } catch (error: Throwable) {
                pendingResponses.remove(cseq)
                throw error
            }
        }
        val response =
            try {
                queue.poll(timeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: throw IOException("RTSP timeout (${timeoutMillis}ms, CSeq=$cseq $method $path)")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("RTSP interrupted (CSeq=$cseq $method $path)")
            } finally {
                pendingResponses.remove(cseq)
            }
        // Slow reply trace: >1.5 s means the head unit is arbitrating internally (first-hand evidence when debugging such problems)
        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (elapsed > 1_500) {
            log("AIRPLAY: slow reply ${elapsed}ms ($method $path, CSeq=$cseq) - heartbeat not interrupted while waiting")
        }
        if (response.first == RTSP_CLOSED_MARK) {
            throw IOException("control channel closed (CSeq=$cseq $method $path)")
        }
        lastRequestCSeq.set(cseq)
        return response
    }

    /**
     * Control channel reader thread: receives, decrypts and dispatches responses to their requests by CSeq.
     *
     * Why it must be asynchronous (root cause measured 2026-10-17): the Audi audio SETUP reply takes ~2.7 s, and the earlier
     * "one lock per connection + synchronous response read" stalled the feedback heartbeat completely during that time; the head unit has a
     * ~3 s watchdog on feedback, so cutting supply during the audio SETUP resets the event channel and tears down the whole session
     * (log signature: gap of 2.7 s between feedback #1 and #2, then `EVENT: read ended - Connection reset`).
     * After going async no long request blocks the heartbeat any more.
     */

    /**
     * The reader thread splits the buffer by "whether encryption is enabled":
     * at the moment cipher goes from null to non-null (pair-verify done), plaintext leftovers from before must be discarded;
     * if a plaintext tail were spliced into ciphertext frame parsing, ChaCha frame boundaries would misalign and no later reply could be decrypted.
     */
    private fun readLoop(
        client: Socket,
        generation: Int,
    ) {
        var cipherPending = ByteArray(0)
        var buffer = ByteArray(0)
        var encryptedMode = false
        try {
            val input = client.getInputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                val encryption = cipher
                if (encryption != null && !encryptedMode) {
                    // Switch point: clear the plaintext-side leftovers; from then on everything goes through frame decryption
                    encryptedMode = true
                    cipherPending = ByteArray(0)
                }
                val data =
                    if (encryption == null) {
                        chunk.copyOf(count)
                    } else {
                        cipherPending += chunk.copyOf(count)
                        val decrypted =
                            try {
                                synchronized(encryption) { encryption.decrypt(cipherPending) }
                            } catch (error: Exception) {
                                log("AIRPLAY: control channel decryption failed - ${error.message}")
                                break
                            }
                        cipherPending = decrypted.rest
                        decrypted.data
                    }
                if (data.isEmpty()) continue
                buffer += data
                while (true) {
                    val parsed = parseRtspMessage(buffer) ?: break
                    buffer = parsed.rest
                    dispatchResponse(parsed.statusLine, parsed.body, parsed.head)
                }
            }
        } catch (_: IOException) {
        } finally {
            // Only the "current" reader thread clears the books: a reconnect swaps the socket and starts a new reader thread, and the old thread must not harm the new session
            if (generation == readerGeneration) {
                failAllPending("control channel reader thread ended")
            }
        }
    }

    /** Control channel message (for CSeq dispatch; head is for diagnostics and request/reply classification). */
    private class RtspMessage(
        val statusLine: String,
        val body: ByteArray,
        val head: String,
    ) {
        val cseq: Int? get() =
            CSEQ_REGEX
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        /** Request sent by the head unit (first line is `METHOD /path RTSP/1.0`); a reply's first line is `RTSP/1.0 200 OK`. */
        val isRequest: Boolean
            get() {
                val tokens = statusLine.split(" ")
                return tokens.size >= 2 && tokens[1].startsWith("/")
            }
    }

    private fun dispatchResponse(
        statusLine: String,
        body: ByteArray,
        head: String,
    ) {
        val message = RtspMessage(statusLine, body, head)
        message.cseq?.let { responseHeads[it] = head }
        val queue = message.cseq?.let { pendingResponses[it] }
        if (queue != null) {
            queue.offer(message.statusLine to message.body)
            return
        }
        // No match: the head unit may be sending its own request on the control channel (must answer 200 or it tears the channel down).
        // Only throttled logging, not silent dropping: the earlier synchronous implementation mistook it for the reply to the previous request.
        unmatchedMessages++
        if (unmatchedMessages <= 5) {
            log(
                "AIRPLAY: control channel unmatched message (#$unmatchedMessages)" +
                    "[$statusLine] cseq=${message.cseq} request=${message.isRequest} body=${body.size}B",
            )
        }
        if (message.isRequest) {
            // Reuse the request CSeq to send an empty reply (same convention as the event channel)
            runCatching {
                val payload =
                    "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n" +
                        (message.cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                val encryption = cipher
                synchronized(rtspWriteLock) {
                    val out = socket?.getOutputStream() ?: return@runCatching
                    out.write(
                        if (encryption != null) {
                            synchronized(encryption) { encryption.encrypt(payload.toByteArray(Charsets.US_ASCII)) }
                        } else {
                            payload.toByteArray(Charsets.US_ASCII)
                        },
                    )
                    out.flush()
                }
            }
        }
    }

    /** Count of unmatched messages (for throttled logging). */
    private var unmatchedMessages = 0

    private fun failAllPending(reason: String) {
        val waiting = pendingResponses.values.toList()
        pendingResponses.clear()
        if (waiting.isEmpty()) return
        log("AIRPLAY: $reason - waking ${waiting.size} pending requests")
        waiting.forEach { it.offer(RTSP_CLOSED_MARK to ByteArray(0)) }
    }

    private fun startReader(client: Socket) {
        val previous: Socket?
        synchronized(rtspWriteLock) {
            if (readerSocket === client && readerThread?.isAlive == true) return
            previous = readerSocket
            readerSocket = client
            val generation = ++readerGeneration
            readerThread =
                Thread({ readLoop(client, generation) }, "airplay-rtsp-reader").apply {
                    isDaemon = true
                    start()
                }
        }
        // The old socket's input stream is owned by this thread: it must be closed (this throws SocketException so the old reader thread exits),
        // otherwise two reader threads may block on the same connection and steal packets; after async dispatch this would misdeliver responses
        if (previous != null && previous !== client) {
            runCatching { previous.close() }
        }
    }

    /** Pending CSeq -> response queue (async dispatch; feedback is no longer starved by long requests). */
    private val pendingResponses =
        java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<Pair<String, ByteArray>>>()

    /** Write side is serialized: CSeq allocation + cipher write counter + socket write must be atomic (the read side is on a separate thread). */
    private val rtspWriteLock = Any()

    private var readerThread: Thread? = null
    private var readerSocket: Socket? = null

    /** Reader thread generation: after a reconnect the old thread must not clean the new session's pending table. */
    private var readerGeneration = 0

    /** Latest RTSP response head (stored per CSeq: the reader and request threads run concurrently, and a single field would mismatch other requests). */
    private val responseHeads = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** CSeq of this thread's latest request() (for fetching the response head in diagnostics; ThreadLocal avoids concurrent mismatches). */
    private val lastRequestCSeq = ThreadLocal<Int>()

    /** Gets the response head of this thread's latest request (diagnostics; returns an empty string if unavailable). */
    private fun lastResponseHeadOfRequest(): String =
        lastRequestCSeq
            .get()
            ?.let { responseHeads[it] }
            ?.replace("\r\n", " | ")
            .orEmpty()

    /** Parse result (message + unconsumed remaining bytes). */
    private class ParsedRtsp(
        val statusLine: String,
        val body: ByteArray,
        val head: String,
        val rest: ByteArray,
    )

    /**
     * Cuts a complete message (head + Content-Length body) out of the buffer.
     * Called only from the control channel reader thread (the buffer is that thread's local state, no cross-thread sharing).
     */
    private fun parseRtspMessage(buffer: ByteArray): ParsedRtsp? {
        val separatorIndex = indexOf(buffer, HEAD_SEPARATOR)
        if (separatorIndex < 0) return null
        val headText = String(buffer, 0, separatorIndex, Charsets.ISO_8859_1)
        val (statusLine, contentLength) = parseRtspHead(headText)
        val bodyStart = separatorIndex + HEAD_SEPARATOR.size
        if (buffer.size - bodyStart < contentLength) return null
        val responseBody = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
        val rest =
            if (buffer.size > bodyStart + contentLength) {
                buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            } else {
                ByteArray(0)
            }
        return ParsedRtsp(statusLine, responseBody, headText, rest)
    }

    // ---- Identity persistence ----

    private fun loadOrCreateIdentity(): ControllerIdentity {
        val preferences = context?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val storedId = preferences?.getString("pairingId", null)
        val storedPrivate = preferences?.getString("privateKey", null)
        if (storedId != null && storedPrivate != null) {
            val privateKey = Base64.getDecoder().decode(storedPrivate)
            val publicKey = AirPlayCrypto.ed25519PublicKey(privateKey)
            return ControllerIdentity(storedId, privateKey, publicKey)
        }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val pairingId = UUID.randomUUID().toString()
        preferences
            ?.edit()
            ?.putString("pairingId", pairingId)
            ?.putString("privateKey", Base64.getEncoder().encodeToString(keyPair.privateKey))
            ?.apply()
        return ControllerIdentity(pairingId, keyPair.privateKey, keyPair.publicKey)
    }

    // ---- Utilities ----

    /**
     * Receiver address (shared by the event channel / iAP tunnel / video / audio).
     *
     * Prefers the address that **actually connected during pairing**: of a multi-address endpoint the IPv4 may be unreachable (the Audi hotspot
     * often gives only a link-local IPv6), and if other channels each pick IPv4 on their own, we get "control channel works, video does not".
     */
    private fun receiverHost(): String? = activeHost ?: EndpointHosts.candidates(endpoint.ipAddresses).firstOrNull()

    /**
     * Device ID self-reported in the session-level SETUP (the receiver uses it to identify "which iPhone this is").
     *
     * Must be this phone's real Bluetooth MAC: WinPlay was measured on 2026-10-19 to filter by the iPhone Bluetooth
     * address the user selected, answering 403 to any placeholder/fake value; Audi MIB3 and DiPlay do not validate, so the old hard-coded value
     * never exposed the problem. The BT phase must already have resolved and cached it (0x4300/0x4E0E depend on it too),
     * so the cache is read first here to avoid spawning another shell; a blocking resolve is done only once if the cache is missing.
     */
    private fun setupDeviceId(): String {
        val applicationContext = context ?: return DeviceIdentity.PLACEHOLDER_MAC
        // Return on a cache hit (resolved during the BT phase); a blocking query is allowed only when it is missing.
        return DeviceIdentity.cachedBluetoothMac()
            ?: DeviceIdentity.bluetoothMac(applicationContext)
    }

    /**
     * Builds the session-level SETUP message body (split out for unit tests: once deviceID falls back to a hard-coded fake value,
     * receivers that filter by device such as WinPlay answer 403 directly, and the symptom is a disconnect only after 45 seconds).
     */
    internal fun buildSessionSetupBody(
        deviceId: String,
        timingPort: Int,
        /** false = unpaired/unencrypted session: must explicitly carry `et=NONE` (same as catplay do_initial_setup). */
        encrypted: Boolean = true,
        /** Adapter mode passes the Wi-Fi interface MAC; everyone else keeps the legacy value. */
        macAddress: String = DEFAULT_SETUP_MAC_ADDRESS,
    ): ByteArray {
        val fields =
            linkedMapOf<String, Any?>(
                "deviceID" to deviceId,
                "macAddress" to macAddress,
                "name" to "iPhone",
                "model" to "iPhone14,2",
                "osName" to "iPhone OS",
                "osBuildVersion" to "19A404",
                "osVersion" to "15.0.2",
                // The iPhone's own AirPlay version (catplay verified value); previously the head unit's 320.17.7 was used by mistake
                "sourceVersion" to SENDER_SOURCE_VERSION,
                "timingPort" to timingPort.toLong(),
                "sessionUUID" to
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .uppercase(),
                "sessionCorrelationUUID" to
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .uppercase(),
                "statsCollectionEnabled" to false,
                "updateSessionRequest" to false,
                "keepAliveLowPower" to true,
                "features" to CONTROLLER_FEATURES,
            )
        if (!encrypted) {
            // AirPlayEncryptionType::NONE = 1<<0 (catplay's downgrade value when /pair-setup was not used).
            // Without this key a strict receiver may treat the session as "encrypted but keyless" and reject it.
            fields["et"] = 1L
        }
        return BinaryPlist.encode(fields)
    }

    private fun typeName(value: Any?): String =
        when (value) {
            null -> "null"
            is ByteArray -> "data(${value.size}B)"
            is List<*> -> "list(${value.size})"
            is Map<*, *> -> "dict(${value.size})"
            is String -> "str(${value.length})"
            else -> value.javaClass.simpleName
        }

    /**
     * `/info displays[].uuid` stringified uniformly.
     *
     * All three encodings have been seen: strings (Audi "0"/"1", some firmware gives a 36-char UUID),
     * the **16-byte UUID type** (bplist 0xB; `toString()` would turn into garbage like `[B@1f2e3d4`),
     * and CFKeyedArchiver UID (an integer). The `uuid` in the screen-stream SETUP must be
     * **exactly identical** to what the receiver declares, otherwise the head unit may accept the stream but bind it to no screen (symptom: "connected but no picture").
     */
    private fun uuidText(value: Any?): String? =
        when (value) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            is ByteArray ->
                if (value.size == 16) {
                    val hex = value.joinToString("") { "%02x".format(it) }
                    "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                        "${hex.substring(16, 20)}-${hex.substring(20)}"
                } else {
                    value.joinToString("") { "%02x".format(it) }
                }
            is Number -> value.toString()
            else -> value.toString().takeIf { it.isNotBlank() }
        }

    private fun logInfoSummary(root: Map<*, *>) {
        // Full list of top-level fields (key: type): shows at a glance under which key the head unit hides its capabilities, instead of only knowing
        // the few official Apple keys (BMW iD9's HID declaration may sit on a key we do not parse)
        log(
            "AIRPLAY: /info top-level fields(${root.size})=" +
                root.entries.joinToString(" ") { (key, value) -> "$key:${typeName(value)}" },
        )
        // Any key whose name contains hid is printed in full (device list + descriptors are the prerequisite for the touch path)
        root.entries
            .filter { (key, _) -> key.toString().contains("hid", ignoreCase = true) }
            .forEach { (key, value) -> log("AIRPLAY: /info $key=$value") }
        val features = root["features"] as? Long
        log(
            "AIRPLAY: /info summary name=${root["name"]} model=${root["model"]} " +
                "deviceID=${root["deviceID"]}" +
                if (features != null) " features=0x${features.toString(16)}" else "",
        )
        (root["displays"] as? List<*>)?.forEachIndexed { index, display ->
            log("AIRPLAY: /info displays[$index]=$display")
            if (display is Map<*, *>) {
                log(
                    "AIRPLAY: /info displays[$index] key/values=" +
                        display.entries.joinToString(" ") { (key, value) ->
                            "$key=${value.toString().take(80)}"
                        },
                )
            }
        }
        // Audio capabilities are printed entry by entry (the whole-line /info log gets truncated, and audioFormats falls right at the cut):
        // the audioType profiles and bitmaps that head units declare for type=100 differ greatly, and negotiation depends on these details
        (root["audioFormats"] as? List<*>)?.forEachIndexed { index, entry ->
            val format = entry as? Map<*, *> ?: return@forEachIndexed
            val output = (format["audioOutputFormats"] as? Number)?.toLong() ?: 0L
            val input = (format["audioInputFormats"] as? Number)?.toLong() ?: 0L
            log(
                "AIRPLAY: audioFormat[$index] type=${format["type"]} audioType=${format["audioType"]} " +
                    "output=0x${output.toString(16)} input=0x${input.toString(16)}" +
                    " (PCM48k stereo bit15=${if ((output and (1L shl 15)) != 0L) "✓" else "✗"})",
            )
        }
        (root["audioLatencies"] as? List<*>)?.forEachIndexed { index, entry ->
            val latency = entry as? Map<*, *> ?: return@forEachIndexed
            log(
                "AIRPLAY: audioLatency[$index] type=${latency["type"]} audioType=${latency["audioType"]} " +
                    "in=${latency["inputLatencyMicros"]} out=${latency["outputLatencyMicros"]}",
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val cleaned = hex.trim()
        if (cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/**
 * Pairing failure retry backoff (pure function, pinned by replay tests): 403 = the real-car "trust established, head unit not ready"
 * state (real-device sysdiagnose: the ready window is about 18~20 s, and frequent retries may keep resetting its state machine);
 * other failures (port not open etc.) keep polling quickly, waiting for the invite.
 */
internal fun pairingRetryBackoffMs(failureMessage: String): Long = if (failureMessage.contains("403")) 5_000L else 500L

/** `disableBluetooth` command body: deviceID is "MAC address of Bluetooth device to disable connectivity to" (Apple). */
internal fun disableBluetoothCommand(bluetoothMac: String): Map<String, Any?> =
    linkedMapOf(
        "type" to "disableBluetooth",
        "params" to linkedMapOf<String, Any?>("deviceID" to bluetoothMac.uppercase()),
    )

private val MAC_ADDRESS_REGEX = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")

/** First MAC in [text] as upper-case colon form; null for none or the all-zero / Android placeholder. */
internal fun normalizeMacAddress(text: String?): String? {
    val found = text?.let { MAC_ADDRESS_REGEX.find(it)?.value } ?: return null
    val mac = found.replace('-', ':').uppercase()
    return mac.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
}
