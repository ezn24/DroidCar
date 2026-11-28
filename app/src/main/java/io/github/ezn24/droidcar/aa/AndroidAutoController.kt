package io.github.ezn24.droidcar.aa

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.KeyEvent
import io.github.ezn24.droidcar.SessionController
import io.github.ezn24.droidcar.aa.cert.CertificateStore
import io.github.ezn24.droidcar.aa.transport.TcpServer
import io.github.ezn24.droidcar.aa.transport.UsbAccessoryTransport
import io.github.ezn24.droidcar.aa.transport.WirelessConnector
import io.github.ezn24.droidcar.aa.transport.WirelessPermissionException
import io.github.ezn24.droidcar.audio.SystemAudioTap
import io.github.ezn24.droidcar.capture.HiddenDisplaySource
import io.github.ezn24.droidcar.input.TouchReports
import io.github.ezn24.droidcar.input.TouchRouter
import io.github.ezn24.droidcar.render.InsetSurface
import io.github.ezn24.droidcar.ui.ScreenDimmer
import io.github.ezn24.droidcar.util.Bg
import io.github.ezn24.droidcar.util.SessionHooks
import io.github.ezn24.droidcar.video.ProjectionEncoder
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.PhoneDescription
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.SessionOptions
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.MicrophoneChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.channels.WifiProjectionChannel

/**
 * Android Auto sessions (experimental), phone side, on AARE's AAP stack (`:aap`, ported). Three ways in:
 * - wired: the head unit switches the phone into USB accessory mode ([onUsbAccessory], started from
 *   [UsbAccessoryActivity], whatever the connect switch says, since plugging in is the user's action);
 * - wireless: with the shared connect switch, Bluetooth RFCOMM to a bonded head unit offering wireless
 *   Android Auto, its Wi-Fi, then TCP to its AAP server ([WirelessConnector]);
 * - bench: AARE's desktop head unit through `adb forward tcp:5277 localabstract:aare_hu` ([TcpServer], adb only).
 * The car sees DroidCar's launcher, like the other protocols; head units that only accept Google's certificate
 * need one imported or downloaded into [CertificateStore].
 */
object AndroidAutoController {
    private val log: (String) -> Unit = { SessionController.appendLog(it) }
    private val aapLog =
        object : AapLog {
            override fun d(msg: String) = Unit

            override fun i(msg: String) = log("AA: $msg")

