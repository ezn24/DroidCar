package io.github.ezn24.droidcar.carlife

import android.content.Context
import android.view.KeyEvent
import io.github.ezn24.droidcar.SessionController
import io.github.ezn24.droidcar.capture.HiddenDisplaySource
import io.github.ezn24.droidcar.input.TouchReports
import io.github.ezn24.droidcar.input.TouchRouter
import io.github.ezn24.droidcar.ui.ScreenDimmer
import io.github.ezn24.droidcar.util.Bg
import io.github.ezn24.droidcar.util.SessionHooks

/**
 * Baidu CarLife sessions (experimental). While enabled the phone listens for a CarLife head unit: Bluetooth RFCOMM
 * bootstrap + Wi-Fi Direct, or a head unit on the phone's hotspot finding it through the UDP beacon. When the car
 * negotiates video, the same launcher as CarPlay (virtual displays + compositor) is drawn into a CarLife H.264 encoder;
 * touch goes through [TouchRouter], music through the Shizuku REMOTE_SUBMIX capture.
 *
 * Never runs together with a CarPlay session (both own the virtual displays and the system audio capture).
 */
object CarLifeController : CarLifeSession.Events {
    private val log: (String) -> Unit = { SessionController.appendLog(it) }

    @Volatile private var app: Context? = null
    private var link: CarLifeLink? = null
    private var session: CarLifeSession? = null
    private var bluetooth: CarLifeBluetooth? = null
    private var wifiDirect: CarLifeWifiDirect? = null
    private var discovery: CarLifeDiscovery? = null
    private val lock = Any()

    @Volatile private var encoder: io.github.ezn24.droidcar.video.ProjectionEncoder? = null

    @Volatile private var source: HiddenDisplaySource? = null

    @Volatile private var audioThread: Thread? = null

    @Volatile var enabled = false
        private set

    private var missingLogged = false

    /** Encoded frames handed to the car (hero card frame rate) and the negotiated rate. */
    @Volatile var framesSent = 0L
        private set

    @Volatile var targetFps = 0
        private set

    /** A car is drawing DroidCar right now. */
    @Volatile var streaming = false
        private set

