package io.github.shaonialife.cardeck.audio

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.airplay.AirPlayClient
import io.github.shaonialife.cardeck.airplay.CarMic
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 车机麦克风上行（2026-11-17，docs/MIC-RESEARCH.md）。
 *
 * **自动切换**：会话期间每 [WATCH_INTERVAL_MS] 查一次「谁在录音」（AppOps RECORD_AUDIO），
 * 有别的 App 在录（录音机、通话、Gemini 语音输入）就开车机麦克风流 + Shizuku 录音注入，
 * 录音停止 [QUIET_STOP_MS] 后撤掉。实验证实录音开始后才注册注入，同一个录音 1s 内就切过去。
 *
 * **车机语音键**（`requestSiri`）：先开好车机麦克风再唤起助理（`cmd voiceinteraction show`）。
 *
 * 注入期间手机自己的麦克风对所有 App 失效，所以只在有人录音时存在。
 * 「Hey Google」热词与其后续对话走 HOTWORD/DSP 通道，注入不到。
 */
object CarMicController {
    private class Active(
        val client: AirPlayClient,
        val info: AirPlayClient.MicStreamInfo,
        val socket: DatagramSocket,
        val output: ParcelFileDescriptor.AutoCloseOutputStream,
        val startedAt: Long,
        /** 在此之前不因「没人录音」收口（语音键刚唤起助理，助理还没开麦）。 */
        val graceUntil: Long,
        val reason: String,
    ) {
        @Volatile var packets = 0

        @Volatile var badPackets = 0

        @Volatile var sent = 0
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

    /** 会话建立（事件通道就绪）后调用：开始自动切换的录音监视。 */
    fun attach(
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        ownPackage: String,
        mediaStreamType: () -> Long?,
    ) {
        detach("新会话")
        if (sharedSecret == null) {
            log("CARMIC: 明文会话没有共享密钥，车机麦克风不可用")
            return
        }
        val format = client.carMicFormat()
        if (format == null) {
            log("CARMIC: 车机 /info 未声明 speechRecognition 的 PCM 单声道输入——车机麦克风不可用")
            return
        }
        log("CARMIC: 车机麦克风可用（${format.sampleRate}Hz 单声道），有 App 录音时自动切换")
        val bound = Binding(client, sharedSecret, ownPackage, mediaStreamType)
        binding = bound
        watcher =
            Thread({ watchLoop(bound) }, "car-mic-watch").apply {
                isDaemon = true
                start()
            }
    }

    /** 会话结束：停监视 + 收口（任意线程，幂等）。 */
    fun detach(reason: String) {
        binding = null
        watcher?.interrupt()
        watcher = null
        stop(reason)
    }

    /** 车机语音键（后台线程）。助理聆听中再按 = 结束。 */
    fun onVoiceButton() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPressAt < PRESS_DEBOUNCE_MS) return
        lastPressAt = now
        if (active != null) {
            stop("车机语音键再次按下")
            Shell.exec("cmd voiceinteraction hide", timeoutMs = 3_000L)
            return
        }
        val bound = binding
        val started = bound != null && start(bound, "车机语音键", graceMs = VOICE_BUTTON_GRACE_MS)
        if (!started) log("CARMIC: 车机麦克风不可用——照常唤起助理（使用手机麦克风）")
        val shown = Shell.exec("cmd voiceinteraction show", timeoutMs = 3_000L)
        log("CARMIC: 唤起语音助理 ok=${shown.ok}${if (shown.ok) "" else "（${shown.output.take(80)}）"}")
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
                val now = SystemClock.uptimeMillis()
                val current = active
                if (recorders.isNotEmpty()) {
                    quietSince = 0L
                    if (current == null && now >= retryAfter) {
                        if (!start(bound, "录音中：${recorders.joinToString()}", graceMs = 0L)) {
                            // 失败不要每 400ms 狂试（会刷爆日志、反复注册策略）：歇一阵再试
                            retryAfter = now + RETRY_BACKOFF_MS
                            log("CARMIC: 开启失败，${RETRY_BACKOFF_MS / 1000}s 后再试")
                        }
                    } else if (recorders.joinToString() != logged) {
                        logged = recorders.joinToString()
                    }
                } else if (current != null) {
                    if (quietSince == 0L) quietSince = now
                    when {
                        now < current.graceUntil -> Unit
                        now - quietSince >= QUIET_STOP_MS -> stop("没有 App 在录音了")
                    }
                } else {
                    quietSince = 0L
                }
                if (current != null && now - current.startedAt >= MAX_SESSION_MS) stop("超过最长时限")
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
                log("CARMIC: Shizuku 服务不可用，无法注入麦克风")
                return false
            }
        val socket =
            runCatching { DatagramSocket(0).apply { soTimeout = SOCKET_TIMEOUT_MS } }.getOrElse {
                log("CARMIC: 无法开 UDP 端口：${it.message}")
                return false
            }
        val pipe =
            runCatching { service.startMicInjection(format.sampleRate) }.getOrNull() ?: run {
                log("CARMIC: 麦克风注入启动失败（详见 /data/local/tmp/carinput_svc.log）")
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
        // 与媒体音频同理：RECORD 之后加入的流要再 RECORD 一次才被纳入会话
        client.record()
        if (bound.mediaStreamType() == CarMic.STREAM_TYPE_MAIN_AUDIO) {
            log("CARMIC: 注意媒体音频同为 type=100——部分车机 TEARDOWN 只看 type，结束时可能连带拆掉媒体流")
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
        log("CARMIC: 开启车机麦克风（$reason）")
        val firstPacket = CountDownLatch(1)
        Thread({ receiveLoop(session, firstPacket) }, "car-mic-rx").apply {
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
            log("CARMIC: 车机没给下行端口（dataPort=${info.carDataPort} host=${client.activeHost}）——不发静音保活")
        }
        val arrived = firstPacket.await(FIRST_PACKET_WAIT_MS, TimeUnit.MILLISECONDS)
        log(
            if (arrived) {
                "CARMIC: ✓ 车机麦克风已到达（${SystemClock.uptimeMillis() - session.startedAt}ms）——注入中"
            } else {
                "CARMIC: ${FIRST_PACKET_WAIT_MS}ms 内没收到车机麦克风包——保持注入继续等"
            },
        )
        return true
    }

    /**
     * 下行静音：每 20ms 一包。接收端（WinPlay 实证）收到这条流的第一个下行包才开麦，
     * 真 iPhone 在 Siri/通话开始时也会在这条流上出声。
     */
    private fun downlinkLoop(
        session: Active,
        host: InetAddress,
    ) {
        val samples = session.info.format.sampleRate / 50
        val silence = ByteArray(samples * 2)
        var sequence = 0
        var timestamp = 0L
        var counter = 0L
        var next = SystemClock.uptimeMillis()
        try {
            while (active === session) {
                val wire = CarMic.sealDownlink(session.info.outputKey, sequence, timestamp, counter, silence)
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
            if (active === session) log("CARMIC: 下行静音中断 - ${error.message}")
        }
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
                        log("CARMIC: 解不开的包 len=${packet.length} from=${packet.address}（第 ${session.badPackets} 个）")
                    }
                    if (session.badPackets == 1) diagnoseBadPacket(session, buffer.copyOf(packet.length))
                    continue
                }
                session.packets++
                firstPacket.countDown()
                session.output.write(pcm)
            }
        } catch (error: Exception) {
            if (active === session) log("CARMIC: 接收结束 - ${error.message}")
        } finally {
            firstPacket.countDown()
        }
    }

    /**
     * 第一个解不开的包：换几种密钥/布局试解，把哪种能解开记进日志（WinPlay 读码看与我们一致，
     * 但实测全部认证失败——用数据而不是猜测定位差异）。只记密钥指纹，不记明文密钥。
     */
    private fun diagnoseBadPacket(
        session: Active,
        packet: ByteArray,
    ) {
        val info = session.info
        val head = packet.copyOf(minOf(16, packet.size)).joinToString(" ") { "%02x".format(it) }
        val tail = packet.copyOfRange(maxOf(0, packet.size - 8), packet.size).joinToString(" ") { "%02x".format(it) }

        fun fp(key: ByteArray) = key.copyOf(4).joinToString("") { "%02x".format(it) }
        // JS 接收端（WinPlay/Node）若把 int64 读成 Number，盐里的连接 ID 会被舍入成双精度的最短十进制
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
                io.github.shaonialife.cardeck.airplay.AirPlayCrypto.hkdfSha512(
                    secret,
                    "DataStream-Salt$jsId".toByteArray(),
                    "DataStream-Input-Encryption-Key".toByteArray(),
                )
        }
        val results =
            candidates.map { (name, key) ->
                val ok = CarMic.openPacket(key, packet) != null
                "$name(${fp(key)})=${if (ok) "✓" else "✗"}"
            }
        log(
            "CARMIC: 诊断 connId=${info.streamConnectionId} 头=[$head] 尾nonce=[$tail] " +
                results.joinToString(" "),
        )
    }

    private fun boundSecret(): ByteArray? = binding?.sharedSecret

    /** 收口（任意线程；幂等）：关收发、撤注入、TEARDOWN 麦克风流。 */
    fun stop(reason: String) {
        val session = synchronized(lock) { active.also { active = null } } ?: return
        runCatching { session.socket.close() }
        runCatching { session.output.close() }
        runCatching { CarInputClient.service?.stopMicInjection() }
        session.client.teardownMicStream(session.info)
        log(
            "CARMIC: 结束（$reason）收包=${session.packets} 坏包=${session.badPackets} 下行=${session.sent} " +
                "历时=${(SystemClock.uptimeMillis() - session.startedAt) / 1000}s——已恢复手机麦克风",
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
}
