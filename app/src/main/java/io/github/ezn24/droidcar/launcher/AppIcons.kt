package io.github.ezn24.droidcar.launcher

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

    /**
     * Car-supplied icon (/info oemIcons) as a rounded tile. A full-bleed picture is only clipped; a glyph on a
     * transparent background gets a plate in a contrasting colour, picked from the glyph's mean luminance (Audi sends a
     * white logo on transparency, which vanished on a white plate upstream, dfa739d).
     */
    fun plated(
        source: Bitmap,
        size: Int,
        radiusFraction: Float = 0.22f,
    ): Bitmap {
        if (size <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(source, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        var left = size
        var top = size
        var right = -1
        var bottom = -1
        var luminance = 0L
        var opaque = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val px = pixels[y * size + x]
                if ((px ushr 24) <= 32) continue
                opaque++
                luminance += (299 * ((px shr 16) and 0xFF) + 587 * ((px shr 8) and 0xFF) + 114 * (px and 0xFF)) / 1000
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * radiusFraction
        canvas.clipPath(
            Path().apply { addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, Path.Direction.CW) },
        )
        val box = if (right < 0) 0f else maxOf(right - left + 1, bottom - top + 1).toFloat() / size
        if (box < 0.85f && opaque > 0) {
            canvas.drawColor(if (luminance / opaque >= 140) 0xFF1C1C1E.toInt() else android.graphics.Color.WHITE)
        }
        canvas.drawBitmap(scaled, 0f, 0f, null)
        if (scaled !== source) scaled.recycle()
        return bitmap
    }
}
