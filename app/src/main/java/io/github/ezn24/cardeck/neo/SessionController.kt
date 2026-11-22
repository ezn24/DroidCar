package io.github.ezn24.cardeck.neo

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.view.KeyEvent
import io.github.ezn24.cardeck.neo.airplay.AirPlayClient
import io.github.ezn24.cardeck.neo.airplay.PairVerifyClient
import io.github.ezn24.cardeck.neo.audio.AudioProfile
import io.github.ezn24.cardeck.neo.audio.AudioProfileMemory
import io.github.ezn24.cardeck.neo.audio.AudioSender
import io.github.ezn24.cardeck.neo.audio.AudioTuning
import io.github.ezn24.cardeck.neo.bt.AdapterMode
import io.github.ezn24.cardeck.neo.bt.BtProfiles
import io.github.ezn24.cardeck.neo.bt.BtSessionGate
import io.github.ezn24.cardeck.neo.bt.Iap2BluetoothClient
import io.github.ezn24.cardeck.neo.bt.Iap2BluetoothServer
import io.github.ezn24.cardeck.neo.bt.TargetDevicePrefs
import io.github.ezn24.cardeck.neo.capture.HiddenDisplaySource
import io.github.ezn24.cardeck.neo.iap2.Iap2MessageIds
import io.github.ezn24.cardeck.neo.iap2.Iap2SenderSession
import io.github.ezn24.cardeck.neo.iap2.ReceiverProfile
import io.github.ezn24.cardeck.neo.input.ConsumerKeys
import io.github.ezn24.cardeck.neo.input.HidNavigation
import io.github.ezn24.cardeck.neo.input.TouchReports
import io.github.ezn24.cardeck.neo.input.TouchRouter
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher
import io.github.ezn24.cardeck.neo.launcher.settings.AppOrientationCompat
import io.github.ezn24.cardeck.neo.launcher.settings.DisplayPrefs
import io.github.ezn24.cardeck.neo.media.CarNowPlaying
import io.github.ezn24.cardeck.neo.net.AirPlayFeatures
import io.github.ezn24.cardeck.neo.net.CarNetwork
import io.github.ezn24.cardeck.neo.net.CarPlayCtrlAdvertiser
import io.github.ezn24.cardeck.neo.net.CarPlayServiceBrowser
import io.github.ezn24.cardeck.neo.net.EndpointHosts
import io.github.ezn24.cardeck.neo.net.WifiCarPlayConnector
import io.github.ezn24.cardeck.neo.net.WifiConnectCommand
import io.github.ezn24.cardeck.neo.net.WifiSavedNetworks
import io.github.ezn24.cardeck.neo.service.CarPlayService
import io.github.ezn24.cardeck.neo.shizuku.CarInputClient
import io.github.ezn24.cardeck.neo.shizuku.Shell
import io.github.ezn24.cardeck.neo.shizuku.ShizukuGate
import io.github.ezn24.cardeck.neo.ui.ScreenDimmer
import io.github.ezn24.cardeck.neo.ui.UiStrings
import io.github.ezn24.cardeck.neo.util.Bg
import io.github.ezn24.cardeck.neo.util.DebugFileLog
import io.github.ezn24.cardeck.neo.video.VideoSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide session hub: the single owner of the log buffer and of the session pipeline.
 *
 * Background (three linked problems found on a device, 2026-10-11): the capture pipeline ran on a thread spawned by MainActivity;
 * after the UI was swiped away (Activity destroyed) the foreground service kept the process alive and the pipeline kept running,
 * but re-entering the app created a new Activity instance:
 *  1. the old thread's logs went to the destroyed instance's UI, so the new UI lost the log stream (the file log was in fact intact)
 *  2. "Disconnect" acted on the new instance's new objects, so nobody held the old pipeline and it could not be stopped
 *  3. reconnecting would build a second set of objects, risking two sessions
 *
 * This object lifts that state into a process-wide singleton: an Activity only attaches (subscribes to logs, forwards buttons),
 * and destroy/recreate has no effect on it. Session threads run through [appContext] and depend on no Activity.
 */
object SessionController {
    // ---- Log buffer (process-wide; Activities subscribe to render it) ----

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Render callback of the currently attached Activity (receives each newly appended log line, timestamp prefix included); cleared in Activity onDestroy. */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** "Listen to logs" switch (synced from preferences by the Activity; when off, nothing is collected or rendered). */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** Window for WiFi user actions (upper bound for turning WLAN on / polling for a manual hotspot join). */
    private const val WLAN_WAIT_TIMEOUT_MS = 120_000L

    /** Upper bound for waiting on the join-confirmation dialog (no answer is treated as abort). */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /**
     * Upper bound for an empty endpoint (the head unit declares it will send 0x4301 but it has not arrived within the window).
     * Audi MMI 9310 delivered 0x4301 2 min 35 s late, so the keep-alive window must be well beyond that;
     * on timeout the link is torn down and the BT reconnect loop takes over (differs from the old "tear down after 8s" only in window length).
     */
    private const val PENDING_START_SESSION_WAIT_MS = 5 * 60_000L

    /**
     * Upper bound for waiting on the head unit's ctrl invitation (event-driven, proceeds as soon as it arrives).
     * catplay treats the invitation as the connect trigger; measured: it arrives ~1.0-1.3s after the ctrl re-registration.
     * When the head unit sends no invitation (old models), an already-discovered _airplay._tcp releases the wait early; worst case is this long.
     */
    private const val CTRL_INVITE_WAIT_MS = 4_000L

    /** Wait for an mDNS cache hit when the endpoint is incomplete: discovery started at network bind, so it usually hits within a few hundred ms. */
    private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L

    /** A data-port attempt that failed faster than this was refused (port not open yet) and is worth one retry. */
    private const val DATA_PORT_RETRY_WINDOW_MS = 1_500L
    private const val DATA_PORT_RETRY_DELAY_MS = 300L

    /**
     * Upper bound for the "encryption upgrade" re-pairing after a plaintext SETUP is rejected (catplay handle_setup_result path).
     * It is reached only when actually rejected, so normal head units pay nothing; a receiver that does not support pairing
     * gives up right after pair-setup gets 404/405/455 and does not wait out this window.
     */
    private const val PAIRING_UPGRADE_WINDOW_MS = 30_000L

    /**
     * Retry window for the initial SETUP of a plaintext session. The AirPlay port of pre-HomeKit head units often opens only
     * after the user taps the phone icon on the head unit; a port that opens 1-2s late must not fail the whole round.
     */
    private const val INITIAL_SETUP_WINDOW_MS = 30_000L

    /**
     * Upper bound for waiting when the endpoint has arrived but the phone has not yet switched to the head-unit hotspot.
     * Covers both "auto join (a few seconds)" and "user joins manually (commonly 10-30s)";
     * on timeout it shows a clear hint and resets sessionStarting, so it never stays in "connecting" forever.
     */
    private const val CAR_WIFI_BIND_WAIT_MS = 45_000L

    /**
     * Upper bound (seconds) for `requestNetwork(WIFI)` to deliver onAvailable.
     * While the user joins the hotspot by hand in system settings, the callback waits for association + DHCP and can take 10s+.
     * The old 5s value misjudged failure 116ms before success and abandoned the whole session (see bindCarWifiAndRegister).
     */
    private const val WIFI_BIND_WAIT_S = 15L

    /**
     * Time window after `cmd wifi connect-network` for the system to actually associate with the hotspot.
     * The command returns once it has only handed the connection to WifiService (exit=0 does not mean connected);
     * disconnect old network -> scan -> associate -> DHCP measured 2-10s, longer on slow 5GHz hotspots.
     */
    private const val CAR_WIFI_JOIN_WAIT_MS = 25_000L

    /** Time window after `cmd wifi set-wifi-enabled enabled` for WLAN to actually turn on. */
    private const val WIFI_ENABLE_WAIT_MS = 10_000L

    /**
     * Upper bound for waiting on the "connect to head-unit hotspot" system dialog (app-specific network).
     * The system confirmation can stay up for a long time; when the user cancels the connection, [connectCancelled] exits immediately.
     */
    // 40s total window = 20s first segment + 20s resend segment (split inside the connector). The head unit's 15s BT-teardown watchdog
    // means the wait must be far shorter than the old 120s: with the old value, a first round stuck for the full 120s found the head unit
    // had long since torn down the link and re-invited, blocked by a zombie round (Audi 3315 on-device finding, 2026-10-22).
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 40_000L

