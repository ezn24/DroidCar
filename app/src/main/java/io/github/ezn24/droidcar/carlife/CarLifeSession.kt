/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar
 * (protocol handling only; navigation, song info and the car microphone are not carried over yet).
 */
package io.github.ezn24.droidcar.carlife

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * One CarLife conversation with a head unit over a [CarLifeLink]: version match, optional content encryption, the
 * video negotiation (the car names its screen size, the phone answers with the stream size it will send), touch and
 * hardware keys from the control channel, music on the media channel.
 */
class CarLifeSession(
    private val context: Context,
    private val link: CarLifeLink,
    private val log: (String) -> Unit,
) : CarLifeLink.Listener {
    interface Events {
        /** The car wants video: encode [streamWidth]x[streamHeight] at [fps] (H.264 Baseline, Annex B). */
        fun onVideoNegotiated(
            streamWidth: Int,
            streamHeight: Int,
            fps: Int,
        )

        fun onProjecting(projecting: Boolean)

        fun onFrameRate(fps: Int)

        fun onNeedKeyFrame()

        /** Touch in stream pixels; [index] is the pointer for POINTER_DOWN / POINTER_UP. */
        fun onTouch(
            action: Int,
            x: Int,
            y: Int,
            x2: Int,
            y2: Int,
            index: Int,
        )

        fun onHardKey(key: Int)

        fun onMusicControl(play: Boolean)

        fun onClosed(reason: String)
    }

    var events: Events? = null

    @Volatile var matched = false
        private set

    @Volatile var projecting = false
        private set

    @Volatile var streamWidth = 0
        private set

    @Volatile var streamHeight = 0
        private set

    @Volatile var fps = START_FPS
        private set

    private val crypto = CarLifeCrypto(log)
    private val feed = CarLifeVideoFeed(::videoPacket, ::videoEmpty)
    private val media = CarLifeMediaStream(::mediaPacket, ::moduleUpdate)

    /** Turn icon renderer (PNG); set by the controller, empty = the car draws its own arrow from the turn code. */
    var turnIcon: (io.github.ezn24.droidcar.nav.NavInfo) -> ByteArray = { ByteArray(0) }

    private val navigation =
        CarLifeNavigation(
            send = { payload -> if (matched) cmd(CarLifeProtocol.CMD_NAVI_NEXT_TURN_INFO, payload, quiet = true) },
            module = { on -> moduleUpdate(CarLifeProtocol.MODULE_NAVI, if (on) 1 else 0) },
            icon = { turnIcon(it) },
        )

    /** Latest navigation reading (null = no route); reaches the car only while it subscribed to navigation. */
    fun navigationUpdate(info: io.github.ezn24.droidcar.nav.NavInfo?) = navigation.update(info)

    @Volatile private var newVehicle = false

    @Volatile private var initSeen = false
    private var videoRequest: Triple<Int, Int, Int>? = null
    private var musicStartSeen = false
    private var musicEndSeen = false
    private var frames = 0L

    @Volatile private var timer: Thread? = null

    @Volatile private var encryptProbeAt = 0L

    fun start() = link.start(this)

    fun stop() {
        if (matched) cmd(CarLifeProtocol.CMD_MD_MANUAL_DISCONNECT)
        link.stop()
        reset()
    }

    private fun reset() {
        projecting = false
        matched = false
        initSeen = false
        videoRequest = null
        encryptProbeAt = 0L
        crypto.reset()
        feed.close()
        media.forget()
        navigation.reset()
        timer?.interrupt()
        timer = null
    }

    // ---- outgoing streams ----

    fun videoConfig(config: ByteArray) = feed.config(config)

    fun videoFrame(
        frame: ByteArray,
        key: Boolean,
    ) = feed.frame(frame, key)

    fun sendAudio(pcm: ByteArray) {
        if (matched) media.data(pcm)
    }

    fun goToCarHome() {
        if (matched) cmd(CarLifeProtocol.CMD_GO_TO_DESKTOP)
    }

    private fun videoClock() = (System.currentTimeMillis() / 1000).toInt()

    private fun videoPacket(frame: ByteArray) {
        frames++
        if (frames <= 3) log("CARLIFE: video frame #$frames ${frame.size} bytes${if (crypto.active) " (encrypted)" else ""}")
        link.send(CarLifeProtocol.CH_VIDEO, CarLifeFraming.stream(CarLifeProtocol.VIDEO_DATA, crypto.encryptOut(frame), videoClock()))
    }

    private fun videoEmpty(heartbeat: Boolean) {
        val id = if (heartbeat) CarLifeProtocol.VIDEO_HEARTBEAT else CarLifeProtocol.VIDEO_DATA
        link.send(CarLifeProtocol.CH_VIDEO, CarLifeFraming.stream(id, ByteArray(0), videoClock()))
    }

    private fun mediaPacket(
        serviceId: Int,
        payload: ByteArray,
    ) {
        val body = if (serviceId == CarLifeProtocol.MEDIA_DATA) crypto.encryptOut(payload) else payload
        link.send(CarLifeProtocol.CH_MEDIA, CarLifeFraming.stream(serviceId, body))
    }

    private fun moduleUpdate(
        module: Int,
        state: Int,
    ) {
        if (!matched) return
        cmd(
            CarLifeProtocol.CMD_MODULE_STATUS,
            ProtoWriter().int32(1, 1).message(2, ProtoWriter().int32(1, module).int32(2, state)).toByteArray(),
        )
    }

    private fun cmd(
        serviceId: Int,
        payload: ByteArray = ByteArray(0),
        quiet: Boolean = false,
    ): Boolean {
        if (!quiet) log("CARLIFE: -> ${CarLifeProtocol.name(serviceId)} (${payload.size} bytes)")
        return link.send(CarLifeProtocol.CH_CMD, CarLifeFraming.cmd(serviceId, crypto.encryptOut(payload)))
    }

    // ---- link callbacks ----

    override fun onCommandConnected(remote: String) {
        log("CARLIFE: head unit connected from $remote, waiting for its protocol version")
    }

    override fun onClosed(reason: String) {
        log("CARLIFE: link closed - $reason")
        reset()
        events?.onClosed(reason)
    }

    override fun onMessage(
        channel: Int,
        head: ByteArray,
        body: ByteArray,
    ) {
        try {
            when (channel) {
                CarLifeProtocol.CH_CMD -> {
                    val c = CarLifeFraming.parseCmd(head, body)
                    handleCmd(c.serviceId, crypto.decryptIn(c.payload))
                }
                CarLifeProtocol.CH_CTRL -> {
                    val c = CarLifeFraming.parseCmd(head, body)
                    handleCtrl(c.serviceId, crypto.decryptIn(c.payload))
                }
                else -> Unit
            }
        } catch (t: Throwable) {
            log("CARLIFE: message on ${CarLifeProtocol.channelName(channel)} failed: ${t.message}")
        }
    }

    private fun handleCmd(
        id: Int,
        payload: ByteArray,
    ) {
        if (id !in QUIET_COMMANDS) log("CARLIFE: <- ${CarLifeProtocol.name(id)} (${payload.size} bytes)")
        when (id) {
            CarLifeProtocol.CMD_HU_PROTOCOL_VERSION -> onProtocolVersion(ProtoReader(payload))
            CarLifeProtocol.CMD_HU_INFO -> {
                val r = ProtoReader(payload)
                log("CARLIFE: head unit info ${r.fields.keys.joinToString { k -> "$k=${r.string(k)?.take(40) ?: r.int(k, 0)}" }}")
            }
            CarLifeProtocol.CMD_HU_FEATURE_CONFIG_RESPONSE -> {
                val features = ProtoReader(payload).messages(2).associate { m -> (m.string(1) ?: "") to m.int(2, -1) }
                log("CARLIFE: head unit features ${features.entries.joinToString { "${it.key}=${it.value}" }}")
                when (features["CONTENT_ENCRYPTION"]) {
                    1 -> startEncryption()
                    0 -> encryptProbeAt = 0L
                }
            }
            CarLifeProtocol.CMD_HU_RSA_PUBLIC_KEY_RESPONSE -> {
                if (crypto.active) return
                val request = crypto.aesKeyRequest(ProtoReader(payload).string(1) ?: "")
                if (request == null) {
                    log("CARLIFE: head unit RSA key unusable, staying in plaintext")
                } else {
                    crypto.armIncoming()
                    if (!cmd(CarLifeProtocol.CMD_MD_AES_KEY_SEND_REQUEST, ProtoWriter().string(1, request).toByteArray())) crypto.reset()
                }
            }
            CarLifeProtocol.CMD_HU_AES_REC_RESPONSE -> {
                if (crypto.enableOutgoing()) {
                    cmd(CarLifeProtocol.CMD_MD_ENCRYPT_READY)
                    log("CARLIFE: content encryption on")
                }
            }
            CarLifeProtocol.CMD_HU_BT_PAIR_INFO -> cmd(CarLifeProtocol.CMD_MD_BT_PAIR_INFO, btPairInfo())
            CarLifeProtocol.CMD_VIDEO_ENCODER_INIT -> onVideoInit(ProtoReader(payload))
            CarLifeProtocol.CMD_VIDEO_ENCODER_START -> {
                val mode = feed.start()
                projecting = true
                frames = 0
                startTimer()
                log("CARLIFE: projection ${if (mode == CarLifeVideoFeed.Mode.RESUMING) "back" else "started"}")
                events?.onProjecting(true)
                if (mode == CarLifeVideoFeed.Mode.RESUMING) events?.onNeedKeyFrame()
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_PAUSE -> {
                feed.pause()
                projecting = false
                events?.onProjecting(false)
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_RESET -> {
                feed.resendConfig()
                events?.onNeedKeyFrame()
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                val asked = ProtoReader(payload).int(1, -1)
                if (asked in 3..60) {
                    // Baidu never paces below 15: a lower ask keeps the current rate
                    val next = if (asked < LOWEST_PACE) fps else asked
                    if (next != fps) {
                        fps = next
                        events?.onFrameRate(next)
                    }
                    cmd(CarLifeProtocol.CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, ProtoWriter().int32(1, asked).toByteArray())
                }
            }
            CarLifeProtocol.CMD_STATISTIC_INFO -> {
                // Baidu's vehicle SDK sends VIDEO_ENCODER_INIT only after MD_AUTHEN_RESULT (carlife_pc_tool, built from it,
                // waits for it on protocol 4.0 too); FT skipped it on 3.2 / 4.x cars, which left the car waiting.
                cmd(CarLifeProtocol.CMD_MD_AUTHEN_RESULT, ProtoWriter().bool(1, true).toByteArray())
                cmd(CarLifeProtocol.CMD_MD_FEATURE_CONFIG_REQUEST)
            }
            CarLifeProtocol.CMD_HU_AUTHEN_REQUEST -> {
                val random = ProtoReader(payload).string(1) ?: ""
                cmd(CarLifeProtocol.CMD_MD_AUTHEN_RESPONSE, ProtoWriter().string(1, random).toByteArray())
            }
            CarLifeProtocol.CMD_LAUNCH_MODE_NORMAL -> media.launchedAgain()
            CarLifeProtocol.CMD_PAUSE_MEDIA -> events?.onMusicControl(false)
            CarLifeProtocol.CMD_MODULE_CONTROL -> onModuleControl(ProtoReader(payload))
            CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE -> {
                // Offer navigation (turn info for the cluster / HUD); other phone data is not provided yet
                val modules = ProtoReader(payload).messages(2).map { it.int(1, 0) }
                val w = ProtoWriter().int32(1, modules.size)
                for (m in modules) w.message(2, ProtoWriter().int32(1, m).bool(2, m == SUBSCRIBE_NAVIGATION))
                cmd(CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_DONE, w.toByteArray())
                log("CARLIFE: car asked for phone data $modules, navigation offered")
                navigation.subscribe(false)
            }
            CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_START, CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_STOP -> {
                val on = id == CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_START
                if (ProtoReader(payload).messages(2).any { it.int(1, -1) == SUBSCRIBE_NAVIGATION }) {
                    log("CARLIFE: navigation subscription ${if (on) "on" else "off"}")
                    navigation.subscribe(on)
                }
            }
            else -> Unit
        }
    }

    private fun onProtocolVersion(r: ProtoReader) {
        val major = r.int(1, 0)
        val minor = r.int(2, 0)
        if (matched) return
        newVehicle = major == 4 || (major == 3 && minor == 2)
        media.forget()
        media.oldVehicle = !newVehicle
        log("CARLIFE: head unit protocol $major.$minor (${if (newVehicle) "new" else "old"} vehicle)")
        cmd(CarLifeProtocol.CMD_PROTOCOL_VERSION_MATCH_STATUS, ProtoWriter().int32(1, 1).toByteArray())
        matched = true
        initSeen = false
        musicStartSeen = false
        musicEndSeen = false
        // Head units that never send a data subscription (carlife_pc_tool) still show turn info: on by default,
        // a subscription request then hands the switch to its START / STOP
        navigation.subscribe(true)
        cmd(CarLifeProtocol.CMD_FOREGROUND)
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        if (pm?.isInteractive != false) cmd(CarLifeProtocol.CMD_SCREEN_ON)
        cmd(CarLifeProtocol.CMD_MD_INFO, deviceInfo())
        cmd(CarLifeProtocol.CMD_MODULE_STATUS, moduleStatus())
        // Some head units wait for the encryption handshake before VIDEO_ENCODER_INIT
        encryptProbeAt = android.os.SystemClock.elapsedRealtime() + ENCRYPT_PROBE_MS
        startTimer()
    }

    private fun onVideoInit(r: ProtoReader) {
        initSeen = true
        encryptProbeAt = 0L
        val carW = r.int(1, 0)
        val carH = r.int(2, 0)
        val asked = r.int(3, 0)
        val request = Triple(carW, carH, asked)
        if (videoRequest == request && streamWidth > 0) {
            cmd(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT_DONE, encoderInfo(streamWidth, streamHeight, fps))
            return
        }
        val (sw, sh) = streamSize(even(carW.takeIf { it > 0 } ?: 1280), even(carH.takeIf { it > 0 } ?: 720), newVehicle)
        streamWidth = sw
        streamHeight = sh
        fps = (if (asked > 2) asked else START_FPS).coerceIn(LOWEST_PACE, 60)
        videoRequest = request
        projecting = false
        log("CARLIFE: car screen ${carW}x$carH, sending ${sw}x$sh at $fps fps${if (asked > 0) " (car asked $asked)" else ""}")
        feed.open()
        events?.onVideoNegotiated(sw, sh, fps)
        cmd(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT_DONE, encoderInfo(sw, sh, asked))
        cmd(CarLifeProtocol.CMD_FOREGROUND)
    }

    private fun onModuleControl(r: ProtoReader) {
        val module = r.int(1, -1)
        val status = r.int(2, -1)
        if (module == CarLifeProtocol.MODULE_VR && status == 1) {
            events?.onHardKey(KEY_VOICE)
            return
        }
        if (module != CarLifeProtocol.MODULE_MUSIC || (status != 0 && status != 1)) return
        // The first start / end the car sends after connecting only mirrors our own status, not a user action
        if (status == 1 && !musicStartSeen) {
            musicStartSeen = true
            return
        }
        if (status == 0 && !musicEndSeen) {
            musicEndSeen = true
            return
        }
        events?.onMusicControl(status == 1)
    }

    private fun handleCtrl(
        id: Int,
        payload: ByteArray,
    ) {
        val e = events ?: return
        when (id) {
            CarLifeProtocol.TOUCH_ACTION -> {
                val r = ProtoReader(payload)
                val raw = r.int(1, -1)
                val x = r.int(2, -1)
                val y = r.int(3, -1)
                val px = r.int(4, 0)
                val py = r.int(5, 0)
                if (raw < 0 || x < 0 || y < 0) return
                val action = raw and 0xFF
                val index = (raw shr 8) and 0xFF
                val second = px != 0 || py != 0
                when (action) {
                    TOUCH_DOWN, TOUCH_UP, TOUCH_MOVE -> e.onTouch(action, x, y, if (second) px else -1, if (second) py else -1, 0)
                    TOUCH_POINTER_DOWN -> e.onTouch(action, x, y, if (second) px else x, if (second) py else y, index.coerceAtLeast(1))
                    TOUCH_POINTER_UP -> e.onTouch(action, x, y, if (second) px else -1, if (second) py else -1, index)
                }
            }
            CarLifeProtocol.TOUCH_DOWN -> point(payload) { x, y -> e.onTouch(TOUCH_DOWN, x, y, -1, -1, 0) }
            CarLifeProtocol.TOUCH_UP -> point(payload) { x, y -> e.onTouch(TOUCH_UP, x, y, -1, -1, 0) }
            CarLifeProtocol.TOUCH_MOVE -> point(payload) { x, y -> e.onTouch(TOUCH_MOVE, x, y, -1, -1, 0) }
            CarLifeProtocol.TOUCH_SINGLE_CLICK -> point(payload) { x, y -> tap(e, x, y, 0L) }
            CarLifeProtocol.TOUCH_DOUBLE_CLICK ->
                point(payload) { x, y ->
                    tap(e, x, y, 0L)
                    tap(e, x, y, DOUBLE_TAP_GAP_MS)
                }
            CarLifeProtocol.TOUCH_LONG_PRESS -> point(payload) { x, y -> tap(e, x, y, 0L, holdMs = LONG_PRESS_MS) }
            CarLifeProtocol.CAR_HARD_KEY_CODE -> {
                val key = ProtoReader(payload).int(1, -1)
                log("CARLIFE: car key $key")
                if (key >= 0) e.onHardKey(key)
            }
            else -> Unit
        }
    }

    private fun tap(
        e: Events,
        x: Int,
        y: Int,
        delayMs: Long,
        holdMs: Long = 0L,
    ) {
        if (delayMs == 0L && holdMs == 0L) {
            e.onTouch(TOUCH_DOWN, x, y, -1, -1, 0)
            e.onTouch(TOUCH_UP, x, y, -1, -1, 0)
            return
        }
        io.github.ezn24.droidcar.util.Bg.run("carlife-tap") {
            if (delayMs > 0) Thread.sleep(delayMs)
            e.onTouch(TOUCH_DOWN, x, y, -1, -1, 0)
            if (holdMs > 0) Thread.sleep(holdMs)
            e.onTouch(TOUCH_UP, x, y, -1, -1, 0)
        }
    }

    private inline fun point(
        payload: ByteArray,
        use: (Int, Int) -> Unit,
    ) {
        val r = ProtoReader(payload)
        val x = r.int(1, -1)
        val y = r.int(2, -1)
        if (x >= 0 && y >= 0) use(x, y)
    }

    private fun startEncryption() {
        if (crypto.started) return
        crypto.started = true
        encryptProbeAt = 0L
        cmd(CarLifeProtocol.CMD_MD_RSA_PUBLIC_KEY_REQUEST)
    }

    /** Video heartbeats, media idle and the encryption probe on one light timer (50 ms). */
    private fun startTimer() {
        if (timer != null) return
        timer =
            Thread({
                try {
                    while (matched) {
                        Thread.sleep(TIMER_MS)
                        val probe = encryptProbeAt
                        if (probe > 0 && android.os.SystemClock.elapsedRealtime() >= probe && !initSeen) {
                            encryptProbeAt = 0L
                            log("CARLIFE: no VIDEO_ENCODER_INIT after the match, trying the encryption handshake")
                            startEncryption()
                        }
                        feed.idle((QUIET_FRAMES * 1000L / fps.coerceIn(1, 60)).coerceAtMost(HEARTBEAT_CAP_MS))
                        if (feed.needsKey()) events?.onNeedKeyFrame()
                        media.idle()
                    }
                } catch (_: InterruptedException) {
                }
            }, "carlife-timer").apply {
                isDaemon = true
                start()
            }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun btPairInfo(): ByteArray {
        val name =
            runCatching {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.name
            }.getOrNull().orEmpty().ifBlank { Build.MODEL }
        val mac =
            io.github.ezn24.droidcar.util.DeviceIdentity
                .bluetoothMac(context)
                .takeIf { !it.startsWith("02:00:00") }
                .orEmpty()
        return ProtoWriter()
            .string(1, mac)
            .string(2, "")
            .string(5, "00001101-0000-1000-8000-00805F9B34FB")
            .string(6, name)
            .int32(7, 1)
            .toByteArray()
    }

    private fun deviceInfo(): ByteArray {
        val abis = Build.SUPPORTED_ABIS
        val abi1 = abis.getOrNull(0) ?: "arm64-v8a"
        val abi2 = abis.getOrNull(1) ?: abi1
        val cid = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull() ?: "droidcar"
        return ProtoWriter()
            .string(1, "Android")
            .string(2, Build.BOARD)
            .string(3, Build.BOOTLOADER)
            .string(4, Build.BRAND)
            .string(5, abi1)
            .string(6, abi2)
            .string(7, Build.DEVICE)
            .string(8, Build.DISPLAY)
            .string(9, Build.FINGERPRINT)
            .string(10, Build.HARDWARE)
            .string(11, Build.HOST)
            .string(12, cid)
            .string(13, Build.MANUFACTURER)
            .string(14, Build.MODEL)
            .string(15, Build.PRODUCT)
            .string(16, "unknown")
            .string(17, Build.VERSION.CODENAME)
            .string(18, Build.VERSION.INCREMENTAL)
            .string(19, Build.VERSION.RELEASE)
            .string(20, Build.VERSION.SDK_INT.toString())
            .int32(21, Build.VERSION.SDK_INT)
            .string(22, "DroidCar")
            .toByteArray()
    }

    private fun moduleStatus(): ByteArray {
        val modules =
            listOf(
                CarLifeProtocol.MODULE_PHONE to 0,
                CarLifeProtocol.MODULE_NAVI to 0,
                CarLifeProtocol.MODULE_MUSIC to if (media.open) 1 else 0,
                CarLifeProtocol.MODULE_VR to 0,
                CarLifeProtocol.MODULE_MIC to 0,
            )
        val w = ProtoWriter().int32(1, modules.size)
        for ((id, st) in modules) w.message(2, ProtoWriter().int32(1, id).int32(2, st))
        return w.toByteArray()
    }

    companion object {
        const val TOUCH_DOWN = 0
        const val TOUCH_UP = 1
        const val TOUCH_MOVE = 2
        const val TOUCH_POINTER_DOWN = 5
        const val TOUCH_POINTER_UP = 6

        /** Pseudo key for the car's voice-assistant request (Android KEYCODE_SEARCH, as FT does). */
        const val KEY_VOICE = 84

        const val START_FPS = 30
        private const val SUBSCRIBE_NAVIGATION = 0
        const val LOWEST_PACE = 15
        private const val ENCRYPT_PROBE_MS = 2500L
        private const val TIMER_MS = 50L
        private const val QUIET_FRAMES = 6
        private const val HEARTBEAT_CAP_MS = 900L
        private const val DOUBLE_TAP_GAP_MS = 60L
        private const val LONG_PRESS_MS = 800L

        private val QUIET_COMMANDS =
            setOf(
                CarLifeProtocol.CMD_HU_GEAR_INFO,
                CarLifeProtocol.CMD_MODULE_CONTROL,
                CarLifeProtocol.CMD_CAR_GPS,
                CarLifeProtocol.CMD_CAR_VELOCITY,
                CarLifeProtocol.CMD_CAR_GYROSCOPE,
                CarLifeProtocol.CMD_CAR_ACCELERATION,
                // Some head units send their video heartbeat on the command channel, once a second
                CarLifeProtocol.VIDEO_HEARTBEAT,
            )

        fun encoderInfo(
            width: Int,
            height: Int,
            fps: Int,
        ): ByteArray =
            ProtoWriter()
                .int32(1, width)
                .int32(2, height)
                .int32(3, fps)
                .toByteArray()

        private fun even(v: Int) = (v / 2) * 2

        /**
         * Stream size for a car screen. Baidu's own phone app sends these sizes and some head-unit decoders accept
         * nothing else; only head units speaking protocol 3.2 / 4.x get full HD.
         */
        fun streamSize(
            carWidth: Int,
            carHeight: Int,
            newVehicle: Boolean,
        ): Pair<Int, Int> {
            val wide = newVehicle && carHeight > 0 && carWidth.toFloat() / carHeight >= 2.3f
            return when {
                carWidth < 800 -> 768 to 432
                carWidth < 1024 -> if (wide) 1024 to 384 else 848 to 480
                carWidth < 1280 -> if (wide) 1024 to 384 else 1024 to 576
                carWidth < 1920 -> if (wide) 1280 to 480 else 1280 to 720
                newVehicle -> if (wide) 1920 to 720 else 1920 to 1080
                else -> 1280 to 720
            }
        }
    }
}
