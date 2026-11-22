package io.github.ezn24.droidcar.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.ezn24.droidcar.shizuku.CarInputClient
import io.github.ezn24.droidcar.shizuku.Shell
import io.github.ezn24.droidcar.util.Bg

/**
 * Always-on + dim overlay: a lock-screen-avoidance scheme replacing screen-off.
 *
 * In the car scenario a phone lock screen freezes input on non-default displays (a hard system boundary), and a secure lock cannot be
 * dismissed by commands.
 * This scheme keeps the screen from ever turning off (screen timeout set very large, original value restored on disconnect),
 * and after 45 seconds without interaction covers it with a near-black full-screen View (imitating screen-off); touching anywhere on the
 * phone removes it and restarts the timer.
 *
 * Touch probe: a 1px window + FLAG_WATCH_OUTSIDE_TOUCH captures real touches anywhere on screen (including third-party apps),
 * used only to reset the timer; it never intercepts or consumes events.
 */
object ScreenDimmer {
    private const val TAG = "ScreenDimmer"
    private const val IDLE_TIMEOUT_MS = 45_000L
    private const val TIMEOUT_MAX = 2_147_483_647

    /** Controlled by a setting (default on). */
    @Volatile
    var enabled: Boolean = true

    /**
     * Master switch for the automatic dim overlay (45s idle fake screen-off / dim). The remote-debug variant sets it false
     * at Application start: troubleshooting needs a live view of the phone screen, and "going black after a while" hides exactly
     * the scene being investigated (user request 2026-10-24); always-on is unaffected, as is manual fake screen-off (the
     * force path of [enterDimNow]).
     */
    @Volatile
    var autoDimAllowed: Boolean = true

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var manualDimActive = false