            override fun w(
                msg: String,
                t: Throwable?,
            ) = log("AA: WARN $msg${t?.let { " - ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")

            override fun e(
                msg: String,
                t: Throwable?,
            ) = log("AA: ERROR $msg${t?.let { " - ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")
        }

    @Volatile private var app: Context? = null

    @Volatile var enabled = false
        private set

    @Volatile var streaming = false
        private set

    @Volatile var framesSent = 0L
        private set

    @Volatile var targetFps = 0
        private set

    @Volatile private var session: AapSession? = null
    private var server: TcpServer? = null
    private var wirelessThread: Thread? = null

    /** Which stored certificate the next attempt offers (moves on when a head unit refuses one). */
    @Volatile private var certificateAttempt = 0

    @Volatile private var video: VideoChannel? = null
    private var input: InputChannel? = null
    private val audio = ArrayList<AudioChannel>()

    @Volatile private var mic: MicrophoneChannel? = null

    /** Car microphone PCM goes into the Shizuku mic injection while an app records (null = phone mic in use). */
    @Volatile private var micOut: java.io.OutputStream? = null

    @Volatile private var micWatch: Thread? = null

    @Volatile private var media: AudioChannel? = null

    @Volatile private var encoder: ProjectionEncoder? = null

    @Volatile private var inset: InsetSurface? = null

    @Volatile private var source: HiddenDisplaySource? = null

    @Volatile private var tap: SystemAudioTap? = null

    @Volatile private var lastPcmAt = 0L
    private var permissionLogged = false

    // ---- entry points ----

    fun start(context: Context) {
        val ctx = context.applicationContext
        synchronized(this) {
            if (enabled) return
            enabled = true
            app = ctx
        }
        runCatching {
            server = TcpServer(lan = false) { transport -> runSession(transport, "adb bench", wireless = false) }.also { it.start() }
            log("AA: developer head-unit server on @${TcpServer.LOCAL_NAME} (adb forward tcp:5277 localabstract:${TcpServer.LOCAL_NAME})")
        }.onFailure { log("AA: developer head-unit server not started - ${it.message}") }
        wirelessThread =
            Thread({ wirelessLoop(ctx) }, "aa-wireless").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        synchronized(this) {
            if (!enabled) return
            enabled = false
        }
        runCatching { server?.stop() }
        server = null
        wirelessThread?.interrupt()
        wirelessThread = null
        session?.stop()
        Bg.run("aa-stop") { endProjection("Android Auto turned off") }
    }

    /** A head unit switched the phone into USB accessory mode (Android Auto over a cable). */
    fun onUsbAccessory(
        context: Context,
        accessory: UsbAccessory,
    ) {
        val ctx = context.applicationContext
        app = ctx
        if (session != null) return log("AA: USB head unit attached while a session runs, ignored")
        if (!UsbAccessoryTransport.isAndroidAuto(accessory)) return
        Bg.run("aa-usb") {
            val transport =
                runCatching { UsbAccessoryTransport(ctx.getSystemService(UsbManager::class.java), accessory) }
                    .onFailure { log("AA: cannot open the USB accessory - ${it.message}") }
                    .getOrNull() ?: return@run
            runSession(transport, "USB", wireless = false)
        }
    }

    private fun wirelessLoop(ctx: Context) {
        val connector = WirelessConnector(ctx, aapLog)
        while (enabled) {
            try {
                if (session == null && !otherProjectionRunning()) {
                    val car = connector.findConnectedHeadUnits().firstOrNull()
                    if (car != null) {
                        log("AA: a bonded head unit offers wireless Android Auto, connecting")
                        val transport = connector.connectBlocking(car)
                        if (enabled) runSession(transport, "wireless", wireless = true) else transport.close()
                    }
                }
                Thread.sleep(WIRELESS_POLL_MS)
            } catch (_: InterruptedException) {
                return
            } catch (e: WirelessPermissionException) {
                if (!permissionLogged) log("AA: wireless needs ${e.missing.map { it.substringAfterLast('.') }}")
                permissionLogged = true
                runCatching { Thread.sleep(WIRELESS_POLL_MS) }.onFailure { return }
            } catch (e: Exception) {
                log("AA: wireless attempt failed - ${e.message}")
                runCatching { Thread.sleep(WIRELESS_POLL_MS) }.onFailure { return }
            }
        }
    }

    private fun otherProjectionRunning() =
        SessionController.isStreaming() ||
            io.github.ezn24.droidcar.carlife.CarLifeController.streaming ||
            io.github.ezn24.droidcar.iccoa.IccoaController.streaming

    // ---- session ----

    private fun runSession(
        transport: Transport,
        via: String,
        wireless: Boolean,
    ) {
        val ctx = app ?: return transport.close()
        synchronized(this) {
            if (session != null || otherProjectionRunning()) {
                log("AA: $via connection refused - another session runs")
                transport.close()
                return
            }
        }
        val entry = CertificateStore(ctx).pick(certificateAttempt)
        log("AA: $via session starting (certificate '${entry.name}'${if (entry.isGoogleIssued) ", Google-issued" else ""})")
        val s =
            AapSession(
                transport,
                entry.identity,
                PhoneDescription(deviceName = Build.MODEL, label = "DroidCar", instanceId = instanceId(ctx)),
                callbacks,
                aapLog,
                SessionOptions(sendPings = wireless),
            )
        session = s
        s.start()
    }

    private val callbacks =
        object : AapSession.Callbacks {
            override fun onServiceDiscovery(
                session: AapSession,
                response: ServiceDiscoveryResponse,
            ): List<ServiceChannel> {
                log("AA: head unit '${response.displayName}' offers ${response.channelsCount} services")
                val channels = ArrayList<ServiceChannel>()
                audio.clear()
                for (s in response.channelsList) channelFor(s, channels)
                return channels
            }

            override fun onRunning(session: AapSession) = log("AA: session running")

            override fun onClosed(
                session: AapSession,
                reason: String,
                error: Throwable?,
            ) {
                if (session.certificateRefusal != null) {
                    certificateAttempt++
                    log(
                        "AA: the head unit refused the phone certificate; the next attempt offers another one (import Google's for strict head units)",
                    )
                }
                log("AA: session closed - $reason")
                if (this@AndroidAutoController.session === session) this@AndroidAutoController.session = null
                Bg.run("aa-closed") {
                    val wasStreaming = streaming
                    endProjection(reason)
                    if (wasStreaming) app?.let { SessionController.onOtherProtocolEnded(it, "Android Auto") }
                }
            }
        }

    private fun channelFor(
        s: Service,
        channels: MutableList<ServiceChannel>,
    ) {
        when {
            s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 -> {
                // Main display only (display type 0); an instrument cluster stream is not offered yet
                if (s.mediaSinkService.displayType.number == 0 && video == null) {
                    video = VideoChannel(s, videoListener).also { channels += it }
                }
            }
            s.hasMediaSinkService() -> {
                // Calls stay on the car's Bluetooth like with the official phone: no telephony stream
                if (s.mediaSinkService.audioType != AudioStreamType.AUDIO_STREAM_TELEPHONY) {
                    val ch = AudioChannel(s, audioListener)
                    audio += ch
                    channels += ch
                }
            }
            s.hasInputSourceService() -> if (input == null) input = InputChannel(s, inputListener).also { channels += it }
            s.hasMediaSourceService() -> if (mic == null) mic = MicrophoneChannel(s, micListener).also { channels += it }
            s.hasSensorSourceService() ->
                channels +=
                    SensorChannel(
                        s,
                        object : SensorChannel.Listener {
                            override fun onSensors(batch: aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch) =
                                Unit
                        },
                    )
            s.hasWifiProjectionService() -> channels += WifiProjectionChannel(s)
            else -> Unit
        }
    }

    private val audioListener =
        object : AudioChannel.Listener {
            override fun onAudioReady(
                channel: AudioChannel,
                config: AudioConfiguration,
            ) {
                if (channel.streamType == AudioStreamType.AUDIO_STREAM_MEDIA) {
                    media = channel
                    log("AA: media audio ${config.samplingRate} Hz, ${config.numberOfChannels} ch")
                }
            }
        }

    private val videoListener =
        object : VideoChannel.Listener {
            override fun chooseConfiguration(
                configs: List<VideoConfiguration>,
                preferred: List<Int>,
            ): Int = chooseVideo(configs, preferred)

            override fun onVideoStart(
                index: Int,
                config: VideoConfiguration,
            ) {
                Bg.run("aa-video") { startProjection(config) }
            }

            override fun onVideoFocus(projected: Boolean) {
                log("AA: video focus ${if (projected) "projected" else "native"}")
                if (projected) encoder?.requestKeyFrame()
            }

            override fun onKeyFrameNeeded() {
                encoder?.requestKeyFrame()
            }
        }

    // ---- projection ----

    private fun startProjection(config: VideoConfiguration) {
        val ctx = app ?: return
        val ch = video ?: return
        endProjection(null)
        if (SessionController.isStreaming() ||
            io.github.ezn24.droidcar.carlife.CarLifeController.streaming ||
            io.github.ezn24.droidcar.iccoa.IccoaController.streaming
        ) {
            log("AA: another projection runs, not starting Android Auto video")
            return
        }
        // Android Auto won: the other protocols stop waiting before our displays come up
        SessionController.stopCarPlayForOtherProtocol(ctx, "Android Auto")
        io.github.ezn24.droidcar.carlife.CarLifeController
            .stop()
        io.github.ezn24.droidcar.iccoa.IccoaController
            .stop()
        val (w, h) = resolution(config.codecResolution)
        val fps = if (config.frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else 30
        // The car shows the frame less its margins, centred
        val marginW = config.widthMargin.coerceIn(0, w - 2)
        val marginH = config.heightMargin.coerceIn(0, h - 2)
        val visW = ((w - marginW) / 2) * 2
        val visH = ((h - marginH) / 2) * 2
        val left = marginW / 2
        val top = marginH / 2
        val bitrate = (w.toLong() * h * fps / 8).toInt().coerceIn(2_000_000, 12_000_000)
        val enc =
            ProjectionEncoder(
                w,
                h,
                fps,
                bitrate,
                log,
                onConfig = { cfg -> ch.sendCodecConfig(cfg, 0, cfg.size) },
                onFrame = { frame, key, pts ->
                    framesSent++
                    // AAP needs 10 bytes of headroom in front of the frame (message id + timestamp)
                    val buf = ByteArray(frame.size + HEADROOM)
                    System.arraycopy(frame, 0, buf, HEADROOM, frame.size)
                    ch.sendFrame(buf, HEADROOM, frame.size, pts, key) { }
                },
            )
        val surface = enc.start() ?: return log("AA: no video encoder")
        encoder = enc
        val target =
            if (marginW > 0 || marginH > 0) {
                InsetSurface(surface, w, h, left, top, visW, visH, log).also { inset = it }.start()
                    ?: return log("AA: inset surface failed")
            } else {
                surface
            }
        mapTouch(visW, visH)
        val density = (420f * minOf(visW, visH) / 540f).toInt().coerceIn(120, 1680)
        val src = HiddenDisplaySource(ctx, log)
        if (!src.attach(target, visW, visH, density)) {
            src.release()
            log("AA: virtual displays failed (is Shizuku ready?)")
            return
        }
        source = src
        targetFps = fps
        streaming = true
        ScreenDimmer.setSessionActive(true)
        SessionHooks.onConnected(ctx)
        startAudio()
        startMicWatch(ctx.packageName)
        SessionController.maybeAutoPlay(ctx)
        log("AA: projecting ${visW}x$visH in a ${w}x$h@$fps frame")
    }

    /** Head-unit touches cover the visible area, in the declared panel's units (AARE's TouchMapper rules). */
    private fun mapTouch(
        visW: Int,
        visH: Int,
    ) {
        val panel = input?.touchScreen
        val pw = panel?.width ?: 0
        val ph = panel?.height ?: 0
        val visiblePixels = pw > visW && ph > visH && kotlin.math.abs(pw.toFloat() / ph - visW.toFloat() / visH) < 0.02f
        val srcX = if (visiblePixels || pw <= 0) visW else pw
        val srcY = if (visiblePixels || ph <= 0) visH else ph
        TouchRouter.setCoordinateScale(srcX, srcY, visW, visH)
        log("AA: touch panel ${pw}x$ph -> mapped from ${srcX}x$srcY")
    }

    private fun startAudio() {
        tap =
            SystemAudioTap(48_000, 2, log) { pcm ->
                val ch = media ?: return@SystemAudioTap
                if (System.currentTimeMillis() - lastPcmAt > AUDIO_IDLE_MS) {
                    session?.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
                    ch.startPlayback()
                }
                lastPcmAt = System.currentTimeMillis()
                val buf = ByteArray(pcm.size + HEADROOM)
                System.arraycopy(pcm, 0, buf, HEADROOM, pcm.size)
                ch.sendPcm(buf, HEADROOM, pcm.size, System.nanoTime() / 1000)
            }.also { it.start() }
        Thread({
            try {
                while (streaming) {
                    Thread.sleep(250)
                    val ch = media ?: continue
                    if (lastPcmAt > 0 && System.currentTimeMillis() - lastPcmAt > AUDIO_IDLE_MS) {
                        // Silence: let the car's own sources play again
                        ch.stopPlayback()
                        lastPcmAt = 0
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "aa-audio-idle").apply {
            isDaemon = true
            start()
        }
    }

    private val inputListener =
        object : InputChannel.Listener {
            override fun onInput(report: InputReport) {
                val ctx = app ?: return
                if (!streaming) return
                if (report.hasTouchEvent()) touch(ctx, report)
                if (report.hasKeyEvent()) {
                    for (k in report.keyEvent.keysList) if (k.down) key(k.keycode)
                }
            }
        }

    private fun touch(
        ctx: Context,
        report: InputReport,
    ) {
        val e = report.touchEvent
        val pointers = e.pointerDataList

        fun contact(
            i: Int,
            down: Boolean,
        ) {
            val p = pointers.getOrNull(i) ?: return
            TouchRouter.onContact(ctx, TouchReports.Contact(p.pointerId.coerceIn(0, 1), p.x, p.y, down))
        }
        when (e.action) {
            PointerAction.ACTION_DOWN, PointerAction.ACTION_MOVED -> pointers.indices.forEach { contact(it, true) }
            PointerAction.ACTION_UP -> pointers.indices.forEach { contact(it, false) }
            PointerAction.ACTION_POINTER_DOWN -> pointers.indices.forEach { contact(it, true) }
            PointerAction.ACTION_POINTER_UP -> contact(e.actionIndex, false)
            else -> Unit
        }
    }

    /** Head-unit keys use Android key codes. */
    private fun key(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            ->
                Bg.run("aa-key") {
                    SessionController.injectNavigationKey(
                        if (code ==
                            KeyEvent.KEYCODE_ENTER
                        ) {
                            KeyEvent.KEYCODE_DPAD_CENTER
                        } else {
                            code
                        },
                    )
                }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            -> SessionController.dispatchMediaKey(code)
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST ->
                Bg.run("aa-voice") {
                    io.github.ezn24.droidcar.audio.CarMicController
                        .onVoiceButton()
                }
            else -> log("AA: car key $code has no action")
        }
    }

    /** Releases displays, encoder and audio of the current projection. */
    private val micListener =
        object : MicrophoneChannel.Listener {
            override fun onMicOpened(ok: Boolean) = log("AA: car microphone ${if (ok) "open" else "refused by the head unit"}")

            override fun onMicData(
                data: ByteArray,
                offset: Int,
                length: Int,
                timestampUs: Long,
            ) {
                // Android Auto sends 16-bit little-endian PCM, which is what the injected AudioTrack plays
                runCatching { micOut?.write(data, offset, length) }
            }
        }

    /**
     * Same rule as CarPlay's CarMicController: while any other app records (the voice assistant, a call), the car's
     * microphone replaces the phone's through the Shizuku AudioPolicy injection; 2 s after the last recorder stops the
     * phone gets its microphone back.
     */
    private fun startMicWatch(ownPackage: String) {
        if (mic == null) return log("AA: the head unit offers no microphone, apps use the phone's")
        micWatch =
            Thread({
                var quietSince = 0L
                try {
                    while (streaming) {
                        Thread.sleep(MIC_WATCH_MS)
                        val recorders =
                            runCatching {
                                io.github.ezn24.droidcar.shizuku.CarInputClient.service
                                    ?.activeMicApps()
                            }.getOrNull()
                                .orEmpty()
                                .split(',')
                                .filter { it.isNotBlank() }
                                .map { it.substringAfter(':') }
                                .filter { it != ownPackage && it != "com.android.shell" && it != "android" }
                        if (recorders.isNotEmpty()) {
                            quietSince = 0L
                            if (micOut == null) openCarMic(recorders)
                        } else if (micOut != null) {
                            if (quietSince == 0L) quietSince = System.currentTimeMillis()
                            if (System.currentTimeMillis() - quietSince >= MIC_QUIET_MS) closeCarMic("no app recording any more")
                        }
                    }
                } catch (_: InterruptedException) {
                } finally {
                    closeCarMic("projection ended")
                }
            }, "aa-mic-watch").apply {
                isDaemon = true
                start()
            }
    }

    private fun openCarMic(recorders: List<String>) {
        val ch = mic ?: return
        val rate = ch.config?.samplingRate?.takeIf { it > 0 } ?: 16_000
        val pipe =
            runCatching {
                io.github.ezn24.droidcar.shizuku.CarInputClient.service
                    ?.startMicInjection(rate)
            }.getOrNull() ?: return log("AA: microphone injection failed to start, apps keep the phone microphone")
        micOut = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe)
        ch.openMic()
        log("AA: car microphone for ${recorders.joinToString()} ($rate Hz)")
    }

    private fun closeCarMic(reason: String) {
        val out = micOut ?: return
        micOut = null
        runCatching { mic?.closeMic() }
        runCatching { out.close() }
        runCatching {
            io.github.ezn24.droidcar.shizuku.CarInputClient.service
                ?.stopMicInjection()
        }
        log("AA: car microphone closed - $reason")
    }

    private fun endProjection(reason: String?) {
        val wasStreaming = streaming
        streaming = false
        micWatch?.interrupt()
        micWatch = null
        tap?.stop()
        tap = null
        source?.release()
        source = null
        inset?.release()
        inset = null
        encoder?.stop()
        encoder = null
        if (session == null) {
            video = null
            input = null
            media = null
            mic = null
        }
        if (wasStreaming) {
            TouchRouter.reset()
            ScreenDimmer.setSessionActive(false)
            app?.let { SessionHooks.onDisconnected(it) }
            if (reason != null) log("AA: projection ended - $reason")
        }
    }

    // ---- helpers ----

    /** Largest visible picture with a short side up to 1080 among the car's preferred H.264 configurations. */
    internal fun chooseVideo(
        configs: List<VideoConfiguration>,
        preferred: List<Int>,
    ): Int {
        val candidates = configs.indices.filter { preferred.isEmpty() || it in preferred }.ifEmpty { configs.indices.toList() }

        fun visible(i: Int) =
            resolution(configs[i].codecResolution).let { (w, h) -> (w - configs[i].widthMargin) to (h - configs[i].heightMargin) }

        fun score(i: Int): Long {
            val (w, h) = visible(i)
            val short = minOf(w, h)
            val fits = short <= 1080
            return (if (fits) 1_000_000_000L else 0L) + (if (fits) short else -short) * 100_000L + maxOf(w, h) * 10L +
                if (configs[i].frameRate == VideoFrameRateType.VIDEO_FPS_60) 1 else 0
        }
        return candidates.maxByOrNull(::score) ?: 0
    }

    internal fun resolution(r: VideoCodecResolutionType): Pair<Int, Int> =
        when (r) {
            VideoCodecResolutionType.VIDEO_800x480 -> 800 to 480
            VideoCodecResolutionType.VIDEO_1280x720 -> 1280 to 720
            VideoCodecResolutionType.VIDEO_1920x1080 -> 1920 to 1080
            VideoCodecResolutionType.VIDEO_2560x1440 -> 2560 to 1440
            VideoCodecResolutionType.VIDEO_3840x2160 -> 3840 to 2160
            VideoCodecResolutionType.VIDEO_720x1280 -> 720 to 1280
            VideoCodecResolutionType.VIDEO_1080x1920 -> 1080 to 1920
            VideoCodecResolutionType.VIDEO_1440x2560 -> 1440 to 2560
            VideoCodecResolutionType.VIDEO_2160x3840 -> 2160 to 3840
        }

    private fun instanceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTANCE, null)?.let { return it }
        val id =
            java.util.UUID
                .randomUUID()
                .toString()
        prefs.edit().putString(KEY_INSTANCE, id).apply()
        return id
    }

    private const val HEADROOM = 10
    private const val MIC_WATCH_MS = 400L
    private const val MIC_QUIET_MS = 2_000L
    private const val AUDIO_IDLE_MS = 1_000L
    private const val WIRELESS_POLL_MS = 5_000L
    private const val PREFS = "android_auto"
    private const val KEY_INSTANCE = "instance_id"
}
