package io.github.ezn24.droidcar.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.github.ezn24.droidcar.render.OverlayCard
import io.github.ezn24.droidcar.shizuku.CarInputClient
import io.github.ezn24.droidcar.shizuku.Shell
import io.github.ezn24.droidcar.ui.WallpaperStore
import io.github.ezn24.droidcar.util.Bg

/**
 * Head-unit "assistive dot" (requested by the user 2026-11-18, iOS AssistiveTouch style): a small dot on the top layer of the compositor,
 * docked at the top of the picture by default and draggable; tapping it opens a capsule with Back / Home / Recents (the Android navigation
 * trio).
 *
 * Drawn inside the compositor ([DualDisplayComposer.assistiveDot], its own texture slot), never into any app picture
 * and never occupying a virtual display; coordinates are output-picture coordinates (origin at top), so touch hit-testing reuses them
 * directly. Light/dark follows the phone.
 * TouchRouter asks [onContact] first: a hit assigns the whole gesture to the dot and nothing is injected into the app.
 */
object AssistiveDot {
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "assistiveDot"
    private const val KEY_X = "assistiveDotX"
    private const val KEY_Y = "assistiveDotY"
    private const val KEY_SIZE = "assistiveDotSize"

    /** Size percentage range (settings slider). */
    const val SIZE_MIN = 60
    const val SIZE_MAX = 180

    @Volatile private var sizePercent = 100

    /** Drag-detection threshold (stream pixels x scale). */
    private const val DRAG_SLOP = 12f

    /** Auto-collapse after the expanded dot sees no interaction. */
    private const val AUTO_COLLAPSE_MS = 5_000L

    /** Opacity when collapsed (so it does not steal attention from the picture). */
    private const val IDLE_ALPHA = 0.72f

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var context: Context? = null

    /** Dot center (output-picture coordinates). */
    private var centerX = -1f
    private var centerY = -1f
    private var expanded = false
    private var card: OverlayCard? = null

    /** Hit areas of the three buttons in the expanded state. */
    private val buttons = arrayOfNulls<RectF>(3)

    // State of the current gesture
    private var tracking = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var grabDx = 0f
    private var grabDy = 0f

    private var cachedKey = ""
    private var cachedBitmap: Bitmap? = null

