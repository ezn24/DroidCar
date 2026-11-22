package io.github.ezn24.droidcar.launcher

import android.content.Context
import io.github.ezn24.droidcar.launcher.settings.AppOrientationCompat
import io.github.ezn24.droidcar.render.OverlayCard
import io.github.ezn24.droidcar.shizuku.Shell
import io.github.ezn24.droidcar.util.Bg

/**
 * Orchestration of the "forced landscape not in effect" hint: event-driven probe -> locate black bars -> build the card -> one-tap restart.
 *
 * Why it is needed (reproduced on an Honor real device 2026-10-18): when the app runs first and `am compat` forced landscape
 * is written only after connecting to the head unit, the system kills the process, but the retained task restores
 * the ActivityRecord with the **old configuration** (`displayChangedWithoutRestart=true`), so `overrideOrientation`
 * stays PORTRAIT, `isLetterboxRunning=true`, `letterboxReason=FIXED_ORIENTATION`.
 * Only force-stop followed by a fresh launch rebuilds it with the new orientation override.
 *
 * Probing is triggered only at event points (connection established, app launched, map fullscreen, after restart), never polled;
 * the decision and geometry are pure functions (see LetterboxHint.kt) and unit-testable.
 */
object LetterboxHintController {
    private const val TAG = "LetterboxHint"

    /** The user tapped "close" / confirmed it cannot be fixed: no more hints this session. */
    private val dismissed = HashSet<String>()

    private enum class State { NORMAL, BUSY, FAILED }

    @Volatile
    private var state = State.NORMAL

    /** Target of the current hint (null = not shown). */
    private data class Target(
        val packageName: String,
        val component: String,
        val displayId: Int,
    )

    @Volatile
    private var target: Target? = null

    /** Card rectangle (output-picture coordinates, origin at top); touch hit-testing uses it directly. */
    @Volatile
    private var cardLeft = 0f

    @Volatile
    private var cardTop = 0f

    @Volatile
    private var cardRight = 0f

    @Volatile
    private var cardBottom = 0f

    @Volatile
    private var probeInFlight = false

    fun isVisible(): Boolean = target != null

    /** Whether the card is hit (output-picture coordinates; called by TouchRouter before injection). */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean = target != null && x >= cardLeft && x < cardRight && y >= cardTop && y < cardBottom

    /** Card tapped: NORMAL = restart the app; BUSY = ignore; FAILED = close. */
    fun onTap() {
        when (state) {
            State.NORMAL -> {
                val t = target ?: return
                restart(t)
            }

            State.BUSY -> Unit

            State.FAILED -> dismiss()
        }
    }

    fun dismiss() {
        target?.let { synchronized(dismissed) { dismissed.add(it.packageName) } }
        clear()
    }

    /** Back to desktop / session end: clear the hint and restore state. */
    fun clear() {
        target = null
        state = State.NORMAL
        cardLeft = 0f
        cardTop = 0f
        cardRight = 0f
        cardBottom = 0f
        VirtualLauncher.setOverlayCard(null)
    }

    /** New session: clear the "no more hints this session" record. */
    fun resetSession() {
        synchronized(dismissed) { dismissed.clear() }
        clear()
    }

    /**
     * Probe at event points (called after connection established / app launched / map fullscreen). Runs in the background:
     * one `dumpsys activity activities` takes about 20ms, but still must not occupy the UI thread.
     *
     * [displayIdHint]: the target display the caller **knows for sure** (passed by launchApp / map launch). When omitted it is inferred
     * from the
     * compositor's current active display - but switching displays waits for the target display's first frame (up to 2s), and a probe that
     * runs before the switch completes
     * looks at the wrong display and reads an empty result (apps with a slow first frame such as Toutiao missed the hint this way,
     * 2026-10-18).
     */
    fun scheduleProbe(
        context: Context,
        reason: String,
        displayIdHint: Int = -1,
        delayMs: Long = 1_200L,
    ) {
        val app = context.applicationContext
        Bg.run("letterbox-probe") {
            if (delayMs > 0) Thread.sleep(delayMs)
            runCatching { probe(app, reason, displayIdHint) }
                .onFailure { android.util.Log.w(TAG, "probe failed after $reason", it) }
        }
    }

