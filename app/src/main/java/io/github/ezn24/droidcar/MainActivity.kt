package io.github.ezn24.droidcar

import android.Manifest
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import io.github.ezn24.droidcar.bt.AdapterMode
import io.github.ezn24.droidcar.bt.BtProfiles
import io.github.ezn24.droidcar.bt.TargetDevicePrefs
import io.github.ezn24.droidcar.input.TouchRouter
import io.github.ezn24.droidcar.launcher.AssistiveDot
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import io.github.ezn24.droidcar.launcher.settings.AppOrientationCompat
import io.github.ezn24.droidcar.launcher.settings.DisplayPrefs
import io.github.ezn24.droidcar.legal.DisclaimerDialog
import io.github.ezn24.droidcar.legal.DisclaimerPrefs
import io.github.ezn24.droidcar.legal.needsDisclaimerAtLaunch
import io.github.ezn24.droidcar.media.NotificationAccess
import io.github.ezn24.droidcar.nav.NavAccessibilityAccess
import io.github.ezn24.droidcar.service.CarPlayService
import io.github.ezn24.droidcar.shizuku.CarInputClient
import io.github.ezn24.droidcar.shizuku.Shell
import io.github.ezn24.droidcar.shizuku.ShizukuGate
import io.github.ezn24.droidcar.ui.DarkMessageDialog
import io.github.ezn24.droidcar.ui.PanelWindow
import io.github.ezn24.droidcar.ui.ScreenDimmer
import io.github.ezn24.droidcar.ui.SignalWavesView
import io.github.ezn24.droidcar.ui.UiStrings
import io.github.ezn24.droidcar.ui.WallpaperStore
import io.github.ezn24.droidcar.util.Bg
import io.github.ezn24.droidcar.util.LogExporter
import java.util.Locale

/**
 * Phone-side UI (Material 3 dark rework, design mock design/phone-ui-proto.html):
 * the hero card carries connection state and the primary action; connection prerequisites are a checklist; Shell state and logs are collapsed.
 * The session pipeline (objects, log buffer) is owned entirely by [SessionController]; the Activity only attaches to it:
 * destroy/recreate does not affect the session, logs resume on re-entry, and connection state is reconciled by a 1s poll.
 */
class MainActivity : AppCompatActivity() {
    private enum class HeroState { IDLE, CONNECTING, CONNECTED }

    /** Default subtitle while connecting (when there is no phase hint). */
    private companion object {
        val DEFAULT_CONNECTING_HINT get() =
            UiStrings.text(
                R.string.screen_001,
            )
        const val KEY_DISCLAIMER_DEADLINE = "disclaimer_deadline"

        /** Bottom-nav page (a light/dark switch recreates the Activity; the page is restored from savedState). */
        const val KEY_TAB = "main_tab"
        const val TAB_CONNECT = 0
        const val TAB_CAR = 1
        const val TAB_DIAG = 2

        /** Key of the "From gallery" option in the wallpaper strip (not a real source). */
        const val PICK_KEY = "pick"
    }

    private lateinit var deviceSubtitle: TextView
    private lateinit var shizukuChip: TextView
    private lateinit var shizukuDesc: TextView
    private lateinit var shizukuBadge: TextView
    private lateinit var signalWaves: SignalWavesView
    private lateinit var hubPlate: FrameLayout
    private lateinit var hubCar: ImageView
    private lateinit var hubCaption: TextView
    private lateinit var heroStateText: TextView
    private lateinit var btnResidualCleanup: TextView
    private lateinit var heroSubline: TextView
    private lateinit var heroStats: TextView
    private lateinit var heroProgress: View
    private lateinit var ctaButton: LinearLayout
    private lateinit var ctaIcon: ImageView
    private lateinit var ctaLabel: TextView
    private lateinit var audioTag: TextView
    private lateinit var readyCount: TextView
    private lateinit var ssidInput: EditText
    private lateinit var btDesc: TextView
    private lateinit var btBadge: TextView
    private lateinit var btnPickTarget: TextView
    private lateinit var audioDesc: TextView
    private lateinit var btnAudio: TextView
    private lateinit var switchAlwaysOn: androidx.appcompat.widget.SwitchCompat
    private lateinit var advBody: View
    private lateinit var advChevron: ImageView
    private lateinit var shellStateText: TextView
    private lateinit var logBody: View
    private lateinit var logChevron: ImageView
    private lateinit var switchLog: androidx.appcompat.widget.SwitchCompat
    private lateinit var logText: TextView
    private lateinit var snackbar: TextView
    private lateinit var appVersionFooter: TextView

    // ---- Hero card state machine (reconciled against SessionController by a 1s poll) ----
    private var heroState: HeroState? = null
    private var connectedAt = 0L
    private var hubBlink: ObjectAnimator? = null

    // Startup residual-display check in progress (hero card shows "checking"); residuals whose cleanup was deferred
    // are kept in pendingResidualDisplays, and the "clean up residual displays" button next to the status can retrigger it
    private var startupCheckRunning = false
    private var pendingResidualDisplays: List<Pair<Int, String>>? = null

    // Live hero-card frame rate: the 1s poll diffs VideoSender's sent-frame counter
    private var lastSentFrames = 0L

    /** Disclaimer countdown deadline (elapsedRealtime; carried across rotation recreate, not restarted). */
    private var disclaimerDeadlineAt = 0L

    private var activeDisclaimer: androidx.appcompat.app.AlertDialog? = null

    /** Shizuku permission result listener (must be unregistered in onDestroy, otherwise it leaks across Activities). */
    private var shizukuPermissionListener:
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener? = null

