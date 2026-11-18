package io.github.shaonialife.cardeck.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.text.TextPaint
import android.text.TextUtils
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.render.OverlayCard
import io.github.shaonialife.cardeck.ui.UiStrings
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg
import java.util.concurrent.atomic.AtomicInteger

/** 一张有时限的车机通知；绘字和读取其他 APK 资源都在后台，不保存通知内容。 */
object NotificationBannerController {
    /** 往上滑超过这个距离（流像素）= 关掉横幅。 */
    private const val SWIPE_DISMISS_PX = 30f

    /** 进场（从上方滑入）/ 出场（往上滑出）时长，毫秒。 */
    private const val ENTER_MS = 320L
    private const val EXIT_MS = 220L
    private const val FRAME_MS = 16L

    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var expiry: Runnable? = null

    @Volatile private var card: OverlayCard? = null

    @Volatile private var key: String? = null

    @Volatile private var component: ComponentName? = null

    /** 横幅停稳后的位置；动画帧都由它挪出来。 */
    @Volatile private var target: OverlayCard? = null

    /** × 的命中区（输出画面坐标）。 */
    @Volatile private var closeZone: android.graphics.RectF? = null

    @Volatile private var leaving = false
    private var animation: Runnable? = null
    private var heldGeneration = -1
    private var downX = 0f
    private var downY = 0f
    private var signature = ""
    private var lastPost = 0L

