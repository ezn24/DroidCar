package io.github.ezn24.cardeck.neo.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import io.github.ezn24.cardeck.neo.airplay.AirPlayCrypto
import io.github.ezn24.cardeck.neo.airplay.BinaryPlist
import io.github.ezn24.cardeck.neo.airplay.StreamFrameCipher
import io.github.ezn24.cardeck.neo.bt.AdapterMode
import io.github.ezn24.cardeck.neo.net.TcpLiveness
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主屏视频发送端（Phase 2-C2）。
 *
 * 线格式（对照 DiPlay ScreenStream.kt）：
 *  - 每条消息 = 128 字节头 + body；头部 = [0..3] u32LE body 长度、[4] opcode、
 *    [8..15] NTP 32.32 LE 发送时间；整头作帧加密 AAD，其余字节为零
 *  - opcode 1 = VideoConfig：明文 avcC 记录（looksLikeAvcC 判定）
 *  - opcode 0 = VideoFrame：ChaCha20-Poly1305 密封的「4 字节 BE 长度前缀 NAL 序列」，
 *    nonce = nonce64(帧计数)，计数按帧顺序从 0 递增
 *
 * 视频源：MediaCodec Surface 输入（H.264 硬编码），内容由画面源渲染
 * （隐形屏模式的 DualDisplayComposer 输入 Surface）。
 */
