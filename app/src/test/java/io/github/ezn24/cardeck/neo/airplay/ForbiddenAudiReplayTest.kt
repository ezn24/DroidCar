package io.github.ezn24.cardeck.neo.airplay

import io.github.ezn24.cardeck.neo.iap2.Iap2SenderSession
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * "Audi MMI 3315_5GHz always rejects the first connection" replay test (2026-10-21 real-device sysdiagnose,
 * docs/PROTOCOL-NOTES.md section 13a): within its own readiness window the head unit answers every pairing request with 403
 * (a real iPhone is also 403'd in the auth phase after pair-verify passes; 18~20s later the head unit
 * re-invites automatically and the second attempt succeeds).
 *
 * The scripted receiver = a loopback ServerSocket that always answers every pair-setup / pair-verify request
 * with `HTTP/1.1 403 Forbidden`, driving the real [AirPlayClient] pairing loop, asserting:
 *  1. `pairingForbidden` is set (a 403 in the verify/auth phase must also mark it -- the old implementation only recognised
 *     the pair-setup phase, so the controller wrongly took the "drop BT and retry" and "plaintext downgrade" detours);
 *  2. Backoff cadence: after a 403 the next attempt starts only after >= 4.5s (5s backoff, see
 *     [pairingRetryBackoffMs]), no longer hammering at 500ms which could reset the head unit's readiness state machine;
 *  3. 2~4 attempt rounds within the 12s window, producing no keys (a 403 head unit has no path to success).
 */
class ForbiddenAudiReplayTest {
    @Test
    fun `first-connect 403 window - mark forbidden and retry with 5s backoff`() {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val requestAtNs = ArrayList<Long>()
        val lock = Any()
        val acceptor =
            thread(isDaemon = true, name = "forbidden-audi") {
                while (!server.isClosed) {
                    val sock =
                        try {
                            server.accept()
                        } catch (_: Exception) {
                            return@thread
                        }
                    sock.use { s ->
                        s.tcpNoDelay = true
                        s.soTimeout = 4_000
                        val input = BufferedInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        try {
                            while (true) {
                                val head = readHead(input) ?: break // Connection closed: back to accept and wait for the next one
                                val length =
                                    Regex("Content-Length: *(\\d+)", RegexOption.IGNORE_CASE)
                                        .find(head)
                                        ?.groupValues
                                        ?.get(1)
                                        ?.toIntOrNull() ?: 0
                                if (length > 0) skipFully(input, length)
                                synchronized(lock) { requestAtNs.add(System.nanoTime()) }
                                // Must echo CSeq: the client dispatches responses by CSeq, a missing one is treated as a 10s timeout
                                val cseq =
                                    Regex("CSeq: *(\\d+)", RegexOption.IGNORE_CASE)
                                        .find(head)
                                        ?.groupValues
                                        ?.get(1) ?: "1"
                                out.write(
                                    "HTTP/1.1 403 Forbidden\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n"
                                        .toByteArray(Charsets.ISO_8859_1),
                                )
                                out.flush()
                            }
                        } catch (_: Exception) {
                            // The client disconnects and opens a new connection (403 fast-path behavior); keep serving
                        }
                    }
                }
            }

        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "Audi_MMI_3315_5GHz",
                passphrase = "",
                channel = 0,
                securityType = 0,
                airPlayPort = server.localPort,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("127.0.0.1"),
            )
        val client = AirPlayClient(endpoint, log = {}, context = null)
        val keys =
            try {
                client.runPairing(windowMillis = 12_000)
            } finally {
                runCatching { server.close() }
                runCatching { acceptor.join(1_000) }
            }

        assertNull("No key can be established within the 403 window", keys)
        assertTrue("A 403 in the verify/auth phase must also set pairingForbidden", client.pairingForbidden)
        assertTrue(client.attemptedPairing)

        val stamps =
            synchronized(lock) { requestAtNs.sorted() }.map { it / 1_000_000 }
        assertTrue("Too few requests (${stamps.size}) -- the loop did not really cycle", stamps.size >= 4)
        // Round clustering: setup->verify requests within a round are <3s apart, rounds are >= 4.5s apart (5s backoff)
        val cycles = ArrayList<Long>()
        for (i in stamps.indices) {
            if (i == 0 || stamps[i] - stamps[i - 1] >= 3_000) cycles.add(stamps[i])
        }
        assertTrue("Rounds ${cycles.size} (expected 2~4)", cycles.size in 2..4)
        val gaps = cycles.zipWithNext { a, b -> b - a }
        assertTrue("Backoff between rounds too short ($gaps ms, expected >= 4500)", gaps.all { it >= 4_500 })
    }

    /** Reads up to the end of the HTTP header (\r\n\r\n); returns null if the connection is closed. */
    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        val chunk = ByteArray(1)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) return if (buf.isEmpty()) null else buf.toString()
            buf.append(chunk[0].toInt().toChar())
            if (buf.length >= 4 && buf.endsWith("\r\n\r\n")) return buf.toString()
            if (buf.length > 16_384) return buf.toString()
        }
    }

    private fun skipFully(
        input: InputStream,
        count: Int,
    ) {
        var left = count
        val buf = ByteArray(4096)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size))
            if (n < 0) return
            left -= n
        }
    }
}
