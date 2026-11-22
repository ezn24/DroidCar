package io.github.ezn24.droidcar.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import io.github.ezn24.droidcar.airplay.AirPlayCrypto
import io.github.ezn24.droidcar.airplay.BinaryPlist
import io.github.ezn24.droidcar.airplay.StreamFrameCipher
import io.github.ezn24.droidcar.bt.AdapterMode
import io.github.ezn24.droidcar.net.CarNetwork
import io.github.ezn24.droidcar.net.TcpLiveness
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay main-screen video sender (Phase 2-C2).
 *
 * Wire format (per DiPlay ScreenStream.kt):
 *  - Every message = 128-byte header + body; header = [0..3] u32LE body length, [4] opcode,
 *    [8..15] NTP 32.32 LE send time; the whole header is the frame-encryption AAD, the other bytes are zero
 *  - opcode 1 = VideoConfig: plaintext avcC record (recognized by looksLikeAvcC)
 *  - opcode 0 = VideoFrame: ChaCha20-Poly1305 sealed "NAL sequence with 4-byte BE length prefixes",
 *    nonce = nonce64(frame counter), the counter increments from 0 in frame order
 *
 * Video source: MediaCodec Surface input (H.264 hardware encode), content rendered by the picture source
 * (the input Surface of DualDisplayComposer in invisible-display mode).
 */