class VideoSender(
    private val host: String,
    private val dataPort: Int,
    /**
     * 帧加密方式（catplay `ScreenFrameCodec` 三种形态）：
     *  - ChaCha：已配对（pair-verify 派生流密钥）
     *  - AesCtr：未配对会话（`et=NONE`，AES-128-CTR + 全零会话密钥）
     */
    private val frameCipher: StreamFrameCipher,
    /** H.264 4:2:0 要求偶数尺寸——奇数高度会让编码器在首帧输入时进错误态（125% 缩放实测踩坑）。 */
    width: Int,
    height: Int,
    /** 目标帧率（30/60）：编码器元数据 + 60fps 提码率；实际节奏由合成器驱动。 */
    private val targetFps: Int = 30,
    private val log: (String) -> Unit,
    /** 推流自然结束（对端断开等）时回调——CarPlay 会话真实结束的信号。 */
    /** 推流结束回调；参数 = 是否由对端（车机/网络）断开，false 表示是本机主动 [stop]。 */
    private val onEnded: ((byPeer: Boolean) -> Unit)? = null,
    /**
     * 屏幕流内 keepAlive 帧是否携带统计体（车机 /info 的 `keepAliveSendStatsAsBody`）。
     * 奥迪 MMI 3315 声明 true → opcode=5 + bplist 统计体；否则 opcode=2 空帧。
     * 真 iPhone/catplay 在视频持续推流时**仍然每秒发一次**——它不是视频流量的备份，
     * 而是接收端屏幕上独立的存活信号（2026-10-19 奥迪 3315 两轮实测：缺它时
     * RECORD 后 10.2s 整轮被 reset，视频/feedback/事件通道全都健康）。
     */
    private val keepAliveWithStats: Boolean = false,
    /** 「清晰优先」：请求 H.264 High profile（编码器拒绝时自动退回，见 [start]）。转接盒恒 Baseline。 */
    private val clarityFirst: Boolean = false,
    /** 日志用：本次会话的分辨率缩放百分比。 */
    private val scalePercent: Int = 100,
    /** 接收端声明并接受 HEVC（SetupInfo.hevc）且用户开了 HEVC：改用 H.265 编码。转接盒恒 H.264。 */
    hevc: Boolean = false,
) {
    /** 实际使用 HEVC（编码器不可用时退回 H.264 会改为 false）。 */
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

    /** AES-CTR 密钥流实例：状态必须跨帧连续（每帧新建会导致对端解出乱码）。 */
    private val aesStream: AirPlayCrypto.Aes128CtrStream? =
        (frameCipher as? StreamFrameCipher.AesCtr)?.let { AirPlayCrypto.Aes128CtrStream(it.key, it.iv) }

    /** 上一次屏幕流 keepAlive 的时刻（uptimeMillis；0 = 尚未发过）。 */
    private var lastKeepAliveAt = 0L

    /** 两次 keepAlive 之间的帧数/字节数（统计体用，catplay ScreenSenderStats 同义）。 */
    private var framesSinceKeepAlive = 0
    private var bytesSinceKeepAlive = 0L
    private var keepAliveCount = 0L

    /** 日志统计窗口（两条 keepAlive 日志之间的累计）。 */
    private var statWindowFrames = 0L
    private var statWindowBytes = 0L
    private var statWindowStartAt = 0L

    @Volatile
    private var framesSent = 0L
    private var keyFramesSent = 0L

    /** 推流是否活跃（静默降噪守卫判断用：start 失败时保持 false）。 */
    val isRunning: Boolean get() = running.get()

    /** 已发送帧总数（英雄卡实时帧率 = 1s 轮询取差值）。 */
    val framesSentTotal: Long get() = framesSent

    /**
     * 提前建立屏幕数据端口 TCP 连接（幂等）。
     *
     * 车机看门狗（2026-10-17 奥迪实测）：屏幕流 SETUP 之后 ~2s 内数据端口必须连上，
     * 否则直接 reset 控制通道拆掉整轮会话。音频 SETUP 本身要 ~2.1s，若排在它后面，
     * 端口连接必然迟到——所以协商音频前先调本方法。catplay 亦然（视频 SETUP 后立即
     * finish_tx 连流，再协商音频）。
     */
    fun openDataPort(): Boolean {
        val existing = socket
        if (existing != null && !existing.isClosed) return true
        return try {
            val client = Socket()
            // 发送缓冲限到 256KB（connect 前设置才影响窗口协商）：内核默认会自动调到数 MB，
            // WiFi 吞吐一掉帧就在缓冲里排队，12Mbps 下 1MB ≈ 0.7s 延迟、恢复时又一口气涌出
            // （「延迟 + 时不时卡」）。缓冲小时写入早阻塞，合成器那端会跳过来不及编的帧，
            // 画面保持新鲜——交互场景宁可丢帧不可积压。
            runCatching { client.sendBufferSize = SEND_BUFFER_BYTES }
            client.connect(InetSocketAddress(host, dataPort), 5_000)
            client.tcpNoDelay = true
            // 推流是长连接：车机熄火/走出热点时链路常不发 RST，写入会阻塞在满窗口上，
            // 编码器线程随之卡死（表现为「画面停在最后一帧」）。
            TcpLiveness.configure(client, "视频数据口", log)
            socket = client
            log("VIDEO: 数据端口已连通 $host:$dataPort sndbuf=${runCatching { client.sendBufferSize }.getOrNull()}（等 RECORD 后启动编码器）")
            true
        } catch (error: Exception) {
            log("VIDEO: 数据端口连接失败 - ${error.message}")
            false
        }
    }

    /**
     * 启动编码与推流。编码器输入 Surface 通过 [onSurfaceReady] 交给调用方渲染
     * （隐形屏模式下由 DualDisplayComposer 的合成画布填充）。
     */
    fun start(onSurfaceReady: (Surface) -> Unit): Boolean {
        if (running.get()) return true
        return try {
            if (!openDataPort()) {
                stop()
                return false
            }
            val bitrate = encoderBitrate(width, height, targetFps)
            log("VIDEO: 启动编码器 ${width}x$height@$targetFps ${bitrate / 1_000_000}Mbps（端口已连通）")

            // 依次尝试：[High（清晰优先）+ level 4.0] → [level 4.0] → [全自动]。
            // 固定 level 4.0（SPS level_idc=40，真 iPhone 同款流形态）：不固定时编码器按
            // 「分辨率+帧率」自动选，30fps 下 1412x654 落入 level 3.2——奥迪 DELPHI 解码器对该
            // level 静默不渲染（2026-10-22 奥迪 0628 真机日志）。个别编码器不接受 profile/level
            // 指定（configure 抛异常=视频全灭），故逐级降级。
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
                    log("VIDEO: 无 ${attempt.mime} 编码器——降级")
                    continue
                }
                // CBR：高通 VBR 实测 10 秒平均超目标 75~80%（H.264 目标 12 → 21Mbps、HEVC 7.8 → 14），
                // 峰值让接收端背压卡顿。用户取舍「稳定、不卡顿」优先于拖动时的清晰度（2026-11-20）。
                // 静止画面靠 QP 下限兜住，CBR 不会为凑码率而重编。编码器不支持时照旧 VBR。
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
                    log("VIDEO: 编码器拒绝 ${attempt.label}（${rejected.message}）——降级重试")
                }
            }
            if (codec == null || chosen == null) error("编码器全部配置方案均被拒绝")
            if (useHevc && !chosen.hevc) {
                log("VIDEO: HEVC 编码器不可用——本次会话退回 H.264")
                useHevc = false
            }
            log(
                "VIDEO: 画质 缩放=$scalePercent% 画布=${width}x$height@$targetFps 码率=${"%.1f".format(chosen.bitrateFrom(bitrate) / 1e6)}Mbps " +
                    "请求=${chosen.label}${if (chosen.level4) " QPmin=$QP_I_MIN/$QP_P_MIN" else ""} " +
                    "码控=${if (chosenCbr) "CBR" else "默认(VBR)"} " +
                    "编码器=${codec.name} 清晰优先=$clarityFirst" +
                    "（实际 profile/level 见 VideoConfig 行：66=Baseline 77=Main 100=High）",
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
            log("VIDEO: 启动失败 - ${error.message}")
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
     * 立即编码一个关键帧（车机 `/command` 的 `forceKeyFrame` 请求）。
     *
     * 车机解不出画面时会反复要 I 帧；我们本身每秒一个，但**要帧请求必须立即响应**——
     * 否则车机可能一直卡在「等下一个 I 帧」的状态，表现就是「连上了但没画面」
     * （宝马 iD9 2026-10-20 实测：会话刚起时收到一次 forceKeyFrame）。
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
            log("VIDEO: 请求关键帧失败 - ${error.message}")
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

    // ---- 编码输出 → 协议封装发送 ----

    private fun drainEncoder() {
        val codec = encoder ?: return
        // 缓冲包一层：128B 头与帧体合并成一次发送（tcpNoDelay 下原先是两个 TCP 段），
        // 每帧末尾本来就会 flush，不引入额外延迟
        val output = java.io.BufferedOutputStream(socket?.getOutputStream() ?: return, 64 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index =
                    try {
                        codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (error: Exception) {
                        // 编码器进错误态（如非法尺寸首帧）：必须留痕——静默 break 曾让
                        // 「零帧推流 4 秒后断开」无从排查（接收端只见 peer EOF）
                        log("VIDEO: 编码器取帧异常，停止推流 - ${error.message}")
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
            // 视频数据口断开（如接收端屏幕关闭收摊会话）：只终止推流，绝不冒泡——
            // 后台线程未捕获异常会杀死整个进程（曾导致锁屏后 app 消失）
            log("VIDEO: 推流中止 - ${error.message}（等待接收端恢复后重连）")
        } finally {
            val byPeer = !stopRequested
            stopInternal()
            // 会话真实结束信号（静默降噪解除 / 状态收尾）
            runCatching { onEnded?.invoke(byPeer) }
        }
    }

    private class EncoderAttempt(
        val high: Boolean,
        val level4: Boolean,
        val hevc: Boolean = false,
    ) {
        val mime: String get() = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        /** 该方案的目标码率：HEVC 按 [hevcBitrate] 折算。 */
        fun bitrateFrom(h264Bitrate: Int): Int = if (hevc) hevcBitrate(h264Bitrate) else h264Bitrate

        val label: String
            get() =
                when {
                    hevc -> if (level4) "HEVC Main" else "HEVC 自动"
                    high -> "High+L4.0"
                    level4 -> "L4.0"
                    else -> "自动"
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
                // HEVC：Main profile，level 交给编码器（H.264 的 level 4.0 约定不适用）
                attempt.hevc -> setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                // The smartBox's hardware decoder showed black for a non-Baseline stream and
                // displayed Baseline (profile 66) in the reference field run; ask for it explicitly.
                adapterMode -> setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                attempt.high -> setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
            if (attempt.level4) {
                if (!attempt.hevc) setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                // QP 下限：画质够好就不许再加位元。2026-11-20 WinPlay 实测：仪表盘只有 Google 地图
                // 定位点在动时，高通编码器仍把 16.6Mbps 预算用满（每帧 16~30KB、整屏反复精修），
                // 真 iPhone 同类动画每帧 ~3KB。下限 20/22 对文字仍清晰；不支持的编码器会忽略。
                // 「全自动」那一档不带，留作万一某编码器因此拒绝 configure 的退路。
                setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, QP_I_MIN)
                setInteger(MediaFormat.KEY_VIDEO_QP_P_MIN, QP_P_MIN)
                setInteger(MediaFormat.KEY_VIDEO_QP_B_MIN, QP_P_MIN)
                // 低延迟提示：实时优先级、编码器最多压 1 帧（不做多帧前瞻）、按目标帧率供算力。
                // 均为提示，不支持的编码器忽略。
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps)
            }
        }

    /**
     * 屏幕流 keepAlive 帧（catplay `ScreenTransmitSession::create_keep_alive`，1s 间隔）。
     *
     * 与 UDP keepAlive / RTSP feedback 是三条相互独立的存活信号；接收端（奥迪 3315）
     * 只看视频帧不够——缺这条时 RECORD 后约 10s 会 reset 整轮会话（2026-10-19 两轮
     * 实测）。帧是**明文**的（catplay 仅对 opcode=VideoFrame 加密），因此不消耗 ChaCha
     * 帧计数；带统计体时 opcode=5，并把 body 长度写进头部 params[14] 的后 4 字节
     * （f32 LE），与 catplay 逐字节一致。
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
        // 日志用「自上一条日志以来」的窗口平均（前 3 条约 1s、之后约 10s），而不是最后 1 秒的
        // 瞬时值——瞬时值取样时机一错开，同样操作的两轮（如 HEVC/H.264 对照）就比不出差别。
        statWindowFrames += frames
        statWindowBytes += bytes
        if (keepAliveCount <= 3 || keepAliveCount % 10 == 0L) {
            val windowMs = (now - statWindowStartAt).coerceAtLeast(1L)
            log(
                "VIDEO: keepAlive #$keepAliveCount " +
                    "opcode=${if (keepAliveWithStats) OP_KEEP_ALIVE_WITH_BODY else OP_KEEP_ALIVE} " +
                    "body=${frame.size - HEADER_LEN}B " +
                    "近${"%.0f".format(windowMs / 1000.0)}s平均 fps=${statWindowFrames * 1000 / windowMs} " +
                    "码率=${"%.2f".format(statWindowBytes * 8.0 / windowMs / 1000.0)}Mbps",
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
            log("VIDEO: csd 缺少 SPS/PPS（${nalUnits.size} 个 NAL），跳过 config")
            return
        }
        // level_idc 补丁到 4.0（关键帧内嵌 SPS 同样会打补丁，见 sendVideoFrame）
        val sps = patchSpsLevel(rawSps)
        val avcc = ByteArrayOutputStream(8 + sps.size + pps.size)
        avcc.write(1)
        avcc.write(sps[1].toInt()) // profile
        avcc.write(sps[2].toInt()) // compat
        avcc.write(sps[3].toInt()) // level
        avcc.write(0xFF) // 6 位保留 + 2 位长度尺寸-1（4 字节）
        avcc.write(0xE1) // 3 位保留 + 5 位 SPS 数
        avcc.write((sps.size ushr 8) and 0xFF)
        avcc.write(sps.size and 0xFF)
        avcc.write(sps)
        avcc.write(1)
        avcc.write((pps.size ushr 8) and 0xFF)
        avcc.write(pps.size and 0xFF)
        avcc.write(pps)
        val body = avcc.toByteArray()
        // 头部必须与 catplay `ScreenFrame::config` 一致，否则部分车机（Audi/DELPHI 实测：
        // 连接与触摸都正常、车机屏全黑）会把后续加密帧按明文 NAL 解析 → 解码失败：
        //  - small_param[1] |= ScreenFlag::Encrypted(0x04)：声明「视频帧是加密的」
        //  - params[1] = (width, height) 两个 f32 LE：车机据此建立解码器画布
        //  - params[0] 归零（catplay 的 config 不带 PTS；只有 VideoFrame 才写 params[0]）
        // 旧实现三项都没写——DiPlay/WinPlay 只读 body、不校验头，所以测试台一直正常，
        // 真车（Audi 4851）却黑屏（2026-10-19 用户日志）。
        val header = buildVideoConfigHeader(body.size, width, height)
        output.write(header)
        output.write(body)
        output.flush()
        configSent = true
        if (adapterMode) lastConfigMessage = header + body
        log(
            "VIDEO: ✓ VideoConfig 已发送 avcC=${body.size}B（SPS ${sps.size}B/PPS ${pps.size}B，" +
                "profile=${sps[1].toInt() and 0xFF} level=${sps[3].toInt() and 0xFF} " +
                "flags=0x%02x ${width}x$height）".format(header[6].toInt() and 0xFF) +
                "\n  ↳ 头部前 24 字节=" + header.take(24).joinToString(" ") { "%02x".format(it) },
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
            log("VIDEO: HEVC csd 缺少 VPS/SPS/PPS（${nalUnits.size} 个 NAL），跳过 config")
            return
        }
        val body = HevcConfig.configBody(record)
        val header = buildVideoConfigHeader(body.size, width, height, hevc = true)
        output.write(header)
        output.write(body)
        output.flush()
        configSent = true
        log(
            "VIDEO: ✓ VideoConfig(HEVC) 已发送 hvcC=${record.size}B（VPS ${vps.size}B/SPS ${sps.size}B/PPS ${pps.size}B，" +
                "profile=${record[1].toInt() and 0x1F} level=${record[12].toInt() and 0xFF} " +
                "flags=0x%02x ${width}x$height）".format(header[6].toInt() and 0xFF),
        )
    }

    private fun sendVideoFrame(
        buffer: java.nio.ByteBuffer,
        size: Int,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ) {
        if (!configSent) {
            log("VIDEO: 丢弃一帧（config 尚未发送）")
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
                        "VIDEO: re-prime → VideoConfig resent before " +
                            (if (keyFrame) "IDR" else "a non-key frame (no IDR within ${RePrimeGate.DEFAULT_MAX_WAIT_MS}ms)") +
                            ", $rePrimeDropped old-GOP frames held back",
                    )
                    rePrimeDropped = 0
                }
            }
        }
        val annexB = ByteArray(size)
        buffer.get(annexB)
        // Annex-B → 4 字节长度前缀，一次扫描直接写进定长数组；关键帧内嵌的 SPS 同步打
        // level 补丁（车机会用帧内 SPS 更新解码器参数，只改 csd 不改帧内 SPS 的话，
        // 收到第一个关键帧就又变回低 level 了）。此前拆 NAL 逐个复制再经
        // ByteArrayOutputStream 拼接，一个 ~550KB 的 I 帧要复制 4 次。
        val plain = annexBToLengthPrefixed(annexB, patchH264Level = !useHevc) ?: return

        // 加密方式按会话的 et 选择（catplay ScreenFrameCodec）：
        //  - ChaCha：header 作 AAD，body 长度含 16B tag；
        //  - AES-128-CTR：无 tag、不改长度，密钥流跨帧连续。
        // keepAlive/config 帧在两种模式下都不加密。头只能构造一次——ChaCha 的 AAD
        // 必须是最终发出的那 128 字节（重复构造会带不同时间戳，对端解密必然失败）。
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
                    // CTR 不改长度、plain 之后只用到 size：原地加密，省一次整帧复制
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
        // 留痕节流：此前每个 I 帧都记一行（1 行/秒，且日志是同步落盘的）。
        // 现在只记开头几帧、每 150 帧一次、以及每 20 个 I 帧一次（足够看出码率与 I 帧大小）。
        if (framesSent <= 3 || framesSent % 150 == 0L || (keyFrame && keyFramesSent % 20 == 1L)) {
            log("VIDEO: 帧 #$framesSent ${plain.size}B${if (keyFrame) " (key #$keyFramesSent)" else ""} 已发送")
        }
    }

    /** 128 字节消息头：u32LE body 长度、opcode、NTP 32.32 LE 时间戳，其余为零。 */
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

    /** 按 00 00 00 01 / 00 00 01 起始码切分 AnnexB 码流为 NAL 列表。 */
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
         * 周期 I 帧间隔（秒）。原为 1s：I 帧 ~550KB、P 帧 5–20KB（S24 实测），每秒一个 I 帧
         * 约占 4Mbps 并造成周期性发送尖峰；后改 3s。车机解码出错时会发 `forceKeyFrame`，由
         * [requestKeyFrame] 即时补 I 帧，所以周期 I 帧只是兜底。
         * 2026-11-20 WinPlay 诊断：真 iPhone 静止时 10 秒窗口内流量≈0，即**没有短周期 I 帧**
         * ——改 30s，只留极低成本的兜底（未请求关键帧的车机最坏 30s 自愈）。
         */
        private const val KEYFRAME_INTERVAL_S = 30

        /** 视频数据口发送缓冲上限（见 [openDataPort]）。 */
        private const val SEND_BUFFER_BYTES = 256 * 1024

        /** QP 下限（H.264 0~51，越小越细）：I 帧 20、P/B 帧 22。 */
        private const val QP_I_MIN = 20
        private const val QP_P_MIN = 22

        private const val HEADER_LEN = 128
        private const val OP_FRAME = 0
        private const val OP_CONFIG = 1
        private const val OP_KEEP_ALIVE = 2
        private const val OP_KEEP_ALIVE_WITH_BODY = 5
        private const val TAG_SIZE = 16

        /**
         * 头部位标志（catplay `ScreenFlag`）：写进 `small_param[1]`（头部偏移 6）。
         * `Encrypted = 1<<2`：视频帧是 ChaCha 密封的；VideoConfig 里必须声明，
         * 否则车机按明文 NAL 解析 → 解码失败黑屏（会话与触摸仍正常）。
         */
        private const val SCREEN_FLAG_ENCRYPTED = 0x04

        /** VideoConfig 头偏移 6 的 HEVC 标志（CarPlayStation 接收端按此位识别 H.265）。 */
        private const val SCREEN_FLAG_HEVC = 0x08

        /** VideoConfig 头部 `params[1]` 的偏移：8 + 1×8，两个 f32 LE = 宽、高。 */
        private const val CONFIG_WIDTH_HEIGHT_OFFSET = 16

        /** 屏幕流 keepAlive 间隔（catplay `DEFAULT_KEEP_ALIVE` = 1s）。 */
        private const val KEEPALIVE_INTERVAL_MS = 1_000L

        /**
         * 构造屏幕流 keepAlive 帧（明文头 + 可选 bplist 统计体）。
         *
         * 拆成纯函数是为了单测：帧格式一旦写错（opcode/body 长度字段/统计体键名），
         * 接收端会静默忽略而不报错——现象仍是 10 秒后被拆链，排查成本极高。
         */
        internal fun buildKeepAliveFrame(
            withStats: Boolean,
            frames: Int,
            bytesPerSecond: Double,
            framesPerSecond: Int,
        ): ByteArray {
            val body =
                if (withStats) {
                    // 字段名对照 catplay ScreenSenderStats（serde camelCase）
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
            // 头部布局：u32LE body 长度、u8 opcode、3 字节小参数、15 × 8 字节参数
            header[0] = (body.size and 0xFF).toByte()
            header[1] = ((body.size ushr 8) and 0xFF).toByte()
            header[2] = ((body.size ushr 16) and 0xFF).toByte()
            header[3] = ((body.size ushr 24) and 0xFF).toByte()
            header[4] = (if (body.isEmpty()) OP_KEEP_ALIVE else OP_KEEP_ALIVE_WITH_BODY).toByte()
            if (body.isNotEmpty()) {
                // params[14]（偏移 8+14*8=120）：前 4 字节 0.0f，后 4 字节 body 长度 f32 LE
                writeLittleEndianFloat(header, 120, 0f)
                writeLittleEndianFloat(header, 124, body.size.toFloat())
            }
            return if (body.isEmpty()) header else header + body
        }

        /**
         * VideoConfig 帧头（catplay `ScreenFrame::config` 逐字节对齐）：
         *  - `small_param[1]`（偏移 6）置 Encrypted(0x04) —— 声明后续视频帧是密封的
         *  - `params[1]`（偏移 16）= 宽、高两个 f32 LE —— 车机据此建解码器画布
         *  - `params[0]`（偏移 8..16）保持 0 —— config 不带 PTS
         *
         * 拆成纯函数是为了单测：真车（Audi 4851）缺这两项时**会话不断、触摸正常、屏全黑**，
         * 日志里看不出任何错误，极难排查。
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
 * 编码目标码率：像素 ×8（下限 4、上限 12Mbps），60fps 再 ×1.5，总上限 12Mbps。
 *
 * 旧公式「像素 ×6、2~10Mbps、上限 15」在 1264×640@60 只给 ~7Mbps，大众 MIB3 车主反馈
 * 文字比原生 CarPlay 发虚（上游 shaonialife/cardeck#26），故小画布提到 ~9.7Mbps。
 * 总上限 12Mbps：2026-11-20 WinPlay 实测，1920×720@60 目标 16.6Mbps 时移动地图峰值到
 * 19~20Mbps，接收端反复背压暂停（卡顿），同场景真 iPhone 峰值 ~13Mbps 且更顺。
 * 静止画面由 QP 下限压低，不靠这里。
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
 * HEVC 目标码率 = H.264 的 65%（下限 3Mbps）。同目标码率下 HEVC 的收益只体现为画质，流量不变
 * （2026-11-20 CarPlayStation 实测两者实际码率相近）；按「同画质约省三到五成」取中间偏保守，
 * 1920×720@60 由 12 → 7.8Mbps，减轻 WiFi 与接收端解码负担。
 */
internal fun hevcBitrate(h264Bitrate: Int): Int = (h264Bitrate.toLong() * 65 / 100).coerceAtLeast(3_000_000L).toInt()

/** H.264 level_idc 目标值：4.0（0x28）。真 iPhone 同款流形态。 */
private const val TARGET_LEVEL_IDC = 40

/**
 * 把 SPS 的 level_idc 补丁到 4.0（3.1→4.0 是放宽约束，安全）。
 *
 * 为什么不改用 MediaFormat.KEY_LEVEL：实测（MagicOS/Android 17）编码器**静默忽略**
 * 该键——不抛异常、SPS 里仍是 3.1。只能后处理。
 *
 * 为什么安全：① level_idc 在 SPS 的固定偏移（nal[3]），单字节改值不改长度；
 * ② 3.1→4.0 只放宽 maxMBPS/maxFS/maxBR 上限，解码器按 4.0 分配的资源对 3.1 编码
 * 的流只多不少；③ 0x28 非零且不会引入 emulation prevention 序列（00 00 0x）。
 * 若 SPS 本来就 ≥4.0（如 60fps 时自动选 4.2），保持原值不降级。
 *
 * 动机：奥迪 DELPHI 解码器对 level 3.1/3.2 的流静默不渲染（TCP 照收、会话正常、
 * 车机黑屏；2026-10-22 奥迪 0628 真机日志：同机 level=40 全部成功、level=32 首例黑屏）。
 */

/**
 * Annex-B（起始码分隔）→ 每个 NAL 前加 4 字节大端长度（AVCC 样式），并就地把 SPS 的
 * level_idc 抬到 [TARGET_LEVEL_IDC]。起始码识别与旧 `splitAnnexB` 完全一致（3/4 字节、
 * 尾部 3 字节内不认），空 NAL 跳过；找不到起始码时整块当一个 NAL。没有任何 NAL 返回 null。
 * 结果与「splitAnnexB → patchSpsLevel → 逐个写长度+数据」逐字节相同（单测对照）。
 */
internal fun annexBToLengthPrefixed(
    bytes: ByteArray,
    patchH264Level: Boolean = true,
): ByteArray? {
    if (bytes.isEmpty()) return null
    // 记录每个 NAL 的 [体起点, 体终点)
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
        bodies += bytes.size.toLong() // 无起始码：[0, size)
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