    private fun probe(
        context: Context,
        reason: String,
        displayIdHint: Int,
    ) {
        if (probeInFlight) return
        probeInFlight = true
        try {
            val displayId =
                displayIdHint
                    .takeIf { it >= 0 && VirtualLauncher.hintDisplaySize(it) != null }
                    ?: visibleDisplayId() ?: run {
                    if (isVisible()) clear()
                    return
                }
            val (fallbackW, fallbackH) =
                VirtualLauncher.hintDisplaySize(displayId) ?: run {
                    if (isVisible()) clear()
                    return
                }
            // Orientation overrides are written asynchronously and serially: wait for the queue to drain before probing, otherwise the
            // pre-write state may be read
            AppOrientationCompat.awaitPending()
            val enabled = AppOrientationCompat.enabledPackages(context)
            if (enabled.isEmpty()) {
                if (isVisible()) clear()
                return
            }
            val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
            val activity =
                parseTopLetterboxActivity(displayId, dump, fallbackW, fallbackH) ?: run {
                    if (isVisible()) clear()
                    return
                }
            val should =
                shouldShowLetterboxHint(activity, enabled, synchronized(dismissed) { dismissed.toSet() })
            if (!should) {
                if (isVisible()) {
                    VirtualLauncher.logSink?.invoke(
                        "HINT: hint cleared ($reason) - " +
                            "letterbox=${activity.letterboxed} reason=${activity.letterboxReason}",
                    )
                    clear()
                }
                return
            }
            // The system-side override must really be in effect, otherwise "force restart" is doomed to fail (misleading hint).
            // A preference that is on while the system side is missing is **self-healable** (session write failed / cleared externally):
            // write the override per the user's intent. `am compat` kills the target process on the spot, so after the write we must
            // actively pull it back to its original display - otherwise the user sees "tap the app and get thrown back to the desktop with
            // no hint"
            // (measured on Toutiao 2026-10-18: only this path remained that failed silently).
            val changeIds = AppOrientationCompat.changeIdsRegistered()
            var systemOn =
                changeIds && AppOrientationCompat.isSystemEnabledFor(activity.packageName)
            var current = activity
            var healedAndRelaunched = false
            if (!systemOn && changeIds) {
                val healed =
                    AppOrientationCompat.setEnabled(context, activity.packageName, true) ==
                        AppOrientationCompat.WriteResult.OK
                systemOn = healed || AppOrientationCompat.isSystemEnabledFor(activity.packageName)
                VirtualLauncher.logSink?.invoke(
                    "HINT: '${activity.packageName}' system-side landscape override missing - re-write" +
                        "${if (healed) " succeeded" else " failed"}",
                )
                if (systemOn) {
                    // The write already killed the process: wait for it to exit, then restart on the original display (equivalent to the
                    // button action, but automatic)
                    Thread.sleep(600)
                    val start =
                        Shell.exec(
                            "am start -n ${activity.component.asShellArgument()} --display $displayId -f 0x18000000",
                            timeoutMs = 8_000,
                        )
                    VirtualLauncher.logSink?.invoke(
                        "HINT: restart after re-write '${activity.packageName}' ok=" +
                            (start.ok && start.output.contains("Starting:", ignoreCase = true)),
                    )
                    healedAndRelaunched = true
                    Thread.sleep(2_200)
                    val fresh = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
                    current =
                        parseTopLetterboxActivity(displayId, fresh, fallbackW, fallbackH) ?: run {
                            if (isVisible()) clear()
                            return
                        }
                    if (!shouldShowLetterboxHint(
                            current,
                            enabled,
                            synchronized(dismissed) { dismissed.toSet() },
                        )
                    ) {
                        if (isVisible()) clear()
                        VirtualLauncher.logSink?.invoke(
                            "HINT: after re-write and restart '${activity.packageName}' is landscape",
                        )
                        return
                    }
                }
            }
            if (!systemOn) {
                VirtualLauncher.logSink?.invoke(
                    "HINT: '${activity.packageName}' is letterboxed, but this device does not support the landscape override - " +
                        "not prompting a restart (changeIds=$changeIds)",
                )
                if (isVisible()) clear()
                return
            }
            // When the black bars are too narrow (the card does not fit) showCard returns false and attaches no card; the log records only
            // real displays
            val wasVisible = isVisible()
            // Still portrait after re-write and restart -> this app simply ignores the orientation override, so go straight to the "still
            // portrait"
            // final-state card (do not lure the user into tapping a restart that is bound to fail again)
            state = if (healedAndRelaunched) State.FAILED else State.NORMAL
            // If re-probed after self-heal, trust the latest reading (the component may have become the rebuilt instance)
            target = Target(current.packageName, current.component, displayId)
            val shown = showCard(current, state)
            if (!shown) {
                target = null
                return
            }
            if (!wasVisible) {
                VirtualLauncher.logSink?.invoke(
                    "HINT: '${current.packageName}' landscape not in effect ($reason)" +
                        " inner=${current.inner.width}x${current.inner.height}" +
                        " outer=${current.full.width}x${current.full.height}" +
                        " bars left=${current.inner.left - current.full.left}" +
                        "/right=${current.full.right - current.inner.right}",
                )
            }
        } finally {
            probeInFlight = false
        }
    }