    private val collapse = Runnable { setExpanded(false) }

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        handler.post { if (enabled) this.context?.let { show(it) } else hide() }
    }

    fun sizePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SIZE, 100).coerceIn(SIZE_MIN, SIZE_MAX)

    /** Takes effect live while the settings slider is dragged: redraws one small bitmap, no reconnect, no extra standing cost. */
    fun setSizePercent(
        context: Context,
        percent: Int,
    ) {
        val value = percent.coerceIn(SIZE_MIN, SIZE_MAX)
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SIZE, value)
            .apply()
        handler.post {
            sizePercent = value
            if (card != null) publish()
        }
    }

    /** Called once the session picture is ready (any thread, main or otherwise). */
    fun attach(ctx: Context) {
        sizePercent = sizePercent(ctx)
        context = ctx.applicationContext
        handler.post { show(ctx.applicationContext) }
    }

    fun detach() {
        handler.post {
            context = null
            hide()
            centerX = -1f
            cachedBitmap = null
            cachedKey = ""
        }
    }

    /** Redraws when the light/dark scheme changes. */
    fun refresh() {
        handler.post { if (card != null) publish() }
    }

    private fun show(ctx: Context) {
        if (!enabled(ctx) || VirtualLauncher.streamWidthPx <= 0) return
        if (centerX < 0f) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val w = VirtualLauncher.streamWidthPx.toFloat()
            val h = VirtualLauncher.streamHeightPx.toFloat()
            centerX = prefs.getFloat(KEY_X, 0.5f) * w
            centerY = prefs.getFloat(KEY_Y, -1f).let { if (it < 0f) dotSize() / 2f + 6f * scale() else it * h }
        }
        expanded = false
        publish()
    }

    private fun hide() {
        handler.removeCallbacks(collapse)
        expanded = false
        tracking = false
        dragging = false
        card = null
        VirtualLauncher.setAssistiveDot(null)
    }

    private fun scale(): Float =
        (minOf(VirtualLauncher.streamWidthPx, VirtualLauncher.streamHeightPx) / 540f).coerceIn(0.5f, 3f) * sizePercent / 100f

    private fun dotSize(): Float = 34f * scale()

    private fun setExpanded(value: Boolean) {
        handler.removeCallbacks(collapse)
        if (card == null) return
        expanded = value
        if (value) handler.postDelayed(collapse, AUTO_COLLAPSE_MS)
        publish()
    }

    /** Draws the bitmap for the current state and hands it to the compositor. */
    private fun publish() {
        val ctx = context ?: return
        val night = WallpaperStore.isNight(ctx)
        val s = scale()
        val bitmap = bitmapFor(night, s)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        // Anchored on the dot center; the whole bitmap is clamped inside the picture
        val left = (centerX - w / 2f).coerceIn(0f, (VirtualLauncher.streamWidthPx - w).coerceAtLeast(0f))
        val top = (centerY - dotSize() / 2f).coerceIn(0f, (VirtualLauncher.streamHeightPx - h).coerceAtLeast(0f))
        if (expanded) {
            val pad = 6f * s
            val button = (w - pad * 2) / 3f
            for (i in 0..2) buttons[i] = RectF(left + pad + button * i, top, left + pad + button * (i + 1), top + h)
        }
        val next = OverlayCard(bitmap, left, top, left + w, top + h, if (expanded || tracking) 1f else IDLE_ALPHA)
        card = next
        VirtualLauncher.setAssistiveDot(next)
    }

    private fun bitmapFor(
        night: Boolean,
        s: Float,
    ): Bitmap {
        val key = "$night|$expanded|$s"
        cachedBitmap?.takeIf { cachedKey == key }?.let { return it }
        val bitmap = if (expanded) drawPanel(night, s) else drawDot(night, s)
        cachedKey = key
        cachedBitmap = bitmap
        return bitmap
    }

    private fun drawDot(
        night: Boolean,
        s: Float,
    ): Bitmap {
        val size = dotSize()
        val px = size.toInt().coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = px / 2f
        // Rounded square base + three concentric circles (AssistiveTouch style, original drawing)
        paint.color = if (night) 0xE61C1C1E.toInt() else 0xE6F2F2F7.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), px * 0.3f, px * 0.3f, paint)
        val ring = if (night) 0xFFFFFFFF.toInt() else 0xFF3A3A3C.toInt()
        paint.color = (ring and 0x00FFFFFF) or 0x40000000
        canvas.drawCircle(c, c, px * 0.36f, paint)
        paint.color = (ring and 0x00FFFFFF) or 0x80000000.toInt()
        canvas.drawCircle(c, c, px * 0.27f, paint)
        paint.color = ring
        canvas.drawCircle(c, c, px * 0.18f, paint)
        return bitmap
    }

    private fun drawPanel(
        night: Boolean,
        s: Float,
    ): Bitmap {
        val h = (46f * s).toInt().coerceAtLeast(16)
        val pad = 6f * s
        val button = 52f * s
        val w = (button * 3 + pad * 2).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (night) 0xF21C1C1E.toInt() else 0xF7F2F2F7.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint)
        val fg = if (night) 0xFFF5F5F7.toInt() else 0xFF1C1C1E.toInt()
        paint.color = fg
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * s
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        val icon = 9f * s
        val cy = h / 2f

        fun cx(i: Int) = pad + button * i + button / 2f
        // Back: left-pointing triangle
        canvas.drawPath(
            Path().apply {
                moveTo(cx(0) + icon * 0.75f, cy - icon)
                lineTo(cx(0) - icon * 0.85f, cy)
                lineTo(cx(0) + icon * 0.75f, cy + icon)
                close()
            },
            paint,
        )
        // Home: circle
        canvas.drawCircle(cx(1), cy, icon, paint)
        // Recents: rounded square
        canvas.drawRoundRect(RectF(cx(2) - icon * 0.9f, cy - icon * 0.9f, cx(2) + icon * 0.9f, cy + icon * 0.9f), 3f * s, 3f * s, paint)
        return bitmap
    }

    /**
     * Touch entry point (touch thread). Returns true = this gesture belongs to the dot.
     * [appGestureActive]: a finger is already down on an app (multi-touch); the dot does not steal it then.
     */
    fun onContact(
        down: Boolean,
        x: Float,
        y: Float,
        appGestureActive: Boolean,
    ): Boolean {
        val current = card ?: return false.also { tracking = false }
        if (!tracking) {
            if (!down) return false
            if (current.contains(x, y)) {
                if (appGestureActive) return false
                tracking = true
                dragging = false
                downX = x
                downY = y
                grabDx = centerX - x
                grabDy = centerY - y
                handler.removeCallbacks(collapse)
                return true
            }
            // Tapping outside while expanded = collapse; this tap is not forwarded to the app
            if (expanded && !appGestureActive) {
                tracking = true
                dragging = false
                downX = Float.NaN
                handler.post { setExpanded(false) }
                return true
            }
            return false
        }
        if (down) {
            if (!downX.isNaN() && !expanded && (dragging || kotlin.math.hypot(x - downX, y - downY) > DRAG_SLOP * scale())) {
                if (!dragging) {
                    dragging = true
                }
                val nx = x + grabDx
                val ny = y + grabDy
                handler.post {
                    centerX = nx
                    centerY = ny
                    publish()
                }
            }
            return true
        }
        // Lift
        tracking = false
        val wasDrag = dragging
        dragging = false
        if (downX.isNaN()) return true
        handler.post {
            when {
                wasDrag -> {
                    savePosition()
                    publish()
                }
                !expanded -> setExpanded(true)
                else -> {
                    val index = buttons.indexOfFirst { it?.contains(x, y) == true }
                    setExpanded(false)
                    when (index) {
                        0 -> back()
                        1 -> VirtualLauncher.showDashboard()
                        2 -> context?.let { VirtualLauncher.showRecentApps(it) }
                    }
                }
            }
        }
        return true
    }

    private fun savePosition() {
        val ctx = context ?: return
        val w = VirtualLauncher.streamWidthPx.toFloat().coerceAtLeast(1f)
        val h = VirtualLauncher.streamHeightPx.toFloat().coerceAtLeast(1f)
        centerX = centerX.coerceIn(0f, w)
        centerY = centerY.coerceIn(0f, h)
        ctx
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_X, centerX / w)
            .putFloat(KEY_Y, centerY / h)
            .apply()
    }

    /**
     * Which display receives Back: fullscreen map -> map display; dashboard -> the last-operated map / music app (default map);
     * otherwise -> the active app display.
     */
    private fun backTarget(): Int {
        val launcher = VirtualLauncher
        if (launcher.mapFullscreen && launcher.mapDisplayId >= 0) return launcher.mapDisplayId
        if (launcher.dashboardMode) {
            val last = launcher.lastTouchedDisplay
            if (last >= 0 && (last == launcher.mapDisplayId || last == launcher.mediaDisplayId)) return last
            if (launcher.mapDisplayId >= 0) return launcher.mapDisplayId
        }
        return launcher.activeAppDisplayId()
    }

    /**
     * Back key: sent to the current foreground display (see [backTarget]). Also the head unit's Back key, so both
     * reach the same app (on the dashboard the last touched map / media pane, not the desktop behind them).
     */
    fun back() {
        val display = backTarget()
        if (display < 0) return
        Bg.run("assistive-back") {
            if (!CarInputClient.keyEvent(display, KeyEvent.KEYCODE_BACK)) {
                Shell.exec("input -d $display keyevent ${KeyEvent.KEYCODE_BACK}", timeoutMs = 3_000L)
            }
            // Back at the root page of a map / music app leaves that display: bring it back so the dashboard is not left blank
            VirtualLauncher.restorePaneAfterBack(display)
        }
    }
}
