package io.github.ezn24.droidcar.assist

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import io.github.ezn24.droidcar.launcher.settings.DisplayPrefs
import io.github.ezn24.droidcar.shizuku.CarInputClient
import io.github.ezn24.droidcar.shizuku.Shell
import io.github.ezn24.droidcar.util.Bg

/**
 * Voice assistant on the car (experimental): shows the Gemini **native overlay** (four-color glow + "Ask Gemini" bar) on the head unit.
 *
 * Findings from the 2026-11-15 S24 Ultra tests (these shaped the approach):
 * - The Gemini overlay is a plain Activity inside the Google app (`FloatyActivity`), opened on the **currently focused display**;
 * "Hey Google" usually lands on the phone's main display, but its task can be moved to the car display with
 * `am display move-stack`, after which it renders natively and is directly touchable, far better than mirror cropping (mirroring was
 * dropped);
 * - On the car display it is covered by the dashboard map/media cards (the compositor draws them above the app display), so the cards
 * must yield temporarily;
 * - App-side recording callbacks, and even shell-side recording configs, cannot see Gemini's HOTWORD recording;
 * the reliable signal is the Google app's RECORD_AUDIO / RECORD_AUDIO_HOTWORD AppOps (queried via shell).
 *
 * Flow: every [MIC_WATCH_MS] ask the shell "is the assistant listening?" (one binder call); once listening, every
 * [POLL_INTERVAL_MS] run `dumpsys window windows` to find the overlay; if it is on the phone display, move it to the car display hosting
 * the desktop;
 * if it is on a car display, yield the cards; restore after it disappears. All shell/binder calls run on [thread].
 */
object AssistOverlayController {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var log: (String) -> Unit = {}

    /** The state below is read and written only on [thread]. */
    private var polling = false
    private var misses = 0
    private var micActive = false
    private var watchState = ""

    /** Car display the overlay is currently on (-1 = not on the car). */
    @Volatile
    private var carDisplayId = -1

    /** Whether the overlay is showing on the car (TouchRouter: tapping the dock closes it). */
    fun isOnCar(): Boolean = carDisplayId >= 0

    /** Overlay is on the assistant transparent display (TouchRouter: hands all app-area touches to it). */
    fun isOnAssistLayer(): Boolean = carDisplayId >= 0 && carDisplayId == VirtualLauncher.assistDisplayId

    /** Task already moved: if it returns to the phone display, the user dismissed it on the car (see [poll]). */
    private var movedTaskId = -1

    /** Current overlay task (used to close it). */
    @Volatile
    private var currentTaskId = -1

    private val micWatch =
        object : Runnable {
            override fun run() {
                val svc = CarInputClient.service
                if (svc != null) {
                    val listening =
                        try {
                            svc.isAssistantListening()
                        } catch (t: Throwable) {
                            // Older UserService process lacks this method (first connect after reinstall may reuse the old process)
                            if (watchState != "unavailable") log("ASSIST: shell mic query unavailable: $t")
                            watchState = "unavailable"
                            null
                        }
                    if (listening != null) {
                        if (watchState != "ok") log("ASSIST: shell mic query ready (AppOps)")
                        watchState = "ok"
                        if (listening != micActive) {
                            micActive = listening
                            log("ASSIST: assistant mic active=$listening")
                        }
                        if (listening) beginPolling()
                    }
                }
                // Reschedule only on this session's thread (a leftover run of an old thread after stop->start must not leak into the new
                // one)
                val h = handler ?: return
                if (h.looper == android.os.Looper.myLooper()) h.postDelayed(this, MIC_WATCH_MS)
            }
        }

