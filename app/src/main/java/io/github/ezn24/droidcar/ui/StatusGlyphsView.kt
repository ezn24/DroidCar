package io.github.ezn24.droidcar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Status row under the CarPlay sidebar clock: cellular signal bars (4 dim dots when there is no SIM) + Wi-Fi fan icon.
 *
 * - Signal: [cellLevel] 0..4 bars lit (white), the rest dim (white 35%); -1 = no SIM/no service, shows 4 dim dots;
 * - Wi-Fi: [wifiLevel] 0..3 arcs lit; -1 = not on Wi-Fi, no icon drawn (width shrinks accordingly).
 *
 * Size is determined by height only (same approach as [BatteryGlyphView]); works with wrap_content in both the dock's horizontal and
 * vertical bars.
 */
class StatusGlyphsView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private var cellLevel = -1
        private var wifiLevel = -1

        private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ON }
        private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OFF }
        private val arcOn =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ON
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        private val arcOff =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = OFF
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        private val oval = RectF()

        /** Cellular signal level count (SignalStrength.level: 0..4; -1 = no SIM / no service). */
        fun setCellLevel(level: Int) {
            val v = level.coerceIn(-1, 4)
            if (v == cellLevel) return
            cellLevel = v
            invalidate()
        }

        /** Wi-Fi level count (0..3; -1 = not on Wi-Fi -> icon hidden). */
        fun setWifiLevel(level: Int) {
            val v = level.coerceIn(-1, 3)
            if (v == wifiLevel) return
            val widthChanges = (v < 0) != (wifiLevel < 0)
            wifiLevel = v
            if (widthChanges) requestLayout()
            invalidate()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val defaultH = (10f * resources.displayMetrics.density).toInt()
            val h = resolveSize(defaultH, heightMeasureSpec)
            val w = (h * (DOTS_W + if (wifiLevel >= 0) GAP_W + WIFI_W else 0f)).toInt()
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), h)
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            if (cellLevel < 0) {
                // No SIM / no service: 4 dim dots, same as the iPhone
                val r = h * DOT_D / 2f
                val cy = h * 0.62f
                for (i in 0 until 4) {
                    val cx = r + i * h * DOT_PITCH
                    canvas.drawCircle(cx, cy, r, offPaint)
                }
            } else {
                // With service: 4 bars rising from short to tall, lit by level (SF Symbols `cellularbars`)
                val barW = h * BAR_W
                val radius = barW * 0.3f
                for (i in 0 until 4) {
                    val left = i * h * BAR_PITCH
                    val top = h * (1f - BAR_HEIGHTS[i])
                    oval.set(left, top, left + barW, h)
                    canvas.drawRoundRect(oval, radius, radius, if (i < cellLevel) onPaint else offPaint)
                }
            }
            if (wifiLevel < 0) return
            // Wi-Fi: three arcs centered on a point at the bottom + a small center dot (approximating SF Symbols `wifi`)
            val x0 = h * (DOTS_W + GAP_W)
            val w = h * WIFI_W
            val cx = x0 + w / 2f
            val baseY = h * 0.92f
            val stroke = h * 0.15f
            arcOn.strokeWidth = stroke
            arcOff.strokeWidth = stroke
            canvas.drawCircle(cx, baseY - stroke * 0.3f, stroke * 0.75f, onPaint)
            for (i in 1..3) {
                val radius = h * (0.10f + 0.245f * i)
                oval.set(cx - radius, baseY - radius, cx + radius, baseY + radius)
                canvas.drawArc(oval, 225f, 90f, false, if (i <= wifiLevel) arcOn else arcOff)
            }
        }

        private companion object {
            const val ON = 0xFFFFFFFF.toInt()
            const val OFF = 0x59FFFFFF

            /** The sizes below are all ratios "relative to height". */
            const val DOT_D = 0.36f
            const val DOT_PITCH = 0.56f
            const val DOTS_W = DOT_D + DOT_PITCH * 3

            /** Signal bars: width 0.27h, heights 35%/55%/77%/100%; total width of the four bars equals that of four dots (no width jump between the two forms). */
            const val BAR_W = 0.27f
            const val BAR_PITCH = (DOTS_W - BAR_W) / 3f
            val BAR_HEIGHTS = floatArrayOf(0.35f, 0.55f, 0.77f, 1f)
            const val GAP_W = 0.45f
            const val WIFI_W = 1.45f
        }
    }
