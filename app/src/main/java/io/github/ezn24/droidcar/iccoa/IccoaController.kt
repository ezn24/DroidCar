package io.github.ezn24.droidcar.iccoa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.github.ezn24.droidcar.SessionController
import io.github.ezn24.droidcar.audio.SystemAudioTap
import io.github.ezn24.droidcar.capture.HiddenDisplaySource
import io.github.ezn24.droidcar.input.TouchReports
import io.github.ezn24.droidcar.input.TouchRouter
import io.github.ezn24.droidcar.ui.ScreenDimmer
import io.github.ezn24.droidcar.util.Bg
import io.github.ezn24.droidcar.util.SessionHooks
import io.github.ezn24.droidcar.video.ProjectionEncoder

/**
 * ICCOA CarLink sessions (Xiaomi CarWith / OPPO Car+ / vivo Jovi InCar head units; experimental). Runs on the same
 * connect switch as CarPlay and CarLife: BLE scan for an ICCOA receiver, GATT bootstrap, the 6-digit PIN the car
 * shows ([pinRequested] / [submitPin]), the car's AP, signed P-256 auth, then WFD-style RTSP + RTP/MPEG-TS video
 * and UIBC input (ported from FT, see the files in this package). Video is DroidCar's launcher, like CarPlay.
 * All state changes run on the main looper, as the ported session expects.
 */
object IccoaController {
    private val log: (String) -> Unit = { SessionController.appendLog(it) }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var app: Context? = null
    private var discovery: IccoaDiscovery? = null
    private var session: IccoaSession? = null
    private var transport: IccoaMediaTransport? = null
    private var connectingTo: IccoaPeer? = null
    private val audioState = IccoaAudioPlayerState()
    private var lastConfig: IccoaVideoConfig? = null

    @Volatile private var encoder: ProjectionEncoder? = null

    @Volatile private var source: HiddenDisplaySource? = null

    @Volatile private var audio: SystemAudioTap? = null

    @Volatile var enabled = false
        private set

    @Volatile var streaming = false
        private set

    /** The car asked for its PIN: the phone UI shows the entry dialog. */
    @Volatile var pinRequested = false
        private set

    /** Last status line of the connection (shown with the PIN dialog). */
    @Volatile var status: String? = null
        private set

    @Volatile var framesSent = 0L
        private set

    @Volatile var targetFps = 0
        private set

    private var missingLogged = false

    fun missingPermissions(context: Context): List<String> =
        buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(android.Manifest.permission.BLUETOOTH_SCAN)
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

    fun start(context: Context) {
        val ctx = context.applicationContext
        if (missingPermissions(ctx).isNotEmpty()) {
            if (!missingLogged) log("ICCOA: missing permissions ${missingPermissions(ctx)}, not scanning")
            missingLogged = true
            return
        }
        main.post {
            if (enabled) return@post
            enabled = true
            app = ctx
            discovery = IccoaDiscovery(ctx) { state -> onDiscovery(state) }
            log("ICCOA: looking for an ICCOA CarLink head unit (Bluetooth LE)")
            discovery?.start()
        }
    }

    fun stop() {
        main.post {
            if (!enabled) return@post
            enabled = false
            main.removeCallbacksAndMessages(RESCAN)
            discovery?.stop()
            discovery = null
            endConnection("ICCOA turned off")
            log("ICCOA: stopped")
        }
    }

    fun submitPin(pin: String) {
        main.post {
            pinRequested = false
            session?.submitPin(pin)
        }
    }

    /** The user dismissed the PIN dialog: give up this car until the next connect. */
    fun cancelPin() {
        main.post {
            pinRequested = false
            endConnection("PIN entry cancelled")
        }
    }

    // ---- discovery and session ----

    private fun onDiscovery(state: IccoaDiscoveryState) {
        if (!enabled || session != null) return
        when (state.phase) {
            IccoaPhase.SCANNING, IccoaPhase.SCAN_COMPLETE -> {
                val car = pick(state.peers)
                if (car != null) {
                    discovery?.stop()
                    connect(car)
                } else if (state.phase == IccoaPhase.SCAN_COMPLETE) {
                    rescanLater()
                }
            }
            IccoaPhase.FAILED -> rescanLater()
            IccoaPhase.BLUETOOTH_OFF, IccoaPhase.PERMISSION_REQUIRED, IccoaPhase.LOCATION_OFF ->
                log("ICCOA: cannot scan (${state.phase})")
            else -> Unit
        }
    }

