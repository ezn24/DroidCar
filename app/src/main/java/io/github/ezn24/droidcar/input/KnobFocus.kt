package io.github.ezn24.droidcar.input

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import io.github.ezn24.droidcar.launcher.DockActivity
import io.github.ezn24.droidcar.launcher.GridActivity
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import io.github.ezn24.droidcar.render.OverlayCard
import io.github.ezn24.droidcar.shizuku.CarInputClient
import io.github.ezn24.droidcar.util.Bg

/**
 * Knob / D-pad focus on the car screen (head-unit HID navigation keys).
 *
 * On DroidCar's own screens (dashboard, desktop, car settings, background apps) everything choosable is a target in
 * stream coordinates; direction keys move to the nearest target in that direction (dock included) and the composer
 * draws a white frame around it; Confirm clicks it. Only the dashboard's map card and media pane are "entered" with
 * Confirm (keys then go to that app, the frame turns green, Home steps back out), because two apps share that screen.
 * A third-party app or the fullscreen map gets the keys directly, as before. The media card's play controls are a row
 * of their own: Down from the card enters it, Left / Right move inside, Up leaves (so Left / Right on the card keep
 * moving between the map, the card and the dock). Any car touch hides the frame.
 *
 * All state lives on the main thread (views are read and clicked there).
 */
object KnobFocus {
    private val main = Handler(Looper.getMainLooper())

    enum class Kind { DOCK, CELL, MAP, MEDIA_CARD, MEDIA_BUTTON, VIEW }

    class Target(
        val rect: RectF,
        val kind: Kind,
        val key: String,
        val view: View? = null,
    )

    /** Key of the framed target, null = no frame (knob idle or after a touch). */
    private var focusKey: String? = null

    /** Dashboard display the knob is inside (map / media), -1 = choosing targets. */
    @Volatile
    private var insideDisplay = -1
    private var insideKey: String? = null

    /** A DroidCar screen shown on the car's app area (car settings, background apps), registered on resume. */
    @Volatile
    private var ownScreen: Activity? = null

    @Volatile
    var log: (String) -> Unit = {}

    fun onOwnScreenResumed(activity: Activity) {
        ownScreen = activity
    }

    fun onOwnScreenPaused(activity: Activity) {
        if (ownScreen === activity) ownScreen = null
    }

    /** Whether DroidCar itself (not a third-party app) fills the car's content area right now. */
    private fun droidCarOnScreen(): Boolean {
        val launcher = VirtualLauncher
        if (launcher.mapFullscreen) return false
        if (launcher.dashboardMode) return true
        if (ownScreen != null) return true
        return GridActivity.current?.isDesktopOnCar() == true
    }