    /** Called from Activity onCreate: registers applicationContext and creates the session objects (idempotent). */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
        // Reasons for a landscape-compat write failure (user-build restriction / Shizuku fault) can only be persisted here:
        // the head-unit settings panel toast is for the user only, and diagnosis needs logs.
        AppOrientationCompat.diagSink = ::appendLog
        // Warning for shell calls on the main thread (both on-device ANRs in history came from this; logcat is unreadable under HKS)
        Shell.violationSink = ::appendLog
        // HID raw log switch (key-map calibration): the static flag is initialized from preferences and must also take effect on a cold process start
        HidNavigation.rawLogEnabled = DisplayPrefs.hidRawLog(appContext!!)
        io.github.ezn24.cardeck.neo.input.KnobFocus.log = ::appendLog
    }

    /** Append one log line: logcat + file + process-wide buffer + attached rendering (incremental line pushed directly). Callable from any thread. */
    fun appendLog(message: String) {
        // NULs written into the log file make it unopenable in text editors/file managers (the Read tool also treats it as binary),
        // which wastes a user-submitted log. The fixed-width 0x4301 address field once printed many NULs verbatim
        // (216 measured, 2026-10-18); they are replaced with a visible placeholder here, which also makes "dirty bytes in the string"
        // visible in the log instead of hiding as an InetSocketAddress parse failure.
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        android.util.Log.i("CarPlaySender", clean)
        appContext?.let {
            DebugFileLog
                .append(it, clean)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date()) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // Incremental lines go straight to the UI: previously every line did toString on the whole buffer and then setText on the full text,
        // which amplified load on the UI thread and GC under high-frequency logs (touch/frames)
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /**
     * Dispatch a system media key (AudioManager routes it globally to the currently active media session, no permission needed).
     * The desktop music card buttons and the head-unit / steering-wheel media keys (Consumer HID) share this path.
     */
    fun dispatchMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * Knob / four-way stick -> arrow keys: injected into the app-screen virtual display (DPAD needs focus, so it cannot be dispatched globally).
     *
     * Goes through UserService `injectInputEvent` (millisecond-level); on failure falls back to `input -d <id> keyevent`
     * (~30ms slower but never drops a key). The dock screen does not take part - cross-display focus needs Launcher-side work, see
     * docs/HID-KNOB.md.
     */
    private fun injectNavigationKey(keyCode: Int) {
        // Knob focus first: it frames dock / desktop / dashboard targets and enters apps on Confirm
        if (io.github.ezn24.cardeck.neo.input.KnobFocus
                .onKey(keyCode)
        ) {
            return
        }
        // Knob "Home" = our launcher's home, like the dock button (an Android HOME would land on the phone)
        if (keyCode == android.view.KeyEvent.KEYCODE_HOME) {
            Bg.run("knob-home") { VirtualLauncher.onHomePressed() }
            return
        }
        // Back follows the assistive dot's routing (fullscreen map / last touched dashboard pane / active app)
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            io.github.ezn24.cardeck.neo.launcher.AssistiveDot
                .back()
            return
        }
        // Inject into the "currently visible app screen" first (desktop grid / an open app screen), otherwise fall back to the main app screen
        val displayId =
            VirtualLauncher.activeAppDisplayId().takeIf { it > 0 }
                ?: VirtualLauncher.appDisplayId
        if (displayId <= 0) {
            appendLog("HID: app screen not ready (displayId=$displayId) - navigation key ignored")
            return
        }
        if (CarInputClient.keyEvent(displayId, keyCode)) return
        Shell.exec("input -d $displayId keyevent $keyCode", 3_000L)
    }

    /**
     * Clear the process-wide log buffer (refreshing the UI is the caller's job: explicitly `renderConsole(logText())`).
     * This used to notify the UI via `logListener?.invoke("")` - semantics from the era of "full setText per line";
     * with incremental appendConsole an empty string means appending an empty line and the UI stays put (root cause of
     * "the clear button does nothing", 2026-10-16). Do not restore that call: an empty string cannot be told apart from a real log line.
     */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- Session objects (process-wide ownership; unaffected by Activity recreation) ----

    private var wifiConnector: WifiCarPlayConnector? = null

    /**
     * Which head-unit hotspot is currently bound (written after bindCarWifiAndRegister succeeds).
     * If the endpoint handoff finds the same SSID already bound, the second bindCurrentWifi is skipped - it release()s first,
     * which would interrupt the ctrl mDNS advertisement just registered and wait pointlessly for the system to reconnect.
     */
    @Volatile
    private var boundCarSsid: String? = null

    /** SSIDs already asked about "clear the system-saved hotspot" in this process (avoids repeated dialogs on retry). */
    private val forgetPromptHandled =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** WiFi bind mutex: auto-join and endpoint handoff may bind the same hotspot concurrently. */
    private val wifiBindLock = Any()

    /** Publishable fields for double-checked locking (DCL without volatile is unsafe publication; currently main-thread only, but do not leave the hazard). */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /**
     * Resident `_airplay._tcp` discoverer (mirrors catplay's resident Bonjour cache).
     * Discovery starts as soon as the hotspot is joined, so the address is usually ready at endpoint handoff and need not be looked up on demand.
     */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    @Volatile
    var videoSender: VideoSender? = null

    // ---- "Display" runtime effective values (snapshotted at session setup; the hero card shows runtime values, not preference values) ----

    /** Stream resolution scale percent for this session (derived from the head-unit resolution; 100 = native size). */
    @Volatile
    var activeScalePercent: Int = 100

    /** Target frame rate for this session (upper bound of the compose cadence; "Display" settings changed mid-run take effect live). */
    @Volatile
    var activeTargetFps: Int = 30

    @Volatile
    var audioSender: AudioSender? = null

    @Volatile
    var currentClient: AirPlayClient? = null

    @Volatile
    var currentHiddenSource: HiddenDisplaySource? = null

    /** Shared key and receiver address of the current session: used to start audio dynamically when the capture grant arrives after the connection. */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** Whether this session plays sound on the head unit (chosen in the "start connection" dialog). */
    @Volatile
    var audioEnabledForSession: Boolean = false

    /**
     * "Enable sound (compatibility tier)": puts non-media tiers (compatibility etc.) first among the candidates.
     * Honda HFT, 2026-10-19: the media tier declares only 44.1k and that head unit's audio path accepts only 48k,
     * so the default tier makes the whole head unit tear down the link a few seconds after going live.
     */
    @Volatile
    var audioPreferCompat: Boolean = false

    /** The audio tier actually used this round (for teardown attribution and memory write-back; null = no audio this time). */
    @Volatile
    private var activeAudioProfile: AudioProfile? = null

    /** Key for the audio-tier memory (head-unit identity text, see [AirPlayClient.receiverIdentity]). */
    @Volatile
    private var activeAudioReceiverKey: String = ""

    /** Moment the audio channel actually started streaming (uptimeMillis); 0 = not started. */
    @Volatile
    private var activeAudioStartedAt: Long = 0L

    /**
     * Audio "survival window" (ms): only after it is the tier considered usable.
     * Based on the 2026-10-19 measurement: teardown happened 6.5s after audio started; 20s leaves margin for
     * head-unit audio path initialization and the first underrun cycle.
     */
    private const val AUDIO_PROFILE_PROBE_MS = 20_000L

    /** Session setup mutex (endpoint handoff -> pairing -> streaming: only one at a time). */
    val sessionStarting = AtomicBoolean(false)

    /**
     * A late endpoint (0x4301) received while the handoff flow is running but the AirPlayClient has not been created yet.
     * The pairing thread takes it once at start and then clears it - there is no client to update at that moment, and dropping it would wait pointlessly until the next round.
     */
    @Volatile
    private var pendingEndpointUpdate: Iap2SenderSession.CarPlayEndpoint? = null

    /** The user cancelled the connection: the pairing thread aborts when it sees this at key checkpoints (mDNS/pairing waits cannot be interrupted midway; granularity is seconds). */
    @Volatile
    var connectCancelled: Boolean = false

    /**
     * One-line hint for the current connection phase (used as the hero card subtitle; see the log for the details).
     * Set explicitly by each phase; when null the UI shows the default text.
     */
    @Volatile
    var phaseHint: String? = null

    /**
     * Car-entry auto-connect is held off until this uptime after the user disconnects or cancels by hand:
     * closing RFCOMM makes the car's ACL drop and reconnect, and each ACL_CONNECTED used to start a new
     * session about a second after the user tapped "Disconnect" (upstream shaonialife/cardeck 1d3afc8).
     * Leaving the car (AUTO_STOP) does not arm it, so the next entry still connects by itself.
     */
    @Volatile
    private var autoConnectSuppressedUntil = 0L

    private const val AUTO_CONNECT_COOLDOWN_MS = 10 * 60_000L

    /** Set by a user disconnect so the stream-end path does not attribute the teardown to the audio tier. */
    @Volatile
    private var userEndedSession = false

    /** Remaining auto-connect hold-off in ms after a manual disconnect (0 = auto-connect allowed). */
    fun autoConnectSuppressedForMillis(): Long = (autoConnectSuppressedUntil - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    /**
     * Armed when a round writes the landscape overrides; the first terminal state (user disconnect or the head unit
     * ending the stream) restores them and later ones skip. A manual disconnect reaches both, which logged
     * "restored display settings" twice (upstream shaonialife/cardeck 1d3afc8).
     */
    private val orientationRestorePending =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private fun restoreOrientationOnce() {
        if (!orientationRestorePending.compareAndSet(true, false)) return
        appContext?.let { ctx -> AppOrientationCompat.resetAllAsync(ctx, ::appendLog) }
    }

    private fun suppressAutoConnect(reason: String) {
        autoConnectSuppressedUntil = SystemClock.uptimeMillis() + AUTO_CONNECT_COOLDOWN_MS
        appendLog("SESSION: $reason - car-entry auto-connect paused for ${AUTO_CONNECT_COOLDOWN_MS / 60_000} min")
    }

    /**
     * Head-unit phone keys (HID Telephony page). Hook Switch answers a ringing call, Drop declines it or ends the
     * active one, Phone Mute toggles the mic. Logs carry the key and call phase only.
     */
    private fun onPhoneKey(
        context: Context,
        usage: Int,
    ) {
        val call = io.github.ezn24.cardeck.neo.call.CallState.current
        val phase = call?.phase
        appendLog("EVENT: head-unit phone key usage=0x%02x call=%s".format(usage, phase ?: "none"))
        if (call == null) return
        val app = context.applicationContext
        val ringing = phase == io.github.ezn24.cardeck.neo.call.CallState.Phase.RINGING
        Bg.run("car-phone-key") {
            when (usage) {
                ConsumerKeys.PHONE_HOOK_SWITCH ->
                    if (ringing) {
                        io.github.ezn24.cardeck.neo.call.CallControl
                            .answer(app)
                    }
                ConsumerKeys.PHONE_DROP ->
                    if (ringing) {
                        io.github.ezn24.cardeck.neo.call.CallControl
                            .decline(app)
                    } else {
                        io.github.ezn24.cardeck.neo.call.CallControl
                            .hangUp(app)
                    }
                ConsumerKeys.PHONE_MUTE ->
                    if (!ringing) {
                        io.github.ezn24.cardeck.neo.call.CallControl
                            .toggleMute(app)
                    }
            }
        }
    }

    /** Launcher "back to the car" cell: show the car's own UI, keeping the session (see [AirPlayClient.handScreenToCar]). */
    fun requestCarUi() {
        val client =
            currentClient ?: run {
                appendLog("OEM: no active session - home button ignored")
                return
            }
        Bg.run("request-car-ui") { client.handScreenToCar() }
    }

    /** Cancel "connecting": stop the links + set the cancel flag, so the user can bail out when stuck on a wrong network or waiting for a reconnect. */
    fun cancelConnecting(context: Context) {
        connectCancelled = true
        appendLog("SESSION: user cancelled the connection - stopping Bluetooth/WiFi links")
        suppressAutoConnect("user cancelled")
        disconnect(context)
        // Force-release the mutex: the pairing thread may be blocked in an uninterruptible connect/mDNS wait (e.g. connecting to the hotspot IP
        // while the phone is not on the hotspot subnet) and its finally is nowhere in sight - an explicit user cancel force-unlocks,
        // otherwise the hero card stays on "connecting" forever (the old flow's output was already cleaned up by retireSession, no conflict)
        sessionStarting.set(false)
        phaseHint = UiStrings.text(R.string.phase_01)
    }

    /**
     * Sender BT link dropped (keep-alive pump exited). While pairing is in progress (not yet streaming) this round is already doomed -
     * release the "session in progress" mutex at once and wake the stuck waits (a window mismatch between the head unit's 15s teardown and our 120s join
     * wait let a zombie round block the door: the new endpoint from the head unit's reconnect was rejected as
     * "a session is already in progress", leaving only keep-alive idling until the user gave up; Audi 3315 on-device finding, 2026-10-22).
     *
     * Sets [connectCancelled] so a pairing thread stuck in WiFi-join style segmented checks exits within <=200ms;
     * does not stop the BT components (the head unit most likely reconnects and listens immediately). The flag is cleared at the start of a new round -
     * getting there means the BT components are still running (the user-cancel path, disconnect, stops them and is blocked by the endpoint
     * guard), so clearing is safe.
     */
    fun onSenderLinkClosed() {
        if (videoSender?.isRunning == true) return
        if (sessionStarting.compareAndSet(true, false)) {
            connectCancelled = true
            appendLog(
                ">>> BT link dropped this round - releasing \"session in progress\", waiting for the head unit's reconnect endpoint to take over",
            )
        }
    }

    /**
     * Head-unit hotspot SSID known to this process: the bound one first, otherwise the SSID of the latest credentials.
     *
     * Feeds the fallback in [Iap2SenderSession] for "this round had neither 0x5703 nor 0x4301": as long as the
     * head-unit hotspot is known there is no need to tear down the link - go by the current network via mDNS/gateway, and a late 0x4301 still hot-updates.
     * Audi 3315 log, 2026-10-22: from round 2 the phone was already on the head-unit hotspot, yet every round killed itself after
     * 8 seconds for "no credentials", resetting the head unit's invitation window each time (a real iPhone capture waited ~21s for the invitation).
     */

    /** Cache of the link generation determined by this round's BT session (written by the 0x1D01 profile callback; null = not identified). */
    @Volatile
    private var senderLinkGenerationCache: ReceiverProfile.LinkGeneration? = null

    /**
     * A wireless CarPlay adapter that already knows this phone skips the Bluetooth bootstrap and
     * just invites us over its Wi-Fi (`GET /ctrl-int/1/connect`, repeated every ~17 s). Start the
     * AirPlay session from the invite, as the reference sender does, when the phone is on the
     * adapter's Wi-Fi and nothing else is running.
     */
    private fun onAdapterInvite(peer: String?) {
        if (!AdapterMode.active || connectCancelled) return
        if (videoSender?.isRunning == true || sessionStarting.get()) return
        if (bluetoothServer?.isRunning != true) return
        val host = peer?.takeIf { EndpointHosts.isUsableAdapterAddress(it) } ?: return
        val ssid = shellWifiStatus().second ?: knownCarHotspotSsid()
        if (ssid.isNullOrBlank()) {
            appendLog("ADAPTER: invite from $host but the phone's Wi-Fi SSID is unknown - waiting for Bluetooth")
            return
        }
        appendLog("ADAPTER: invite from $host on \"$ssid\" without a Bluetooth bootstrap - starting AirPlay from the invite")
        onCarPlayEndpoint(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = ssid,
                passphrase = lastWifiPsk.orEmpty(),
                channel = 0,
                securityType = 0,
                airPlayPort = 7000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf(host),
            ),
            "adapter invite",
        )
    }

    private fun knownCarHotspotSsid(): String? = boundCarSsid ?: lastCarWifiSsid

    /**
     * Head-unit link generation determined by this round's BT session (0x1D01 profile: CLASSIC_MIB3 / EVENT_LINK).
     * Falls back to EVENT_LINK when the session object is not built or not yet identified (pre-0.1.13 behavior) -
     * mistaking MIB3 for EVENT_LINK only makes the negotiation go quiet after no reply, while the reverse would hang the head unit.
     */
    private fun senderLinkGeneration(): ReceiverProfile.LinkGeneration =
        senderLinkGenerationCache ?: ReceiverProfile.LinkGeneration.EVENT_LINK

    /**
     * Hotspot password from the latest 0x5703 (memory only, never persisted).
     * Used by the "head-unit hotspot -> connect" button to copy to the clipboard (manual-join fallback).
     */
    @Volatile
    var lastWifiPsk: String? = null

    /**
     * Hotspot SSID most recently obtained from the head unit (0x5703 or 0x4301; both paths write it).
     *
     * Differs from [boundCarSsid]: boundCarSsid has a value only after a successful bind, while this is written as soon as
     * credentials arrive - used to decide "which hotspot this head unit should join" when there are no new credentials.
     * In the Audi log the manual-fallback button falsely reported "hotspot password not obtained yet" because the 0x4301 path did not write the psk.
     */
    @Volatile
    var lastCarWifiSsid: String? = null

    /** Attached-UI hook: on SSID change, tells the current Activity to backfill the input field (nullable). */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    /** Attached-UI hook: shows important connection hints (WiFi off / hotspot join limits etc.) to the user. */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** Dialog spec (two-button questions such as the join-hotspot confirmation). */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
    )

    /**
     * Attached-UI hook: two-button confirmation dialog.
     * onChoice: true = positive; false = negative; null = nobody answered / dismissed (the caller falls back).
     */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // The app-specific network was dropped by the system (head unit off / out of range): tell the user explicitly
                    // instead of letting the hero card flip silently between "connected" and failed
                    connector.onNetworkLost = { ssid ->
                        boundCarSsid = null
                        phaseHint = UiStrings.format(R.string.phase_02, ssid)
                        uiHintSink?.invoke("车机热点 \"$ssid\" 已断开——请重新启动连接")
                        appendLog(">>> head-unit hotspot \"$ssid\" disconnected - this session must reconnect")
                    }
                }
            // Head-unit sockets are pinned to the hotspot network explicitly (VPN bypass, see CarNetwork)
            CarNetwork.provider = { wifiConnector?.currentNetwork }
            CarNetwork.connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            CarNetwork.log = { line -> appendLog(line) }
            CarNetwork.onVpnBlocked = { uiHintSink?.invoke(UiStrings.text(R.string.hint_vpn_blocks_car)) }
            // Now playing for the head unit's cluster / media widget (iAP2 0x5000 subscriptions)
            CarNowPlaying.init(context)
            CarNowPlaying.log = { line -> appendLog(line) }
            // Turn-by-turn for the cluster / HUD (iAP2 0x5200 subscriptions)
            io.github.ezn24.cardeck.neo.nav.CarRouteGuidance.apply {
                setEnabled(
                    io.github.ezn24.cardeck.neo.nav.NavAccessibilityAccess
                        .enabledByUser(context),
                )
                log = { line -> appendLog(line) }
            }
            io.github.ezn24.cardeck.neo.nav.NavAccessibilityService.log = { line -> appendLog(line) }
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // Target head unit is read dynamically: a choice takes effect on the next connect attempt / next inbound connection, no link restart needed
            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "head-unit reconnect") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "head-unit reconnect") },
                    onLinkClosed = ::onSenderLinkClosed,
                    knownCarSsid = ::knownCarHotspotSsid,
                    onGeneration = { g ->
                        senderLinkGenerationCache = g
                        appendLog("IAPLINK: generation cache updated -> $g (used for arming dispatch in the AirPlay phase)")
                    },
                    targetProvider = targetProvider,
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "outbound to head unit") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "outbound to head unit") },
                    onLinkClosed = ::onSenderLinkClosed,
                    knownCarSsid = ::knownCarHotspotSsid,
                    onGeneration = { g ->
                        senderLinkGenerationCache = g
                        appendLog("IAPLINK: generation cache updated -> $g (used for arming dispatch in the AirPlay phase)")
                    },
                    targetProvider = targetProvider,
                )
            ctrlAdvertiser =
                CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }.also { advertiser ->
                    advertiser.onInvite = { peer -> onAdapterInvite(peer) }
                }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // With a dual-homed phone (on the head-unit hotspot while also sharing its own hotspot to a PC) mDNS discovers the PC's
                        // AirPlay receiver across networks (built into macOS) - measured 2026-10-11: a MacBook was connected as the head unit for 120s.
                        // Anything not on this subnet never counts as a head unit.
                        // Address checks all go through EndpointHosts: IPv4 by /24 prefix, link-local IPv6
                        // is inherently on-link only, global IPv6 compared by /64. The old code recognized only IPv4 and would have
                        // dropped Audi 4xxx units that advertise only fe80:: (2026-10-19).
                        val ip = info.host?.hostAddress ?: return@CarPlayServiceBrowser false
                        EndpointHosts.isSameNetwork(
                            ip,
                            wifiConnector?.localIpv4Address(),
                            wifiConnector?.localIpv6Addresses().orEmpty(),
                        )
                    },
                )
        }

        // If the previous process crashed/was killed during a physical screen-off, the panel stays OFF (UserService is a resident shell process,
        // and the power state it holds does not disappear with the app process). Restore once at new-process start: there is no session yet,
        // so restoring is safe - otherwise the user gets a black screen that will not light (recoverable by pressing the power key twice).
        Bg.run("screen-power-startup-restore") {
            if (ShizukuGate.state(context) != ShizukuGate.State.READY) return@run
            val deadline = android.os.SystemClock.uptimeMillis() + 3_000L
            while (android.os.SystemClock.uptimeMillis() < deadline && CarInputClient.service == null) {
                CarInputClient.ensure(context)
                Thread.sleep(150)
            }
            // Restore only when the service is already bound: if Shizuku is not up there is no need to bother the user (the panel was not turned off by us either)
            if (CarInputClient.service != null && !CarInputClient.setPhysicalScreenPower(true)) {
                appendLog("SCREEN: failed to restore the physical screen at startup (ROM did not grant shell power control)")
            }
        }
    }

    /** Status line text (used by the Activity to render statusText). */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** Whether we are streaming to the head unit (AirPlay session established, runs over WiFi, no longer depends on Bluetooth). */
    fun isStreaming(): Boolean = videoSender?.isRunning == true

    /** Whether the session is active (the streaming process or BT is running). */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            bluetoothServer?.isRunning == true ||
            bluetoothClient?.isRunning == true

    /**
     * Wireless CarPlay adapters read the phone's SDP records when they pair, and only later dial
     * the phone's iAP2 service if it was published then. Start the listener before pairing one.
     */
    fun listenForAdapterPairing(context: Context) {
        ensure(context)
        AdapterMode.active = true
        bluetoothServer?.resetAdapterHandoff()
        // start() is idempotent and re-publishes an existing "iAP2" record as "Wireless iAP".
        if (bluetoothServer?.start() == true) {
            appendLog("ADAPTER: phone iAP2 service published before pairing (the adapter dials it after pairing)")
        }
    }

    /**
     * Body of "start connection" (called after the Activity completes the permission checks): foreground keep-alive service + bidirectional Bluetooth.
     * Idempotent: with an existing session the BT components' start() short-circuits and never doubles up.
     */
    fun startConnection(context: Context) {
        connectCancelled = false
        // Connecting again (by hand, or auto-connect after the hold-off) ends a manual-disconnect hold-off.
        autoConnectSuppressedUntil = 0L
        userEndedSession = false
        phaseHint = UiStrings.text(R.string.phase_03)
        when (
            ShizukuGate
                .state(context)
        ) {
            ShizukuGate.State.READY ->
                appendLog("SHIZUKU: ready (touch injection available)")

            ShizukuGate.State.UNAUTHORIZED -> {
                appendLog("SHIZUKU: this app is not authorized - a permission request was issued, tap Allow in the dialog and reconnect")
                ShizukuGate
                    .requestPermission()
            }

            ShizukuGate.State.NOT_RUNNING ->
                appendLog("SHIZUKU: service not running - open the Shizuku app and tap \"Start\"")

            ShizukuGate.State.NOT_INSTALLED ->
                appendLog("SHIZUKU: not installed (required by hidden-display mode)")
        }
        CarPlayService
            .start(context)
        // Capability advertisement precedes the connection (catplay's approach: CarPlayCtrlServer advertises
        // `_carplay-ctrl._tcp` from the very start, then force_announce every 1s, and builds AirPlay only on a head-unit invitation).
        // We used to wait for 0x5703 before registering, so "head unit discovers the phone" always landed after the hotspot bind,
        // costing 1-3s per round; now it is published at start-connection, and ensureForNetwork re-registers on network change.
        ctrlAdvertiser?.resetInviteCount()
        // Publish in the background: start() first resolves the real Bluetooth MAC via Shizuku (shell), which must not run on the main thread
        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        // Real-car path: the phone connects to the head unit (SDP ...caff accessory service)
        // Test-bench path: listen on the phone-side iPhone UUID and wait for DiPlay/xcertplay to connect back
        val target = TargetDevicePrefs.get(context)
        AdapterMode.active = AdapterMode.looksLikeAdapter(target?.name)
        if (AdapterMode.active) {
            // Wireless CarPlay adapter: the box dials our ...cafe service; it has no ...caff
            // service to dial, and the outbound client would also drop its other profiles.
            appendLog(
                "ADAPTER: \"${target?.name}\" looks like a wireless CarPlay adapter - " +
                    "waiting for it to connect to the phone (outbound Bluetooth connect disabled)",
            )
            bluetoothClient?.stop()
            bluetoothServer?.resetAdapterHandoff()
        } else {
            bluetoothClient?.start()
        }
        bluetoothServer?.start()
        // Diagnostics (Audi measurement: when the phone and head unit are "Bluetooth connected" the head unit does not open CarPlay):
        // print the target's Bluetooth profile state before connecting, so a failure shows at a glance whether this is the cause.
        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-PROFILE: Bluetooth profile state of \"${target.name}\" before connecting=" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "none"),
            )
        }
    }

    /** "Disconnect" (callable on any Activity instance - it acts on the real pipeline objects). */

    /**
     * [byUser] = the user tapped "Disconnect" (or declined the disclaimer): nothing will be resumed, so the map task
     * parked on its virtual display is released too. Car-side ends keep it for [VirtualLauncher] to resume.
     */
    fun disconnect(
        context: Context,
        byUser: Boolean = false,
    ) {
        val hadProjection = CarPlayService.mediaProjection != null
        if (byUser) {
            userEndedSession = true
            suppressAutoConnect("user disconnected")
        }
        io.github.ezn24.cardeck.neo.util.SessionHooks
            .onDisconnected(context)
        pendingEndpointUpdate = null
        retireSession()
        // The BT control session is retired only here (user terminal state); closing the socket makes the keep-alive read loop exit at once:
        // otherwise it blocks until the peer times out (~24s measured) and after a user cancel the hero card keeps spinning.
        // It must never be dropped during pairing - DiPlay sees RFCOMM drop before the tunnel iAP2 is ready, judges
        // bring-up failed and tears down the whole wireless stack (including a streaming AirPlay session, measured 2026-10-16).
        detachActiveBtSessions()
        if (byUser) VirtualLauncher.releaseParkedMap("user disconnected")
        phaseHint = null
        CarPlayService
            .stop(context)
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        endpointBrowser?.stop()
        wifiConnector?.release()
        boundCarSsid = null
        // Terminal-state restore of the "force app landscape" overrides (it used to be in retireSession - which also runs at
        // the start of every pairing round and would kill the apps the new round just launched; moved to the real terminal states 2026-10-23).
        restoreOrientationOnce()
        VirtualLauncher.setOemBlock(null)
        // Auto-forget of system networks saved by the shell fallback (connect-network): a hotspot without internet
        // is taken by the system as the default network and displaces mobile data. Failures are only logged (the user can delete it manually in WLAN).
        cleanupShellFallbackNetwork()
        // The generation cache is cleared with the session: the read side has an `?: EVENT_LINK` fallback so semantics are unchanged; keeping it
        // would leave a theoretical window of reading the previous car's cache when switching cars (Audi -> Honda; reviewed 2026-10-23).
        senderLinkGenerationCache = null
        if (hadProjection) {
            // Android 14+: MediaProjection is bound to the keep-alive service (mediaProjection FGS type),
            // so stopping the service invalidates the grant too - the next connection needs the capture permission requested again
            appendLog(
                "Manually disconnected and stopped the keep-alive service (keep-awake restored); the capture grant lapsed with the service, request it again next time if sound is wanted",
            )
        } else {
            appendLog("Manually disconnected and stopped the keep-alive service (keep-awake restored)")
        }
    }

    // ---- Physical screen-off (same as scrcpy -S/--turn-screen-off) ----

    /** Whether we have powered off the physical main screen (only for deciding "whether to restore", avoiding pointless AIDL calls). */
    @Volatile
    private var phoneScreenOff = false

    /**
     * Power switching is serialized. enter/restore are both Bg tasks (the cached pool does not guarantee order);
     * without a lock "restore finishes first, enter runs after" can happen -> the session is over yet the panel gets turned off.
     */
    private val screenPowerLock = Any()

    /**
     * Session enters "real screen-off": hardware power-off of the physical main screen while the system stays awake and virtual displays keep producing frames.
     *
     * Unlike the dim cover: the cover is only a black View while the touch layer stays powered (pocket touches, battery drain);
     * this is SurfaceFlinger-level power-off (the touch IC goes dark too) and looks like a lock screen, but does not lock -
     * the head unit's navigation/music apps are not suspended by Keyguard.
     *
     * Failures are only logged: when the ROM gives the shell no permission or the display token cannot be obtained, the worst case falls back to the dim cover.
     */
    private fun enterPhoneScreenOff() {
        val ctx = appContext ?: return
        // No need to turn the screen off when not streaming (the settings panel toggling with no session also lands here)
        if (videoSender?.isRunning != true) return
        Bg.run("screen-power-off") {
            synchronized(screenPowerLock) {
                if (phoneScreenOff) return@synchronized
                // A Bg task may run after the session already ended: once the session is gone the screen must never be turned off
                if (videoSender?.isRunning != true) {
                    appendLog("SCREEN: session already ended - skipping physical screen-off")
                    return@synchronized
                }
                // If the phone is asleep: wake it first so the framework sets the physical screen ON; otherwise after we set OFF the framework's
                // wake-up flow lights the panel again (both fight over the same SurfaceFlinger power state).
                val interactive =
                    runCatching {
                        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
                    }.getOrDefault(true)
                if (!interactive) {
                    appendLog(
                        "SCREEN: phone is asleep - waking first, then physical screen-off (avoids the framework relighting the panel)",
                    )
                    Shell.exec("input keyevent KEYCODE_WAKEUP", 4_000L)
                    Thread.sleep(400)
                    // With no password / SmartLock, swipe away the keyguard as well; a secure lock will fail, which is fine
                    Shell.exec("wm dismiss-keyguard", 3_000L)
                }
                // Power-off is carried by the dim cover (on-device finding 2026-10-24: after the panel is powered off the touch IC still works,
                // and tapping the cover wakes it; the cover is also the pocket-touch shield - without it, pocket touches would hit the foreground app).
                // enterDimNow = put up the cover + cut panel power + bookkeeping (panelOffByUs), the same path as manual fake screen-off.
                if (ScreenDimmer.isAttached()) {
                    ScreenDimmer.enterDimNow()
                    phoneScreenOff = true
                    appendLog(
                        "SCREEN: real screen-off entered (dim cover carries touch detection - tap the cover to wake, no power key needed)",
                    )
                } else {
                    // Dim cover unavailable (missing overlay permission): degrade to bare power-off; with no touch wake-up the power key is the only way
                    val ok = CarInputClient.setPhysicalScreenPower(false)
                    phoneScreenOff = ok
                    appendLog(
                        if (ok) {
                            "SCREEN: physical screen powered off (dim cover unavailable - no touch wake-up, press the power key twice to recover)"
                        } else {
                            "SCREEN: physical screen power-off failed (ROM gave the shell no permission / display token unavailable)"
                        },
                    )
                }
            }
        }
    }

    /**
     * Settings panel toggled "real screen-off on connect": takes effect immediately during a session (does nothing without a session).
     *
     * It can be operated on the head-unit screen, so a black phone panel does not block the toggle; turning it off lights the panel at once.
     */
    fun applyScreenOffPreference(enabled: Boolean) {
        if (enabled) {
            enterPhoneScreenOff()
        } else {
            restorePhoneScreen(sleepIfCarSide = false)
        }
    }

    /**
     * Restore the physical screen to lit. Idempotent: does nothing if we never turned it off.
     *
     * @param sleepIfCarSide true when the head-unit side disconnected (person out of the car, phone in a pocket): put the phone straight to sleep
     *   without lighting the panel first (otherwise the pocketed phone would flash on, then off); a manual user disconnect passes false.
     */
    private fun restorePhoneScreen(sleepIfCarSide: Boolean) {
        Bg.run("screen-power-restore") {
            synchronized(screenPowerLock) {
                if (!phoneScreenOff) return@synchronized
                phoneScreenOff = false
                if (sleepIfCarSide) {
                    // Keep SurfaceFlinger in OFF so the framework sleeps normally; on the next wake-up the framework sets the panel ON
                    Shell.exec("input keyevent KEYCODE_SLEEP", 4_000L)
                    appendLog("SCREEN: head-unit side disconnected - phone put to sleep (panel kept dark)")
                } else {
                    // Close out the power-off state carried by the dim cover: remove the cover + restore panel power (idempotent)
                    ScreenDimmer.exitPanelOff()
                    val ok = CarInputClient.setPhysicalScreenPower(true)
                    appendLog("SCREEN: physical screen restored to lit ok=$ok")
                }
            }
        }
    }

    /**
     * Connects the video data port, with one quick retry when the first attempt was refused rather than timed out
     * (the port can open a moment after the screen SETUP reply). A timed-out attempt already used up the head unit's
     * ~2 s activation window, so it is not repeated.
     */
    private fun openDataPortWithRetry(sender: VideoSender?): Boolean {
        sender ?: return false
        val startedAt = android.os.SystemClock.uptimeMillis()
        if (sender.openDataPort()) return true
        if (connectCancelled || android.os.SystemClock.uptimeMillis() - startedAt > DATA_PORT_RETRY_WINDOW_MS) return false
        try {
            Thread.sleep(DATA_PORT_RETRY_DELAY_MS)
        } catch (_: InterruptedException) {
            return false
        }
        return !connectCancelled && sender.openDataPort()
    }

    /**
     * The video data port could not be connected after the screen SETUP: end this round instead of sending RECORD
     * (chengshl/cardeck ec70bb80). Before, the failure was ignored and RECORD went out anyway, so the app showed
     * "connected" over a black head-unit screen until the car's watchdog dropped the session ~10 s later, with nothing
     * in the log saying why. The round now ends like a rejected SETUP; the BT link stays for the next invite.
     */
    private fun failDataPort(client: AirPlayClient) {
        phaseHint = UiStrings.text(R.string.phase_37)
        appendLog("AIRPLAY: video data port unreachable after screen SETUP - not sending RECORD, ending this round")
        videoSender?.stop()
        videoSender = null
        client.teardownSession()
        runCatching { client.close() }
    }

    /** Encoder + data port of the alternate (cluster) screen stream; null = none this session. */
    @Volatile
    private var altVideoSender: VideoSender? = null

    /**
     * Second screen stream for a receiver's alternate display (type 111; ChairPlay AltScreen "Map" modes). It carries the
     * map display, centre-cropped to the alt aspect, at the alt display's frame rate (`AltScreenOutput`). H.264 always.
     * A failure only loses the cluster picture.
     */
    private fun startAltScreen(
        client: AirPlayClient,
        sessionKey: ByteArray?,
        setup: AirPlayClient.SetupInfo,
        host: String,
    ): Boolean {
        val alt = setup.altDisplay ?: return false
        val stream = client.runAltScreenStream(sessionKey, alt.uuid) ?: return false
        val width = (alt.width / 2) * 2
        val height = (alt.height / 2) * 2
        val fps = alt.maxFps.coerceIn(10, 30)
        val sender =
            VideoSender(
                host = host,
                dataPort = stream.dataPort,
                frameCipher = stream.cipher,
                width = width,
                height = height,
                targetFps = fps,
                log = { line -> appendLog("ALT $line") },
                keepAliveWithStats = setup.keepAliveSendStatsAsBody,
            )
        if (!sender.openDataPort()) {
            appendLog("ALT: alt screen data port unreachable - no cluster picture this session")
            return false
        }
        altVideoSender = sender
        val output = io.github.ezn24.cardeck.neo.render.AltScreenOutput
        output.log = { line -> appendLog(line) }
        output.setViewAreas(alt.viewAreas, alt.initialViewArea)
        altScreenUuid = alt.uuid
        io.github.ezn24.cardeck.neo.nav.CarRouteGuidance.onInfo = { info -> output.onNav(info) }
        sender.start { surface ->
            io.github.ezn24.cardeck.neo.render.AltScreenOutput
                .set(
                    io.github.ezn24.cardeck.neo.render.AltScreenOutput
                        .Target(surface, width, height, fps),
                )
        }
        appendLog("ALT: alt screen ${width}x$height@$fps shows the map display")
        return true
    }

    /** uuid of the alt display, to route its showUI / updateViewArea commands. */
    @Volatile
    private var altScreenUuid: String? = null

    private fun stopAltScreen() {
        io.github.ezn24.cardeck.neo.input.KnobFocus
            .reset()
        altScreenUuid = null
        io.github.ezn24.cardeck.neo.nav.CarRouteGuidance.onInfo = null
        io.github.ezn24.cardeck.neo.render.AltScreenOutput
            .set(null)
        altVideoSender?.stop()
        altVideoSender = null
    }

    /** Fully retire the previous session before it ends / a new cycle starts (a leaked old pipeline accumulates encoders/virtual displays and eventually fails dequeueBuffer -19). */
    fun retireSession() {
        io.github.ezn24.cardeck.neo.audio.CarMicController
            .detach("session retired")
        stopAltScreen()
        videoSender?.stop()
        videoSender = null
        // Dim cover / keep-awake are dropped with the session (this method also runs at the start of every pairing round; they are set again once the new round streams)
        ScreenDimmer.setSessionActive(false)
        // Physical-screen fallback restore: if a crash/abnormal path left the panel OFF, it will not be left rotting in a black screen here
        restorePhoneScreen(sleepIfCarSide = false)
        audioSender?.stop()
        audioSender = null
        currentSharedSecret = null
        currentAudioHost = null
        currentHiddenSource?.release()
        currentHiddenSource = null
        TouchRouter
            .reset()
        CarNetwork.resetSession()
        currentClient?.let { old ->
            old.cancelled = true
            // Tell the head unit the session is over before closing (AirPlayClient.teardownSession, bounded to <1 s).
            // From the UI thread the network I/O goes to the background; on a pairing thread it runs inline so the old
            // connection is fully closed before the next round connects to the same port.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Bg.run("airplay-teardown") {
                    old.teardownSession()
                    old.release()
                }
            } else {
                old.teardownSession()
                old.release()
            }
        }
        currentClient = null
        // Note: the BT control session must never be detached here. This method also runs at the start of every pairing round,
        // and DiPlay, on seeing RFCOMM drop before the WiFi tunnel is ready, judges bring-up failed and
        // closeWirelessStack tears down the whole wireless stack (Bonjour/hotspot/AirPlay service), taking down an established
        // or even streaming AirPlay session with it - root cause of the "drops ~3s after connecting" loop
        // (measured 2026-10-16). BT retirement happens only at the user terminal state, see disconnect().
        // Lift session quiet mode: outbound probing resumes (set again when the next session is established)
        BtSessionGate.sessionActive = false
        // Restoring the "force app landscape" overrides is deliberately not done here (Audi 3315 on-device log finding, 2026-10-23):
        // this method also runs at the start of every pairing round, only seconds after the previous round ended - the previous round's
        // desktop/map apps (the same packages as the new round's) are mostly still alive, and this session's apps are
        // launched right away in attach. If resetAll's `am compat disable` ran in this window it would kill exactly the apps the
        // new round just launched -> the virtual display shows only the static wallpaper -> the picture the head unit receives never changes -> the head unit
        // does not switch to the CarPlay UI (the user sees "streaming but the head unit still shows its original screen").
        // Restore runs only at real terminal states: disconnect() (user disconnects) and streaming onEnded (the head unit tears down the link),
        // each calling resetAllAsync. The new round's applyAll is idempotent with the old overrides (enable over enable),
        // so there is no need to restore before writing.
    }

    /** When the session is established and the projection is ready but audio has not started, start it dynamically (the capture grant came after the connection). */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client =
            currentClient ?: run {
                appendLog("MODE: capture ready (no session now - audio is enabled on the next connection)")
                return
            }
        val secret = currentSharedSecret
        if (secret == null) {
            appendLog("MODE: plaintext (unpaired) session has no shared key - audio not started late")
            return
        }
        if (audioSender?.isRunning == true) return
        appendLog("MODE: session in progress, starting the audio channel dynamically...")
        startAudioChannel(context, client, secret, currentAudioHost)
    }

    /**
     * Runs as soon as 0x5703 arrives (head-unit hotspot credentials obtained) - the precondition of the real car's 0x4301:
     * the head unit discovers the phone's `_carplay-ctrl._tcp` (TXT id = real Bluetooth MAC) on the WiFi network, then
     * sends `GET /ctrl-int/1/connect` itself, and only once the invitation is accepted does it send 0x4301.
     * So here we must: bind the head-unit WiFi -> advertise ctrl; doing it after 0x4300 makes both sides wait on each other in a deadlock (Audi measurement).
     */
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        // A 0x5703 received while the session is streaming is a redundant credential (the DiPlay controller's periodic reconnect resends a round of
        // Phase 1, one driver of the "auto-disconnect after connecting" loop, measured 2026-10-15): going through binding again would
        // release+rebind the process network (disturbing the streaming socket) and force re-register ctrl
        // mDNS (DiPlay sees the phone as freshly online and pulls another RFCOMM round). Only the password cache is refreshed.
        if (videoSender?.isRunning == true) {
            lastWifiPsk = wifi.passphrase
            lastCarWifiSsid = wifi.ssid
            appendLog("0x5703 redundant credentials (session streaming) - keeping the existing WiFi bind and ctrl advertisement unchanged")
            return
        }
        ssidSink?.invoke(wifi.ssid)
        lastWifiPsk = wifi.passphrase
        lastCarWifiSsid = wifi.ssid
        phaseHint = UiStrings.text(R.string.phase_04)
        appendLog(">>> Head-unit hotspot credentials obtained (0x5703) - binding WiFi + advertising _carplay-ctrl immediately")
        // The ctrl advertisement was already published at startConnection (catplay: keep the capability online before connecting).
        // Here we record the SSID and align the registration once (if the phone is already manually on the head-unit hotspot and Shizuku is
        // unavailable, this is the only re-registration chance); after joining the hotspot bindCarWifiAndRegister force
        // re-registers again, putting multicast on the head unit's network (needed on a real car, measured).
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: advertisement already published at start-connection; aligning to hotspot ${wifi.ssid} here")
        // Binding and switching both go through the auto-join flow: bindCurrentWifi is a blind bind that "succeeds on any WiFi",
        // and with the phone on a corporate WiFi it would misjudge being on the head-unit hotspot (ctrl advertisement lands on the wrong network -> mutual-wait deadlock),
        // so the real SSID must be checked before deciding to bind or switch
        autoJoinCarWifi(wifi)
    }

    /**
     * Verify the real WiFi and join the head-unit hotspot. Silent path first (since 2026-10-22):
     *  1. `cmd wifi set-wifi-enabled` + `cmd wifi connect-network` (Shizuku): the system connects to the hotspot directly,
     *     zero dialogs, zero user action; the hotspot is saved into the system config and rejoined automatically next time in range;
     *  2. `requestNetwork(TRANSPORT_WIFI)` (without a specifier) obtains the WiFi `Network`
     *     handle and binds the process network - with no specifier no system confirmation dialog appears;
     *  3. every step checks [connectCancelled] - after a user cancel it must not keep running (otherwise phaseHint is overwritten and
     *     the ctrl advertisement is restarted, which shows up as "cannot cancel").
     * Only when the silent path fails (Shizuku unavailable / command-line connect failed) does it fall back to the app-specific network
     * (`WifiNetworkSpecifier`, which shows a system confirmation dialog on first join).
     * If everything fails, fall back to the "join manually in system WLAN" guidance (the password can be copied via "go connect").
     */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        Bg.run("wifi-auto-join") {
            val shellReady =
                appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
            // 1) Preferred silent path: Shizuku command-line connect (no dialog)
            if (shellReady) {
                if (joinCarWifiSilently(wifi) is WifiCarPlayConnector.JoinResult.Joined) return@run
                if (connectCancelled) return@run
                // The hotspot is connected and only the Network channel failed to bind: never fall back to the specifier - that would
                // re-associate the hotspot and show the system confirmation dialog (the user sees "connected, then a dialog pops up"). Abort and ask to reconnect.
                if (shellWifiStatus().second == wifi.ssid) {
                    phaseHint = UiStrings.text(R.string.phase_05)
                    appendLog(
                        "WIFI: already on \"${wifi.ssid}\" but requestNetwork did not call back - aborting this round" +
                            "(not falling back to the dialog-prone specifier path)",
                    )
                    return@run
                }
            }
            // 2) Fallback: app-specific network (does not affect 4G/5G, not saved as a system network)
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled()) return@run
            if (connectCancelled) return@run
            when (val result = joinCarWifiSpecifier(wifi)) {
                is WifiCarPlayConnector.JoinResult.Joined -> Unit

                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    appendLog(
                        "WIFI: hotspot security type ${wifi.securityType} cannot be expressed as an app-specific network - falling back to system-level join",
                    )
                    legacyJoinCarWifi(wifi, shellReady)
                }

                WifiCarPlayConnector.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady)
            }
        }
    }

    /**
     * Network-join entry when the endpoint arrives before 0x5703 (or the previous round's network switch failed): silent path first,
     * falling back to the app-specific network if the silent path fails.
     */
    private fun joinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): WifiCarPlayConnector.JoinResult {
        if (!shellReady) return joinCarWifiSpecifier(wifi)
        val silent = joinCarWifiSilently(wifi)
        if (silent is WifiCarPlayConnector.JoinResult.Joined) return silent
        // Hotspot connected but the channel failed to bind: do not go through the dialog-prone specifier (same judgment as in autoJoinCarWifi)
        if (!connectCancelled && shellWifiStatus().second == wifi.ssid) return silent
        return joinCarWifiSpecifier(wifi)
    }

    /**
     * Silently join the head-unit hotspot (two steps, zero dialogs throughout).
     *
     * Step 1: Shizuku (uid 2000) runs `cmd wifi connect-network "<SSID>" wpa2 "<password>"`;
     * the system associates with the hotspot directly and saves it as an ordinary network - without `WifiNetworkSpecifier`,
     * so no system confirmation dialog is triggered (the specifier dialog is forced at the framework level and apps cannot turn it off).
     *
     * Step 2: a `requestNetwork(TRANSPORT_WIFI)` without a specifier captures the `Network` handle of the WiFi just joined
     * and binds the process network ([bindCarWifiAndRegister]). With no specifier the system shows no dialog
     * and promptly hands the head-unit WiFi `Network` callback to us.
     *
     * Why step 2 still needs `bindProcessToNetwork` (rather than only passing the network to NsdManager):
     *  - `NsdManager.registerService` for the mDNS `_carplay-ctrl._tcp` advertisement has no Network
     *    parameter (only API 36's `AdvertisingRequest` has it; this project's compileSdk is 34), so registration follows the
     *    process default network. Without binding the advertisement leaves via cellular -> the head unit cannot discover the phone -> 0x4301 never arrives;
     *  - many bare `Socket()`/`DatagramSocket()` uses in the AirPlay control/event/video/audio paths depend on the process binding;
     *  - binding affects only this process's sockets: navigation/music in the virtual displays are separate processes and unaffected
     *    (`bindProcessToNetwork` is not a global/system-level operation).
     */
    private fun joinCarWifiSilently(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        // Shares one lock with the specifier fallback path: both paths release + rebind the process network.
        // For the locked implementation see [joinCarWifiSilentlyLocked].
        return synchronized(wifiBindLock) { joinCarWifiSilentlyLocked(wifi) }
    }

    /** Locked implementation of [joinCarWifiSilently] (the caller must hold [wifiBindLock]). */
    private fun joinCarWifiSilentlyLocked(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val existing = connector.currentNetwork
        if (boundCarSsid == wifi.ssid && existing != null) {
            appendLog("WIFI: head-unit hotspot ready and network channel already bound - skipping duplicate join")
            return WifiCarPlayConnector.JoinResult.Joined(existing)
        }
        if (!ensureWifiEnabledSilently()) return WifiCarPlayConnector.JoinResult.Unavailable
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog(
                "WIFI: phone is already on head-unit hotspot \"${wifi.ssid}\" - establishing the network channel and binding directly",
            )
            if (wifi.ssid in appSavedSsids()) registerShellSavedNetwork(wifi.ssid, preExisting = true)
        } else {
            // If it is known to have been saved by this app there is no need to look it up (saves a shell round trip; joining must finish within the head unit's ~10s window)
            val preExisting = wifi.ssid !in appSavedSsids() && savedNetworkIds(wifi.ssid).isNotEmpty()
            phaseHint = UiStrings.format(R.string.phase_06, wifi.ssid)
            appendLog(
                ">>> Silent join of head-unit hotspot \"${wifi.ssid}\" (cmd wifi connect-network, no dialog; " +
                    "current ${current ?: "not connected"})",
            )
            // The command returns as soon as it has handed the connection to WifiService; exit=0 does not mean connected - the SSID must be polled
            val result =
                Shell.exec(
                    WifiConnectCommand.build(wifi.ssid, wifi.passphrase, wifi.securityType),
                    20_000L,
                )
            if (!result.ok) {
                appendLog(
                    "WIFI: connect-network failed (exit=${result.exitCode} " +
                        "${result.output.take(80)})",
                )
                return WifiCarPlayConnector.JoinResult.Unavailable
            }
            // When the command returns ok the hotspot is already in the system config - register it at once (cancel/timeout paths must not leave it behind either)
            registerShellSavedNetwork(wifi.ssid, preExisting)
            if (!waitForCarSsid(wifi.ssid, CAR_WIFI_JOIN_WAIT_MS)) {
                return WifiCarPlayConnector.JoinResult.Unavailable
            }
        }
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val bound = bindCarWifiAndRegister(wifi.ssid)
        val network = connector.currentNetwork
        return if (bound && network != null) {
            WifiCarPlayConnector.JoinResult.Joined(network)
        } else {
            WifiCarPlayConnector.JoinResult.Unavailable
        }
    }

    /** Turn WLAN on directly via Shizuku when it is off (`cmd wifi set-wifi-enabled`); returns whether it is now enabled. */
    private fun ensureWifiEnabledSilently(): Boolean {
        if (!shellWifiDisabled()) return true
        appendLog("WIFI: WLAN is off - turning it on via Shizuku (cmd wifi set-wifi-enabled enabled, no manual step)")
        val result = Shell.exec("cmd wifi set-wifi-enabled enabled", 8_000L)
        if (!result.ok) {
            appendLog("WIFI: failed to turn WLAN on (exit=${result.exitCode} ${result.output.take(60)})")
            return false
        }
        val deadline = android.os.SystemClock.uptimeMillis() + WIFI_ENABLE_WAIT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            if (!shellWifiDisabled()) return true
            Thread.sleep(500)
        }
        if (shellWifiDisabled()) {
            appendLog("WIFI: timed out waiting for WLAN to turn on (${WIFI_ENABLE_WAIT_MS / 1000}s)")
            return false
        }
        return true
    }

    /** Poll `cmd wifi status` until associated with [ssid] (cancelable). */
    private fun waitForCarSsid(
        ssid: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var lastLogAt = 0L
        var current: String? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            current = shellWifiStatus().second
            if (current == ssid) {
                appendLog("WIFI: switched to head-unit hotspot \"$ssid\"")
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogAt >= 3_000L) {
                lastLogAt = now
                phaseHint = UiStrings.format(R.string.phase_07, ssid)
                appendLog("WIFI: waiting to switch to \"$ssid\" (current ${current ?: "not connected"})...")
            }
            Thread.sleep(500)
        }
        appendLog("WIFI: timed out waiting to switch to \"$ssid\" (current ${current ?: "not connected"})")
        return false
    }

    /**
     * Join the head-unit hotspot via an app-specific network (`WifiNetworkSpecifier`); on success the process network is bound.
     *
     * Only a fallback for the silent path (Shizuku unavailable, or the ROM blocks `cmd wifi connect-network`):
     * the network is visible only to this process, not saved and not the default network, but the first join shows a system confirmation dialog.
     * For the same reason it no longer asks to "clear the same-named hotspot saved in the system" - the silent path
     * saves the hotspot into the system config by design (not a leftover of an old version).
     */
    private fun joinCarWifiSpecifier(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        phaseHint = UiStrings.format(R.string.phase_08, wifi.ssid)
        appendLog(
            ">>> Joining \"${wifi.ssid}\" as an app-specific network (not saved as system WiFi; other apps stay on 4G/5G)",
        )
        val result =
            synchronized(wifiBindLock) {
                val existing = connector.currentNetwork
                if (boundCarSsid == wifi.ssid && existing != null) {
                    appendLog("WIFI: app-specific network already in place - skipping duplicate join")
                    WifiCarPlayConnector.JoinResult.Joined(existing)
                } else {
                    // Probe first (upstream shaonialife/cardeck 9d31800): when the system is already on the car hotspot
                    // (saved network, manual join) the specifier sits pending for its whole 5 s first window on some
                    // ROMs, out of a ~9-14 s join budget. Without Shizuku or when the SSID differs this returns null at
                    // once and the specifier path runs as before.
                    specifierQuickFallback(wifi)?.let { bound -> return@synchronized WifiCarPlayConnector.JoinResult.Joined(bound) }
                    connector.joinCarPlayNetwork(
                        ssid = wifi.ssid,
                        passphrase = wifi.passphrase,
                        securityType = wifi.securityType,
                        timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                        isCancelled = { connectCancelled },
                        quickFallback = { specifierQuickFallback(wifi) },
                        deepFallback = { specifierDeepFallback(wifi) },
                    )
                }
            }
        if (result is WifiCarPlayConnector.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            phaseHint = UiStrings.text(R.string.phase_09)
            // Timing is critical: register only after bindProcessToNetwork completes, so the head unit can see the ctrl advertisement
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
            appendLog(
                ">>> WiFi joined the head-unit hotspot (app-specific network), ctrl advertisement re-registered, waiting for the head unit's ctrl invitation",
            )
        } else if (!connectCancelled) {
            appendLog("WIFI: app-specific network join did not succeed (${result.javaClass.simpleName})")
            // Fallback: when the system has this hotspot saved it may already have connected to it globally - with a hanging specifier
            // this shortcut is the steadiest (on-device 2026-10-22: user chose "keep system network" +
            // Android 16 specifier pending forever). Verify the current SSID, then bind the existing connection directly.
            val currentSsid = shellWifiStatus().second
            if (currentSsid == wifi.ssid) {
                appendLog(
                    "WIFI: system is currently on \"$currentSsid\" - binding the existing connection directly (shortcut when a saved network auto-connects)",
                )
                // bindCurrentWifi calls back asynchronously: wait briefly for the bind to finish (the callback is quick, the system is already on the network)
                if (bindExistingCarWifi(connector, wifi.ssid) != null) {
                    boundCarSsid = wifi.ssid
                    phaseHint = UiStrings.text(R.string.phase_10)
                    ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
                    appendLog(">>> WiFi bound to the existing \"$currentSsid\" connection, ctrl advertisement re-registered")
                    return WifiCarPlayConnector.JoinResult.Joined(connector.currentNetwork!!)
                }
                appendLog("WIFI: binding the existing connection did not succeed - treated as failure")
            }
        }
        return result
    }

    /**
     * SSID recorded when this round's shell fallback saved the hotspot as a system network - at the session terminal state (disconnect and
     * streaming onEnded) it is automatically `forget`-ed. A system-saved hotspot without internet is taken as the default network and
     * on some ROMs displaces mobile data.
     */
    @Volatile
    private var shellFallbackCleanupSsid: String? = null

    /**
     * Head-unit hotspots saved into the system by CarDeck itself (connect-network), recorded persistently.
     *
     * On-device, 2026-11-18: "the phone stays on the head-unit hotspot after disconnect / cancel": the silent-join path ran
     * connect-network every round but never registered the end-of-session removal - the hotspot stayed in the system, and the next round
     * took the "already connected" branch and registered nothing either. With this record, "previously saved" can tell apart a network the user
     * kept (not deleted) from one we saved (deleted at session end).
     */
    private fun appSavedSsids(): MutableSet<String> =
        appContext
            ?.getSharedPreferences("wifi", Context.MODE_PRIVATE)
            ?.getStringSet("appSavedSsids", emptySet())
            .orEmpty()
            .toMutableSet()

    private fun setAppSaved(
        ssid: String,
        saved: Boolean,
    ) {
        val set = appSavedSsids()
        if (if (saved) set.add(ssid) else set.remove(ssid)) {
            appContext
                ?.getSharedPreferences("wifi", Context.MODE_PRIVATE)
                ?.edit()
                ?.putStringSet("appSavedSsids", set)
                ?.apply()
        }
    }

    /** After connect-network succeeds, register the end-of-session removal; except same-named networks the user kept. */
    private fun registerShellSavedNetwork(
        ssid: String,
        preExisting: Boolean,
    ) {
        if (preExisting && ssid !in appSavedSsids()) {
            appendLog("WIFI: \"$ssid\" was already saved in the system before (the user chose to keep it) - not removing at session end")
            return
        }
        shellFallbackCleanupSsid = ssid
        setAppSaved(ssid, true)
        appendLog("WIFI: \"$ssid\" was saved as a system network by this app - registered for automatic removal at session end")
    }

    /**
     * Quick fallback when the specifier hangs (called after the first 5s window with zero callbacks; runs on the connect-flow thread).
     *
     * Probe: after `forget` of the old network the system may already have reconnected to the same SSID - if so, bind the existing connection directly.
     * It must stay at millisecond shell round trips + <=3s bind wait: the head unit's join budget counted from 0x5703 is only
     * ~9-14s (BT torn down ~15s after 0x4301), and every extra second here squeezes the "resend specifier" window that follows
     * (joined in 1.6s when measured, the verified recovery method).
     *
     * @return a network that is bound and ready to use; null = no hit (the caller goes on to resend the specifier).
     */
    private fun specifierQuickFallback(wifi: Iap2SenderSession.WifiConfiguration): Network? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        val current = shellWifiStatus().second
        if (current != wifi.ssid) return null
        appendLog("WIFI: system is already on \"$current\" - binding directly (quick fallback)")
        val network = bindExistingCarWifi(connector, wifi.ssid)
        if (network != null) boundCarSsid = wifi.ssid
        return network
    }

    /**
     * Deep fallback after the specifier resend window also gets no callback: `cmd wifi connect-network` connects to the hotspot directly.
     *
     * By the time this runs this round's head-unit window (~15s) has certainly passed - the value is getting the phone onto the head-unit hotspot first,
     * so the next round (BT reconnects 0.17s after the head unit tears down the link) takes the "system already connected" quick fallback/shortcut instead of spinning.
     *
     * The timing of registering the cleanup is critical: the moment connect-network returns ok, the hotspot is already in the system config
     * and [shellFallbackCleanupSsid] must be registered immediately - if it waited for "poll sees the switch + bind succeeds",
     * the cancel/timeout/bind-failure paths would exit before registering and leave the system network behind (a no-internet hotspot
     * auto-connected globally displacing mobile data; reviewed 2026-10-23). Forgetting an SSID that was never saved
     * is a harmless no-op, so over-register rather than miss.
     *
     * Exception: if this SSID was already saved in the system before this round (the user explicitly chose "keep" in the dialog)
     * - connect-network is then only an idempotent reconnect and cleanup is not registered, otherwise the session end would delete a network
     * the user decided to keep (violating "never delete on our own").
     */
    private fun specifierDeepFallback(wifi: Iap2SenderSession.WifiConfiguration): Network? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        appendLog(
            "WIFI: no callback in either specifier window - deep fallback connect-network to \"${wifi.ssid}\"" +
                "(this round's window has most likely passed; placeholder for the next round)",
        )
        val preExisting = savedNetworkIds(wifi.ssid).isNotEmpty()
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val r = Shell.exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security${WifiConnectCommand.AUTOJOIN_DISABLED}", 10_000L)
        if (!r.ok) {
            appendLog("WIFI: connect-network failed (${r.output.take(80)})")
            return null
        }
        registerShellSavedNetwork(wifi.ssid, preExisting)
        // Poll until the switch completes (drop old network / associate / DHCP, measured 2-10s)
        repeat(30) {
            if (connectCancelled) return null
            Thread.sleep(300)
            if (shellWifiStatus().second == wifi.ssid) {
                val network = bindExistingCarWifi(connector, wifi.ssid)
                if (network != null) {
                    boundCarSsid = wifi.ssid
                    appendLog("WIFI: connect-network connected to \"${wifi.ssid}\"")
                }
                return network
            }
        }
        appendLog(
            "WIFI: not on the head-unit hotspot 9s after connect-network - returning failure (system network registered, removed at session end)",
        )
        return null
    }

    /** Bind the currently connected WiFi and wait briefly for the bind to finish (shared by the fallback paths). Returns the bound network or null. */
    private fun bindExistingCarWifi(
        connector: WifiCarPlayConnector,
        ssid: String,
    ): Network? {
        var boundOk = false
        // ssidHint: when the binding drops (ignition off) the session layer is told via onNetworkLost - the fallback join is the main path now,
        // and without the notification the hero card would stay on "connected" and the session would die only at the heartbeat timeout
        connector.bindCurrentWifi(ssid) { boundOk = it }
        val waitUntil = SystemClock.uptimeMillis() + 3_000
        while (!boundOk &&
            connector.currentNetwork == null &&
            SystemClock.uptimeMillis() < waitUntil &&
            !connectCancelled
        ) {
            Thread.sleep(100)
        }
        return connector.currentNetwork
    }

    /** Clean up the system network saved by the shell fallback at the session terminal state (shared by [disconnect] and streaming onEnded). */
    private fun cleanupShellFallbackNetwork() {
        val ssid = shellFallbackCleanupSsid ?: return
        shellFallbackCleanupSsid = null
        Bg.run("wifi-fallback-cleanup") {
            // forget-network accepts only a netId (the old code passed the SSID, the command failed and the hotspot stayed in the system)
            val ids = savedNetworkIds(ssid)
            val ok = ids.isNotEmpty() && ids.all { Shell.exec("cmd wifi forget-network $it", 5_000L).ok }
            if (ok || ids.isEmpty()) setAppSaved(ssid, false)
            appendLog(
                "WIFI: removed \"$ssid\" saved by this app at session end (netId=$ids) ok=$ok" +
                    (if (!ok && ids.isNotEmpty()) " (delete it manually in Settings > WLAN)" else ""),
            )
        }
    }

    /** `cmd wifi` can run only when Shizuku is ready (same guard as the other shell calls). */
    private fun shellWifiReady(): Boolean = appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true

    /**
     * When the system already has a same-named hotspot saved, ask the user whether to clear it (never delete on our own).
     *
     * Why ask instead of deleting directly: keeping it has legitimate uses - the user may want the system to auto-connect to the head unit,
     * or the head-unit hotspot really has internet (some models provide data). Deleting only affects whether the system connects to it
     * globally and automatically, and has nothing to do with this round's connection; choosing "keep" continues connecting normally.
     *
     * Asked once per SSID per process: pairing retries and endpoint handoff re-entry reach here again,
     * and repeated dialogs would block the connection (measured 2026-10-18). The user's choice is remembered for this process.
     *
     * Blocks until the user chooses ([JOIN_DIALOG_TIMEOUT_S] timeout is treated as "keep" - no destructive action).
     */
    private fun promptForgetSavedNetworkIfAny(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid)
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: system already has hotspot \"$ssid\" saved (netId=${ids.joinToString(",")})" +
                " - asking the user whether to clear it (keeping it also connects fine)",
        )
        val forget =
            askWifiJoinChoice(
                DialogSpec(
                    title = "已保存该车机热点",
                    message =
                        "系统里保存过 \"$ssid\"。保留会让手机在进入范围时自动连接它，" +
                            "若该热点没有互联网，可能导致手机无法使用移动数据。\n\n" +
                            "「清除」只是不再自动连接，不影响本次使用；" +
                            "如果车机热点本身提供网络，选「保留」。",
                    positive = "清除保存",
                    negative = "保留",
                ),
            )
        if (connectCancelled) return
        when (forget) {
            true -> {
                ids.forEach { id ->
                    val result = Shell.exec("cmd wifi forget-network $id", 5_000L)
                    appendLog(
                        "WIFI: cleared the system-saved hotspot \"$ssid\" at the user's choice (netId=$id ok=${result.ok})",
                    )
                }
            }

            false -> appendLog("WIFI: the user chose to keep the system-saved \"$ssid\" - continuing with the app-specific network")

            // Nobody answered (UI not in the foreground): treat as "keep", never delete a network without the user's confirmation
            null -> appendLog("WIFI: nobody answered whether to clear the saved network - treated as keep (user config untouched)")
        }
    }

    /** Look up the ids of system-saved networks whose SSID exactly matches the target (`cmd wifi list-networks`). */
    private fun savedNetworkIds(ssid: String): List<Int> {
        val listing = Shell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: cannot read the system-saved networks (exit=${listing.exitCode}) - skipping the check")
            return emptyList()
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** Guidance and wait when WiFi is off; true = it is on and the flow can continue. */
    private fun waitForWifiEnabled(): Boolean {
        val open =
            askWifiJoinChoice(
                DialogSpec(
                    title = "WiFi 未开启",
                    message = "连接车机热点需要打开手机 WLAN。是否打开 WLAN 设置？",
                    positive = "打开 WLAN 设置",
                    negative = "取消",
                ),
            )
        if (connectCancelled) return false
        when (open) {
            true -> {
                openWlanSettings()
                uiHintSink?.invoke("请在 WLAN 设置中打开 WiFi 开关")
            }

            false -> {
                phaseHint = UiStrings.text(R.string.phase_11)
                appendLog("WIFI: user cancelled - auto-join aborted (WiFi is off)")
                return false
            }

            null -> {
                phaseHint = UiStrings.text(R.string.phase_12)
                appendLog("WIFI: dialog unanswered - auto-join aborted (WiFi is off)")
                return false
            }
        }
        var waited = 0
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && !connectCancelled) {
            if (waited == 0) {
                phaseHint = UiStrings.text(R.string.phase_13)
                appendLog("WIFI: waiting for the user to turn on WLAN (up to 120s; cancelling the connection aborts)")
            }
            Thread.sleep(2_000)
            waited += 2_000
        }
        if (connectCancelled) {
            phaseHint = UiStrings.text(R.string.phase_14)
            return false
        }
        if (shellWifiDisabled()) {
            phaseHint = UiStrings.text(R.string.phase_15)
            appendLog("WIFI: timed out and WiFi is still off - auto-join aborted, turn on WLAN and reconnect")
            return false
        }
        return true
    }

    /** Both the silent path and the app-specific network failed: let the user retry or switch to a manual connect. */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "未能加入车机热点",
                    message =
                        "自动加入 \"${wifi.ssid}\" 没有成功（热点可能不在范围内，或系统拒绝了连接）。\n\n" +
                            "「重试」会再自动加入一次（先走免弹窗的静默路径，仍不行才会弹系统确认框）；" +
                            "也可以到 WLAN 设置手动连接（密码已复制到剪贴板）。",
                    positive = "重试",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        when (choice) {
            true -> {
                when (val retry = joinCarWifi(wifi, shellReady)) {
                    is WifiCarPlayConnector.JoinResult.Joined -> Unit
                    WifiCarPlayConnector.JoinResult.Unsupported -> legacyJoinCarWifi(wifi, shellReady)
                    WifiCarPlayConnector.JoinResult.Unavailable -> {
                        phaseHint = UiStrings.text(R.string.phase_16)
                        appendLog("WIFI: retry still did not join \"${wifi.ssid}\" - connect manually in system WLAN")
                    }
                }
            }

            false -> manualJoinCarWifi(wifi)

            null -> {
                phaseHint = UiStrings.text(R.string.phase_17)
                appendLog("WIFI: dialog unanswered - auto-join aborted")
            }
        }
    }

    /** Manual fallback: password to clipboard + jump to WLAN settings, poll whether the user has connected. */
    private fun manualJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        copyHotspotPsk(wifi.passphrase)
        openWlanSettings()
        phaseHint = UiStrings.format(R.string.phase_18, wifi.ssid)
        appendLog(
            "WIFI: user chose manual connect - waiting for WLAN to connect to \"${wifi.ssid}\" (up to 120s); " +
                "note: connecting manually saves the hotspot as a system network, which may affect mobile data if it has no internet",
        )
        val manualDeadline = android.os.SystemClock.uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < manualDeadline) {
            if (connectCancelled) return
            Thread.sleep(5_000)
            if (shellWifiStatus().second == wifi.ssid) {
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
        }
        phaseHint = UiStrings.format(R.string.phase_19, wifi.ssid)
        appendLog("WIFI: timed out waiting for the manual connect - auto-join aborted")
    }

    /**
     * Legacy path (security types such as WEP that a specifier cannot express): `cmd wifi connect-network`
     * saves system-wide and switches. Note this saves the hotspot as a system network - it may affect mobile data when there is no internet,
     * so it is only a compatibility fallback.
     */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (!shellReady) {
            phaseHint = UiStrings.format(R.string.phase_20, wifi.ssid)
            appendLog(
                ">>> Phone is not on the head-unit hotspot \"${wifi.ssid}\" and Shizuku is not ready (cannot auto-join) - " +
                    "join manually in Settings > WLAN (the password can be copied via \"go connect\")",
            )
            return
        }
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog("WIFI: phone is already on head-unit hotspot \"$current\" - binding directly")
            bindCarWifiAndRegister(wifi.ssid)
            return
        }
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "连接车机热点",
                    message =
                        "将自动加入 \"${wifi.ssid}\" 热点。该热点无互联网，系统可能自动切回原 WiFi。" +
                            "\n\n出现断连时，请到 WLAN 设置中主动连接 \"${wifi.ssid}\"（手动连接不会被切回）。",
                    positive = "继续自动加入",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        if (choice == false) {
            manualJoinCarWifi(wifi)
            return
        }
        if (choice == null) {
            phaseHint = UiStrings.text(R.string.phase_21)
            appendLog("WIFI: dialog unanswered - auto-join aborted")
            return
        }
        phaseHint = UiStrings.format(R.string.phase_22, wifi.ssid)
        appendLog(
            ">>> Phone's current WiFi: ${current ?: "not connected"} (not the head-unit hotspot) - " +
                "switching to \"${wifi.ssid}\" automatically via Shizuku",
        )
        if (connectCancelled) return
        val result =
            Shell.exec(
                WifiConnectCommand.build(wifi.ssid, wifi.passphrase, wifi.securityType),
                10_000L,
            )
        if (!result.ok) {
            phaseHint = UiStrings.format(R.string.phase_23, wifi.ssid)
            appendLog(
                "WIFI: connect-network failed (${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }})" +
                    " - join manually in Settings > WLAN (the password can be copied via \"go connect\")",
            )
            return
        }
        phaseHint = UiStrings.format(R.string.phase_24, wifi.ssid)
        appendLog("WIFI: connect-network issued (network saved automatically, waiting for the switch...)")
        // The system switch is asynchronous (drop current network / scan / associate / DHCP, 2-10 seconds): poll the SSID to confirm.
        repeat(20) {
            if (connectCancelled) {
                appendLog("WIFI: auto-join cancelled (user disconnected)")
                return
            }
            val ssid = shellWifiStatus().second
            if (ssid == null) {
                // status command unavailable (non-AOSP system): degrade to trying the bind directly (no SSID check)
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: switched to head-unit hotspot ${wifi.ssid}")
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            Thread.sleep(800)
        }
        phaseHint = UiStrings.format(R.string.phase_25, wifi.ssid)
        uiHintSink?.invoke("未能在热点驻留——请到 WLAN 设置中主动连接 ${wifi.ssid}")
        appendLog(
            ">>> WiFi auto-join timed out (the system may have switched back to the original WiFi) - " +
                "join \"${wifi.ssid}\" manually in Settings > WLAN (a manual connection is not switched back automatically)",
        )
    }

    /**
     * Bind the current WiFi (should be the head-unit hotspot) and re-register the ctrl advertisement; give guidance when the bind times out.
     *
     * The bind is serialized with [wifiBindLock]: both the auto-join thread and the endpoint handoff thread may trigger it,
     * and concurrent bindCurrentWifi calls release each other (the mDNS advertisement stops working the instant it unbinds).
     *
     * The wait window [WIFI_BIND_WAIT_S] must be long enough: `requestNetwork(WIFI)` onAvailable,
     * when the phone is on no WiFi yet and the user is joining the hotspot by hand in system settings, fires only after
     * association + DHCP truly complete. The old 5s was a fatal race on the Audi: at 20:02:09.352 it judged
     * "not on the hotspot" and abandoned the whole session, and at 20:02:09.467 (116ms later) onAvailable arrived and
     * the bind succeeded; the user had to wait another BT reconnect round (+13s). Better to wait longer (interruptibly) than to fail falsely.
     *
     * @return whether the process network was bound successfully
     */
    private fun bindCarWifiAndRegister(ssid: String): Boolean {
        var bound = false
        synchronized(wifiBindLock) {
            // Another path already bound the same hotspot: do not go through release+requestNetwork again
            if (boundCarSsid == ssid && wifiConnector?.currentNetwork != null) {
                bound = true
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                wifiConnector?.bindCurrentWifi(ssid) { ok ->
                    bound = ok
                    latch.countDown()
                }
                // Segmented wait: a user cancel during it must exit at once, not wait out the whole window
                val deadline = android.os.SystemClock.uptimeMillis() + WIFI_BIND_WAIT_S * 1_000
                while (!connectCancelled &&
                    bound.not() &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                }
                if (bound) boundCarSsid = ssid
            }
        }
        if (bound && !connectCancelled) {
            phaseHint = UiStrings.text(R.string.phase_26)
            // Timing is critical: register only after bindProcessToNetwork completes, so the head unit can see the ctrl advertisement
            ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            appendLog(
                ">>> WiFi bound to the head-unit hotspot, ctrl advertisement re-registered, waiting for the head unit's ctrl invitation (0x4301 is answered after that)",
            )
        } else if (!connectCancelled) {
            phaseHint = UiStrings.format(R.string.phase_27, ssid)
            appendLog(
                ">>> Waited ${WIFI_BIND_WAIT_S}s and still no WiFi network handle for \"$ssid\" - " +
                    "make sure WLAN is connected to that hotspot and reconnect",
            )
        }
        return bound
    }

    /**
     * Ask the user in a dialog (blocks the calling thread until chosen/dismissed/90s timeout):
     * true = positive; false = negative; null = nobody answered (the caller decides the fallback).
     */
    private fun askWifiJoinChoice(spec: DialogSpec): Boolean? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice: Boolean? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val sink = dialogSink
            if (sink == null) {
                // No UI host (Activity-destroyed window): better to abort than to decide for the user by defaulting to join
                latch.countDown()
                return@post
            }
            sink(spec) { result ->
                choice = result
                latch.countDown()
            }
        }
        latch.await(JOIN_DIALOG_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        return choice
    }

    private fun openWlanSettings() {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** Copy the hotspot password to the clipboard (paste it on the WLAN page when connecting manually) and tell the user. */
    private fun copyHotspotPsk(passphrase: String) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    uiHintSink?.invoke("热点密码已复制（${passphrase.length} 位）——粘贴到密码框即可")
                }
            }
        }
    }

    /**
     * WiFi switch and the currently connected SSID (one `cmd wifi status` query).
     * Output looks like: `Wifi is enabled` + `Wifi is connected to "Redmi K40"` (no such line when not connected).
     */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result =
            Shell
                .exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
        return enabled to ssid
    }

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** POSIX shell literal wrapped in single quotes (an SSID/password may contain spaces and special characters). */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\''") + "'"

    /** Reproduce the "BT control ended" state: drop the iAP2 control session of whichever path is active (DiPlay answers RTSP only in this state). */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    /**
     * Wait for the head unit's ctrl invitation (`GET /ctrl-int/1/connect`) - catplay's connect trigger.
     *
     * An invitation means "the head unit has discovered us on WiFi and has opened the AirPlay port", so only then
     * does connecting AirPlay avoid Connection refused. The old code bet on a fixed sleep of 1.6s for the invitation to have arrived:
     * wasted wait when the head unit is fast, not enough when slow. This is event-driven, and also releases at once if mDNS has already discovered
     * `_airplay._tcp` (the port is certainly connectable), so old models that send no invitation do not wait out the whole window.
     *
     * @return true = invitation received / endpoint discovered; false = wait window exhausted
     */
    private fun awaitCtrlInvite(
        ssid: String,
        timeoutMillis: Long,
    ): Boolean {
        val advertiser = ctrlAdvertiser
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastLogAt = startedAt
        var reannounced = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            val invites = advertiser?.inviteCount ?: 0
            if (invites > 0) {
                appendLog(
                    "CTRL: ✓ head-unit invitation received (${android.os.SystemClock.uptimeMillis() - startedAt}ms, #$invites)" +
                        " - connecting AirPlay immediately",
                )
                return true
            }
            if (endpointBrowser?.awaitEndpoint(0) != null) {
                appendLog(
                    "CTRL: _airplay._tcp discovered (no invitation received, old-model behavior) - connecting AirPlay directly",
                )
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            // catplay measurement: the iPhone's Bonjour announcements are often dropped by the head unit - it does a
            // force_announce before every invitation. Send one more midway through the wait, covering "the announcement was lost the instant the network was bound".
            if (!reannounced && now - startedAt >= 2_000) {
                reannounced = true
                appendLog("CTRL: no invitation after 2s - forcing a re-announcement (same force_announce as catplay)")
                ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            }
            if (now - lastLogAt > 1_200) {
                appendLog("CTRL: waiting for the head unit's ctrl invitation... (${now - startedAt}ms so far)")
                lastLogAt = now
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        appendLog(
            "CTRL: no invitation after ${timeoutMillis}ms - continuing the connection on timeout (the head unit may use the old discovery path)",
        )
        return false
    }

    // ---- The methods below were moved over from MainActivity verbatim (session logic does not depend on an Activity) ----

    /**
     * Audio channel (phone -> head unit, RTP over UDP): SETUP type=100 negotiation -> playback capture -> ChaCha packet push.
     *
     * Depends on MediaProjection (the grant source of AudioPlaybackCapture), and the user must pick "enable sound" in the "start connection"
     * dialog - pushing audio and video together adds noticeable load (measured: stutter/disconnects).
     *
     * @return whether the audio channel actually started (the caller decides from it whether to resend RECORD to activate the newly added stream)
     */
    fun startAudioChannel(
        context: Context,
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean {
        if (!audioEnabledForSession) {
            appendLog("AUDIO: head-unit audio not enabled for this connection (chosen in the \"start connection\" dialog) - video only")
            return false
        }
        if (sharedSecret == null) {
            // A plaintext session (et=NONE) has no shared key to derive the DataStream output key:
            // the head unit cannot decrypt the RTP we would push. Better not to push than to create the illusion of "packets but no sound".
            appendLog("AUDIO: plaintext (unpaired) session has no shared key - skipping the audio channel")
            return false
        }
        if (host == null) {
            appendLog("AUDIO: no usable receiver address, skipping the audio channel")
            return false
        }
        val projection = CarPlayService.mediaProjection
        if (projection == null && io.github.ezn24.cardeck.neo.shizuku.CarInputClient.service == null) {
            appendLog(
                "AUDIO: capture permission missing (playback capture depends on MediaProjection) - audio channel not started. " +
                    "Tapping \"request capture permission\" now also starts it on the fly (no reconnect needed); note \"Disconnect\" stops the service and invalidates the capture grant with it",
            )
            return false
        }
        return try {
            // Head-unit identity -> used only for the persisted memory of "which audio tiers burned this head unit".
            val identity = client.receiverIdentity()
            // The audio packaging parameters are settled (see AudioTuning) and no longer switchable
            val tuning = AudioTuning.RAW_SPF
            val entry = AudioProfileMemory.load(context.applicationContext, identity)
            if (entry.disabled) {
                // Previously "all tiers cause teardown" automatically fell back to video only and made the user press
                // "clear memory" in a dialog. The audio path is now AAC-LC and measured stable for a full 147s,
                // so the old conclusion is no longer trustworthy: since the user chose "enable sound" on purpose, clear the memory and retry directly.
                AudioProfileMemory.clearAll(context.applicationContext)
                appendLog(
                    "AUDIO: this head unit ($identity) had an old \"all tiers tear down the link\" memory, cleared automatically and retrying",
                )
            }
            val candidates = client.audioCandidates(preferCompat = audioPreferCompat)
            val ordered = AudioProfileMemory.order(candidates, entry)
            if (ordered.isEmpty()) {
                appendLog("AUDIO: no usable audio tier (candidates=${candidates.map { it.label }}) - video only")
                return false
            }
            appendLog(
                "AUDIO: audio tier candidates=${ordered.map { it.label }}" +
                    " (this head unit's memory: ${AudioProfileMemory.describe(entry)}; " +
                    "packaging=${tuning.detail})",
            )
            // A SETUP failure does not disturb the session (the head unit just does not accept this stream's request), so the candidates are
            // all tried within this session, no need to wait for the next connection. This matters for the current scenario: 102/AAC-LC and 100/PCM
            // are two completely different paths, and when the first is not accepted it should fall back to the second at once, not make the user connect in vain.
            var chosen: AudioProfile? = null
            var streamInfo: AirPlayClient.AudioStreamInfo? = null
            for (candidate in ordered) {
                val result = client.runAudioStream(sharedSecret, candidate, tuning)
                if (result != null) {
                    chosen = candidate
                    streamInfo = result
                    break
                }
                // A rejected SETUP does not equal "head unit incompatible": it may be only resource arbitration/network jitter,
                // so the head unit's audio must not be written off on that basis (setting disabled relies only on teardown evidence).
                AudioProfileMemory.recordRejected(
                    context.applicationContext,
                    identity,
                    candidate.key,
                    ordered.map { it.key }.toSet(),
                    markExhausted = false,
                )
                appendLog("AUDIO: tier ${candidate.label} negotiation failed - trying the next tier in this session")
            }
            val profile = chosen
            val audio = streamInfo
            if (profile == null || audio == null) {
                appendLog("AUDIO: all candidate tiers failed to negotiate (${ordered.size} tiers) - video only this time")
                return false
            }
            activeAudioProfile = profile
            activeAudioReceiverKey = identity
            activeAudioStartedAt = android.os.SystemClock.uptimeMillis()
            val sender =
                AudioSender(
                    host = host,
                    dataPort = audio.dataPort,
                    streamKey = audio.streamKey,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                    sampleRate = audio.sampleRate,
                    codec = profile.codec,
                    streamType = profile.streamType,
                    adtsFrames = tuning.adts,
                    // Sync-packet target port: delivered by the head unit in the SETUP reply. Without it there is no RTP<->NTP anchor,
                    // The receiver can only "guess by arrival time", and strict implementations simply do not schedule playback (measured 2026-10-20).
                    controlPort = audio.controlPort,
                )
            audioSender = sender
            if (!sender.start(projection)) {
                audioSender = null
                activeAudioProfile = null
                activeAudioStartedAt = 0L
                false
            } else {
                true
            }
        } catch (error: Exception) {
            appendLog("AUDIO: channel start error - ${error.message}")
            audioSender = null
            activeAudioProfile = null
            activeAudioStartedAt = 0L
            false
        }
    }

    /**
     * Audio teardown attribution at session end (added from the Honda HFT / Alpine measurements, 2026-10-19).
     *
     * Symptom: after choosing "video + audio" the session is established and video is fine, but about 6s after audio starts the head unit tears down the whole
     * AirPlay session (`EVENT: read ended - Connection reset`), while video-only is stable.
     * The head unit never tells us which audio tier is incompatible, so it can only be inferred from survival time:
     *  - the whole link torn down before audio ran for [AUDIO_PROFILE_PROBE_MS] -> the tier is judged incompatible and
     *    recorded in this head unit's list (if all tiers are written off, set disabled so the next time pushes video only);
     *  - it ran the full window -> the tier is recorded as usable and is chosen first next time.
     *
     * Attribution happens only when audio actually started; a video-only session ending rewrites no memory.
     */
    private fun attributeAudioTeardown() {
        val profile = activeAudioProfile ?: return
        val startedAt = activeAudioStartedAt
        val identity = activeAudioReceiverKey
        // The user ended it: says nothing about the audio tier (and is not the head unit's watchdog)
        if (userEndedSession) {
            activeAudioProfile = null
            activeAudioStartedAt = 0L
            return
        }
        // Link-watchdog guard (Audi owner's 0.1.17 log, 2026-10-23): MIB3 armed but the control session
        // did not complete before the link was torn down = the head unit's ~10s link watchdog (10.18s after RECORD, same as
        // 0.1.9's 10.17s), unrelated to the audio tier. Without the guard the t102/media tier would be wrongly recorded as "incompatible", the next round
        // would switch to the compatibility tier - a path the head unit never accepts (see the three hard audio constraints in AGENTS),
        // and there would be no sound ever after.
        // Only a teardown inside the audio probe window can be that watchdog: receivers that never run the tunnel
        // control session (WinPlay) also leave it incomplete, and after minutes of streaming the end is a normal
        // disconnect that should count as "tier works", not be logged as the watchdog.
        val livedSoFar = if (startedAt > 0) android.os.SystemClock.uptimeMillis() - startedAt else -1L
        currentClient?.let { client ->
            if (client.mib3LinkArmed && !client.mib3ControlSessionDone && livedSoFar in 0 until AUDIO_PROFILE_PROBE_MS) {
                appendLog(
                    "AUDIO: session torn down by the head unit before link negotiation completed (~10s watchdog pattern) - not recording an audio tier",
                )
                activeAudioProfile = null
                activeAudioStartedAt = 0L
                return
            }
        }
        val lived = livedSoFar
        // This round's memory goes stale quickly: the next connection picks a tier from the latest memory again
        activeAudioProfile = null
        activeAudioStartedAt = 0L
        if (startedAt == 0L || identity.isBlank()) return
        val ctx = appContext?.applicationContext
        if (lived >= AUDIO_PROFILE_PROBE_MS) {
            AudioProfileMemory.recordWorking(ctx, identity, profile.key)
            appendLog("AUDIO: tier ${profile.label} survived ${lived / 1000}s - recorded as usable on this head unit")
            return
        }
        val allKeys = audioCandidateKeys(identity)
        AudioProfileMemory.recordRejected(ctx, identity, profile.key, allKeys)
        val after = AudioProfileMemory.load(ctx, identity)
        appendLog(
            "AUDIO: ⚠ tier ${profile.label} was torn down by the head unit ${lived / 1000}s after streaming started - " +
                "judged incompatible on this head unit (added to its list). " +
                if (after.disabled) {
                    "All tiers incompatible: the next connection will push video only (no head-unit sound)."
                } else {
                    "The next \"start connection\" switches to the next tier automatically; you can also pick \"enable sound (compatibility tier)\" directly."
                },
        )
        uiHintSink?.invoke(
            if (after.disabled) {
                "本车机音频不兼容，下次连接将自动只推视频"
            } else {
                "该音频档位导致断开，已记录；重连会自动换档"
            },
        )
    }

    /** "Every tier key that may be used" on this head unit - to decide whether all have been tried. */
    private fun audioCandidateKeys(identity: String): Set<String> =
        runCatching {
            val client = currentClient ?: return@runCatching emptySet()
            val preferred = client.audioCandidates(preferCompat = audioPreferCompat).map { it.key }
            val alternate = client.audioCandidates(preferCompat = !audioPreferCompat).map { it.key }
            (preferred + alternate).toSet()
        }.getOrDefault(emptySet())

    /**
     * modesChanged resource re-assertion scheduling (driven by the MIB3 control-session-complete event).
     *
     * Timing verified by AADisplay r17 on a long Audi run: after auth+burst, first send at +1.5s, then re-assert at +4s/+12s/
     * +30s (absolute times). A declaration landing while 0xAA00/0xAA05 is in progress competes with the iAP POST
     * for the control channel and the Audi dies ~12s later (AADisplay's own comment) - so it can only be triggered by the burst-complete event,
     * not sent all at once at session setup. Finding from 082446: the second control session resets the head unit's resource
     * arbitration, so the earlier 200 OK declaration was already stale by the time the subscription cluster completed, and the head unit was waiting for a fresh one.
     */
    @Volatile
    private var modesReclaimEpoch = 0L

    private fun scheduleModesReclaim(client: AirPlayClient) {
        val epoch = ++modesReclaimEpoch
        Bg.run("modes-reclaim") {
            val start = android.os.SystemClock.uptimeMillis()
            for (atMs in longArrayOf(1_500, 4_000, 12_000, 30_000)) {
                while (true) {
                    if (epoch != modesReclaimEpoch || client.cancelled || connectCancelled) return@run
                    val remain = atMs - (android.os.SystemClock.uptimeMillis() - start)
                    if (remain <= 0) break
                    Thread.sleep(minOf(remain, 200L))
                }
                if (epoch != modesReclaimEpoch || client.cancelled || connectCancelled) return@run
                if (client.screenHandedToCar) continue
                if (!client.assertModes()) return@run
                appendLog("AIRPLAY: modesChanged resource re-assertion (+${atMs / 1000}s after the burst, AADisplay r17 timing)")
            }
        }
    }

    /**
     * Endpoint completion: absorb the address resolved by mDNS first, then decide whether to wait/fall back.
     *
     * Key (Audi 4851 real-car log, 2026-10-19): 0x4301 gave only
     * `fe80::3ca8:64ff:fe7c:293e` (IPv6 link-local), while `_airplay._tcp` resolved to
     * `10.174.189.1:7000` - the head unit actually works over IPv4. Do not skip mDNS just because 0x4301 carried an IPv6 address:
     * that would throw away the only verified IPv4 path. A "zero-wait" cache merge is done first,
     * and if an IPv4 turns up it is used per EndpointHosts priority (IPv4 -> IPv6); only on a cache miss does it wait on demand.
     */
    private fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val cached = endpointBrowser?.awaitEndpoint(0)
        if (cached != null) {
            effective = mergeBrowserEndpoint(effective, cached, source, needPort = effective.airPlayPort <= 0)
        }
        val needHost = !EndpointHosts.hasUsable(effective.ipAddresses)
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        phaseHint = UiStrings.text(R.string.phase_28)
        appendLog(
            ">>> [$source] 0x4301 endpoint incomplete (port=${effective.airPlayPort} ips=${effective.ipAddresses}), fetching the head unit's _airplay._tcp from mDNS...",
        )
        // Use the resident discoverer's cache (background discovery started at network bind, so it usually already hits by now).
        // Before, a discovery was started on demand with a hard 3x6s retry: wasted wait when head-unit mDNS is stable, a full 18s drag when it is not.
        // Subnet filtering (guarding against connecting to a macOS receiver on the same network) is done uniformly by the browser's accept.
        val info =
            endpointBrowser?.awaitEndpoint(ENDPOINT_RESOLVE_WAIT_MS) ?: run {
                // Fallback: in a hotspot scenario the head-unit IP = the gateway (.1 measured on Audi, but using the gateway is more general).
                // mDNS is unreliable on some networks/models (2026-10-11: head-unit mDNS came and went);
                // connect straight to gateway address + default port 7000, the AirPlay port being a fixed convention.
                val gateway =
                    if (AdapterMode.active) {
                        wifiConnector?.adapterFallbackHost(ctrlAdvertiser?.lastInvitePeer)?.host
                    } else {
                        wifiConnector?.gatewayAddress()
                    }
                if (gateway != null && EndpointHosts.candidates(listOf(gateway)).isNotEmpty()) {
                    appendLog(
                        ">>> [$source] mDNS found nothing, falling back to the gateway address $gateway:7000 (hotspot gateway = head unit)",
                    )
                    return effective.copy(
                        ipAddresses = listOf(gateway) + effective.ipAddresses,
                        airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                    )
                }
                appendLog(
                    ">>> [$source] mDNS did not find the head unit's AirPlay service (the head unit may not have started its wireless session yet)",
                )
                return effective
            }
        return mergeBrowserEndpoint(effective, info, source, needPort)
    }

    /**
     * Merge one mDNS resolution result into the endpoint: the address is prepended (EndpointHosts re-sorts by
     * IPv4 -> global IPv6 -> link-local IPv6, so order matters only within the same type).
     * Other fields are filled only when missing (the authoritative values from 0x4301 are not overwritten).
     */
    private fun mergeBrowserEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        info: android.net.nsd.NsdServiceInfo,
        source: String,
        needPort: Boolean,
    ): Iap2SenderSession.CarPlayEndpoint {
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            appendLog(">>> [$source] mDNS resolved head-unit address $address:${info.port}")
        }
        if (needPort && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        // TXT public-key fallback (for pair-verify; when 0x4301 lacks it, this is the only source)
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        return result
    }

    /**
     * Hot-update entry for a 0x4301 the head unit sends after Phase 1 (the CarPlay window opened late).
     *
     * Three cases:
     *  - streaming: a redundant endpoint, ignored (same guard as [onCarPlayEndpoint], to avoid tearing down a healthy session);
     *  - handoff/pairing in progress: hot-update the current AirPlayClient's endpoint, the pairing loop uses the new address next round;
     *    if the client is not created yet (still binding the network / waiting for the invitation) stash it and merge when the pairing thread starts;
     *  - the previous round failed (no session running): rerun a full handoff on the new endpoint - Audi MMI 9310
     *    measured 0x4301 arriving 28s after the 120s pairing window was exhausted; the old code dropped it as log noise,
     *    and the head unit, with nobody connecting in 15s, tore down the link, showing as "auto-disconnect after connecting".
     */
    private fun onLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (connectCancelled) {
            appendLog(">>> [$source] 0x4301 arrived late (connection cancelled) - ignored")
            return
        }
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 0x4301 arrived late (streaming) - redundant endpoint ignored")
            return
        }
        val client = currentClient
        if (sessionStarting.get()) {
            if (client != null && client.pairingInProgress) {
                // Merge rather than replace: 0x4301 may carry no IPv4 (Audi MMI 9310 measured: endpoint resolution relies on
                // mDNS/the gateway), and replacing would wipe the already-resolved address so the pairing loop could no longer connect.
                val merged = preferEndpoint(endpoint, client.currentEndpoint)
                val changed = client.updateEndpoint(merged, "late 0x4301")
                appendLog(
                    if (changed) {
                        ">>> [$source] 0x4301 arrived late (pairing in progress) - endpoint hot-updated, the next retry uses the new address"
                    } else {
                        ">>> [$source] 0x4301 arrived late (pairing in progress) - endpoint unchanged, ignored"
                    },
                )
            } else {
                pendingEndpointUpdate = endpoint
                appendLog(
                    ">>> [$source] 0x4301 arrived late (handoff has not created the pairing client yet) - stashed, used when pairing starts",
                )
            }
            return
        }
        // When the user has disconnected (BT components stopped), a late message must not revive the session
        if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) {
            appendLog(">>> [$source] 0x4301 arrived late (Bluetooth link retired) - ignored")
            return
        }
        appendLog(">>> [$source] 0x4301 arrived late (previous round already ended) - rerunning a connection round on the new endpoint")
        onCarPlayEndpoint(endpoint, "$source/late 0x4301")
    }

    /** Merge a late endpoint stashed during the handoff into the newly built client (the race window between building the client and receiving 0x4301). */
    private fun drainPendingEndpoint(client: AirPlayClient) {
        val late = pendingEndpointUpdate ?: return
        pendingEndpointUpdate = null
        if (client.updateEndpoint(preferEndpoint(late, client.currentEndpoint), "late 0x4301 during handoff")) {
            appendLog("AIRPLAY: the boundary-window 0x4301 endpoint was merged into the pairing client")
        }
    }

    /**
     * Watch task for the empty-endpoint wait (the head unit declared it will send 0x4301 but it has not come):
     * 1. poll the current WiFi every 5s - when the user has manually joined the head-unit hotspot, confirm in a dialog; on confirmation
     *    synthesize an endpoint from that SSID and rerun [onCarPlayEndpoint] (no port -> mDNS/gateway fallback);
     * 2. step aside when a late 0x4301 triggers a new handoff round (sessionStarting set again);
     * 3. with no progress after [PENDING_START_SESSION_WAIT_MS]: log it and tear down the keep-alive link,
     *    letting the BT reconnect loop take over (differs from the old "tear down after 8s" only in window length).
     */
    private fun watchForPendingStartSession(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        Bg.run("pending-start-session-watch") {
            var promptedSsid: String? = null
            val deadline = android.os.SystemClock.uptimeMillis() + PENDING_START_SESSION_WAIT_MS
            while (!connectCancelled && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(5_000)
                if (connectCancelled) return@run
                // A late 0x4301 has already triggered a new handoff round - the watch task steps aside.
                if (sessionStarting.get()) return@run
                val ssid = shellWifiStatus().second ?: continue
                // Ask only once per SSID (no more nagging after the user chose "keep waiting"); ask again on a different network.
                if (ssid.isEmpty() || promptedSsid == ssid) continue
                promptedSsid = ssid
                val choice =
                    askWifiJoinChoice(
                        DialogSpec(
                            title = "检测到已连接 $ssid",
                            message =
                                "车机尚未经蓝牙下发连接信息（0x4301）。是否把当前网络 \"$ssid\" 当车机热点，" +
                                    "直接尝试 mDNS/网关连接？（点「继续等待」则保持蓝牙保活等车机下发）",
                            positive = "按车机热点继续",
                            negative = "继续等待",
                        ),
                    )
                if (choice == true && !connectCancelled && !sessionStarting.get()) {
                    appendLog(
                        ">>> [$source] user confirmed the current network \"$ssid\" as the head-unit hotspot - rerunning the connection with a synthesized endpoint",
                    )
                    onCarPlayEndpoint(endpoint.copy(ssid = ssid), "$source/manual join")
                    return@run
                }
            }
            if (!connectCancelled && !sessionStarting.get()) {
                appendLog(
                    ">>> [$source] empty-endpoint wait timed out after ${PENDING_START_SESSION_WAIT_MS / 60_000} minutes - " +
                        "the head unit did not send 0x4301. Tearing down the keep-alive link so the reconnect loop takes over; please export the log and report it",
                )
                detachActiveBtSessions()
            }
        }
    }

    /**
     * Endpoint refresher for the pairing loop (non-blocking): reads only the browser's cached `_airplay._tcp`
     * and merges IP/port/public key etc. when present. Returns null = no new information (the pairing loop keeps the current endpoint).
     */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = endpointBrowser?.awaitEndpoint(0) ?: return null
        EndpointHosts.mdnsMismatch(endpoint.airPlayPort, endpoint.publicKey, info.port, txtValue(info, "pk"))?.let { reason ->
            appendLog(">>> [$source] ignoring mDNS endpoint ${info.host?.hostAddress}:${info.port} ($reason) - not the same receiver")
            return null
        }
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if (result.airPlayPort <= 0 && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        if (result == endpoint) return null
        appendLog(
            ">>> [$source] mDNS refreshed the endpoint during pairing -> " +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    private fun txtValue(
        info: android.net.nsd.NsdServiceInfo,
        key: String,
    ): String? =
        info.attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trimEnd { it.code == 0 }
            ?.takeIf { it.isNotEmpty() }

    /**
     * Late endpoint takes priority: the port/public key the head unit states explicitly outrank mDNS-synthesized values,
     * but the IP list is the union (0x4301 may carry no IPv4, as Audi MMI 9310 measured).
     */
    private fun preferEndpoint(
        late: Iap2SenderSession.CarPlayEndpoint,
        fallback: Iap2SenderSession.CarPlayEndpoint,
    ): Iap2SenderSession.CarPlayEndpoint =
        late.copy(
            airPlayPort = if (late.airPlayPort > 0) late.airPlayPort else fallback.airPlayPort,
            ipAddresses = (late.ipAddresses + fallback.ipAddresses).distinct(),
            publicKey = late.publicKey ?: fallback.publicKey,
            deviceIdentifier = late.deviceIdentifier ?: fallback.deviceIdentifier,
            sourceVersion = late.sourceVersion ?: fallback.sourceVersion,
            advertisedFeatures = late.advertisedFeatures ?: fallback.advertisedFeatures,
        )

    /**
     * Initial SETUP (with reconnect retry).
     *
     * Paired path: the pairing loop has just connected, so one SETUP is enough (a failure is a real failure, left to degradation/wrap-up).
     * Plaintext-session path: the head unit's AirPlay port may open only after "the user taps the head-unit icon",
     * and catplay likewise connects only after receiving an invitation; here, within [INITIAL_SETUP_WINDOW_MS], reconnect every 2s
     * and retry SETUP, so a port that opens 1-2 seconds late does not fail the whole round.
     */
    private fun runInitialSetup(
        client: AirPlayClient,
        plainSession: Boolean,
        noPairing: Boolean,
    ): AirPlayClient.SetupInfo? {
        val first = client.runSetup()
        if (first != null || !plainSession) return first
        val deadline = android.os.SystemClock.uptimeMillis() + INITIAL_SETUP_WINDOW_MS
        var attempt = 1
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            attempt++
            appendLog(
                "AIRPLAY: plaintext SETUP attempt ${attempt - 1} did not pass - reconnecting and retrying in 2s (the head unit's AirPlay port may not be open yet)",
            )
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return null
            }
            if (connectCancelled) return null
            client.close()
            if (!client.reconnect(2_000)) continue
            val retried = client.runSetup()
            if (retried != null) {
                appendLog("AIRPLAY: ✓ plaintext SETUP succeeded on attempt $attempt")
                return retried
            }
            // The port is open but SETUP is still rejected: the TXT judgment may be wrong, leave it to the caller's encryption-upgrade path
            if (!noPairing) return null
        }
        return null
    }

    /** BT session handoff point: after 0x5703/0x4301 both paths follow the same flow - bind WiFi -> AirPlay pairing -> SETUP -> streaming. */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        val context = appContext ?: return
        // Redundant-endpoint guard: the receiver controller (DiPlay measured) completes its earlier
        // RFCOMM reconnect and resends 0x4301 only after we have paired and started streaming. Restarting pairing then would retireSession a healthy session
        // (encoder stop race = root cause of the "auto-disconnect after connecting" loop). Endpoints while streaming are always ignored;
        // when the link really drops, streaming ends by itself (isRunning=false) and the next endpoint naturally resumes the reconnect.
        if (videoSender?.isRunning == true) {
            appendLog(
                ">>> [$source] session streaming - ignoring the redundant endpoint (a repeated 0x4301 from the receiver's periodic reconnect)",
            )
            return
        }
        if (!sessionStarting.compareAndSet(false, true)) {
            appendLog(">>> [$source] a session is already in progress, ignoring this endpoint (waiting for the previous round to finish)")
            return
        }
        // A new round clears the cancel flag at start: the previous round may have exited with it set because the "BT link dropped" (see
        // onSenderLinkClosed) - getting here means the BT components are still running (the user-cancel path
        // stops the components and is blocked by the guard above), and the flag belongs to the departed old round; not clearing it would hit this round by mistake.
        connectCancelled = false
        // New handoff round starts: drop the stashed late endpoint left by the previous (possibly mid-way failed) round,
        // otherwise it would overwrite the endpoint this session just resolved during the drain phase of this round.
        pendingEndpointUpdate = null
        // Empty endpoint = a new-protocol head unit declared it will send 0x4301 but it did not arrive in the window (Iap2SenderSession no longer tears down the link,
        // and the keep-alive pump routes a late 0x4301 back into this function via onLateEndpoint for the full flow).
        // sessionStarting is not taken here: a late endpoint takes the "previous round ended" branch; while waiting, poll the phone's
        // WiFi - once the user connects the head-unit hotspot by hand (the password is visible in the head-unit settings, Cadillac CT5 measured), after confirmation
        // an endpoint can be synthesized from that SSID and take the mDNS/gateway path.
        if (endpoint.ssid.isEmpty()) {
            appendLog(
                ">>> [$source] endpoint has no AP info (the head unit did not send 0x4301 within the window) - " +
                    "Bluetooth stays in keep-alive waiting for a late 0x4301; " +
                    "if the phone is found on a WiFi it will ask whether to continue with it as the head-unit hotspot",
            )
            phaseHint = UiStrings.text(R.string.phase_29)
            sessionStarting.set(false)
            watchForPendingStartSession(endpoint, source)
            return
        }
        appendLog(
            ">>> [$source] receiver hotspot obtained: SSID=${endpoint.ssid}; " +
                "password=${if (endpoint.passphrase.isEmpty()) "(empty)" else "obtained"}",
        )
        phaseHint = UiStrings.text(R.string.phase_30)
        ssidSink?.invoke(endpoint.ssid)
        // The credential cache must be written on both paths:
        // 0x5703 goes through onWifiCredentials, 0x4301 arrives only here.
        // Missing it makes the manual "head-unit hotspot -> connect" fallback falsely report
        // "hotspot password not obtained yet (needs 0x5703)"
        // - exactly how the 2026-10-22 Audi log sent the investigation in the wrong direction.
        if (endpoint.ssid.isNotBlank()) {
            lastCarWifiSsid = endpoint.ssid
            if (endpoint.passphrase.isNotEmpty()) lastWifiPsk = endpoint.passphrase
        }
        // Diagnostics (a must-read when troubleshooting old head units): whether the address is IPv4 or IPv6 only, and whether TXT features declare pairing capability.
        // Audi 4xxx's two kinds of "cannot connect" both originate here - the old logs had neither item at all.
        val addresses = endpoint.ipAddresses
        val hasIpv4 = addresses.any { EndpointHosts.isIpv4(it) }
        val pairingFlag = AirPlayFeatures.supportsPairing(endpoint.advertisedFeatures)
        appendLog(
            ">>> [$source] endpoint diagnostics: addresses=${addresses.ifEmpty { listOf("(none)") }}" +
                " (${if (hasIpv4) {
                    "IPv4"
                } else if (addresses.isEmpty()) {
                    "no address"
                } else {
                    "IPv6 only"
                }})" +
                " features=" + (endpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "unknown") +
                " HomeKit pairing=" +
                when (pairingFlag) {
                    true -> "declared supported"
                    false -> "not declared (treated as a pre-HomeKit plaintext session)"
                    null -> "unknown (pair on demand)"
                },
        )
        // Head-unit profile (signal convergence point): the mDNS-side signal has arrived - era verdict + a one-line explainable basis
        appendLog(
            ReceiverProfile.describe(
                ReceiverProfile.classify(
                    ReceiverProfile.Signals(
                        features = endpoint.advertisedFeatures,
                        srcvers = endpoint.sourceVersion,
                    ),
                ),
            ),
        )
        // Bind the network before resolving the endpoint: mDNS multicast follows the network of bindProcessToNetwork, and resolving first
        // would only return results from the old network (previously masked by resolveEndpointHost's internal retry, wasting several seconds).
        // After binding, three things run in parallel: re-register the ctrl advertisement for the new network, background-discover _airplay._tcp,
        // and wait for the head unit's ctrl invitation (the authoritative signal that the head unit has discovered us).
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!bound) {
                phaseHint = UiStrings.format(R.string.phase_31, endpoint.ssid)
                appendLog(
                    ">>> [$source] phone is not on that hotspot - join \"${endpoint.ssid}\" in Settings > WLAN, then disconnect and reconnect",
                )
                sessionStarting.set(false)
                return@proceed
            }
            // Network bind done: re-register the ctrl advertisement (landing on the head unit's network) and start background mDNS discovery.
            // Both happen while waiting for the invitation, taking no extra time.
            ctrlAdvertiser?.ensureForNetwork(endpoint.ssid, force = true, network = wifiConnector?.currentNetwork)
            endpointBrowser?.resetCache()
            endpointBrowser?.start()
            // A 0x4301 that carries a full address + port = the receiver has stated explicitly "connect here", no need to wait for the invitation
            // (DiPlay bench path: pair directly this round, saving the fixed wait).
            // IPv6 counts as an address too: Audi 4xxx's 0x4301 often gives only fe80:: (the old check looked only at IPv4,
            // treated such endpoints as "incomplete", waited in vain for the ctrl invitation and then fell back to the gateway).
            val endpointSelfContained =
                endpoint.airPlayPort > 0 && EndpointHosts.hasUsable(endpoint.ipAddresses)
            Bg.run("airplay-pairing") {
                try {
                    if (connectCancelled) {
                        appendLog("AIRPLAY: user cancelled - aborting pairing")
                        sessionStarting.set(false)
                        return@run
                    }
                    if (endpointSelfContained) {
                        appendLog(
                            "AIRPLAY: 0x4301 endpoint complete (${endpoint.ipAddresses.firstOrNull()}:${endpoint.airPlayPort}) - pairing directly, not waiting for the ctrl invitation",
                        )
                    } else {
                        // Wait for the ctrl invitation (catplay CarPlayCtrlServer: invitation = the head unit has discovered the phone and its
                        // AirPlay window is open, connect as soon as it arrives). Before, it was "sleep a fixed 1.6s and bet it has arrived",
                        // wasted wait when fast and not enough when slow; now event-driven.
                        awaitCtrlInvite(endpoint.ssid, CTRL_INVITE_WAIT_MS)
                    }
                    // Endpoint resolution comes after the invitation: the head unit's `_airplay._tcp` often goes online only once invited,
                    // and this step takes up to 3s - it must run on a background thread and not hold the network callback thread.
                    var effectiveEndpoint = resolveEndpointHost(endpoint, source)
                    // A 0x4301 the head unit sends during the handoff start-up (more authoritative than the mDNS synthesis) is merged here.
                    pendingEndpointUpdate?.let { late ->
                        pendingEndpointUpdate = null
                        effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)
                        appendLog(
                            "AIRPLAY: merged the late 0x4301 endpoint from the handoff period -> " +
                                "${effectiveEndpoint.ipAddresses.firstOrNull()}:${effectiveEndpoint.airPlayPort}",
                        )
                    }
                    if (connectCancelled) {
                        appendLog("AIRPLAY: user cancelled - aborting pairing")
                        sessionStarting.set(false)
                        return@run
                    }

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            // Link generation: the BT-side 0x1D01 profile has decided it (CLASSIC_MIB3/EVENT_LINK),
                            // and the MIB3 arming in the AirPlay phase dispatches the marker strategy from it (generation-based refactor, 2026-10-23)
                            linkGeneration = senderLinkGeneration(),
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
                            retryTick = { attempt ->
                                // During pairing retries the head unit may still not have detected ctrl (Bonjour missed the first announcement):
                                // periodically force a rebroadcast and write the current mDNS state to the log - otherwise only
                                // the "port not open" retry spam is visible, and one cannot tell whether the head unit never invited or the
                                // address is wrong (the 2026-10-19 Audi 0.1.7 log lacked exactly these two pieces of information).
                                ctrlAdvertiser?.ensureForNetwork(
                                    endpoint.ssid,
                                    force = true,
                                    network = wifiConnector?.currentNetwork,
                                )
                                val invites = ctrlAdvertiser?.inviteCount ?: 0
                                val found = endpointBrowser?.awaitEndpoint(0)
                                appendLog(
                                    "AIRPLAY: retry #$attempt - ctrl re-announced; " +
                                        "invitations from head unit=$invites, mDNS _airplay._tcp=" +
                                        (found?.let { "${it.host?.hostAddress}:${it.port}" } ?: "not found"),
                                )
                            },
                        ).also { created ->
                            // Head-unit media keys (iAP2 0x6802 path): previous/next track on the steering wheel / panel.
                            // Attached here rather than at the call site, so it is also hooked up when a reconnect rebuilds the client.
                            created.onMediaKey = { keyCode ->
                                dispatchMediaKey(keyCode)
                                appendLog("EVENT: head-unit media key (iAP2 0x6802) -> keyCode=$keyCode injected")
                            }
                            // Knob / four-way stick (iAP2 0x6802 path): inject DPAD into the virtual display
                            created.onNavKey = { keyCode -> injectNavigationKey(keyCode) }
                            // MIB3 control session complete -> modesChanged resource re-assertion burst (finding from 082446:
                            // if declared only once early in the session, the second control session resets the head unit's resource arbitration,
                            // and the head unit stays healthy throughout yet never switches to the CarPlay picture).
                            created.onTunnelControlSessionComplete = {
                                scheduleModesReclaim(created)
                            }
                            // Wireless CarPlay adapter: re-prime the decoder when the box brings
                            // CarPlay back (it tears its decoder down while native owns the screen).
                            created.onRequestUI = { videoSender?.reprime("requestUI") }
                            // After disableBluetooth the box moves iAP2 to Wi-Fi; release RFCOMM once
                            // the tunnel is up, or 10 s after disableBluetooth in case it waits for us.
                            created.onBluetoothReleasable = { reason ->
                                if (AdapterMode.active) {
                                    appendLog("ADAPTER: releasing Bluetooth RFCOMM ($reason)")
                                    bluetoothServer?.detachCurrentSession()
                                }
                            }
                        }
                    // The "force app landscape" override writes are queued at pairing start: `am compat enable` kills
                    // the listed apps at once, so it must finish before attach launches any app. The pairing phase has tens of seconds
                    // of idle time in which the writes drain naturally, and attach only has to drain (~0ms) - running it synchronously in attach
                    // (1.6s, 3.2s when serialized) used to eat into the ~10s teardown budget of "RECORD -> screen-port keepAlive"
                    // (the keepAlive frame starts only after attach returns; finding of 2026-10-23).
                    orientationRestorePending.set(true)
                    appContext?.let { ctx -> AppOrientationCompat.applyAllAsync(ctx, ::appendLog) }
                    retireSession()
                    // Session quiet mode (2026-10-10): from the handoff point on, pause outbound connection probes toward other receivers
                    // (typical case: stop noisily probing the Audi while connected to the DiPlay bench). Lifted when the session ends/disconnects.
                    BtSessionGate.sessionActive = true
                    // Phase 1: keep BT in place and pair + SETUP directly (the protocol-correct form)
                    phaseHint = UiStrings.text(R.string.phase_32)
                    var client = newClient()
                    currentClient = client
                    drainPendingEndpoint(client)
                    // Pre-HomeKit old head units (CarPlay 210.81 era, Audi 4xxx firmware) do not declare
                    // HK_PAIRING_AND_ENCRYPT in `_airplay._tcp`: they accept only a plaintext
                    // SETUP (et=NONE) + AES-CTR screen stream, and pair-setup always fails.
                    // This is catplay's receiver class 1; the old code spun here for the whole 120s pairing window,
                    // which the user saw as "cannot connect".
                    val noPairing =
                        AirPlayFeatures.supportsPairing(client.currentEndpoint.advertisedFeatures) == false
                    var keys: PairVerifyClient.ControlKeys? =
                        if (noPairing) {
                            client.skipPairing("TXT features do not declare HK_PAIRING_AND_ENCRYPT")
                            client.reconnect()
                            null
                        } else {
                            client.runPairing()
                        }
                    if (connectCancelled) {
                        retireSession()
                        appendLog("AIRPLAY: user cancelled - aborting pairing")
                        sessionStarting.set(false)
                        return@run
                    }
                    // Fallback path after pairing fails (decided by a pure function; the on-device capture scenarios are pinned by PairingFallbackTest)
                    val fallbackAfterFirstRound =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                        )
                    if (fallbackAfterFirstRound.useBtRetireHack) {
                        // Phase 2 (DiPlay bench only): reproduce the "BT control ended" state and try again.
                        // A real car (Audi) must never drop BT - on real wireless CarPlay the BT control channel stays alive throughout,
                        // and dropping it makes the head unit tear down the whole AirPlay window (measured on a real car).
                        // Also disabled on pairingForbidden (a 403 in any segment, including verify/auth - finding from the 2026-10-21 on-device
                        // sysdiagnose: a 403 on first connect is an inherent head-unit state, it re-invites by itself 18-20s later and
                        // the second attempt succeeds): dropping BT makes the head unit rebuild its whole stack and miss exactly
                        // the upcoming re-invitation window.
                        // Gate: use this hack only if this round really connected to the receiver's RTSP (attemptedPairing);
                        // when the endpoint is unresolved (no IP) / the port is closed, dropping BT is never worthwhile (hit on 2026-10-11:
                        // an mDNS failure also triggered detach and needlessly tore down the head-unit window).
                        detachActiveBtSessions()
                        client.release()
                        client = newClient()
                        currentClient = client
                        drainPendingEndpoint(client)
                        keys = client.runPairing()
                        if (connectCancelled) {
                            retireSession()
                            appendLog("AIRPLAY: user cancelled - aborting pairing")
                            sessionStarting.set(false)
                            return@run
                        }
                    }
                    // With keys == null there is still a legitimate path: catplay's et=NONE plaintext session.
                    //  - the receiver explicitly does not support pairing (pre-HomeKit / TXT lacks HK_PAIRING_AND_ENCRYPT);
                    //  - pairing failed outright but RTSP did connect (some cars accept only plaintext sessions,
                    //    rejecting pair-setup/pair-verify entirely - catplay does "try plaintext first, pair only on failure",
                    //    we do the reverse: pair first (preserving DiPlay/known head units) and fall back to plaintext on failure).
                    //  - Exception: 403 evidence (pairingForbidden) (on-device capture finding 2026-10-21, decision
                    //    in pairingFallback): the head unit is waiting to become ready and a re-invite ~20s later succeeds - plaintext would certainly be
                    //    rejected and burn the re-invite window, so no downgrade.
                    // The hack segment may have rerun and obtained keys: the decision follows the state after the rerun.
                    val plainSession =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                        ).usePlainSession
                    if (plainSession) {
                        phaseHint = UiStrings.text(R.string.phase_33)
                        appendLog(
                            "AIRPLAY: pairing unavailable (" +
                                (if (client.pairingUnsupported) "receiver does not support it" else "pairing failed") + ")" +
                                " - switching to an et=NONE plaintext session + AES-CTR screen stream (catplay receiver class 1/2)",
                        )
                        // When the pairing loop ends the socket is closed / the state machine unusable: reconnect once before SETUP.
                        // A failure is not treated as fatal: the head unit's AirPlay port may not be open yet, and the retry below follows the window.
                        if (!client.reconnect()) {
                            appendLog(
                                "AIRPLAY: first reconnect of the plaintext session did not succeed - continuing to retry SETUP within the window",
                            )
                        }
                    }
                    // keys is a mutable variable captured in a lambda: Kotlin cannot smart-cast it, so freeze it into an immutable value first.
                    val pairedKeys = keys
                    if (pairedKeys != null || plainSession) {
                        phaseHint = if (pairedKeys != null) UiStrings.text(R.string.phase_paired) else UiStrings.text(R.string.phase_legacy)
                        if (pairedKeys != null) {
                            appendLog(
                                "AIRPLAY: ✓ pairing complete writeKey=" +
                                    pairedKeys.phoneWriteKey.joinToString("") { "%02x".format(it) }.take(16) + "… " +
                                    "readKey=" + pairedKeys.phoneReadKey.joinToString("") { "%02x".format(it) }.take(16) + "…",
                            )
                        }
                        // Paired -> shared key; plaintext session -> null (stream encryption degrades to the AES-CTR zero key)
                        var sessionKey = pairedKeys?.sharedSecret
                        var setup = runInitialSetup(client, plainSession, noPairing)
                        if (setup == null && plainSession && noPairing) {
                            // catplay's "encryption upgrade" path: done only when we deliberately skipped pairing (TXT did not declare
                            // HK_PAIRING_AND_ENCRYPT) and the plaintext SETUP still failed.
                            // The reverse (pairing was tried first and failed) gains nothing from one more attempt and would only wait another 30s.
                            val upgraded = client.retryWithPairing(PAIRING_UPGRADE_WINDOW_MS)
                            if (upgraded != null) {
                                sessionKey = upgraded.sharedSecret
                                setup = client.runSetup()
                            }
                        }
                        if (setup != null) {
                            // Record the session credentials: used to start audio dynamically when the capture grant arrives after the connection.
                            // Use client.currentEndpoint rather than effectiveEndpoint: when mDNS refreshed the endpoint during pairing,
                            // what actually connected is the address in the client (2026-10-18:
                            // the fixed-width IP field of 0x4301 carried NULs while mDNS gave a clean address; using the old value
                            // would make the video port/keepAlive use the dirty address -> all fail to resolve).
                            currentSharedSecret = sessionKey
                            currentAudioHost =
                                client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            // Head-unit microphone auto-switch (when an app is recording): /info was cached during the handshake, so availability can be judged here
                            io.github.ezn24.cardeck.neo.audio.CarMicController.attach(
                                client = client,
                                sharedSecret = sessionKey,
                                ownPackage = context.packageName,
                                mediaStreamType = { activeAudioProfile?.streamType },
                            )
                            client.connectEventChannel(sessionKey, setup) { command ->
                                when (command["type"]) {
                                    "hidSendReport" -> {
                                        val uuid = command["uuid"]?.toString()
                                        val report = command["hidReport"] as? ByteArray
                                        // Head-unit media keys (Consumer Control) go through the hidSendReport path;
                                        // the decision follows the device descriptor (not uuid, not hard-coded per head-unit model):
                                        // only a device whose descriptor declares the Consumer page (0x0C) counts as media keys, and the report byte is
                                        // a Usage array index (HID Array semantics). The descriptors of touch/knob/telephony devices
                                        // are the Digitizer/GenericDesktop/Telephony pages and never go through here -
                                        // DiPlay's knob (4B) and telephony (1B) report lengths overlap with media keys,
                                        // so judging by length alone misfires (e.g. telephony usage 0xB3 would wrongly send "fast forward").
                                        val descriptor = uuid?.let { setup.hidDescriptors[it] }
                                        val keyMap =
                                            descriptor?.let(ConsumerKeys::parseDescriptor)
                                        val rawValue =
                                            report
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { it[0].toInt() and 0xFF }
                                        val consumerUsage =
                                            when {
                                                // With a descriptor: only Consumer devices are parsed (non-Consumer -> null)
                                                descriptor != null -> keyMap?.let { rawValue?.let(it::usageFor) }
                                                // Descriptor missing (unknown head unit): conservative fallback - only 1-byte reports,
                                                // the value read directly as a Usage ID
                                                report != null && report.size == 1 -> rawValue?.takeIf { it != 0 }
                                                else -> null
                                            }
                                        // Telephony device (page 0x0B, Array like the media keys): answer / end / mute.
                                        // Its 1-byte reports overlap media-key lengths, so it is told apart by descriptor only.
                                        val phoneKeys =
                                            if (consumerUsage == null) {
                                                descriptor?.let {
                                                    ConsumerKeys.parseDescriptor(it, ConsumerKeys.USAGE_PAGE_TELEPHONY)
                                                }
                                            } else {
                                                null
                                            }
                                        if (phoneKeys != null) {
                                            val usage = rawValue?.let(phoneKeys::usageFor)
                                            if (usage != null) onPhoneKey(context, usage)
                                            return@connectEventChannel
                                        }
                                        // Knob / four-way stick (Generic Desktop page Wheel/Hat):
                                        // only non-Consumer devices come here; outputs DPAD_* injected into the virtual display.
                                        val navKeys =
                                            if (consumerUsage == null && report != null) {
                                                if (HidNavigation.rawLogEnabled) {
                                                    appendLog(
                                                        "HID-RAW: uuid=$uuid bytes=" +
                                                            report.joinToString(" ") { "%02X".format(it) } +
                                                            " desc=${descriptor?.size ?: 0}B",
                                                    )
                                                }
                                                HidNavigation.handle(uuid.orEmpty(), descriptor, report)
                                            } else {
                                                emptyList()
                                            }
                                        val contacts =
                                            if (consumerUsage == null && navKeys.isEmpty() && report != null) {
                                                // Touch format is decided by the device descriptor (Digitizer page + single-finger/multi-slot),
                                                // and falls back to report length when no descriptor is available - not dependent on the head-unit model or uuid
                                                val touchFormat =
                                                    descriptor?.let(TouchReports::formatFromDescriptor)
                                                TouchReports.parse(report, touchFormat)
                                            } else {
                                                null
                                            }
                                        if (navKeys.isNotEmpty()) {
                                            navKeys.forEach(::injectNavigationKey)
                                            appendLog(
                                                "EVENT: knob/navigation key " +
                                                    navKeys.joinToString("+") { HidNavigation.nameOf(it) } +
                                                    " (uuid=$uuid)",
                                            )
                                        } else if (consumerUsage != null) {
                                            val keyCode = ConsumerKeys.keyCodeFor(consumerUsage)
                                            if (keyCode != 0) {
                                                dispatchMediaKey(keyCode)
                                                appendLog(
                                                    "EVENT: head-unit media key ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        " (uuid=$uuid usage=0x%02x → keyCode=$keyCode)"
                                                            .format(consumerUsage),
                                                )
                                            } else {
                                                appendLog(
                                                    "EVENT: head-unit media key ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        " (uuid=$uuid usage=0x%02x) has no matching Android key, ignored"
                                                            .format(consumerUsage),
                                                )
                                            }
                                        } else if (keyMap != null && report != null) {
                                            // "No key" / release report of a Consumer device (value 0 or index out of range):
                                            // counted silently, so release events do not flood the log
                                            consumerReleaseCount++
                                        } else if (contacts != null) {
                                            if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                appendLog(
                                                    "EVENT: touch " +
                                                        contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                )
                                            }
                                            touchLogCount++
                                            contacts.forEach {
                                                TouchRouter.onContact(
                                                    context.applicationContext,
                                                    it,
                                                )
                                            }
                                        } else if (report != null) {
                                            appendLog(
                                                "EVENT: other HID report uuid=$uuid bytes=${report.size}" +
                                                    " hex=${report.joinToString(" ") { "%02x".format(it) }}",
                                            )
                                        }
                                    }

                                    "showUI", "updateViewArea" -> {
                                        // The receiver chooses what its alternate (cluster) screen shows (ChairPlay AltScreen)
                                        val params = command["params"] as? Map<*, *>
                                        val uuid = params?.get("uuid")?.toString()
                                        if (uuid != null && uuid == altScreenUuid) {
                                            val output = io.github.ezn24.cardeck.neo.render.AltScreenOutput
                                            if (command["type"] == "showUI") {
                                                output.showUi(params["url"]?.toString().orEmpty())
                                            } else {
                                                (params["viewAreaIndex"] as? Number)?.toInt()?.let(output::updateViewArea)
                                            }
                                        } else {
                                            appendLog("EVENT: command type=${command["type"]} params=$params")
                                        }
                                    }

                                    "requestSiri" -> {
                                        // Head-unit voice key (steering wheel / on-screen mic key): siriAction 2 = press, 3 = release
                                        // (DiPlay's head-unit side sends 2+3 back to back). Only the press counts: open the head-unit microphone -> wake the assistant.
                                        val action =
                                            ((command["params"] as? Map<*, *>)?.get("siriAction") as? Number)?.toInt()
                                        appendLog("EVENT: head-unit voice key requestSiri siriAction=$action")
                                        if (action == null || action == 2) {
                                            Bg.run("car-voice-button") {
                                                io.github.ezn24.cardeck.neo.audio.CarMicController
                                                    .onVoiceButton()
                                            }
                                        }
                                    }

                                    "iAPSendMessage" -> {
                                        // iAP2 virtual-link bytes on the event channel (the head unit sends the marker first to open).
                                        // Fed to the link engine: handshake -> EA session -> touch HID.
                                        val params = command["params"] as? Map<*, *>
                                        val data = params?.get("data") as? ByteArray
                                        if (data == null) {
                                            appendLog("EVENT: iAPSendMessage has no data (params=$params)")
                                        } else {
                                            iapEventMessageCount++
                                            client.onIapEventBytes(data)
                                        }
                                    }

                                    "forceKeyFrame" -> {
                                        // When the head unit cannot decode the picture it asks for an I-frame. This must be answered at once:
                                        // waiting for the next periodic I-frame (3s, see VideoSender.KEYFRAME_INTERVAL_S) leaves the head unit stuck waiting for a frame.
                                        val forced = videoSender?.requestKeyFrame() == true
                                        appendLog(
                                            "EVENT: head unit requested a key frame (forceKeyFrame) → " +
                                                if (forced) "I-frame forced" else "encoder not ready, waiting for the periodic I-frame",
                                        )
                                    }

                                    "changeModes" -> {
                                        // The head unit requests resource arbitration (e.g. the user tapped the phone on the MMI):
                                        // reply with modesChanged declaring that the phone holds the main screen/main audio
                                        appendLog(
                                            "EVENT: head unit requested changeModes -> replying modesChanged to take over the resources",
                                        )
                                        client.takeScreenBack("car changeModes")
                                    }

                                    else ->
                                        appendLog(
                                            // An unrecognized command must carry its parameters: what a head-unit private command (e.g. requestUI)
                                            // actually wants is all in params - logging only the type is as good as logging nothing.
                                            "EVENT: command type=${command["type"]} " +
                                                command.entries
                                                    .filter { it.key?.toString() != "type" }
                                                    .joinToString(" ") { (key, value) ->
                                                        "$key=" +
                                                            when (value) {
                                                                is ByteArray -> value.joinToString("") { "%02x".format(it) }
                                                                else -> value.toString().take(240)
                                                            }
                                                    }.take(600),
                                        )
                                }
                            }
                            // Resource declaration (must): a real car answers a screen-stream SETUP with undeclared resources by 400 outright
                            client.assertModes()
                            // HID input mode arming: /info hidDevices (Audi strings "0"/"1", touch = "1").
                            // display features=0x08 (high-fidelity touch) is declared; without this step the head unit sends no hidSendReport.
                            setup.hidDeviceUuids.forEach { uuid ->
                                client.sendHidSetInputMode(uuid, mode = 0)
                            }
                            val screen = client.runScreenStream(sessionKey, setup.displayUuid)
                            if (screen != null) {
                                // Take the address the client actually connected through (it may have been refreshed by mDNS during pairing):
                                // both the video data port and keepAlive build their sockets from it; an IPv6 endpoint also counts as a
                                // usable address (EndpointHosts sorts uniformly: IPv4 -> global IPv6 -> link-local IPv6).
                                val host = client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                                // Heartbeat double insurance (missing either one gets the session torn down by the head unit's ~10s watchdog, measured 2026-10-11):
                                //   keepAlive UDP ping (the keepAlivePort returned by the head unit's SETUP, 10s period)
                                //   + RTSP POST /feedback (1s period, catplay FEEDBACK_INTERVAL)
                                if (host != null && setup.keepAlivePort != null) {
                                    client.startKeepAlive(host, setup.keepAlivePort!!)
                                }
                                client.startFeedbackLoop()
                                // iAP tunnel (type-130): iAP2 EA data from head unit to phone (the carrier of touch HID).
                                // A setup failure does not block the session (touch unavailable but video continues).
                                val tunnel = client.runIapTunnel(sessionKey)
                                tunnel?.onIapMessage = { frame ->
                                    // Touch evidence collection: record everything first, hook up TouchRouter once the format is confirmed
                                    if (frame.messageId != 0x0000) {
                                        val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                                        appendLog(
                                            "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                "(${Iap2MessageIds.nameOf(
                                                    frame.messageId,
                                                )}) ${frame.body.size}B" +
                                                if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                        )
                                    }
                                }
                                // Key timing (Audi finding 2026-10-17): after the screen SETUP the dataPort must be connected as soon as possible
                                // - the head unit has a ~2s watchdog. The audio SETUP measured ~2.1s,
                                // and if placed first, a late port connection makes the head unit reset the control channel
                                // (log signature: `audio SETUP round trip 2091ms` and `EVENT read ended
                                // - Connection reset` on the same millisecond). So build VideoSender and
                                // openDataPort() first, then negotiate audio, and finally RECORD + start the encoder.
                                var pendingVideoW = 0
                                var pendingVideoH = 0
                                var pendingVideoDensity = 0
                                var dataPortOk = true
                                if (host != null) {
                                    // "Display" preference: the stream canvas / virtual display is generated as a percentage of the head-unit resolution.
                                    // Effective only at session setup - the encoder and virtual display size are fixed at this moment;
                                    // the density scales in step, so the logical layout (dp) is identical to 100%.
                                    val scalePct =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .scalePercent(it)
                                            } ?: 100
                                    val scale = scalePct / 100f
                                    // After scaling, round down to an even number (H.264 requirement): the virtual display / compositor / encoder
                                    // must all share one size, so it is aligned at the source (VideoSender also has a fallback)
                                    val streamW =
                                        ((setup.displayWidth * scale).toInt().coerceAtLeast(320) / 2) * 2
                                    val streamH =
                                        ((setup.displayHeight * scale).toInt().coerceAtLeast(180) / 2) * 2
                                    val fps =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .fps(it)
                                            } ?: 30
                                    activeScalePercent = scalePct
                                    activeTargetFps = fps
                                    // Head-unit touch is in the advertised view coordinate system (e.g. 1160x540) while the stream is scaled
                                    // proportionally (e.g. 1450x674) - it must be mapped before injection, otherwise taps drift
                                    TouchRouter
                                        .setCoordinateScale(
                                            setup.displayWidth,
                                            setup.displayHeight,
                                            streamW,
                                            streamH,
                                        )
                                    appendLog(
                                        "DISPLAY: stream ${setup.displayWidth}x${setup.displayHeight} → " +
                                            "${streamW}x$streamH ($scalePct%) @${fps}fps",
                                    )
                                    pendingVideoW = streamW
                                    pendingVideoH = streamH
                                    // The dock screen density scales with the canvas short side (design baseline 1160x540@420dpi,
                                    // see design/launcher-proto.html: dock 48dp=126px): on high-resolution
                                    // head units (bench 2320x1080 = 2x) the dock text/icons scale up in the same proportion as on the Audi
                                    // - it used to be fixed at 420dpi, so the dock looked half-size relative to the canvas (2026-10-17).
                                    // Capped at 4x (4K-class canvas).
                                    pendingVideoDensity =
                                        (420f * minOf(streamW, streamH) / 540f)
                                            .toInt()
                                            .coerceIn(120, 1680)
                                    videoSender =
                                        VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            frameCipher = screen.cipher,
                                            width = streamW,
                                            height = streamH,
                                            targetFps = fps,
                                            log = { line -> appendLog(line) },
                                            keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                            clarityFirst = appContext?.let { DisplayPrefs.clarityFirst(it) } ?: false,
                                            scalePercent = scalePct,
                                            hevc = setup.hevc && appContext?.let { DisplayPrefs.hevc(it) } == true,
                                            onEnded = { byPeer ->
                                                // The session has truly ended: lift the session quiet mode (the client resumes probing/reconnecting)
                                                appendLog(">>> CarPlay session ended (streaming stopped) - resuming Bluetooth probing")
                                                stopAltScreen()
                                                BtSessionGate.sessionActive = false
                                                appContext?.let {
                                                    io.github.ezn24.cardeck.neo.util.SessionHooks
                                                        .onDisconnected(it)
                                                }
                                                // The head-unit microphone injection must be removed with the session, otherwise the phone microphone stays occupied
                                                io.github.ezn24.cardeck.neo.audio.CarMicController
                                                    .detach("session ended")
                                                // Ending by the head unit dropping (no user disconnect action): the dim cover / keep-awake must be released together.
                                                // This path does not go through retireSession (VirtualLauncher is still attached),
                                                // and missing it produces "the session dropped long ago, then 45s later the phone screen suddenly goes black"
                                                // (user-observed 2026-10-19).
                                                ScreenDimmer.setSessionActive(false)
                                                // Head-unit side disconnected: the person is out of the car, let the phone go to sleep directly (panel stays dark)
                                                // instead of lighting it first and then waiting for the 60s timeout
                                                restorePhoneScreen(sleepIfCarSide = true)
                                                // Restore the landscape overrides the same way. This runs on the streaming wrap-up thread, not on the connect
                                                // critical path, but goes through the async entry anyway to keep the "serial, non-reentrant" semantics.
                                                restoreOrientationOnce()
                                                VirtualLauncher.setOemBlock(null)
                                                // The system network saved by the shell fallback must be restored here too: the head unit tearing down the link is the
                                                // most common way of leaving (ignition off) and the user may not tap "Disconnect" - hooking only
                                                // the disconnect end would always leave it behind (reviewed 2026-10-23). If the next round
                                                // takes the deep fallback it registers again, so the semantics are self-consistent.
                                                cleanupShellFallbackNetwork()
                                                // Audio teardown attribution: audio was started but the link was torn down soon after -> record that tier
                                                // as incompatible, and the next connection switches tier automatically (see attributeAudioTeardown).
                                                attributeAudioTeardown()
                                                // The head unit disconnected on its own: by default do not wait for it to reconnect, shut everything down (same as tapping "Disconnect") -
                                                // otherwise the phone keeps the Bluetooth listener/hotspot waiting (user request 2026-11-18, the switch is on the connection page).
                                                // The local side stopping the stream itself (starting a new round, etc.) does not count.
                                                val ctx = appContext
                                                if (byPeer && ctx != null && !DisplayPrefs.waitForReconnect(ctx)) {
                                                    appendLog(
                                                        "SESSION: head unit disconnected - \"wait for reconnect\" is off, shutting down Bluetooth/hotspot",
                                                    )
                                                    android.os
                                                        .Handler(android.os.Looper.getMainLooper())
                                                        .post { if (isSessionRunning()) disconnect(ctx) }
                                                }
                                            },
                                        )
                                    // Connect the port first (head-unit watchdog), the encoder starts after RECORD
                                    dataPortOk = openDataPortWithRetry(videoSender)
                                }
                                if (!dataPortOk) {
                                    failDataPort(client)
                                } else {
                                    // RECORD first to activate the session and start video, then negotiate audio (Audi finding 2026-10-17):
                                    // after the screen-stream SETUP the head unit has a ~2s watchdog and tears down the whole session if no RECORD/data stream arrives.
                                    // The audio SETUP itself measured ~2.05-2.16s (head-unit audio path
                                    // initialization), so placing it before RECORD always times out - the log shows the reset and the audio SETUP
                                    // return on the same millisecond, reproduced on all 5 measured runs. So the order is now:
                                    //   screen SETUP -> connect data port -> RECORD -> start encoder (frames out) -> audio SETUP
                                    // An audio SETUP failure only loses sound, the video session stays.
                                    client.record()
                                    if (host != null) {
                                        val videoW = pendingVideoW
                                        val videoH = pendingVideoH
                                        val videoDensity = pendingVideoDensity
                                        videoSender?.start { surface ->
                                            // The only mode: hidden display (dual-screen launcher: dock + app screen)
                                            val source =
                                                HiddenDisplaySource(
                                                    context.applicationContext,
                                                    log = { line -> appendLog(line) },
                                                )
                                            currentHiddenSource = source
                                            val ok =
                                                source.attach(
                                                    surface,
                                                    videoW,
                                                    videoH,
                                                    videoDensity,
                                                )
                                            appendLog(
                                                "MODE: hidden display ${if (ok) "started (Amap car edition preferred) display=${source.displayId}" else "failed to start (is Shizuku ready?)"}",
                                            )
                                            // Dim cover / keep-awake take effect only once projection has really started (see ScreenDimmer.sessionActive):
                                            // at app launch only the touch probe is attached, avoiding "the session dropped long ago yet the phone dims a while later".
                                            if (ok) {
                                                io.github.ezn24.cardeck.neo.util.SessionHooks
                                                    .onConnected(context)
                                                ScreenDimmer.setSessionActive(true)
                                                // Virtual displays are built -> the "real screen-off on connect" switch decides whether to cut hardware power
                                                if (DisplayPrefs.screenOffOnConnect(context)) {
                                                    enterPhoneScreenOff()
                                                } else {
                                                    appendLog("SCREEN: \"real screen-off on connect\" is off - dim cover only this time")
                                                }
                                            }
                                        }
                                    }
                                    // Audio channel (optional): SETUP type=100 -> UDP RTP push.
                                    // Negotiated after the session is activated (RECORD done); a failure does not affect the main video link.
                                    val audioStarted = startAudioChannel(context, client, sessionKey, host)
                                    // Alternate (cluster) screen: only when /info lists a type-111 display and the SETUP
                                    // reply enabled "altScreen" (ChairPlay AltScreen). Its RECORD rides on the audio one below.
                                    val altStarted = host != null && startAltScreen(client, sessionKey, setup, host)
                                    if (audioStarted || altStarted) {
                                        // The stream joins after RECORD: AirPlay semantics require another RECORD
                                        // to bring the new stream into the already-activated session. Without this step the head unit answers the SETUP with 200
                                        // and gives a dataPort but never actually plays (Audi measured 2026-10-17:
                                        // audio packets keep flowing and the capture is non-zero, yet the head unit is silent).
                                        appendLog(
                                            "AIRPLAY: stream(s) joined after RECORD (audio=$audioStarted alt=$altStarted) - " +
                                                "resending RECORD to activate them",
                                        )
                                        client.record()
                                    }
                                }
                            }
                        } else {
                            // The session-level SETUP was rejected (typical: the receiver filters by device and answers 403, see the
                            // deviceID note in AirPlayClient.runSetup). Close the control channel actively:
                            // otherwise the receiver waits ~45s for a video timeout before tearing down the BT link, and the user only sees
                            // "auto-disconnect after connecting" with no way to tell the session setup failed.
                            phaseHint = UiStrings.text(R.string.phase_34)
                            appendLog("AIRPLAY: session setup failed - closing the control channel actively, this round ends")
                            uiHintSink?.invoke("接收端拒绝了会话建立（SETUP）——请查看日志中的 deviceID 提示")
                            runCatching { client.close() }
                        }
                    }
                } catch (error: Throwable) {
                    // No exception on the pairing thread may leave a stuck "a session is already in progress" state
                    appendLog("AIRPLAY: pairing thread exception - ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    sessionStarting.set(false)
                    // When pairing did not establish streaming (fail/exception/early exit on start failure), lift the session quiet mode,
                    // otherwise BT-C stays outside the gate forever and the real-car path is dead.
                    // While streaming is active it stays closed, and VideoSender.onEnded (streaming stopped) lifts it.
                    if (videoSender?.isRunning != true) {
                        BtSessionGate.sessionActive = false
                        // Wrap-up race: if a late 0x4301 arrives exactly in the window of "pairing already failed, mutex not yet released",
                        // it is stashed with nobody to consume it. Re-post it here - the head unit tears down the link if nobody connects in 15s,
                        // and missing this re-post means the whole round fails (analysis 2026-10-18).
                        val pending = pendingEndpointUpdate
                        if (pending != null && !connectCancelled) {
                            pendingEndpointUpdate = null
                            appendLog(">>> found a stashed late 0x4301 at wrap-up - rerunning a round on the new endpoint immediately")
                            Bg.run("late-endpoint-retry") {
                                onCarPlayEndpoint(pending, "late 0x4301 (re-posted at wrap-up)")
                            }
                        }
                    }
                }
            }
        }
        // A network is already bound (autoJoinCarWifi just bound and re-registered it): do not run the join flow again:
        // WifiCarPlayConnector internally does release() -> bindProcessToNetwork(null) first,
        // which interrupts the just-registered ctrl mDNS advertisement and then waits for the system to rebuild the binding.
        val wifiConfig =
            Iap2SenderSession.WifiConfiguration(
                endpoint.ssid,
                endpoint.passphrase,
                endpoint.channel,
                endpoint.securityType,
            )
        if (boundCarSsid == endpoint.ssid && wifiConnector?.currentNetwork != null) {
            appendLog(">>> [$source] head-unit hotspot ${endpoint.ssid} already bound, skipping the duplicate bind")
            continueWithNetwork(true)
        } else {
            // Endpoint arrived before 0x5703 (or the previous round's network switch failed): same entry - silent path first,
            // falling back to the app-specific network. Do not blind-bind whichever WiFi TRANSPORT_WIFI gives:
            // mid-switch it would bind the old network and AirPlay could never connect.
            Bg.run("wifi-join-on-endpoint") {
                while (!connectCancelled) {
                    val shellReady =
                        appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
                    when (val result = joinCarWifi(wifiConfig, shellReady)) {
                        is WifiCarPlayConnector.JoinResult.Joined -> {
                            continueWithNetwork(true)
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unsupported -> {
                            // WEP etc. cannot be expressed by a specifier: wait for the user to connect in system WLAN, then bind
                            waitForManualWifiThenContinue(wifiConfig) { bound ->
                                continueWithNetwork(bound)
                            }
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unavailable -> {
                            promptSpecifierFailed(wifiConfig, shellReady)
                            // promptSpecifierFailed may already have retried / switched to manual; both
                            // resume the flow via joinCarWifiSpecifier -> continueWithNetwork,
                            // so here only a total failure ends this round
                            if (boundCarSsid == wifiConfig.ssid && wifiConnector?.currentNetwork != null) {
                                continueWithNetwork(true)
                            } else {
                                sessionStarting.set(false)
                            }
                            return@run
                        }
                    }
                }
                sessionStarting.set(false)
            }
        }
    }

    /** Manual fallback when the specifier is unavailable: poll whether system WLAN has connected to the target hotspot, then bind. */
    private fun waitForManualWifiThenContinue(
        wifi: Iap2SenderSession.WifiConfiguration,
        onBound: (Boolean) -> Unit,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + CAR_WIFI_BIND_WAIT_MS
        var lastHintAt = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            val current = shellWifiStatus().second
            if (current == wifi.ssid) {
                onBound(bindCarWifiAndRegister(wifi.ssid))
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastHintAt > 5_000) {
                lastHintAt = android.os.SystemClock.uptimeMillis()
                phaseHint = UiStrings.format(R.string.phase_35, wifi.ssid)
                appendLog(
                    "WIFI: waiting for the user to join the head-unit hotspot in system WLAN (current ${current ?: "not connected"})...",
                )
                openWlanSettings()
            }
            Thread.sleep(1_000)
        }
        if (!connectCancelled) {
            phaseHint = UiStrings.format(R.string.phase_36, wifi.ssid)
            appendLog(">>> Timed out waiting for the head-unit hotspot - join \"${wifi.ssid}\" in Settings > WLAN and reconnect")
            uiHintSink?.invoke("未连上车机热点 \"${wifi.ssid}\"——请到 WLAN 设置中主动连接")
        }
        onBound(false)
    }

    /** Low-frequency counters for touch/tunnel events (migrated from MainActivity fields, keeping the log throttling behavior unchanged). */
    @Volatile
    private var touchLogCount = 0

    /** Count of head-unit media-key "release" reports (1-byte reports with value 0, dropped silently and only counted for reference). */
    @Volatile
    private var consumerReleaseCount = 0

    @Volatile
    private var iapEventMessageCount = 0
}

