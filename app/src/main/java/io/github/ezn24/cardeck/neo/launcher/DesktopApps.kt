package io.github.ezn24.cardeck.neo.launcher

import android.content.Context

/**
 * Desktop app selection (persisted in SharedPreferences).
 *
 * Default (the user never set it): show only installed map apps (Amap / Baidu / Tencent;
 * per the user's request "show Amap, Baidu etc. maps by default"); once the user ticks anything in settings,
 * the explicit choice rules (including the "deselect all" state).
 */
object DesktopApps {
    private const val PREFS_NAME = "settings"
    private const val KEY_SELECTED = "desktopApps"

    /** Package names of the map apps ticked by default. */
    private val MAP_PACKAGES =
        setOf(
            "com.autonavi.amapauto", // Amap car version
            "com.baidu.naviauto", // Baidu Maps car version
            "com.autonavi.minimap", // Amap
            "com.baidu.BaiduMap", // Baidu Maps
            "com.tencent.map", // Tencent Maps
        )

    /** Apps that should currently show on the desktop (set of component flattenToString). */
    fun selected(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SELECTED)) {
            return apps
                .filter { it.component.packageName in MAP_PACKAGES }
                .map { it.component.flattenToString() }
                .toSet()
        }
        return prefs.getStringSet(KEY_SELECTED, emptySet()).orEmpty().toSet()
    }

    fun save(
        context: Context,
        selection: Set<String>,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED, selection.toMutableSet())
            .apply()
    }
}
