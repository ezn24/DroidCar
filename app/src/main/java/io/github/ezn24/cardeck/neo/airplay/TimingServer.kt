package io.github.ezn24.cardeck.neo.airplay

import io.github.ezn24.cardeck.neo.net.CarNetwork
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * AirPlay timing service (NTP over RTCP; see catplay clock/timing_server.rs + rtcp_timesync.rs).
 *
 * The initial SETUP advertises this port (timingPort); the head unit (media clock master) sends 32-byte timesync requests to it,
 * and we answer with NTP semantics: t1 = echoed request t3 (client send time), t2 = local receive time, t3 = local send time.
 * Missing piece: we used to advertise timingPort=0, so the head unit could not sync its media clock with us; this was the
 * prime suspect for the screen-stream SETUP getting 400 (catplay always binds a real port on real cars).
 *
 * Wire format (big-endian): [0x80][pt][length=7][rtp_ts=0][t1(8)][t2(8)][t3(8)],
 * pt: 0xD2 request / 0xD3 response; time = NTP 64-bit (seconds<<32 | 2^32 fraction), taken from a monotonic clock.
 */
class TimingServer(
    private val log: (String) -> Unit = {},
) {
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    /** Count of timesync requests received (diagnostics: whether the head unit actually syncs its media clock). */
    private var syncRequests = 0L
    private var lastSyncLogAt = 0L

    /** Count of non-timesync packets (diagnostics: whether other UDP traffic hits this port). */
    private var unexpected = 0L

    val port: Int get() = socket?.localPort ?: 0

    fun start(): Boolean {
        if (port != 0 && running) return true
        return try {
            val s = DatagramSocket().also { CarNetwork.bind(it, "timing UDP") }
            s.reuseAddress = true
            socket = s
            running = true
            Thread({ loop(s) }, "airplay-timing").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("TIMING: start failed - ${error.message}")
            false
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(256)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                return // socket closed
            }
            val request = buffer.copyOfRange(0, packet.length)
            val response = buildResponse(request, System.nanoTime())
            if (response == null) {
                unexpected++
                if (unexpected <= 5) {
                    log("TIMING: non-timesync packet ${request.size}B (total $unexpected)")
                }
                continue
            }
            syncRequests++
            val now = System.currentTimeMillis()
            if (syncRequests <= 3 || now - lastSyncLogAt >= 30_000L) {
                lastSyncLogAt = now
                log("TIMING: timesync reply #$syncRequests -> ${packet.address.hostAddress}:${packet.port}")
            }
            try {
                s.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val PT_TIMESYNC_REQUEST: Byte = 0xD2.toByte()
        private const val PT_TIMESYNC_RESPONSE: Byte = 0xD3.toByte()
        private const val PACKET_BYTES = 32

        /**
         * Builds a timesync reply; returns null for non-timesync requests.
         * [nowNanos]: current monotonic clock value (System.nanoTime semantics; any origin works, since NTP offset math only uses relative values).
         */
        fun buildResponse(
            request: ByteArray,
            nowNanos: Long,
        ): ByteArray? {
            if (request.size < PACKET_BYTES) return null
            val version = (request[0].toInt() and 0xFF) shr 6
            if (version != 2) return null
            if (request[1] != PT_TIMESYNC_REQUEST) return null
            val length = ((request[2].toInt() and 0xFF) shl 8) or (request[3].toInt() and 0xFF)
            if (length != PACKET_BYTES / 4 - 1) return null

            val response = ByteArray(PACKET_BYTES)
            response[0] = (2 shl 6).toByte()
            response[1] = PT_TIMESYNC_RESPONSE
            response[2] = 0
            response[3] = (PACKET_BYTES / 4 - 1).toByte()
            // rtp_ts(4..8) = 0
            System.arraycopy(request, 24, response, 8, 8) // t1 = request t3 (ntp_xmit echo)
            val now = ntpFromNanos(nowNanos)
            System.arraycopy(now, 0, response, 16, 8) // t2 = receive time
            System.arraycopy(now, 0, response, 24, 8) // t3 = send time
            return response
        }

        /** Monotonic nanoseconds -> NTP 64-bit (seconds<<32 | (nano fraction * 2^32 / 1e9)), 8 bytes big-endian. */
        fun ntpFromNanos(nanos: Long): ByteArray {
            val secs = Math.floorDiv(nanos, 1_000_000_000L)
            val fracNanos = Math.floorMod(nanos, 1_000_000_000L)
            val frac = (fracNanos.toLong() shl 32) / 1_000_000_000L
            val packed = (secs shl 32) or (frac and 0xFFFF_FFFFL)
            return ByteArray(8) { index -> (packed ushr (8 * (7 - index))).toByte() }
        }
    }
}
