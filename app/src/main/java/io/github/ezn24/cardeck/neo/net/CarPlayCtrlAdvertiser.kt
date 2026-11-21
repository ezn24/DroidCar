package io.github.ezn24.cardeck.neo.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import io.github.ezn24.cardeck.neo.bt.AdapterMode
import io.github.ezn24.cardeck.neo.util.Bg
import io.github.ezn24.cardeck.neo.util.DeviceIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Advertises the phone-side (iPhone role) _carplay-ctrl._tcp control service and answers receiver probes.
 *
 * In wireless CarPlay the receiver connects back to this port after discovering the service, first sending
 * `GET /ctrl-int/1/connect` (carrying AirPlay-Receiver-Device-ID) to probe whether the phone is online,
 * and expects HTTP 200 (follows xcertplay CarPlayBonjour.probeOnce: only the status line is read).
 * After the probe passes, the iAP2 control session is taken over through the type-130 tunnel on the AirPlay event channel (Phase 2
 * follow-up).
 *
 * This class also logs the request line of every connection, so whatever DiPlay sends next is directly visible.
 */
class CarPlayCtrlAdvertiser(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"

        /**
         * Version the sender self-reports in the ctrl TXT.
         *
         * **Must stay 950.7.1**: ruled on a real Audi MMI 3315, 2026-10-19: after changing to catplay's
         * 566.25.21 (sender SDK version) the head unit **stopped sending even the ctrl-int probe** and 0x4301 never arrived;
         * with 950.7.1 the same car sent 0x4301 within 0.65s. The head unit only sees this TXT over WiFi,
         * and evidently judges the peer's kind by "versions common to receivers/system broadcasts".
         */
        private const val SENDER_SRCVERS = "950.7.1"

        /**
         * ctrl service instance name. Likewise keep the 0.1.6 `sender`: renaming to `iPhone` took effect together with the previous change,
         * so a single log could not attribute the cause; both were reverted to the combination verified to work (2026-10-19).
         */
        private const val SERVICE_NAME = "sender"

        /**
         * Visible to tests: these two values were verified on a real car, so changes need a retest first (see
         * CarPlayCtrlAdvertiserTest).
         */
        internal fun advertisedSrcversForTest(): String = SENDER_SRCVERS

        internal fun advertisedServiceNameForTest(): String = SERVICE_NAME

        /** Visible to tests: the full set of service types this class really advertises (only ctrl now; _device-info was removed). */
        internal fun advertisedServiceTypesForTest(): List<String> = listOf(SERVICE_TYPE)

        private const val CLOSE_RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

        /**
         * Reply to one ctrl request. Non-adapter: the Audi-verified `Connection: close` 200, unchanged.
         * Adapter mode, `/ctrl-int/`: keep-alive 200 echoing the request's protocol token and CSeq, as the
         * reference responder the smartBox accepted (unapple-phone `carplay_ctrl::connect_response`).
         */
        internal fun ctrlResponse(
            head: String,
            adapterMode: Boolean,
        ): String {
            val requestLine = head.lineSequence().firstOrNull().orEmpty()
            if (!adapterMode || !requestLine.startsWith("GET /ctrl-int/")) return CLOSE_RESPONSE
            val proto =
                requestLine
                    .trim()
                    .split(' ')
                    .getOrNull(2)
                    ?.takeIf { it.isNotBlank() } ?: "HTTP/1.1"
            val cseq =
                head
                    .lineSequence()
                    .firstOrNull { it.substringBefore(':').trim().equals("CSeq", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
            return buildString {
                append("$proto 200 OK\r\nContent-Length: 0\r\n")
                if (!cseq.isNullOrEmpty()) append("CSeq: $cseq\r\n")
                append("\r\n")
            }
        }

        private const val TAG = "CTRL"
        private const val MAX_HEAD_BYTES = 16 * 1024

        /** Cooldown for a forced re-registration on the same SSID (see ensureForNetwork). */
        private const val FORCE_COOLDOWN_MILLIS = 2_000L
    }

    private var serverSocket: ServerSocket? = null
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Whether the accept thread continues (only [stop] clears it; re-registration does not touch it, so port and thread persist). */
    @Volatile
    private var acceptRunning = false

    @Volatile
    private var registered = false

    /**
     * Head unit ctrl invitation counter (incremented on every `GET /ctrl-int/1/connect`).
     *
     * The connect trigger of the catplay sender is exactly this invitation: it means "the head unit has discovered us and opened
     * its AirPlay window", and AirPlay is connected right after receiving it with no fixed sleep. The controller uses it in place of
     * the earlier "sleep 1.6s after endpoint handoff" timing guess.
     */
    @Volatile
    var inviteCount: Int = 0
        private set

    /** Cleared when a new connection round starts (so a previous round's invitation is not taken as this round's signal). */
    fun resetInviteCount() {
        inviteCount = 0
    }

    /**
     * Adapter mode: source address of the box's latest `GET /ctrl-int/1/connect` (= the box; the
     * reference sender connects to `invite.peer:7000`). A host fallback when mDNS can't resolve it.
     */
    @Volatile
    var lastInvitePeer: String? = null
        private set

    /** [SystemClock.uptimeMillis] of [lastInvitePeer]'s invite (0 = none yet). */
    @Volatile
    var lastInviteAtMillis: Long = 0L
        private set

    /** Adapter mode only: called (on a background thread) after each connect invite is answered. */
    @Volatile
    var onInvite: ((peer: String?) -> Unit)? = null

    /**
     * WiFi SSID at registration time: after a network change (2.4G -> 5G or a head unit hotspot switch) the old registration is
     * invisible and must be redone.
     */
    private var registeredSsid: String? = null

    /** Currently active registration listener (a fresh instance on every start, avoiding already-in-use). */
    private var activeListener: NsdManager.RegistrationListener? = null

    /** Time of this registration (for the force cooldown check). */
    @Volatile
    private var registeredAtMillis: Long = 0L

    /** MAC written into the TXT at this registration (placeholder = the head unit will not invite us, re-register soon). */
    @Volatile
    private var registeredWithMac: String? = null

    /** Network the process was on at this registration (to decide "whether to re-register after binding"). */
    private var registeredNetwork: android.net.Network? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    /**
     * Whether the TXT id currently advertised is a placeholder MAC (the head unit matches phones by it; a placeholder is never
     * invited).
     */
    val macPlaceholder: Boolean
        get() = registeredWithMac == DeviceIdentity.PLACEHOLDER_MAC

    /**
     * Ensure the advertisement is visible on the network of the given SSID (called whenever a session obtains 0x5703).
     * Real car measured (2026-10-11): after the phone moved from a 2.4G to a 5G hotspot, the old mDNS registration
     * was no longer seen by the head unit (no more ctrl-int probes -> AirPlay window not opened).
     * On an SSID change, unregister then register again so the advertisement lands on the current network.
     *
     * @param force true = re-register unconditionally (timing fix "after binding the process network":
     *   mDNS multicast follows the network of bindProcessToNetwork, so registration must happen after binding).
     * @param network the bound process network; if it differs from the one at the last registration we must re-register (cooldown
     * does not apply),
     * otherwise the 2s cooldown would swallow the crucial post-binding re-registration when "switching to the car hotspot right
     * after registering".
     */
    @Synchronized
    fun ensureForNetwork(
        ssid: String,
        force: Boolean = false,
        network: android.net.Network? = null,
    ): Boolean {
        if (registered && registeredSsid == ssid && !force && !macPlaceholder) return true
        // Force cooldown: the series of "ensure" calls after binding (bindCarWifiAndRegister -> endpoint handoff)
        // often happen within the same second, and an unregister+register each time hits NsdManager's
        // listener-already-in-use and eats a 1.2s retry, which slows the connection instead.
        // Exceptions: a placeholder MAC in the advertisement, or a changed process network, must not be skipped.
        val sameNetwork = network == null || network == registeredNetwork
        if (force &&
            registered &&
            registeredSsid == ssid &&
            !macPlaceholder &&
            sameNetwork &&
            SystemClock.uptimeMillis() - registeredAtMillis < FORCE_COOLDOWN_MILLIS
        ) {
            log("$TAG: skipping duplicate forced re-registration (registered ${SystemClock.uptimeMillis() - registeredAtMillis}ms ago)")
            return true
        }
        if (registered) {
            log(
                "$TAG: re-registering mDNS advertisement " +
                    "(${registeredSsid ?: "?"}${if (force) ", post-binding timing fix" else " -> $ssid"})",
            )
            // Unregister the service only and **keep the listening port**: Android NSD has no catplay/JmDNS
            // reannounce(), so re-announcing means unregister+register; if the port changed too, a head unit probing the cached port
            // would hit a closed socket (xcertplay as head unit probes 7 times x 1.5s per session,
            // and a mid-way port change voids the whole round). In the Audi log of 2026-10-22 the port jumped over 6 re-registrations
            // (43857->33383->33125->38151->40697->38015), each with a service gap of up to 1.2s.
            unregisterServiceOnly()
        }
        registeredSsid = ssid
        registeredNetwork = network ?: registeredNetwork
        var ok = start()
        if (!ok) {
            // Unregistering is asynchronous: in extreme cases registerService hits "listener already in use",
            // so retry once after a short wait (real car measured 10-11: 300ms is not enough, a longer wait is needed)
            try {
                Thread.sleep(1_200)
            } catch (_: InterruptedException) {
            }
            ok = start()
        }
        return ok
    }

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        return try {
            // The port is fixed for the whole advertiser lifetime: re-registration only swaps the mDNS registration and reuses the same
            // socket and accept thread (see the ensureForNetwork comment).
            val socket =
                serverSocket ?: ServerSocket(0).also { created ->
                    serverSocket = created
                    acceptRunning = true
                    Thread(::acceptLoop, "ctrl-accept").apply {
                        isDaemon = true
                        start()
                    }
                }
            // With a placeholder MAC, force one retry (when the advertisement is published as soon as the connection starts,
            // Shizuku may not be ready;
            // a wrong advertisement means the head unit never invites us)
            var mac = DeviceIdentity.bluetoothMac(context)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
                val retryResult =
                    if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                        "still failed (the head unit may not invite us; make sure Shizuku is running)"
                    } else {
                        "succeeded"
                    }
                log("$TAG: Bluetooth MAC resolved to the placeholder, forced retry $retryResult")
            }
            val info =
                NsdServiceInfo().apply {
                    serviceName = SERVICE_NAME
                    serviceType = SERVICE_TYPE
                    setPort(socket.localPort)
                    // TXT id = the real Bluetooth MAC of this phone (confirmed by catplay CarPlayInviter: the head unit matches
                    // the phone to invite by it, and a placeholder makes the head unit never invite -> 0x4301 never arrives).
                    setAttribute("id", mac)
                    setAttribute("srcvers", SENDER_SRCVERS)
                }
            // Use a brand-new listener for every registration: unregisterService is asynchronous, and reusing one listener
            // hits "listener already in use" (real car measured 10-11: it blocked the ctrl advertisement)
            val freshListener = createListener()
            activeListener = freshListener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, freshListener)
            registered = true
            registeredAtMillis = SystemClock.uptimeMillis()
            registeredWithMac = mac
            // The only phone identity the head unit sees over WiFi is these TXT fields: a placeholder MAC / wrong version both
            // directly cause "no ctrl probe, 0x4301 never arrives", and earlier logs showed none of them
            // (could not attribute the cause in the Audi 0.1.7 investigation of 2026-10-19), so one line is logged on every registration.
            log(
                "$TAG: listening on :${socket.localPort}, advertising $SERVICE_TYPE " +
                    "name=$SERVICE_NAME id=$mac srcvers=$SENDER_SRCVERS" +
                    if (mac == DeviceIdentity.PLACEHOLDER_MAC) " (WARNING placeholder MAC: the head unit will not invite)" else "",
            )
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                // A head unit never invites with a placeholder id: wait in the background for Shizuku/MAC to be ready, then
                // re-register at once,
                // so that "advertising as early as connection start" does not end up writing a wrong TXT id (2026-10-18).
                Bg.run("ctrl-mac-repair") { repairPlaceholderMac() }
            }
            true
        } catch (error: Exception) {
            log("$TAG: start failed - ${error.message}")
            false
        }
    }

    /** Self-repair of a placeholder MAC: retry resolving every 2s and re-register once for the current SSID on success. */
    private fun repairPlaceholderMac() {
        repeat(15) {
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return
            }
            if (!registered || !macPlaceholder) return
            val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) return@repeat
            log("$TAG: Bluetooth MAC resolved to $mac; re-registering the ctrl advertisement (fixing the placeholder id)")
            ensureForNetwork(registeredSsid ?: "", force = true)
            return
        }
        log(
            "$TAG: could not resolve the real Bluetooth MAC within 30s; the ctrl advertisement id is still the placeholder and the " +
                "head unit may not invite (make sure Shizuku is running)",
        )
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (acceptRunning) {
            val client =
                try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
            Bg.run("ctrl-client") { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readUntilHeaderEnd(input)
            if (head.isEmpty()) {
                log("$TAG: $peer connected but sent no request (EOF)")
                return
            }
            val requestLine = head.lineSequence().firstOrNull() ?: ""
            val headers =
                head
                    .substringAfter("\r\n", "")
                    .lineSequence()
                    .filter { it.contains(':') }
                    .joinToString("; ") { it.trim() }
            log("$TAG: $peer -> [$requestLine] $headers")

            // Head unit ctrl invitation: the authoritative signal that "the head unit has discovered the phone and the AirPlay
            // window is open"
            // (catplay CarPlayCtrlSession: the invitation is handed to the connect flow on receipt).
            if (requestLine.startsWith("GET /ctrl-int/1/connect")) {
                val receiverId =
                    head
                        .lineSequence()
                        .firstOrNull {
                            it.substringBefore(':').trim().equals("AirPlay-Receiver-Device-ID", ignoreCase = true)
                        }?.substringAfter(':')
                        ?.trim()
                inviteCount++
                log("$TAG: OK head unit ctrl invitation (#$inviteCount, receiver=${receiverId ?: "?"}); AirPlay window opened")
            }

            val output = client.getOutputStream()
            val adapter = AdapterMode.active
            if (adapter && requestLine.startsWith("GET /ctrl-int/1/connect")) {
                lastInvitePeer = client.inetAddress?.hostAddress
                lastInviteAtMillis = SystemClock.uptimeMillis()
            }
            val response = ctrlResponse(head, adapter)
            if (requestLine.startsWith("GET /ctrl-int/")) {
                log("$TAG: OK answered ctrl-int probe 200 OK${if (adapter) " (keep-alive, adapter mode)" else ""}")
            } else {
                log("$TAG: unrecognized request, answered with an empty 200 (to be refined later as needed)")
            }
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()
            if (adapter && requestLine.startsWith("GET /ctrl-int/1/connect")) {
                // The box re-invites every ~17 s until a session is live (unapple-phone run 20261026T192344Z);
                // the hook lets the session layer react. Duplicate-session guarding is the hook's job.
                onInvite?.let { hook ->
                    val peer = lastInvitePeer
                    Bg.run("ctrl-invite-hook") { hook(peer) }
                }
            }

            // A peer Connection: close disconnects by itself; if more bytes arrive while waiting (tunnel/event), dump them
            client.soTimeout = 3_000
            val rest = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    rest.write(buffer, 0, count)
                    if (rest.size() > 64 * 1024) break
                }
            } catch (_: IOException) {
                // Stop dumping on read timeout
            }
            if (rest.size() > 0) {
                log(
                    "$TAG: received ${rest.size()} bytes after replying: " +
                        rest.toByteArray().take(64).joinToString(" ") {
                            "%02x".format(it.toInt() and 0xFF)
                        },
                )
            }
            log("$TAG: $peer connection ended")
        } catch (error: IOException) {
            log("$TAG: $peer connection error - ${error.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (buffer.size() < MAX_HEAD_BYTES) {
            val count =
                try {
                    input.read(chunk)
                } catch (_: IOException) {
                    break
                }
            if (count < 0) break
            buffer.write(chunk, 0, count)
            val text = buffer.toString("ISO-8859-1")
            if (text.contains("\r\n\r\n")) return text
        }
        return buffer.toString("ISO-8859-1")
    }

    /** Unregister only the mDNS registration, keep the listening socket and accept thread (for re-registration, port unchanged). */
    @Synchronized
    private fun unregisterServiceOnly() {
        registered = false
        registeredWithMac = null
        activeListener?.let { runCatching { nsdManager.unregisterService(it) } }
        activeListener = null
    }

    @Synchronized
    fun stop() {
        unregisterServiceOnly()
        registeredSsid = null
        registeredNetwork = null
        acceptRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        log("$TAG: stopped")
    }

    /** Create a new listener per registration (see the start() comment; reusing one instance triggers already in use). */
    private fun createListener(): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                log("$TAG: mDNS registration succeeded ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {
                // Reset state: the next ensureForNetwork (0x5703 of a new session) will register again
                registered = false
                log("$TAG: mDNS registration failed code=$errorCode (will retry automatically next session)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) = Unit
        }
}
