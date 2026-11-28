/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.iccoa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.ezn24.droidcar.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One independently owned ICCOA connection: GATT bootstrap, AP join, PIN SignIn and UCAR lanes.
 * Current AP credentials and agreement keys are never persisted. If owner chooses
 * automatic remembered-car reconnect, only the verified OEM car public key is kept
 * encrypted under a non-exportable AndroidKeyStore key; never logged.
 * Status/PIN/closed/ready callbacks run on main; messages run on network workers.
 * [onReady] must synchronously open media listeners before returning (before AuthConfirm).
 */
@SuppressLint("MissingPermission")
class IccoaSession(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPinRequired: () -> Unit,
    private val onReady: (IccoaLink, String) -> Unit,
    private val onMessage: (Int, Int, ByteArray) -> Unit,
    private val onClosed: (String, IccoaCloseReason) -> Unit,
    private val onControlReady: () -> Unit = {},
    private val trustedCarName: String = "",
    private val tryRememberedPin: Boolean = false,
    private val forceCurrentPin: Boolean = false,
    private val vehicleProfileId: String = IccoaProfile.ID,
) {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val trustedPin by lazy { IccoaTrustedPin(context, vehicleProfileId) }
    private val trustedCarKey by lazy { IccoaTrustedCarKey(context, vehicleProfileId) }

    @Volatile private var current: Attempt? = null
    val cipher: IccoaControl.Cipher? get() = current?.cipher

    private class Attempt(
        val profile: IccoaClientProfile,
    ) {
        val clientId get() = profile.id
        val resources = mutableListOf<Closeable>()
        val workers = Executors.newCachedThreadPool { Thread(it, "ICCOA-session") }
        val controlSender = sender("ICCOA-control-send", 32)
        val audioSender = sender("ICCOA-audio-send", 8)
        val sequence = AtomicInteger(1000)
        val serverInfo = ByteArrayOutputStream()

        @Volatile var closed = false

        @Volatile var cipher: IccoaControl.Cipher? = null

        @Volatile var control: Socket? = null

        @Volatile var audio: Socket? = null

        @Volatile var controlWritingAt = 0L

        @Volatile var audioWritingAt = 0L
        var firstHeartbeatWritten = false // Accessed only by the control sender.
        var gatt: BluetoothGatt? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var network: IccoaLink? = null
        val networkGate = IccoaNetworkCallbackGate<IccoaLink>()
        var servicesRequested = false
        var servicesHandled = false
        var pinReady = false
        var pin: String? = null
        var pinAttempt = 0
        var quickMode = false
        var pinnedCarIdentity: ByteArray? = null
        var pendingTrustedCarIdentity: ByteArray? = null
        val trustedCommitGate = IccoaTrustedCommitGate()
        var wifiRequested = false
        var authStarted = false
        var authenticatedNetwork: IccoaLink? = null // Main looper only; never reuse committed lanes.
        var hasWifiAddress = false
        var gatewayRetryScheduled = false

        companion object {
            private fun sender(
                name: String,
                capacity: Int,
            ) = ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(capacity),
                { task -> Thread(task, name) },
                ThreadPoolExecutor.AbortPolicy(),
            )
        }
    }

    @Synchronized fun start(address: String) {
        stop()
        val attempt = Attempt(IccoaClientProfile.load(context))
        current = attempt
        post(attempt) {
            try {
                status(attempt, context.getString(R.string.iccoa_msg_connecting_bluetooth))
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                require(adapter?.isEnabled == true) { "Bluetooth disabled" }
                synchronized(attempt) {
                    if (!active(attempt)) return@post
                    attempt.gatt = adapter.getRemoteDevice(address).connectGatt(
                        context,
                        false,
                        bluetoothCallback(attempt),
                        BluetoothDevice.TRANSPORT_LE,
                    ) ?: error("GATT unavailable")
                }
                later(attempt, 20_000) {
                    // pinReady becomes false again while a submitted PIN is in flight.
                    // The initial Bluetooth watchdog must stop once bootstrap is ready.
                    if (!attempt.servicesHandled &&
                        !attempt.wifiRequested
                    ) {
                        fail(
                            attempt,
                            context.getString(R.string.iccoa_msg_bluetooth_timeout),
                            closeReason = IccoaCloseReason.TRANSPORT_FAILURE,
                        )
                    }
                }
            } catch (
                e: Exception,
            ) {
                fail(
                    attempt,
                    context.getString(R.string.iccoa_msg_bluetooth_connect_failed, e.javaClass.simpleName),
                    closeReason = IccoaCloseReason.TRANSPORT_FAILURE,
                )
            }
        }
    }

    /** OEM Quick does not send a previous PIN over GATT. */
    private fun submitQuick(
        attempt: Attempt,
        pinned: ByteArray,
    ) = post(attempt) {
        if (!attempt.pinReady || attempt.wifiRequested || pinned.size != 64) return@post
        try {
            val gatt = attempt.gatt ?: error("Bluetooth unavailable")
            val characteristic =
                gatt.getService(SERVICE)?.getCharacteristic(CLIENT_INFO)
                    ?: error("ICCOA characteristic unavailable")
            val payload = attempt.profile.clientInfoQuick(Build.MODEL, IccoaClientProfile.channel(context))
            require(payload.size <= 512)
            attempt.pinReady = false
            attempt.quickMode = true
            attempt.pinnedCarIdentity = pinned.copyOf()
            val revision = ++attempt.pinAttempt
            attempt.serverInfo.reset()
            status(attempt, context.getString(R.string.iccoa_msg_quick_checking))
            val queued =
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            payload.fill(0)
            if (!queued) {
                fail(attempt, context.getString(R.string.iccoa_msg_quick_queue_error))
                return@post
            }
            later(attempt, 12_000) {
                if (!attempt.wifiRequested && revision == attempt.pinAttempt) {
                    // An absent BLE response is ambiguous, not a revocation of trust.
                    requestPin(attempt, context.getString(R.string.iccoa_msg_quick_not_confirmed))
                }
            }
        } catch (e: Exception) {
            fail(attempt, context.getString(R.string.iccoa_msg_quick_bluetooth_error, e.javaClass.simpleName))
        }
    }

    fun submitPin(pin: String) {
        val attempt = current ?: return
        post(attempt) {
            if (!pin.matches(Regex("[0-9]{6}"))) {
                status(attempt, context.getString(R.string.iccoa_msg_enter_code))
                onPinRequired()
                return@post
            }
            if (!attempt.pinReady || attempt.wifiRequested) return@post
            try {
                val gatt = attempt.gatt ?: error("GATT unavailable")
                val characteristic =
                    gatt.getService(SERVICE)?.getCharacteristic(CLIENT_INFO)
                        ?: error("Missing client characteristic")
                val payload = attempt.profile.clientInfo(pin, Build.MODEL, IccoaClientProfile.channel(context))
                require(payload.size <= 512) { "Client info too large" }
                attempt.pinReady = false
                attempt.pin = pin
                attempt.quickMode = false
                attempt.pinnedCarIdentity = null
                val revision = ++attempt.pinAttempt
                attempt.serverInfo.reset()
                status(attempt, context.getString(R.string.iccoa_msg_checking_code))
                val accepted =
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = payload
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    }
                payload.fill(0)
                if (!accepted) {
                    requestPin(attempt, context.getString(R.string.iccoa_msg_code_send_failed))
                    return@post
                }
                later(attempt, 12_000) {
                    if (!attempt.wifiRequested && revision == attempt.pinAttempt) {
                        // A silent BLE response is ambiguous: no automatic replay or key removal.
                        requestPin(attempt, context.getString(R.string.iccoa_msg_car_no_answer))
                    }
                }
            } catch (e: Exception) {
                fail(
                    attempt,
                    context.getString(R.string.iccoa_msg_code_send_error, e.javaClass.simpleName),
                    closeReason = IccoaFailurePolicy.channel(e),
                )
            }
        }
    }

    /** Explicit stop is silent; terminal transport failures invoke onClosed exactly once. */
    @Synchronized fun stop() {
        val attempt = current ?: return
        current = null
        close(attempt)
    }

    fun decryptInput(body: ByteArray): ByteArray = (cipher ?: error("ICCOA authentication is not complete")).decryptUibc(body)

    fun sendControl(
        method: Int,
        body: ByteArray,
    ): Boolean = current?.let { send(it, 1, method, body, 1) } ?: false

    fun sendAudio(
        method: Int,
        body: ByteArray,
    ): Boolean = current?.let { send(it, 4, method, body, 0) } ?: false

    private fun send(
        attempt: Attempt,
        category: Int,
        method: Int,
        body: ByteArray,
        format: Int,
    ): Boolean {
        if (!active(attempt)) return false
        val socket = (if (category == 4) attempt.audio else attempt.control) ?: return false
        val key = attempt.cipher ?: return false
        if (body.size > IccoaControl.MAX_FRAME_SIZE - 56) return false
        val copy = body.copyOf()
        try {
            (if (category == 4) attempt.audioSender else attempt.controlSender).execute {
                try {
                    if (!active(attempt)) return@execute
                    val frame = IccoaControl.frame(category, method, 0, key.encrypt(copy), attempt.sequence.getAndIncrement(), format)
                    if (category == 4) attempt.audioWritingAt = System.nanoTime() else attempt.controlWritingAt = System.nanoTime()
                    socket.getOutputStream().run {
                        write(frame)
                        flush()
                    }
                    if (category == 1 && method == 1 && !attempt.firstHeartbeatWritten) {
                        attempt.firstHeartbeatWritten = true
                        DiagLog.i("ICCOA", "CONTROL first-heartbeat written")
                    } else if (category == 1 && method != 1) {
                        DiagLog.i("ICCOA", "CONTROL written method=$method bytes=${copy.size}")
                    }
                } catch (e: Exception) {
                    if (active(attempt)) DiagLog.w("ICCOA", "SEND category=$category method=$method ${IccoaTransportError.describe(e)}")
                    fail(
                        attempt,
                        context.getString(R.string.iccoa_msg_channel_closed, category, e.javaClass.simpleName),
                        closeReason = IccoaFailurePolicy.channel(e),
                    )
                } finally {
                    copy.fill(0)
                    if (category == 4) attempt.audioWritingAt = 0 else attempt.controlWritingAt = 0
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            copy.fill(0)
            // Audio backpressure drops one packet; control messages must not disappear silently.
            if (category != 4 && active(attempt)) fail(attempt, context.getString(R.string.iccoa_msg_control_queue_full))
            return false
        }
    }

    private fun bluetoothCallback(attempt: Attempt) =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) = post(attempt) {
                if (gatt !== attempt.gatt) return@post
                DiagLog.i("ICCOA", "GATT connection status=$status state=$newState")
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.requestMtu(512)) discover(attempt, gatt)
                    later(attempt, 2_000) { discover(attempt, gatt) }
                } else if (!attempt.wifiRequested &&
                    (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)
                ) {
                    fail(
                        attempt,
                        context.getString(R.string.iccoa_msg_bluetooth_closed, status),
                        closeReason = IccoaCloseReason.TRANSPORT_FAILURE,
                    )
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) = post(attempt) {
                if (gatt !== attempt.gatt) return@post
                DiagLog.i("ICCOA", "GATT MTU=$mtu status=$status")
                discover(attempt, gatt)
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) = post(attempt) {
                if (gatt !== attempt.gatt || attempt.servicesHandled) return@post
                DiagLog.i("ICCOA", "GATT services status=$status")
                try {
                    require(status == BluetoothGatt.GATT_SUCCESS)
                    val service = gatt.getService(SERVICE) ?: error("ICCOA service missing")
                    val incoming = service.getCharacteristic(SERVER_INFO) ?: error("Server info missing")
                    require(service.getCharacteristic(CLIENT_INFO) != null)
                    require(gatt.setCharacteristicNotification(incoming, true))
                    attempt.servicesHandled = true
                    // The observed Carlink bootstrap enables local notification routing and
                    // proceeds directly to CLIENT_INFO. XA60 stalls on the extra CCCD write;
                    // do not introduce an ATT operation absent from that exchange.
                    DiagLog.i("ICCOA", "GATT ICCOA channel ready; checking remembered-car credentials")
                    val pinned =
                        if (tryRememberedPin && !forceCurrentPin && trustedCarName.isNotBlank()) {
                            trustedCarKey.load(attempt.profile.id, trustedCarName)
                        } else {
                            null
                        }
                    if (pinned != null) {
                        attempt.pinReady = true
                        DiagLog.i("ICCOA", "Quick Auth: pinned OEM car identity; no saved PIN replay")
                        submitQuick(attempt, pinned)
                    } else {
                        requestPin(attempt)
                    }
                } catch (e: Exception) {
                    fail(attempt, context.getString(R.string.iccoa_msg_no_channel, e.javaClass.simpleName))
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) = post(attempt) {
                if (gatt !== attempt.gatt) return@post
                DiagLog.i("ICCOA", "GATT characteristic write uuid=${characteristic.uuid} status=$status")
                if (gatt === attempt.gatt &&
                    characteristic.uuid == CLIENT_INFO &&
                    status != BluetoothGatt.GATT_SUCCESS &&
                    !attempt.wifiRequested
                ) {
                    if (attempt.quickMode) {
                        requestPin(attempt, context.getString(R.string.iccoa_msg_quick_not_confirmed))
                    } else {
                        requestPin(attempt, context.getString(R.string.iccoa_msg_code_rejected, status))
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == SERVER_INFO) receiveBootstrap(attempt, gatt, value.copyOf())
            }

            @Deprecated("Pre-Android 13 callback")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                if (characteristic.uuid == SERVER_INFO) receiveBootstrap(attempt, gatt, value.copyOf())
            }
        }

    private fun discover(
        attempt: Attempt,
        gatt: BluetoothGatt,
    ) {
        if (gatt !== attempt.gatt || attempt.servicesRequested) return
        attempt.servicesRequested = true
        try {
            if (!gatt.discoverServices()) fail(attempt, context.getString(R.string.iccoa_msg_services_request_failed))
        } catch (e: Exception) {
            fail(attempt, context.getString(R.string.iccoa_msg_services_error, e.javaClass.simpleName))
        }
    }

    private fun requestPin(
        attempt: Attempt,
        message: String = context.getString(R.string.iccoa_msg_enter_code),
    ) {
        attempt.pin = null
        attempt.quickMode = false
        attempt.pinnedCarIdentity = null
        attempt.pinReady = true
        status(attempt, message)
        onPinRequired()
    }

    private fun receiveBootstrap(
        attempt: Attempt,
        gatt: BluetoothGatt,
        fragment: ByteArray,
    ) = post(attempt) {
        try {
            if (gatt !== attempt.gatt ||
                attempt.wifiRequested ||
                (attempt.pin == null && !attempt.quickMode)
            ) {
                return@post
            }
            DiagLog.i("ICCOA", "GATT server-info fragment received: ${fragment.size} bytes")
            if (fragment.firstOrNull() == '{'.code.toByte()) attempt.serverInfo.reset()
            require(attempt.serverInfo.size() + fragment.size <= 4096)
            attempt.serverInfo.write(fragment)
            if (fragment.toString(Charsets.UTF_8).trimEnd().lastOrNull() != '}') return@post
            val raw = attempt.serverInfo.toByteArray()
            val info =
                try {
                    JSONObject(raw.toString(Charsets.UTF_8))
                } finally {
                    raw.fill(0)
                    attempt.serverInfo.reset()
                }
            when (info.optInt("type", IccoaLink.TYPE_WIFI_DIRECT)) {
                IccoaLink.TYPE_SOFT_AP, IccoaLink.TYPE_BOTH -> joinWifi(attempt, info)
                IccoaLink.TYPE_WIFI_DIRECT -> {
                    require(Build.VERSION.SDK_INT >= 29) { "P2P_UNSUPPORTED" }
                    joinWifiDirect(attempt, info)
                }
                else -> error("unknown network type")
            }
        } catch (e: Exception) {
            fail(
                attempt,
                if (e.message == "P2P_UNSUPPORTED") {
                    context.getString(R.string.iccoa_msg_wifi_direct_unsupported)
                } else {
                    context.getString(R.string.iccoa_msg_wifi_params_invalid, e.javaClass.simpleName)
                },
            )
        } finally {
            fragment.fill(0)
        }
    }

    private fun joinWifi(
        attempt: Attempt,
        info: JSONObject,
    ) {
        val specifier =
            WifiNetworkSpecifier
                .Builder()
                .setSsid(info.getString("ssid"))
                .setWpa2Passphrase(info.getString("psk"))
                .apply {
                    val mac = info.optString("mac")
                    if (mac.isNotBlank()) setBssid(MacAddress.fromString(mac))
                }.build()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) =
                    post(attempt) {
                        if (!selectNetwork(attempt, IccoaLink.Wifi(network))) return@post
                        connectivity.getLinkProperties(network)?.let { networkReady(attempt, network, it) }
                    }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    properties: LinkProperties,
                ) = post(attempt) {
                    if (attempt.networkGate.isCurrent(IccoaLink.Wifi(network))) networkReady(attempt, network, properties)
                }

                override fun onUnavailable() {
                    fail(attempt, context.getString(R.string.iccoa_msg_wifi_unavailable))
                }

                override fun onLost(network: Network) =
                    post(attempt) {
                        if (attempt.networkGate.onLost(IccoaLink.Wifi(network))) {
                            fail(
                                attempt,
                                context.getString(R.string.iccoa_msg_wifi_lost),
                                closeReason = IccoaCloseReason.TRANSPORT_FAILURE,
                                shouldFail = { attempt.networkGate.isUnreplacedLoss(IccoaLink.Wifi(network)) },
                            )
                        }
                    }
            }
        attempt.wifiRequested = true
        attempt.networkCallback = callback
        status(attempt, context.getString(R.string.iccoa_msg_wifi_connecting))
        try {
            connectivity.requestNetwork(request, callback, 60_000)
        } catch (e: SecurityException) {
            fail(attempt, context.getString(R.string.iccoa_msg_wifi_denied, "SecurityException"))
            return
        } catch (e: Exception) {
            fail(
                attempt,
                context.getString(R.string.iccoa_msg_wifi_request_failed, e.javaClass.simpleName),
                closeReason = IccoaCloseReason.TRANSPORT_FAILURE,
            )
            return
        }
        later(attempt, 65_000) {
            if (!attempt.authStarted) {
                fail(
                    attempt,
                    if (attempt.hasWifiAddress) {
                        context.getString(R.string.iccoa_msg_wifi_no_gateway)
                    } else {
                        context.getString(R.string.iccoa_msg_wifi_no_ipv4)
                    },
                    closeReason = IccoaCloseReason.TRANSPORT_FAILURE,
                )
            }
        }
    }

    /**
     * The car is a Wi-Fi Direct group owner (server info type 1001): join its group by name and passphrase, the
     * "fast connection" a real ColorOS phone does on p2p0 (OpenCarLink manual section 17). Android only takes names
     * of the DIRECT-xy-... form here. Sockets are then bound to the phone's p2p address ([IccoaLink.Direct]).
     */
    @android.annotation.TargetApi(29)
    private fun joinWifiDirect(
        attempt: Attempt,
        info: JSONObject,
    ) {
        val manager =
            context.getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
                ?: error("no Wi-Fi Direct")
        val channel = manager.initialize(context, main.looper, null) ?: error("no Wi-Fi Direct channel")
        val config =
            android.net.wifi.p2p.WifiP2pConfig
                .Builder()
                .setNetworkName(info.getString("ssid"))
                .setPassphrase(info.getString("psk"))
                .enablePersistentMode(false)
                .apply {
                    val freq = info.optInt("freq", 0)
                    if (freq > 0) setGroupOperatingFrequency(freq)
                    val mac = info.optString("mac")
                    if (mac.isNotBlank() && !mac.startsWith("02:00:00")) runCatching { setDeviceAddress(MacAddress.fromString(mac)) }
                }.build()
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    c: android.content.Context,
                    intent: android.content.Intent,
                ) {
                    if (intent.action != android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                    post(attempt) {
                        manager.requestConnectionInfo(channel) { p2p ->
                            post(attempt) { directGroupChanged(attempt, manager, channel, p2p) }
                        }
                    }
                }
            }
        val filter = android.content.IntentFilter(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        track(
            attempt,
            Closeable {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { manager.removeGroup(channel, null) }
                runCatching { channel.close() }
            },
        )
        attempt.wifiRequested = true
        status(attempt, context.getString(R.string.iccoa_msg_wifi_connecting))
        DiagLog.i("ICCOA", "joining the car's Wi-Fi Direct group (freq=${info.optInt("freq", 0)})")
        manager.connect(
            channel,
            config,
            object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit

                override fun onFailure(reason: Int) {
                    fail(
                        attempt,
                        context.getString(R.string.iccoa_msg_wifi_request_failed, "p2p reason $reason"),
                        closeReason = IccoaCloseReason.TRANSPORT_FAILURE,
                    )
                }
            },
        )
        later(attempt, 65_000) {
            if (!attempt.authStarted) {
                fail(attempt, context.getString(R.string.iccoa_msg_wifi_no_ipv4), closeReason = IccoaCloseReason.TRANSPORT_FAILURE)
            }
        }
    }

    private fun directGroupChanged(
        attempt: Attempt,
        manager: android.net.wifi.p2p.WifiP2pManager,
        channel: android.net.wifi.p2p.WifiP2pManager.Channel,
        p2p: android.net.wifi.p2p.WifiP2pInfo?,
    ) {
        val current = attempt.network as? IccoaLink.Direct
        if (p2p == null || !p2p.groupFormed) {
            if (current != null && attempt.networkGate.onLost(current)) {
                fail(attempt, context.getString(R.string.iccoa_msg_wifi_lost), closeReason = IccoaCloseReason.TRANSPORT_FAILURE)
            }
            return
        }
        if (attempt.authStarted || p2p.isGroupOwner) return
        val peer = p2p.groupOwnerAddress?.hostAddress ?: return
        manager.requestGroupInfo(channel) { group ->
            val iface = group?.`interface`
            worker(attempt) {
                // The p2p address shows up a moment after the group forms
                var local: String? = null
                var tries = 0
                while (local == null && tries++ < 40 && active(attempt)) {
                    local = p2pAddress(iface)
                    if (local == null) Thread.sleep(250)
                }
                val address = local ?: return@worker fail(attempt, context.getString(R.string.iccoa_msg_wifi_no_ipv4))
                post(attempt) {
                    if (attempt.authStarted) return@post
                    attempt.hasWifiAddress = true
                    val link = IccoaLink.Direct(address)
                    if (!selectNetwork(attempt, link)) return@post
                    DiagLog.i("ICCOA", "on the car's Wi-Fi Direct group (iface=$iface)")
                    beginAuth(attempt, link, address, peer)
                }
            }
        }
    }

    private fun p2pAddress(iface: String?): String? =
        runCatching {
            java.net.NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && (if (iface.isNullOrBlank()) it.name.startsWith("p2p") else it.name == iface) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
        }.getOrNull()

    private fun networkReady(
        attempt: Attempt,
        network: Network,
        properties: LinkProperties,
    ) {
        if (!attempt.networkGate.isCurrent(IccoaLink.Wifi(network)) || attempt.authStarted) return
        val link = properties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return
        val address = link.address as Inet4Address
        val local = address.hostAddress ?: return
        attempt.hasWifiAddress = true
        val routed = properties.routes.firstOrNull { it.hasGateway() && it.gateway is Inet4Address }?.gateway

        // On concurrent Wi-Fi Android returns this requesting app's specifier connection.
        // Still reject a stale DHCP snapshot unless its local address/subnet match this Network.
        @Suppress("DEPRECATION")
        val fallback =
            if (routed == null) {
                runCatching {
                    context.getSystemService(WifiManager::class.java)?.dhcpInfo?.let {
                        IccoaWifiGateway.fromDhcp(address, link.prefixLength, it.ipAddress, it.gateway)
                    }
                }.getOrNull()
            } else {
                null
            }
        val peer = (routed ?: fallback)?.hostAddress
        if (peer == null) {
            if (!attempt.gatewayRetryScheduled) {
                attempt.gatewayRetryScheduled = true
                status(attempt, context.getString(R.string.iccoa_msg_wifi_waiting_gateway))
                later(attempt, 1_000) {
                    attempt.gatewayRetryScheduled = false
                    post(attempt) {
                        connectivity.getLinkProperties(network)?.let { networkReady(attempt, network, it) }
                    }
                }
            }
            return
        }
        DiagLog.i("ICCOA", "Wi-Fi gateway source=${if (routed != null) "routes" else "validated-dhcp"}")
        beginAuth(attempt, IccoaLink.Wifi(network), local, peer)
    }

    /** The car's network is up (SoftAP or Wi-Fi Direct) with both addresses known: run the signed auth. */
    private fun beginAuth(
        attempt: Attempt,
        network: IccoaLink,
        local: String,
        peer: String,
    ) {
        val pin = attempt.pin
        val pinned = attempt.pinnedCarIdentity?.copyOf()
        if (pin == null && pinned == null) {
            return fail(attempt, context.getString(R.string.iccoa_msg_no_auth_method))
        }
        attempt.authStarted = true
        attempt.network = network
        // Retain the fresh PIN only within this attempt until signed auth succeeds;
        // a replacement Network may need to repeat the same in-flight handshake.
        status(attempt, context.getString(R.string.iccoa_msg_authenticating))
        worker(attempt) { authenticate(attempt, network, local, peer, pin, pinned) }
    }

    private fun authenticate(
        attempt: Attempt,
        network: IccoaLink,
        local: String,
        peer: String,
        pin: String?,
        pinned: ByteArray?,
    ) {
        var authSocket: Socket? = null
        var stage = "tcp-bind"

        fun progress(value: String) {
            stage = value
            if (active(attempt)) DiagLog.i("ICCOA", "AUTH $value")
        }
        try {
            val socket = Socket()
            authSocket = socket
            if (!track(attempt, socket)) return
            if (!attempt.networkGate.bindIfCurrent(network) { network.bind(socket) }) {
                socket.close()
                return
            }
            progress("tcp-connect")
            socket.connect(InetSocketAddress(peer, 57209), 8_000)
            if (!attempt.networkGate.isCurrent(network)) {
                socket.close()
                return // Never send auth bytes over a superseded Wi-Fi Network.
            }
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            progress("build-request")
            val auth = IccoaAuth(pin, attempt.clientId, Build.MODEL, IccoaClientIdentity.load(), pinned)
            progress("send-request")
            socket.getOutputStream().run {
                write(IccoaControl.frame(3, 1, 1, auth.request(), 1))
                flush()
            }
            progress("read-response")
            val response =
                IccoaControl.read(socket.getInputStream()) { header ->
                    if (active(attempt)) DiagLog.i("ICCOA", "AUTH header $header")
                }
            // The reference accepts the first auth-lane response without specifying method/type.
            // Its protobuf transcript is still authenticated below before any listener is exposed.
            if (response.category != 3 || response.format != 1) throw IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_LANE)
            val key = auth.accept(response.body, ::progress)
            val verifiedCarIdentity =
                auth.verifiedCarIdentity
                    ?: throw IccoaHandshakeException(IccoaHandshakeException.Reason.IDENTITY_LENGTH)
            // A Normal response must not silently overwrite an existing trusted car key.
            if (pinned == null && trustedCarName.isNotBlank()) {
                val oldKey = trustedCarKey.load(attempt.clientId, trustedCarName)
                if (oldKey != null && !MessageDigest.isEqual(oldKey, verifiedCarIdentity)) {
                    throw IccoaHandshakeException(IccoaHandshakeException.Reason.TRUSTED_IDENTITY_MISMATCH)
                }
            }
            // Serialize the key commit and listener publication with Android Network callbacks.
            // Only local port binding runs on main; handshake reads/writes stay on this worker.
            progress("open-listeners")
            val activation =
                FutureTask {
                    activateNetwork(attempt, network, key, local, peer)
                }
            if (!main.post(activation) || !activation.get()) return
            if (!active(attempt) || !attempt.networkGate.isCurrent(network)) return
            progress("send-confirm")
            socket.getOutputStream().run {
                write(IccoaControl.frame(3, 3, 0, auth.confirm(key, local), 2))
                flush()
            }
            if (pinned == null) {
                synchronized(attempt) {
                    attempt.pendingTrustedCarIdentity = verifiedCarIdentity.copyOf()
                }
            }
            if (attempt.trustedCommitGate.onConfirmSent()) confirmTrustedCarAfterControl(attempt)
            status(attempt, context.getString(R.string.iccoa_msg_auth_done))
            later(attempt, 25_000) {
                if (attempt.control ==
                    null
                ) {
                    fail(
                        attempt,
                        context.getString(R.string.iccoa_msg_no_control_channel),
                        closeReason = IccoaCloseReason.TRANSPORT_FAILURE,
                    )
                }
            }
            watchWrites(attempt)
            // Auth is a bootstrap lane: receivers may close it after confirmation.
            socket.soTimeout = 0
            try {
                while (active(attempt)) IccoaControl.read(socket.getInputStream())
            } catch (_: Exception) {
            }
            socket.close()
        } catch (failure: Exception) {
            val e = (failure as? ExecutionException)?.cause as? Exception ?: failure
            // A stale worker must not terminate the replacement Network's auth.
            if (!active(attempt) || !attempt.networkGate.isCurrent(network)) return
            // Exception messages may contain peer data. Log only our fixed codes and stack locations.
            val reason = (e as? IccoaHandshakeException)?.diagnostic ?: e.javaClass.simpleName
            val location =
                e.stackTrace
                    .firstOrNull { it.className.startsWith("io.github.ezn24.droidcar.iccoa.") }
                    ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    .orEmpty()
            DiagLog.w("ICCOA", "AUTH failed stage=$stage reason=$reason at=$location")
            val hint =
                when ((e as? IccoaHandshakeException)?.reason) {
                    IccoaHandshakeException.Reason.PIN_MAC -> context.getString(R.string.iccoa_msg_hint_pin_mac)
                    IccoaHandshakeException.Reason.RESPONSE_RESULT ->
                        when (e.result) {
                            1L -> context.getString(R.string.iccoa_msg_hint_new_pin)
                            2L -> context.getString(R.string.iccoa_msg_hint_confirm_on_car)
                            3L -> context.getString(R.string.iccoa_msg_hint_car_error)
                            4L -> context.getString(R.string.iccoa_msg_hint_mode_rejected)
                            else -> context.getString(R.string.iccoa_msg_hint_rejected)
                        }
                    else -> context.getString(R.string.iccoa_msg_hint_send_report)
                }
            fail(
                attempt,
                context.getString(R.string.iccoa_msg_auth_failed, stage, reason, hint),
                closeReason = IccoaFailurePolicy.authentication(stage, pinned != null, e),
                shouldFail = { attempt.networkGate.isCurrent(network) },
            )
        } finally {
            authSocket?.let { socket ->
                runCatching { socket.close() }
                synchronized(attempt) { attempt.resources.remove(socket) }
            }
        }
    }

    /** Called on main, like activation, so a replacement cannot interleave listener publication. */
    private fun selectNetwork(
        attempt: Attempt,
        network: IccoaLink,
    ): Boolean {
        if (!active(attempt)) return false
        val changed = attempt.networkGate.onAvailable(network)
        if (changed && attempt.authenticatedNetwork != null) {
            // Committed channels/cipher belong to one Network. Rebuild the whole session instead
            // of reusing listeners, or replaying a Normal PIN that has already been discarded.
            terminate(
                attempt,
                context.getString(R.string.iccoa_msg_wifi_changed),
                IccoaCloseReason.TRANSPORT_FAILURE,
            )
            return false
        }
        if (changed) attempt.authStarted = false
        attempt.network = network
        return true
    }

    /** Main looper only; no session/gate locks may be held across the owner's callback. */
    private fun activateNetwork(
        attempt: Attempt,
        network: IccoaLink,
        key: IccoaControl.Cipher,
        local: String,
        peer: String,
    ): Boolean {
        if (!active(attempt) || !attempt.networkGate.isCurrent(network)) return false
        check(attempt.authenticatedNetwork == null) { "Network already authenticated" }
        synchronized(attempt) {
            if (!active(attempt)) return false
            attempt.cipher = key
            attempt.pin = null
            attempt.authenticatedNetwork = network
        }
        for (port in intArrayOf(57219, 57229, 57239, 57249, 57259)) listen(attempt, local, peer, port)
        if (!active(attempt)) return false
        onReady(network, peer)
        return active(attempt)
    }

    /** Persist the pinned car identity only after authenticated CONTROL follows AuthConfirm. */
    private fun confirmTrustedCarAfterControl(attempt: Attempt): Unit =
        post(attempt) {
            // Trust writes and owner stop/forget actions run on the same main looper.
            // A queued write from a cancelled attempt is discarded by post().
            if (attempt.quickMode) {
                DiagLog.i("ICCOA", "QUICK_AUTH_PINLESS_CONFIRMED: signed response + CONTROL")
                return@post
            }
            val carKey =
                synchronized(attempt) {
                    val pending = attempt.pendingTrustedCarIdentity
                    attempt.pendingTrustedCarIdentity = null
                    pending
                }
            if (carKey != null) {
                if (trustedCarName.isNotBlank()) {
                    val stored = trustedCarKey.saveIfAbsent(attempt.clientId, trustedCarName, carKey)
                    DiagLog.i("ICCOA", if (stored) "OEM car identity PINNED after CONTROL" else "OEM identity not saved")
                }
                carKey.fill(0)
            }
            trustedPin.clear() // A rotating NORMAL PIN must never be replayed.
        }

    private fun listen(
        attempt: Attempt,
        local: String,
        peer: String,
        port: Int,
    ) {
        val lane =
            IccoaLaneServer(
                port,
                InetSocketAddress(local, port),
                InetAddress.getByName(peer),
                onConnected = { socket ->
                    synchronized(attempt) {
                        if (active(attempt)) {
                            if (port == IccoaLaneServer.CONTROL_PORT) {
                                attempt.control = socket
                                val heartbeat =
                                    IccoaHeartbeat(
                                        send = { body -> send(attempt, 1, 1, body, 1) },
                                        schedule = { delay, action -> later(attempt, delay, action) },
                                    )
                                attempt.resources += heartbeat
                                DiagLog.i("ICCOA", "CONTROL starting heartbeat on accept")
                                heartbeat.start()
                            }
                            if (port == IccoaLaneServer.AUDIO_PORT) attempt.audio = socket
                        }
                    }
                    if (port == IccoaLaneServer.CONTROL_PORT && active(attempt)) {
                        onControlReady()
                    }
                },
                onDisconnected = { socket ->
                    synchronized(attempt) {
                        if (attempt.control === socket) {
                            attempt.control = null
                        }
                        if (attempt.audio === socket) attempt.audio = null
                    }
                },
                onFrame = { frame -> handleFrame(attempt, port, frame) },
                log = { message -> if (active(attempt)) DiagLog.i("ICCOA", message) },
            )
        if (!track(attempt, lane)) return
        worker(attempt) {
            try {
                lane.run()
            } catch (
                e: Exception,
            ) {
                fail(
                    attempt,
                    context.getString(R.string.iccoa_msg_channel_closed, port, e.javaClass.simpleName),
                    closeReason = IccoaFailurePolicy.channel(e),
                )
            }
        }
    }

    private fun handleFrame(
        attempt: Attempt,
        port: Int,
        frame: IccoaControl.Frame,
    ) {
        if (!active(attempt)) return
        val key = attempt.cipher ?: error("Session key unavailable")
        // Empty/plain protocol messages exist in the reference; encrypted envelopes fail closed.
        val decoded = IccoaInboundFrame.decode(port, frame, key)
        val body = decoded.body
        if (!active(attempt)) return
        if (frame.category == 1 && frame.method == 23) {
            fail(attempt, context.getString(R.string.iccoa_msg_car_ended), closeReason = IccoaCloseReason.TRANSPORT_FAILURE)
            return
        }
        if (decoded.authenticatedControl && attempt.trustedCommitGate.onAuthenticatedControl()) {
            confirmTrustedCarAfterControl(attempt)
        }
        if (frame.category != 1 || frame.method != 1) onMessage(frame.category, frame.method, body)
    }

    private fun watchWrites(attempt: Attempt): Unit =
        post(attempt) {
            val now = System.nanoTime()
            if (listOf(attempt.controlWritingAt, attempt.audioWritingAt).any { it > 0 && now - it > TimeUnit.SECONDS.toNanos(5) }) {
                fail(attempt, context.getString(R.string.iccoa_msg_send_timeout), closeReason = IccoaCloseReason.TRANSPORT_FAILURE)
            } else {
                later(attempt, 2_000) { watchWrites(attempt) }
            }
        }

    private fun active(attempt: Attempt) = current === attempt && !attempt.closed

    private fun post(
        attempt: Attempt,
        action: () -> Unit,
    ) {
        main.post {
            if (active(attempt)) {
                try {
                    action()
                } catch (e: Exception) {
                    fail(attempt, context.getString(R.string.iccoa_msg_connection_error, e.javaClass.simpleName))
                }
            }
        }
    }

    private fun later(
        attempt: Attempt,
        delay: Long,
        action: () -> Unit,
    ) {
        main.postAtTime({ if (active(attempt)) action() }, attempt, android.os.SystemClock.uptimeMillis() + delay)
    }

    private fun status(
        attempt: Attempt,
        message: String,
    ) = post(attempt) {
        DiagLog.i("ICCOA", message)
        onStatus(message)
    }

    private fun worker(
        attempt: Attempt,
        action: () -> Unit,
    ) {
        try {
            attempt.workers.execute { if (active(attempt)) action() }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun track(
        attempt: Attempt,
        resource: Closeable,
    ): Boolean =
        synchronized(attempt) {
            if (!active(attempt)) {
                runCatching { resource.close() }
                false
            } else {
                attempt.resources += resource
                true
            }
        }

    private fun fail(
        attempt: Attempt,
        message: String,
        closeReason: IccoaCloseReason = IccoaCloseReason.FAILED,
        shouldFail: () -> Boolean = { true },
    ) = post(attempt) {
        // Recheck a queued Network loss immediately before closing the session.
        if (!shouldFail()) return@post
        terminate(attempt, message, closeReason)
    }

    private fun terminate(
        attempt: Attempt,
        message: String,
        closeReason: IccoaCloseReason,
    ) {
        synchronized(this) {
            if (!active(attempt)) return
            current = null
            close(attempt)
        }
        onClosed(message, closeReason)
    }

    private fun close(attempt: Attempt) {
        synchronized(attempt) {
            attempt.closed = true
            attempt.cipher = null
            attempt.pin = null
            attempt.quickMode = false
            attempt.pinnedCarIdentity = null
            attempt.pendingTrustedCarIdentity = null
            attempt.trustedCommitGate.cancel()
            attempt.resources.forEach { runCatching { it.close() } }
            attempt.resources.clear()
            attempt.control = null
            attempt.audio = null
        }
        // Release the gate outside the attempt monitor (socket binding also owns the gate).
        attempt.networkGate.clear()
        main.removeCallbacksAndMessages(attempt)
        attempt.workers.shutdownNow()
        attempt.controlSender.shutdownNow()
        attempt.audioSender.shutdownNow()
        val releaseAndroidResources = {
            runCatching { attempt.gatt?.disconnect() }
            runCatching { attempt.gatt?.close() }
            attempt.gatt = null
            attempt.networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            attempt.networkCallback = null
            attempt.network = null
            attempt.serverInfo.reset()
            DiagLog.i("ICCOA", "Session resources released: Bluetooth, Wi-Fi request and sockets")
        }
        // Main-thread callers must release Android handles before a new scan/attempt starts.
        if (Looper.myLooper() == main.looper) {
            releaseAndroidResources()
        } else {
            main.post { releaseAndroidResources() }
        }
    }

    companion object {
        private val SERVICE = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789100")
        private val CLIENT_INFO = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789101")
        private val SERVER_INFO = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789102")
    }
}