class VideoSender(
    private val host: String,
    private val dataPort: Int,
    /**
     * Frame encryption (the three forms of catplay `ScreenFrameCodec`):
     *  - ChaCha: paired (stream key derived by pair-verify)
     *  - AesCtr: unpaired session (`et=NONE`, AES-128-CTR + all-zero session key)
     */
    private val frameCipher: StreamFrameCipher,
    /**
     * H.264 4:2:0 requires even dimensions: an odd height puts the encoder into an error state on the first input frame
     * (hit with 125% scaling).
     */
    width: Int,
    height: Int,
    /** Target frame rate (30/60): encoder metadata + 60fps raises the bitrate; the actual pace is driven by the composer. */
    private val targetFps: Int = 30,
    private val log: (String) -> Unit,
    /** Called when streaming ends naturally (peer disconnect etc.): the signal that the CarPlay session really ended. */
    /** Stream-ended callback; parameter = whether the peer (car/network) disconnected, false means a local [stop]. */
    private val onEnded: ((byPeer: Boolean) -> Unit)? = null,
    /**
     * Whether the in-stream keepAlive frame carries a stats body (the car's `/info` `keepAliveSendStatsAsBody`).
     * Audi MMI 3315 declares true -> opcode=5 + bplist stats body; otherwise opcode=2 empty frame.
     * A real iPhone/catplay **still sends one every second** while video streams: it is not a backup for video traffic
     * but an independent liveness signal on the receiver's screen (2026-10-19 Audi 3315, two rounds: without it
     * the whole round was reset 10.2s after RECORD while video/feedback/event channels were all healthy).
     */
    private val keepAliveWithStats: Boolean = false,
    /**
     * "Clarity first": request H.264 High profile (falls back automatically if the encoder refuses, see [start]).
     * Adapter mode is always Baseline.
     */
    private val clarityFirst: Boolean = false,
    /** For logs: resolution scale percentage of this session. */
    private val scalePercent: Int = 100,
    /**
     * The receiver declared and accepted HEVC (SetupInfo.hevc) and the user enabled HEVC: encode with H.265. Adapter
     * mode is always H.264.
     */
    hevc: Boolean = false,
) {
    /** HEVC actually in use (becomes false when the encoder is unavailable and we fall back to H.264). */
    @Volatile
    private var useHevc = hevc && !AdapterMode.active

    private val width: Int = (width / 2) * 2
    private val height: Int = (height / 2) * 2
    private var socket: Socket? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private var frameCounter = 0L
    private var configSent = false

    /** Wireless-adapter session (decided per connection before the sender is built). */
    private val adapterMode = AdapterMode.active

    /** Adapter mode: holds old-GOP frames until the re-prime IDR (see [reprime]). */
    private val rePrimeGate = RePrimeGate()

    /** Last VideoConfig message (header + avcC), resent in front of a re-prime IDR. */
    @Volatile
    private var lastConfigMessage: ByteArray? = null
    private var rePrimeDropped = 0

    /** AES-CTR keystream instance: state must continue across frames (a fresh one per frame makes the peer decrypt garbage). */
    private val aesStream: AirPlayCrypto.Aes128CtrStream? =
        (frameCipher as? StreamFrameCipher.AesCtr)?.let { AirPlayCrypto.Aes128CtrStream(it.key, it.iv) }

    /** Time of the last in-stream keepAlive (uptimeMillis; 0 = never sent). */
    private var lastKeepAliveAt = 0L

    /** Frames/bytes between two keepAlives (for the stats body, same meaning as catplay ScreenSenderStats). */
    private var framesSinceKeepAlive = 0
    private var bytesSinceKeepAlive = 0L
    private var keepAliveCount = 0L

    /** Log statistics window (accumulated between two keepAlive log lines). */
    private var statWindowFrames = 0L
    private var statWindowBytes = 0L
    private var statWindowStartAt = 0L

    @Volatile
    private var framesSent = 0L
    private var keyFramesSent = 0L

    /** Whether streaming is active (used by the silent log-noise guard: stays false when start fails). */
    val isRunning: Boolean get() = running.get()

    /** Total frames sent (hero-card live frame rate = difference over a 1s poll). */
    val framesSentTotal: Long get() = framesSent

    /**
     * Establishes the screen data-port TCP connection early (idempotent).
     *
     * Head-unit watchdog (Audi field test 2026-10-17): after the screen-stream SETUP the data port must be connected within ~2s,
     * otherwise the control channel is reset and the whole session torn down. The audio SETUP itself takes ~2.1s; if it came
     * first the port connection would necessarily be late, so call this before negotiating audio. catplay does the same
     * (finish_tx connects the stream right after the video SETUP, then negotiates audio).
     */
    fun openDataPort(): Boolean {
        val existing = socket
        if (existing != null && !existing.isClosed) return true
        return try {
            val client = Socket().also { CarNetwork.bind(it, "video data port") }
            // Cap the send buffer at 256KB (it must be set before connect to affect window negotiation): the kernel default auto-tunes
            // to several MB, so when WiFi throughput dips frames queue in the buffer, 1MB at 12Mbps is ~0.7s of latency and
            // then floods out on recovery ("latency + occasional stutter"). With a small buffer writes block early, the composer
            // skips frames the encoder cannot keep up with and the picture stays fresh: for interaction, dropping
            // frames beats building a backlog.
            runCatching { client.sendBufferSize = SEND_BUFFER_BYTES }
            client.connect(InetSocketAddress(host, dataPort), 5_000)
            client.tcpNoDelay = true
            // Streaming is a long-lived connection: when the car shuts down or the phone leaves the hotspot the link
            // often sends no RST, and writes
            // block on a full window, stalling the encoder thread (seen as "picture stuck on the last frame").
            TcpLiveness.configure(client, "video data port", log)
            socket = client
            log(
                "VIDEO: data port connected $host:$dataPort " +
                    "sndbuf=${runCatching { client.sendBufferSize }.getOrNull()} (encoder starts after RECORD)",
            )
            true
        } catch (error: Exception) {
            log("VIDEO: data port connection failed - ${error.message}")
            false
        }
    }

    /**
     * Starts encoding and streaming. The encoder input Surface is handed to the caller via [onSurfaceReady] for rendering
     * (in invisible-display mode filled by the composition canvas of DualDisplayComposer).
     */
    fun start(onSurfaceReady: (Surface) -> Unit): Boolean {
        if (running.get()) return true
        return try {
            if (!openDataPort()) {
                stop()
                return false
            }
            val bitrate = encoderBitrate(width, height, targetFps)
            log("VIDEO: starting encoder ${width}x$height@$targetFps ${bitrate / 1_000_000}Mbps (port connected)")

            // Tried in order: [High (clarity first) + level 4.0] -> [level 4.0] -> [fully automatic].
            // Level 4.0 is fixed (SPS level_idc=40, the same stream shape as a real iPhone): when not fixed the encoder picks from
            // "resolution + frame rate", and at 30fps 1412x654 lands in level 3.2, which the Audi DELPHI decoder silently does not
            // render (2026-10-22 Audi 0628 real-device log). Some encoders do not accept profile/level
            // (configure throws = no video at all), hence the stepwise degradation.
            val attempts =
                buildList {
                    if (useHevc) {
                        add(EncoderAttempt(high = false, level4 = true, hevc = true))
                        add(EncoderAttempt(high = false, level4 = false, hevc = true))
                    }
                    if (clarityFirst && !adapterMode) add(EncoderAttempt(high = true, level4 = true))
                    add(EncoderAttempt(high = false, level4 = true))
                    add(EncoderAttempt(high = false, level4 = false))
                }
            var codec: MediaCodec? = null
            var chosen: EncoderAttempt? = null
            var chosenCbr = false
            for (attempt in attempts) {
                val candidate = runCatching { MediaCodec.createEncoderByType(attempt.mime) }.getOrNull()
                if (candidate == null) {
                    log("VIDEO: no ${attempt.mime} encoder - degrading")
                    continue
                }
                // CBR: Qualcomm VBR measured a 10-second average 75~80% above target (H.264 target 12 -> 21Mbps, HEVC 7.8 -> 14),
                // and the peaks made the receiver back-pressure and stutter. The user's trade-off: "stable, no
                // stutter" over clarity while dragging (2026-11-20).
                // Static pictures are held down by the QP floor, so CBR does not re-encode to fill the bitrate. Falls
                // back to VBR when the encoder lacks CBR.
                val cbr =
                    attempt.level4 &&
                        runCatching {
                            candidate.codecInfo
                                .getCapabilitiesForType(attempt.mime)
                                .encoderCapabilities
                                .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                        }.getOrDefault(false)
                try {
                    candidate.configure(
                        buildEncoderFormat(attempt.bitrateFrom(bitrate), attempt, cbr),
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                    codec = candidate
                    chosen = attempt
                    chosenCbr = cbr
                    break
                } catch (rejected: Exception) {
                    runCatching { candidate.release() }
                    log("VIDEO: encoder rejected ${attempt.label} (${rejected.message}) - degrading and retrying")
                }
            }
            if (codec == null || chosen == null) error("every encoder configuration was rejected")
            if (useHevc && !chosen.hevc) {
                log("VIDEO: HEVC encoder unavailable - falling back to H.264 for this session")
                useHevc = false
            }
            log(
                "VIDEO: quality scale=$scalePercent% canvas=${width}x$height@$targetFps " +
                    "bitrate=${"%.1f".format(chosen.bitrateFrom(bitrate) / 1e6)}Mbps " +
                    "requested=${chosen.label}${if (chosen.level4) " QPmin=$QP_I_MIN/$QP_P_MIN" else ""} " +
                    "rate-control=${if (chosenCbr) "CBR" else "default(VBR)"} " +
                    "encoder=${codec.name} clarityFirst=$clarityFirst" +
                    " (actual profile/level: see the VideoConfig line: 66=Baseline 77=Main 100=High)",
            )
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            inputSurface = surface
            running.set(true)
            lastKeepAliveAt = android.os.SystemClock.uptimeMillis()
            statWindowStartAt = lastKeepAliveAt

            onSurfaceReady(surface)

            Thread({ drainEncoder() }, "video-drain").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("VIDEO: start failed - ${error.message}")
            stop()
            false
        }
    }

    @Volatile
    private var stopRequested = false

    fun stop() {
        stopRequested = true
        stopInternal()
    }

    private fun stopInternal() {
        running.set(false)
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { socket?.close() }
        socket = null
    }

    /**
     * Encodes a key frame immediately (the car's `/command` `forceKeyFrame` request).
     *
     * When the car cannot decode the picture it asks for I-frames repeatedly; we send one periodically, but a **key-
     * frame request must be answered immediately**,
     * otherwise the car may sit in "waiting for the next I-frame" and show "connected but no picture"
     * (BMW iD9 2026-10-20: one forceKeyFrame arrived right after the session started).
     */
    fun requestKeyFrame(): Boolean {
        if (adapterMode && lastConfigMessage != null) return reprime("forceKeyFrame")
        val codec = encoder ?: return false
        return try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            true
        } catch (error: Exception) {
            log("VIDEO: key frame request failed - ${error.message}")
            false
        }
    }

    /**
     * Re-prime the receiver's decoder: request an IDR, hold back the old GOP until it arrives,
     * and resend VideoConfig right in front of it (adapter `requestUI`/`forceKeyFrame`; the car
     * tears its decoder down while its native UI owns the screen).
     */
    fun reprime(reason: String): Boolean {
        val codec = encoder ?: return false
        if (lastConfigMessage == null) return false
        return try {
            rePrimeGate.request(android.os.SystemClock.uptimeMillis())
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            log("VIDEO: re-prime requested ($reason) - next frames start at a fresh IDR after VideoConfig")
            true
        } catch (error: Exception) {
            log("VIDEO: re-prime request failed - ${error.message}")
            false
        }
    }

    // ---- Encoder output -> protocol framing and sending ----

    private fun drainEncoder() {
        val codec = encoder ?: return
        // Buffered wrapper: the 128B header and the frame body go out as one send (they used to be two TCP segments under tcpNoDelay);
        // every frame ends with a flush anyway, so no extra latency is added
        val output = java.io.BufferedOutputStream(socket?.getOutputStream() ?: return, 64 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index =
                    try {
                        codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (error: Exception) {
                        // Encoder in an error state (e.g. illegal-size first frame): must leave a trace; a silent break once made
                        // "zero-frame streaming, disconnect after 4 seconds" impossible to diagnose (the receiver only saw peer EOF)
                        log("VIDEO: encoder dequeue error, stopping stream - ${error.message}")
                        break
                    }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        maybeSendKeepAlive(output)
                        continue
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        val flags = bufferInfo.flags
                        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sendVideoConfig(buffer, bufferInfo.size, output)
                        } else if (bufferInfo.size > 0) {
                            sendVideoFrame(buffer, bufferInfo.size, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, output)
                        }
                        codec.releaseOutputBuffer(index, false)
                        maybeSendKeepAlive(output)
                    }
                }
            }
        } catch (error: Exception) {
            // Video data port disconnected (e.g. the receiver screen turned off and the session was wound up): only
            // end streaming, never propagate;
            // an uncaught exception on a background thread kills the whole process (once made the app vanish after screen lock)
            log("VIDEO: stream aborted - ${error.message} (waiting for the receiver to recover, then reconnect)")
        } finally {
            val byPeer = !stopRequested
            stopInternal()
            // Real session-end signal (lifts silent log-noise suppression / state cleanup)
            runCatching { onEnded?.invoke(byPeer) }
        }
    }

    private class EncoderAttempt(
        val high: Boolean,
        val level4: Boolean,
        val hevc: Boolean = false,
    ) {
        val mime: String get() = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        /** Target bitrate of this plan: HEVC is scaled by [hevcBitrate]. */
        fun bitrateFrom(h264Bitrate: Int): Int = if (hevc) hevcBitrate(h264Bitrate) else h264Bitrate

        val label: String
            get() =
                when {
                    hevc -> if (level4) "HEVC Main" else "HEVC auto"
                    high -> "High+L4.0"
                    level4 -> "L4.0"
                    else -> "auto"
                }
    }

    private fun buildEncoderFormat(
        bitrate: Int,
        attempt: EncoderAttempt,
        cbr: Boolean = false,
    ): MediaFormat =
        MediaFormat.createVideoFormat(attempt.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
            when {
                // HEVC: Main profile, level left to the encoder (the H.264 level 4.0 convention does not apply)
                attempt.hevc -> setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                // The smartBox's hardware decoder showed black for a non-Baseline stream and
                // displayed Baseline (profile 66) in the reference field run; ask for it explicitly.
                adapterMode -> setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                attempt.high -> setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
            if (attempt.level4) {
                if (!attempt.hevc) setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                // QP floor: once quality is good enough, do not spend more bits. 2026-11-20 WinPlay: with only the Google Maps
                // location dot moving on the dashboard the Qualcomm encoder still used the full 16.6Mbps budget
                // (16~30KB per frame, re-refining the whole
                // screen), while a real iPhone spends ~3KB per frame on the same animation. A floor of 20/22 keeps
                // text sharp; encoders that do not support it ignore it.
                // The "fully automatic" plan omits it, kept as the way out if some encoder rejects configure because of it.
                setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, QP_I_MIN)
                setInteger(MediaFormat.KEY_VIDEO_QP_P_MIN, QP_P_MIN)
                setInteger(MediaFormat.KEY_VIDEO_QP_B_MIN, QP_P_MIN)
                // Low-latency hints: realtime priority, encoder buffers at most 1 frame (no multi-frame lookahead),
                // compute supplied for the target frame rate.
                // All are hints; encoders that do not support them ignore them.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps)
            }
        }

    /**
     * In-stream keepAlive frame (catplay `ScreenTransmitSession::create_keep_alive`, 1s interval).
     *
     * One of three independent liveness signals alongside the UDP keepAlive / RTSP feedback; for the receiver (Audi 3315)
     * video frames alone are not enough: without this the whole session is reset about 10s after RECORD (2026-10-19, two rounds
     * of field tests). The frame is **plaintext** (catplay encrypts only opcode=VideoFrame), so it consumes no ChaCha
     * frame counter; with a stats body the opcode is 5 and the body length is written into the last 4 bytes of header params[14]
     * (f32 LE), byte-for-byte identical to catplay.
     */
    private fun maybeSendKeepAlive(output: java.io.OutputStream) {
        if (!running.get()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastKeepAliveAt < KEEPALIVE_INTERVAL_MS) return
        val elapsedNanos = (now - lastKeepAliveAt).coerceAtLeast(1L) * 1_000_000.0
        val frames = framesSinceKeepAlive
        val bytes = bytesSinceKeepAlive
        val framesPerSecond = (frames * 1_000_000_000.0 / elapsedNanos).toInt()
        val bytesPerSecond = bytes * 1_000_000_000.0 / elapsedNanos
        framesSinceKeepAlive = 0
        bytesSinceKeepAlive = 0
        lastKeepAliveAt = now
        keepAliveCount++
        val frame = buildKeepAliveFrame(keepAliveWithStats, frames, bytesPerSecond, framesPerSecond)
        output.write(frame)
        output.flush()
        // Logs use the window average "since the previous log line" (~1s for the first 3 lines, ~10s afterwards) rather than the last
        // second's instantaneous value: sampling at slightly different moments makes two runs of the same operation
        // (e.g. HEVC vs H.264) incomparable.
        statWindowFrames += frames
        statWindowBytes += bytes
        if (keepAliveCount <= 3 || keepAliveCount % 10 == 0L) {
            val windowMs = (now - statWindowStartAt).coerceAtLeast(1L)
            log(
                "VIDEO: keepAlive #$keepAliveCount " +
                    "opcode=${if (keepAliveWithStats) OP_KEEP_ALIVE_WITH_BODY else OP_KEEP_ALIVE} " +
                    "body=${frame.size - HEADER_LEN}B " +
                    "avg over last ${"%.0f".format(windowMs / 1000.0)}s fps=${statWindowFrames * 1000 / windowMs} " +
                    "bitrate=${"%.2f".format(statWindowBytes * 8.0 / windowMs / 1000.0)}Mbps",
            )
            statWindowFrames = 0
            statWindowBytes = 0
            statWindowStartAt = now
        }
    }

    private fun sendVideoConfig(
        buffer: java.nio.ByteBuffer,
        size: Int,
        output: java.io.OutputStream,
    ) {
        val annexB = ByteArray(size)
        buffer.get(annexB)
        val nalUnits = splitAnnexB(annexB)
        if (useHevc) {
            sendHevcConfig(nalUnits, output)
            return
        }
        val rawSps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (rawSps == null || pps == null) {
            log("VIDEO: csd lacks SPS/PPS (${nalUnits.size} NALs), skipping config")
            return
        }
        // Patch level_idc to 4.0 (the SPS embedded in key frames is patched too, see sendVideoFrame)
        val sps = patchSpsLevel(rawSps)
        val avcc = ByteArrayOutputStream(8 + sps.size + pps.size)
        avcc.write(1)
        avcc.write(sps[1].toInt()) // profile
        avcc.write(sps[2].toInt()) // compat
        avcc.write(sps[3].toInt()) // level
        avcc.write(0xFF) // 6 reserved bits + 2-bit length size - 1 (4 bytes)
        avcc.write(0xE1) // 3 reserved bits + 5-bit SPS count
        avcc.write((sps.size ushr 8) and 0xFF)
        avcc.write(sps.size and 0xFF)
        avcc.write(sps)
        avcc.write(1)
        avcc.write((pps.size ushr 8) and 0xFF)
        avcc.write(pps.size and 0xFF)
        avcc.write(pps)
        val body = avcc.toByteArray()
        // The header must match catplay `ScreenFrame::config`, otherwise some head units (Audi/DELPHI field test:
        // connection and touch fine, car screen all black) parse later encrypted frames as plaintext NALs -> decode failure:
        //  - small_param[1] |= ScreenFlag::Encrypted(0x04): declares "video frames are encrypted"
        //  - params[1] = (width, height) as two f32 LE: the car builds its decoder canvas from them
        //  - params[0] zeroed (catplay's config carries no PTS; only VideoFrame writes params[0])
        // The old code wrote none of the three; DiPlay/WinPlay read only the body and do not check the header, so the
        // test bench was always fine
        // while a real car (Audi 4851) went black (2026-10-19 user log).
        val header = buildVideoConfigHeader(body.size, width, height)
        output.write(header)
        output.write(body)
        output.flush()
        configSent = true
        if (adapterMode) lastConfigMessage = header + body
        log(
            "VIDEO: OK VideoConfig sent avcC=${body.size}B (SPS ${sps.size}B/PPS ${pps.size}B, " +
                "profile=${sps[1].toInt() and 0xFF} level=${sps[3].toInt() and 0xFF} " +
                "flags=0x%02x ${width}x$height)".format(header[6].toInt() and 0xFF) +
                "\n  -> first 24 header bytes=" + header.take(24).joinToString(" ") { "%02x".format(it) },
        )
    }

    private fun sendHevcConfig(
        nalUnits: List<ByteArray>,
        output: java.io.OutputStream,
    ) {
        fun ofType(type: Int) = nalUnits.firstOrNull { it.size > 2 && HevcConfig.nalType(it[0]) == type }
        val vps = ofType(HevcConfig.NAL_VPS)
        val sps = ofType(HevcConfig.NAL_SPS)
        val pps = ofType(HevcConfig.NAL_PPS)
        val record = if (vps != null && sps != null && pps != null) HevcConfig.record(vps, sps, pps) else null
        if (vps == null || sps == null || pps == null || record == null) {
            log("VIDEO: HEVC csd lacks VPS/SPS/PPS (${nalUnits.size} NALs), skipping config")
            return
        }
        val body = HevcConfig.configBody(record)
        val header = buildVideoConfigHeader(body.size, width, height, hevc = true)
        output.write(header)
        output.write(body)
        output.flush()
        configSent = true
        log(
            "VIDEO: OK VideoConfig(HEVC) sent hvcC=${record.size}B (VPS ${vps.size}B/SPS ${sps.size}B/PPS ${pps.size}B, " +
                "profile=${record[1].toInt() and 0x1F} level=${record[12].toInt() and 0xFF} " +
                "flags=0x%02x ${width}x$height)".format(header[6].toInt() and 0xFF),
        )
    }

    private fun sendVideoFrame(
        buffer: java.nio.ByteBuffer,
        size: Int,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ) {
        if (!configSent) {
            log("VIDEO: dropping a frame (config not sent yet)")
            return
        }
        if (adapterMode && rePrimeGate.pending) {
            when (rePrimeGate.onFrame(keyFrame, android.os.SystemClock.uptimeMillis())) {
                RePrimeGate.Decision.SEND -> Unit
                RePrimeGate.Decision.DROP -> {
                    rePrimeDropped++
                    return
                }
                RePrimeGate.Decision.SEND_WITH_CONFIG,
                RePrimeGate.Decision.SEND_WITH_CONFIG_TIMEOUT,
                -> {
                    lastConfigMessage?.let { message ->
                        output.write(message)
                        output.flush()
                    }
                    log(
                        "VIDEO: re-prime: VideoConfig resent before " +
                            (if (keyFrame) "IDR" else "a non-key frame (no IDR within ${RePrimeGate.DEFAULT_MAX_WAIT_MS}ms)") +
                            ", $rePrimeDropped old-GOP frames held back",
                    )
                    rePrimeDropped = 0
                }
            }
        }
        val annexB = ByteArray(size)
        buffer.get(annexB)
        // Annex-B -> 4-byte length prefix, written into a fixed-size array in one scan; the SPS embedded in key frames gets the
        // level patch too (the car updates decoder parameters from the in-frame SPS, so patching only csd would let the first
        // key frame turn it back to a low level). The old code split NALs, copied each one and joined them via
        // ByteArrayOutputStream, copying a ~550KB I-frame 4 times.
        val plain = annexBToLengthPrefixed(annexB, patchH264Level = !useHevc) ?: return

        // Encryption mode is chosen by the session's et (catplay ScreenFrameCodec):
        //  - ChaCha: header is the AAD, body length includes the 16B tag;
        //  - AES-128-CTR: no tag, length unchanged, keystream continues across frames.
        // keepAlive/config frames are unencrypted in both modes. The header may be built only once: ChaCha's AAD
        // must be the exact 128 bytes sent (rebuilding it would carry a different timestamp and the peer's decryption
        // would necessarily fail).
        val header =
            buildHeader(
                OP_FRAME,
                if (frameCipher is StreamFrameCipher.ChaCha) plain.size + TAG_SIZE else plain.size,
            )
        val sealed =
            when (val cipher = frameCipher) {
                is StreamFrameCipher.ChaCha ->
                    AirPlayCrypto.chachaSeal(cipher.key, AirPlayCrypto.nonce64(frameCounter), plain, header)
                is StreamFrameCipher.AesCtr -> {
                    // CTR does not change the length and only size is used after plain: encrypt in place, saving one whole-frame copy
                    aesStream?.applyKeystream(plain)
                    plain
                }
            }
        frameCounter++
        output.write(header)
        output.write(sealed)
        output.flush()
        framesSent++
        framesSinceKeepAlive++
        bytesSinceKeepAlive += plain.size.toLong()
        if (keyFrame) keyFramesSent++
        // Log throttling: every I-frame used to log a line (1 line/s, and logging was a synchronous disk write).
        // Now only the first few frames, once every 150 frames and once every 20 I-frames (enough to see bitrate and I-frame size).
        if (framesSent <= 3 || framesSent % 150 == 0L || (keyFrame && keyFramesSent % 20 == 1L)) {
            log("VIDEO: frame #$framesSent ${plain.size}B${if (keyFrame) " (key #$keyFramesSent)" else ""} sent")
        }
    }

    /** 128-byte message header: u32LE body length, opcode, NTP 32.32 LE timestamp, rest zero. */
    private fun buildHeader(
        opcode: Int,
        bodyLength: Int,
    ): ByteArray {
        val header = ByteArray(HEADER_LEN)
        header[0] = (bodyLength and 0xFF).toByte()
        header[1] = ((bodyLength ushr 8) and 0xFF).toByte()
        header[2] = ((bodyLength ushr 16) and 0xFF).toByte()
        header[3] = ((bodyLength ushr 24) and 0xFF).toByte()
        header[4] = opcode.toByte()
        val nanos = System.nanoTime()
        val seconds = nanos / 1_000_000_000L
        val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        val ntp = (seconds shl 32) or fraction
        for (index in 0 until 8) {
            header[8 + index] = ((ntp ushr (8 * index)) and 0xFF).toByte()
        }
        return header
    }

    private fun writeMessage(
        opcode: Int,
        body: ByteArray,
        declaredLength: Int,
        output: java.io.OutputStream,
    ) {
        output.write(buildHeader(opcode, declaredLength))
        output.write(body)
        output.flush()
    }

    /** Splits an AnnexB stream into a NAL list by 00 00 00 01 / 00 00 01 start codes. */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
            return units
        }
        for (unit in 0 until starts.size) {
            val start = starts[unit]
            val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
            val bodyStart = start + prefix
            val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
        }
        return units
    }

    companion object {
        /**
         * Periodic I-frame interval (seconds). It was 1s: I-frame ~550KB, P-frame 5-20KB (S24 field test), so one I-frame per second
         * cost about 4Mbps and caused periodic send spikes; later changed to 3s. When the car decodes badly it sends `forceKeyFrame`,
         * and [requestKeyFrame] supplies an I-frame at once, so periodic I-frames are only a backstop.
         * 2026-11-20 WinPlay diagnostics: a real iPhone at rest carries ~0 traffic in a 10-second window, i.e. **no
         * short-period I-frames**,
         * so this is now 30s, leaving only a very cheap backstop (worst case 30s self-heal for a car that never requests key frames).
         */
        private const val KEYFRAME_INTERVAL_S = 30

        /** Upper bound for the video data-port send buffer (see [openDataPort]). */
        private const val SEND_BUFFER_BYTES = 256 * 1024

        /** QP floor (H.264 0~51, smaller = finer): I-frame 20, P/B-frame 22. */
        private const val QP_I_MIN = 20
        private const val QP_P_MIN = 22

        private const val HEADER_LEN = 128
        private const val OP_FRAME = 0
        private const val OP_CONFIG = 1
        private const val OP_KEEP_ALIVE = 2
        private const val OP_KEEP_ALIVE_WITH_BODY = 5
        private const val TAG_SIZE = 16

        /**
         * Header bit flags (catplay `ScreenFlag`): written to `small_param[1]` (header offset 6).
         * `Encrypted = 1<<2`: video frames are ChaCha-sealed; it must be declared in VideoConfig,
         * otherwise the car parses plaintext NALs -> decode failure, black screen (session and touch stay normal).
         */
        private const val SCREEN_FLAG_ENCRYPTED = 0x04

        /** HEVC flag at VideoConfig header offset 6 (the CarPlayStation receiver recognizes H.265 by this bit). */
        private const val SCREEN_FLAG_HEVC = 0x08

        /** Offset of VideoConfig header `params[1]`: 8 + 1x8, two f32 LE = width, height. */
        private const val CONFIG_WIDTH_HEIGHT_OFFSET = 16

        /** In-stream keepAlive interval (catplay `DEFAULT_KEEP_ALIVE` = 1s). */
        private const val KEEPALIVE_INTERVAL_MS = 1_000L

        /**
         * Builds the in-stream keepAlive frame (plaintext header + optional bplist stats body).
         *
         * Extracted as a pure function for unit tests: if the frame format is wrong (opcode / body-length field / stats key names)
         * the receiver ignores it silently with no error; the symptom is still a teardown after 10 seconds, at very high diagnostic cost.
         */
        internal fun buildKeepAliveFrame(
            withStats: Boolean,
            frames: Int,
            bytesPerSecond: Double,
            framesPerSecond: Int,
        ): ByteArray {
            val body =
                if (withStats) {
                    // Field names per catplay ScreenSenderStats (serde camelCase)
                    BinaryPlist.encode(
                        linkedMapOf(
                            "txUsageAvg" to bytesPerSecond,
                            "encoderCurrentFPS" to framesPerSecond.toLong(),
                            "sentFramesAvg" to frames.toLong(),
                            "queuedFramesAvg" to 0L,
                            "lossAvg" to 0.0,
                        ),
                    )
                } else {
                    ByteArray(0)
                }
            val header = ByteArray(HEADER_LEN)
            // Header layout: u32LE body length, u8 opcode, 3 bytes of small params, 15 x 8 bytes of params
            header[0] = (body.size and 0xFF).toByte()
            header[1] = ((body.size ushr 8) and 0xFF).toByte()
            header[2] = ((body.size ushr 16) and 0xFF).toByte()
            header[3] = ((body.size ushr 24) and 0xFF).toByte()
            header[4] = (if (body.isEmpty()) OP_KEEP_ALIVE else OP_KEEP_ALIVE_WITH_BODY).toByte()
            if (body.isNotEmpty()) {
                // params[14] (offset 8+14*8=120): first 4 bytes 0.0f, last 4 bytes body length as f32 LE
                writeLittleEndianFloat(header, 120, 0f)
                writeLittleEndianFloat(header, 124, body.size.toFloat())
            }
            return if (body.isEmpty()) header else header + body
        }

        /**
         * VideoConfig frame header (aligned byte-for-byte with catplay `ScreenFrame::config`):
         *  - `small_param[1]` (offset 6) set to Encrypted(0x04): declares that later video frames are sealed
         *  - `params[1]` (offset 16) = width, height as two f32 LE: the car builds its decoder canvas from them
         *  - `params[0]` (offset 8..16) stays 0: config carries no PTS
         *
         * Extracted as a pure function for unit tests: when a real car (Audi 4851) lacks these two the **session
         * stays up, touch works and the screen is all black**,
         * with no error in the log, which is very hard to diagnose.
         */
        internal fun buildVideoConfigHeader(
            bodySize: Int,
            width: Int,
            height: Int,
            hevc: Boolean = false,
        ): ByteArray {
            val header = ByteArray(HEADER_LEN)
            header[0] = (bodySize and 0xFF).toByte()
            header[1] = ((bodySize ushr 8) and 0xFF).toByte()
            header[2] = ((bodySize ushr 16) and 0xFF).toByte()
            header[3] = ((bodySize ushr 24) and 0xFF).toByte()
            header[4] = OP_CONFIG.toByte()
            header[6] = (SCREEN_FLAG_ENCRYPTED or if (hevc) SCREEN_FLAG_HEVC else 0).toByte()
            for (index in 0 until 4) {
                val widthBits = width.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + index] = ((widthBits ushr (8 * index)) and 0xFF).toByte()
                val heightBits = height.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + 4 + index] = ((heightBits ushr (8 * index)) and 0xFF).toByte()
            }
            return header
        }

        private fun writeLittleEndianFloat(
            target: ByteArray,
            offset: Int,
            value: Float,
        ) {
            val bits = value.toRawBits()
            for (index in 0 until 4) {
                target[offset + index] = ((bits ushr (8 * index)) and 0xFF).toByte()
            }
        }
    }
}

