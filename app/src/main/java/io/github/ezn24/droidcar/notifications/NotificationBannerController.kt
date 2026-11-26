package io.github.ezn24.droidcar.notifications

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
import io.github.ezn24.droidcar.R
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import io.github.ezn24.droidcar.launcher.settings.DisplayPrefs
import io.github.ezn24.droidcar.render.OverlayCard
import io.github.ezn24.droidcar.ui.UiStrings
import io.github.ezn24.droidcar.ui.WallpaperStore
import io.github.ezn24.droidcar.util.Bg
import java.util.concurrent.atomic.AtomicInteger

/** A time-limited car notification; text drawing and reading other APK resources happen in the background; notification content is not stored. */
object NotificationBannerController {
    /** Swiping up beyond this distance (stream pixels) = dismiss the banner. */
    private const val SWIPE_DISMISS_PX = 30f

    /** Duration of enter (slide in from above) / exit (slide out upward), in milliseconds. */
    private const val ENTER_MS = 320L
    private const val EXIT_MS = 220L
    private const val FRAME_MS = 16L

    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var expiry: Runnable? = null

    @Volatile private var card: OverlayCard? = null

    @Volatile private var key: String? = null

    @Volatile private var component: ComponentName? = null

    /** Resting position of the banner once settled; animation frames are moved off it. */
    @Volatile private var target: OverlayCard? = null

    /** Hit area of the x button (output-frame coordinates). */
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
        // Separate "notification apps" list: empty = all; otherwise only apps in the list pass
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
            // Glass body = the bitmap inset by the shadow margin drawn around it in renderBanner, same corner radius
            val next =
                OverlayCard(bitmap, left, top, left + width, top + height, glassRadius = 22f * scale, glassInset = 10f * scale)
            val zone = android.graphics.RectF(closeRect).apply { offset(left, top) }
            val launch = app.packageManager.getLaunchIntentForPackage(sbn.packageName)?.component
            handler.post {
                if (token != generation.get() || VirtualLauncher.appContext() == null) return@post
                expiry?.let { handler.removeCallbacks(it) }
                key = sbn.key
                component = launch
                // A banner is already showing -> swap the content in place; otherwise slide in from above
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
     * CarPlay-style banner: rounded app icon on the left; on the right the first row is app name + "now", the second a bold title, the
     * last the content.
     * Light/dark follows the phone (same criterion as car wallpaper and car settings), translucent background + soft shadow.
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
        // Frosted glass (the compositor blurs what lies under the body, see OverlayCard.glassRadius): the shadow is drawn with an
        // opaque shape whose inside is then cleared, so it does not darken the see-through body, and the body itself is a translucent tint
        paint.color = 0xFF000000.toInt()
        paint.setShadowLayer(shadow * 0.8f, 0f, 2f * scale, 0x59000000)
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.clearShadowLayer()
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.xfermode = null
        paint.color = if (night) 0x9E2C2C2E.toInt() else 0xB8F2F2F7.toInt()
        canvas.drawRoundRect(card, radius, radius, paint)

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

        // Top-right close button: small round background + x
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
        // Hit area larger than the glyph (fingers are thick on the car): a square at the top-right
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

    /** Cleared after sliding out upward; use [clear] when it must vanish at once (e.g. session end). */
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

    /** Move the banner per frame on the main thread: only position and alpha change, the bitmap stays (compositor does not re-upload the texture). */
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
                    // Fade from translucent to opaque on enter, fade out on exit
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
