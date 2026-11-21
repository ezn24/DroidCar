package io.github.ezn24.cardeck.neo.call

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView

/**
 * Caller photo and name from a notification's custom view. WeChat rings with a custom RemoteViews and neither a
 * CallStyle person nor a large icon (2026-11-22: `avatar=none` on every WeChat call), so both only exist inside that
 * layout. The views are inflated off-screen (background thread, never attached to a window).
 *
 * Inflate with the application context: an AppCompat Activity's inflater turns `ImageView` into `AppCompatImageView`,
 * on which RemoteViews refuses `setImageBitmap` and the whole apply fails (2026-11-22, every WeChat call).
 */
object CallAvatar {
    class Extracted(
        val photo: Bitmap?,
        /** Largest text in the layout (the caller name in WeChat's ringing view). Unverified against other layouts. */
        val name: String?,
        /** Per image candidate: view id, size, opaque share, colour spread. Numbers only, no pixel content. */
        val candidates: List<String> = emptyList(),
    )

    fun fromViews(
        context: Context,
        views: RemoteViews,
        appLabel: String?,
    ): Extracted {
        val app = context.applicationContext
        val root = views.apply(app, FrameLayout(app))
        var photo: Bitmap? = null
        var photoScore = 0f
        var name: String? = null
        var nameSize = 0f
        val candidates = ArrayList<String>()

        fun visit(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is ImageView) {
                val bmp = v.drawable?.let(::toBitmap)
                if (bmp != null && !bmp.isRecycled && isPhotoShaped(bmp)) {
                    val idName = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: "-"
                    val (opaque, spread) = pixelStats(bmp)
                    // A photo: mostly opaque (a round crop still covers ~78%) and not one flat colour. A blank placeholder or a
                    // tinted background picked as "largest square" showed as nothing on the card (2026-11-22: photo=true but
                    // no picture)
                    val looksLikePhoto = opaque >= 0.5f && spread >= 12f
                    val hint = PHOTO_ID_HINTS.any { idName.contains(it, ignoreCase = true) }
                    val score =
                        if (!looksLikePhoto) 0f else (if (hint) 10f else 1f) + bmp.width * bmp.height / 1_000_000f
                    val chosen = score > photoScore
                    if (chosen) {
                        photo = bmp
                        photoScore = score
                    }
                    candidates += "$idName ${bmp.width}x${bmp.height} opaque=${"%.2f".format(opaque)} spread=${spread.toInt()}"
                }
            } else if (v is TextView) {
                val text =
                    v.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                if (text.isNotEmpty() && text != appLabel && !TIME_LIKE.matches(text) && v.textSize > nameSize) {
                    name = text
                    nameSize = v.textSize
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        // Bitmaps the layout carries but did not draw (set on a view type we skipped, or hidden): RemoteViews keeps every
        // setImageViewBitmap payload in its bitmap cache
        for (bmp in cachedBitmaps(views)) {
            if (bmp.isRecycled || !isPhotoShaped(bmp)) continue
            val (opaque, spread) = pixelStats(bmp)
            candidates += "cache ${bmp.width}x${bmp.height} opaque=${"%.2f".format(opaque)} spread=${spread.toInt()}"
            val score = if (opaque >= 0.5f && spread >= 12f) 0.5f + bmp.width * bmp.height / 1_000_000f else 0f
            if (score > photoScore) {
                photo = bmp
                photoScore = score
            }
        }
        candidates += "actions ${actionSummary(views)}"
        return Extracted(photo, name, candidates)
    }

    private fun cachedBitmaps(views: RemoteViews): List<Bitmap> =
        runCatching {
            val cache =
                RemoteViews::class.java
                    .getDeclaredField("mBitmapCache")
                    .apply { isAccessible = true }
                    .get(views)
            val list =
                cache.javaClass
                    .getDeclaredField("mBitmaps")
                    .apply { isAccessible = true }
                    .get(cache)
            (list as? List<*>).orEmpty().filterIsInstance<Bitmap>()
        }.getOrElse { emptyList() }

    /** Kinds of operations the layout applies (class and method names, value types; never values), for the log. */
    private fun actionSummary(views: RemoteViews): String =
        runCatching {
            val actions =
                RemoteViews::class.java
                    .getDeclaredField("mActions")
                    .apply { isAccessible = true }
                    .get(views) as? List<*>
            actions.orEmpty().filterNotNull().joinToString(",") { action ->
                val method =
                    generateSequence<Class<*>>(action.javaClass) { it.superclass }
                        .mapNotNull { c -> runCatching { c.getDeclaredField("mMethodName") }.getOrNull() }
                        .firstOrNull()
                        ?.apply { isAccessible = true }
                        ?.get(action)
                action.javaClass.simpleName + (method?.let { ":$it" } ?: "")
            }
        }.getOrElse { "unreadable(${it.javaClass.simpleName})" }

    /** Any drawable with a size (BitmapDrawable, rounded drawables, icons) as a bitmap of at most 256 px. */
    private fun toBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w < MIN_PHOTO_PX || h < MIN_PHOTO_PX) return null
        val scale = minOf(1f, 256f / maxOf(w, h))
        val out = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, out.width, out.height)
        drawable.draw(android.graphics.Canvas(out))
        return out
    }

    /** Opaque pixel share and the standard deviation of luminance over opaque pixels, on a 24x24 sample grid. */
    private fun pixelStats(bmp: Bitmap): Pair<Float, Float> {
        val n = 24
        var opaque = 0
        var sum = 0.0
        var sumSq = 0.0
        for (y in 0 until n) {
            for (x in 0 until n) {
                val c = bmp.getPixel((x * bmp.width + bmp.width / 2) / n, (y * bmp.height + bmp.height / 2) / n)
                if (c ushr 24 < 128) continue
                opaque++
                val lum = 0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)
                sum += lum
                sumSq += lum * lum
            }
        }
        if (opaque == 0) return 0f to 0f
        val mean = sum / opaque
        val spread = kotlin.math.sqrt((sumSq / opaque - mean * mean).coerceAtLeast(0.0)).toFloat()
        return opaque.toFloat() / (n * n) to spread
    }

    private fun isPhotoShaped(bmp: Bitmap): Boolean {
        val min = minOf(bmp.width, bmp.height)
        val max = maxOf(bmp.width, bmp.height)
        return min >= MIN_PHOTO_PX && max <= min * 1.3f
    }

    private const val MIN_PHOTO_PX = 64
    private val PHOTO_ID_HINTS = listOf("avatar", "head", "photo", "portrait")
    private val TIME_LIKE = Regex("[0-9:：.\\s]+")
}