/**
 * Encoder target bitrate: pixels x8 (floor 4, cap 12Mbps), x1.5 more at 60fps, overall cap 12Mbps.
 *
 * The old formula "pixels x6, 2~10Mbps, cap 15" gave only ~7Mbps at 1264x640@60, and a VW MIB3 owner reported text blurrier than
 * native CarPlay (upstream shaonialife/cardeck#26), so small canvases are raised to ~9.7Mbps.
 * Overall cap 12Mbps: 2026-11-20 WinPlay: with a 16.6Mbps target at 1920x720@60 a moving map peaked at
 * 19~20Mbps and the receiver repeatedly back-pressured and paused (stutter), while a real iPhone in the same scene
 * peaks ~13Mbps and is smoother.
 * Static pictures are held down by the QP floor, not by this.
 */
internal fun encoderBitrate(
    width: Int,
    height: Int,
    fps: Int,
): Int {
    val base = (width.toLong() * height * 8).coerceIn(4_000_000L, 12_000_000L)
    val scaled = if (fps >= 60) base * 3 / 2 else base
    return scaled.coerceAtMost(12_000_000L).toInt()
}

/**
 * HEVC target bitrate = 65% of H.264 (floor 3Mbps). At the same target bitrate HEVC's gain shows only as quality, with no traffic change
 * (2026-11-20 CarPlayStation: actual bitrates were similar); "about 30-50% saving at equal quality" is taken conservatively,
 * 1920x720@60 goes from 12 to 7.8Mbps, easing WiFi and receiver decode load.
 */
