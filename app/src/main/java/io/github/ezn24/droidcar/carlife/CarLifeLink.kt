/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar
 * (threads instead of coroutines, one writer thread per channel).
 */
package io.github.ezn24.droidcar.carlife

import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone side of a wireless CarLife connection: one TCP listener per channel, the head unit connects to each of them
 * after it got the phone's address (Bluetooth `MD_WIFI_IP` or the UDP discovery answer).
 * A new CMD connection replaces the whole old group (the car reconnects all channels after a restart).
 */
class CarLifeLink(
    private val log: (String) -> Unit,
    private val ports: Map<Int, Int> = DEFAULT_PORTS,
) {
    interface Listener {
        fun onMessage(
            channel: Int,
            head: ByteArray,
            body: ByteArray,
        )

        fun onCommandConnected(remote: String)

        fun onClosed(reason: String)
    }

    private val running = AtomicBoolean(false)
    private val servers = HashMap<Int, ServerSocket>()
    private val sockets = HashMap<Int, Socket>()
    private val writers = HashMap<Int, Writer>()
    private var listener: Listener? = null

    val connected: Boolean get() = running.get() && synchronized(sockets) { sockets.containsKey(CarLifeProtocol.CH_CMD) }

    fun start(listener: Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        for ((channel, port) in ports) {
            Thread({ acceptLoop(channel, port) }, "carlife-accept-${CarLifeProtocol.channelName(channel)}").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        dropAll()
        synchronized(servers) {
            servers.values.forEach { runCatching { it.close() } }
            servers.clear()
        }
        listener = null
    }

    /** Queues [frame] (already framed) on [channel]; false when the channel is not connected or its queue is full. */
    fun send(
        channel: Int,
        frame: ByteArray,
    ): Boolean {
        val writer = synchronized(sockets) { writers[channel] } ?: return false
        return writer.offer(frame)
    }

    private fun acceptLoop(
        channel: Int,
        port: Int,
    ) {
        val server = listen(port) ?: return
        synchronized(servers) { servers[channel] = server }
        log("CARLIFE: ${CarLifeProtocol.channelName(channel)} listening on tcp $port")
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            if (!running.get()) {
                runCatching { socket.close() }
                break
            }
            tune(socket, channel)
            if (channel == CarLifeProtocol.CH_CMD && synchronized(sockets) { sockets.containsKey(channel) }) {
                log("CARLIFE: CMD connected again while the old one was open, starting over")
                dropAll()
                listener?.onClosed("head unit connected again")
            }
            val old: Pair<Socket?, Writer?>
            synchronized(sockets) {
                old = sockets.put(channel, socket) to writers.remove(channel)
                writers[channel] = Writer(channel, socket)
            }
            old.first?.let { runCatching { it.close() } }
            old.second?.close()
            val remote = socket.inetAddress?.hostAddress ?: "?"
            log("CARLIFE: ${CarLifeProtocol.channelName(channel)} connected from $remote")
            if (channel == CarLifeProtocol.CH_CMD) listener?.onCommandConnected(remote)
            Thread({ readLoop(channel, socket) }, "carlife-read-${CarLifeProtocol.channelName(channel)}").apply {
                isDaemon = true
                start()
            }
        }
        runCatching { server.close() }
    }

    private fun listen(port: Int): ServerSocket? {
        var tries = 0
        while (running.get()) {
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                return server
            } catch (e: java.io.IOException) {
                runCatching { server.close() }
                if (++tries == 1) log("CARLIFE: tcp $port busy (${e.message}), retrying")
                Thread.sleep(if (tries < 8) 250 else 2000)
            }
        }
        return null
    }

    private fun tune(
        s: Socket,
        channel: Int,
    ) {
        runCatching { s.tcpNoDelay = true }
        runCatching { s.keepAlive = true }
        runCatching { s.receiveBufferSize = BUFFER }
        runCatching {
            s.sendBufferSize =
                when (channel) {
                    CarLifeProtocol.CH_TTS -> 8 * 1024
                    CarLifeProtocol.CH_MEDIA -> 16 * 1024
                    else -> BUFFER
                }
        }
        when (channel) {
            CarLifeProtocol.CH_MEDIA, CarLifeProtocol.CH_TTS -> runCatching { s.trafficClass = 0xB8 }
            CarLifeProtocol.CH_VIDEO -> runCatching { s.trafficClass = 0x88 }
        }
    }

    private fun readLoop(
        channel: Int,
        s: Socket,
    ) {
        try {
            val input = BufferedInputStream(s.getInputStream(), 32 * 1024)
            val headLen = CarLifeFraming.headLen(channel)
            while (running.get() && !s.isClosed) {
                val head = ByteArray(headLen)
                Bytes.readFully(input, head)
                val len = CarLifeFraming.bodyLen(channel, head)
                if (len < 0 || len > CarLifeFraming.maxBody(channel)) error("bad body length $len")
                val body = ByteArray(len)
                Bytes.readFully(input, body)
                listener?.onMessage(channel, head, body)
            }
        } catch (t: Throwable) {
            if (running.get()) log("CARLIFE: ${CarLifeProtocol.channelName(channel)} read ended: ${t.message}")
        } finally {
            runCatching { s.close() }
        }
        val mine =
            synchronized(sockets) {
                if (sockets[channel] === s) {
                    sockets.remove(channel)
                    writers.remove(channel)?.close()
                    true
                } else {
                    false
                }
            }
        if (mine && running.get()) {
            log("CARLIFE: ${CarLifeProtocol.channelName(channel)} disconnected")
            if (channel == CarLifeProtocol.CH_CMD) {
                dropAll()
                listener?.onClosed("head unit disconnected")
            }
        }
    }

    private fun dropAll() {
        val all =
            synchronized(sockets) {
                val list = sockets.values.toList() to writers.values.toList()
                sockets.clear()
                writers.clear()
                list
            }
        all.first.forEach { runCatching { it.close() } }
        all.second.forEach { it.close() }
    }

    /** One FIFO per channel: socket writes never block the caller (encoder drain, touch thread). */
    private inner class Writer(
        private val channel: Int,
        private val socket: Socket,
    ) {
        private val queue = ArrayBlockingQueue<ByteArray>(if (channel == CarLifeProtocol.CH_VIDEO) 90 else 64)

        @Volatile private var open = true
        private val thread =
            Thread({ loop() }, "carlife-send-${CarLifeProtocol.channelName(channel)}").apply {
                isDaemon = true
                start()
            }

        fun offer(frame: ByteArray): Boolean = open && queue.offer(frame)

        fun close() {
            open = false
            thread.interrupt()
        }

        private fun loop() {
            val out = runCatching { socket.getOutputStream() }.getOrNull() ?: return
            try {
                while (open) {
                    val frame = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    out.write(frame)
                    out.flush()
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                if (open && running.get()) log("CARLIFE: ${CarLifeProtocol.channelName(channel)} write failed: ${t.message}")
                runCatching { socket.close() }
            }
        }
    }

    companion object {
        private const val BUFFER = 327_680

        /** Ports the CarLife head-unit SDK connects to (Baidu defaults, same as FT). */
        val DEFAULT_PORTS =
            mapOf(
                CarLifeProtocol.CH_CMD to 7240,
                CarLifeProtocol.CH_VIDEO to 8240,
                CarLifeProtocol.CH_MEDIA to 9240,
                CarLifeProtocol.CH_TTS to 9241,
                CarLifeProtocol.CH_VR to 9242,
                CarLifeProtocol.CH_CTRL to 9340,
            )
    }
}
