package io.github.ezn24.cardeck.neo.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** App icon bitmap helper (iOS-style rounded corners; shared by desktop grid / dock / settings). */
object AppIcons {
    /**
     * Drawable -> rounded bitmap; [radiusFraction] is the corner radius as a fraction of the edge (about 22%).
     *
     * Renders on a copy of the Drawable (constantState.newDrawable; bounds saved/restored as a fallback):
     * instances in iconCache are shared by several screens, so calling setBounds directly would pollute other users.
     * Real-device root cause: the desktop grid rewrote the shared Drawable's bounds with its own iconSize,
     * so the dock's scaling matrix for the same instance went out of sync (icon shrank and shifted left; reproduced after tapping home
     * rebound the grid).
     */
    fun rounded(
        drawable: Drawable,
        size: Int,
        radiusFraction: Float = 0.22f,
    ): Bitmap {
        if (size <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * radiusFraction
        val path =
            Path().apply {
                addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, Path.Direction.CW)
            }
        val target =
            runCatching { drawable.constantState?.newDrawable()?.mutate() }
                .getOrNull() ?: drawable
        val savedBounds = Rect(target.bounds)
        canvas.save()
        canvas.clipPath(path)
        target.setBounds(0, 0, size, size)
        target.draw(canvas)
        canvas.restore()
        target.bounds = savedBounds
        return bitmap
    }
}