    /** The user took the primary action in the "request permission" guidance: continue the connect flow automatically once granted. */
    private var pendingShizukuThenConnect = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heroTick: Runnable =
        object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                tickHero()
                mainHandler.postDelayed(this, 1_000L)
            }
        }

    /**
     * Attach registration: log resume (including history of an existing session when re-entering after swiping the UI away) + SSID backfill.
     */
    private val uiHintSinkRef: (String) -> Unit = { message ->
        runOnUiThread { if (!isFinishing && !isDestroyed) snack(message, 6_000L) }
    }

    /** The currently open "join hotspot" confirmation dialog: closed together with cancel-connect (which aborts the flow). */
    private var wifiJoinDialog: androidx.appcompat.app.AlertDialog? = null

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) appendConsole(line)
        }
    }

    private val ssidSink: (String) -> Unit = { ssid ->
        runOnUiThread { ssidInput.setText(ssid) }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BLUETOOTH_CONNECT permission: $granted")
            if (granted && pendingStartAfterBtPermission.compareAndSet(true, false)) {
                // Shizuku may have been stopped during the grant: run the same precheck here
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        }

    /** Android Auto certificate import: the certificate PEM first, then its private key. */
    private var pendingAaCertPem: String? = null
    private val aaCertPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                pendingAaCertPem = null
                return@registerForActivityResult
            }
            io.github.ezn24.droidcar.util.Bg.run("aa-cert-import") {
                val text =
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull()
                runOnUiThread { onAaCertFile(text) }
            }
        }

    private fun onAaCertFile(text: String?) {
        val cert = pendingAaCertPem
        if (text == null) {
            pendingAaCertPem = null
            aaCertResult(getString(R.string.aa_cert_import_failed, "read"))
            return
        }
        if (cert == null) {
            pendingAaCertPem = text
            android.widget.Toast
                .makeText(this, R.string.aa_cert_pick_key, android.widget.Toast.LENGTH_LONG)
                .show()
            aaCertPicker.launch(arrayOf("*/*"))
            return
        }
        pendingAaCertPem = null
        io.github.ezn24.droidcar.util.Bg.run("aa-cert-save") {
            val result =
                runCatching {
                    io.github.ezn24.droidcar.aa.cert
                        .CertificateStore(this)
                        .importPem("imported", cert, text)
                }
            runOnUiThread {
                aaCertResult(
                    result.fold(
                        { getString(R.string.aa_cert_import_done, it.subject) },
                        { getString(R.string.aa_cert_import_failed, it.message ?: it.javaClass.simpleName) },
                    ),
                )
            }
        }
    }

    private fun aaCertResult(message: String) {
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.aa_cert_title),
            message = message,
            actions = listOf(DarkMessageDialog.Action(getString(R.string.aa_cert_close)) {}),
        )
    }

    /** The Android Auto certificates DroidCar can offer, with download and import. */
    private fun showAaCertificates() {
        io.github.ezn24.droidcar.util.Bg.run("aa-cert-list") {
            val entries =
                runCatching {
                    io.github.ezn24.droidcar.aa.cert
                        .CertificateStore(this)
                        .entries()
                }.getOrDefault(emptyList())
            val fmt = java.text.DateFormat.getDateInstance()
            val list =
                entries.joinToString("\n") { e ->
                    val kind =
                        when {
                            e.isGoogleIssued -> "Google · "
                            e.selfSigned -> getString(R.string.aa_cert_self) + " · "
                            else -> ""
                        }
                    "• " + kind + getString(R.string.aa_cert_until, fmt.format(e.notAfter))
                }
            runOnUiThread {
                DarkMessageDialog.show(
                    activity = this,
                    title = getString(R.string.aa_cert_title),
                    message = getString(R.string.aa_cert_explain) + "\n\n" + list,
                    actions =
                        listOf(
                            DarkMessageDialog.Action(getString(R.string.aa_cert_close)) {},
                            DarkMessageDialog.Action(getString(R.string.aa_cert_import)) {
                                pendingAaCertPem = null
                                android.widget.Toast
                                    .makeText(this, R.string.aa_cert_pick_cert, android.widget.Toast.LENGTH_LONG)
                                    .show()
                                aaCertPicker.launch(arrayOf("*/*"))
                            },
                            DarkMessageDialog.Action(getString(R.string.aa_cert_download), DarkMessageDialog.Emphasis.PRIMARY) {
                                downloadAaCertificate()
                            },
                        ),
                )
            }
        }
    }

    private fun downloadAaCertificate() {
        io.github.ezn24.droidcar.util.Bg.run("aa-cert-download") {
            val result =
                runCatching {
                    val d =
                        io.github.ezn24.droidcar.aa.cert.CertificateDownload
                            .fetch()
                    io.github.ezn24.droidcar.aa.cert
                        .CertificateStore(this)
                        .importDownloaded(d)
                }
            SessionController.appendLog(
                "AA: certificate download " + if (result.isSuccess) "done" else "failed - ${result.exceptionOrNull()?.message}",
            )
            runOnUiThread {
                aaCertResult(
                    result.fold(
                        {
                            getString(
                                R.string.aa_cert_download_done,
                                java.text.DateFormat
                                    .getDateInstance()
                                    .format(it.notAfter),
                            )
                        },
                        { getString(R.string.aa_cert_download_failed, it.message ?: it.javaClass.simpleName) },
                    ),
                )
            }
        }
    }

    /** Continue starting the connection automatically after the Bluetooth permission is granted. */
    private val pendingStartAfterBtPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** Slot (dark / light) that the gallery-picked image is written to. */
    private var pendingWallpaperSlot = WallpaperStore.Slot.DARK

    /** Head-unit wallpaper picker (Photo Picker; no storage permission needed). The pick is persisted and the compositor is told to swap the image. */
    private val wallpaperPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                log("WALLPAPER: no image selected")
                return@registerForActivityResult
            }
            val slot = pendingWallpaperSlot
            val ok =
                WallpaperStore
                    .setFromUri(this, uri, slot)
            if (ok) buildWallpaperStrips()
            log(
                "WALLPAPER: ${if (ok) "wallpaper updated (reaches the car within ~1s during a session; otherwise applies on next connect)" else "save failed"}",
            )
            if (ok) {
                snack(
                    UiStrings
                        .text(R.string.screen_002),
                )
            } else {
                snack(
                    UiStrings
                        .text(R.string.screen_003),
                )
            }
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("MODE: RECORD_AUDIO permission: $granted (needed by the audio channel)")
            // The audio-recording and screen-capture grants must be requested one after the other: if both system dialogs
            // are raised at once they stack and the first one jumps/shifts when the other appears (the long-reported "dialog jitter")
            launchProjectionConsent()
        }

    private fun launchProjectionConsent() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /**
     * Request the grants needed for head-unit audio. When Shizuku is ready, go through [SilentProjectionActivity] (appops allow,
     * same path as "auto-connect on boarding") so no system dialog is shown; otherwise ask for recording, then screen capture, in turn.
     */
    private fun requestAudioCapture() {
        if (ShizukuGate.state(this) == ShizukuGate.State.READY) {
            Bg.run("audio-silent-grant") {
                val grant =
                    io.github.ezn24.droidcar.audio.SilentProjectionActivity
                        .request(applicationContext)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (grant != null) {
                        log("MODE: screen-capture grant obtained silently via Shizuku (no system dialog)")
                        onProjectionGranted(grant.resultCode, grant.data)
                    } else {
                        log("MODE: silent grant failed, falling back to the system grant dialog")
                        requestAudioCaptureViaSystem()
                    }
                }
            }
            return
        }
        requestAudioCaptureViaSystem()
    }

    private fun requestAudioCaptureViaSystem() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionConsent()
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                log("MODE: screen-capture permission denied (head-unit audio unavailable)")
                return@registerForActivityResult
            }
            onProjectionGranted(result.resultCode, data)
        }

    private fun onProjectionGranted(
        resultCode: Int,
        data: android.content.Intent,
    ) {
        // Android 14+ order: hand the result to the service first, which upgrades the FGS type and then calls getMediaProjection
        CarPlayService.pendingProjectionResult =
            resultCode to data
        CarPlayService
            .start(this)
        log("MODE: ✓ screen-capture permission granted (must be granted again after a phone reboot)")
        // Granted while a session is already running (grant came after connect): once the projection is ready, start the audio channel dynamically
        Bg.run("audio-late-start") {
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (SystemClock.uptimeMillis() < deadline) {
                if (CarPlayService.mediaProjection != null) break
                Thread.sleep(200)
            }
            if (CarPlayService.mediaProjection == null) {
                log("MODE: projection not ready after 5s (service problem?), audio channel not started this time")
                return@run
            }
            SessionController.maybeStartAudioNow(applicationContext)
            // Sound was enabled at "start connection" and the capture grant only arrives now: go on to start the connection
            if (pendingStartAfterProjection.compareAndSet(true, false)) {
                startConnectionNow()
            }
        }
    }

    /** Set when the capture grant is a prerequisite of "start connection" (sound enabled and not yet granted). */
    private val pendingStartAfterProjection =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * Sound choice before "start connection": it used to prompt every time, now it reads the "head-unit audio" switch on the connection page (user request 2026-11-18).
     * The entry name is kept because several continue-connect paths (e.g. permission callbacks) go through it.
     */
    private fun showAudioChoiceDialog() {
        // Car audio is always on (the switch was removed 2026-11-26: nobody connects without sound)
        beginWithAudio(true)
    }

    /** "Cancel connection" confirmation dialog (guards against accidental taps): aborts the current attempt and drops the Bluetooth/WiFi links. */
    private fun promptCancelConnecting() {
        DarkMessageDialog.show(
            activity = this,
            title =
                UiStrings
                    .text(R.string.screen_010),
            message =
                UiStrings
                    .text(R.string.screen_011),
            actions =
                listOf(
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_012),
                    ) {},
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_013),
                        DarkMessageDialog.Emphasis.DANGER,
                    ) {
                        // Close the still-waiting "join hotspot" confirmation dialog (its onChoice(null), together with
                        // connectCancelled, makes the autoJoin flow abort immediately)
                        wifiJoinDialog?.cancel()
                        wifiJoinDialog = null
                        SessionController.cancelConnecting(this)
                    },
                ),
        )
    }

    /**
     * Precheck for "start connection": Bluetooth permission -> Bluetooth switch -> WLAN location permission (Android 12/12L only).
     * Any failure shows a guidance dialog (one tap to system Bluetooth settings when it is off); on success the callback continues.
     */
    private fun ensureBluetoothReady(onReady: () -> Unit) {
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterBtPermission.set(true)
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        ensureWifiLocationReady {
            val on =
                try {
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                        ?.isEnabled == true
                } catch (_: SecurityException) {
                    true
                }
            if (!on) {
                DarkMessageDialog.show(
                    activity = this,
                    title =
                        UiStrings
                            .text(R.string.screen_014),
                    message =
                        UiStrings
                            .text(R.string.screen_015),
                    actions =
                        listOf(
                            DarkMessageDialog.Action(
                                UiStrings
                                    .text(R.string.ui_081),
                            ) {},
                            DarkMessageDialog.Action(
                                UiStrings
                                    .text(R.string.screen_016),
                                DarkMessageDialog.Emphasis.PRIMARY,
                            ) {
                                runCatching {
                                    startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                    )
                                }.onFailure { log("BT: cannot open Bluetooth settings, please enable it manually") }
                            },
                        ),
                )
                log("BT: Bluetooth is off - connection not started")
                return@ensureWifiLocationReady
            }
            onReady()
        }
    }

    /**
     * An app-specific network (WifiNetworkSpecifier) needs location permission and the location service on Android 12/12L;
     * 13+ uses NEARBY_WIFI_DEVICES instead and no longer requires location. Without them the system makes
     * `requestNetwork` fail outright (surfacing as "could not join the head-unit hotspot", hard to diagnose), so it is blocked up front.
     */
    private fun ensureWifiLocationReady(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onReady()
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterLocationPermission.set(true)
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val locationOn =
            try {
                androidx.core.location.LocationManagerCompat
                    .isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
            } catch (_: Exception) {
                true
            }
        if (!locationOn) {
            DarkMessageDialog.show(
                activity = this,
                title =
                    UiStrings
                        .text(R.string.screen_017),
                message =
                    UiStrings
                        .text(R.string.screen_018) +
                        UiStrings
                            .text(R.string.screen_019) +
                        UiStrings
                            .text(R.string.screen_020),
                actions =
                    listOf(
                        DarkMessageDialog.Action(
                            UiStrings
                                .text(R.string.ui_081),
                        ) {},
                        DarkMessageDialog.Action(
                            UiStrings
                                .text(R.string.screen_021),
                            DarkMessageDialog.Emphasis.PRIMARY,
                        ) {
                            runCatching {
                                startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                )
                            }.onFailure { log("WIFI: cannot open location service settings, please enable it manually") }
                        },
                    ),
            )
            log("WIFI: location service is off - app-specific network is unavailable on Android 12, connection not started")
            return
        }
        onReady()
    }

    /** Continue starting the connection automatically after the location permission is granted (Android 12/12L only). */
    private val pendingStartAfterLocationPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("ACCESS_FINE_LOCATION permission: $granted")
            if (granted && pendingStartAfterLocationPermission.compareAndSet(true, false)) {
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        }

    /**
     * "Disconnect" confirmation dialog (guards against accidental taps): disconnecting cuts the head-unit picture immediately,
     * and the capture grant lapses when the keep-alive service stops (head-unit audio needs a new grant next time).
     */
    private fun promptDisconnect() {
        DarkMessageDialog.show(
            activity = this,
            title =
                UiStrings
                    .text(R.string.screen_022),
            message =
                UiStrings
                    .text(R.string.screen_023) +
                    UiStrings
                        .text(R.string.screen_024),
            actions =
                listOf(
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.ui_081),
                    ) {},
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_025),
                        DarkMessageDialog.Emphasis.DANGER,
                    ) {
                        SessionController.disconnect(this, byUser = true)
                    },
                ),
        )
    }

    /**
     * Hard prerequisite for "start connection": Shizuku must be ready.
     *
     * Virtual-display creation, touch injection and window/display state reads in hidden-display mode all go through the Shizuku shell;
     * if it is not ready the connection ends up half-working ("Bluetooth connected, head unit black + all touch lost"), worse than blocking outright.
     * When not ready, actionable guidance is given per state (install / start / grant).
     */

    /**
     * Silently grant notification-listener access (idempotent; background thread).
     *
     * Do not try only once in onCreate: if Shizuku is not ready then, it is skipped for good,
     * and the user sees "music playing but the card says media info not connected": without the grant
     * `getActiveSessions()` still returns sessions (playback state is fine) but `metadata` is always null.
     * So it is called again at every moment Shizuku is confirmed ready; when already granted it returns
     * immediately without extra shell round trips.
     */
    private fun ensureNotificationAccessAsync() {
        Bg.run("notification-access") {
            if (ShizukuGate.state(this) != ShizukuGate.State.READY) {
                log("MEDIA: Shizuku not ready - cannot auto-grant notification access yet (will retry once ready)")
                return@run
            }
            NotificationAccess.ensureGranted(this, ::log)
        }
    }

    private fun setClusterNavigationEnabled(enabled: Boolean) {
        val app = applicationContext
        NavAccessibilityAccess.setEnabledByUser(app, enabled)
        Bg.run("nav-a11y-toggle") {
            val active = NavAccessibilityAccess.sync(app, ::log)
            if (enabled && !active) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || !NavAccessibilityAccess.enabledByUser(app)) return@runOnUiThread
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
        }
    }

    private fun ensureShizukuReady(onReady: () -> Unit) {
        val state = ShizukuGate.state(this)
        if (state == ShizukuGate.State.READY) {
            // Once Shizuku is ready, grant silently (fills in a grant that was skipped earlier because it was not ready)
            ensureNotificationAccessAsync()
            onReady()
            return
        }
        log("SHIZUKU: not ready ($state) - this connection was blocked")
        promptShizukuNotReady(state)
    }

    /**
     * Shizuku-not-ready guidance dialog: the primary action varies with the state, plus "retry check" and "cancel".
     * Uses the custom [DarkMessageDialog] panel; the system AlertDialog is light-themed and clashes with the dark UI.
     */
    private fun promptShizukuNotReady(state: ShizukuGate.State) {
        val guidance = ShizukuGate.localizedGuidanceOf(state)
        val actions = mutableListOf<DarkMessageDialog.Action>()
        actions +=
            DarkMessageDialog.Action(
                UiStrings
                    .text(R.string.ui_081),
            ) {}
        actions +=
            DarkMessageDialog.Action(
                UiStrings
                    .text(R.string.screen_026),
            ) {
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        when (state) {
            ShizukuGate.State.NOT_RUNNING ->
                actions +=
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_027),
                        DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        val intent = packageManager.getLaunchIntentForPackage(ShizukuGate.PACKAGE_NAME)
                        if (intent == null) {
                            snack(
                                UiStrings
                                    .text(R.string.screen_028),
                            )
                        } else {
                            runCatching { startActivity(intent) }
                                .onFailure {
                                    snack(
                                        UiStrings
                                            .text(R.string.screen_028),
                                    )
                                }
                        }
                    }

            ShizukuGate.State.UNAUTHORIZED ->
                actions +=
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_029),
                        DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        pendingShizukuThenConnect = true
                        ShizukuGate.requestPermission()
                        snack(
                            UiStrings
                                .text(R.string.screen_030),
                        )
                    }

            // Not installed: no actionable primary action (no made-up install link)
            ShizukuGate.State.NOT_INSTALLED, ShizukuGate.State.READY -> Unit
        }
        DarkMessageDialog.show(
            activity = this,
            title = guidance.title,
            message = guidance.message,
            actions = actions,
        )
    }

    private fun beginWithAudio(enabled: Boolean) {
        SessionController.audioEnabledForSession = enabled
        // Sound has a single mode now: path negotiation (AAC-LC first, automatic fallback to PCM) completes inside the session,
        // so there is no manual "compatibility tier" branch any more.
        SessionController.audioPreferCompat = false
        if (!enabled) {
            log("MODE: this connection pushes video only (head-unit audio not enabled)")
            startConnectionNow()
            return
        }
        if (CarPlayService.mediaProjection != null) {
            log("MODE: sound enabled (screen-capture permission ready)")
            startConnectionNow()
            return
        }
        // Shizuku captures the system output directly: no screen-capture grant needed (the session falls back to one only if
        // that capture fails, SessionController.requestProjectionFallback)
        if (ShizukuGate.state(this) == ShizukuGate.State.READY) {
            log("MODE: sound via Shizuku system output capture (no screen-capture grant needed)")
            startConnectionNow()
            return
        }
        // Enabling sound needs the screen-capture permission (the source of the playback-capture grant): request it first, then start the connection once granted
        log(
            "MODE: sound needs screen-capture permission - requesting in sequence (audio capture depends on it), connection starts after the grant",
        )
        pendingStartAfterProjection.set(true)
        requestAudioCapture()
    }

    /** Body of "start connection" (permission checks stay here; session start is delegated to [SessionController], idempotent, never doubled). */
    private fun startConnectionNow() {
        runOnUiThread {
            // Fallback guard: delayed paths such as permission callbacks can also land here, and Shizuku may stop midway, so block again
            val gate = ShizukuGate.state(this)
            if (gate != ShizukuGate.State.READY) {
                log("SHIZUKU: not ready ($gate) - this connection was blocked")
                promptShizukuNotReady(gate)
                return@runOnUiThread
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Notification permission (API 33+): makes the foreground-service notification visible; denial does not affect keep-alive
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
            SessionController.startConnection(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Rotation/recreate: reuse the original countdown deadline (onResume resumes the dialog from it)
        disclaimerDeadlineAt = savedInstanceState?.getLong(KEY_DISCLAIMER_DEADLINE, 0L) ?: 0L

        appVersionFooter = findViewById(R.id.appVersionFooter)
        appVersionFooter.text = "DroidCar v${BuildConfig.VERSION_NAME}"
        deviceSubtitle = findViewById(R.id.deviceSubtitle)
        shizukuChip = findViewById(R.id.shizukuChip)
        shizukuDesc = findViewById(R.id.shizukuDesc)
        shizukuBadge = findViewById(R.id.shizukuBadge)
        signalWaves = findViewById(R.id.signalWaves)
        hubPlate = findViewById(R.id.hubPlate)
        hubCar = findViewById(R.id.hubCar)
        hubCaption = findViewById(R.id.hubCaption)
        heroStateText = findViewById(R.id.heroStateText)
        btnResidualCleanup = findViewById(R.id.btnResidualCleanup)
        btnResidualCleanup.setOnClickListener {
            pendingResidualDisplays?.let { promptResidualDisplays(it) }
        }
        heroSubline = findViewById(R.id.heroSubline)
        heroStats = findViewById(R.id.heroStats)
        heroProgress = findViewById(R.id.heroProgress)
        ctaButton = findViewById(R.id.ctaButton)
        ctaIcon = findViewById(R.id.ctaIcon)
        ctaLabel = findViewById(R.id.ctaLabel)
        audioTag = findViewById(R.id.audioTag)
        readyCount = findViewById(R.id.readyCount)
        ssidInput = findViewById(R.id.ssidInput)
        btDesc = findViewById(R.id.btDesc)
        btBadge = findViewById(R.id.btBadge)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        audioDesc = findViewById(R.id.audioDesc)
        btnAudio = findViewById(R.id.btnAudio)
        switchAlwaysOn = findViewById(R.id.switchAlwaysOn)
        advBody = findViewById(R.id.advBody)
        advChevron = findViewById(R.id.advChevron)
        shellStateText = findViewById(R.id.shellStateText)
        logBody = findViewById(R.id.logBody)
        logChevron = findViewById(R.id.logChevron)
        switchLog = findViewById(R.id.switchLog)
        logText = findViewById(R.id.logText)
        snackbar = findViewById(R.id.snackbar)

        deviceSubtitle.text = deviceName()
        logText.movementMethod = ScrollingMovementMethod()
        // The console sits inside a long-page ScrollView: on touch-down ask the parent not to intercept vertical gestures, otherwise it cannot be scrolled by hand
        logText.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,
                -> updateLogBottomButton()
            }
            false
        }
        findViewById<ImageView>(R.id.btnLogBottom).setOnClickListener {
            // Jump to the bottom: auto-follow resumes after the scroll
            scrollConsoleToBottom()
        }

        // Shizuku permission result: refresh the status row and hero subtitle immediately; if the user came here via the
        // "request permission" guidance (pendingShizukuThenConnect), continue straight to the audio choice once granted.
        shizukuPermissionListener =
            rikka.shizuku.Shizuku
                .OnRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode != ShizukuGate.PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        if (granted) ScreenDimmer.syncScreenTimeout(applicationContext)
                        log("SHIZUKU: permission result granted=$granted")
                        updateReadyRow()
                        updateShizukuChip()
                        if (granted) {
                            // Shizuku just became ready: grant notification access once more (typical after a package rename/reboot,
                            // when the onCreate attempt was skipped because it was not ready)
                            ensureNotificationAccessAsync()
                        }
                        if (granted && pendingShizukuThenConnect) {
                            pendingShizukuThenConnect = false
                            ensureShizukuReady { showAudioChoiceDialog() }
                        } else if (!granted) {
                            snack(
                                UiStrings
                                    .text(R.string.screen_031),
                            )
                        }
                    }
                }.also {
                    runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(it) }
                }

        // Session hub (process-wide): register applicationContext + create the session objects;
        // attach registration: log resume + SSID backfill
        SessionController.ensure(this)
        SessionController.logListener = logListener
        // Important connect-flow hints (WiFi off / hotspot join limits etc.) are shown as a long Snackbar
        SessionController.uiHintSink = uiHintSinkRef
        // Join-hotspot confirmation dialog: continue auto-join / open WLAN settings to connect manually.
        // cancel() on cancel-connect closes it (triggers onChoice(null) -> the flow checks the cancel flag and aborts)
        SessionController.dialogSink = { spec, onChoice ->
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    onChoice(null)
                    return@runOnUiThread
                }
                var answered = false
                // Cancel (back key / tap outside / closed by cancelConnecting) = null, and the flow aborts on it;
                // besides calling back false, "open WLAN" must also send the user to the system WLAN page to connect manually.
                val dialog =
                    DarkMessageDialog.show(
                        activity = this,
                        title = spec.title,
                        message = spec.message,
                        actions =
                            listOf(
                                DarkMessageDialog.Action(spec.negative) {
                                    answered = true
                                    runCatching {
                                        startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
                                        )
                                    }
                                    onChoice(false)
                                },
                                DarkMessageDialog.Action(
                                    spec.positive,
                                    DarkMessageDialog.Emphasis.PRIMARY,
                                ) {
                                    answered = true
                                    onChoice(true)
                                },
                            ),
                        onCancel = { if (!answered) onChoice(null) },
                    )
                wifiJoinDialog = dialog
            }
        }
        SessionController.ssidSink = ssidSink
        renderConsole(SessionController.logText())

        setUpUiControls()
        setUpTabs(savedInstanceState?.getInt(KEY_TAB, TAB_CONNECT) ?: TAB_CONNECT)
        // Starting in landscape does not trigger onConfigurationChanged, so lay out once here for the current orientation.
        // On the first call contentArea is unmeasured (width=0, only a screen-width estimate is possible),
        // so recompute precisely once layout completes, to avoid the hero card width and right-column inset being a few pixels off.
        val startLandscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        applyContentLayout(startLandscape)
        findViewById<android.widget.FrameLayout>(R.id.contentArea).post {
            if (!isFinishing && !isDestroyed) applyContentLayout(startLandscape)
        }

        // First run: the setup guide walks through the disclaimer, permissions and the main options (it handles the disclaimer itself)
        if (savedInstanceState == null &&
            !io.github.ezn24.droidcar.setup.SetupPrefs
                .done(this)
        ) {
            startActivity(Intent(this, io.github.ezn24.droidcar.setup.SetupActivity::class.java))
            return
        }
        // Disclaimer: forced dialog when the current version is not yet accepted (not cancelable, needs countdown + scroll to bottom).
        // A non-null savedState (rotation/recreate) reuses the original deadline instead of restarting the timer.
        if (savedInstanceState == null &&
            needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            )
        ) {
            showDisclaimer(requireAcceptance = true)
        }
    }

    /** Bottom "Disclaimer" entry: read-only review (no time limit, no scroll-to-bottom requirement). */
    private fun showDisclaimer(requireAcceptance: Boolean) {
        if (activeDisclaimer?.isShowing == true) return
        if (requireAcceptance && disclaimerDeadlineAt == 0L) {
            disclaimerDeadlineAt = SystemClock.elapsedRealtime() + DisclaimerDialog.COUNTDOWN_MS
        }
        activeDisclaimer =
            DisclaimerDialog
                .show(
                    activity = this,
                    requireAcceptance = requireAcceptance,
                    deadlineAt = disclaimerDeadlineAt,
                    onDecline = {
                        // Give up: finish this task and exit (not System.exit, to keep normal lifecycle teardown).
                        // If a session is running, disconnect normally first so no unmanaged stream or virtual display is left behind.
                        runCatching { SessionController.disconnect(this, byUser = true) }
                        finishAndRemoveTask()
                    },
                    onDismiss = { activeDisclaimer = null },
                )
    }

    /** Hides a list row and the 1dp divider that separates it from its neighbour (the one before it, else the one after). */
    private fun hideRowWithDivider(row: View) {
        row.visibility = View.GONE
        val parent = row.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(row)

        fun isDivider(view: View?) = view != null && view.javaClass == View::class.java
        val before = parent.getChildAt(index - 1)
        val after = parent.getChildAt(index + 1)
        when {
            isDivider(before) -> before.visibility = View.GONE
            isDivider(after) -> after.visibility = View.GONE
        }
    }

    /** Tail of onCreate's UI initialization: switches/buttons/collapsible cards (session logic unchanged, just attached to the new UI). */
    private fun setUpUiControls() {
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Clear the mode preference left by old versions (the mode is now fixed to the "hidden display" launcher)
        if (preferences.contains("mode")) {
            preferences.edit().remove("mode").apply()
        }

        // ---- Disclaimer (read-only review) ----
        findViewById<LinearLayout>(R.id.btnDisclaimer).setOnClickListener {
            showDisclaimer(requireAcceptance = false)
        }

        // ---- Remote-debug entry: shown only when the remote debug build has the component (resolves to null in the standard build, always hidden;
        // main code references no bridge class and detects the component instead, so debug code never leaks into the standard artifact) ----
        val bridgeSetup =
            Intent().setClassName(packageName, "io.github.ezn24.droidcar.bridge.BridgeSetupActivity")
        if (packageManager.resolveActivity(bridgeSetup, 0) != null) {
            findViewById<LinearLayout>(R.id.btnBridgeSetup).visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.btnBridgeSetup).setOnClickListener {
                startActivity(bridgeSetup)
            }
        }

        // ---- Shizuku status row: when not ready, tapping it shows the guidance (same text as when "start connection" is blocked) ----
        findViewById<LinearLayout>(R.id.rowShizuku).setOnClickListener {
            val state = ShizukuGate.state(this)
            if (state == ShizukuGate.State.READY) {
                snack(
                    UiStrings
                        .text(R.string.screen_032),
                )
            } else {
                promptShizukuNotReady(state)
            }
        }

        // ---- Keep-awake + dim cover ----
        ScreenDimmer.enabled =
            preferences.getBoolean("alwaysOn", true)
        switchAlwaysOn.isChecked = ScreenDimmer.enabled
        switchAlwaysOn.setOnCheckedChangeListener { _, checked ->
            ScreenDimmer.enabled = checked
            preferences.edit().putBoolean("alwaysOn", checked).apply()
            if (checked) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    promptOverlayPermission(
                        UiStrings
                            .text(R.string.screen_033),
                    )
                } else {
                    ScreenDimmer
                        .attach(this)
                }
            } else {
                if (VirtualLauncher.appDisplayId >= 0) {
                    // During a session: only turn off keep-awake/dim cover (capture probes and other resources are released together at session end)
                    ScreenDimmer
                        .disableDim()
                } else {
                    ScreenDimmer
                        .detach()
                }
            }
            log("MODE: keep-awake + dim cover ${if (checked) "on" else "off"}")
        }

        // ---- Hero card primary action ----
        ctaButton.setOnClickListener {
            when (heroState) {
                HeroState.CONNECTED -> promptDisconnect()
                // Prechecks: Shizuku (hard dependency) -> Bluetooth permission/switch -> audio choice
                HeroState.IDLE -> ensureShizukuReady { ensureBluetoothReady { showAudioChoiceDialog() } }
                // Cancelable while connecting: lets the user escape when stuck on a wrong network or waiting for a reconnect (dialog guards against accidental taps)
                HeroState.CONNECTING -> promptCancelConnecting()
                null -> {}
            }
        }

        // ---- Connection prerequisites ----
        findViewById<TextView>(R.id.btnWifi).setOnClickListener {
            val ssid = ssidInput.text.toString().trim()
            // Manual fallback when auto-join is unavailable: the password goes to the clipboard, paste it in the system WLAN page
            val psk = SessionController.lastWifiPsk
            if (psk != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("carplay-wifi", psk))
                snack(UiStrings.format(R.string.wifi_password_copied, psk.length))
                log(
                    "WiFi: hotspot password copied to clipboard, pick \"$ssid\" and paste it; " +
                        UiStrings
                            .text(R.string.screen_034),
                )
            } else {
                snack(
                    UiStrings
                        .text(R.string.screen_035),
                )
                log(
                    "WiFi: hotspot password not obtained yet (needs Bluetooth session 0x5703) - opening WLAN settings to pick \"$ssid\" manually",
                )
            }
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                log("WiFi: cannot open WLAN settings, please go to Settings > WLAN manually")
            }
        }

        // Target head-unit choice: with several paired head units, pin a single target (requests go only to it / only it may connect in)
        btnPickTarget.setOnClickListener { showTargetPicker() }

        // Head-unit audio grants (formerly "request screen-capture permission"): RECORD_AUDIO + MediaProjection are requested together
        btnAudio.setOnClickListener { requestAudioCapture() }

        // ---- Head-unit display ----
        // Connection-page switches: head-unit audio (replaces the pre-connect dialog), and whether to wait for a reconnect after the head unit disconnects
        // The former "car audio" switch row (sound is always on now) is "play music when connected"
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchCarAudio).apply {
            isChecked = DisplayPrefs.autoPlayOnConnect(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> DisplayPrefs.setAutoPlayOnConnect(this@MainActivity, checked) }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchWaitReconnect).apply {
            isChecked =
                io.github.ezn24.droidcar.launcher.settings.DisplayPrefs
                    .waitForReconnect(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                io.github.ezn24.droidcar.launcher.settings.DisplayPrefs
                    .setWaitForReconnect(this@MainActivity, checked)
            }
        }
        findViewById<SwitchCompat>(R.id.switchCarAssistiveDot).apply {
            isChecked = AssistiveDot.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AssistiveDot.setEnabled(this@MainActivity, checked) }
        }
        findViewById<SwitchCompat>(R.id.switchClusterNavigation).apply {
            isChecked = NavAccessibilityAccess.enabledByUser(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> setClusterNavigationEnabled(checked) }
        }
        findViewById<SwitchCompat>(R.id.switchCarNotifications).apply {
            isChecked = DisplayPrefs.showCarNotifications(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                DisplayPrefs.setShowCarNotifications(this@MainActivity, checked)
                if (checked) ensureNotificationAccessAsync()
            }
        }
        findViewById<LinearLayout>(R.id.rowDim).setOnClickListener {
            if (!ScreenDimmer
                    .isAttached()
            ) {
                snack(
                    UiStrings
                        .text(R.string.screen_036),
                )
                return@setOnClickListener
            }
            ScreenDimmer
                .enterDimNow()
            log("DIMMER: manual fake screen-off (tap the screen to restore)")
        }

        // Removed 2026-11-26: "car audio" (always on now) with its readiness row (Shizuku captures sound without a screen-capture
        // grant) and "fake screen-off now" (the dim cover comes on by itself). Hidden with the divider next to each row.
        hideRowWithDivider(findViewById<View>(R.id.rowDim))
        hideRowWithDivider(btnAudio.parent as View)

        buildWallpaperStrips()

        // ---- Collapsible cards: advanced / log ----
        bindExpandable(R.id.advHeader, advBody, advChevron)
        bindExpandable(R.id.logHeader, logBody, logChevron)

        // ---- Global Shell state ----
        findViewById<View>(R.id.btnShellRefresh).setOnClickListener { refreshShellState() }
        findViewById<View>(R.id.btnShellReset).setOnClickListener { resetShellState() }
        refreshShellState(startupCheck = true)

        // ---- Log switch (default on; when off nothing is collected or rendered; the choice is persisted) ----
        var logEnabled = preferences.getBoolean("logEnabled", true)
        SessionController.logEnabled = logEnabled
        switchLog.isChecked = logEnabled
        switchLog.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            SessionController.logEnabled = checked
            preferences.edit().putBoolean("logEnabled", checked).apply()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnClearLog).setOnClickListener {
            // After clearing the buffer a re-render must be explicit: the log callback has append-only semantics, so the clearLog
            // notification cannot clear the UI (see the SessionController.clearLog comment, root cause of "tapped but nothing happened")
            SessionController.clearLog()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnCopyLog).setOnClickListener {
            val text = SessionController.logText()
            if (text.isEmpty()) {
                snack(
                    UiStrings
                        .text(R.string.screen_039),
                )
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    UiStrings
                        .text(R.string.screen_040),
                    text,
                ),
            )
            snack(UiStrings.format(R.string.logs_copied, text.length))
        }
        findViewById<ImageView>(R.id.btnExportLog).setOnClickListener { exportLogFile() }
        // Long-press exports the full history: by default only this launch is exported; fetch history on demand when it is large
        findViewById<ImageView>(R.id.btnExportLog).setOnLongClickListener {
            exportLogFile(LogExporter.Scope.ALL)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Notification-listener access (media session enumeration, data source of the music card): can be granted silently once Shizuku is ready.
        // Idempotent and retryable - it may not be ready at launch (Shizuku not running after reboot, not yet granted after a package rename),
        // so it is called again when Shizuku is granted, when a connection is established, etc.
        ensureNotificationAccessAsync()

        // ---- Car settings ("Car" page: the settings menu shared with the head-unit two-pane layout) ----
        buildCarMenu()

        // ---- Head-unit screenshot (the whole composited picture) ----
        findViewById<View>(R.id.btnSessionHooks).setOnClickListener {
            startActivity(android.content.Intent(this, AutomationActivity::class.java))
        }
        findViewById<View>(R.id.btnCarScreenshot).setOnClickListener {
            io.github.ezn24.droidcar.util.CarScreenshot.capture(this) { result ->
                runOnUiThread {
                    if (result == null) {
                        snack(getString(R.string.md_car_screenshot_failed))
                    } else {
                        log("SCREENSHOT: head-unit picture ${result.width}x${result.height} → ${result.path}")
                        snack(result.path.substringAfter("/storage/emulated/0/"))
                    }
                }
            }
        }

        // Check at launch: keep-awake on + no overlay permission -> request it proactively
        if (ScreenDimmer.enabled) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                promptOverlayPermission(
                    UiStrings
                        .text(R.string.screen_041),
                )
            } else {
                // attach only hooks up the touch probe and does not schedule the auto dim timer: auto dim/keep-awake is session-driven
                // (ScreenDimmer.setSessionActive), otherwise the screen would also go black after 45s while disconnected
                // (user-observed "dim cover starts after the session ended", 2026-10-19).
                ScreenDimmer
                    .attach(this)
            }
        }

        TouchRouter.logSink = { line -> log(line) }
        VirtualLauncher.logSink = { line -> log(line) }
        CarInputClient.logSink = { line -> log(line) }

        log("DroidCar Phase 0 ready")
        log(
            "Real car: pair Bluetooth with the head unit + join the head-unit hotspot on the phone -> tap \"Start connection\" (connects to the head unit proactively)",
        )
        log(
            "Test bench: pair the two phones over Bluetooth -> run xcertplay wireless mode on the spare phone -> tap \"Start connection\" and wait for it to connect back",
        )
    }

    private fun bindExpandable(
        headerId: Int,
        body: View,
        chevron: ImageView,
    ) {
        findViewById<View>(headerId).setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron
                .animate()
                .rotation(if (opening) 180f else 0f)
                .setDuration(250L)
                .start()
        }
    }

    // ==================== Hero card state machine ====================

    private fun tickHero() {
        val s =
            when {
                SessionController.videoSender?.isRunning == true -> HeroState.CONNECTED
                io.github.ezn24.droidcar.carlife.CarLifeController.streaming -> HeroState.CONNECTED
                io.github.ezn24.droidcar.iccoa.IccoaController.streaming -> HeroState.CONNECTED
                io.github.ezn24.droidcar.aa.AndroidAutoController.streaming -> HeroState.CONNECTED
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() ->
                    HeroState.CONNECTING
                else -> HeroState.IDLE
            }
        if (s != heroState) {
            if (s == HeroState.CONNECTED) connectedAt = SystemClock.elapsedRealtime()
            heroState = s
            applyHeroState(s)
        }
        // Connecting: the subtitle follows the phase hint (visible inside the hero card, no need to scroll down to the log)
        if (s == HeroState.CONNECTING) {
            heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
        }
        // Startup residual-display check in progress: status and subtitle temporarily switch to the checking hint
        if (s == HeroState.IDLE && startupCheckRunning) {
            heroStateText.text =
                UiStrings
                    .text(R.string.screen_042)
            heroSubline.text =
                UiStrings
                    .text(R.string.screen_043)
            heroProgress.visibility = View.VISIBLE
        }
        // Idle but Shizuku not ready: the subtitle says outright that "tapping will not connect", so the user does not keep tapping
        if (s == HeroState.IDLE && !startupCheckRunning) {
            val gate = ShizukuGate.state(this)
            if (gate != ShizukuGate.State.READY) {
                heroSubline.text =
                    UiStrings.format(R.string.shizuku_not_ready_hint, ShizukuGate.localizedGuidanceOf(gate).title) +
                    UiStrings
                        .text(R.string.screen_044)
            }
        }
        maybeAskIccoaPin()
        updateConnectedSubline()
        updateHeroStats()
        updateAudioTag()
        updateReadyRow()
        updateShizukuChip()
    }

    /**
     * Hero card stats row (only while connected): runtime resolution + actual/target frame rate.
     * Actual frame rate = 1s poll diff of the sent-frame counter; align the baseline first when a new session resets the counter to zero.
     */
    private fun updateHeroStats() {
        if (heroState != HeroState.CONNECTED) return
        val carLife = io.github.ezn24.droidcar.carlife.CarLifeController
        val iccoa = io.github.ezn24.droidcar.iccoa.IccoaController
        val onCarPlay = SessionController.videoSender?.isRunning == true
        val onCarLife = carLife.streaming && !onCarPlay
        val onIccoa = iccoa.streaming && !onCarPlay && !onCarLife
        val aa = io.github.ezn24.droidcar.aa.AndroidAutoController
        val onAa = aa.streaming && !onCarPlay && !onCarLife && !onIccoa
        val sent =
            when {
                onCarLife -> carLife.framesSent
                onIccoa -> iccoa.framesSent
                onAa -> aa.framesSent
                else -> SessionController.videoSender?.framesSentTotal ?: 0
            }
        if (sent < lastSentFrames) lastSentFrames = sent
        val fps = sent - lastSentFrames
        lastSentFrames = sent
        heroStats.text =
            if (onCarLife || onIccoa || onAa) {
                val target =
                    when {
                        onCarLife -> carLife.targetFps
                        onIccoa -> iccoa.targetFps
                        else -> aa.targetFps
                    }
                UiStrings.format(R.string.stream_stats, 100, fps, target)
            } else {
                UiStrings.format(R.string.stream_stats, SessionController.activeScalePercent, fps, SessionController.activeTargetFps)
            }
    }

    private fun applyHeroState(s: HeroState) {
        signalWaves.setState(
            when (s) {
                HeroState.IDLE -> SignalWavesView.State.IDLE
                HeroState.CONNECTING -> SignalWavesView.State.CONNECTING
                HeroState.CONNECTED -> SignalWavesView.State.CONNECTED
            },
        )
        val (plateColor, carColor) =
            when (s) {
                HeroState.IDLE -> getColor(R.color.m3_surface_high) to getColor(R.color.m3_on_var)
                HeroState.CONNECTING ->
                    getColor(R.color.m3_pri_container) to getColor(R.color.m3_on_pri_container)
                HeroState.CONNECTED -> getColor(R.color.m3_primary) to getColor(R.color.m3_on_primary)
            }
        hubPlate.background.mutate().setTint(plateColor)
        hubCar.setColorFilter(carColor)
        hubCaption.setTextColor(
            getColor(if (s == HeroState.IDLE) R.color.m3_mono_dim else R.color.m3_hub_caption),
        )

        hubBlink?.cancel()
        hubBlink = null
        hubCar.alpha = 1f
        if (s == HeroState.CONNECTING) {
            hubBlink =
                ObjectAnimator.ofFloat(hubCar, View.ALPHA, 1f, 0.45f).apply {
                    duration = 1_200L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            if (!uiResumed || ScreenDimmer.isCovering()) hubBlink?.pause()
        }

        heroStateText.text =
            when (s) {
                HeroState.IDLE ->
                    UiStrings
                        .text(R.string.ui_005)
                HeroState.CONNECTING ->
                    UiStrings
                        .text(R.string.screen_045)
                HeroState.CONNECTED ->
                    UiStrings
                        .text(R.string.screen_046)
            }
        heroProgress.visibility = if (s == HeroState.CONNECTING) View.VISIBLE else View.GONE
        heroStats.visibility = if (s == HeroState.CONNECTED) View.VISIBLE else View.GONE

        when (s) {
            HeroState.IDLE -> {
                val pending = pendingResidualDisplays
                heroSubline.text =
                    if (pending != null) {
                        UiStrings.format(R.string.leftover_displays, pending.size)
                    } else {
                        UiStrings
                            .text(R.string.ui_007)
                    }
                styleCta(
                    R.drawable.bg_cta_fill,
                    R.color.m3_on_primary,
                    R.drawable.ic_play,
                    UiStrings
                        .text(R.string.ui_010),
                )
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTING -> {
                heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
                styleCta(
                    R.drawable.bg_cta_fill,
                    R.color.m3_on_primary,
                    R.drawable.ic_close,
                    UiStrings
                        .text(R.string.screen_013),
                )
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTED -> {
                styleCta(
                    R.drawable.bg_cta_outline_err,
                    R.color.m3_error,
                    R.drawable.ic_power,
                    UiStrings
                        .text(R.string.screen_047),
                )
                audioTag.visibility = View.VISIBLE
            }
        }

        ctaIcon.rotation = 0f
    }

    /** Uniform style of the hero card primary-action button (the three states differ only in style/icon/text). */
    private fun styleCta(
        bgRes: Int,
        colorRes: Int,
        iconRes: Int,
        label: String,
    ) {
        ctaButton.setBackgroundResource(bgRes)
        ctaLabel.setTextColor(getColor(colorRes))
        ctaIcon.imageTintList =
            android.content.res.ColorStateList
                .valueOf(getColor(colorRes))
        ctaIcon.setImageResource(iconRes)
        ctaLabel.text = label
        ctaButton.isEnabled = true
    }

    private var iccoaPinDialog: androidx.appcompat.app.AlertDialog? = null

    /** An ICCOA head unit shows a 6-digit code: ask for it once per request (polled with the hero card). */
    private fun maybeAskIccoaPin() {
        val iccoa = io.github.ezn24.droidcar.iccoa.IccoaController
        if (!iccoa.pinRequested) {
            if (iccoaPinDialog?.isShowing == true) iccoaPinDialog?.dismiss()
            iccoaPinDialog = null
            return
        }
        if (iccoaPinDialog?.isShowing == true || !uiResumed) return
        val field =
            android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                hint = "000000"
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                letterSpacing = 0.3f
            }
        iccoaPinDialog =
            DarkMessageDialog.show(
                activity = this,
                title = getString(R.string.iccoa_pin_title),
                message = iccoa.status?.takeIf { it.isNotBlank() } ?: getString(R.string.iccoa_msg_enter_code),
                extra = field,
                cancelable = false,
                actions =
                    listOf(
                        DarkMessageDialog.Action(getString(R.string.iccoa_pin_cancel)) { iccoa.cancelPin() },
                        DarkMessageDialog.Action(getString(R.string.iccoa_pin_connect), DarkMessageDialog.Emphasis.PRIMARY) {
                            val pin = field.text.toString().trim()
                            if (pin.length == 6) iccoa.submitPin(pin) else iccoa.cancelPin()
                        },
                    ),
            )
        field.requestFocus()
    }

    private fun updateConnectedSubline() {
        if (heroState != HeroState.CONNECTED) return
        val elapsed = SystemClock.elapsedRealtime() - connectedAt
        val mm = elapsed / 60_000
        val ss = elapsed % 60_000 / 1_000
        // CarLife has no car hotspot name to show: name the protocol instead
        val onCarPlay = SessionController.videoSender?.isRunning == true
        val ssid =
            if (!onCarPlay && io.github.ezn24.droidcar.carlife.CarLifeController.streaming) {
                "Baidu CarLife"
            } else if (!onCarPlay && io.github.ezn24.droidcar.iccoa.IccoaController.streaming) {
                "ICCOA CarLink"
            } else if (!onCarPlay && io.github.ezn24.droidcar.aa.AndroidAutoController.streaming) {
                "Android Auto"
            } else {
                ssidInput.text
                    .toString()
                    .trim()
                    .ifEmpty {
                        UiStrings
                            .text(R.string.ui_020)
                    }
            }
        heroSubline.text =
            String.format(
                Locale.US,
                UiStrings
                    .text(R.string.screen_048),
                ssid,
                mm,
                ss,
            )
    }

    private fun updateAudioTag() {
        if (heroState != HeroState.CONNECTED) return
        val on =
            SessionController.audioEnabledForSession &&
                SessionController.audioSender?.isRunning == true
        val icon =
            ContextCompat.getDrawable(
                this,
                if (on) R.drawable.ic_music_small else R.drawable.ic_volume_off_small,
            )!!
        DrawableCompat.setTint(icon.mutate(), getColor(if (on) R.color.m3_ok else R.color.m3_outline))
        audioTag.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        audioTag.text =
            if (on) {
                UiStrings.text(
                    R.string.ui_011,
                )
            } else {
                UiStrings
                    .text(R.string.screen_049)
            }
    }

    /**
     * Connection prerequisite count: Shizuku / hotspot SSID / Bluetooth pairing / screen-capture permission.
     *
     * Shizuku is a hard prerequisite (connection is blocked when it is not ready), so it counts toward the total and renders its own status row.
     */
    private fun updateReadyRow() {
        var ready = 0
        val shizukuState = ShizukuGate.state(this)
        val shizukuReady = shizukuState == ShizukuGate.State.READY
        if (shizukuReady) ready++
        updateShizukuRow(shizukuState)
        if (ssidInput.text
                .toString()
                .trim()
                .isNotEmpty()
        ) {
            ready++
        }
        val btGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        var bondedCount = -1
        if (btGranted) {
            try {
                val adapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                bondedCount = adapter?.bondedDevices?.size ?: -1
            } catch (_: SecurityException) {
                bondedCount = -1
            }
        }
        if (bondedCount > 0) ready++

        btBadge.visibility = if (bondedCount >= 0) View.VISIBLE else View.GONE
        if (bondedCount >= 0) {
            val target = TargetDevicePrefs.get(this)
            if (bondedCount > 0) {
                btBadge.background.mutate().setTint(getColor(R.color.m3_ok_dot))
                btBadge.text =
                    UiStrings
                        .text(R.string.ui_014)
                btDesc.text =
                    if (target != null) {
                        UiStrings.format(R.string.target_device_only, target.name)
                    } else if (bondedCount > 1) {
                        UiStrings.format(R.string.paired_devices_auto, bondedCount)
                    } else {
                        UiStrings.format(R.string.paired_devices, bondedCount)
                    }
            } else {
                btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                btBadge.text =
                    UiStrings
                        .text(R.string.screen_050)
                btDesc.text =
                    UiStrings
                        .text(R.string.screen_051)
            }
            // Prompt to re-pick when the target device is no longer in the paired list (e.g. the pairing was deleted in system settings)
            if (target != null && bondedCount > 0) {
                val stillBonded =
                    try {
                        (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                            .adapter
                            ?.bondedDevices
                            ?.any { TargetDevicePrefs.matches(target, it.address) } == true
                    } catch (_: SecurityException) {
                        true
                    }
                if (!stillBonded) {
                    btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                    btBadge.text =
                        UiStrings
                            .text(R.string.screen_052)
                    btDesc.text = UiStrings.format(R.string.target_removed, target.name)
                }
            }
            // The dialog can scan and pair unpaired devices directly, so it is no longer shown only when "already paired":
            // with zero paired devices this is exactly the user's entry point.
            btnPickTarget.visibility = View.VISIBLE
        } else {
            btnPickTarget.visibility = View.GONE
        }
        val projectionReady =
            CarPlayService.mediaProjection != null
        if (projectionReady) {
            btnAudio.text =
                UiStrings
                    .text(R.string.screen_053)
            btnAudio.setTextColor(getColor(R.color.m3_ok))
            audioDesc.text =
                UiStrings
                    .text(R.string.screen_054)
        } else {
            btnAudio.text =
                UiStrings
                    .text(R.string.ui_026)
            btnAudio.setTextColor(getColor(R.color.m3_primary))
            audioDesc.text =
                UiStrings
                    .text(R.string.ui_025)
        }
        // Shizuku, Bluetooth pairing, head-unit hotspot (audio needs no grant of its own any more)
        readyCount.text = "$ready/3"
    }

    /**
     * Shizuku status row in "connection prerequisites": the state is the source of truth (read once per reconcile).
     * When not ready, tapping the whole row shows the guidance dialog - the same text seen when "start connection" is blocked.
     */
    private fun updateShizukuRow(state: ShizukuGate.State) {
        val ready = state == ShizukuGate.State.READY
        shizukuBadge.background.mutate().setTint(
            getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
        )
        shizukuBadge.setTextColor(getColor(if (ready) R.color.m3_ok else R.color.m3_on_sec_container))
        shizukuBadge.text =
            if (ready) {
                UiStrings.text(
                    R.string.ui_014,
                )
            } else {
                UiStrings
                    .text(R.string.screen_055)
            }
        shizukuDesc.text =
            when (state) {
                ShizukuGate.State.READY ->
                    UiStrings.text(
                        R.string.screen_056,
                    )
                ShizukuGate.State.NOT_RUNNING ->
                    UiStrings.text(
                        R.string.screen_057,
                    )
                ShizukuGate.State.UNAUTHORIZED ->
                    UiStrings.text(
                        R.string.screen_058,
                    )
                ShizukuGate.State.NOT_INSTALLED ->
                    UiStrings.text(
                        R.string.screen_059,
                    )
            }
    }

    // ---- Target head-unit picker dialog (scan/pairing state) ----

    /** The currently open dialog (scan callbacks add rows to it; closing cleans up uniformly). */
    private var targetPicker: androidx.appcompat.app.AlertDialog? = null

    private var targetPickerReceiver: BroadcastReceiver? = null

    /** Scan results already shown in the dialog (deduplicated by address; paired devices go into the upper group, not here). */
    private val scannedTargets = LinkedHashMap<String, TargetPickerEntry>()

    /** Addresses of devices for which "Pair" was tapped during the scan (auto-selected as target once pairing completes). */
    private var pendingBondAddress: String? = null

    private data class TargetPickerEntry(
        val address: String,
        val name: String,
        val rssi: Int,
        val bonded: Boolean,
    )

    /** Set when the scan permission was denied: the grant callback continues (the dialog may already be closed, then it is ignored). */
    private var pendingTargetScan = false

    private val targetScanPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BT-SCAN: BLUETOOTH_SCAN permission: $granted")
            if (granted && pendingTargetScan) {
                pendingTargetScan = false
                targetPicker?.let { startTargetScan(it) }
            } else if (!granted) {
                pendingTargetScan = false
                targetPicker?.let { _ ->
                    updatePickerStatus(
                        UiStrings
                            .text(R.string.screen_060),
                        warn = true,
                    )
                    pickerToast(
                        UiStrings
                            .text(R.string.screen_061),
                    )
                }
            }
        }

    /**
     * Target head-unit picker dialog (reworked 2026-10-18):
     *  group 1 "Paired" - pick directly (wireless CarPlay on the head unit requires system-level Bluetooth pairing);
     *  group 2 "Nearby devices" - live scan (classic Bluetooth discovery); tapping an unpaired device starts
     *  system pairing, and on success it is set as the target automatically.
     *
     * The first row "Auto (unspecified)" keeps the original semantics: outbound tries each in turn, inbound accepts anyone;
     * once a target is chosen, outbound connects only to it and inbound rejects others (prevents connecting to another head unit at home).
     * The choice takes effect immediately (the link reads the target dynamically, no reconnect needed).
     */
    private fun showTargetPicker() {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            snack(
                UiStrings
                    .text(R.string.screen_062),
            )
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_target_picker, null)
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setView(view)
                .create()
        targetPicker = dialog
        scannedTargets.clear()
        pendingBondAddress = null
        view.findViewById<TextView>(R.id.btnCancelPicker).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnRescan).setOnClickListener { startTargetScan(dialog) }
        renderBondedGroup(view)
        dialog.setOnDismissListener {
            stopTargetScan()
            targetPicker = null
            pendingBondAddress = null
            updateReadyRow()
        }
        // The panel has its own rounded corners: the window background must be transparent, otherwise square corners show through.
        // Set before show(): doing it in the show listener drew one frame off-centre at the default width first
        dialog.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        startTargetScan(dialog)
    }

    /** Render the "Paired" group: the auto row + every paired device. */
    private fun renderBondedGroup(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.bondedList)
        val empty = view.findViewById<TextView>(R.id.bondedEmpty)
        list.removeAllViews()
        val current = TargetDevicePrefs.get(this)
        // "Auto (unspecified)" is a mode, not a device: placed above both groups, not mixed into "Paired"
        view.findViewById<LinearLayout>(R.id.autoRowContainer).apply {
            removeAllViews()
            addView(
                inflateTargetRow(
                    name =
                        UiStrings
                            .text(R.string.screen_063),
                    address =
                        UiStrings
                            .text(R.string.screen_064),
                    action =
                        if (current ==
                            null
                        ) {
                            UiStrings
                                .text(R.string.screen_065)
                        } else {
                            UiStrings
                                .text(R.string.ui_110)
                        },
                    highlighted = current == null,
                    iconTint = R.color.m3_outline,
                ) {
                    TargetDevicePrefs.set(this@MainActivity, null)
                    log("TARGET: target pin cleared - outbound tries each paired device, inbound accepts any")
                    // Close the dialog before the notice (the snackbar lives in the Activity window and would be covered by the dialog)
                    targetPicker?.dismiss()
                    snack(
                        UiStrings
                            .text(R.string.screen_066),
                    )
                },
            )
        }
        val bonded = bondedDevices()
        empty.visibility = if (bonded.isEmpty()) View.VISIBLE else View.GONE
        bonded.forEach { device ->
            val address = device.address
            val name = deviceDisplayName(device)
            val isCurrent = current != null && TargetDevicePrefs.matches(current, address)
            list.addView(
                inflateTargetRow(
                    name = name,
                    address = address,
                    action =
                        if (isCurrent) {
                            UiStrings.text(
                                R.string.screen_065,
                            )
                        } else {
                            UiStrings
                                .text(R.string.ui_110)
                        },
                    highlighted = isCurrent,
                    iconTint = R.color.m3_primary,
                ) {
                    setTarget(device, name, closePicker = true)
                },
            )
        }
    }

    /** A single device row (shared by both groups; the name uses the primary color when highlighted). */
    private fun inflateTargetRow(
        name: String,
        address: String,
        action: String,
        highlighted: Boolean,
        iconTint: Int,
        onClick: () -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_target_device, null)
        row.background =
            ContextCompat.getDrawable(
                this@MainActivity,
                if (highlighted) R.drawable.bg_bt_device_row_current else R.drawable.bg_bt_device_row,
            )
        row.findViewById<TextView>(R.id.devName).apply {
            text = name
            setTextColor(getColor(if (highlighted) R.color.m3_primary else R.color.m3_on_surface))
        }
        row.findViewById<TextView>(R.id.devAddr).text = address
        row.findViewById<TextView>(R.id.devAction).apply {
            // "Current" = solid green pill + check mark + dark text; "Select/Pair" = outlined pill + cyan text.
            // Hue alone is not enough (cyan #7EDCE6 vs green #7BD8A5 are hard to tell apart at small sizes), so shape differs too.
            if (highlighted) {
                text = "✓ $action"
                setTextColor(getColor(R.color.m3_surface))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_current)
            } else {
                text = action
                setTextColor(getColor(R.color.m3_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tbtn)
            }
            // After switching solid/outlined, padding is decided by the drawable and needs no reset
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        }
        row.findViewById<ImageView>(R.id.devIcon).setColorFilter(getColor(iconTint))
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * Start scanning nearby devices: register ACTION_FOUND / ACTION_BOND_STATE_CHANGED, then startDiscovery.
     * Results are deduplicated by address and exclude paired devices (they are in the group above); tapping "Pair" calls createBond().
     */
    private fun startTargetScan(dialog: androidx.appcompat.app.AlertDialog) {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            } ?: return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingTargetScan = true
            updatePickerStatus(
                UiStrings
                    .text(R.string.screen_067),
                warn = true,
            )
            targetScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        val scannedList = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // Set of paired addresses: drop them from scan results (so one device does not appear in two places)
        val bondedAddresses =
            bondedDevices()
                .map { it.address }
                .toSet()
        scannedTargets.clear()
        scannedList.removeAllViews()
        updatePickerStatus(
            UiStrings
                .text(R.string.ui_080),
        )
        stopTargetScan()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                            onTargetFound(dialog, device, rssi, bondedAddresses)
                        }

                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                            onBondStateChanged(dialog, device, state)
                        }
                    }
                }
            }
        targetPickerReceiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
        // Broadcasts sent by the system (Bluetooth stack): must be EXPORTED to be received on some ROMs
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val started =
            try {
                adapter.startDiscovery()
            } catch (_: SecurityException) {
                false
            }
        if (!started) {
            updatePickerStatus(
                UiStrings
                    .text(R.string.screen_068),
                warn = true,
            )
        } else {
            log("BT-SCAN: started scanning for nearby Bluetooth devices")
        }
    }

    /** A device was scanned: paired -> ignore; new device -> add a row (tapping "Pair" starts system pairing). */
    private fun onTargetFound(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        rssi: Int,
        bondedAddresses: Set<String>,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address in bondedAddresses || scannedTargets.containsKey(address)) return
        val name = deviceDisplayName(device)
        scannedTargets[address] = TargetPickerEntry(address, name, rssi, bonded = false)
        val list = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // Scan still running: keep the status row with a count so the user knows they can keep waiting for other devices.
        // Deliberately not via updatePickerStatus - it would scroll the list to the status row and steal the scroll position when a new device appears.
        dialog.findViewById<TextView>(R.id.scanStatus)?.apply {
            text = UiStrings.format(R.string.scanned_devices, scannedTargets.size)
            setTextColor(getColor(R.color.m3_mono_dim))
            visibility = View.VISIBLE
        }
        list.addView(
            inflateTargetRow(
                name = name,
                address = "$address · ${rssi}dBm",
                action =
                    UiStrings
                        .text(R.string.screen_069),
                highlighted = false,
                iconTint = R.color.m3_amber,
            ) {
                beginBond(device)
            },
        )
    }

    /** Tap on an unpaired device: stop the scan first (pairing during a scan tends to fail), then createBond(). */
    private fun beginBond(device: BluetoothDevice) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        stopDiscoveryOnly()
        pendingBondAddress = address
        val name = deviceDisplayName(device)
        if (AdapterMode.looksLikeAdapter(name)) {
            SessionController.listenForAdapterPairing(applicationContext)
        }
        val ok =
            try {
                device.createBond()
            } catch (error: SecurityException) {
                log("BT-PAIR: pairing \"$name\" failed - missing permission: ${error.message}")
                false
            }
        if (ok) {
            log("BT-PAIR: pairing with \"$name\" ($address) started - confirm in the system dialog")
            pickerNotice(UiStrings.format(R.string.pairing_device, name))
        } else {
            pendingBondAddress = null
            log("BT-PAIR: pairing \"$name\" ($address) failed to start (device may already be paired or Bluetooth is busy)")
            pickerNotice(
                UiStrings
                    .text(R.string.screen_070),
            )
        }
    }

    /**
     * Notice while the dialog is open.
     *
     * The dialog is a separate window (higher layer + full-screen scrim), so a snackbar in the Activity is always covered
     * - the user only sees a dimmed strip at the bottom (reported 2026-10-18). A system Toast sits in a higher window layer
     * and floats above the dialog; the status row inside the dialog is also updated (scrolled into view) as a lasting explanation.
     */
    private fun pickerNotice(message: String) {
        pickerToast(message)
        updatePickerStatus(message, warn = true)
    }

    /**
     * System Toast while the dialog is open. A system Toast sits in a higher window layer and floats above the dialog;
     * an Activity snackbar is fully covered by the dialog (separate window + scrim).
     */
    private fun pickerToast(message: String) {
        android.widget.Toast
            .makeText(this, message, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** Update the status row in the dialog and scroll it into view (`scanStatus` sits under the "Nearby devices" heading). */
    private fun updatePickerStatus(
        message: String,
        warn: Boolean = false,
    ) {
        val dialog = targetPicker ?: return
        val status = dialog.findViewById<TextView>(R.id.scanStatus) ?: return
        status.text = message
        status.setTextColor(getColor(if (warn) R.color.m3_amber else R.color.m3_mono_dim))
        status.visibility = View.VISIBLE
        // Scroll only when the status row is out of the viewport (a user browsing the paired list above must not lose their position).
        status.post {
            val scroll = dialog.findViewById<android.widget.ScrollView>(R.id.pickerScroll) ?: return@post
            val content = scroll.getChildAt(0) ?: return@post
            val top = offsetWithin(status, content)
            val bottom = top + status.height
            val visibleTop = scroll.scrollY
            val visibleBottom = visibleTop + scroll.height
            if (top < visibleTop || bottom > visibleBottom) {
                val y = top - (12 * resources.displayMetrics.density).toInt()
                scroll.smoothScrollTo(0, y.coerceAtLeast(0))
            }
        }
    }

    /** Content offset of view relative to ancestor (sum of top along the parent chain; ancestor must be an ancestor of view). */
    private fun offsetWithin(
        view: View,
        ancestor: View,
    ): Int {
        var current: View? = view
        var y = 0
        while (current != null && current !== ancestor) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    /** Pairing state change: on success set it as the target and close the dialog; on failure show a notice. */
    private fun onBondStateChanged(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        state: Int,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address != pendingBondAddress) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                pendingBondAddress = null
                val name = deviceDisplayName(device)
                log("BT-PAIR: ✓ paired with \"$name\" ($address) - set as target automatically")
                // Close the dialog before the notice: the snackbar lives in the Activity window and is covered while the dialog is open
                dialog.dismiss()
                setTarget(device, name)
                if (AdapterMode.looksLikeAdapter(name)) {
                    // Wireless CarPlay adapters connect to the phone themselves; dropping their
                    // profiles right after pairing would only get in the way.
                    log("BT-PAIR: \"$name\" looks like a wireless CarPlay adapter - tap \"Connect\" and wait for it to connect")
                    return
                }
                // "Pair only, do not connect": after pairing the system/head unit often sets up HFP/A2DP
                // by itself, and on Audi this "connected" state makes the head unit refuse CarPlay. Disconnect once immediately and
                // once after 1.5s (the head unit's reconnect usually happens between those two moments).
                val app = applicationContext
                Bg.run("bt-pair-only") {
                    repeat(2) { attempt ->
                        val dropped = BtProfiles.disconnect(app, device)
                        if (dropped.isNotEmpty()) {
                            log(
                                "BT-PAIR: dropped ${dropped.joinToString("/")} connection(s) auto-established after pairing" +
                                    UiStrings
                                        .text(R.string.screen_071),
                            )
                        }
                        if (attempt == 0) {
                            try {
                                Thread.sleep(1_500)
                            } catch (_: InterruptedException) {
                                return@run
                            }
                        }
                    }
                }
            }

            BluetoothDevice.BOND_NONE -> {
                pendingBondAddress = null
                log("BT-PAIR: pairing with $address not completed (user cancelled or failed)")
                // Dialog still open: use a Toast (floats above the dialog) + the status row in the dialog, not the covered snackbar
                pickerNotice(
                    UiStrings
                        .text(R.string.screen_072),
                )
            }
            // BOND_BONDING: keep waiting (system confirmation not yet answered)
        }
    }

    /**
     * Set as the outbound target; an attempt in progress switches on the next attempt / next inbound connection.
     *
     * @param closePicker true = close the device picker dialog before the notice (the snackbar belongs to the Activity window
     *   and is always covered while the dialog is open - see the pickerNotice comment).
     */
    private fun setTarget(
        device: BluetoothDevice,
        name: String,
        closePicker: Boolean = false,
    ) {
        if (closePicker) targetPicker?.dismiss()
        val previous = TargetDevicePrefs.get(this)
        TargetDevicePrefs.set(this, TargetDevicePrefs.Target(device.address, name))
        log(
            "TARGET: target head unit set to \"$name\" (${device.address})" +
                UiStrings
                    .text(R.string.screen_073) +
                if (previous != null && previous.address != device.address) {
                    "(previous target \"${previous.name}\")"
                } else {
                    ""
                },
        )
        snack(UiStrings.format(R.string.target_selected, name))
        updateReadyRow()
    }

    /** Stop scanning and unregister the listener (called when the dialog closes / rescans). */
    private fun stopTargetScan() {
        stopDiscoveryOnly()
        targetPickerReceiver?.let {
            runCatching { unregisterReceiver(it) }
            targetPickerReceiver = null
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopDiscoveryOnly() {
        // Every call site has already confirmed BLUETOOTH_SCAN is granted (scan-flow entry), and exceptions are caught as a whole here
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun bondedDevices(): List<BluetoothDevice> =
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.bondedDevices
                ?.toList()
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    /** Device display name (falls back to the address when there is no name); reading it needs BLUETOOTH_CONNECT, the address is used on exception. */
    private fun deviceDisplayName(device: BluetoothDevice): String =
        try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            try {
                device.address
            } catch (_: SecurityException) {
                UiStrings
                    .text(R.string.screen_074)
            }
        }

    /** Device marketing name (ro.product.marketname), falling back to Build.MODEL when unavailable. */
    private fun deviceName(): String =
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            (get.invoke(null, "ro.product.marketname") as? String)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (_: Exception) {
            Build.MODEL
        }

    private fun updateShizukuChip() {
        val ready =
            ShizukuGate
                .state(this) ==
                ShizukuGate.State.READY
        shizukuChip.text =
            if (ready) {
                UiStrings.text(
                    R.string.ui_003,
                )
            } else {
                UiStrings
                    .text(R.string.screen_075)
            }
        shizukuChip.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
        // Subtitle matches the prototype: device name · Shizuku state (dot color follows the state)
        deviceSubtitle.text =
            UiStrings.format(
                R.string.device_status,
                deviceName(),
                if (ready) UiStrings.text(R.string.shizuku_ready_label) else UiStrings.text(R.string.shizuku_unavailable_label),
            )
        deviceSubtitle.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
    }

    // ==================== Common UI helpers ====================

    private fun log(message: String) {
        // Write into the process-wide buffer (logcat + file + attached rendering are all handled by SessionController)
        SessionController.appendLog(message)
    }

    /** Shell query/execute (background threads only; shared by both diagnostic panels, do not write local variants again). */
    private fun shExec(
        cmd: String,
        timeoutMs: Long = 4_000L,
    ): Shell.Result =
        Shell
            .exec(cmd, timeoutMs)

    private fun sh(cmd: String): String = shExec(cmd).output

    /**
     * Export logs to "Downloads/DroidCar/" and tell the user the location in a dialog.
     *
     * By default only logs since this process started are exported (earlier rounds are noise for diagnosis, often hundreds of KB);
     * long-pressing the export button exports the whole history ([LogExporter.Scope.ALL]).
     * Why a dialog and not a snack: a snack floats at the bottom for only 2.4s, and after tapping export the user is
     * usually looking at the log area and misses it; failures also need clear feedback (writing the file can fail
     * when storage is full or the media store misbehaves).
     */
    private fun exportLogFile(scope: LogExporter.Scope = LogExporter.Scope.CURRENT_SESSION) {
        snack(
            if (scope ==
                LogExporter.Scope.ALL
            ) {
                UiStrings
                    .text(R.string.screen_076)
            } else {
                UiStrings
                    .text(R.string.screen_077)
            },
        )
        val fallback = SessionController.logText()
        Bg.run("export-log") {
            val result = LogExporter.export(applicationContext, fallback, scope)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    snack(
                        UiStrings
                            .text(R.string.screen_078),
                        4_000L,
                    )
                    return@runOnUiThread
                }
                log("Log exported (${scope.label}): ${result.displayPath} (${result.bytes} bytes)")
                val scopeNote =
                    if (scope == LogExporter.Scope.CURRENT_SESSION) {
                        UiStrings
                            .text(R.string.screen_079)
                    } else {
                        UiStrings
                            .text(R.string.screen_080)
                    }
                DarkMessageDialog.show(
                    this,
                    title =
                        UiStrings
                            .text(R.string.screen_081),
                    message =
                        "文件位置：\n${result.displayPath}\n\n" +
                            "大小：${formatBytes(result.bytes)}\n$scopeNote\n\n" +
                            UiStrings
                                .text(R.string.screen_082),
                    actions =
                        listOf(
                            DarkMessageDialog.Action(
                                UiStrings
                                    .text(R.string.screen_083),
                                DarkMessageDialog.Emphasis.PRIMARY,
                            ) {
                                openExportedFile(result.uri, result.displayPath)
                            },
                            DarkMessageDialog.Action(
                                UiStrings
                                    .text(R.string.screen_084),
                            ) {
                                openExportFolder(result.displayPath)
                            },
                            DarkMessageDialog.Action(
                                UiStrings
                                    .text(R.string.screen_085),
                            ) {
                                shareExportedFile(result.uri)
                            },
                            DarkMessageDialog.Action(
                                UiStrings
                                    .text(R.string.ui_108),
                                onClick = {},
                            ),
                        ),
                )
            }
        }
    }

    /** Human-readable size: <1KB shows bytes (integer division would show a misleading "0 KB"). */
    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> UiStrings.format(R.string.byte_count, bytes)
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }

    /**
     * Open the system file manager at the export directory (DocumentsUI document URIs can reach a subdirectory directly).
     *
     * Why an explicit component first: on Honor ROMs an implicit ACTION_VIEW + directory is mismatched by the resolver
     * (2026-10-18: it popped up a music app as the default); the same intent via
     * `am start` lands correctly in DocumentsUI. Naming FilesActivity explicitly skips the resolver,
     * and failure falls back step by step (other ROMs' component names may differ -> implicit -> external storage root -> text instructions).
     */
    private fun openExportFolder(displayPath: String) {
        val folder = displayPath.substringBeforeLast('/')
        val relative = folder.removePrefix("/storage/emulated/0/")
        val dirUri =
            Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3A" + Uri.encode(relative).replace("/", "%2F"),
            )
        val explicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                component =
                    android.content.ComponentName(
                        "com.android.documentsui",
                        "com.android.documentsui.files.FilesActivity",
                    )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val implicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/directory",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        for (intent in listOf(explicit, implicit, storageRoot)) {
            if (runCatching { startActivity(intent) }.isSuccess) {
                if (intent !== explicit) snack("请按路径打开：$folder", 5_000L)
                return
            }
        }
        snack(
            UiStrings
                .text(R.string.screen_086),
            5_000L,
        )
    }

    /** Open with the system default app (most ROMs land on the file manager / a text viewer preview). */
    private fun openExportedFile(
        uri: Uri,
        displayPath: String,
    ) {
        val view =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(view) }.isSuccess) return
        // Without a text viewer, fall back to the file manager: DocumentsUI's internal storage root (the user follows the path into Downloads/DroidCar)
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(storageRoot) }.isSuccess) {
            snack("请按路径打开：$displayPath", 5_000L)
            return
        }
        snack(
            UiStrings
                .text(R.string.screen_087),
            5_000L,
        )
    }

    /** Share the log file (WeChat/QQ/mail etc.), the content URI carries a temporary read grant. */
    private fun shareExportedFile(uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching {
            startActivity(
                Intent.createChooser(
                    send,
                    UiStrings
                        .text(R.string.screen_088),
                ),
            )
        }.onFailure {
            snack(
                UiStrings
                    .text(R.string.screen_089),
            )
        }
    }

    private fun updateLogBottomButton() {
        findViewById<ImageView>(R.id.btnLogBottom)?.visibility =
            if (consoleAtBottom()) View.INVISIBLE else View.VISIBLE
    }

    /** Maximum scroll offset of the log content. */
    private fun consoleMaxScroll(): Int {
        val layout = logText.layout ?: return 0
        return (
            layout.getLineBottom(layout.lineCount - 1) +
                logText.paddingBottom - logText.height
        ).coerceAtLeast(0)
    }

    /** Whether already at the bottom (40px tolerance: absorbs line-height rounding, which would otherwise leave it a few pixels short forever). */
    private fun consoleAtBottom(): Boolean = consoleMaxScroll() - logText.scrollY <= 40

    private fun scrollConsoleToBottom() {
        // +16px overshoot to cover rounding (dark console background, overshoot is invisible; View does not clamp it but it is visually harmless)
        logText.scrollTo(0, consoleMaxScroll() + 16)
        logText.post { updateLogBottomButton() }
    }

    private fun renderConsole(text: String) {
        // Whether it was at the bottom before the text change (or the user is scrolled up reading): decides whether this update auto-follows
        val follow = consoleAtBottom()
        logText.text =
            if (SessionController.logEnabled) {
                text
            } else {
                UiStrings.text(
                    R.string.screen_090,
                )
            }
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    /**
     * Append one log line incrementally (SessionController pushes line by line; previously every line did a full toString + setText
     * of the whole text, amplifying UI/GC cost under high-frequency logging). When over the limit, trim by reconciling once from the process buffer.
     */
    private fun appendConsole(line: String) {
        val follow = consoleAtBottom()
        if (logText.text.length > 44_000) {
            renderConsole(SessionController.logText())
            return
        }
        logText.append(line)
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    private var snackRunnable: Runnable? = null

    /** Custom-drawn M3 Snackbar (inverse-color pill at the bottom, replaces Toast). */
    private fun snack(
        message: String,
        durationMs: Long = 2_400L,
    ) {
        snackbar.text = message
        snackbar.visibility = View.VISIBLE
        snackbar.translationY = snackbar.height + 60f
        snackbar
            .animate()
            .translationY(0f)
            .setDuration(260L)
            .start()
        snackRunnable?.let { mainHandler.removeCallbacks(it) }
        snackRunnable =
            Runnable {
                snackbar
                    .animate()
                    .translationY(snackbar.height + 60f)
                    .setDuration(260L)
                    .withEndAction { snackbar.visibility = View.GONE }
                    .start()
            }.also { mainHandler.postDelayed(it, durationMs) }
    }

    /**
     * Parse the CarPlay virtual displays in dumpsys display: returns (displayId, name).
     * Note uniqueId looks like virtual:com.android.shell,2000,CarPlayDock,36 - the trailing 36 is a
     * generation counter, not the display ID; the real ID is in the "displayId=39, uniqueId='…'" section.
     */
    private fun parseCarPlayDisplays(dump: String): List<Pair<Int, String>> =
        Regex("displayId=(\\d+), uniqueId='[^']*CarPlay(Dock|AppHi|App|Map)")
            .findAll(dump)
            .map { it.groupValues[1].toInt() to "CarPlay${it.groupValues[2]}" }
            .distinctBy { it.first }
            .toList()

    /** Read the global shell state (background thread + UI backfill). With [startupCheck] also detects residual virtual displays. */
    private fun refreshShellState(startupCheck: Boolean = false) {
        if (startupCheck) {
            startupCheckRunning = true
            tickHero()
        }
        shellStateText.text =
            UiStrings
                .text(R.string.screen_091)
        Bg.run("shell-state") {
            if (ShizukuGate
                    .state(this) !=
                ShizukuGate.State.READY
            ) {
                runOnUiThread {
                    startupCheckRunning = false
                    if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                    shellStateText.text =
                        UiStrings
                            .text(R.string.screen_092)
                }
                return@run
            }

            val letterbox =
                Regex("Aspect ratio:\\s*([0-9.]+)")
                    .find(sh("cmd window get-letterbox-style"))
                    ?.groupValues
                    ?.get(1) ?: "?"
            val forceResizable = sh("settings get global force_resizable_activities").ifBlank { "null" }
            val overlay = sh("settings get global overlay_display_devices").ifBlank { "null" }
            val wmSize = sh("wm size").replace("\n", " / ").ifBlank { "?" }
            val wmDensity = sh("wm density").replace("\n", " / ").ifBlank { "?" }
            val carDisplays = parseCarPlayDisplays(sh("dumpsys display"))
            val displays = carDisplays.joinToString(", ") { "${it.second}(${it.first})" }
            val timeout = sh("settings get system screen_off_timeout").ifBlank { "?" }
            val text =
                buildString {
                    appendLine("letterbox 宽高比: $letterbox  （1.05=会话调优生效；0.0=系统默认）")
                    appendLine("force_resizable: $forceResizable  （平板模式实验；默认 null/0）")
                    appendLine("overlay_display_devices: $overlay  （叠加屏实验残留；应为 null）")
                    appendLine("wm size: $wmSize")
                    appendLine("wm density: $wmDensity")
                    appendLine(
                        UiStrings
                            .text(R.string.screen_093) +
                            displays.trim().replace("\n", ", ").ifEmpty {
                                UiStrings
                                    .text(R.string.screen_094)
                            },
                    )
                    appendLine("screen_off_timeout: $timeout  （App 常亮功能管理）")
                }
            runOnUiThread { shellStateText.text = text.trim() }
            if (startupCheck) {
                var residual = carDisplays.filter { it.first !in currentSessionDisplayIds() }
                if (residual.isNotEmpty()) {
                    log(
                        "SHELL: startup check found ${residual.size} residual virtual displays: " +
                            residual.joinToString(", ") { "${it.second}(${it.first})" },
                    )
                    // Residual displays = the last session ended abnormally (process killed): the end-of-session restore of
                    // landscape overrides did not run either - clean it up together so apps do not stay landscape-overridden on the phone
                    runCatching {
                        val reset =
                            AppOrientationCompat
                                .resetAll(applicationContext)
                        if (reset > 0) log("ORIENTATION: restored $reset landscape override(s) from the residual session")
                    }
                    // Run one automatic cleanup first (including reaping old-version UserService processes - the displays they hold cannot be
                    // released across processes, only process reaping frees them; the old process Shizuku reuses by version after an APK reinstall
                    // is exactly the root cause of "clean up released 0/N", 2026-10-16). Ask the user only about what cannot be cleaned.
                    val released = releaseDisplays(residual.map { it.first })
                    log("SHELL: startup residual auto-cleanup (released $released/${residual.size})")
                    if (released > 0) {
                        residual =
                            parseCarPlayDisplays(sh("dumpsys display"))
                                .filter { it.first !in currentSessionDisplayIds() }
                    }
                }
                runOnUiThread {
                    startupCheckRunning = false
                    if (residual.isNotEmpty()) {
                        // When the user picks "not now", keep pending; the button next to the status can retrigger the cleanup
                        pendingResidualDisplays = residual
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        promptResidualDisplays(residual)
                        // Auto-expand the log card: the two startup-cleanup records are directly visible (the card is collapsed by default)
                        findViewById<View>(R.id.logBody).visibility = View.VISIBLE
                        findViewById<ImageView>(R.id.logChevron)
                            .animate()
                            .rotation(180f)
                            .setDuration(250L)
                            .start()
                    } else if (heroState == HeroState.IDLE) {
                        // No residuals: restore the default "not connected" display
                        applyHeroState(HeroState.IDLE)
                    }
                }
            }

            // Pre-bind UserService: warms up touch injection and triggers reaping of old-version residual processes (the bind callback includes
            // reapStaleUserServices - old processes Shizuku does not reap after an APK reinstall; the virtual displays they hold
            // can only be freed by process reaping). Must come after the residual scan above: reaping makes the old process's displays vanish
            // at once, and if it happened before the scan the residual list could be empty -> resetAll (landscape override restore) would be skipped.
            CarInputClient.ensure(this)
        }
    }

    /** Virtual displays held by the current session (excluded from residual cleanup). */
    private fun currentSessionDisplayIds(): Set<Int> =
        buildSet {
            val launcher = VirtualLauncher
            listOf(
                launcher.dockDisplayId,
                launcher.appDisplayId,
                launcher.appHiDisplayId,
                launcher.mapDisplayId,
                launcher.mediaDisplayId,
                launcher.gridDisplayId,
            ).forEach { if (it >= 0) add(it) }
            addAll(launcher.preservedDisplayIds())
        }

    /** Startup detected residual CarPlay virtual displays: ask whether to clean up now (left over from an abnormal exit of the last session). */
    private fun promptResidualDisplays(residual: List<Pair<Int, String>>) {
        if (isFinishing || isDestroyed) return
        val names = residual.joinToString("\n") { "· ${it.second}（displayId=${it.first}）" }
        DarkMessageDialog.show(
            activity = this,
            title =
                UiStrings
                    .text(R.string.screen_095),
            message =
                "发现 ${residual.size} 个上次会话遗留的 CarPlay 虚拟屏：\n$names\n\n" +
                    UiStrings
                        .text(R.string.screen_096),
            actions =
                listOf(
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_097),
                    ) {},
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_098),
                        DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        pendingResidualDisplays = null
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        Bg.run("residual-cleanup") {
                            val released = releaseDisplays(residual.map { it.first })
                            log("SHELL: residual virtual display cleanup done (released $released/${residual.size})")
                            runOnUiThread {
                                snack(UiStrings.format(R.string.displays_released, released, residual.size))
                                refreshShellState()
                            }
                        }
                    },
                ),
            onCancel = {
                // Back key / tap outside = "not now": keep the hero card's "clean up residual displays" entry
                updateResidualChip()
            },
        )
    }

    /** Show/hide the "clean up residual displays" button next to the hero card status (appears when there is residue to clean). */
    private fun updateResidualChip() {
        btnResidualCleanup.visibility =
            if (pendingResidualDisplays != null) View.VISIBLE else View.GONE
    }

    /** Release the given virtual displays (CarInputClient lazy binding + 3s short wait). Call from a background thread; returns the success count. */
    private fun releaseDisplays(ids: List<Int>): Int {
        if (ids.isEmpty()) return 0
        var svc = CarInputClient.service
        if (svc == null) {
            CarInputClient
                .ensure(this)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val bound = svc ?: return 0
        ids.filterNot { it in currentSessionDisplayIds() }.forEach { runCatching { bound.releaseDisplay(it) } }
        // Displays not released directly are most likely held by an old-version UserService process (releaseDisplay only checks this process's
        // map and cannot release across processes - the known problem that Shizuku does not reap old processes after an APK reinstall).
        // Reap the old process so its displays are freed with it, then re-verify with an actual scan (process reaping is asynchronous).
        CarInputClient.reapStaleUserServices()
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (true) {
            val alive = parseCarPlayDisplays(sh("dumpsys display")).map { it.first }.toSet()
            val gone = ids.count { it !in alive }
            if (gone >= ids.size || SystemClock.uptimeMillis() >= deadline) return gone
            Thread.sleep(200)
        }
    }

    /** One-tap reset of the global shell state to system defaults (includes releasing residual virtual displays, keeping the current session's displays). */
    private fun resetShellState() {
        shellStateText.text =
            UiStrings
                .text(R.string.screen_099)
        Bg.run("shell-reset") {
            if (ShizukuGate
                    .state(this) !=
                ShizukuGate.State.READY
            ) {
                runOnUiThread {
                    shellStateText.text =
                        UiStrings
                            .text(R.string.screen_100)
                }
                return@run
            }

            fun sh(cmd: String) =
                Shell
                    .exec(cmd, 5_000L)
            val steps =
                listOf(
                    UiStrings
                        .text(R.string.screen_101) to
                        "cmd window reset-letterbox-style aspectRatio",
                    UiStrings
                        .text(R.string.screen_102) to
                        "settings delete global overlay_display_devices",
                    "force_resizable" to "settings delete global force_resizable_activities",
                    "wm size" to "wm size reset",
                    "wm density" to "wm density reset",
                )
            val results =
                steps.map { (name, cmd) ->
                    val r = sh(cmd)
                    if (r.ok) "✓ $name" else "✗ $name（${r.output.take(40)}）"
                }
            // Residual virtual display release: keep the current session's displays, release every other CarPlay* display one by one
            val ids =
                parseCarPlayDisplays(sh("dumpsys display").output)
                    .map { it.first }
                    .filter { it !in currentSessionDisplayIds() }
                    .toList()
            val released = releaseDisplays(ids)
            val summary = results.joinToString("; ") + "; residual displays released $released/${ids.size}"
            log("SHELL-RESET: $summary")
            runOnUiThread {
                shellStateText.text =
                    UiStrings
                        .text(R.string.screen_103) +
                    results.joinToString("\n") +
                    "\n残留虚拟屏: 释放 $released/${ids.size}（保留当前会话屏）\n" +
                    UiStrings
                        .text(R.string.screen_104)
                refreshShellState()
            }
        }
    }

    /** Overlay permission request: show an explanatory dialog first, then go to system settings once the user confirms. */
    private fun promptOverlayPermission(reason: String) {
        if (android.provider.Settings.canDrawOverlays(this)) return
        DarkMessageDialog.show(
            activity = this,
            title =
                UiStrings
                    .text(R.string.screen_105),
            message =
                "$reason\n\n" +
                    UiStrings
                        .text(R.string.screen_106) +
                    UiStrings
                        .text(R.string.screen_107),
            actions =
                listOf(
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_097),
                    ) {
                        log("DIMMER: user has not granted overlay permission yet (dim cover unavailable, keep-awake still works)")
                    },
                    DarkMessageDialog.Action(
                        UiStrings
                            .text(R.string.screen_108),
                        DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        try {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                ),
                            )
                        } catch (_: Exception) {
                            log(
                                "DIMMER: cannot open the grant page, please go to Settings > Apps > Special access > Display over other apps manually",
                            )
                        }
                    },
                ),
        )
    }

    /** UI is in the foreground (between onResume and onPause). */
    private var uiResumed = false

    private val dimmerCoverListener: (Boolean) -> Unit = { runOnUiThread { syncPhoneUiActivity() } }

    /**
     * The phone home page animation and the 1s hero-card refresh run only when someone can actually see it: foreground and not covered by the dim cover.
     * While the car is connected the phone is mostly under the dim cover / powered off, and animation and refresh used to keep running (wasting CPU/GPU/battery).
     */
    private fun syncPhoneUiActivity() {
        if (isFinishing || isDestroyed) return
        val covered = ScreenDimmer.isCovering()
        val active = uiResumed && !covered
        mainHandler.removeCallbacks(heroTick)
        if (active) mainHandler.post(heroTick)
        // The wave animation runs only while the "Connect" page is visible (the hero card is GONE on other pages)
        val heroVisible = active && currentTab == TAB_CONNECT
        if (::signalWaves.isInitialized) signalWaves.paused = !heroVisible
        hubBlink?.let { if (heroVisible) it.resume() else it.pause() }
        applySystemBars(covered)
    }

    /**
     * System bars normally match the page color (light/dark follows the phone); switch back to pure black while the dim cover is up - the cover is
     * an APPLICATION_OVERLAY window that cannot reach the system bars, so in a light theme two bright edges would remain.
     */
    private fun applySystemBars(covered: Boolean) {
        val black = android.graphics.Color.BLACK
        window.statusBarColor = if (covered) black else getColor(R.color.m3_surface)
        window.navigationBarColor = if (covered) black else getColor(R.color.m3_surface_low)
        val lightBars = !covered && resources.getBoolean(R.bool.m3_light_bars)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    // ---- Bottom navigation (MD3 Navigation bar; in landscape it becomes a left Navigation rail, see applyContentLayout) ----
    private var currentTab = TAB_CONNECT

    private fun setUpTabs(initial: Int) {
        findViewById<View>(R.id.navConnect).setOnClickListener { selectTab(TAB_CONNECT) }
        findViewById<View>(R.id.navCar).setOnClickListener { selectTab(TAB_CAR) }
        findViewById<View>(R.id.navDiag).setOnClickListener { selectTab(TAB_DIAG) }
        selectTab(initial.coerceIn(TAB_CONNECT, TAB_DIAG))
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        val pages = intArrayOf(R.id.contentArea, R.id.tabCar, R.id.tabDiag)
        pages.forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (i == tab) View.VISIBLE else View.GONE
        }
        val items =
            arrayOf(
                Triple(R.id.navConnectPill, R.id.navConnectIcon, R.id.navConnectLabel),
                Triple(R.id.navCarPill, R.id.navCarIcon, R.id.navCarLabel),
                Triple(R.id.navDiagPill, R.id.navDiagIcon, R.id.navDiagLabel),
            )
        items.forEachIndexed { i, (pill, icon, label) ->
            val on = i == tab
            findViewById<View>(pill).background =
                if (on) ContextCompat.getDrawable(this, R.drawable.bg_nav_pill) else null
            findViewById<ImageView>(icon).imageTintList =
                android.content.res.ColorStateList.valueOf(
                    getColor(if (on) R.color.m3_on_sec_container else R.color.m3_on_var),
                )
            findViewById<TextView>(label).apply {
                setTextColor(getColor(if (on) R.color.m3_on_surface else R.color.m3_on_var))
                setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            findViewById<View>(
                when (i) {
                    TAB_CONNECT -> R.id.navConnect
                    TAB_CAR -> R.id.navCar
                    else -> R.id.navDiag
                },
            ).isSelected = on
        }
        syncPhoneUiActivity()
    }

    // ---- Head-unit wallpaper: dark / light thumbnail rows (default, built-in patterns, my image, from gallery; light has an extra "same as dark") ----

    private fun buildWallpaperStrips() {
        fillWallpaperStrip(findViewById(R.id.wallpaperDarkStrip), WallpaperStore.Slot.DARK)
        fillWallpaperStrip(findViewById(R.id.wallpaperLightStrip), WallpaperStore.Slot.LIGHT)
    }

    private fun fillWallpaperStrip(
        strip: LinearLayout,
        slot: WallpaperStore.Slot,
    ) {
        strip.removeAllViews()
        val current = WallpaperStore.source(this, slot)
        val options = mutableListOf<Pair<String, String>>()
        if (slot == WallpaperStore.Slot.LIGHT) options += WallpaperStore.SRC_SAME to getString(R.string.wp_same)
        options += WallpaperStore.SRC_DEFAULT to getString(R.string.wp_default)
        // Built-in patterns sorted by slot: those matching this slot's tone come first
        val presets =
            io.github.ezn24.droidcar.ui.WallpaperPresets.ALL
                .sortedBy { it.light != (slot == WallpaperStore.Slot.LIGHT) }
        presets.forEach { options += (WallpaperStore.SRC_PRESET_PREFIX + it.id) to getString(it.nameRes) }
        if (WallpaperStore.hasUserImage(this, slot)) options += WallpaperStore.SRC_USER to getString(R.string.wp_custom)
        options += PICK_KEY to getString(R.string.wp_pick)

        val density = resources.displayMetrics.density
        val thumbW = (96 * density).toInt()
        val thumbH = (54 * density).toInt()
        for ((key, label) in options) {
            val selected = key == current
            val item =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, (10 * density).toInt(), 0)
                }
            val frame =
                FrameLayout(this).apply {
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 12 * density
                            setColor(getColor(R.color.m3_surface_high))
                            if (selected) setStroke((3 * density).toInt(), getColor(R.color.m3_primary))
                        }
                    clipToOutline = true
                    val pad = if (selected) (3 * density).toInt() else 0
                    setPadding(pad, pad, pad, pad)
                    contentDescription = label
                    isClickable = true
                    isFocusable = true
                    foreground =
                        ContextCompat.getDrawable(
                            this@MainActivity,
                            android.util
                                .TypedValue()
                                .also {
                                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                                }.resourceId,
                        )
                    setOnClickListener { onWallpaperOption(slot, key) }
                }
            val image =
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 9 * density
                            setColor(getColor(R.color.m3_surface_high))
                        }
                }
            frame.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            when (key) {
                PICK_KEY, WallpaperStore.SRC_SAME -> {
                    image.scaleType = ImageView.ScaleType.CENTER
                    image.setImageResource(if (key == PICK_KEY) R.drawable.ic_wallpaper else R.drawable.ic_moon)
                    image.imageTintList =
                        android.content.res.ColorStateList
                            .valueOf(getColor(R.color.m3_primary))
                }
                else ->
                    Bg.run("wallpaper-thumb") {
                        val thumb =
                            runCatching {
                                val bmp =
                                    WallpaperStore.decodeSource(this, slot, key, maxOf(thumbW, thumbH) * 2)
                                bmp?.let { android.media.ThumbnailUtils.extractThumbnail(it, thumbW, thumbH) }
                            }.getOrNull()
                        runOnUiThread { if (!isDestroyed && thumb != null) image.setImageBitmap(thumb) }
                    }
            }
            item.addView(frame, LinearLayout.LayoutParams(thumbW, thumbH))
            item.addView(
                TextView(this).apply {
                    text = label
                    textSize = 11.5f
                    setTextColor(getColor(if (selected) R.color.m3_primary else R.color.m3_on_var))
                    if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, (4 * density).toInt(), 0, 0)
                },
            )
            strip.addView(item)
        }
    }

    private fun onWallpaperOption(
        slot: WallpaperStore.Slot,
        key: String,
    ) {
        if (key == PICK_KEY) {
            pendingWallpaperSlot = slot
            wallpaperPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
            return
        }
        WallpaperStore.setSource(this, slot, key)
        log("WALLPAPER: ${slot.name} slot → $key (reaches the car within ~1s during a session)")
        buildWallpaperStrips()
    }

    /** The "Car" page settings menu: the same [SettingsMenus] as the head-unit two-pane layout, opening the shared content pages. */
    private fun buildCarMenu() {
        val list = findViewById<LinearLayout>(R.id.carMenuList)
        list.removeAllViews()
        val ripple = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        for (item in io.github.ezn24.droidcar.launcher.settings.SettingsMenus.ALL) {
            val row = layoutInflater.inflate(R.layout.item_phone_settings_menu, list, false)
            // Menu items used to be standalone cards; inside a group card they become list rows
            row.setBackgroundResource(ripple.resourceId)
            (row.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin = 0
            row.findViewById<ImageView>(R.id.menuIcon).setImageResource(item.iconRes)
            row.findViewById<TextView>(R.id.menuTitle).text = item.title
            row.findViewById<TextView>(R.id.menuDesc).text = item.desc
            row.setOnClickListener { SettingsContentActivity.start(this, item) }
            list.addView(row)
        }
        // Setup guide: run the first-time flow again
        val guide = layoutInflater.inflate(R.layout.item_phone_settings_menu, list, false)
        guide.setBackgroundResource(ripple.resourceId)
        (guide.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin = 0
        guide.findViewById<ImageView>(R.id.menuIcon).setImageResource(R.drawable.ic_info_circle)
        guide.findViewById<TextView>(R.id.menuTitle).text = getString(R.string.setup_menu_title)
        guide.findViewById<TextView>(R.id.menuDesc).text = getString(R.string.setup_menu_desc)
        guide.setOnClickListener { startActivity(Intent(this, io.github.ezn24.droidcar.setup.SetupActivity::class.java)) }
        list.addView(guide)
        // Android Auto: certificates for head units that only accept Google's
        val aaCert = layoutInflater.inflate(R.layout.item_phone_settings_menu, list, false)
        aaCert.setBackgroundResource(ripple.resourceId)
        (aaCert.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin = 0
        aaCert.findViewById<ImageView>(R.id.menuIcon).setImageResource(R.drawable.ic_car)
        aaCert.findViewById<TextView>(R.id.menuTitle).text = getString(R.string.aa_cert_title)
        aaCert.findViewById<TextView>(R.id.menuDesc).text = getString(R.string.aa_cert_desc)
        aaCert.setOnClickListener { showAaCertificates() }
        list.addView(aaCert)
    }

    override fun onResume() {
        super.onResume()
        uiResumed = true
        ScreenDimmer.addCoverListener(dimmerCoverListener)
        syncPhoneUiActivity()
        // Not yet accepted after rotation/recreate: resume the dialog from the original deadline (no new 10s wait).
        // While the setup guide is still pending it owns the disclaimer.
        if (io.github.ezn24.droidcar.setup.SetupPrefs
                .done(this) &&
            needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            ) &&
            activeDisclaimer?.isShowing != true
        ) {
            showDisclaimer(requireAcceptance = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DISCLAIMER_DEADLINE, disclaimerDeadlineAt)
        outState.putInt(KEY_TAB, currentTab)
    }

    /**
     * Rotation (configChanges declared, the Activity is not recreated): views and all in-memory state are kept as they are.
     * Two things need handling:
     * 1. The content area switches to two columns - in landscape the screen is only ~300dp tall, the hero card alone fills it,
     *    and the rest is pushed off screen (user-reported "have to scroll to see it");
     * 2. Window sizes of dialogs are recomputed - they were computed from the old orientation's screen width (92%), and without a recompute they cover only the middle half.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFinishing || isDestroyed) return
        applyContentLayout(landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        PanelWindow.resizeShowing(this)
        // The target head-unit dialog does not use DarkMessageDialog, so recompute it separately
        targetPicker?.window?.let {
            val width = (resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
            it.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Content layout: portrait single column (hero card and details in one ScrollView) <->
     * landscape two columns (hero card fixed on the left, only the right-hand details column scrolls).
     *
     * Why the hero card leaves the ScrollView in landscape: in the same scroll container as the details,
     * scrolling the right column would carry the hero card away too (user-reported). Once moved into [contentArea] it is a fixed layer,
     * scrolling affects only mainScroll, and the right-hand content makes room through the ScrollView's left padding.
     *
     * onCreate must call it once as well - rotation does not recreate, but starting in landscape does not trigger
     * onConfigurationChanged.
     */
    private fun applyContentLayout(landscape: Boolean) {
        val contentArea = findViewById<android.widget.FrameLayout>(R.id.contentArea)
        val scroll = findViewById<android.widget.ScrollView>(R.id.mainScroll)
        val row = findViewById<android.widget.LinearLayout>(R.id.contentRow)
        val hero = findViewById<android.widget.LinearLayout>(R.id.heroCard)
        val detail = findViewById<android.widget.LinearLayout>(R.id.detailColumn)
        val density = resources.displayMetrics.density
        val gap = (12 * density).toInt()
        // Landscape is only ~300dp tall: wave area 176→70dp, padding 24/20→12dp,
        // so that "status + subtitle + start connection" all stay on the first screen.
        // Budget: top bar ~60 + wave 70 + status 40 + subtitle 20 + 18 spacing + button 52 + padding 24 ≈ 284dp
        val waveHeight = ((if (landscape) 70 else 176) * density).toInt()
        for (id in intArrayOf(R.id.heroWaveFrame, R.id.signalWaves)) {
            findViewById<View>(id).layoutParams =
                findViewById<View>(id).layoutParams.apply { height = waveHeight }
        }
        val padTop = ((if (landscape) 12 else 24) * density).toInt()
        val padBottom = ((if (landscape) 12 else 20) * density).toInt()
        hero.setPadding(hero.paddingStart, padTop, hero.paddingEnd, padBottom)
        // The landscape wave box is only 70dp: the disc shrinks to 44dp too, leaving room for the outer track and arcs
        // (a 74dp disc fills the wave box and squeezes the arcs out of the visible area, exposed by a device screenshot)
        val plateSize = ((if (landscape) 44 else 74) * density).toInt()
        val carSize = ((if (landscape) 24 else 38) * density).toInt()
        findViewById<View>(R.id.hubPlate).layoutParams =
            findViewById<View>(R.id.hubPlate).layoutParams.apply {
                width = plateSize
                height = plateSize
            }
        findViewById<View>(R.id.hubCar).layoutParams =
            findViewById<View>(R.id.hubCar).layoutParams.apply {
                width = carSize
                height = carSize
            }
        // The landscape wave box is only 70dp: no room for the CARPLAY label, so the label yields
        // (the disc is the visual focus, and the brand text is already in the top title)
        findViewById<View>(R.id.hubCaption).visibility =
            if (landscape) View.GONE else View.VISIBLE
        val railWidth = applyNavPlacement(landscape, density)

        row.orientation = android.widget.LinearLayout.VERTICAL
        if (landscape) {
            if (hero.parent === row) {
                row.removeView(hero)
                contentArea.addView(hero)
            }
            // Width is derived from the screen width minus page padding, not read from contentArea.width:
            // at the moment of rotation the latter is still the old orientation's measurement (portrait 1168 → the hero card is only 537px,
            // the subtitle gets squeezed to three lines, exposed by a device screenshot).
            val pagePadding =
                (16 * resources.displayMetrics.density * 2).toInt()
            val available =
                (resources.displayMetrics.widthPixels - pagePadding - railWidth).coerceAtLeast(1)
            val heroWidth = (available * 0.46f).toInt().coerceAtLeast(1)
            // Height must be WRAP_CONTENT: FrameLayout treats MATCH_PARENT/WRAP as fill,
            // and fill pushes the button to the card's bottom edge and squeezes the subtitle to three lines (exposed by a device screenshot)
            hero.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        heroWidth,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = android.view.Gravity.START or android.view.Gravity.TOP }
            // The right-hand content makes room for the hero card width (including spacing): the scrollbar stays at the right screen edge
            scroll.setPadding(heroWidth + gap, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        } else {
            if (hero.parent === contentArea) {
                contentArea.removeView(hero)
                row.addView(
                    hero,
                    0,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            (hero.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                topMargin = gap
                marginEnd = 0
            }
            scroll.setPadding(0, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        }
    }

    /**
     * Navigation bar position: portrait bottom strip (80dp); in landscape the screen is only ~300dp tall and a bottom bar would push the hero card off the first screen,
     * so it becomes a left vertical navigation bar (MD3 Navigation rail). Returns the width the navigation bar takes (0 in portrait).
     */
    private fun applyNavPlacement(
        landscape: Boolean,
        density: Float,
    ): Int {
        val root = findViewById<LinearLayout>(R.id.rootColumn)
        val nav = findViewById<LinearLayout>(R.id.bottomNav)
        val page = findViewById<LinearLayout>(R.id.pageColumn)
        val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        val barSize = (80 * density).toInt()
        root.removeView(nav)
        if (landscape) {
            root.orientation = LinearLayout.HORIZONTAL
            root.addView(nav, 0, LinearLayout.LayoutParams(barSize, match))
            page.layoutParams = LinearLayout.LayoutParams(0, match, 1f)
            nav.orientation = LinearLayout.VERTICAL
            nav.gravity = android.view.Gravity.CENTER
            nav.setPadding(0, 0, 0, 0)
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.addView(nav, LinearLayout.LayoutParams(match, barSize))
            page.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            nav.orientation = LinearLayout.HORIZONTAL
            nav.gravity = android.view.Gravity.NO_GRAVITY
            nav.setPadding(0, (12 * density).toInt(), 0, (16 * density).toInt())
        }
        for (id in intArrayOf(R.id.navConnect, R.id.navCar, R.id.navDiag)) {
            findViewById<View>(id).layoutParams =
                if (landscape) {
                    LinearLayout.LayoutParams(match, (72 * density).toInt())
                } else {
                    LinearLayout.LayoutParams(0, match, 1f)
                }
        }
        return if (landscape) barSize else 0
    }

    override fun onPause() {
        super.onPause()
        uiResumed = false
        ScreenDimmer.removeCoverListener(dimmerCoverListener)
        syncPhoneUiActivity()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Force-declared state must not leave a window behind when the Activity is destroyed (rotation recreates the Activity, onResume resumes it)
        runCatching { activeDisclaimer?.dismiss() }
        activeDisclaimer = null
        shizukuPermissionListener?.let {
            runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(it) }
        }
        shizukuPermissionListener = null
        pendingShizukuThenConnect = false
        // Bluetooth scan/pair listeners are torn down with the UI: no longer needed once the Activity exits (also avoids a leaked receiver)
        runCatching { stopTargetScan() }
        targetPicker = null
        // Unbind only the "attachment", not the session - the connection is kept alive by the foreground service and objects are owned by SessionController;
        // for an explicit disconnect use the "Disconnect" button (any instance can reach the real pipeline).
        if (SessionController.logListener === logListener) SessionController.logListener = null
        if (SessionController.uiHintSink === uiHintSinkRef) SessionController.uiHintSink = null
        SessionController.dialogSink = null
        if (SessionController.ssidSink === ssidSink) SessionController.ssidSink = null
    }
}