    /**
     * The display the app area actually shows: the map display when the map is fullscreen, otherwise the active app display.
     * The desktop (GridActivity) is not on the "app landscape" list, so the probe skips it naturally.
     */
    private fun visibleDisplayId(): Int? {
        if (VirtualLauncher.mapFullscreen && VirtualLauncher.mapDisplayId >= 0) {
            return VirtualLauncher.mapDisplayId
        }
        val id = VirtualLauncher.activeAppDisplayId()
        return if (id >= 0) id else null
    }

    /**
     * Builds and attaches the card from the letterbox rectangle.
     *
     * Coordinate system: the dumpsys bounds are in **the app display's own pixels**; the compositor stretches/scales the whole display into
     * the app area, so they are converted to output-picture coordinates by app-area width / display width (the two axes have equal ratios,
     * use the horizontal one).
     * The card shows only when the black bars are wide enough - the width is measured from the text, with no hard-coded threshold.
     */
    private fun showCard(
        activity: LetterboxActivity,
        newState: State,
    ): Boolean {
        val area = VirtualLauncher.appAreaRect() ?: return false
        val display =
            VirtualLauncher.hintDisplaySize(activity.displayId) ?: return false
        if (display.first <= 0 || area.width() <= 0f) return false
        val scale = area.width() / display.first
        val leftBar = (activity.inner.left - activity.full.left) * scale
        val rightBar = (activity.full.right - activity.inner.right) * scale
        val density = VirtualLauncher.uiDensity()
        val margin = LetterboxCardPainter.marginPx(density)
        val needed = LetterboxCardPainter.minWidthPx(density) + 2f * margin
        // Black bars too narrow (no different from full-screen landscape) -> do not show
        val side = chooseLetterboxSide(leftBar, rightBar, needed) ?: return false
        val barWidth = if (side == LetterboxSide.LEFT) leftBar else rightBar
        val width = minOf(LetterboxCardPainter.maxWidthPx(density), barWidth - 2f * margin).toInt()
        val bitmap =
            LetterboxCardPainter.render(
                density = density,
                widthPx = width,
                maxHeightPx = area.height().toInt(),
                state = newState.toCardState(),
            ) ?: return false
        state = newState
        val barStart = if (side == LetterboxSide.LEFT) area.left else area.right - barWidth
        val left = barStart + (barWidth - bitmap.width) / 2f
        val top = area.top + (area.height() - bitmap.height) / 2f
        cardLeft = left
        cardTop = top
        cardRight = left + bitmap.width
        cardBottom = top + bitmap.height
        VirtualLauncher.logSink?.invoke(
            "HINT: card bitmap=${bitmap.width}x${bitmap.height} rect=" +
                "(${cardLeft.toInt()},${cardTop.toInt()}-${cardRight.toInt()},${cardBottom.toInt()})" +
                " bar=$barWidth density=$density widthPx=$width",
        )
        VirtualLauncher.setOverlayCard(
            OverlayCard(bitmap, cardLeft, cardTop, cardRight, cardBottom),
        )
        return true
    }