    /** The car DroidCar paired with last time, else the strongest connectable ICCOA receiver. */
    private fun pick(peers: List<IccoaPeer>): IccoaPeer? {
        val remembered = app?.let { prefs(it).getString(KEY_ADDRESS, null) }
        peers.firstOrNull { it.connectable && it.address.equals(remembered, true) }?.let { return it }
        return peers.filter { it.connectable && it.hint }.maxByOrNull { it.rssi }
    }

    private fun rescanLater() {
        main.removeCallbacksAndMessages(RESCAN)
        main.postAtTime({ if (enabled && session == null) discovery?.start() }, RESCAN, android.os.SystemClock.uptimeMillis() + RESCAN_MS)
    }

    private fun connect(car: IccoaPeer) {
        val ctx = app ?: return
        connectingTo = car
        log("ICCOA: connecting to an ICCOA receiver (rssi ${car.rssi})")
        val s =
            IccoaSession(
                ctx,
                onStatus = { text -> status = text },
                onPinRequired = { pinRequested = true },
                onReady = { network, peer ->
                    val t =
                        IccoaMediaTransport(
                            network,
                            peer,
                            decryptInput = { session?.decryptInput(it) ?: error("Session ended") },
                            onPlay = { config -> main.post { play(config) } },
                            onPause = { main.post { transport?.pause() } },
                            onKeyframe = { encoder?.requestKeyFrame() },
                            onTouch = ::touch,
                            onKey = ::key,
                            onClosed = { reason, _ -> main.post { endConnection(reason, byCar = true) } },
                        )
                    t.start()
                    transport = t
                },
                onMessage = { category, method, body -> main.post { control(category, method, body) } },
                onClosed = { reason, _ -> endConnection(reason, byCar = true) },
                trustedCarName = car.name,
            )
        session = s
        s.start(car.address)
    }

    private fun control(
        category: Int,
        method: Int,
        body: ByteArray,
    ) {
        if (category != 1) return
        when (method) {
            IccoaPortNegotiation.REQUEST ->
                IccoaPortNegotiation.responseFor(body)?.let { session?.sendControl(IccoaPortNegotiation.RESPONSE, it) }
            4 -> lastConfig?.let(::play)
            5, 41 -> {
                transport?.pause()
                audioState.pause()
                flushAudioState()
            }
            16 -> key(KeyEvent.KEYCODE_SEARCH)
            25 -> runCatching { log("ICCOA: car config ${IccoaCarConfig.describe(body)}") }
            26 -> session?.sendControl(27, byteArrayOf())
        }
    }

    // ---- projection ----

    private fun play(config: IccoaVideoConfig) {
        val ctx = app ?: return
        val output = transport ?: return
        lastConfig = config
        if (!streaming) {
            if (SessionController.isStreaming() ||
                io.github.ezn24.droidcar.carlife.CarLifeController.streaming ||
                io.github.ezn24.droidcar.aa.AndroidAutoController.streaming
            ) {
                log("ICCOA: another projection (CarPlay / CarLife) is running, not starting ICCOA video")
                return
            }
            val plan = IccoaVideoPlan.from(config)
            // ICCOA won: the CarPlay wait and CarLife listening stop before our displays come up
            SessionController.stopCarPlayForOtherProtocol(ctx, "ICCOA")
            io.github.ezn24.droidcar.carlife.CarLifeController
                .stop()
            io.github.ezn24.droidcar.aa.AndroidAutoController
                .stop()
            val enc =
                ProjectionEncoder(
                    plan.streamWidth,
                    plan.streamHeight,
                    plan.fps,
                    plan.bitrate,
                    log,
                    onConfig = output::configure,
                    onFrame = { frame, key, pts ->
                        framesSent++
                        output.frame(frame, key, pts)
                    },
                    constantBitrate = true,
                )
            val surface = enc.start() ?: return endConnection("no video encoder")
            encoder = enc
            targetFps = plan.fps
            // UIBC touch is already scaled to the stream by the transport: identity mapping here
            TouchRouter.setCoordinateScale(plan.streamWidth, plan.streamHeight, plan.streamWidth, plan.streamHeight)
            val density = (420f * minOf(plan.streamWidth, plan.streamHeight) / 540f).toInt().coerceIn(120, 1680)
            val w = plan.streamWidth
            val h = plan.streamHeight
            Bg.run("iccoa-displays") {
                val src = HiddenDisplaySource(ctx, log)
                if (!src.attach(surface, w, h, density)) {
                    src.release()
                    main.post { endConnection("virtual displays failed (is Shizuku ready?)") }
                    return@run
                }
                main.post {
                    if (encoder !== enc) {
                        src.release()
                        return@post
                    }
                    source = src
                    streaming = true
                    ScreenDimmer.setSessionActive(true)
                    SessionHooks.onConnected(ctx)
                    connectingTo?.let { prefs(ctx).edit().putString(KEY_ADDRESS, it.address).apply() }
                    audio =
                        SystemAudioTap(SAMPLE_RATE, CHANNELS, log) { pcm -> transport?.audio(pcm) }.also { it.start() }
                    SessionController.maybeAutoPlay(ctx)
                    log("ICCOA: projecting ${w}x$h@${plan.fps} (car asked ${config.width}x${config.height}@${config.fps})")
                }
            }
        }
        output.resume()
        encoder?.requestKeyFrame()
        audioState.play()
        flushAudioState()
        main.postDelayed({ session?.sendControl(24, byteArrayOf()) }, CONFIG_REQUEST_MS)
    }