internal fun hevcBitrate(h264Bitrate: Int): Int = (h264Bitrate.toLong() * 65 / 100).coerceAtLeast(3_000_000L).toInt()

/** H.264 level_idc target: 4.0 (0x28). Same stream shape as a real iPhone. */
private const val TARGET_LEVEL_IDC = 40

/**
 * Patches the SPS level_idc to 4.0 (3.1->4.0 relaxes constraints, so it is safe).
 *
 * Why not MediaFormat.KEY_LEVEL: measured (MagicOS/Android 17) the encoder **silently ignores**
 * that key: no exception, and the SPS still says 3.1. Only post-processing works.
 *
 * Why it is safe: 1) level_idc sits at a fixed SPS offset (nal[3]), a one-byte change that keeps the length;
 * 2) 3.1->4.0 only relaxes the maxMBPS/maxFS/maxBR limits, so resources the decoder allocates for 4.0 are never fewer
 * than a 3.1 stream needs; 3) 0x28 is non-zero and introduces no emulation-prevention sequence (00 00 0x).
 * If the SPS is already >=4.0 (e.g. auto-selected 4.2 at 60fps) the original value is kept, never downgraded.
 *
 * Motivation: the Audi DELPHI decoder silently does not render level 3.1/3.2 streams (TCP received, session normal,
 * car screen black; 2026-10-22 Audi 0628 real-device log: on the same unit level=40 always succeeded, level=32 gave
 * the first black screen).
 */