    private fun State.toCardState(): LetterboxCardState =
        when (this) {
            State.NORMAL -> LetterboxCardState.NORMAL
            State.BUSY -> LetterboxCardState.BUSY
            State.FAILED -> LetterboxCardState.FAILED
        }

    /**
     * Force-restarts the target app: force-stop -> relaunch on the **original display** -> re-probe.
     * Runs on a background thread; before acting it waits for the orientation-override queue to drain (otherwise the restart still reads
     * the old override).
     */
    private fun restart(t: Target) {
        state = State.BUSY
        reRenderBusy()
        VirtualLauncher.logSink?.invoke("HINT: force restart ${t.packageName} (display=${t.displayId})")
        Bg.run("letterbox-restart") {
            runCatching {
                AppOrientationCompat.awaitPending()
                // The am compat write does kill the process, but the task restores with the old configuration; an explicit stop here makes
                // the next launch take a fresh path (measured: after restart overrideOrientation=NOSENSOR,
                // areBoundsLetterboxed=false).
                Shell.exec("am force-stop ${t.packageName}", timeoutMs = 8_000)
                Thread.sleep(400)
                val start =
                    Shell.exec(
                        "am start -n ${t.component.asShellArgument()} --display ${t.displayId} -f 0x18000000",
                        timeoutMs = 8_000,
                    )
                if (!start.ok) {
                    VirtualLauncher.logSink?.invoke(
                        "HINT: restart ${t.packageName} failed exit=${start.exitCode} out=${start.output.take(120)}",
                    )
                }
                // Wait for the app to rebuild its window, then re-probe
                Thread.sleep(2_000)
                val (w, h) = VirtualLauncher.hintDisplaySize(t.displayId) ?: return@runCatching
                val ctx = VirtualLauncher.appContext() ?: return@runCatching
                val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
                val activity = parseTopLetterboxActivity(t.displayId, dump, w, h)
                val still =
                    activity != null &&
                        activity.packageName == t.packageName &&
                        shouldShowLetterboxHint(
                            activity,
                            AppOrientationCompat.enabledPackages(ctx),
                            synchronized(dismissed) { dismissed.toSet() },
                        )
                if (still) {
                    state = State.FAILED
                    if (!showCard(activity!!, State.FAILED)) {
                        // The black bars cannot fit the card (or the display is released): dismiss directly so a restart does not go
                        // without feedback
                        clear()
                    }
                    VirtualLauncher.logSink?.invoke(
                        "HINT: after restart '${t.packageName}' is still letterboxed (reason=${activity.letterboxReason})",
                    )
                } else {
                    clear()
                    VirtualLauncher.logSink?.invoke("HINT: after restart '${t.packageName}' is landscape")
                }
            }.onFailure {
                state = State.NORMAL
                VirtualLauncher.logSink?.invoke("HINT: restart error ${it.message}")
            }
        }
    }

    /** Redraw in BUSY state: reuse the current card's width/position to avoid re-parsing. */
    private fun reRenderBusy() {
        val density = VirtualLauncher.uiDensity()
        val width = (cardRight - cardLeft).toInt()
        val maxHeight = (VirtualLauncher.appAreaRect()?.height() ?: 0f).toInt()
        val bitmap = LetterboxCardPainter.render(density, width, maxHeight, LetterboxCardState.BUSY) ?: return
        // Align vertically on the center of the original rectangle (the height may change)
        val centerY = (cardTop + cardBottom) / 2f
        cardTop = centerY - bitmap.height / 2f
        cardBottom = cardTop + bitmap.height
        cardRight = cardLeft + bitmap.width
        VirtualLauncher.setOverlayCard(OverlayCard(bitmap, cardLeft, cardTop, cardRight, cardBottom))
    }

    /** For diagnostics: package name of the current hint (null when not shown). */
    fun currentPackage(): String? = target?.packageName

    /** Whether a probe is running (tests / diagnostics). */
    fun isProbing(): Boolean = probeInFlight

    /** Snapshot of the card's last screen coordinates (diagnostics / tests). */
    fun cardRect(): FloatArray = floatArrayOf(cardLeft, cardTop, cardRight, cardBottom)
}
