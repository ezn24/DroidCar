package io.github.ezn24.cardeck.neo.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * CarPlay / iOS 16 style battery icon: rounded battery body + terminal nub on the right, filled by charge level,
 * the percentage digits **knocked out** of the body; while charging the fill turns green with a bolt on the right, <=20% turns red.
 *
 * Size is determined by height only (fixed aspect ratio), so it can use wrap_content in both the dock's vertical and horizontal bars.
 */
class BatteryGlyphView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private var percent = -1
        private var charging = false

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
        private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, DIGIT_WEIGHT, false)
                textAlign = Paint.Align.CENTER
                // Knock-out: erase the body where the digits are, revealing the dock background behind (the iOS 16 approach)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
        private val whiteTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, DIGIT_WEIGHT, false)
                textAlign = Paint.Align.CENTER
                color = 0xFFFFFFFF.toInt()
            }
        private val body = RectF()
        private val clip = Path()
        private val bolt = Path()

        fun setLevel(
            percent: Int,
            charging: Boolean,
        ) {
            val p = percent.coerceIn(0, 100)
            if (p == this.percent && charging == this.charging) return
            this.percent = p
            this.charging = charging
            contentDescription = "$p%"
            requestLayout()
            invalidate()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val defaultH = (12f * resources.displayMetrics.density).toInt()
            val h = resolveSize(defaultH, heightMeasureSpec)
            val w = (h * (BODY_ASPECT + CAP_W_RATIO + CAP_GAP_RATIO + if (charging) BOLT_W_RATIO + BOLT_GAP_RATIO else 0f)).toInt()
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), h)
        }

        override fun onDraw(canvas: Canvas) {
            if (percent < 0) return
            val h = height.toFloat()
            val bodyW = h * BODY_ASPECT
            val capGap = h * CAP_GAP_RATIO
            val capW = h * CAP_W_RATIO
            val radius = h * 0.32f
            body.set(0f, 0f, bodyW, h)

            // Body + knocked-out digits are drawn in a separate layer so DST_OUT erases only the body, not the background
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), h, null)
            canvas.drawRoundRect(body, radius, radius, trackPaint)
            clip.reset()
            clip.addRoundRect(body, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            fillPaint.color = fillColor()
            canvas.drawRect(0f, 0f, bodyW * percent / 100f, h, fillPaint)
            canvas.restore()
            textPaint.textSize = h * 0.78f
            val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            // Normally knocked out (background shows through); while charging (green) / low battery (red) use white text, since
            // knocked-out digits on a colored fill are dark and hard to read
            val solidText = charging || percent <= 20
            if (!solidText) canvas.drawText(percent.toString(), bodyW / 2f, baseline, textPaint)
            canvas.restoreToCount(layer)
            if (solidText) {
                whiteTextPaint.textSize = textPaint.textSize
                canvas.drawText(percent.toString(), bodyW / 2f, baseline, whiteTextPaint)
            }

            // Terminal nub
            val capH = h * 0.36f
            body.set(bodyW + capGap, (h - capH) / 2f, bodyW + capGap + capW, (h + capH) / 2f)
            canvas.drawRoundRect(body, capW, capW, capPaint)

            if (charging) {
                val x0 = bodyW + capGap + capW + h * BOLT_GAP_RATIO
                drawBolt(canvas, x0, h * BOLT_W_RATIO, h)
            }
        }

        private fun fillColor(): Int =
            when {
                charging -> 0xFF66D15E.toInt()
                percent <= 20 -> 0xFFFF3B30.toInt()
                else -> 0xFFFFFFFF.toInt()
            }

        /** Approximate outline of the SF Symbols `bolt.fill`. */
        private fun drawBolt(
            canvas: Canvas,
            x: Float,
            w: Float,
            h: Float,
        ) {
            bolt.reset()
            bolt.moveTo(x + w * 0.62f, 0f)
            bolt.lineTo(x + w * 0.05f, h * 0.58f)
            bolt.lineTo(x + w * 0.45f, h * 0.58f)
            bolt.lineTo(x + w * 0.35f, h)
            bolt.lineTo(x + w * 0.95f, h * 0.40f)
            bolt.lineTo(x + w * 0.55f, h * 0.40f)
            bolt.close()
            canvas.drawPath(bolt, boltPaint)
        }

        private companion object {
            /** Weight of the charge digits (800 ~ Heavy; Bold=700 looks thin at small sizes). */
            const val DIGIT_WEIGHT = 800

            const val BODY_ASPECT = 2.1f
            const val CAP_GAP_RATIO = 0.08f
            const val CAP_W_RATIO = 0.12f
            const val BOLT_GAP_RATIO = 0.18f
            const val BOLT_W_RATIO = 0.62f
        }
    }
