package io.github.ezn24.droidcar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import io.github.ezn24.droidcar.util.Bg
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Frosted-glass background that stays aligned with the backdrop while the view moves ("pseudo real-time" blur).
 *
 * The [source] is the desktop wallpaper ImageView behind the card. Its bitmap is scaled down to 1/[DOWNSCALE] and Gaussian-blurred
 * once on a background thread whenever the wallpaper or the strength changes; every frame only draws the part of that small
 * blurred image that lies under this view, scaled back up. Moving the card (page slides) therefore costs one bitmap draw.
 *
 * The first versions recorded the source into a RenderNode with a RenderEffect blur: HWUI drew the node but never applied the
 * effect there, so the card showed the sharp wallpaper through its tint (car screenshot, 2026-11-26). The CPU blur is
 * deterministic and runs on a ~180k-pixel image only when the wallpaper changes.
 *
 * [blurSigma] is in this view's pixels; the same sigma the compositor uses for the dock glass (VirtualLauncher.glassSigmaPx).
 */
class BackdropBlurView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        /** The wallpaper ImageView behind this one. */
        var source: View? = null
            set(value) {
                if (field === value) return
                field = value
                invalidateBackdrop()
            }

        /** Gaussian sigma in pixels (of this view); 0 = the backdrop is shown sharp. */
        var blurSigma: Float = 0f
            set(value) {
                if (field == value) return
                field = value
                invalidateBackdrop()
            }

        @Volatile
        private var blurred: Bitmap? = null

        /** Source bitmap the blurred image was made from. */
        private var builtFrom: Bitmap? = null
        private val generation = AtomicInteger()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val ownLocation = IntArray(2)
        private val sourceLocation = IntArray(2)
        private var lastDx = Int.MIN_VALUE
        private var lastDy = Int.MIN_VALUE

        private val preDraw =
            ViewTreeObserver.OnPreDrawListener {
                val (dx, dy) = offsetInSource() ?: return@OnPreDrawListener true
                if (dx != lastDx || dy != lastDy) invalidate()
                true
            }

        /** The source's content changed (new wallpaper): blur it again in the background. */
        fun invalidateBackdrop() {
            builtFrom = null
            rebuild()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(preDraw)
            rebuild()
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnPreDrawListener(preDraw)
            super.onDetachedFromWindow()
        }

        private fun sourceBitmap(): Bitmap? = ((source as? ImageView)?.drawable as? BitmapDrawable)?.bitmap

        private fun rebuild() {
            val bitmap = sourceBitmap() ?: return
            if (bitmap === builtFrom) return
            builtFrom = bitmap
            val sigma = blurSigma
            val token = generation.incrementAndGet()
            Bg.run("glass-blur") {
                val result = runCatching { blur(bitmap, sigma / DOWNSCALE) }.getOrNull()
                if (token != generation.get() || result == null) return@run
                post {
                    blurred = result
                    invalidate()
                }
            }
        }

        /** This view's top-left in the source's coordinates (both measured in the window). */
        private fun offsetInSource(): Pair<Int, Int>? {
            val src = source ?: return null
            if (!isAttachedToWindow || !src.isAttachedToWindow) return null
            getLocationInWindow(ownLocation)
            src.getLocationInWindow(sourceLocation)
            return (ownLocation[0] - sourceLocation[0]) to (ownLocation[1] - sourceLocation[1])
        }

        override fun onDraw(canvas: Canvas) {
            val src = source ?: return
            if (sourceBitmap() !== builtFrom) rebuild()
            val image = blurred ?: return
            if (width <= 0 || height <= 0 || src.width <= 0 || src.height <= 0) return
            val (dx, dy) = offsetInSource() ?: return
            lastDx = dx
            lastDy = dy
            // The blurred image covers the whole source view: map this view's rectangle into it
            val sx = image.width.toFloat() / src.width
            val sy = image.height.toFloat() / src.height
            srcRect.set(
                (dx * sx).roundToInt(),
                (dy * sy).roundToInt(),
                ((dx + width) * sx).roundToInt(),
                ((dy + height) * sy).roundToInt(),
            )
            dstRect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(image, srcRect, dstRect, paint)
        }

        private companion object {
            /** The blurred backdrop is held at a quarter of the width and height; the blur hides the lower resolution. */
            const val DOWNSCALE = 4f

            /** Scales [source] down and applies a Gaussian of [sigma] (in the scaled image's pixels) as three box blurs. */
            fun blur(
                source: Bitmap,
                sigma: Float,
            ): Bitmap {
                val w = (source.width / DOWNSCALE).roundToInt().coerceAtLeast(1)
                val h = (source.height / DOWNSCALE).roundToInt().coerceAtLeast(1)
                val readable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
                val small = Bitmap.createScaledBitmap(readable, w, h, true)
                if (sigma < 0.5f) return small
                val pixels = IntArray(w * h)
                small.getPixels(pixels, 0, w, 0, 0, w, h)
                val scratch = IntArray(w * h)
                for (radius in boxRadii(sigma)) {
                    boxPass(pixels, scratch, w, h, radius, horizontal = true)
                    boxPass(scratch, pixels, w, h, radius, horizontal = false)
                }
                return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            }

            /** Radii of three box blurs whose combination approximates a Gaussian of [sigma] (Kovesi / Wells). */
            fun boxRadii(sigma: Float): IntArray {
                val ideal = sqrt(12f * sigma * sigma / 3f + 1f)
                var lower = ideal.toInt()
                if (lower % 2 == 0) lower--
                val upper = lower + 2
                val m = ((12f * sigma * sigma - 3 * lower * lower - 12 * lower - 9) / (-4f * lower - 4)).roundToInt()
                return IntArray(3) { i -> ((if (i < m) lower else upper) - 1) / 2 }
            }

            /** One box blur of [radius] along rows or columns, reading [input], writing [output] (edges clamped). */
            fun boxPass(
                input: IntArray,
                output: IntArray,
                w: Int,
                h: Int,
                radius: Int,
                horizontal: Boolean,
            ) {
                val length = if (horizontal) w else h
                val lines = if (horizontal) h else w
                val span = radius * 2 + 1
                for (line in 0 until lines) {
                    fun at(i: Int): Int {
                        val c = i.coerceIn(0, length - 1)
                        return if (horizontal) input[line * w + c] else input[c * w + line]
                    }
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    for (i in -radius..radius) {
                        val p = at(i)
                        a += p ushr 24
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                    for (i in 0 until length) {
                        val index = if (horizontal) line * w + i else i * w + line
                        output[index] = ((a / span) shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
                        val out = at(i - radius)
                        val add = at(i + radius + 1)
                        a += (add ushr 24) - (out ushr 24)
                        r += ((add shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                        g += ((add shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                        b += (add and 0xFF) - (out and 0xFF)
                    }
                }
            }
        }
    }
