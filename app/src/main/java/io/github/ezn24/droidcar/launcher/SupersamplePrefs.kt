package io.github.ezn24.droidcar.launcher

import android.content.Context
import android.content.pm.ActivityInfo

/**
 * Per-app supersampling preference (tri-state, persisted in SharedPreferences).
 *
 *  Auto (default): supersample when the launch Activity is "portrait-locked" -
 *      such apps get letterboxed into a narrow window on our landscape app display by the system, and reserve a top band
 *      by the phone status-bar physical pixels; supersampling (render x1.5 then scale back) shrinks the share of fixed pixels.
 *      Landscape apps (maps) do not need it - native resolution is sharper and smoother.
 *  On / Off: explicit user choice, overriding auto detection.
 *
 * Portrait detection reads the launch Activity's screenOrientation (PORTRAIT/REVERSE/SENSOR/USER).
 */
object SupersamplePrefs {
    const val MODE_AUTO = 0
    const val MODE_ON = 1
    const val MODE_OFF = 2

    private const val PREFS_NAME = "settings"
    private const val KEY_PREFIX = "ss_"

    /** Reads the preference (default auto). */
    fun mode(
        context: Context,
        component: android.content.ComponentName,
    ): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREFIX + component.flattenToString(), MODE_AUTO)

    fun setMode(
        context: Context,
        component: android.content.ComponentName,
        mode: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PREFIX + component.flattenToString(), mode)
            .apply()
    }

    /** Whether this launch uses the supersampled app display (final result after tri-state resolution). */
    fun shouldSupersample(
        context: Context,
        component: android.content.ComponentName,
    ): Boolean =
        when (mode(context, component)) {
            MODE_ON -> true
            MODE_OFF -> false
            // WeChat's two-pane layout appears on the supersampled display (user test 2026-11-28, ChairPlay), so the tablet
            // layout setting sends it there; an explicit per-app choice above still wins
            else ->
                isPortraitActivity(context, component) ||
                    (component.packageName == WECHAT && ChatMode.enabled(context))
        }

    private const val WECHAT = "com.tencent.mm"

    /** Auto detection: whether the launch Activity is portrait-locked (the form that gets letterboxed on a landscape app display). */
    fun isPortraitActivity(
        context: Context,
        component: android.content.ComponentName,
    ): Boolean =
        try {
            val info = context.packageManager.getActivityInfo(component, 0)
            when (info.screenOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                -> true

                else -> false
            }
        } catch (_: Exception) {
            false
        }
}