    private val pollTask =
        object : Runnable {
            override fun run() {
                if (!polling) return
                poll()
                if (polling) handler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    /** Called on session attach (VirtualLauncher); does nothing when the preference is off. */
    @Synchronized
    fun start(
        context: Context,
        logSink: (String) -> Unit,
    ) {
        stop()
        log = logSink
        val app = context.applicationContext
        if (!DisplayPrefs.showAssistOverlay(app)) return
        val t = HandlerThread("assist-overlay").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        log("ASSIST: enabled (waiting for the voice assistant to open the mic)")
        h.post(micWatch)
    }

    /** Called on session detach: restore cards, stop the thread. */
    @Synchronized
    fun stop() {
        val h = handler ?: return
        val t = thread
        handler = null
        thread = null
        h.post {
            polling = false
            leaveCarDisplay()
            t?.quitSafely()
        }
    }

    /** Settings panel toggle: takes effect immediately during a session. */
    fun onPrefChanged(
        context: Context,
        enabled: Boolean,
    ) {
        if (enabled) {
            if (VirtualLauncher.appContext() != null && handler == null) start(context, log)
        } else {
            stop()
        }
    }

    /**
     * User tapped somewhere the overlay cannot reach on the car (dock display): end the overlay. Returns true = handled (caller swallows
     * the tap).
     *
     * Cannot send BACK: Gemini treats BACK as "collapse" and the task jumps back to the phone display and lingers (observed with
     * misrecognized text from music pending to send). Removing the task is what truly ends it.
     */
    fun dismissFromCar(): Boolean {
        val display = carDisplayId
        val task = currentTaskId
        if (display < 0 || task < 0) return false
        Bg.run("assist-dismiss") { removeTask(task) }
        log("ASSIST: car tap on dock -> ending assistant overlay task=$task")
        return true
    }

    private fun removeTask(task: Int) {
        io.github.ezn24.droidcar.launcher.VirtualLauncher
            .removeTask(task.toString())
    }

    /** Until this uptime the window check keeps going without a mic (see [onAssistRequested]). */
    @Volatile
    private var requestedUntil = 0L

    /**
     * The car's voice key opened the assistant. The newer Gemini overlay opens as a text bar without using the mic, so the
     * mic watch never fired and the bar stayed on the desktop display under the dashboard map (2026-11-23 ChairPlay).
     */
    fun onAssistRequested() {
        val h = handler ?: return
        requestedUntil = android.os.SystemClock.uptimeMillis() + REQUEST_WATCH_MS
        h.post { beginPolling() }
    }

    private fun beginPolling() {
        if (polling) return
        polling = true
        misses = 0
        log("ASSIST: start checking assistant window")
        handler?.post(pollTask)
    }

    private fun poll() {
        val result = Shell.exec("dumpsys window windows", timeoutMs = 2_000L)
        val window = if (result.ok) parseAssistWindow(result.output) else AssistWindow(false)
        if (window.visible) {
            misses = 0
            currentTaskId = window.taskId
            if (window.displayId == 0 && window.taskId >= 0 && window.taskId == movedTaskId) {
                // The overlay moved to the car returned to the phone display by itself: the user tapped blank space on the car to
                // collapse it.
                // After collapsing Gemini lingers on the phone (possibly with misrecognized text), so end it directly.
                log(
                    "ASSIST: assistant overlay returned from car display to phone task=${window.taskId} -> treated as closed on the car, ending it",
                )
                removeTask(window.taskId)
                leaveCarDisplay()
                return
            }
            val target = targetDisplay()
            if (target >= 0 && window.displayId != target && moveTo(window.taskId, target)) return
            if (window.displayId != 0) showOnCarDisplay(window.displayId)
            return
        }
        misses++
        if (carDisplayId >= 0 && misses >= HIDE_AFTER_MISSES) leaveCarDisplay()
        val awaitingRequested = android.os.SystemClock.uptimeMillis() < requestedUntil
        if (carDisplayId < 0 && !micActive && !awaitingRequested && misses >= HIDE_AFTER_MISSES) {
            polling = false
            log("ASSIST: assistant window gone, stop checking")
        } else if (carDisplayId < 0 && misses >= GIVE_UP_MISSES) {
            polling = false
            log("ASSIST: mic stays active but no assistant window ($misses times), stop checking")
        }
    }

    /**
     * Where the overlay should live (settled after two rounds of user feedback on 2026-11-15):
     * - Preferred: the **assistant transparent display** (CarPlayAssist): the compositor layers it translucently over the whole app area,
     * glow fills the screen, chat bar at the bottom, map/media cards stay visible (yielding the cards made the map vanish,
     * and moving it to the map display only covered the map card; both were rejected);
     * - If the transparent display cannot be created, fall back to: dashboard/fullscreen map -> map display; otherwise -> current active
     * app display.
     */
    private fun targetDisplay(): Int {
        val assist = VirtualLauncher.ensureAssistDisplay()
        if (assist >= 0) return assist
        return if ((VirtualLauncher.dashboardMode || VirtualLauncher.mapFullscreen) && VirtualLauncher.mapDisplayId >= 0) {
            VirtualLauncher.mapDisplayId
        } else {
            VirtualLauncher.activeAppDisplayId()
        }
    }

    /** Already moved (task, target display) pairs: each combination is moved once, no repeated retries on failure or system refusal. */
    private var movedTarget = -1

    /** Move the overlay task to [target]; returns whether a move was started (no display-state handling this round). */
    private fun moveTo(
        task: Int,
        target: Int,
    ): Boolean {
        if (task < 0 || (task == movedTaskId && target == movedTarget)) return false
        movedTaskId = task
        movedTarget = target
        val r = Shell.exec("am display move-stack $task $target", timeoutMs = 3_000L)
        log("ASSIST: assistant overlay task=$task -> moved to car display $target ok=${r.ok} ${r.output.take(80)}")
        return r.ok
    }

    /** Overlay is on a car display; if it sits on a display covered by dashboard cards (fallback when a move fails), yield the cards temporarily. */
    private fun showOnCarDisplay(displayId: Int) {
        if (carDisplayId == displayId) return
        carDisplayId = displayId
        VirtualLauncher.setAssistLayerVisible(displayId == VirtualLauncher.assistDisplayId)
        val uncovered = VirtualLauncher.setAssistOnCarDisplay(displayId)
        log(
            "ASSIST: assistant overlay on car display display=$displayId -> ${if (uncovered) "yielding dashboard cards" else "not the desktop display, shown as is"}",
        )
    }

    private fun leaveCarDisplay() {
        if (carDisplayId < 0) return
        log("ASSIST: assistant overlay on the car display closed display=$carDisplayId -> restoring dashboard cards")
        carDisplayId = -1
        VirtualLauncher.setAssistLayerVisible(false)
        VirtualLauncher.setAssistOnCarDisplay(-1)
    }

    private const val MIC_WATCH_MS = 400L

    /** How long a voice-key request waits for the assistant window to appear. */
    private const val REQUEST_WATCH_MS = 6_000L
    private const val POLL_INTERVAL_MS = 450L

    /** Consecutive misses before the window counts as closed (dumpsys occasionally drops a block, one read is not trustworthy). */
    private const val HIDE_AFTER_MISSES = 2

    /** Keeps listening but never sees an assistant window: stop checking, wait for the next mic open. */
    private const val GIVE_UP_MISSES = 20
}
