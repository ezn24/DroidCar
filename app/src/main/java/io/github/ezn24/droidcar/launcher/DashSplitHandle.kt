package io.github.ezn24.droidcar.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Dashboard split-drag handle: a three-dot indicator bar in the gap (three dots stacked vertically in landscape, in a row in portrait).
 *
 * Press and drag to adjust the map card / music card split. During the drag [VirtualLauncher.previewDashSplit] only
 * stretches the map texture in rendering (virtual display untouched); on release [commitDashSplit] does the real resize.
 *
 * Two hard touch rules (design review 2026-10-21):
 *  - DOWN must call `requestDisallowInterceptTouchEvent(true)`: the host is the ViewPager2
 *    home page, otherwise a horizontal drag is taken by the pager as a page flip;
 * - Use rawX/rawY for the displacement math: the handle's own position changes every frame during the drag, so getX (relative to itself)
 * is self-referential.
 *    A finger leaving the handle bounds does not affect event ownership (Android binds the pointer stream to the DOWN receiver).
 */
class DashSplitHandle(
    context: Context,
) : View(context) {
    /** true = portrait (drag up/down, drag y); false = landscape (drag left/right, drag x). */
    private var verticalSplit = false

    /** (handle center in app-display pixels, settled); when settled = true the receiver should commit. */
    private var onDrag: ((Float, Boolean) -> Unit)? = null

    private var downCenter = 0f
    private var downRaw = 0f
    private var dragging = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()

    fun configure(
        verticalSplit: Boolean,
        onDrag: (Float, Boolean) -> Unit,
    ) {
        this.verticalSplit = verticalSplit
        this.onDrag = onDrag
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val raw = if (verticalSplit) event.rawY else event.rawX
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downRaw = raw
                downCenter = VirtualLauncher.dashHandleCenterScreen()
                dragging = downCenter >= 0
                isPressed = true
                invalidate()
                dragging
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                onDrag?.invoke(downCenter + (raw - downRaw), false)
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (!dragging) return false
                dragging = false
                isPressed = false
                invalidate()
                // CANCEL finishes at the current position too: commit is idempotent and clamped, consistent with UP and predictable
                onDrag?.invoke(downCenter + (raw - downRaw), true)
                true
            }

            else -> false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        if (isPressed) {
            val inset = 6f * density
            bgRect.set(inset, inset, width - inset, height - inset)
            bgPaint.color = 0x14FFFFFF
            canvas.drawRoundRect(bgRect, bgRect.height() / 2f, bgRect.height() / 2f, bgPaint)
        }
        dotPaint.color = if (isPressed) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
        val r = 2.5f * density
        val gap = 10f * density
        val cx = width / 2f
        val cy = height / 2f
        // Dot direction follows the bar: portrait = horizontal bar -> dots in a row; landscape = vertical bar -> dots stacked
        // (2026-10-22 two corrections: the first version always stacked, the second had the branch reversed)
        if (verticalSplit) {
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx - gap, cy, r, dotPaint)
            canvas.drawCircle(cx + gap, cy, r, dotPaint)
        } else {
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx, cy - gap, r, dotPaint)
            canvas.drawCircle(cx, cy + gap, r, dotPaint)
        }
    }
}
