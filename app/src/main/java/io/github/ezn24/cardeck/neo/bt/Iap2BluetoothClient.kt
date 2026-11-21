package io.github.ezn24.cardeck.neo.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.ezn24.cardeck.neo.iap2.Iap2SenderSession
import io.github.ezn24.cardeck.neo.iap2.ReceiverProfile
import io.github.ezn24.cardeck.neo.transport.Iap2LinkChannel
import io.github.ezn24.cardeck.neo.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Wireless iAP2 RFCOMM client where the phone (iPhone role) **actively connects to the head unit**; added in the real-car stage.
 *
 * Real wireless CarPlay link direction (follows carplay-wifi-extractor client mode, verified on a real car):
 * after the phone and head unit complete system-level Bluetooth pairing, the **phone connects in** to the accessory-side service
 * advertised in the head unit SDP (UUID 00000000-deca-fade-deca-deafdecacaff, measured present in the Audi MIB3 SDP);
 * the head unit never connects back to the phone (the DiPlay test bench does, but that is receiver test behavior).
 *
 * Once connected it reuses exactly the same sender bring-up as the test bench ([Iap2SenderSession]),
 * and on success calls [onEndpoint] to hand over the hotspot credentials and the AirPlay endpoint.
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** Fires as soon as 0x5703 arrives (bind WiFi + advertise _carplay-ctrl), the precondition of 0x4301 on real cars. */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Hands the endpoint to the controller for a hot update when the head unit sends 0x4301 after Phase 1 (CarPlay window opened late). */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /**
     * Notifies the controller when the sender link drops (keep-alive pump exited) so it releases the zombie round (see
     * SessionController.onSenderLinkClosed).
     */
    private val onLinkClosed: () -> Unit = {},
    /** Car hotspot SSID known to this process (reused when this round has no new credentials, avoids an 8s link teardown every round). */
    private val knownCarSsid: () -> String? = { null },
    /** Link generation decided from the 0x1D01 profile (called back after Phase 1, used for dispatch in the AirPlay stage). */
    private val onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
    /** Target head unit (read dynamically: no link restart needed after selection, it takes effect on the next attempt). */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
) {
    companion object {
        /**
         * Accessory-side (head unit) wireless iAP2 service UUID.
         * The phone-side UUID is ...cafe (xcertplay IAP2_IPHONE_UUID), the accessory side is ...caff.
         */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L

        /** Throttle for the "Bluetooth connected -> disconnected" log (the head unit may auto-reconnect repeatedly, avoids log spam). */
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /** Wait window for the SDP service probe (fetchUuidsWithSdp -> ACTION_UUID broadcast). */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)

    /**
     * Client thread generation: both start() and stop() increment it. A thread stuck in a blocking connect()
     * that stop() unblocks (by closing the connecting socket) exits at once on generation mismatch,
     * preventing "a thread survives stop and the old and new generations both connect to the head unit on the next start"
     * (measured 2026-10-11: several zombie threads doing RFCOMM concurrently make the head unit tear the link down repeatedly).
     */
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    /** Throttle for the "target device not in the paired list" notice (avoids log spam every 3 seconds). */
    private var lastTargetMissingLogAt = 0L

    /** Dedup for the "no iAP2 service -> skip" log (once per address is enough, repeated polling does not spam). */
    private val skippedNoService = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Cache of the "provides an iAP2 service" verdict (address -> verdict): SDP queries are not cheap, do not redo every 3s. */
    private val iap2ServiceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Whether the device provides the accessory-side iAP2 service (...caff): read the system uuids cache first, and if missing
     * trigger one SDP query via `fetchUuidsWithSdp` and wait for the `ACTION_UUID` broadcast (timeout counts as no service).
     * The verdict is cached until the client stops; a head unit firmware upgrade changing the UUID is covered by restarting the app.
     * Permission: start() already checked BLUETOOTH_CONNECT; a SecurityException from uuids/fetchUuidsWithSdp
     * is absorbed by runCatching (a failed probe counts as no service, so audio is not cut).
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Boolean {
        iap2ServiceCache[device.address]?.let { return it }

        fun cachedHas(): Boolean =
            runCatching {
                device.uuids?.any { it == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        if (cachedHas()) {
            iap2ServiceCache[device.address] = true
            return true
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: android.content.Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (hit?.address == device.address) latch.countDown()
                }
            }
        val started =
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(BluetoothDevice.ACTION_UUID),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                device.fetchUuidsWithSdp()
            }.getOrDefault(false)
        if (started) {
            try {
                latch.await(SDP_PROBE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        runCatching { context.unregisterReceiver(receiver) }
        val has = cachedHas()
        iap2ServiceCache[device.address] = has
        return has
    }

    /** Timestamp for the "Bluetooth connected -> disconnected" log throttle. */
    private var lastProfileLogAt = 0L

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: missing BLUETOOTH_CONNECT permission")
            return false
        }
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: Bluetooth unavailable or off")
            return false
        }
        // Reset the SDP verdicts and skip logs every round (re-probe after a new pairing / firmware change)
        iap2ServiceCache.clear()
        skippedNoService.clear()
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log(
            "BT-C: started; will connect to the head unit wireless iAP2 service (...caff)" +
                if (target != null) {
                    ", target: \"${target.name}\" ${target.address} (only this device)"
                } else {
                    " (no target set: trying all candidate devices in turn, one can be chosen in Connection Setup)"
                },
        )
        Thread({ connectLoop(myGeneration) }, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop(myGeneration: Long) {
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            } ?: return
        var lastFailureLogAt = 0L
        while (running && generation.get() == myGeneration) {
            // Quiet mode (enabled after the 2026-10-10 user test): fully silent during a session, no connection attempts/logs at all:
            //  - inboundActive: a receiver is connecting to us (two RFCOMM links step on each other; Audi measured a messy double session)
            //  - sessionActive: a CarPlay session is established (endpoint obtained); all connection attempts to other receivers pause.
            //    Typical case: while connected to Redmi/DiPlay we no longer blindly probe the Audi (removes log noise and Bluetooth churn);
            //    resumes automatically when the session ends (either BT session ends / manual disconnect).
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                sleepQuietly(2_000)
                continue
            }
            val candidates = candidateDevices(adapter)
            if (candidates.isEmpty()) {
                log("BT-C: no paired Bluetooth devices; pair with the head unit in system settings first")
                sleepQuietly(RETRY_DELAY_MILLIS * 3)
                continue
            }
            var connected: BluetoothSocket? = null
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                val name =
                    try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    } ?: device.address
                runCatching { adapter.cancelDiscovery() }
                // Auto mode (no target selected) goes through the SDP gate first: candidates = all paired devices, and headsets/watches
                // have no iAP2 service, so connecting fails and their HFP/A2DP would be cut for nothing (the device-level
                // disconnect fallback
                // would even hit other online devices); only after confirming ...caff do we enter the "disconnect + connect" flow. A
                // user-selected target skips the gate (explicitly chosen, keep the original path and timing).
                if (targetProvider() == null && !awaitIap2Service(device)) {
                    if (skippedNoService.add(device.address)) {
                        log("BT-C: skipping \"$name\" (no iAP2 service found; not a CarPlay device, leaving its audio connection alone)")
                    }
                    continue
                }
                // "Pair only, do not connect": Audi MIB3 measured that while the phone and head unit are connected over Bluetooth
                // (HFP/A2DP online) the head unit does not open its wireless CarPlay iAP2 service window,
                // which shows up as a connect failure every 3s. iAP2 is an independent direct SDP service unrelated to these
                // profiles, and disconnecting them does not affect CarPlay itself (audio goes over AirPlay on WiFi).
                run {
                    val dropped = BtProfiles.disconnect(context, device)
                    if (dropped.isNotEmpty()) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                            lastProfileLogAt = now
                            val left = BtProfiles.stillConnected(context, device.address)
                            log(
                                "BT-C: $name is Bluetooth-connected (${dropped.joinToString("/")}); disconnected" +
                                    if (left.isEmpty()) "" else " (${left.joinToString("/")} still online)",
                            )
                            log("BT-C: head unit does not accept CarPlay while Bluetooth-connected; keeping pair-only")
                        }
                    }
                }
                val socket = tryConnect(device)
                if (socket != null) {
                    log("BT-C: connected to the wireless iAP2 service of $name (RFCOMM established)")
                    connected = socket
                    break
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        log("BT-C: connecting to $name failed (head unit not ready or not in wireless pairing window), retry in 3s...")
                    }
                }
            }
            if (connected == null) {
                sleepQuietly(RETRY_DELAY_MILLIS)
                continue
            }
            if (generation.get() != myGeneration) {
                // stop() raced with a successful connect(): this thread is stale, do not build a session
                currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
                return
            }
            runSession(connected)
            sleepQuietly(RETRY_DELAY_MILLIS)
        }
        log("BT-C: client stopped")
    }

    /**
     * Candidate devices: paired devices whose SDP cache has the accessory UUID first;
     * when the cache is missing, fall back to all paired devices (the SDP query at connect time filters on its own).
     * When a target head unit is set **only that device is returned** (avoids a wrong connection when the user paired several head units).
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        targetProvider()?.let { target ->
            // Target policy: an address match is the only candidate and is not filtered by the SDP cache (the cache may be missing,
            // and an explicit user choice should be tried); when the target is not in the paired list, notify once and idle-wait.
            val hit = bonded.filter { TargetDevicePrefs.matches(target, it.address) }
            if (hit.isEmpty()) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTargetMissingLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                    lastTargetMissingLogAt = now
                    log(
                        "BT-C: target device \"${target.name}\" (${target.address}) is not in the paired list" +
                            "; pair it in system Bluetooth settings, or pick another target in Connection Setup",
                    )
                }
                return emptyList()
            }
            return hit
        }
        val withService =
            bonded.filter { device ->
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }
        return if (withService.isNotEmpty()) withService else bonded
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (error: IOException) {
                return null
            }
        // Register before connecting: connect() is blocking (SDP query + link setup can take tens of seconds),
        // and stop() can only interrupt it with close() if it has the socket reference, otherwise the thread lingers.
        currentSocket.set(socket)
        return try {
            socket.connect()
            socket
        } catch (error: IOException) {
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            null
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt-car", adapterMode = false, carLink = true) { line -> log(line) }
        activeChannel = channel
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint,
                onWifiCredentials,
                onEndpointUpdate,
                onLinkClosed,
                knownCarSsid,
                onGeneration,
            )
        } catch (error: IOException) {
            log("BT-C: SESSION failed - ${error.message}")
        } finally {
            activeChannel = null
            currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
            runCatching { socket.close() }
            channel.close()
            log("BT-C: session round ended (client still running, will reconnect automatically)")
        }
    }

    /**
     * Actively end the current BT control session (the reconnect loop is unaffected). Used to reproduce the retired control channel
     * state before AirPlay pairing.
     */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT-C: current control session actively disconnected")
        }
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    fun stop() {
        running = false
        // Increment the generation: even if a thread is stuck in connect() it will later kill itself on generation mismatch and not
        // revive on the next start
        generation.incrementAndGet()
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
        activeChannel?.let { runCatching { it.close() } }
        thread?.interrupt()
        thread = null
        log("BT-C: stopped")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }
}

/**
 * Inbound session gate: while an iAP2 session initiated by the head unit is active, the outbound client pauses connection attempts.
 * Reason: a head unit usually has a single wireless iAP2 context, and links opened in both directions interfere with each other.
 */
object BtSessionGate {
    /** An inbound (head unit initiated) iAP2 session is active: outbound pauses so two RFCOMM links do not step on each other. */
    @Volatile
    var inboundActive: Boolean = false

    /**
     * A CarPlay session is established (set after any path obtains an endpoint, maintained by SessionController):
     * suspends all outbound connection attempts (quiet mode); typically it stops probing the Audi while connected to DiPlay.
     * Cleared by SessionController when the session ends / on manual disconnect.
     */
    @Volatile
    var sessionActive: Boolean = false
}
