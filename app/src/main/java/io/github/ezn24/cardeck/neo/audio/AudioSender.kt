package io.github.ezn24.cardeck.neo.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.ezn24.cardeck.neo.airplay.AirPlayCrypto
import io.github.ezn24.cardeck.neo.airplay.TimingServer
import io.github.ezn24.cardeck.neo.net.CarNetwork
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay main audio sender (phone -> head unit, RTP over UDP).
 *
 * Wire format (per DiPlay `AudioStream.kt` decrypt path and catplay `rtp_cipher_chacha.rs`):
 * ```
 * [RTP header 12B][PCM payload sealed with ChaCha20-Poly1305 + 16B tag][8B LE nonce]
 * ```
 *  - Encryption: ChaCha20-Poly1305; AAD = header bytes 4..12 (BE timestamp+ssrc,
 *    catplay's "modern AAD"; DiPlay decrypts over the same range)
 *  - nonce (12B) = 4 zero bytes + trailing 8 bytes (LE counter, from 0, +1 per packet) -> [AirPlayCrypto.nonce64]
 *  - RTP fields: version=2, PT=stream type (100=PCM path / 102=AAC-LC path;
 *    per catplay `payload_type = stream_type`),
 *    SSRC=0 (same as catplay), timestamp = cumulative sample frames, seq +1 per packet
 *  - **The first audio packet sets the Marker bit** (spec: the first packet after RECORD/FLUSH is marked so the
 *    receiver can split playback boundaries; catplay always sends false, but our audio stream joins
 *    only after RECORD, and the marker helps the receiver realign)
 *
 * Side channels:
 *  - **Sync packet**: [AudioSyncPacket] goes to the stream's `controlPort` every second to establish the RTP<->NTP anchor.
 *    (Without it the receiver can only guess from arrival time, and strict ones skip scheduling entirely; 2026-10-20 field test)
 *
 * Capture: `AudioPlaybackCapture` (Android 10+) grabs media/navigation audio played by other apps
 * (needs MediaProjection, the same grant as mirror mode). [SAMPLES_PER_PACKET] sample frames per packet
 * (48000Hz stereo 16-bit = 2048B payload, ~2084B packet, under the receiver's 4096B receive buffer).
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** Mute phone media volume (sound comes out of the car only). null = do nothing (tests). */
    private val context: android.content.Context? = null,
    /**
     * Capture sample rate (Hz): must match the format negotiated in SETUP (48k preferred; when the car
     * supports only 44.1k, AirPlayClient negotiates it). Default 48k.
     */
    private val sampleRate: Int = 48_000,
    /** Payload encoding: PCM (type=100 path) or AAC-LC (type=102 path). */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP stream type, for logging only (PT matches the SETUP type). */
    private val streamType: Long = 100L,
    /** Whether AAC frames get an ADTS header (see [AudioTuning.ADTS], for debugging the car's decode chain). */
    private val adtsFrames: Boolean = false,
    /**
     * Control port of this audio stream (given by the car in the SETUP reply). When >0 an [AudioSyncPacket]
     * is sent every [SYNC_INTERVAL_MILLIS] to establish the RTP<->NTP anchor; 0 = car gave none (old cars), audio only.
     */
    private val controlPort: Int = 0,
) {
    companion object {
        const val CHANNELS = 2

        /**
         * Sample frames per packet = AirPlay standard SPF 352 (catplay `MAX_FRAMES_PER_PACKET_PCM`,
         * verified on a real car). 48000/352 ~= 136 packets/s (7.33ms/packet).
         * 2026-10-17 field test: 512 frames (10.67ms/packet) produced no sound on the Audi receiver;
         * a packet length different from a real iPhone's may be dropped by receivers buffering at a fixed SPF.
         */
        const val SAMPLES_PER_PACKET = 352

        /** Payload bytes per packet (16-bit stereo). */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** Sync packet interval (spec: one per second). */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** Pacer send queue cap (packets). Steady state holds 0..2; beyond the cap the oldest is dropped. */
        const val SEND_QUEUE_CAP = 16

        /** Queue depth the pacer drains back to (extra packets per tick, each taking a future tick). */
        const val QUEUE_TARGET = 2

        /** The tick follows the measured capture rate within +-this percent. */
        const val TICK_ADJUST_PERCENT_MAX = 3L

        /** Window in which the capture feed rate is measured for the tick. */
        const val TICK_CALIB_WINDOW_NANOS = 10_000_000_000L

        /** A pacer stall longer than this skips ahead instead of sending late packets in a burst. */
        const val RESYNC_BEHIND_NANOS = 1_000_000_000L

        /** Packets to skip after a pacer stall of [behindNanos] (0 = catch up by sending). */
        internal fun resyncSkipPackets(
            behindNanos: Long,
            tickNanos: Long,
            thresholdNanos: Long,
        ): Long = if (behindNanos <= thresholdNanos || tickNanos <= 0) 0 else behindNanos / tickNanos

        /**
         * Wall time of sample [sampleTs] extrapolated from the last sent packet. The ts difference is in samples
         * (1e9 / sampleRate ns each), not packets. null = nothing sent yet.
         */
        internal fun paceAnchorNanosOf(
            sampleTs: Long,
            lastSentTs: Long,
            lastSentNanos: Long,
            sampleRate: Int,
        ): Long? =
            if (lastSentTs >= 0 && lastSentNanos > 0) {
                lastSentNanos + (sampleTs - lastSentTs) * 1_000_000_000L / sampleRate
            } else {
                null
            }

        /** Persistence key for the pre-mute media volume (restore target survives a process kill). */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"

        /** Little-endian s16 -> big-endian s16 (swap every two bytes). The CarPlay wire format needs big-endian samples. */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** Idempotency gate for stop() (several teardown paths call it). */
    private val stopped = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var syncThread: Thread? = null

    // ---- Constant-rate sending (pacer; ported from upstream shaonialife/cardeck 90a3b6b..a813244) ----
    // Capture and AAC encoding stall now and then (REMOTE_SUBMIX pipe, encoder, system load). The RTP timestamp
    // counts content, so every stall left it permanently behind wall time and the receiver's playback delay grew
    // in steps (upstream car logs: one 0.4 s hole in 5 min = +0.4 s for the rest of the drive). Capture now only
    // queues; the pacer sends one packet per tick of wall time and fills an empty queue with a silence frame, so
    // ts stays linear with wall time and a stall becomes a short gap instead of added delay.
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var pacerThread: Thread? = null

    /** Last sent packet (ts, nanoTime): fallback anchor for sync packets. */
    @Volatile private var lastSentTs = -1L

    @Volatile private var lastSentNanos = 0L

    /** Frames queued by capture (silent ones included): input of the tick calibration. */
    private val captureFedFrames =
        java.util.concurrent.atomic
            .AtomicLong(0)
    private var silenceFilledPackets = 0
    private var queueDroppedPackets = 0
    private var resyncCount = 0

    /** Encoded silence frame for the AAC path, made once by a separate encoder (the capture encoder is single-threaded). */
    private var aacSilenceFrame: ByteArray? = null

    /**
     * Capture anchors: sample number [syncAnchorTs] (same unit as RTP ts, counted from capture) arrived at
     * [syncAnchorNanos]. Sync packets anchor ts to its capture time, not its send time: anchoring to send time put
     * the queue + encoder delay (~350 ms upstream) into the receiver's playback schedule (upstream 2c59047).
     */
    @Volatile private var capturedSamples = 0L

    @Volatile private var syncAnchorTs = -1L

    @Volatile private var syncAnchorNanos = 0L

    @Volatile private var prevAnchorTs = -1L

    @Volatile private var prevAnchorNanos = 0L

    /** Sample number -> capture time (nanoTime), extrapolated from the last two capture anchors; null = none yet. */
    internal fun captureTimeOf(sampleTs: Long): Long? {
        val t1 = syncAnchorTs
        val n1 = syncAnchorNanos
        if (t1 < 0 || n1 == 0L) return null
        val t0 = prevAnchorTs
        val n0 = prevAnchorNanos
        return if (t0 >= 0 && n0 > 0 && t1 > t0) {
            n1 + (sampleTs - t1) * (n1 - n0) / (t1 - t0)
        } else {
            n1 + (sampleTs - t1) * 1_000_000_000L / sampleRate
        }
    }

    internal fun setCaptureAnchorForTest(
        prevTs: Long,
        prevNanos: Long,
        ts: Long,
        nanos: Long,
    ) {
        prevAnchorTs = prevTs
        prevAnchorNanos = prevNanos
        syncAnchorTs = ts
        syncAnchorNanos = nanos
    }

    /** AAC-LC encoder (created only for [AudioCodec.AAC_LC]). */
    private var aacEncoder: AacLcEncoder? = null

    /**
     * RTP cursors (originally locals in the capture loop). They are fields because the **sync-packet thread** must read
     * the current/next packet timestamp, or the anchor timestamp never matches packets actually sent.
     */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L

    @Volatile private var nonceCounter = 0L

    /** First-audio-packet marker (cleared once sent); the AAC self-check runs only once. */
    private var markerPending = true
    private var selfChecked = false

    /** PCM bytes per packet/frame (PCM path = one packet's payload; AAC path = one frame's input). */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** Samples advanced per packet (AAC frame 1024, PCM packet 352). */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /**
     * RTP SSRC: fixed 0 (catplay `prepare_header` uses 0 on real cars; DiPlay's receiver does not check).
     * 2026-10-17 field test: a random value gave no sound on the Audi receiver, since some head units tie the
     * SETUP-declared stream to its SSRC (non-zero is treated as an unknown stream and dropped).
     */
    private val ssrc = 0

    /** Capture+send statistics (for log rate limiting). */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var readFailures = 0

    val isRunning: Boolean get() = running.get()

    /** Phone media volume index before muting (-1 = not muted). */
    private var systemAudioInput: java.io.InputStream? = null
    private var systemAudioService: io.github.ezn24.cardeck.neo.shizuku.ICarInput? = null
    private var mutedOriginalVolume = -1
    private var silenceSince = 0L
    private var volumeRecoveryAttempted = false

    /**
     * Mute the phone's media output: once the audio channel is active sound comes only from the car (real CarPlay behavior).
     * Evidence (2026-10-10): playback capture reads from REMOTE_SUBMIX (before the volume stage),
     * so muting the phone stream volume **does not affect captured data**: phone silent, car plays normally.
     *
     * The restore value is persisted (SharedPreferences): a process kill/install update leaves no chance to restore,
     * and a later mute reading 0 would treat it as the "original volume", leaving the phone permanently muted after
     * disconnect (hit 2026-10-10).
     */
    private fun mutePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            if (mutedOriginalVolume >= 0) return
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            val current = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            // Restore target: prefer the saved value from an unrestored earlier run; otherwise the current value (trusted only if >0)
            val restoreTarget =
                when {
                    saved > 0 -> saved
                    current > 0 -> current
                    else -> -1
                }
            if (restoreTarget > 0) {
                prefs.edit().putInt(KEY_SAVED_VOLUME, restoreTarget).apply()
            }
            mutedOriginalVolume = restoreTarget
            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            // Read-back check: the ROM's audio policy may reject or bounce the change immediately (volume stayed at its
            // original value in tests), leaving the phone speaker on, so the log must make that visible
            val after = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            log(
                "AUDIO: phone media output muted (original volume ${if (restoreTarget > 0) restoreTarget else "unknown"}, " +
                    "restored on disconnect) - read-back volume=$after" +
                    if (after != 0) " (WARNING: mute did not take effect, phone will still play)" else "",
            )
        } catch (error: Exception) {
            log("AUDIO: muting phone media failed - ${error.message}")
        }
    }

    private fun restorePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val target =
                when {
                    mutedOriginalVolume > 0 -> mutedOriginalVolume
                    else -> prefs.getInt(KEY_SAVED_VOLUME, -1)
                }
            if (target > 0) {
                manager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    target,
                    0,
                )
                log("AUDIO: phone media volume restored ($target)")
            } else {
                log("AUDIO: no valid original volume recorded, phone media left as is (adjust with the volume keys)")
            }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        } catch (_: Exception) {
        }
        mutedOriginalVolume = -1
    }

    /**
     * Starts audio sending. Needs MediaProjection (the authorization source for AudioPlaybackCapture).
     * Returns whether the capture loop was entered.
     */
    fun start(projection: MediaProjection?): Boolean {
        if (running.get()) return true
        stopped.set(false)
        silenceSince = 0L
        volumeRecoveryAttempted = false
        return try {
            val client = DatagramSocket().also { CarNetwork.bind(it, "audio RTP") }
            client.connect(InetAddress.getByName(host), dataPort)
            socket = client

            // For the AAC path bring up the encoder first: if it cannot be built there is no point capturing (also
            // avoids a "packets but no sound" illusion)
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
                aacSilenceFrame = encodeSilenceFrame()
                if (aacSilenceFrame == null) log("AUDIO: no AAC silence frame - pacer waits for content during gaps")
            }

            val service = io.github.ezn24.cardeck.neo.shizuku.CarInputClient.service
            val pipe =
                runCatching { service?.startAudioCapture(sampleRate, CHANNELS) }
                    .onFailure { log("AUDIO: system output capture unavailable: ${it.message}; trying playback capture") }
                    .getOrNull()
            if (pipe != null) {
                systemAudioInput = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)
                systemAudioService = service
                log("AUDIO: using Shizuku system output capture (REMOTE_SUBMIX, microphone not captured)")
            } else {
                val captureConfig = buildCaptureConfig(requireNotNull(projection) { "system capture unavailable and no projection grant" })
                val audioFormat =
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                val recorder =
                    AudioRecord
                        .Builder()
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(framePcmBytes * 8)
                        .build()
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    log("AUDIO: AudioRecord init failed (state=${recorder.state})")
                    runCatching { recorder.release() }
                    aacEncoder?.stop()
                    aacEncoder = null
                    client.close()
                    socket = null
                    return false
                }
                record = recorder
            }
            running.set(true)
            if (systemAudioInput == null &&
                context?.let {
                    io.github.ezn24.cardeck.neo.launcher.settings.DisplayPrefs
                        .mutePhoneAudio(it)
                } == true
            ) {
                mutePhoneMedia()
            } else {
                restorePhoneMedia()
                log("AUDIO: keeping phone media volume so the system does not also mute playback capture")
            }
            log(
                "AUDIO: started (playback capture ${sampleRate}Hz stereo -> $host:$dataPort, " +
                    "path=t$streamType/${codec.name}, " +
                    if (codec == AudioCodec.AAC_LC) {
                        "1 AAC frame per packet (${AacLcEncoder.FRAME_SAMPLES} samples" +
                            (if (adtsFrames) ", ADTS wrapped)" else ", raw frames)")
                    } else {
                        "${SAMPLES_PER_PACKET} PCM frames per packet)"
                    },
            )

            // Start the sync-packet thread first: the earlier the anchor exists, the sooner the receiver can schedule playback
            startSyncLoop()

            // The pacer starts before capture: capture only queues, so there is no initial backlog
            Thread(
                {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    } catch (_: Exception) {
                    }
                    try {
                        runPacerLoop(samplesPerPacket * 1_000_000_000L / sampleRate)
                    } catch (error: Throwable) {
                        log("AUDIO: pacer exited abnormally - ${error.message}")
                    } finally {
                        stop()
                    }
                },
                "audio-pacer",
            ).apply {
                isDaemon = true
                start()
                pacerThread = this
            }

            Thread(
                {
                    try {
                        record?.startRecording()
                    } catch (error: Exception) {
                        log("AUDIO: capture start failed - ${error.message}")
                        stop()
                        return@Thread
                    }
                    runCaptureLoop()
                },
                "audio-capture",
            ).apply {
                isDaemon = true
                start()
                thread = this
            }
            true
        } catch (error: Exception) {
            log("AUDIO: start failed - ${error.message}")
            stop()
            false
        }
    }

    /**
     * Stops audio.
     *
     * There is more than one teardown path (capture thread finally, session-end callback, manual disconnect), so it is
     * called repeatedly and must be idempotent; otherwise "media volume restored" is logged twice, and if the user changed
     * the volume manually during the session, the second restore overwrites it with the stale value.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        runCatching { systemAudioService?.stopAudioCapture() }
        systemAudioService = null
        runCatching { systemAudioInput?.close() }
        systemAudioInput = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        aacEncoder?.stop()
        aacEncoder = null
        runCatching { socket?.close() }
        socket = null
        runCatching { syncSocket?.close() }
        syncSocket = null
        syncThread = null
        thread = null
        pacerThread?.let {
            java.util.concurrent.locks.LockSupport
                .unpark(it)
        }
        pacerThread = null
        sendQueue.clear()
        restorePhoneMedia()
        log("AUDIO: stopped (sent $packetsSent packets / ${bytesSent / 1024}KB)")
    }

    /** Capture config: common playback usages such as media/game/navigation (added one by one; skipped if the system rejects one). */
    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        ).forEach { usage ->
            runCatching { builder.addMatchingUsage(usage) }
        }
        return builder.build()
    }

    // ---- Audio sync packets (RTP timestamp <-> NTP, one per second) ----

    private fun startSyncLoop() {
        if (controlPort <= 0) {
            log("AUDIO: car gave no controlPort - not sending sync packets (relying on timestamps inside audio packets)")
            return
        }
        val client =
            try {
                DatagramSocket().apply {
                    CarNetwork.bind(this, "audio sync")
                    connect(InetAddress.getByName(host), controlPort)
                }
            } catch (error: Exception) {
                log("AUDIO: sync packet socket creation failed - ${error.message}")
                return
            }
        syncSocket = client
        log("AUDIO: sync packets enabled -> $host:$controlPort (one every ${SYNC_INTERVAL_MILLIS}ms, establishing the RTP<->NTP anchor)")
        val worker =
            Thread(
                {
                    var syncSequence = 0
                    var first = true
                    // The first send error ends the loop on purpose. Upstream tried retrying every second (and
                    // sending to dataPort when SETUP gives no controlPort) and reverted both on 2026-11-18: the
                    // build that played on the Audi stopped here, and the retries kept hitting a port the car had
                    // already refused.
                    try {
                        while (running.get() && !client.isClosed) {
                            val next = timestamp
                            val current = (next - samplesPerPacket).coerceAtLeast(0L)
                            val anchorNanos =
                                captureTimeOf(next)
                                    ?: paceAnchorNanosOf(next, lastSentTs, lastSentNanos, sampleRate)
                                    ?: System.nanoTime()
                            val ntp = TimingServer.ntpFromNanos(anchorNanos)
                            val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                            client.send(DatagramPacket(packet, packet.size))
                            syncSequence = (syncSequence + 1) and 0xFFFF
                            if (first) {
                                first = false
                                val lagMs = (System.nanoTime() - anchorNanos) / 1_000_000
                                log(
                                    "AUDIO: first sync packet sent (X bit=1) ts=$current->$next, anchor = capture time " +
                                        "(${lagMs}ms ago) - if the car is still silent it is not waiting on this anchor",
                                )
                            }
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        }
                    } catch (error: Exception) {
                        if (running.get()) log("AUDIO: sync packet sending stopped - ${error.message}")
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        worker.start()
        syncThread = worker
    }

    // ---- Capture loop -> packetize and send ----

    private fun runCaptureLoop() {
        val recorder = record
        val systemInput = systemAudioInput
        if (recorder == null && systemInput == null) return
        val frameBytes = framePcmBytes
        val pcm = ByteArray(frameBytes)
        // Diagnostic sampling cadence: PCM ~125 packets/s (every 20s); AAC ~47 frames/s
        val diagnosticStride = if (codec == AudioCodec.AAC_LC) 600L else 1875L
        var capturedFrames = 0L
        try {
            while (running.get()) {
                val read =
                    try {
                        if (systemInput != null) {
                            readPcmFrame(systemInput, pcm)
                        } else {
                            recorder!!.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                        }
                    } catch (error: Exception) {
                        log("AUDIO: capture read error - ${error.message}")
                        break
                    }
                if (read < 0) {
                    if (systemInput != null) break
                    readFailures++
                    if (readFailures == 1 || readFailures % 100 == 0) {
                        log("AUDIO: capture error code $read ($readFailures consecutive)")
                    }
                    if (readFailures > 500) break
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                if (read < frameBytes) continue // Partial packet (buffer tail): drop, wait for the next full one
                readFailures = 0
                // Capture anchor for sync packets: once read returns, the last sample of this frame has arrived.
                capturedSamples += read / (CHANNELS * 2)
                prevAnchorTs = syncAnchorTs
                prevAnchorNanos = syncAnchorNanos
                syncAnchorTs = capturedSamples
                syncAnchorNanos = System.nanoTime()
                val captureNow = android.os.SystemClock.elapsedRealtime()
                var silent = true
                for (sampleIndex in 0 until read) {
                    if (pcm[sampleIndex] != 0.toByte()) {
                        silent = false
                        break
                    }
                }
                if (!silent) {
                    silenceSince = 0L
                } else if (silenceSince == 0L) {
                    silenceSince = captureNow
                }
                if (!volumeRecoveryAttempted && mutedOriginalVolume > 0 && silent && captureNow - silenceSince >= 2000L) {
                    val manager = context?.getSystemService(android.media.AudioManager::class.java)
                    if (manager?.isMusicActive == true) {
                        volumeRecoveryAttempted = true
                        restorePhoneMedia()
                        log(
                            "AUDIO: playback capture stayed all-zero during playback, phone volume restored; " +
                                "if still silent, check whether the source app allows playback capture",
                        )
                    }
                }

                // Content diagnostics (2026-10-17): playback capture may return all zeros (app forbids capture / audio
                // routed to Bluetooth / nothing playing). With all zeros the car is silent while the phone still plays and the
                // user reports "sound is on the phone", so "nothing captured" must be distinguishable from "captured
                // but the car did not play".
                capturedFrames++
                // Counted per captured frame: with the pacer, `sequence` belongs to the send thread and can sit on
                // one value for several reads, which logged the same sample line three times.
                if (capturedFrames <= 3 || capturedFrames % diagnosticStride == 0L) {
                    var nonZero = 0
                    var peak = 0
                    var index = 0
                    while (index + 1 < read) {
                        val value = ((pcm[index + 1].toInt() and 0xFF) shl 8) or (pcm[index].toInt() and 0xFF)
                        val magnitude = if (value > 32767) 65536 - value else value
                        if (magnitude > peak) peak = magnitude
                        if (magnitude > 0) nonZero++
                        index += 2
                    }
                    val percent = nonZero * 100 / (read / 2).coerceAtLeast(1)
                    log(
                        "AUDIO: capture sample frame=$capturedFrames non-zero $percent% peak=$peak" +
                            if (peak == 0) {
                                " (WARNING: all zero: nothing captured - " +
                                    "the app may forbid playback capture or audio goes via Bluetooth)"
                            } else {
                                ""
                            },
                    )
                }

                // PCM path: one capture chunk = one packet; AAC path: feed one PCM frame, the encoder may emit 0~N frames
                val payloads: List<ByteArray> =
                    if (codec == AudioCodec.AAC_LC) {
                        val encoder = aacEncoder ?: break
                        encoder.encode(pcm, frameBytes)
                    } else {
                        listOf(pcm)
                    }
                if (payloads.isEmpty()) continue

                // One-time self-check: decode our own encoder output to prove the bitstream is valid and non-silent.
                // This is the key evidence for "car is silent": if the self-decoded peak is 0 the encoder is at fault;
                // if self-decode has sound while the car is silent, the fault is on the car side (session/volume/routing).
                if (!selfChecked) {
                    selfChecked = true
                    verifyEncodedFrame(payloads.first())
                }

                // Queue only; the pacer sends on wall-time ticks. A full queue drops the oldest (a content hole the
                // car conceals) and means capture runs faster than the tick has adapted to so far.
                for (payload in payloads) {
                    // The PCM buffer is reused for the next read, so the queued copy must be its own array
                    val queued = if (codec == AudioCodec.AAC_LC) payload else payload.copyOf()
                    if (sendQueue.size >= SEND_QUEUE_CAP) {
                        sendQueue.poll()
                        queueDroppedPackets++
                        if (queueDroppedPackets == 1 || queueDroppedPackets % 50 == 0) {
                            log("AUDIO: send queue full, dropped the oldest packet (total $queueDroppedPackets)")
                        }
                    }
                    sendQueue.offer(queued)
                    captureFedFrames.incrementAndGet()
                }
            }
        } catch (error: Throwable) {
            // An uncaught exception on a background thread kills the whole process: audio errors must never propagate
            log("AUDIO: capture thread exited abnormally - ${error.message}")
        } finally {
            stop()
        }
    }

    /** One encoded AAC frame of silence from a throwaway encoder (it needs 1-2 frames of lookahead). */
    private fun encodeSilenceFrame(): ByteArray? {
        val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = {})
        if (!encoder.start()) return null
        return try {
            val zeros = ByteArray(framePcmBytes)
            var frame: ByteArray? = null
            for (attempt in 0 until 8) {
                frame = encoder.encode(zeros, zeros.size).lastOrNull() ?: frame
                if (frame != null && attempt >= 2) break
            }
            frame
        } catch (_: Exception) {
            null
        } finally {
            encoder.stop()
        }
    }

    /** Filler for an empty queue: zero PCM, or the AAC silence frame (null = none, wait for content). */
    private fun silencePayload(): ByteArray? = if (codec == AudioCodec.AAC_LC) aacSilenceFrame else ByteArray(framePcmBytes)

    /**
     * Sends one packet per tick of wall time: queued content first, silence when the queue is empty. A pacer stall
     * over [RESYNC_BEHIND_NANOS] skips ahead (late packets would be discarded by the receiver anyway); shorter ones
     * catch up by at most 12 packets. Extra packets that drain a deep queue take future ticks, so the send rate stays
     * equal to the tick (draining "for free" pushed ts ahead of wall time and grew the car's buffer +15 ms/s upstream).
     * The tick follows the measured capture rate (REMOTE_SUBMIX runs ~1.4% fast upstream), clamped to +-3%.
     */
    private fun runPacerLoop(tickNanos: Long) {
        var nextTick = -1L
        var lastLogAt = System.nanoTime()
        var effectiveTick = tickNanos
        var calibStartNanos = 0L
        var calibFedFrames = 0L
        var sentInLogWindow = 0L
        while (running.get()) {
            if (nextTick == -1L) {
                // The schedule starts with the first packet that can be sent
                val first = sendQueue.poll() ?: silencePayload()
                if (first == null) {
                    java.util.concurrent.locks.LockSupport
                        .parkNanos(2_000_000)
                    continue
                }
                if (!sendPayload(first)) break
                nextTick = System.nanoTime() + effectiveTick
                calibStartNanos = nextTick
                calibFedFrames = captureFedFrames.get()
                sentInLogWindow = 1
                continue
            }
            val now = System.nanoTime()
            if (now < nextTick) {
                java.util.concurrent.locks.LockSupport
                    .parkNanos(nextTick - now)
                continue
            }
            val behind = now - nextTick
            val skip = resyncSkipPackets(behind, effectiveTick, RESYNC_BEHIND_NANOS)
            if (skip > 0) {
                sequence = (sequence + skip.toInt()) and 0xFFFF
                timestamp += skip * samplesPerPacket
                nonceCounter += skip
                nextTick += skip * effectiveTick
                resyncCount++
                log("AUDIO: pacer stalled ${behind / 1_000_000}ms - skipped $skip packets (resync #$resyncCount)")
            }
            var failed = false
            var burst = 0
            while (running.get() && System.nanoTime() >= nextTick && burst < 12) {
                val payload = sendQueue.poll() ?: silencePayload()?.also { silenceFilledPackets++ }
                if (payload == null) {
                    // No silence frame (the AAC encoder could not make one): wait for content without advancing ts
                    java.util.concurrent.locks.LockSupport
                        .parkNanos(2_000_000)
                    break
                }
                if (!sendPayload(payload)) {
                    failed = true
                    break
                }
                nextTick += effectiveTick
                burst++
                sentInLogWindow++
            }
            if (failed || !running.get()) break
            var drained = 0
            while (running.get() && sendQueue.size > QUEUE_TARGET && drained < 2) {
                val extra = sendQueue.poll() ?: break
                if (!sendPayload(extra)) {
                    failed = true
                    break
                }
                nextTick += effectiveTick
                drained++
                sentInLogWindow++
            }
            if (failed || !running.get()) break
            val after = System.nanoTime()
            if (calibStartNanos > 0 && after - calibStartNanos >= TICK_CALIB_WINDOW_NANOS) {
                val fedNow = captureFedFrames.get()
                val fed = fedNow - calibFedFrames
                if (fed > 0) {
                    val lo = tickNanos * (100 - TICK_ADJUST_PERCENT_MAX) / 100
                    val hi = tickNanos * (100 + TICK_ADJUST_PERCENT_MAX) / 100
                    effectiveTick = ((after - calibStartNanos) / fed).coerceIn(lo, hi)
                }
                calibStartNanos = after
                calibFedFrames = fedNow
            }
            if (after - lastLogAt >= 30_000_000_000L) {
                val ppsX10 = sentInLogWindow * 10_000_000_000L / (after - lastLogAt)
                lastLogAt = after
                sentInLogWindow = 0
                log(
                    "AUDIO: streaming (total $packetsSent packets, ${bytesSent / 1024}KB, seq=$sequence ts=$timestamp, " +
                        "queue=${sendQueue.size} silence-filled=$silenceFilledPackets dropped=$queueDroppedPackets " +
                        "rate=${ppsX10 / 10}.${ppsX10 % 10}pps tick=${effectiveTick / 1000}us)",
                )
            }
        }
    }

    /** Packetize + send one packet (updates the seq/ts/nonce cursors). Returns false if the link is down. */
    private fun sendPayload(payload: ByteArray): Boolean {
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        val sentTs = timestamp
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            bytesSent += wire.size
            lastSentTs = sentTs
            lastSentNanos = System.nanoTime()
            true
        } catch (error: IOException) {
            log("AUDIO: UDP send failed - ${error.message}")
            false
        }
    }

    /** Decode the first frame back to PCM with the platform decoder and record the peak (background thread, does not block streaming). */
    private fun verifyEncodedFrame(frame: ByteArray) {
        val payload = frame.copyOf()
        Thread(
            {
                try {
                    val result = AacLcEncoder.decodePeak(payload, sampleRate, CHANNELS)
                    if (result == null) {
                        log(
                            "AUDIO: self-check = local decoder produced no samples (first frame ${payload.size}B) - " +
                                "cannot refute, continuing to stream",
                        )
                        return@Thread
                    }
                    val (peak, samples) = result
                    log(
                        "AUDIO: self-check = first frame ${payload.size}B decoded locally to $samples samples, peak $peak " +
                            if (peak > 0) {
                                "(OK: bitstream valid and non-silent: if the car is silent the fault is not encoding/encryption)"
                            } else {
                                "(WARNING: decoded to all zeros: the AAC encoder is emitting silence)"
                            },
                    )
                } catch (error: Exception) {
                    log("AUDIO: self-check decode error - ${error.message}")
                }
            },
            "audio-selfcheck",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Builds one RTP packet: plaintext PCM -> ChaCha20 seal -> [header][ciphertext+tag][nonce8]. (internal: tested directly) */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD = header's timestamp+ssrc range (bytes 4..12), same as catplay "modern AAD" / DiPlay decrypt
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // Key point: the CarPlay wire format's LPCM is **big-endian** s16: the receiver does
        // byteSwapS16 back to little-endian before feeding AudioTrack (DiPlay AndroidMediaSink.kt:1687).
        // Android captures native little-endian, so we must swap here; skipping it yields "hiss" (2026-10-10 field test).
        //
        // The AAC path does not swap: an AAC bitstream is a byte stream, not 16-bit samples, and swapping
        // scrambles it (car decode fails -> packets but no sound).
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // Trailing 8-byte little-endian nonce (equals the low 8 bytes of AirPlayCrypto.nonce64)
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT follows stream type (catplay `payload_type = stream_type`): 100=PCM path, 102=AAC path
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
