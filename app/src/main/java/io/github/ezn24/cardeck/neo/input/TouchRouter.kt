package io.github.ezn24.cardeck.neo.input

import android.content.Context
import io.github.ezn24.cardeck.neo.input.TouchReports.Contact
import io.github.ezn24.cardeck.neo.launcher.CardGesture
import io.github.ezn24.cardeck.neo.launcher.LetterboxHintController
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher
import io.github.ezn24.cardeck.neo.launcher.resolveCardGesture
import io.github.ezn24.cardeck.neo.launcher.settings.DisplayPrefs
import io.github.ezn24.cardeck.neo.render.CARD_MARGIN_PX
import io.github.ezn24.cardeck.neo.shizuku.CarInputClient
import io.github.ezn24.cardeck.neo.util.Bg

/**
 * Touch routing: injects head-unit hidSendReport touch points into the car-side virtual displays (invisible-display mode).
 *
 * Split by main-picture x coordinate: x < dock width -> dock display; otherwise -> app display (x shifted by the dock width).
 * Injection goes through the Shizuku UserService (1:1 real coordinates, millisecond-level injection).
 *
 * (The former "desktop" (direct Presentation projection) and "mirror" (accessibility continueStroke) modes
 *  were removed on demand, 2026-10-10.)
 */
object TouchRouter {
    /** Diagnostic log sink (wired to the file log by MainActivity). */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - diagAt < 1000L) return
        diagAt = now
        logSink?.invoke("TOUCH: $message")
    }

    private var pressed = false
    private var pressedDisplay = -1
    private var failStreak = 0

    /** Overlay-card gesture state (after hitting a card it owns the touch exclusively and nothing is injected into the app). */
    private var overlayPressed = false

    /** Split-handle sticky-routing state (after hitting the handle bar the whole touch stream is pinned to the app display). */
    private var splitHandlePressed = false

    /** This press was used to dismiss the voice-assistant overlay (tap on the dock); events are swallowed until lift. */
    private var assistDockSwallow = false

    private fun inDockArea(
        px: Int,
        py: Int,
    ): Boolean = if (VirtualLauncher.dockAtBottom) py >= VirtualLauncher.appHeightPx else px < VirtualLauncher.dockWidthPx

    private val dashboardSwipe = DashboardSwipeGesture()
    private val touchReleases = TouchReleaseTracker()

    /**
     * MOVE injection throttle: 0 = off; when >0 a move less than that many ms after the last injection
     * is merged and dropped (down/up boundary events and cross-display switches are never throttled, the final position is carried by up).
     * Symptom: laggy interaction with a normal frame rate (event dispatch piling up). Adjustable in the "Display"
     * settings panel, default 24ms.
     */
    @Volatile
    var moveThrottleMs: Long = 24L

    @Volatile
    private var throttleInited = false

    private var lastInjectAt = 0L
    private var throttledCount = 0
    private var lastThrottleLogAt = 0L

    // Conversion factor from head-unit touch coordinates (advertised view coordinate system, e.g. 1160x540) -> stream
    // coordinates (scaled, e.g. 1450x674).
    // With resolution scaling != 100% the stream is larger than the view; without conversion taps drift to the lower
    // right (dock routing is misaligned too).
    @Volatile
    private var coordScaleX = 1f

    @Volatile
    private var coordScaleY = 1f

    /** Set at session setup: view (the head unit's advertised display) size -> stream (encode/virtual display) size. */
    fun setCoordinateScale(
        viewW: Int,
        viewH: Int,
        streamW: Int,
        streamH: Int,
    ) {
        coordScaleX = if (viewW > 0) streamW.toFloat() / viewW else 1f
        coordScaleY = if (viewH > 0) streamH.toFloat() / viewH else 1f
    }

    fun onContact(
        context: Context,
        report: Contact,
    ) {
        val contact = touchReleases.accept(report) ?: return
        val px = (contact.xPixels * coordScaleX).toInt().coerceAtLeast(0)
        val py = (contact.yPixels * coordScaleY).toInt().coerceAtLeast(0)

        // The assistive dot is drawn on the top layer, so ask it first
        if (io.github.ezn24.cardeck.neo.launcher.AssistiveDot
                .onContact(contact.down, px.toFloat(), py.toFloat(), pressed)
        ) {
            return
        }
        // Overlay cards (the "forced landscape not in effect" hint) consume touch first: they are drawn on the app area's black borders
        // and must hit before the injection decision, otherwise the touch would pass through to the app below.
        run {
            val visible = LetterboxHintController.isVisible()
            val hit = visible && LetterboxHintController.contains(px.toFloat(), py.toFloat())
            when (resolveCardGesture(visible, overlayPressed, contact.down, hit, appGestureActive = pressed)) {
                CardGesture.NOT_HANDLED -> overlayPressed = false
                CardGesture.CONSUME -> {
                    overlayPressed = contact.down
                    return
                }
                CardGesture.TAP -> {
                    overlayPressed = false
                    LetterboxHintController.onTap()
                    return
                }
            }
        }

        // When the voice-assistant overlay is on the car, a tap on the dock (outside the overlay's display, where it
        // cannot receive it) = dismiss the overlay;
        // the whole press->lift is swallowed so the dock button is not triggered at the same time. Taps inside the
        // overlay's display are injected as usual and
        // Gemini handles them itself (tapping blank space collapses it).
        if (assistDockSwallow ||
            (
                contact.down &&
                    !pressed &&
                    io.github.ezn24.cardeck.neo.assist.AssistOverlayController
                        .isOnCar() &&
                    inDockArea(px, py)
            )
        ) {
            if (!assistDockSwallow) {
                io.github.ezn24.cardeck.neo.assist.AssistOverlayController
                    .dismissFromCar()
            }
            assistDockSwallow = contact.down
            return
        }
        if (io.github.ezn24.cardeck.neo.notifications.NotificationBannerController.onContact(
                contact.down,
                px.toFloat(),
                py.toFloat(),
            )
        ) {
            return
        }
        if (io.github.ezn24.cardeck.neo.launcher.MediaReturnControl
                .onContact(contact.down, px.toFloat(), py.toFloat(), pressed)
        ) {
            return
        }
        val launcher = VirtualLauncher
        // The split map card is directly operable (2026-11-15 user request, no more tap-to-fullscreen): gestures
        // pressed inside the map card
        // go to the map and must not start the dashboard left swipe, otherwise dragging the map horizontally would flip to the app page
        val splitMapPane = splitMapPane(launcher)
        val assistLayer =
            io.github.ezn24.cardeck.neo.assist.AssistOverlayController
                .isOnAssistLayer()
        val canStartSwipe =
            !assistLayer &&
                !launcher.assistCoversDashboard &&
                launcher.dashboardMode &&
                !launcher.mapFullscreen &&
                !launcher.isLayoutTransitioning() &&
                splitMapPane?.contains(px.toFloat(), py.toFloat()) != true &&
                (if (launcher.dockAtBottom) py < launcher.appHeightPx else px >= launcher.dockWidthPx)
        val gesture =
            dashboardSwipe.update(
                contact.slot,
                contact.down,
                px.toFloat(),
                py.toFloat(),
                canStartSwipe,
                maxOf(16f, minOf(launcher.streamWidthPx, launcher.streamHeightPx) * 0.025f),
            )
        when (gesture) {
            DashboardSwipeGesture.Result.NONE -> Unit
            DashboardSwipeGesture.Result.CONSUME -> return
            DashboardSwipeGesture.Result.BEGIN_DRAG -> {
                logSink?.invoke("LAUNCHER: dashboard left drag -> finger-following page flip")
                if (!launcher.beginDashboardDragOut()) {
                    // No pager to drag (desktop not ready): fall back to the old behavior and switch straight to the app page
                    Bg.run("dashboard-swipe") { launcher.showDesktop() }
                    return
                }
                launcher.dashboardDragBy(dashboardDragPixels(launcher, dashboardSwipe.dragDelta))
                return
            }
            DashboardSwipeGesture.Result.DRAG -> {
                launcher.dashboardDragBy(dashboardDragPixels(launcher, dashboardSwipe.dragDelta))
                return
            }
            DashboardSwipeGesture.Result.END_DRAG -> {
                launcher.endDashboardDrag()
                return
            }
            DashboardSwipeGesture.Result.TAP -> {
                // Classified as a tap: replay a full down + up at the press point
                val sx = dashboardSwipe.startX.toInt()
                val sy = dashboardSwipe.startY.toInt()
                inject(context, contact.copy(down = true), sx, sy)
                inject(context, contact.copy(down = false), sx, sy)
                return
            }
            DashboardSwipeGesture.Result.RELEASE -> {
                // Vertical/right swipe handed back to the app: replay down at the press point first, then inject the
                // current position as usual
                inject(context, contact.copy(down = true), dashboardSwipe.startX.toInt(), dashboardSwipe.startY.toInt())
            }
        }
        inject(context, contact, px, py)
    }

    /**
     * Stream-pixel displacement -> desktop (Grid) display pixels: with the dock on the left in landscape the desktop
     * display may be supersampled.
     */
    private fun dashboardDragPixels(
        launcher: VirtualLauncher,
        dx: Float,
    ): Float = if (launcher.dockAtBottom) dx else dx * launcher.gridScale().coerceAtLeast(1f)

    /** Coordinate routing + injection (the part after gesture classification). */
    private fun inject(
        context: Context,
        contact: Contact,
        px: Int,
        py: Int,
    ) {
        val launcher = VirtualLauncher
        val splitMapPane = splitMapPane(launcher)
        val assistLayer =
            io.github.ezn24.cardeck.neo.assist.AssistOverlayController
                .isOnAssistLayer()
        if (launcher.isLayoutTransitioning()) {
            if (!contact.down) {
                if (pressed) runCatching { CarInputClient.service?.pointerUp(pressedDisplay, 0, 0) }
                pressed = false
                pressedDisplay = -1
            }
            return
        }
        // During animation do not inject static coordinates into the moving picture; after the first frame it operates normally.
        if (launcher.isLayoutTransitioning()) return
        val dockW = launcher.dockWidthPx
        if (dockW <= 0 || launcher.appDisplayId < 0 || launcher.dockDisplayId < 0) return
        var svc = CarInputClient.service
        if (svc == null) {
            // Proactively trigger a rebind (binder lost after a phone/Shizuku restart).
            // **Must be in the background**: ensure internally makes a synchronous binder call (bindUserService), and this
            // touch comes from the iAP2 event thread; the main thread is even worse (the Shizuku callback also runs
            // on the main thread, self-lock).
            Bg.run("touch-rebind") { CarInputClient.ensure(context) }
            svc = CarInputClient.service
        }
        if (svc == null) {
            failStreak++
            // Report the first one and then every 200th: a real device sees dozens of touches per second, logging each would drown the log
            if (failStreak == 1 || failStreak % 200 == 0) {
                diag(
                    "injection service unavailable ($failStreak consecutive failures, rebinding in the background; " +
                        "if it keeps failing open the Shizuku app or allow it to run in the background in system settings)",
                )
            }
            return
        }
        failStreak = 0
        // Main-picture coordinates -> routing:
        //  landscape: dock is a left strip (x < dockW), app on the right (x shifted by dockW)
        //  portrait: dock is a bottom bar (y > appHeight), app on top
        //  map display (dashboard/fullscreen, takes precedence over the app area): fullscreen = the whole app area;
        //  split = the tile area right of the dock.
        //  The map display is created 1:1 with the stream and coordinates are just shifted; everything else goes to
        //  the active app display (with supersampling scale conversion).
        var inDock: Boolean
        var targetDisplay = -1
        var localX = 0
        var localY = 0
        var routedToMap = false
        // Split-handle sticky routing: a press landing in the handle bar -> the whole touch stream is pinned to the
        // app display (where Grid lives)
        // until lift. During a fast drag the handle lags behind the finger and coordinates leave the handle bar (even
        // cross the map tile edge);
        // re-routing per frame by coordinates would re-inject across displays (pointerUp on the app display +
        // pointerDown on the map display), cutting the drag
        // and giving the map a stray touch (pinned by the 2026-10-21 design review). Same mode as the overlay-card gesture ownership above.
        val handlePin =
            splitHandlePressed ||
                (contact.down && !pressed && launcher.dashHandleHit(px.toFloat(), py.toFloat()))
        if (handlePin) {
            splitHandlePressed = contact.down
            inDock = false
            targetDisplay =
                if (launcher.gridDisplayId >= 0) launcher.gridDisplayId else launcher.activeAppDisplayId()
            if (launcher.dockAtBottom) {
                localX = px
                localY = py
            } else {
                val scale = launcher.gridScale().coerceAtLeast(1f)
                localX = ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        } else if (launcher.dockAtBottom) {
            val appH = launcher.appHeightPx
            inDock = py >= appH
            // Map tile (upper half of the app area; fullscreen = the whole app area). Touch/stream coordinates count from the top:
            // tile rectangle py in [CARD_MARGIN_PX, +mapTileHeight], in-display y = py - margin (the top margin is deducted)
            if (!inDock &&
                !launcher.assistCoversDashboard &&
                launcher.mapDisplayId >= 0 &&
                launcher.mapFullscreen &&
                launcher.tileSlideFraction <= 0.01f &&
                px < launcher.appAreaWidth &&
                (
                    launcher.mapFullscreen ||
                        (py >= CARD_MARGIN_PX && py < CARD_MARGIN_PX + launcher.mapTileHeight)
                )
            ) {
                inDock = false
                targetDisplay = launcher.mapDisplayId
                localX = px
                localY = if (launcher.mapFullscreen) py else py - CARD_MARGIN_PX
                routedToMap = true
            }
            if (!routedToMap) {
                // The landscape bottom dock (Android Auto style) also has a supersampled app display: use the active app display + scale
                // (a portrait head unit has no supersampled display, activeAppScale is always 1, behavior identical to the old version)
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
                val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
                localX = if (inDock) px else (px * scale).toInt()
                localY = if (inDock) py - appH else (py * scale).toInt()
            }
        } else {
            inDock = px < dockW
            if (!inDock &&
                !launcher.assistCoversDashboard &&
                launcher.mapDisplayId >= 0 &&
                launcher.mapFullscreen &&
                launcher.tileSlideFraction <= 0.01f
            ) {
                val tileX = px - dockW
                val tileVisibleW = if (launcher.mapFullscreen) Int.MAX_VALUE else launcher.mapTileWidth
                if (tileX in 0 until tileVisibleW) {
                    inDock = false
                    targetDisplay = launcher.mapDisplayId
                    // The map display resizes dynamically with the layout (split = tile size, fullscreen = app-area size),
                    // the display content is the coordinate domain: the offset from the app area's left edge is the
                    // in-display coordinate, just shifted
                    localX = tileX
                    localY = py
                    routedToMap = true
                }
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
                val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
                localX = if (inDock) px else ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        }
        // Split map card: a press landing inside the card -> the whole gesture is injected into the map display
        // (dragging outside the card still sticks to the map display, like the media card).
        // The map display is resized to the card size when split (1:1 with mapPaneBounds), so coordinates only need shifting.
        if (splitMapPane != null &&
            (
                (!pressed && splitMapPane.contains(px.toFloat(), py.toFloat())) ||
                    (pressed && pressedDisplay == launcher.mapDisplayId)
            )
        ) {
            inDock = false
            targetDisplay = launcher.mapDisplayId
            localX = (px - splitMapPane.left).toInt().coerceIn(0, splitMapPane.width - 1)
            localY = (py - splitMapPane.top).toInt().coerceIn(0, splitMapPane.height - 1)
        }
        if (launcher.isMediaPaneVisible() && !launcher.assistCoversDashboard) {
            val pane = launcher.mediaAppBounds()
            if (pane.contains(px.toFloat(), py.toFloat()) ||
                (pressed && pressedDisplay == launcher.mediaDisplayId)
            ) {
                inDock = false
                targetDisplay = launcher.mediaDisplayId
                // The media display is supersampled (VirtualLauncher.mediaSupersample)
                val scale = VirtualLauncher.mediaSupersample
                localX = ((px - pane.left).toInt() * scale).coerceIn(0, pane.width * scale - 1)
                localY = ((py - pane.top).toInt() * scale).coerceIn(0, pane.height * scale - 1)
            }
        }
        // The voice-assistant layer is on top: all touches in the app area go to it (tapping blank space makes Gemini
        // collapse itself), taking precedence over card routing.
        // The assistant display is the same size as the app area, 1:1, so coordinates only need the app-area origin
        // subtracted; a gesture already on the assistant display at press time sticks to it.
        if (!inDock && (assistLayer || (pressed && pressedDisplay == launcher.assistDisplayId)) && launcher.assistDisplayId >= 0) {
            targetDisplay = launcher.assistDisplayId
            localX = (if (launcher.dockAtBottom) px else px - dockW).coerceIn(0, launcher.appAreaWidth - 1)
            localY = py.coerceIn(0, launcher.appAreaHeight - 1)
        }
        if (targetDisplay < 0) {
            diag("invalid target display dock=${launcher.dockDisplayId} app=${launcher.appDisplayId}")
            return
        }
        // MOVE throttle: consecutive moves on the same display too close together are merged and dropped (state
        // machine untouched, the latest position
        // is carried by later move/up). down/up/cross-display switches are not throttled.
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (!throttleInited) {
            throttleInited = true
            moveThrottleMs =
                DisplayPrefs
                    .moveThrottleMs(context)
                    .toLong()
        }
        if (moveThrottleMs > 0 &&
            contact.down &&
            pressed &&
            pressedDisplay == targetDisplay &&
            nowMs - lastInjectAt < moveThrottleMs
        ) {
            throttledCount++
            if (nowMs - lastThrottleLogAt > 5_000) {
                logSink?.invoke(
                    "TOUCH: move throttle ${moveThrottleMs}ms in effect - merged $throttledCount so far",
                )
                throttledCount = 0
                lastThrottleLogAt = nowMs
            }
            return
        }
        try {
            val ok: Boolean =
                when {
                    contact.down && !pressed -> {
                        pressed = true
                        pressedDisplay = targetDisplay
                        launcher.lastTouchedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    }

                    contact.down && pressedDisplay != targetDisplay -> {
                        // Cross-display drag: lift on the old display first, then press on the new one
                        runCatching { svc.pointerUp(pressedDisplay, 0, 0) }
                        pressedDisplay = targetDisplay
                        launcher.lastTouchedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    }

                    contact.down -> svc.pointerMove(targetDisplay, localX, localY)

                    else -> {
                        pressed = false
                        svc.pointerUp(pressedDisplay, localX, localY)
                    }
                }
            if (ok) lastInjectAt = nowMs
            if (!ok) {
                diag(
                    "injection rejected disp=$targetDisplay ($localX,$localY) " +
                        "down=${contact.down} pressed=$pressed pressedDisplay=$pressedDisplay",
                )
            } else if (contact.down) {
                diag("injection OK disp=$targetDisplay ($localX,$localY)")
            }
        } catch (e: Exception) {
            diag("injection error: ${e.message}")
        }
    }

    fun reset() {
        pressed = false
        pressedDisplay = -1
        overlayPressed = false
        splitHandlePressed = false
        dashboardSwipe.reset()
        touchReleases.reset()
        io.github.ezn24.cardeck.neo.launcher.MediaReturnControl
            .reset()
        coordScaleX = 1f
        coordScaleY = 1f
    }

    /**
     * Map-card area directly operable in split mode (stream coordinates); null when not split / the card is sliding
     * / the assistant overlay is active.
     */
    private fun splitMapPane(launcher: VirtualLauncher): io.github.ezn24.cardeck.neo.render.PaneBounds? =
        if (!launcher.assistCoversDashboard &&
            launcher.dashboardMode &&
            !launcher.mapFullscreen &&
            launcher.mapDisplayId >= 0 &&
            launcher.tileSlideFraction <= 0.01f
        ) {
            launcher.mapPaneBounds()
        } else {
            null
        }
}