    fun post(
        context: Context,
        sbn: StatusBarNotification,
    ) {
        if (VirtualLauncher.appContext() == null || !DisplayPrefs.showCarNotifications(context)) return
        // 独立的「通知 App」名单：空 = 全部；否则只放行名单内的 App
        val allowed = DisplayPrefs.notificationApps(context)
        if (allowed.isNotEmpty() && sbn.packageName !in allowed) return
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text =
            (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty()
        if (!shouldShowNotificationBanner(
                sbn.packageName == context.packageName,
                sbn.isOngoing,
                notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                notification.category == Notification.CATEGORY_TRANSPORT,
                title.isNotBlank() || text.isNotBlank(),
            )
        ) {
            return
        }
        if (notification.visibility == Notification.VISIBILITY_SECRET) return
        val now = android.os.SystemClock.elapsedRealtime()
        val nextSignature = "${sbn.key}|$title|$text"
        if (signature == nextSignature && now - lastPost < 5000) return
        signature = nextSignature
        lastPost = now
        val token = generation.incrementAndGet()
        val app = context.applicationContext
        Bg.run("notification-banner") {
            if (token != generation.get()) return@run
            val area = VirtualLauncher.appAreaRect() ?: return@run
            val scale = (minOf(VirtualLauncher.streamWidthPx, VirtualLauncher.streamHeightPx) / 540f).coerceIn(0.5f, 3f)
            val locked = app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            val pm = app.packageManager
            val label =
                runCatching { pm.getApplicationInfo(sbn.packageName, 0).loadLabel(pm).toString() }
                    .getOrDefault(sbn.packageName)
            val icon = runCatching { pm.getApplicationIcon(sbn.packageName) }.getOrNull()
            val (bitmap, closeRect) =
                renderBanner(
                    scale = scale,
                    maxWidth = area.width().toFloat(),
                    maxHeight = area.height() / 3f,
                    night = WallpaperStore.isNight(app),
                    icon = icon,
                    appName = label,
                    title = if (locked) UiStrings.text(R.string.new_notification) else title,
                    body = if (locked) "" else text,
                    now = UiStrings.text(R.string.notif_now),
                )
            val width = bitmap.width
            val height = bitmap.height
            val left = area.left + (area.width() - width) / 2f
            val top = area.top + 8f * scale
            val next = OverlayCard(bitmap, left, top, left + width, top + height)
            val zone = android.graphics.RectF(closeRect).apply { offset(left, top) }
            val launch = app.packageManager.getLaunchIntentForPackage(sbn.packageName)?.component
            handler.post {
                if (token != generation.get() || VirtualLauncher.appContext() == null) return@post
                expiry?.let { handler.removeCallbacks(it) }
                key = sbn.key
                component = launch
                // 已有横幅在显示 → 原位换内容；否则从画面上方滑入
                val from = if (card != null && !leaving) next.top else -next.bitmap.height.toFloat()
                target = next
                closeZone = zone
                leaving = false
                animate(token, from, next.top, ENTER_MS, easeOut = true, onDone = null)
                expiry = Runnable { if (token == generation.get()) dismiss() }
                handler.postDelayed(expiry!!, 5000)
            }
        }
    }

    /**
     * CarPlay 式横幅：左侧圆角 App 图标；右侧首行 App 名 + 「现在」，次行粗体标题，末行内容。
     * 深浅色跟随手机（与车机壁纸、车机设置同一判据），半透明底 + 柔和投影。
     */
    private fun renderBanner(
        scale: Float,
        maxWidth: Float,
        maxHeight: Float,
        night: Boolean,
        icon: android.graphics.drawable.Drawable?,
        appName: String,
        title: String,
        body: String,
        now: String,
    ): Pair<Bitmap, android.graphics.RectF> {
        val shadow = 10f * scale
        val cardW = minOf(maxWidth * 0.7f, 640f * scale)
        val cardH = minOf(92f * scale, maxHeight)
        val width = (cardW + shadow * 2).toInt().coerceAtLeast(1)
        val height = (cardH + shadow * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val radius = 22f * scale
        val card = android.graphics.RectF(shadow, shadow, shadow + cardW, shadow + cardH)
        paint.color = if (night) 0xF22C2C2E.toInt() else 0xF7FFFFFF.toInt()
        paint.setShadowLayer(shadow * 0.8f, 0f, 2f * scale, 0x59000000)
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.clearShadowLayer()

        val primary = if (night) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val secondary = if (night) 0xFF98989F.toInt() else 0xFF6C6C70.toInt()
        val bodyColor = if (night) 0xFFE5E5EA.toInt() else 0xFF1C1C1E.toInt()

        val pad = 14f * scale
        val iconSize = minOf(52f * scale, cardH - pad * 2)
        val iconLeft = card.left + pad
        val iconTop = card.top + (cardH - iconSize) / 2f
        if (icon != null) {
            val clip = android.graphics.Path()
            clip.addRoundRect(
                android.graphics.RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize),
                iconSize * 0.225f,
                iconSize * 0.225f,
                android.graphics.Path.Direction.CW,
            )
            canvas.save()
            canvas.clipPath(clip)
            icon.setBounds(iconLeft.toInt(), iconTop.toInt(), (iconLeft + iconSize).toInt(), (iconTop + iconSize).toInt())
            icon.draw(canvas)
            canvas.restore()
        }
        val textLeft = iconLeft + iconSize + 12f * scale
        val textRight = card.right - pad

        // 右上角关闭键：小圆底 + ×
        val closeD = 24f * scale
        val closeCx = card.right - 12f * scale - closeD / 2f
        val closeCy = card.top + 12f * scale + closeD / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (night) 0x33FFFFFF else 0x14000000
        canvas.drawCircle(closeCx, closeCy, closeD / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * scale
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (night) 0xFFD1D1D6.toInt() else 0xFF3C3C43.toInt()
        val arm = closeD * 0.2f
        canvas.drawLine(closeCx - arm, closeCy - arm, closeCx + arm, closeCy + arm, paint)
        canvas.drawLine(closeCx - arm, closeCy + arm, closeCx + arm, closeCy - arm, paint)
        paint.style = Paint.Style.FILL
        // 命中区比图形大（车机上手指粗）：右上角一块方形
        val hit = 56f * scale
        val closeRect = android.graphics.RectF(card.right - hit, card.top, card.right + shadow, card.top + hit)

        val metaRight = closeCx - closeD / 2f - 10f * scale
        paint.textSize = 13f * scale
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.color = secondary
        val nowWidth = paint.measureText(now)
        canvas.drawText(now, metaRight - nowWidth, card.top + 28f * scale, paint)

        fun line(
            value: String,
            size: Float,
            y: Float,
            color: Int,
            bold: Boolean,
            right: Float,
        ) {
            if (value.isBlank()) return
            paint.textSize = size * scale
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            paint.color = color
            val clipped =
                TextUtils
                    .ellipsize(value.replace('\n', ' '), paint, (right - textLeft).coerceAtLeast(1f), TextUtils.TruncateAt.END)
                    .toString()
            canvas.drawText(clipped, textLeft, card.top + y * scale, paint)
        }
        line(appName.uppercase(), 13f, 28f, secondary, false, metaRight - nowWidth - 10f * scale)
        line(title, 17f, 52f, primary, true, metaRight)
        line(body, 15f, 75f, bodyColor, false, textRight)
        return bitmap to closeRect
    }

    fun remove(notificationKey: String) {
        if (key == notificationKey) dismiss()
    }

    /** 往上滑出后清掉；会话结束等需要立即消失的场合用 [clear]。 */
    fun dismiss() {
        handler.post {
            val current = card ?: return@post
            if (leaving) return@post
            leaving = true
            expiry?.let { handler.removeCallbacks(it) }
            val token = generation.get()
            animate(token, current.top, -current.bitmap.height.toFloat(), EXIT_MS, easeOut = false) {
                if (token == generation.get()) clear()
            }
        }
    }

    fun clear() {
        generation.incrementAndGet()
        expiry?.let { handler.removeCallbacks(it) }
        animation?.let { handler.removeCallbacks(it) }
        expiry = null
        animation = null
        card = null
        target = null
        closeZone = null
        leaving = false
        key = null
        component = null
        VirtualLauncher.setNotificationOverlay(null)
    }

    /** 在主线程按帧挪动横幅：只换位置与透明度，位图不变（合成器不重传纹理）。 */
    private fun animate(
        token: Int,
        from: Float,
        to: Float,
        durationMs: Long,
        easeOut: Boolean,
        onDone: (() -> Unit)?,
    ) {
        animation?.let { handler.removeCallbacks(it) }
        val base = target ?: return
        val start = android.os.SystemClock.uptimeMillis()
        val frame =
            object : Runnable {
                override fun run() {
                    if (token != generation.get()) return
                    val t = ((android.os.SystemClock.uptimeMillis() - start) / durationMs.toFloat()).coerceIn(0f, 1f)
                    val k = if (easeOut) 1f - (1f - t) * (1f - t) * (1f - t) else t * t * t
                    val top = from + (to - from) * k
                    // 进场由半透明变实，出场变淡
                    val alpha = if (easeOut) 0.4f + 0.6f * k else 1f - 0.6f * k
                    val next = base.moved(top, alpha)
                    card = next
                    VirtualLauncher.setNotificationOverlay(next)
                    if (t < 1f) {
                        handler.postDelayed(this, FRAME_MS)
                    } else {
                        animation = null
                        onDone?.invoke()
                    }
                }
            }
        animation = frame
        frame.run()
    }

    fun onContact(
        down: Boolean,
        x: Float,
        y: Float,
    ): Boolean {
        val visible = card
        if (heldGeneration < 0 &&
            (visible == null || leaving || !VirtualLauncher.isNotificationOverlayVisible() || !visible.contains(x, y))
        ) {
            return false
        }
        if (down) {
            if (heldGeneration < 0) {
                heldGeneration = generation.get()
                downX = x
                downY = y
            }
            return true
        }
        val same = heldGeneration == generation.get() && !leaving
        heldGeneration = -1
        if (!same) return true
        val moved = kotlin.math.abs(x - downX) + kotlin.math.abs(y - downY)
        if (moved < 30f && closeZone?.contains(downX, downY) == true) {
            dismiss()
        } else if (moved < 30f && card?.contains(x, y) == true) {
            val launch = component
            dismiss()
            if (launch != null) Bg.run("notification-open-app") { VirtualLauncher.launchApp(launch) }
        } else if (downY - y > SWIPE_DISMISS_PX) {
            dismiss()
        }
        return true
    }
}