/**
 * Annex-B (start-code delimited) -> prefix every NAL with a 4-byte big-endian length (AVCC style) and raise the SPS
 * level_idc in place to [TARGET_LEVEL_IDC]. Start-code recognition is identical to the old `splitAnnexB` (3/4 bytes,
 * not recognized within the last 3 bytes), empty NALs are skipped; with no start code the whole block is one NAL.
 * Returns null if there is no NAL.
 * The result is byte-for-byte identical to "splitAnnexB -> patchSpsLevel -> write length+data one by one" (unit tests compare).
 */
internal fun annexBToLengthPrefixed(
    bytes: ByteArray,
    patchH264Level: Boolean = true,
): ByteArray? {
    if (bytes.isEmpty()) return null
    // Record each NAL's [body start, body end)
    val bodies = ArrayList<Long>()
    var index = 0
    var pendingStart = -1
    while (index + 3 < bytes.size) {
        if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
            val prefix =
                when {
                    bytes[index + 2] == 1.toByte() -> 3
                    bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                    else -> 0
                }
            if (prefix > 0) {
                if (pendingStart >= 0 && index > pendingStart) bodies += (pendingStart.toLong() shl 32) or index.toLong()
                pendingStart = index + prefix
                index += prefix
                continue
            }
        }
        index++
    }
    if (pendingStart < 0) {
        bodies += bytes.size.toLong() // No start code: [0, size)
    } else if (bytes.size > pendingStart) {
        bodies += (pendingStart.toLong() shl 32) or bytes.size.toLong()
    }
    if (bodies.isEmpty()) return null
    var total = 0
    for (b in bodies) total += 4 + ((b and 0xFFFFFFFFL) - (b ushr 32)).toInt()
    val out = ByteArray(total)
    var pos = 0
    for (b in bodies) {
        val start = (b ushr 32).toInt()
        val len = (b and 0xFFFFFFFFL).toInt() - start
        out[pos] = (len ushr 24).toByte()
        out[pos + 1] = (len ushr 16).toByte()
        out[pos + 2] = (len ushr 8).toByte()
        out[pos + 3] = len.toByte()
        System.arraycopy(bytes, start, out, pos + 4, len)
        if (patchH264Level && len >= 4 && (bytes[start].toInt() and 0x1F) == 7 && (bytes[start + 3].toInt() and 0xFF) < TARGET_LEVEL_IDC) {
            out[pos + 4 + 3] = TARGET_LEVEL_IDC.toByte()
        }
        pos += 4 + len
    }
    return out
}

internal fun patchSpsLevel(sps: ByteArray): ByteArray {
    if (sps.size < 4) return sps
    val current = sps[3].toInt() and 0xFF
    if (current >= TARGET_LEVEL_IDC) return sps
    val patched = sps.copyOf()
    patched[3] = TARGET_LEVEL_IDC.toByte()
    return patched
}