    /** Head-unit knob key; returns true when handled here (the caller does nothing else). */
    fun onKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return onBack()
        val direction =
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        val inside = insideDisplay >= 0
        val handled = (direction && (inside || droidCarOnScreen())) || (keyCode == KeyEvent.KEYCODE_HOME && inside)
        if (!handled) {
            // A third-party app gets the keys itself: no frame over it
            if (direction) main.post { clearFrame() }
            return false
        }
        main.post { runCatching { handle(keyCode) }.onFailure { log("FOCUS: key failed - ${it.message}") } }
        return true
    }

    /**
     * Back: inside a dashboard app it goes to that app; otherwise the assistive dot's routing decides (fullscreen map,
     * last used dashboard pane, active app), with the framed dashboard card counting as the last used pane.
     */
    private fun onBack(): Boolean {
        val inside = insideDisplay
        if (inside >= 0) {
            Bg.run("knob-back") {
                injectKey(inside, KeyEvent.KEYCODE_BACK)
                // Back at the app's root left the pane empty (it was brought back): step out to its card,
                // the same as Home
                if (VirtualLauncher.restorePaneAfterBack(inside)) main.post { leaveInside("back at root") }
            }
            return true
        }
        when (focusKey) {
            "map" -> VirtualLauncher.lastTouchedDisplay = VirtualLauncher.mapDisplayId
            "media" -> if (VirtualLauncher.isMediaPaneVisible()) VirtualLauncher.lastTouchedDisplay = VirtualLauncher.mediaDisplayId
        }
        return false
    }

    /** A touch on the car screen: the knob frame goes away (touch and knob do not fight over one highlight). */
    fun onTouch() {
        if (focusKey == null && insideDisplay < 0) return
        main.post {
            insideDisplay = -1
            insideKey = null
            clearFrame()
        }
    }

    /** Session end: forget everything. */
    fun reset() {
        main.post {
            focusKey = null
            insideDisplay = -1
            insideKey = null
        }
    }

    private fun clearFrame() {
        if (focusKey == null) return
        focusKey = null
        VirtualLauncher.setFocusRing(null)
    }

    private fun injectKey(
        display: Int,
        keyCode: Int,
    ) {
        if (!CarInputClient.keyEvent(display, keyCode)) {
            io.github.ezn24.droidcar.shizuku.Shell
                .exec("input -d $display keyevent $keyCode", 3_000L)
        }
    }

    private fun leaveInside(reason: String) {
        if (insideDisplay < 0) return
        log("FOCUS: left display $insideDisplay ($reason)")
        focusKey = insideKey
        insideDisplay = -1
        insideKey = null
        redraw(targets())
    }

    /**
     * A DroidCar screen came back (an app was backed out of): the next knob key starts from the item that opened it.
     */
    fun onDroidCarResumed() {
        main.post {
            if (resumeKey != null && insideDisplay < 0) {
                focusKey = null
                VirtualLauncher.setFocusRing(null)
            }
        }
    }

    /** Item (dock entry / desktop cell) that opened the last app: framed again when DroidCar returns. */
    private var resumeKey: String? = null

    private fun handle(keyCode: Int) {
        if (insideDisplay >= 0) {
            if (keyCode == KeyEvent.KEYCODE_HOME) {
                leaveInside("home")
                return
            }
            val display = insideDisplay
            Bg.run("knob-inside") { injectKey(display, keyCode) }
            return
        }
        val targets = targets()
        if (targets.isEmpty()) return
        val current = targets.firstOrNull { it.key == focusKey }
        if (current == null) {
            // First key: frame the item that opened the last app if it is here, else something sensible
            val resumed = resumeKey?.let { key -> targets.firstOrNull { it.key == key } }
            resumeKey = null
            focusKey = resumed?.key ?: (
                targets.firstOrNull { it.kind == Kind.MAP }
                    ?: targets.firstOrNull { it.kind != Kind.DOCK && it.kind != Kind.MEDIA_BUTTON }
                    ?: targets.first()
            ).key
            redraw(targets)
            return
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            activate(current)
            return
        }
        val next = move(current, targets, keyCode)
        val grid = GridActivity.current
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && (next == null || next.kind == Kind.DOCK) && grid != null) {
            // Right edge: the dashboard slides to the desktop, a desktop page to the next one
            val turned =
                when (current.kind) {
                    Kind.MAP, Kind.MEDIA_CARD -> {
                        grid.animateDashboardToDesktop()
                        true
                    }
                    Kind.CELL -> grid.turnDesktopPage(true)
                    else -> false
                }
            if (turned) {
                focusKey = null
                main.postDelayed({ frameFirst(KeyEvent.KEYCODE_DPAD_RIGHT) }, 450)
                return
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
            current.kind == Kind.CELL &&
            (next == null || next.kind == Kind.DOCK) &&
            grid != null &&
            grid.currentPage() > 1 &&
            grid.turnDesktopPage(false)
        ) {
            focusKey = null
            main.postDelayed({ frameFirst(KeyEvent.KEYCODE_DPAD_LEFT) }, 450)
            return
        }
        if (next != null) {
            focusKey = next.key
            redraw(targets)
        } else if (current.kind == Kind.VIEW && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            scrollOwnScreen(keyCode == KeyEvent.KEYCODE_DPAD_DOWN, current)
        }
    }

    /** After a page turn: frame the first content target on the side we came from. */
    private fun frameFirst(keyCode: Int) {
        val targets = targets()
        val content = targets.filter { it.kind != Kind.DOCK && it.kind != Kind.MEDIA_BUTTON }
        val pick =
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                content.maxWithOrNull(compareBy<Target>({ it.rect.right }, { -it.rect.top }))
            } else {
                content.minWithOrNull(compareBy<Target>({ it.rect.left }, { it.rect.top }))
            }
        focusKey = pick?.key
        redraw(targets)
    }

    /** Direction rules: the media controls are a row of their own, entered with Down and left with Up. */
    private fun move(
        current: Target,
        targets: List<Target>,
        keyCode: Int,
    ): Target? {
        val buttons = targets.filter { it.kind == Kind.MEDIA_BUTTON }
        return when {
            current.kind == Kind.MEDIA_BUTTON ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> targets.firstOrNull { it.kind == Kind.MEDIA_CARD }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> nearest(current, buttons, keyCode)
                    else -> null
                }
            current.kind == Kind.MEDIA_CARD && keyCode == KeyEvent.KEYCODE_DPAD_DOWN ->
                // Start on play / pause, the middle one
                buttons.getOrNull(buttons.size / 2) ?: nearest(current, targets.filter { it.kind != Kind.MEDIA_BUTTON }, keyCode)
            else -> nearest(current, targets.filter { it.kind != Kind.MEDIA_BUTTON }, keyCode)
        }
    }

    private fun activate(target: Target) {
        when (target.kind) {
            Kind.DOCK, Kind.CELL -> {
                resumeKey = target.key
                target.view?.performClick()
            }
            Kind.MEDIA_BUTTON, Kind.VIEW -> target.view?.performClick()
            Kind.MAP -> enter(VirtualLauncher.mapDisplayId, target)
            Kind.MEDIA_CARD ->
                if (VirtualLauncher.isMediaPaneVisible()) {
                    enter(VirtualLauncher.mediaDisplayId, target)
                } else {
                    // The whole card opens the music app in the pane, then the knob goes into it
                    target.view?.performClick()
                    main.postDelayed({
                        if (VirtualLauncher.isMediaPaneVisible()) enter(VirtualLauncher.mediaDisplayId, target)
                    }, 800)
                }
        }
    }

    private fun enter(
        display: Int,
        target: Target,
    ) {
        if (display < 0) return
        insideDisplay = display
        insideKey = target.key
        focusKey = target.key
        VirtualLauncher.lastTouchedDisplay = display
        log("FOCUS: entered display $display (${target.kind})")
        redraw(targets())
    }

    /** Nearest target whose centre lies in [keyCode]'s direction; distance along the axis counts less than sideways drift. */
    internal fun nearest(
        from: Target,
        all: List<Target>,
        keyCode: Int,
    ): Target? {
        val cx = from.rect.centerX()
        val cy = from.rect.centerY()
        var best: Target? = null
        var bestScore = Float.MAX_VALUE
        for (candidate in all) {
            if (candidate === from || candidate.key == from.key) continue
            val dx = candidate.rect.centerX() - cx
            val dy = candidate.rect.centerY() - cy
            val (along, across) =
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dx to dy
                    KeyEvent.KEYCODE_DPAD_LEFT -> -dx to dy
                    KeyEvent.KEYCODE_DPAD_DOWN -> dy to dx
                    KeyEvent.KEYCODE_DPAD_UP -> -dy to dx
                    else -> return null
                }
            if (along <= 1f) continue
            val score = along + kotlin.math.abs(across) * 2f
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    /** Everything choosable right now, in stream coordinates. */
    private fun targets(): List<Target> {
        val list = ArrayList<Target>()
        val launcher = VirtualLauncher
        DockActivity.current?.focusViews()?.forEachIndexed { index, view ->
            rectOf(view)?.let { r ->
                val rect =
                    if (launcher.dockAtBottom) RectF(r.left, r.top + launcher.appHeightPx, r.right, r.bottom + launcher.appHeightPx) else r
                list += Target(rect, Kind.DOCK, "dock$index", view)
            }
        }
        val grid = GridActivity.current
        val gridScale = launcher.gridScale().coerceAtLeast(1f)
        val offsetX = if (launcher.dockAtBottom) 0f else launcher.dockWidthPx.toFloat()

        fun fromGrid(view: View): RectF? =
            rectOf(view)?.let { r ->
                RectF(
                    offsetX + r.left / gridScale,
                    r.top / gridScale,
                    offsetX + r.right / gridScale,
                    r.bottom / gridScale,
                )
            }
        val own = ownScreen
        when {
            own != null && !launcher.dashboardMode -> {
                clickableViews(own.window?.decorView).forEachIndexed { index, view ->
                    fromGrid(view)?.let { list += Target(it, Kind.VIEW, "own${System.identityHashCode(view)}_$index", view) }
                }
            }
            launcher.dashboardMode && !launcher.mapFullscreen -> {
                if (launcher.mapDisplayId >= 0) {
                    val map = launcher.mapPaneBounds()
                    list += Target(RectF(map.left, map.top, map.right, map.bottom), Kind.MAP, "map")
                }
                val panel = grid?.dashboardFocusViews()
                if (launcher.isMediaPaneVisible()) {
                    val pane = launcher.mediaAppBounds()
                    list += Target(RectF(pane.left, pane.top, pane.right, pane.bottom), Kind.MEDIA_CARD, "media", panel?.firstOrNull())
                } else if (panel != null) {
                    panel.firstOrNull()?.let { card -> fromGrid(card)?.let { list += Target(it, Kind.MEDIA_CARD, "media", card) } }
                    panel.drop(1).forEachIndexed { index, button ->
                        if (button.isShown) fromGrid(button)?.let { list += Target(it, Kind.MEDIA_BUTTON, "mediaBtn$index", button) }
                    }
                }
            }
            grid != null && grid.isDesktopOnCar() -> {
                grid.desktopFocusViews().forEachIndexed { index, cell ->
                    fromGrid(cell)?.let { list += Target(it, Kind.CELL, "cell${grid.currentPage()}_$index", cell) }
                }
            }
        }
        return list
    }

    /** Visible, enabled, clickable views; a clickable row hides the clickable views inside it (one target per row). */
    private fun clickableViews(root: View?): List<View> {
        val out = ArrayList<View>()
        val visible = Rect()

        fun walk(view: View) {
            if (!view.isShown) return
            if (view.isClickable && view.isEnabled && view.getGlobalVisibleRect(visible) && visible.width() > 4 && visible.height() > 4) {
                out += view
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        root?.let(::walk)
        // The decor's own content root is never a target
        return out.filter { it !== root }
    }

    /** No target further down / up on a DroidCar screen: scroll its list and frame the next visible row. */
    private fun scrollOwnScreen(
        down: Boolean,
        from: Target,
    ) {
        val root = ownScreen?.window?.decorView ?: return
        val scroller = findScrollable(root, if (down) 1 else -1) ?: return
        val amount = (scroller.height * 0.6f).toInt() * if (down) 1 else -1
        if (scroller is androidx.recyclerview.widget.RecyclerView) scroller.scrollBy(0, amount) else scroller.scrollBy(0, amount)
        main.postDelayed({
            val targets = targets()
            val next = nearest(from, targets, if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            if (next != null) focusKey = next.key
            redraw(targets)
        }, 120)
    }

    private fun findScrollable(
        view: View,
        direction: Int,
    ): View? {
        if (view.isShown && view.canScrollVertically(direction)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findScrollable(view.getChildAt(i), direction)?.let { return it }
        }
        return null
    }

    private fun rectOf(view: View): RectF? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return RectF(
            location[0].toFloat(),
            location[1].toFloat(),
            (location[0] + view.width).toFloat(),
            (location[1] + view.height).toFloat(),
        )
    }

    private fun redraw(targets: List<Target>) {
        val target = targets.firstOrNull { it.key == focusKey }
        if (target == null) {
            VirtualLauncher.setFocusRing(null)
            return
        }
        val inside = insideDisplay >= 0
        val stroke = (minOf(VirtualLauncher.streamWidthPx, VirtualLauncher.streamHeightPx) * 0.006f).coerceAtLeast(3f)
        val pad = stroke
        val rect = RectF(target.rect.left - pad, target.rect.top - pad, target.rect.right + pad, target.rect.bottom + pad)
        val w = rect.width().toInt().coerceAtLeast(4)
        val h = rect.height().toInt().coerceAtLeast(4)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                // System blue reads on the dark launcher and the light settings alike (white vanished on light mode)
                color = if (inside) Color.rgb(48, 209, 88) else Color.rgb(10, 132, 255)
            }
        val radius = minOf(w, h) * 0.12f
        Canvas(bitmap).drawRoundRect(RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2), radius, radius, paint)
        VirtualLauncher.setFocusRing(OverlayCard(bitmap, rect.left, rect.top, rect.left + w, rect.top + h))
    }
}
