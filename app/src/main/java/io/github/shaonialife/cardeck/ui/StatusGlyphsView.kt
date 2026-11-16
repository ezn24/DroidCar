package io.github.shaonialife.cardeck.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * CarPlay 侧栏时间下方的状态行：蜂窝信号柱（无 SIM 时为 4 个暗圆点）+ Wi-Fi 扇形图标。
 *
 * - 信号：[cellLevel] 0..4 根信号柱点亮（白），其余暗（白 35%）；-1 = 无 SIM/无服务，显示 4 个暗圆点；
 * - Wi-Fi：[wifiLevel] 0..3 条弧点亮；-1 = 未连 Wi-Fi，不画图标（宽度随之收缩）。
 *
 * 尺寸只由高度决定（与 [BatteryGlyphView] 同一套做法），dock 横条/竖条都能 wrap_content。
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

        /** 蜂窝信号格数（SignalStrength.level：0..4；-1 = 无 SIM / 无服务）。 */
        fun setCellLevel(level: Int) {
            val v = level.coerceIn(-1, 4)
            if (v == cellLevel) return
            cellLevel = v
            invalidate()
        }

        /** Wi-Fi 格数（0..3；-1 = 未连 Wi-Fi → 不显示图标）。 */
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
                // 无 SIM / 无服务：iPhone 同款 4 个暗圆点
                val r = h * DOT_D / 2f
                val cy = h * 0.62f
                for (i in 0 until 4) {
                    val cx = r + i * h * DOT_PITCH
                    canvas.drawCircle(cx, cy, r, offPaint)
                }
            } else {
                // 有服务：4 根由矮到高的信号柱，按格数点亮（SF Symbols `cellularbars`）
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
            // Wi-Fi：以底部一点为圆心的三条弧 + 圆心小点（SF Symbols `wifi` 的近似）
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

            /** 以下尺寸都是「相对高度」的比例。 */
            const val DOT_D = 0.36f
            const val DOT_PITCH = 0.56f
            const val DOTS_W = DOT_D + DOT_PITCH * 3

            /** 信号柱：宽 0.27h，高度 35%/55%/77%/100%；四根总宽与四个圆点相同（两种形态切换不跳宽）。 */
            const val BAR_W = 0.27f
            const val BAR_PITCH = (DOTS_W - BAR_W) / 3f
            val BAR_HEIGHTS = floatArrayOf(0.35f, 0.55f, 0.77f, 1f)
            const val GAP_W = 0.45f
            const val WIFI_W = 1.45f
        }
    }
