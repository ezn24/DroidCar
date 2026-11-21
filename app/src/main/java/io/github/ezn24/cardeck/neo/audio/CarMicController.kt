package io.github.ezn24.cardeck.neo.audio

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.ezn24.cardeck.neo.SessionController
import io.github.ezn24.cardeck.neo.airplay.AirPlayClient
import io.github.ezn24.cardeck.neo.airplay.CarMic
import io.github.ezn24.cardeck.neo.net.CarNetwork
import io.github.ezn24.cardeck.neo.shizuku.CarInputClient
import io.github.ezn24.cardeck.neo.shizuku.Shell
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Head-unit microphone uplink (2026-11-17, docs/MIC-RESEARCH.md).
 *
 * **Automatic switching**: during a session, every [WATCH_INTERVAL_MS] check who is recording (AppOps RECORD_AUDIO).
 * When another app records (recorder, call, Gemini voice input) open the car mic stream + Shizuku recording injection,
 * and tear it down [QUIET_STOP_MS] after recording stops. Tests show the injection is registered only once recording
 * starts, and the switch happens within 1s.
 *
 * **Head-unit voice button** (`requestSiri`): open the car mic first, then summon the assistant (`cmd voiceinteraction show`).
 *
 * While injecting, the phone's own microphone is dead for all apps, so the injection exists only while someone records.
 * The "Hey Google" hotword and the conversation after it use the HOTWORD/DSP path, which injection cannot reach.
 */
object CarMicController {
    private class Active(
        val client: AirPlayClient,
        val info: AirPlayClient.MicStreamInfo,
        val socket: DatagramSocket,
        val output: ParcelFileDescriptor.AutoCloseOutputStream,
        val startedAt: Long,
        /**
         * Do not wind down for "nobody recording" before this time (the voice button just summoned the assistant, which
         * has not opened the mic yet).
         */
        val graceUntil: Long,
        val reason: String,
    ) {
        @Volatile var packets = 0

        @Volatile var badPackets = 0

        @Volatile var sent = 0

        /** Far-end call voice while a call is connected (fed into this stream's downlink instead of silence). */
        @Volatile var callAudio: CallAudio? = null

        @Volatile var callFrames = 0
    }

    /** Shell-side capture of the call's far-end voice, buffered for the 20ms downlink tick (at most [MAX_CALL_BUFFER_MS]). */
    private class CallAudio(
        val pipe: ParcelFileDescriptor,
        sampleRate: Int,
    ) {
        private val buffer = ByteArray(sampleRate * 2 * MAX_CALL_BUFFER_MS / 1000)
        private var start = 0
        private var size = 0

        @Volatile var dropped = 0

        @Synchronized
        fun put(
            src: ByteArray,
            length: Int,
        ) {
            for (i in 0 until length) {
                if (size == buffer.size) {
                    // Keep the newest audio: a call must not drift behind
                    start = (start + 1) % buffer.size
                    size--
                    dropped++
                }
                buffer[(start + size) % buffer.size] = src[i]
                size++
            }
        }

        /** Fills [out] with the next frame as big-endian PCM (wire order); false while not enough audio is buffered. */
        @Synchronized
        fun takeBigEndian(out: ByteArray): Boolean {
            if (size < out.size) return false
            var i = 0
            while (i + 1 < out.size) {
                // Android captures s16le, the car stream is s16be
                out[i + 1] = buffer[start]
                out[i] = buffer[(start + 1) % buffer.size]
                start = (start + 2) % buffer.size
                size -= 2
                i += 2
            }
            return true
        }
    }

    private class Binding(
        val client: AirPlayClient,
        val sharedSecret: ByteArray,
        val ownPackage: String,
        val mediaStreamType: () -> Long?,
    )

    @Volatile
    private var active: Active? = null

    @Volatile
    private var binding: Binding? = null

    @Volatile
    private var watcher: Thread? = null

    @Volatile
    private var lastPressAt = 0L

    private val lock = Any()

    private fun log(line: String) = SessionController.appendLog(line)

    /** Called once the session is up (event channel ready): starts the recording watch for automatic switching. */
    fun attach(
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        ownPackage: String,
        mediaStreamType: () -> Long?,
    ) {
        detach("new session")
        if (sharedSecret == null) {
            log("CARMIC: plaintext session has no shared key, car microphone unavailable")
            return
        }
        val format = client.carMicFormat()
        if (format == null) {
            log("CARMIC: car /info does not declare speechRecognition PCM mono input - car microphone unavailable")
            return
        }
        log("CARMIC: car microphone available (${format.sampleRate}Hz mono), switches automatically when an app records")
        val bound = Binding(client, sharedSecret, ownPackage, mediaStreamType)
        binding = bound
        watcher =
            Thread({ watchLoop(bound) }, "car-mic-watch").apply {
                isDaemon = true
                start()
            }
    }