    /** Permissions the CarLife bootstrap needs at runtime (RFCOMM listen + Wi-Fi Direct peers). */
    fun missingPermissions(context: Context): List<String> =
        buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) add(android.Manifest.permission.BLUETOOTH_CONNECT)
            add(
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    android.Manifest.permission.NEARBY_WIFI_DEVICES
                } else {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                },
            )
        }.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

    private const val NAV_LISTENER = "CarLife"
    private const val TURN_ICON_PX = 128

    fun start(context: Context) {
        if (missingPermissions(context).isNotEmpty()) {
            if (!missingLogged) log("CARLIFE: missing permissions ${missingPermissions(context)}, not listening yet")
            missingLogged = true
            return
        }
        synchronized(lock) {
            if (enabled) return
            enabled = true
            val ctx = context.applicationContext
            app = ctx
            SessionController.ensure(ctx)
            val l = CarLifeLink(log)
            val s = CarLifeSession(ctx, l, log).also { it.events = this }
            s.turnIcon = { info ->
                val bitmap =
                    io.github.ezn24.droidcar.render.AltScreenCard
                        .maneuverIcon(info, TURN_ICON_PX)
                java.io
                    .ByteArrayOutputStream()
                    .also { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        bitmap.recycle()
                    }.toByteArray()
            }
            val bt = CarLifeBluetooth(ctx, log) { l.connected }
            val p2p = CarLifeWifiDirect(ctx, log)
            val udp = CarLifeDiscovery(log) { !l.connected && !SessionController.isStreaming() }
            bt.onCarWifiName = { name -> p2p.join(name) }
            p2p.onAddress = { phoneIp, carIp ->
                bt.wifiDirectReady(phoneIp)
                udp.carAddress = carIp?.let { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
            }
            link = l
            session = s
            bluetooth = bt
            wifiDirect = p2p
            discovery = udp
            s.start()
            bt.start()
            p2p.start()
            udp.start()
            log("CARLIFE: listening for a CarLife head unit (Bluetooth, Wi-Fi Direct, hotspot)")
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!enabled) return
            enabled = false
            session?.stop()
            bluetooth?.stop()
            wifiDirect?.stop()
            discovery?.stop()
            session = null
            link = null
            bluetooth = null
            wifiDirect = null
            discovery = null
        }
        Bg.run("carlife-stop") { endProjection("CarLife turned off") }
        log("CARLIFE: stopped listening")
    }

    // ---- session events ----

    override fun onVideoNegotiated(
        streamWidth: Int,
        streamHeight: Int,
        fps: Int,
    ) {
        val ctx = app ?: return
        if (SessionController.isStreaming() ||
            io.github.ezn24.droidcar.iccoa.IccoaController.streaming ||
            io.github.ezn24.droidcar.aa.AndroidAutoController.streaming
        ) {
            log("CARLIFE: another projection (CarPlay / ICCOA) is running, not starting CarLife video")
            return
        }
        Bg.run("carlife-video") {
            endProjection(null)
            // CarLife won: the CarPlay wait (Bluetooth, Wi-Fi join) stops before our displays come up
            SessionController.stopCarPlayForOtherProtocol(ctx, "CarLife")
            io.github.ezn24.droidcar.iccoa.IccoaController
                .stop()
            io.github.ezn24.droidcar.aa.AndroidAutoController
                .stop()
            val s = session ?: return@run
            val enc =
                io.github.ezn24.droidcar.video.ProjectionEncoder(
                    streamWidth,
                    streamHeight,
                    fps,
                    io.github.ezn24.droidcar.video.ProjectionEncoder
                        .carLifeBitrateFor(streamWidth),
                    log,
                    onConfig = { s.videoConfig(it) },
                    onFrame = { frame, key, _ ->
                        framesSent++
                        s.videoFrame(frame, key)
                    },
                )
            val surface = enc.start() ?: return@run
            encoder = enc
            // Same density rule as CarPlay: the launcher design scales with the canvas short side (base 540 px = 420 dpi)
            val density = (420f * minOf(streamWidth, streamHeight) / 540f).toInt().coerceIn(120, 1680)
            // CarLife touch arrives in stream pixels: identity mapping
            TouchRouter.setCoordinateScale(streamWidth, streamHeight, streamWidth, streamHeight)
            val src = HiddenDisplaySource(ctx, log)
            val ok = src.attach(surface, streamWidth, streamHeight, density)
            if (!ok) {
                log("CARLIFE: virtual displays failed (is Shizuku ready?)")
                src.release()
                enc.stop()
                encoder = null
                return@run
            }
            source = src
            targetFps = fps
            streaming = true
            ScreenDimmer.setSessionActive(true)
            SessionHooks.onConnected(ctx)
            startAudio()
            SessionController.maybeAutoPlay(ctx)
            // Turn info for the car's cluster / HUD: the same map readings as CarPlay route guidance
            io.github.ezn24.droidcar.nav.NavAccessibilityAccess
                .sync(ctx, log)
            io.github.ezn24.droidcar.nav.CarRouteGuidance
                .listen(NAV_LISTENER) { info -> session?.navigationUpdate(info) }
            log("CARLIFE: projecting ${streamWidth}x$streamHeight@$fps")
        }
    }

    override fun onProjecting(projecting: Boolean) {
        if (projecting) encoder?.requestKeyFrame()
    }

    override fun onFrameRate(fps: Int) {
        encoder?.setFrameRate(fps)
        targetFps = fps
        log("CARLIFE: car asked for $fps fps")
    }

    override fun onNeedKeyFrame() {
        encoder?.requestKeyFrame()
    }

    override fun onTouch(
        action: Int,
        x: Int,
        y: Int,
        x2: Int,
        y2: Int,
        index: Int,
    ) {
        val ctx = app ?: return
        if (!streaming) return

        fun contact(
            slot: Int,
            cx: Int,
            cy: Int,
            down: Boolean,
        ) = TouchRouter.onContact(ctx, TouchReports.Contact(slot, cx, cy, down))
        when (action) {
            CarLifeSession.TOUCH_DOWN -> contact(0, x, y, true)
            CarLifeSession.TOUCH_MOVE -> {
                contact(0, x, y, true)
                if (x2 >= 0 && y2 >= 0) contact(1, x2, y2, true)
            }
            CarLifeSession.TOUCH_UP -> {
                if (x2 >= 0 && y2 >= 0) contact(1, x2, y2, false)
                contact(0, x, y, false)
            }
            CarLifeSession.TOUCH_POINTER_DOWN -> {
                contact(0, x, y, true)
                contact(1, x2, y2, true)
            }
            CarLifeSession.TOUCH_POINTER_UP -> {
                if (index == 0) contact(0, x, y, false) else contact(1, if (x2 >= 0) x2 else x, if (y2 >= 0) y2 else y, false)
            }
        }
    }

    /**
     * Car hardware keys, Baidu's key table (CarlifeCarHardKeyCode, carlife_pc_tool constants from the vehicle SDK):
     * 1 home, 2 / 3 call / end, 6 / 7 selector, 9 media, 14 back, 15 / 16 seek, 20 OK, 21-28 directions,
     * 31 / 32 media start / stop, 33 voice. 79 / 219 / 231 are extra codes FT saw on real cars.
     */
    override fun onHardKey(key: Int) {
        val ctx = app ?: return
        if (!streaming) return
        val nav = { code: Int -> Bg.run("carlife-key") { SessionController.injectNavigationKey(code) } }
        when (key) {
            1 -> nav(KeyEvent.KEYCODE_HOME)
            2 -> SessionController.onPhoneKey(ctx, io.github.ezn24.droidcar.input.ConsumerKeys.PHONE_HOOK_SWITCH)
            3, 4 -> SessionController.onPhoneKey(ctx, io.github.ezn24.droidcar.input.ConsumerKeys.PHONE_DROP)
            6, 22 -> nav(KeyEvent.KEYCODE_DPAD_RIGHT)
            7, 21 -> nav(KeyEvent.KEYCODE_DPAD_LEFT)
            23 -> nav(KeyEvent.KEYCODE_DPAD_UP)
            24 -> nav(KeyEvent.KEYCODE_DPAD_DOWN)
            20 -> nav(KeyEvent.KEYCODE_DPAD_CENTER)
            9, 79 -> SessionController.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            31 -> SessionController.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            32 -> SessionController.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            14 -> nav(KeyEvent.KEYCODE_BACK)
            15 -> SessionController.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            16 -> SessionController.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            33, 219, 231, CarLifeSession.KEY_VOICE ->
                Bg.run("carlife-voice") {
                    io.github.ezn24.droidcar.audio.CarMicController
                        .onVoiceButton()
                }
            else -> log("CARLIFE: car key $key has no action")
        }
    }

    override fun onMusicControl(play: Boolean) {
        SessionController.dispatchMediaKey(if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    override fun onClosed(reason: String) {
        Bg.run("carlife-closed") {
            val wasStreaming = streaming
            endProjection(reason)
            if (wasStreaming) app?.let { SessionController.onOtherProtocolEnded(it, "CarLife") }
        }
    }

    /** Releases displays, encoder and audio of the current projection (keeps listening). */
    private fun endProjection(reason: String?) {
        val wasStreaming = streaming
        streaming = false
        stopAudio()
        source?.release()
        source = null
        encoder?.stop()
        encoder = null
        io.github.ezn24.droidcar.nav.CarRouteGuidance
            .unlisten(NAV_LISTENER)
        if (wasStreaming) {
            TouchRouter.reset()
            ScreenDimmer.setSessionActive(false)
            app?.let { SessionHooks.onDisconnected(it) }
            if (reason != null) log("CARLIFE: projection ended - $reason")
        }
    }

    private fun startAudio() {
        val service =
            io.github.ezn24.droidcar.shizuku.CarInputClient.service ?: run {
                log("CARLIFE: no Shizuku service, music stays on the phone")
                return
            }
        val pipe =
            runCatching { service.startAudioCapture(CarLifeMediaStream.SAMPLE_RATE, CarLifeMediaStream.CHANNELS) }
                .onFailure { log("CARLIFE: system audio capture unavailable: ${it.message}") }
                .getOrNull() ?: return
        audioThread =
            Thread({
                val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)
                // 20 ms of 48 kHz stereo 16-bit PCM per media packet
                val frame = ByteArray(CarLifeMediaStream.SAMPLE_RATE / 50 * CarLifeMediaStream.CHANNELS * 2)
                var sent = 0L
                try {
                    while (streaming && !Thread.currentThread().isInterrupted) {
                        if (io.github.ezn24.droidcar.audio
                                .readPcmFrame(input, frame) < 0
                        ) {
                            break
                        }
                        if (frame.any { it.toInt() != 0 }) {
                            session?.sendAudio(frame.copyOf())
                            if (++sent == 1L || sent % 3000 == 0L) log("CARLIFE: music packets sent to the car: $sent")
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { input.close() }
                }
            }, "carlife-audio").apply {
                isDaemon = true
                start()
            }
        log("CARLIFE: music goes to the car (system audio capture)")
    }

    private fun stopAudio() {
        val t = audioThread ?: return
        audioThread = null
        runCatching {
            io.github.ezn24.droidcar.shizuku.CarInputClient.service
                ?.stopAudioCapture()
        }
        t.interrupt()
    }
}