    private fun flushAudioState() {
        audioState.flush { session?.sendControl(8, it) == true }
    }

    private fun touch(t: CarTouch) {
        val ctx = app ?: return
        if (!streaming) return

        fun contact(
            slot: Int,
            x: Int,
            y: Int,
            down: Boolean,
        ) = TouchRouter.onContact(ctx, TouchReports.Contact(slot, x.coerceAtLeast(0), y.coerceAtLeast(0), down))
        when (t.action) {
            CarTouch.DOWN -> contact(0, t.x, t.y, true)
            CarTouch.MOVE -> {
                contact(0, t.x, t.y, true)
                if (t.x2 >= 0 && t.y2 >= 0) contact(1, t.x2, t.y2, true)
            }
            CarTouch.UP -> {
                if (t.x2 >= 0 && t.y2 >= 0) contact(1, t.x2, t.y2, false)
                contact(0, t.x, t.y, false)
            }
            CarTouch.POINTER_DOWN -> {
                contact(0, t.x, t.y, true)
                contact(1, if (t.x2 >= 0) t.x2 else t.x, if (t.y2 >= 0) t.y2 else t.y, true)
            }
            CarTouch.POINTER_UP ->
                if (t.index ==
                    0
                ) {
                    contact(0, t.x, t.y, false)
                } else {
                    contact(1, if (t.x2 >= 0) t.x2 else t.x, if (t.y2 >= 0) t.y2 else t.y, false)
                }
        }
    }

    /** UIBC generic keys arrive as Android key codes. */
    private fun key(code: Int) {
        if (!streaming) return
        when (code) {
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            ->
                Bg.run("iccoa-key") {
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
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_HEADSETHOOK,
            -> SessionController.dispatchMediaKey(if (code == KeyEvent.KEYCODE_HEADSETHOOK) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE else code)
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST ->
                Bg.run("iccoa-voice") {
                    io.github.ezn24.droidcar.audio.CarMicController
                        .onVoiceButton()
                }
            else -> log("ICCOA: car key $code has no action")
        }
    }

    /** Ends the current car (session, transport, projection); keeps scanning while enabled. Main looper. */
    private fun endConnection(
        reason: String,
        byCar: Boolean = false,
    ) {
        val hadSession = session != null || transport != null
        val wasStreaming = streaming
        if (byCar && wasStreaming) app?.let { ctx -> Bg.run("iccoa-ended") { SessionController.onOtherProtocolEnded(ctx, "ICCOA") } }
        streaming = false
        pinRequested = false
        audio?.stop()
        audio = null
        transport?.close()
        transport = null
        session?.stop()
        session = null
        connectingTo = null
        lastConfig = null
        audioState.reset()
        val src = source
        source = null
        val enc = encoder
        encoder = null
        Bg.run("iccoa-release") {
            src?.release()
            enc?.stop()
        }
        if (wasStreaming) {
            TouchRouter.reset()
            ScreenDimmer.setSessionActive(false)
            app?.let { SessionHooks.onDisconnected(it) }
        }
        if (hadSession) log("ICCOA: connection ended - $reason")
        if (enabled) rescanLater()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "iccoa"
    private const val KEY_ADDRESS = "car_address"
    private const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 2
    private const val RESCAN_MS = 3_000L
    private const val CONFIG_REQUEST_MS = 3_000L
    private val RESCAN = Any()
}
