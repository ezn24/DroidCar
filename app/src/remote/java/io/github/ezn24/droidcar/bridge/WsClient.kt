package io.github.ezn24.droidcar.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal RFC6455 WebSocket client (serves only the paired debug bridge, not meant to be general):
 * text-frame send/receive, masking, ping/pong, close, server fragment reassembly.
 *
 * Why no okhttp/ws dependency: the bridge code only enters the remote variant, but dependencies would still be pulled into the build;
 * with our own server (ws library) and known message shapes a ~200-line hand-written implementation is reliable enough.
 * wss:// uses the system default SSLSocketFactory (tunnel services such as Cloudflare Tunnel provide valid
 * certificates); for self-signed/bare-IP cases use ws:// (LAN or http tunnel).
 *
 * Threading model: one internal read thread per connection, sends are serialized on any thread; connection lifecycle is
 * managed by [BridgeClient] (reconnect backoff lives there).
 */
class WsClient(
    private val url: String,
    private val listener: Listener,
    /** Explicit networking channel (cellular). During a car session the process default route is bound to the internet-less head unit hotspot,
     *  so sockets must be created from this network + DNS resolved through it to keep the debug bridge online (see [InternetRoute]). */
    private val network: android.net.Network? = null,
) {
    interface Listener {
        fun onOpen()

        fun onText(text: String)

        fun onClosed(reason: String)
    }

    private val writeLock = Any()
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null

    @Volatile
    private var closedByUs = false

    @Volatile
    private var alive = false
    val isConnected: Boolean get() = alive

    private val random = SecureRandom()

    /** Blocking connect (handshake completes on the calling thread; failure throws IOException). */
    fun connect() {
        val secure = url.startsWith("wss://", ignoreCase = true)
        val rest = url.substring(if (secure) 6 else 5)
        val hostPortPath =
            if (rest.contains('/')) {
                val i = rest.indexOf('/')
                rest.substring(0, i) to rest.substring(i)
            } else {
                rest to "/"
            }
        val host = hostPortPath.first.substringBefore(':')
        val port =
            hostPortPath.first.substringAfter(':', if (secure) "443" else "80").toIntOrNull()
                ?: throw IOException("port resolution failed: $url")
        val path = hostPortPath.second.ifBlank { "/" }

        // When a network is specified DNS must also go through it: the system default DNS would land on the head unit hotspot and fail to
        // resolve public domains
        val address =
            network?.let { n -> runCatching { n.getAllByName(host).firstOrNull() }.getOrNull() }
                ?: InetAddress.getByName(host)
        val plain =
            network?.socketFactory?.createSocket()?.also { s ->
                s.connect(InetSocketAddress(address, port), 8_000)
            }
                ?: run {
                    val s =
                        if (secure) {
                            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket()
                        } else {
                            Socket()
                        }
                    s.connect(InetSocketAddress(address, port), 8_000)
                    s
                }
        val raw =
            if (secure && network != null) {
                // Layer TLS over the already-connected plaintext socket (host is used for SNI; autoClose closes the plaintext layer)
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, host, port, true)
            } else {
                plain
            }
        raw.tcpNoDelay = true
        val input = BufferedInputStream(raw.getInputStream())
        val out = BufferedOutputStream(raw.getOutputStream())
        socket = raw
        output = out

        val key = ByteArray(16).also { random.nextBytes(it) }
        val keyB64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        val handshake =
            "GET $path HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $keyB64\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
        out.write(handshake.toByteArray(Charsets.US_ASCII))
        out.flush()

        val head = readUntilHeaderEnd(input) ?: throw IOException("incomplete handshake response")
        if (!head.startsWith("HTTP/1.1 101") && !head.startsWith("HTTP/1.0 101")) {
            throw IOException("handshake rejected: ${head.lineSequence().first()}")
        }
        alive = true
        listener.onOpen()
        // Dead-connection detection: 25s heartbeat + 75s read timeout. When cellular/NAT silently drops the link (no FIN) read
        // blocks forever and the reconnect loop gets stuck with it; observed 2026-10-21: no reconnect for 4 minutes after the drop, the
        // bridge-side
        // device stayed "offline" long. The heartbeat period is far below the read timeout, so a healthy connection is never killed by
        // mistake.
        socket?.soTimeout = 75_000
        val heartbeat =
            HEARTBEAT.scheduleAtFixedRate(
                {
                    if (!alive) return@scheduleAtFixedRate
                    runCatching { sendFrame(OPCODE_PING, ByteArray(0), mask = true) }
                        .onFailure { runCatching { socket?.close() } }
                },
                25,
                25,
                TimeUnit.SECONDS,
            )
        try {
            readLoop(input)
        } finally {
            heartbeat.cancel(false)
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        val terminator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            val bytes = buf.toByteArray()
            if (bytes.size >= 4 && bytes.copyOfRange(bytes.size - 4, bytes.size).contentEquals(terminator)) {
                return String(bytes, Charsets.ISO_8859_1)
            }
            if (bytes.size > 16_384) return null
        }
    }

    private fun readLoop(input: BufferedInputStream) {
        try {
            var fragmentOpcode = -1
            var fragmentBuffer: ByteArrayOutputStream? = null
            while (alive) {
                val frame = readFrame(input) ?: break
                when (frame.opcode) {
                    OPCODE_TEXT, OPCODE_BINARY -> {
                        if (fragmentBuffer != null) {
                            // New data frame received while an incomplete fragment is pending: discard the old fragment and start over
                            fragmentBuffer = null
                            fragmentOpcode = -1
                        }
                        if (frame.isFin) {
                            listener.onText(String(frame.payload, Charsets.UTF_8))
                        } else {
                            fragmentOpcode = frame.opcode
                            fragmentBuffer = ByteArrayOutputStream().also { it.write(frame.payload) }
                        }
                    }

                    OPCODE_CONTINUATION -> {
                        fragmentBuffer?.let { frag ->
                            frag.write(frame.payload)
                            if (frame.isFin) {
                                listener.onText(String(frag.toByteArray(), Charsets.UTF_8))
                                fragmentBuffer = null
                                fragmentOpcode = -1
                            }
                        }
                    }

                    OPCODE_PING -> runCatching { sendFrame(OPCODE_PONG, frame.payload, mask = true) }

                    OPCODE_PONG -> Unit

                    OPCODE_CLOSE -> {
                        val reason =
                            frame.payload
                                .drop(2)
                                .toByteArray()
                                .toString(Charsets.UTF_8)
                        listener.onClosed("peer closed: $reason")
                        return
                    }
                }
            }
            listener.onClosed("read loop ended")
        } catch (error: IOException) {
            listener.onClosed("IO: ${error.message}")
        } finally {
            alive = false
            runCatching { socket?.close() }
        }
    }

    private class Frame(
        val opcode: Int,
        val isFin: Boolean,
        val payload: ByteArray,
    )

    private fun readFrame(input: BufferedInputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((input.read() and 0xFF).toLong() shl 8) or (input.read() and 0xFF).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or (input.read() and 0xFF).toLong() }
        }
        if (len < 0 || len > 8_000_000) return null
        val maskKey = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(len.toInt())
        readFully(input, payload)
        if (maskKey != null) {
            payload.forEachIndexed { index, _ -> payload[index] = (payload[index].toInt() xor maskKey[index % 4].toInt()).toByte() }
        }
        return Frame(opcode, fin, payload)
    }

    private fun readFully(
        input: BufferedInputStream,
        target: ByteArray,
    ) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw IOException("connection interrupted (reading payload)")
            offset += count
        }
    }

    /** Send a text frame (returns false when the connection is down; the upper layer reconnects). */
    fun sendText(text: String): Boolean = sendFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8), mask = true)

    private fun sendFrame(
        opcode: Int,
        payload: ByteArray,
        mask: Boolean,
    ): Boolean {
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                val header = ByteArrayOutputStream()
                header.write(0x80 or opcode)
                val maskBit = if (mask) 0x80 else 0
                when {
                    payload.size < 126 -> header.write(maskBit or payload.size)
                    payload.size < 65536 -> {
                        header.write(maskBit or 126)
                        header.write((payload.size shr 8) and 0xFF)
                        header.write(payload.size and 0xFF)
                    }

                    else -> {
                        header.write(maskBit or 127)
                        var v = payload.size.toLong()
                        val shiftOrder = (7 downTo 0).map { it * 8 }
                        shiftOrder.forEach { shift -> header.write(((v shr shift) and 0xFF).toInt()) }
                        @Suppress("UNUSED_EXPRESSION")
                        v = 0
                    }
                }
                if (mask) {
                    val key = ByteArray(4).also { random.nextBytes(it) }
                    header.write(key)
                    val masked = ByteArray(payload.size)
                    payload.forEachIndexed { i, b -> masked[i] = (b.toInt() xor key[i % 4].toInt()).toByte() }
                    out.write(header.toByteArray())
                    out.write(masked)
                } else {
                    out.write(header.toByteArray())
                    out.write(payload)
                }
                out.flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    fun close(
        code: Int = 1000,
        reason: String = "",
    ) {
        closedByUs = true
        alive = false
        val body = ByteArray(2 + reason.toByteArray(Charsets.UTF_8).size)
        body[0] = (code shr 8).toByte()
        body[1] = code.toByte()
        reason.toByteArray(Charsets.UTF_8).copyInto(body, 2)
        runCatching { sendFrame(OPCODE_CLOSE, body, mask = true) }
        runCatching { socket?.close() }
    }

    private companion object {
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA

        /** Heartbeat scheduler (single-thread daemon: connection count is on the order of 1, not worth a thread per connection). */
        val HEARTBEAT =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ws-heartbeat").apply { isDaemon = true }
            }
    }
}