/** Fallback path decision after pairing fails (pure function; the on-device capture scenarios are pinned by PairingFallbackTest). */
internal data class PairingFallback(
    /** Two-stage hack of "drop BT to reproduce control-ended and retry" (DiPlay bench form only). */
    val useBtRetireHack: Boolean,
    /** Downgrade to an et=NONE plaintext session (fallback for pre-HomeKit old head units / pairing rejected throughout but RTSP did connect). */
    val usePlainSession: Boolean,
)

/**
 * Basis (2026-10-21 on-device sysdiagnose, docs/PROTOCOL-NOTES.md section 13a):
 * a 403 in any phase = "a head unit with iAP2 trust established is waiting to become ready" - being rejected on first connect is an inherent head-unit state,
 * it re-invites by itself 18-20s later and the second attempt succeeds. In this state both dropping BT (full stack rebuild 30-40s, missing exactly the re-invite
 * window) and a plaintext downgrade (certainly rejected and burning the re-invite window) are counter-productive, so both fallbacks are disabled;
 * the pairing loop retries in place within the window with a 5s backoff (see pairingRetryBackoffMs).
 */
internal fun pairingFallback(
    keysEstablished: Boolean,
    noPairing: Boolean,
    pairSetupRejected: Boolean,
    pairingForbidden: Boolean,
    attemptedPairing: Boolean,
    pairingUnsupported: Boolean,
): PairingFallback {
    if (keysEstablished) return PairingFallback(useBtRetireHack = false, usePlainSession = false)
    val useBtRetireHack = !noPairing && !pairSetupRejected && !pairingForbidden && attemptedPairing
    val usePlainSession = !pairingForbidden && (pairingUnsupported || attemptedPairing)
    return PairingFallback(useBtRetireHack = useBtRetireHack, usePlainSession = usePlainSession)
}
