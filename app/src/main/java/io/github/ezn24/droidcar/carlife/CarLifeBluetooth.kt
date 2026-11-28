/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar
 * (CarBluetooth + CarWirelessSetup merged, threads instead of coroutines).
 */
package io.github.ezn24.droidcar.carlife

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.util.UUID

/**
 * Wireless CarLife bootstrap over Bluetooth. The head unit calls the phone on the CarLife RFCOMM service (or plain SPP),
 * then the phone asks it to open Wi-Fi Direct and learns the group name ([onCarWifiName]); once the phone has an
 * address on that group ([wifiDirectReady]) it answers the car's IP request and the car connects to the TCP channels.
 */
@SuppressLint("MissingPermission")
class CarLifeBluetooth(
    context: Context,
    private val log: (String) -> Unit,
    private val commandConnected: () -> Boolean,
) {
    var onCarWifiName: ((String) -> Unit)? = null
    var onCar: ((address: String?) -> Unit)? = null

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var running = false
    private val servers = ArrayList<BluetoothServerSocket>()

    @Volatile private var socket: BluetoothSocket? = null
    private val buffer = java.io.ByteArrayOutputStream()

    @Volatile var carWifiName: String? = null
        private set

    private var ipWanted = false
    private var ipSentAt = 0L
    private var phoneIp: String? = null

    fun start() {
        if (running) return
        running = true
        Thread({ listenLoop() }, "carlife-bt").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        synchronized(servers) {
            servers.forEach { runCatching { it.close() } }
            servers.clear()
        }
        runCatching { socket?.close() }
        socket = null
    }

    /** The phone's address on the car's Wi-Fi Direct group (null = group gone). */
    @Synchronized
    fun wifiDirectReady(ip: String?) {
        if (ip != phoneIp) ipSentAt = 0L
        phoneIp = ip
        if (ip != null && ipWanted) sendIp(requested = false)
    }

    private fun listenLoop() {
        val a = adapter ?: return log("CARLIFE: no Bluetooth adapter")
        while (running) {
            if (!a.isEnabled) {
                Thread.sleep(3000)
                continue
            }
            val offered =
                listOf(CARLIFE_UUID to "CarLife", SPP_UUID to "SPP").mapNotNull { (uuid, label) ->
                    runCatching { a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid) }
                        .onFailure { log("CARLIFE: cannot offer Bluetooth $label: ${it.message}") }
                        .getOrNull()
                }
            if (offered.isEmpty()) {
                Thread.sleep(3000)
                continue
            }
            synchronized(servers) { servers.addAll(offered) }
            log("CARLIFE: waiting for the car to call over Bluetooth")
            val winner = java.util.concurrent.SynchronousQueue<BluetoothSocket>()
            val accepts =
                offered.map { ss ->
                    Thread({
                        runCatching { ss.accept() }.getOrNull()?.let { s ->
                            if (!winner.offer(s)) runCatching { s.close() }
                        }
                    }, "carlife-bt-accept").apply {
                        isDaemon = true
                        start()
                    }
                }
            var call: BluetoothSocket? = null
            while (running && call == null && accepts.any { it.isAlive }) {
                call = winner.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            synchronized(servers) {
                servers.forEach { runCatching { it.close() } }
                servers.clear()
            }
            if (call != null && running) talk(call)
            if (running) Thread.sleep(800)
        }
    }

    private fun talk(s: BluetoothSocket) {
        val address = runCatching { s.remoteDevice?.address }.getOrNull().orEmpty()
        log("CARLIFE: car called over Bluetooth")
        socket = s
        synchronized(this) {
            buffer.reset()
            carWifiName = null
            ipWanted = false
            ipSentAt = 0L
        }
        onCar?.invoke(address)
        send(CarLifeFraming.cmd(MD_WIRELESS_INFO_REQUEST))
        val input = runCatching { s.inputStream }.getOrNull()
        val buf = ByteArray(512)
        try {
            while (running) {
                val n = input?.read(buf) ?: -1
                if (n < 0) break
                if (n > 0) feed(buf.copyOf(n))
            }
        } catch (t: Throwable) {
            if (running) log("CARLIFE: Bluetooth link ended: ${t.message}")
        } finally {
            runCatching { s.close() }
            if (socket === s) socket = null
            onCar?.invoke(null)
        }
    }

    private fun send(bytes: ByteArray): Boolean {
        val s = socket ?: return false
        return runCatching {
            s.outputStream.write(bytes)
            s.outputStream.flush()
            true
        }.getOrElse {
            log("CARLIFE: Bluetooth write failed: ${it.message}")
            false
        }
    }

    @Synchronized
    private fun feed(chunk: ByteArray) {
        buffer.write(chunk)
        val data = buffer.toByteArray()
        var at = 0
        val frames = ArrayList<Pair<Int, ByteArray>>()
        while (data.size - at >= CarLifeProtocol.HEAD_CMD) {
            val len = Bytes.u16(data, at)
            if (len > MAX_BODY || Bytes.u16(data, at + 2) != 0) {
                at++
                continue
            }
            if (data.size - at < CarLifeProtocol.HEAD_CMD + len) break
            frames += Bytes.u32(data, at + 4) to data.copyOfRange(at + CarLifeProtocol.HEAD_CMD, at + CarLifeProtocol.HEAD_CMD + len)
            at += CarLifeProtocol.HEAD_CMD + len
        }
        buffer.reset()
        buffer.write(data, at, data.size - at)
        frames.forEach { (id, body) -> handle(id, body) }
    }

    private fun handle(
        id: Int,
        body: ByteArray,
    ) {
        when (id) {
            HU_WIRELESS_INFO -> {
                val r = ProtoReader(body)
                log("CARLIFE: car wireless info type ${r.int(1, -1)} band ${r.int(2, 0)}")
                if (carWifiName == null) send(CarLifeFraming.cmd(MD_TARGET_INFO_REQUEST))
            }
            HU_TARGET_INFO -> {
                if (carWifiName != null) return
                val name = ProtoReader(body).string(1).orEmpty().trim()
                if (name.isBlank()) return log("CARLIFE: car sent no Wi-Fi Direct name")
                carWifiName = name
                log("CARLIFE: car is on Wi-Fi Direct as '$name'")
                onCarWifiName?.invoke(name)
            }
            HU_IP_REQUEST -> {
                ipWanted = true
                log("CARLIFE: car asks for the phone address (have=${phoneIp != null})")
                if (phoneIp != null) sendIp(requested = true)
            }
            HU_STATUS -> log("CARLIFE: car wireless status ${ProtoReader(body).int(1, -1)}")
            else -> Unit
        }
    }

    /** The car's SDK restarts its TCP client on every MD_WIFI_IP: never send it while CMD is already connected. */
    private fun sendIp(requested: Boolean) {
        val ip = phoneIp ?: return
        if (commandConnected()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (ipSentAt > 0 && (!requested || now - ipSentAt < IP_REPLY_INTERVAL_MS)) return
        ipSentAt = now
        if (send(CarLifeFraming.cmd(MD_WIFI_IP, ProtoWriter().string(1, ip).toByteArray()))) {
            log("CARLIFE: told the car to connect to the phone on Wi-Fi Direct")
        } else {
            ipSentAt = 0L
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val CARLIFE_UUID: UUID = UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78")
        private const val SERVICE_NAME = "carlife"
        private const val MAX_BODY = 4096
        private const val IP_REPLY_INTERVAL_MS = 2000L
        const val MD_WIRELESS_INFO_REQUEST = 0x00100001
        const val HU_WIRELESS_INFO = 0x00108002
        const val MD_TARGET_INFO_REQUEST = 0x00100004
        const val HU_TARGET_INFO = 0x00108005
        const val HU_IP_REQUEST = 0x00108006
        const val MD_WIFI_IP = 0x00100007
        const val HU_STATUS = 0x00108009
    }
}
