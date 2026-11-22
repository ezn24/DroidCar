package io.github.ezn24.cardeck.neo.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
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
 * iAP2 Bluetooth RFCOMM listener on the phone side (iPhone role).
 *
 * In real wireless CarPlay the iPhone listens on this SDP service after pairing and waits for the head unit to connect back;
 * on the test bench DiPlay/xcertplay scan paired PHONE_SMART devices and connect in actively.
 *
 * After connection: link negotiation (marker/sync/ACK) -> full sender bring-up (Phase 1),
 * and on success calls [onEndpoint] to hand over the hotspot SSID/password and the AirPlay endpoint.
 */
class Iap2BluetoothServer(
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
    /** Target head unit (read dynamically: connections from non-target devices are rejected; null = accept anyone). */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
) {
    companion object {
        /**
         * iPhone iAP2 service UUID (published by Oligo Security; same as xcertplay
         * CarPlayController.IAP2_IPHONE_UUID). The accessory side is ...decacaff.
         */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val SDP_NAME = "iAP2"

        /** Service name the reference sender / a real iPhone publish for the smartBox. */
        private const val ADAPTER_SDP_NAME = "Wireless iAP"
        private const val SESSION_TIMEOUT_MILLIS = 60_000L
        private val CHANNEL_REGEX = Regex("Channel:\\s*(-?\\d+)")
    }

    private var serverSocket: AtomicReference<BluetoothServerSocket?> = AtomicReference(null)

    /** SDP service name of the current listener. */
    @Volatile
    private var publishedName: String? = null

    /**
     * Set once an adapter session has handed over Wi-Fi credentials. The smartBox re-dials RFCOMM
     * afterwards and repeats detect markers; the reference sender never answered them (CarPlay ran
     * fine), so later connections are held open silently instead of starting a second bootstrap.
     * Cleared by [stop] and [resetAdapterHandoff].
     */
    @Volatile
    private var adapterHandedOff = false

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        val wantedName = if (AdapterMode.active) ADAPTER_SDP_NAME else SDP_NAME
        if (running) {
            if (wantedName == publishedName || activeChannel != null) return true
            // Adapter mode was decided after the record went out under the other name
            log("ADAPTER: re-publishing the phone iAP2 service as \"$wantedName\" (was \"$publishedName\")")
            running = false
            serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        }
        if (!hasPermission()) {
            log("BT: missing BLUETOOTH_CONNECT permission")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: Bluetooth unavailable or off")
            return false
        }
        return try {
            val listener = adapter.listenUsingRfcommWithServiceRecord(wantedName, IPHONE_IAP2_UUID)
            serverSocket.set(listener)
            publishedName = wantedName
            running = true
            // toString() looks like "ServerSocket: Type: TYPE_RFCOMM Channel: N": the reference implementation
            // uses channel 4 on the smartBox, and the channel number helps when debugging "head unit does not reconnect".
            log("BT: listening on $wantedName ($listener), waiting for the receiver to connect back...")
            if (AdapterMode.active) {
                // Android picks the RFCOMM channel. Forcing 4 (the reference's) would need an unbound
                // BluetoothServerSocket(type, auth, encrypt, ParcelUuid) or BluetoothSocket.bindListen(),
                // both blocked non-SDK APIs on Android 14; the box reads the channel from SDP anyway.
                val channel = CHANNEL_REGEX.find(listener.toString())?.groupValues?.get(1) ?: "?"
                log(
                    "ADAPTER: published \"$wantedName\" (...decacafe) on RFCOMM channel $channel " +
                        "(system-assigned; the reference used 4, forcing it is not possible without blocked hidden APIs)",
                )
            }
            Thread({ acceptLoop(listener) }, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            log("BT: listen failed - ${error.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(listener: BluetoothServerSocket) {
        while (running && serverSocket.get() === listener) {
            val socket: BluetoothSocket =
                try {
                    listener.accept() ?: break
                } catch (error: IOException) {
                    if (running && serverSocket.get() === listener) log("BT: accept failed - ${error.message}")
                    break
                }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // When a target head unit is set, reject connections from other devices (the user paired several head units: the
            // controllers of non-target
            // head units also reconnect periodically, and letting them in would hijack/wrongly build a session). Address comparison
            // is case-insensitive.
            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            if (target != null && !TargetDevicePrefs.matches(target, peerAddress)) {
                log(
                    "BT: ignoring connection from non-target device name=$peerName addr=${peerAddress ?: "?"}" +
                        " (current target: \"${target.name}\")",
                )
                runCatching { socket.close() }
                continue
            }
            if (AdapterMode.active && adapterHandedOff) {
                log(
                    "ADAPTER: adapter re-dialed RFCOMM after the Wi-Fi handoff (name=$peerName) - holding it open without " +
                        "answering its markers (reference behaviour; no second bootstrap)",
                )
                Thread({ holdSilently(socket) }, "iap2-bt-hold").apply {
                    isDaemon = true
                    start()
                }
                continue
            }
            if (!AdapterMode.active && AdapterMode.looksLikeAdapter(peerName)) {
                // When the target is "auto", startConnection cannot recognize an adapter box: a box dialing in confirms it,
                // and the flag must be set before Iap2LinkChannel is built (responder mode of the link is read at construction).
                // The box has no ...caff service, so the SDP gate of the outbound client never touches its profiles anyway.
                AdapterMode.active = true
                log("ADAPTER: incoming device \"$peerName\" is a wireless CarPlay adapter box; this connection uses the adapter flow")
            }
            log("BT: receiver connected name=$peerName, starting iAP2 sender session")
            BtSessionGate.lastPeerAddress = peerAddress
            Thread({ runSession(socket) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt", carLink = true) { line -> log(line) }
        activeChannel = channel
        BtSessionGate.inboundActive = true
        val credentialsHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
            if (channel.adapterMode) {
                { wifi ->
                    adapterHandedOff = true
                    onWifiCredentials(wifi)
                }
            } else {
                onWifiCredentials
            }
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint,
                credentialsHook,
                onEndpointUpdate,
                onLinkClosed,
                knownCarSsid,
                onGeneration,
            )
        } catch (error: IOException) {
            log("SESSION failed: ${error.message}")
        } finally {
            activeChannel = null
            BtSessionGate.inboundActive = false
            channel.close()
            log("SESSION ended, waiting for the receiver to reconnect (BT listener still running)")
        }
    }

    /**
     * Actively end the current BT control session (the listener is unaffected). Used to reproduce the retired control channel state
     * before AirPlay pairing.
     */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT: current control session actively disconnected (simulating retirement after tunnel handoff)")
        }
        closeHeldSockets()
    }

    /** Forget an earlier adapter Wi-Fi handoff so the next RFCOMM connection gets a full bootstrap. */
    fun resetAdapterHandoff() {
        adapterHandedOff = false
        closeHeldSockets()
    }

    private val heldSockets = java.util.Collections.synchronizedSet(HashSet<BluetoothSocket>())

    private fun holdSilently(socket: BluetoothSocket) {
        heldSockets += socket
        var bytes = 0L
        try {
            val input = socket.inputStream
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
            }
        } catch (_: IOException) {
            // closed by either side
        } finally {
            heldSockets -= socket
            runCatching { socket.close() }
            log("ADAPTER: held post-handoff RFCOMM connection closed ($bytes bytes ignored)")
        }
    }

    private fun closeHeldSockets() {
        val sockets = synchronized(heldSockets) { heldSockets.toList() }
        sockets.forEach { runCatching { it.close() } }
    }

    fun stop() {
        running = false
        adapterHandedOff = false
        closeHeldSockets()
        serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        publishedName = null
        log("BT: listener stopped")
    }
}
