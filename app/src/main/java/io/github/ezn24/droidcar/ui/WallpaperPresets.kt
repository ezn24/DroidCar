package io.github.ezn24.droidcar.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import io.github.ezn24.droidcar.R

/**
 * Built-in wallpaper patterns: gradients drawn in code (diagonal base color + two soft glows); no APK size cost and sharp at any
 * resolution.
 * The dark group serves night/dark mode and the light group serves daytime; users can pick any pattern for either slot.
 */
object WallpaperPresets {
    data class Preset(
        val id: String,
        val nameRes: Int,
        val light: Boolean,
        /** Both ends of the diagonal gradient. */
        val from: Int,
        val to: Int,
        /** Two soft glows at top-left / bottom-right (with alpha). */
        val glowA: Int,
        val glowB: Int,
    )

    val ALL =
        listOf(
            Preset("midnight", R.string.wp_preset_midnight, false, 0xFF0B1026.toInt(), 0xFF1A2747.toInt(), 0x993A5BA0.toInt(), 0x66203A7A),
            Preset(
                "aurora",
                R.string.wp_preset_aurora,
                false,
                0xFF04141A.toInt(),
                0xFF0B2A30.toInt(),
                0x991FA88A.toInt(),
                0x806A4BC4.toInt(),
            ),
            Preset("ember", R.string.wp_preset_ember, false, 0xFF150A10.toInt(), 0xFF2A1020.toInt(), 0x99C2410C.toInt(), 0x667A1F4A),
            Preset("graphite", R.string.wp_preset_graphite, false, 0xFF101114.toInt(), 0xFF1E2026.toInt(), 0x664B5563, 0x4D374151),
            Preset("dawn", R.string.wp_preset_dawn, true, 0xFFF6E7D8.toInt(), 0xFFE6D6F0.toInt(), 0xCCFFFFFF.toInt(), 0x80F4B6A0.toInt()),
            Preset("mist", R.string.wp_preset_mist, true, 0xFFE8EFF4.toInt(), 0xFFCADBE6.toInt(), 0xCCFFFFFF.toInt(), 0x809CC3DA.toInt()),
            Preset(
                "meadow",
                R.string.wp_preset_meadow,
                true,
                0xFFEEF5E6.toInt(),
                0xFFCFE6C8.toInt(),
                0xCCFFFFFF.toInt(),
                0x80A7D58C.toInt(),
            ),
        )

    fun find(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /** Rendered at 16:9 (long side = [maxSize], capped at 1920; the caller crops with aspectFill). */
    fun render(
        id: String,
        maxSize: Int,
    ): Bitmap? {
        val preset = find(id) ?: return null
        val width = maxSize.coerceIn(64, 1920)
        val height = (width * 9 / 16).coerceAtLeast(36)
        return render(preset, width, height)
    }

    fun render(
        preset: Preset,
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        paint.shader = LinearGradient(0f, 0f, w, h, preset.from, preset.to, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)

        fun glow(
            cx: Float,
            cy: Float,
            radius: Float,
            color: Int,
        ) {
            paint.shader = RadialGradient(cx, cy, radius, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        glow(w * 0.18f, h * 0.12f, maxOf(w, h) * 0.55f, preset.glowA)
        glow(w * 0.86f, h * 0.92f, maxOf(w, h) * 0.5f, preset.glowB)
        return bitmap
    }
}