    /** Session end: stop the watch + wind down (any thread, idempotent). */
    fun detach(reason: String) {
        binding = null
        watcher?.interrupt()
        watcher = null
        stop(reason)
    }

    /** Head-unit voice button (background thread). Pressing again while the assistant is listening = end. */
    fun onVoiceButton() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPressAt < PRESS_DEBOUNCE_MS) return
        lastPressAt = now
        if (active != null) {
            stop("voice button pressed again")
            Shell.exec("cmd voiceinteraction hide", timeoutMs = 3_000L)
            return
        }
        val bound = binding
        val started = bound != null && start(bound, "voice button", graceMs = VOICE_BUTTON_GRACE_MS)
        if (!started) log("CARMIC: car microphone unavailable - summoning the assistant anyway (using the phone microphone)")
        val shown = Shell.exec("cmd voiceinteraction show", timeoutMs = 3_000L)
        log("CARMIC: assistant summoned ok=${shown.ok}${if (shown.ok) "" else " (${shown.output.take(80)})"}")
    }

    private fun foreignRecorders(bound: Binding): List<String> {
        val raw = runCatching { CarInputClient.service?.activeMicApps() }.getOrNull().orEmpty()
        return raw
            .split(',')
            .filter { it.isNotBlank() }
            .map { it.substringAfter(':') }
            .filter { it != bound.ownPackage && it != "com.android.shell" && it != "android" }
            .distinct()
    }

    private fun watchLoop(bound: Binding) {
        var quietSince = 0L
        var logged = ""
        var retryAfter = 0L
        try {
            while (binding === bound) {
                Thread.sleep(WATCH_INTERVAL_MS)
                val recorders = foreignRecorders(bound)
                // Outgoing calls never ring: recording in call mode is what tells the call card a call is on
                runCatching {
                    io.github.ezn24.cardeck.neo.call.CallState
                        .onRecorders(recorders)
                }
                // Apps ringing without a call notification (WeChat while its screen is up)
                runCatching {
                    val ringing =
                        CarInputClient.service
                            ?.ringingApps()
                            .orEmpty()
                            .split(',')
                            .filter { it.isNotBlank() && it != bound.ownPackage }
                    io.github.ezn24.cardeck.neo.call.CallState
                        .onRingers(ringing)
                }
                val now = SystemClock.uptimeMillis()
                val current = active
                if (recorders.isNotEmpty()) {
                    quietSince = 0L
                    if (current == null && now >= retryAfter) {
                        if (!start(bound, "recording: ${recorders.joinToString()}", graceMs = 0L)) {
                            // On failure do not retry every 400ms (floods the log and re-registers the policy): back off, then retry
                            retryAfter = now + RETRY_BACKOFF_MS
                            log("CARMIC: start failed, retrying in ${RETRY_BACKOFF_MS / 1000}s")
                        }
                    } else if (recorders.joinToString() != logged) {
                        logged = recorders.joinToString()
                    }
                } else if (current != null) {
                    if (quietSince == 0L) quietSince = now
                    when {
                        now < current.graceUntil -> Unit
                        now - quietSince >= QUIET_STOP_MS -> stop("no app recording any more")
                    }
                } else {
                    quietSince = 0L
                }
                if (current != null && now - current.startedAt >= MAX_SESSION_MS) stop("max duration exceeded")
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun start(
        bound: Binding,
        reason: String,
        graceMs: Long,
    ): Boolean {
        synchronized(lock) { if (active != null) return true }
        val client = bound.client
        val format = client.carMicFormat() ?: return false
        val service =
            CarInputClient.service ?: run {
                log("CARMIC: Shizuku service unavailable, cannot inject microphone")
                return false
            }
        val socket =
            runCatching {
                DatagramSocket(0).apply {
                    soTimeout = SOCKET_TIMEOUT_MS
                    CarNetwork.bind(this, "mic UDP")
                }
            }.getOrElse {
                log("CARMIC: cannot open UDP port: ${it.message}")
                return false
            }
        val pipe =
            runCatching { service.startMicInjection(format.sampleRate) }.getOrNull() ?: run {
                log("CARMIC: microphone injection failed to start (see /data/local/tmp/carinput_svc.log)")
                socket.close()
                return false
            }
        val info = client.runMicStream(bound.sharedSecret, format, socket.localPort)
        if (info == null) {
            runCatching { pipe.close() }
            runCatching { service.stopMicInjection() }
            socket.close()
            return false
        }
        // Same as media audio: a stream added after RECORD needs another RECORD to join the session
        client.record()
        if (bound.mediaStreamType() == CarMic.STREAM_TYPE_MAIN_AUDIO) {
            log(
                "CARMIC: note media audio is also type=100 - some head units' TEARDOWN looks only at type, " +
                    "so ending may also tear down the media stream",
            )
        }
        val now = SystemClock.uptimeMillis()
        val session =
            Active(
                client = client,
                info = info,
                socket = socket,
                output = ParcelFileDescriptor.AutoCloseOutputStream(pipe),
                startedAt = now,
                graceUntil = now + graceMs,
                reason = reason,
            )
        synchronized(lock) { active = session }
        log("CARMIC: opening car microphone ($reason)")
        val firstPacket = CountDownLatch(1)
        Thread({ receiveLoop(session, firstPacket) }, "car-mic-rx").apply {
            isDaemon = true
            start()
        }
        Thread({ callAudioLoop(session) }, "car-call-audio").apply {
            isDaemon = true
            start()
        }
        val host = client.activeHost?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        if (info.carDataPort > 0 && host != null) {
            Thread({ downlinkLoop(session, host) }, "car-mic-tx").apply {
                isDaemon = true
                start()
            }
        } else {
            log(
                "CARMIC: car gave no downlink port (dataPort=${info.carDataPort} host=${client.activeHost}) - " +
                    "not sending silence keepalive",
            )
        }
        val arrived = firstPacket.await(FIRST_PACKET_WAIT_MS, TimeUnit.MILLISECONDS)
        log(
            if (arrived) {
                "CARMIC: OK car microphone arrived (${SystemClock.uptimeMillis() - session.startedAt}ms) - injecting"
            } else {
                "CARMIC: no car microphone packet within ${FIRST_PACKET_WAIT_MS}ms - keeping injection and waiting"
            },
        )
        return true
    }

    /**
     * Downlink silence: one packet every 20ms. The receiver (confirmed on WinPlay) opens the mic only on the first downlink
     * packet of this stream; a real iPhone also sends sound on it when Siri/a call starts.
     */
    private fun downlinkLoop(
        session: Active,
        host: InetAddress,
    ) {
        val samples = session.info.format.sampleRate / 50
        val silence = ByteArray(samples * 2)
        val voice = ByteArray(samples * 2)
        var sequence = 0
        var timestamp = 0L
        var counter = 0L
        var next = SystemClock.uptimeMillis()
        try {
            while (active === session) {
                val frame =
                    if (session.callAudio?.takeBigEndian(voice) == true) {
                        session.callFrames++
                        voice
                    } else {
                        silence
                    }
                val wire = CarMic.sealDownlink(session.info.outputKey, sequence, timestamp, counter, frame)
                session.socket.send(DatagramPacket(wire, wire.size, host, session.info.carDataPort))
                session.sent++
                sequence = (sequence + 1) and 0xFFFF
                timestamp += samples
                counter++
                next += 20
                val wait = next - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (error: Exception) {
            if (active === session) log("CARMIC: downlink silence interrupted - ${error.message}")
        }
    }

    /**
     * While a call is connected (CallState from the call notification), capture its far-end voice in the shell
     * (`USAGE_VOICE_COMMUNICATION` loopback, which also takes it off the phone speaker) and feed this stream's downlink.
     * Covers VoIP apps; a cellular call's voice stays in the modem path and is not captured here.
     */
    private fun callAudioLoop(session: Active) {
        try {
            while (active === session) {
                val wanted =
                    io.github.ezn24.cardeck.neo.call.CallState.current
                        ?.phase ==
                        io.github.ezn24.cardeck.neo.call.CallState.Phase.ACTIVE
                val current = session.callAudio
                if (wanted && current == null) {
                    val pipe = runCatching { CarInputClient.service?.startCallCapture(session.info.format.sampleRate) }.getOrNull()
                    if (pipe == null) {
                        log("CARMIC: call voice capture unavailable (see /data/local/tmp/carinput_svc.log) - far end stays on the phone")
                        return
                    }
                    val audio = CallAudio(pipe, session.info.format.sampleRate)
                    session.callAudio = audio
                    log("CARMIC: call connected - far-end voice goes to the car")
                    Thread({ readCallAudio(session, audio) }, "car-call-audio-rx").apply {
                        isDaemon = true
                        start()
                    }
                } else if (!wanted && current != null) {
                    closeCallAudio(session, "call ended")
                }
                Thread.sleep(CALL_POLL_MS)
            }
        } catch (_: InterruptedException) {
        } finally {
            closeCallAudio(session, "microphone stream ended")
        }
    }

    private fun readCallAudio(
        session: Active,
        audio: CallAudio,
    ) {
        val chunk = ByteArray(4096)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(audio.pipe).use { input ->
                while (session.callAudio === audio) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (read > 0) audio.put(chunk, read)
                }
            }
        } catch (error: Exception) {
            if (session.callAudio === audio) log("CARMIC: call voice capture ended - ${error.message}")
        }
    }

    private fun closeCallAudio(
        session: Active,
        reason: String,
    ) {
        val audio = session.callAudio ?: return
        session.callAudio = null
        runCatching { audio.pipe.close() }
        runCatching { CarInputClient.service?.stopCallCapture() }
        log("CARMIC: far-end voice back on the phone ($reason) frames=${session.callFrames} dropped=${audio.dropped}B")
    }

    private fun receiveLoop(
        session: Active,
        firstPacket: CountDownLatch,
    ) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (active === session) {
                try {
                    session.socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val pcm = CarMic.openPacket(session.info.inputKey, buffer, packet.length)
                if (pcm == null) {
                    session.badPackets++
                    if (session.badPackets <= 3) {
                        log("CARMIC: undecryptable packet len=${packet.length} from=${packet.address} (#${session.badPackets})")
                    }
                    if (session.badPackets == 1) diagnoseBadPacket(session, buffer.copyOf(packet.length))
                    continue
                }
                session.packets++
                firstPacket.countDown()
                session.output.write(pcm)
            }
        } catch (error: Exception) {
            if (active === session) log("CARMIC: receive ended - ${error.message}")
        } finally {
            firstPacket.countDown()
        }
    }

    /**
     * First undecryptable packet: try several keys/layouts and log which one decrypts (WinPlay's code reads the same as ours,
     * but all authentications failed in tests: locate the difference with data, not guesses). Only key fingerprints
     * are logged, never plaintext keys.
     */
    private fun diagnoseBadPacket(
        session: Active,
        packet: ByteArray,
    ) {
        val info = session.info
        val head = packet.copyOf(minOf(16, packet.size)).joinToString(" ") { "%02x".format(it) }
        val tail = packet.copyOfRange(maxOf(0, packet.size - 8), packet.size).joinToString(" ") { "%02x".format(it) }

        fun fp(key: ByteArray) = key.copyOf(4).joinToString("") { "%02x".format(it) }
        // If a JS receiver (WinPlay/Node) reads an int64 as Number, the connection ID in the salt is rounded to the shortest double decimal
        val jsId =
            java.math
                .BigDecimal(info.streamConnectionId.toDouble().toString())
                .toPlainString()
        val secret = boundSecret()
        val candidates =
            linkedMapOf(
                "input" to info.inputKey,
                "output" to info.outputKey,
            )
        if (secret != null) {
            candidates["input-js($jsId)"] =
                io.github.ezn24.cardeck.neo.airplay.AirPlayCrypto.hkdfSha512(
                    secret,
                    "DataStream-Salt$jsId".toByteArray(),
                    "DataStream-Input-Encryption-Key".toByteArray(),
                )
        }
        val results =
            candidates.map { (name, key) ->
                val ok = CarMic.openPacket(key, packet) != null
                "$name(${fp(key)})=${if (ok) "ok" else "fail"}"
            }
        log(
            "CARMIC: diagnostics connId=${info.streamConnectionId} head=[$head] tail nonce=[$tail] " +
                results.joinToString(" "),
        )
    }

    private fun boundSecret(): ByteArray? = binding?.sharedSecret

    /** Wind down (any thread; idempotent): close send/receive, remove injection, TEARDOWN the mic stream. */
    fun stop(reason: String) {
        val session = synchronized(lock) { active.also { active = null } } ?: return
        runCatching { session.socket.close() }
        runCatching { session.output.close() }
        runCatching { CarInputClient.service?.stopMicInjection() }
        session.client.teardownMicStream(session.info)
        log(
            "CARMIC: ended ($reason) received=${session.packets} bad=${session.badPackets} downlink=${session.sent} " +
                "elapsed=${(SystemClock.uptimeMillis() - session.startedAt) / 1000}s - phone microphone restored",
        )
    }

    private const val PRESS_DEBOUNCE_MS = 1_200L
    private const val SOCKET_TIMEOUT_MS = 500
    private const val FIRST_PACKET_WAIT_MS = 1_500L
    private const val WATCH_INTERVAL_MS = 400L
    private const val QUIET_STOP_MS = 2_000L
    private const val VOICE_BUTTON_GRACE_MS = 10_000L
    private const val MAX_SESSION_MS = 30 * 60_000L
    private const val RETRY_BACKOFF_MS = 15_000L
    private const val CALL_POLL_MS = 300L
    private const val MAX_CALL_BUFFER_MS = 200
}
