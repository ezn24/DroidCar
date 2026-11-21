package io.github.ezn24.cardeck.neo.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.ezn24.cardeck.neo.R

/**
 * Hero-card "connection status" visual (recreates the waves SVG of design/phone-ui-proto.html):
 * outer dashed orbit + three WiFi-like signal arcs centered on straight up, 45 degrees to each side.
 * Three states:
 *  IDLE       still gray arcs
 *  CONNECTING cyan arcs pulsing (1.5s, phase-staggered) + orbit spinning fast (2.6s/turn)
 *  CONNECTED  cyan arcs breathing slowly (2.8s) + orbit spinning slowly (9s/turn)
 * Design coordinates are based on 210x176 and scaled proportionally to the actual width.
 */
class SignalWavesView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        enum class State { IDLE, CONNECTING, CONNECTED }

        var state: State = State.IDLE
            private set

        private val density = resources.displayMetrics.density

        private val orbitPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = currentColor(R.color.m3_orbit)
                strokeWidth = 1.6f * density
                pathEffect = DashPathEffect(floatArrayOf(3f * density, 9f * density), 0f)
            }
        private val arcPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

        // Design baseline: center (105,88), three arc radii 50/68/86, spanning -135..-45 deg (12 o'clock +-45 deg)
        private val designWidth = 210f
        private val designHeight = 176f
        private val cx = 105f
        private val cy = 88f
        private val radii = floatArrayOf(50f, 68f, 86f)
        private val arcWidths = floatArrayOf(2.6f, 2.4f, 2.4f)

        private var animator: ValueAnimator? = null
        private var orbitRotation = 0f

        /**
         * Pause the looping animation (when the phone UI is invisible / covered by the dim overlay). While the car is connected the phone
         * home page
         * stays in the foreground; after the physical panel is powered down the app still redraws this animation at 60fps; on 2026-11-16
         * it was measured
         * as the most CPU-hungry thread in the whole process (RenderThread ~7%).
         */
        var paused: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                startAnimation()
            }

        fun setState(next: State) {
            if (state == next) return
            state = next
            startAnimation()
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startAnimation()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        private fun startAnimation() {
            animator?.cancel()
            orbitRotation = 0f
            val idle = state == State.IDLE
            if (idle || paused) {
                invalidate()
                return
            }
            val period = if (state == State.CONNECTING) 1_500L else 2_800L
            val newAnim =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = period
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { a ->
                        arcPhase = (a.animatedValue as Float) * 2f * Math.PI.toFloat()
                        val playMs = a.currentPlayTime.toLong()
                        val orbitPeriod = if (state == State.CONNECTING) 2_600L else 9_000L
                        orbitRotation = (playMs % orbitPeriod) * 360f / orbitPeriod
                        invalidate()
                    }
                }
            animator = newAnim
            post(newAnim::start)
        }

        private var arcPhase = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Scale proportionally by the **shorter side**: in landscape when the hero card is squashed, scaling by width only makes the
            // arcs
            // overflow the view (height only 70dp while width is still half the screen). With min the graphic always stays inscribed.
            val scale = minOf(width / designWidth, height / designHeight)
            val centerX = width / 2f
            // Vertically centered: the design center 88/176 is exactly the middle, still centered after scaling
            val centerY = height / 2f

            canvas.save()
            canvas.rotate(orbitRotation, centerX, centerY)
            canvas.drawCircle(centerX, centerY, radii[2] * scale, orbitPaint)
            canvas.restore()

            val baseColor =
                when (state) {
                    State.IDLE -> currentColor(R.color.m3_wave_idle)
                    State.CONNECTING -> currentColor(R.color.m3_wave_connecting)
                    State.CONNECTED -> currentColor(R.color.m3_wave_connected)
                }
            for (i in radii.indices) {
                // Phase stagger: 0.25s/layer while connecting, 0.4s/layer when connected; IDLE has no brightness change
                val offset = if (state == State.CONNECTING) i * 0.25f else i * 0.4f
                val alpha =
                    if (state == State.IDLE) {
                        1f
                    } else {
                        0.625f + 0.375f * kotlin.math.sin(arcPhase - offset * 2f * Math.PI.toFloat())
                    }
                arcPaint.color = baseColor
                arcPaint.alpha = (alpha.coerceIn(0.15f, 1f) * 255).toInt()
                arcPaint.strokeWidth = arcWidths[i] * scale
                canvas.drawArc(
                    centerX - radii[i] * scale,
                    centerY - radii[i] * scale,
                    centerX + radii[i] * scale,
                    centerY + radii[i] * scale,
                    -135f,
                    90f,
                    false,
                    arcPaint,
                )
            }
        }

        private fun currentColor(id: Int) =
            androidx.core.content.ContextCompat
                .getColor(context, id)
    }
