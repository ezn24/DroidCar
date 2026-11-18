package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.github.shaonialife.cardeck.render.OverlayCard
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * 车机「辅助小白点」（2026-11-18 用户要求，iOS AssistiveTouch 式）：合成器最上层的一颗小圆点，
 * 默认贴在画面顶部，可拖动；点开成一条胶囊，提供 返回 / 主页 / 后台 三键（Android 导航那套）。
 *
 * 画在合成器里（[DualDisplayComposer.assistiveDot]，独立纹理槽），不进任何 App 画面、
 * 不占虚拟屏；坐标是输出画面坐标（顶部原点），触摸命中直接复用。深浅色跟随手机。
 * 触摸由 TouchRouter 最先询问 [onContact]：命中即整段手势归小白点，不注入 App。
 */
object AssistiveDot {
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "assistiveDot"
    private const val KEY_X = "assistiveDotX"
    private const val KEY_Y = "assistiveDotY"
    private const val KEY_SIZE = "assistiveDotSize"

    /** 大小百分比范围（设置滑杆）。 */
    const val SIZE_MIN = 60
    const val SIZE_MAX = 180

    @Volatile private var sizePercent = 100

    /** 拖动判定阈值（流像素 × scale）。 */
    private const val DRAG_SLOP = 12f

    /** 展开后无操作自动收起。 */
    private const val AUTO_COLLAPSE_MS = 5_000L

    /** 收起状态的透明度（不抢画面）。 */
    private const val IDLE_ALPHA = 0.72f

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var context: Context? = null

    /** 圆点中心（输出画面坐标）。 */
    private var centerX = -1f
    private var centerY = -1f
    private var expanded = false
    private var card: OverlayCard? = null

    /** 展开态三个按钮的命中区。 */
    private val buttons = arrayOfNulls<RectF>(3)

    // 一段手势的状态
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

    /** 设置滑杆拖动时实时生效：只重画一张小位图，不重连、不增加常驻开销。 */
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

    /** 会话画面就绪后调用（主线程或任意线程皆可）。 */
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

    /** 深浅色变更时重画。 */
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

    /** 按当前状态画位图并交给合成器。 */
    private fun publish() {
        val ctx = context ?: return
        val night = WallpaperStore.isNight(ctx)
        val s = scale()
        val bitmap = bitmapFor(night, s)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        // 以圆点中心为锚，整张夹在画面内
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
        // 圆角方底 + 三层同心圆（AssistiveTouch 式，原创绘制）
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
        // 返回：左指三角
        canvas.drawPath(
            Path().apply {
                moveTo(cx(0) + icon * 0.75f, cy - icon)
                lineTo(cx(0) - icon * 0.85f, cy)
                lineTo(cx(0) + icon * 0.75f, cy + icon)
                close()
            },
            paint,
        )
        // 主页：圆
        canvas.drawCircle(cx(1), cy, icon, paint)
        // 后台：圆角方
        canvas.drawRoundRect(RectF(cx(2) - icon * 0.9f, cy - icon * 0.9f, cx(2) + icon * 0.9f, cy + icon * 0.9f), 3f * s, 3f * s, paint)
        return bitmap
    }

    /**
     * 触摸入口（触摸线程）。返回 true = 这段手势归小白点。
     * [appGestureActive]：已有手指在 App 上按着（多指），此时不抢。
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
            // 展开时点外面 = 收起，这一下不传给 App
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
        // 抬起
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
     * 返回键发给哪块屏：全屏地图 → 地图屏；仪表盘 → 最后操作的地图 / 音乐 App（默认地图）；
     * 其它 → 活动应用屏。
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

    /** 返回键：发给当前前台屏（见 [backTarget]）。 */
    private fun back() {
        val display = backTarget()
        if (display < 0) return
        Bg.run("assistive-back") {
            if (!CarInputClient.keyEvent(display, KeyEvent.KEYCODE_BACK)) {
                Shell.exec("input -d $display keyevent ${KeyEvent.KEYCODE_BACK}", timeoutMs = 3_000L)
            }
        }
    }
}
