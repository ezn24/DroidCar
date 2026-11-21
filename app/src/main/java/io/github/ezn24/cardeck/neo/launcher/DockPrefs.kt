package io.github.ezn24.cardeck.neo.launcher

import android.content.Context

/**
 * Dock appearance preferences (persisted in SharedPreferences + listener notification).
 *
 *  - scrimOpacity: dock scrim strength 0..100 (% black; 0 = wallpaper clearly visible, 100 = fully black)
 *  - blur: background Gaussian blur strength 0..100 (0 = no blur; mapped to the RenderEffect blur radius)
 *
 * Listeners are called on the main thread (triggered while the settings slider is dragged), and DockActivity re-renders immediately.
 */
object DockPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_SCRIM = "dockScrimOpacity"
    private const val KEY_BLUR = "dockBlur"
    private const val KEY_WIDTH = "dockWidthPercent"

    /** Default scrim strength (%). */
    const val DEFAULT_SCRIM = 20

    /**
     * Bar width scale (50-100, step 5; default 100). Thickness pixels and the dock display density are **multiplied together** -
     * the dp layout of the content is unchanged but rendered into fewer pixels, so the whole dock (icons / clock / text) narrows
     * proportionally.
     * **Takes effect on the next connection only** (the dock virtual display is created with the size/density at session setup,
     * unlike scrim/blur which apply live).
     */
    const val WIDTH_MIN = 50
    const val WIDTH_MAX = 100
    const val WIDTH_STEP = 5
    const val WIDTH_DEFAULT = 100

    fun widthPercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_WIDTH, WIDTH_DEFAULT)
            .coerceIn(WIDTH_MIN, WIDTH_MAX)

    fun setWidthPercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WIDTH, percent.coerceIn(WIDTH_MIN, WIDTH_MAX))
            .apply()
    }

    /** Default blur strength (%). */
    const val DEFAULT_BLUR = 40

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun scrimOpacity(context: Context): Int = prefs(context).getInt(KEY_SCRIM, DEFAULT_SCRIM).coerceIn(0, 100)

    fun blur(context: Context): Int = prefs(context).getInt(KEY_BLUR, DEFAULT_BLUR).coerceIn(0, 100)

    fun setScrimOpacity(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit().putInt(KEY_SCRIM, value.coerceIn(0, 100)).apply()
        notifyListeners()
    }

    fun setBlur(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit().putInt(KEY_BLUR, value.coerceIn(0, 100)).apply()
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }
}