    private val timeoutExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "screen-timeout").apply { isDaemon = true }
        }
    private var windowManager: WindowManager? = null
    private var handler: Handler? = null
    private var dimView: View? = null
    private var watchView: View? = null

    @Volatile
    private var dimShown = false

    /**
     * True fake screen-off: the physical panel has been powered down by us (SurfaceControl POWER_MODE_OFF).
     * Power is restored on the teardown paths only when this object set the flag; on a real system sleep (power key) the
     * SCREEN_OFF broadcast clears it, after which the panel belongs to DisplayPowerController, so do not fight it.
     */
    @Volatile
    private var panelOffByUs = false

    private var screenOffReceiver: BroadcastReceiver? = null

    /**
     * Whether a casting session is currently active.
     *
     * **Auto dim and always-on apply only during a session** (user feedback 2026-10-19):
     * the app attaches at launch (so "manual fake screen-off" is always available), but a head-unit-side disconnect
     * (`VideoSender.onEnded`) does not release VirtualLauncher and the 45s dim timer keeps running,
     * which showed up as "the session ended long ago, then the phone screen suddenly goes black". So automatic scheduling
     * must be session-driven; manual [enterDimNow] is exempt (explicit user action).
     */
    @Volatile
    private var sessionActive = false

    private val showRunnable = Runnable { showDim() }

    /** Dim show/hide listeners (called on the main thread): when the phone UI is covered it should stop its own animations/polling, or it keeps drawing under the overlay. */
    private val coverListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addCoverListener(listener: (Boolean) -> Unit) {
        coverListeners.add(listener)
    }

    fun removeCoverListener(listener: (Boolean) -> Unit) {
        coverListeners.remove(listener)
    }

    /** Whether the phone screen is currently covered by the dim overlay (including physical panel powered down). */
    fun isCovering(): Boolean = dimShown

    private fun notifyCover(covered: Boolean) {
        coverListeners.forEach { runCatching { it(covered) } }
    }

    /** Session start/stop (called by SessionController when streaming starts/stops). */
    fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        val ctx = appContext ?: return
        val h = handler
        if (active) {
            if (!enabled) return
            syncScreenTimeout(ctx)
            h?.post { schedule() }
            Log.i("CarPlaySender", "DIMMER: session started -- always-on/auto dim in effect (dims after ${IDLE_TIMEOUT_MS / 1000}s idle)")
        } else {
            h?.removeCallbacks(showRunnable)
            manualDimActive = false
            // If the dim overlay is covering when the session ends (head unit disconnected by itself), remove it at once so the user sees
            // the normal phone UI
            h?.post { hideDim() }
            restorePanelIfNeeded()
            syncScreenTimeout(ctx)
            Log.i("CarPlaySender", "DIMMER: session ended -- auto dim stopped, screen timeout restore scheduled")
        }
    }

    fun attach(context: Context) {
        if (appContext != null) {
            // Already attached: if this is an "enable" toggle, complete always-on and the auto timer (the touch probe was already attached)
            if (enabled && sessionActive) {
                syncScreenTimeout(context)
                handler?.post { schedule() }
            }
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission missing, dim/touch probe unavailable (always-on unavailable)")
            appContext = context.applicationContext
            windowManager = null
            handler = Handler(Looper.getMainLooper())
            return
        }
        appContext = context.applicationContext
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        // Always-on is switched on only during a session: attach at app launch does not set the screen timeout to "never",
        // otherwise the phone never sleeps while unconnected (and it would not be restored after a head-unit disconnect either).
        if (enabled && sessionActive) syncScreenTimeout(context)
        handler?.post {
            addWatcher()
            registerScreenOffReceiver(context)
            if (enabled && sessionActive) schedule()
        }
        if (!enabled) {
            Log.i("CarPlaySender", "DIMMER: touch probe started (always-on/dim off)")
        } else if (sessionActive) {
            Log.i("CarPlaySender", "DIMMER: started (always-on + dim after ${IDLE_TIMEOUT_MS / 1000}s idle, tap to restore)")
        } else {
            Log.i("CarPlaySender", "DIMMER: touch probe started (not casting -- always-on/auto dim take effect after a session starts)")
        }
    }

    /** Turn off always-on/dim but keep the touch probe (no window rebuild needed when re-enabled). */
    fun disableDim() {
        enabled = false
        val ctx = appContext ?: return
        syncScreenTimeout(ctx)
        handler?.removeCallbacks(showRunnable)
        handler?.post { hideDim() }
        restorePanelIfNeeded()
        Log.i("CarPlaySender", "DIMMER: always-on/dim turned off (touch probe kept)")
    }

    /**
     * Whether auto dim should be in effect (pure function, see [shouldAutoDim]).
     *
     * Split out for unit tests: if any of the three is false it must **not** show the dim overlay. The user-reported bug was
     * exactly "session ended (sessionActive=false) but the timer still running", the phone screen going black for no reason.
     */
    fun shouldAutoDim(
        enabled: Boolean,
        sessionActive: Boolean,
        attached: Boolean,
    ): Boolean = enabled && sessionActive && attached

    fun detach() {
        val h = handler
        handler = null
        // The session flag must be cleared too: otherwise the next attach (app launched while unconnected) would reuse the previous
        // true and schedule at once, bringing back the old "auto dim without a session" problem.
        sessionActive = false
        manualDimActive = false
        restorePanelIfNeeded()
        appContext?.let { unregisterScreenOffReceiver(it) }
        h?.removeCallbacksAndMessages(null)
        val wm = windowManager
        val dim = dimView
        val watch = watchView
        dimView = null
        watchView = null
        dimShown = false
        windowManager = null
        h?.post {
            dim?.let { v -> runCatching { wm?.removeView(v) } }
            watch?.let { v -> runCatching { wm?.removeView(v) } }
        }
        val ctx = appContext
        appContext = null
        if (ctx != null) syncScreenTimeout(ctx)
        Log.i("CarPlaySender", "DIMMER: stopped (screen timeout restore scheduled)")
    }

    /** Whether the dim overlay is ready (attach completed and overlay capability present). */
    fun isAttached(): Boolean = handler != null && windowManager != null

    /** Enter manual fake screen-off (triggered by the main page button; tapping the overlay or anywhere on the phone screen restores). */
    fun enterDimNow() {
        val h = handler ?: return
        // Permission missing at attach but granted later: build the window manager and touch probe here
        if (windowManager == null) {
            val ctx = appContext ?: return
            if (!android.provider.Settings.canDrawOverlays(ctx)) return
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            h.post { addWatcher() }
        }
        h.post { showDim(force = true) }
    }

    /** Called on a physical touch (any app/UI) or a dim overlay tap: resets the auto-dim timer. */
    fun onUserInteraction() {
        val h = handler ?: return
        if (panelOffByUs) {
            // Wake-up under true fake screen-off: power the panel first, then remove the overlay (otherwise the user sees a black screen)
            restorePanelIfNeeded()
        }
        if (dimShown) hideDim()
        if (enabled && sessionActive) schedule()
    }

    private fun schedule() {
        if (!sessionActive) return
        handler?.removeCallbacks(showRunnable)
        handler?.postDelayed(showRunnable, IDLE_TIMEOUT_MS)
    }

    private fun showDim(force: Boolean = false) {
        // The automatic path needs an active session: never show the dim overlay once the session has ended (especially a head-unit
        // disconnect)
        if (!force && !shouldAutoDim(enabled, sessionActive, isAttached())) return
        // remote debug variant: automatic fake screen-off/dim is never scheduled (troubleshooting needs the screen); the manual path is
        // unrestricted
        if (!force && !autoDimAllowed) return
        val ctx = appContext
        if (ctx == null) {
            android.util.Log.w("ScreenDimmer", "showDim: appContext is null (attach did not run)")
            return
        }
        val wm = windowManager
        if (wm == null) {
            android.util.Log.w("ScreenDimmer", "showDim: windowManager is null (overlay permission missing?)")
            return
        }
        if (dimShown) return
        val view =
            View(ctx).apply {
                setBackgroundColor(Color.argb(0xF8, 0, 0, 0))
                isClickable = true
                setOnClickListener { onUserInteraction() }
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            )
        runCatching { wm.addView(view, params) }
            .onSuccess {
                dimView = view
                dimShown = true
                if (force && !sessionActive) {
                    manualDimActive = true
                    syncScreenTimeout(ctx)
                }
                Log.i("CarPlaySender", "DIMMER: dim overlay shown")
                notifyCover(true)
                // True fake screen-off: once the overlay is up, cut physical panel power directly. The system stays interactive (the
                // always-on timeout is set
                // to never, so it will not really sleep), virtual displays are unaffected; touches wake it through the probe.
                setPanelPower(false)
            }.onFailure { Log.w(TAG, "Failed to add dim overlay: ${it.message}") }
    }

    private fun hideDim() {
        val view = dimView ?: return
        dimView = null
        dimShown = false
        if (manualDimActive) {
            manualDimActive = false
            appContext?.let(::syncScreenTimeout)
        }
        runCatching { windowManager?.removeView(view) }
        notifyCover(false)
    }

    /** 1px touch probe: captures DOWN events anywhere on screen to reset the timer, consumes nothing. */
    private fun addWatcher() {
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val view =
            View(ctx).apply {
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN ||
                        event.action == MotionEvent.ACTION_OUTSIDE
                    ) {
                        onUserInteraction()
                    }
                    false
                }
            }
        val params =
            WindowManager
                .LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
        runCatching { wm.addView(view, params) }
            .onSuccess { watchView = view }
            .onFailure { Log.w(TAG, "Failed to add touch probe: ${it.message}") }
    }

    // ---- True fake screen-off: cut/restore physical panel power at the SurfaceControl layer (via Shizuku UserService) ----

    /** Cut/restore panel power. The AIDL call is a blocking binder call, always run in the background; on failure stay in the dim-overlay state (old behavior). */
    private fun setPanelPower(on: Boolean) {
        Bg.run("dimmer-panel") {
            val svc = CarInputClient.service
            if (svc == null) {
                Log.i(TAG, "Panel power skipped (UserService not ready, dim overlay only)")
                return@run
            }
            val ok =
                runCatching { svc.setScreenPower(on) }
                    .onFailure { Log.w(TAG, "setScreenPower($on) call failed: $it") }
                    .getOrDefault(false)
            if (ok) {
                panelOffByUs = !on
                Log.i("CarPlaySender", "DIMMER: panel power ${if (on) "restored" else "cut"} (true fake screen-off)")
            } else {
                Log.i(TAG, "Panel power $on failed (ROM unsupported? staying in dim-overlay state)")
            }
        }
    }

    /** Single exit for teardown paths: restore power only if "we cut it", to avoid fighting system power management for the panel. */
    private fun restorePanelIfNeeded() {
        if (panelOffByUs) setPanelPower(true)
    }

    /**
     * Exit for "true screen-off on connect" teardown (called by SessionController restore paths):
     * remove the overlay + restore panel power + clear the powered-down flag (idempotent: a harmless no-op if power was never cut).
     * Unlike [onUserInteraction], this is a **programmatic teardown** (switch off / session retired / startup recovery)
     * and does not reschedule the auto timer; while powered down the overlay carries touch detection (tapping it wakes the phone; the touch
     * IC was observed to keep working after panel power-off), so removing the overlay must happen together with restoring power.
     */
    fun exitPanelOff() {
        val h = handler ?: return
        panelOffByUs = false
        h.post {
            if (dimShown) hideDim()
            setPanelPower(true)
        }
    }

    /**
     * Lifecycle teardown for real sleep (power key) and unlock:
     * - SCREEN_OFF: hand the panel back to system power management (clear the flag), remove the overlay, stop the timer; the lock screen
     * itself is
     *   "cover + anti-mistouch", and a lingering overlay would become a leftover needing an extra tap after unlock (user test 2026-10-23).
     * - USER_PRESENT: reschedule the auto fake screen-off timer after unlock; fingerprint/face unlock produces no screen touch events,
     *   so the probe cannot see them, and without rescheduling here the timer stays stopped after unlock.
     */
    private fun registerScreenOffReceiver(context: Context) {
        if (screenOffReceiver != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            if (panelOffByUs) {
                                panelOffByUs = false
                                Log.i(
                                    TAG,
                                    "Real system sleep -- panel handed back to system power management (fake screen-off flag cleared)",
                                )
                            }
                            if (dimShown) {
                                handler?.removeCallbacks(showRunnable)
                                hideDim()
                                Log.i(TAG, "Real sleep -- dim overlay removed (lock screen takes over covering)")
                            }
                        }

                        Intent.ACTION_USER_PRESENT -> {
                            if (enabled && sessionActive && isAttached()) {
                                schedule()
                                Log.i(TAG, "Unlocked -- rescheduling the auto fake screen-off timer")
                            }
                        }
                    }
                }
            }
        screenOffReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
    }

    private fun unregisterScreenOffReceiver(context: Context) {
        screenOffReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenOffReceiver = null
    }

    private const val TIMEOUT_BACKUP = "screen_off_timeout_backup"

    /** Serialize system writes and retain the original timeout until it is safe to clear. */
    fun syncScreenTimeout(context: Context) {
        val ctx = context.applicationContext
        timeoutExecutor.execute {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val keepAwake = enabled && appContext != null && (sessionActive || manualDimActive)
            val hasBackup = prefs.contains(TIMEOUT_BACKUP)
            if (!keepAwake && !hasBackup) return@execute

            val current = readScreenTimeout()
            if (current == null) {
                Log.w(TAG, "Failed to read screen timeout, keeping the pending-restore record")
                return@execute
            }

            if (keepAwake) {
                if (current == TIMEOUT_MAX) return@execute
                if (!prefs.edit().putInt(TIMEOUT_BACKUP, current).commit()) {
                    Log.w(TAG, "Cannot save the original screen timeout, skipping always-on")
                    return@execute
                }
                val result = Shell.exec("settings put system screen_off_timeout $TIMEOUT_MAX")
                if (!result.ok) Log.w(TAG, "Failed to set always-on")
            } else {
                val backup = prefs.getInt(TIMEOUT_BACKUP, 60_000)
                // If the timeout changed outside DroidCar, keep the user's new setting.
                if (current == TIMEOUT_MAX &&
                    (!Shell.exec("settings put system screen_off_timeout $backup").ok || readScreenTimeout() != backup)
                ) {
                    Log.w(TAG, "Failed to restore screen timeout, will retry when Shizuku is available again")
                    return@execute
                }
                prefs.edit().remove(TIMEOUT_BACKUP).commit()
            }
        }
    }

    private fun readScreenTimeout(): Int? =
        Shell.exec("settings get system screen_off_timeout").let { result ->
            if (result.ok) result.output.trim().toIntOrNull() else null
        }
}
