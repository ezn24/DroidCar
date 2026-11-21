package io.github.ezn24.cardeck.neo.launcher

import android.content.ComponentName
import android.content.Context

/**
 * Default map preference (used for the dashboard tile and map-display launch).
 *
 * Single choice within the whitelist [VirtualLauncher.MAP_PACKAGES_PRIORITY]; when unset, the first
 * installed entry in priority order is used. The persisted key is the package name.
 *
 * WARNING: resolution logic lives in [resolveDefaultMap] (a pure function). Do **not** let the resolution path call back
 * `VirtualLauncher.defaultMapComponent()`: it merely forwards to this object's [resolve],
 * which forms infinite recursion -> StackOverflowError (settled 2026-10-18, see the resolveDefaultMap comment).
 */
object DefaultMapPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_DEFAULT_MAP = "defaultMapPackage"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Package name of the map explicitly chosen by the user (null = unset, use priority order). */
    fun manualPackage(context: Context): String? = prefs(context).getString(KEY_DEFAULT_MAP, null)

    /** Currently effective default map (explicit setting first; otherwise the first installed one in priority order). */
    fun resolve(context: Context): ComponentName? =
        resolveDefaultMap(
            manual = manualPackage(context),
            priority = VirtualLauncher.MAP_PACKAGES_PRIORITY,
            componentFor = { pkg -> launchComponent(context, pkg) },
        )

    /** Launchable Activity of that package (not installed / no launch entry -> null). */
    private fun launchComponent(
        context: Context,
        pkg: String,
    ): ComponentName? {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        val activity = context.packageManager.resolveActivity(launch, 0)?.activityInfo ?: return null
        // resolveActivity may return another package's fallback when nothing matches: accept only a matching package name,
        // otherwise an explicit setting for an "uninstalled" app would be judged usable.
        if (activity.packageName != pkg) return null
        return ComponentName(pkg, activity.name)
    }

    fun set(
        context: Context,
        packageName: String?,
    ) {
        if (packageName == null) {
            prefs(context).edit().remove(KEY_DEFAULT_MAP).apply()
        } else {
            prefs(context).edit().putString(KEY_DEFAULT_MAP, packageName).apply()
        }
    }
}

/**
 * The **pure-function** part of default-map resolution (explicit setting first -> priority scan).
 *
 * Split out for two reasons:
 *  1. Unit-testable (no Context needed) - see DefaultMapPrefsTest.
 *  2. Loops are explicitly forbidden: this function depends only on the passed [componentFor] and **must not** call back
 *     `VirtualLauncher.defaultMapComponent()`. In the old implementation `resolve()` called it when "no default
 *     map set", and it forwarded back to `resolve()`, forming infinite recursion -> StackOverflowError.
 *     Symptom (measured 2026-10-18): after uninstall/reinstall the map card did not render (only an empty preference reaches this branch;
 *     the exception was swallowed into logcat by `Bg.run` and the file log stayed silent), and re-picking the default map in settings
 *     fixed it - easily misdiagnosed as a virtual-display / rendering problem.
 */
internal fun <T> resolveDefaultMap(
    manual: String?,
    priority: List<String>,
    componentFor: (String) -> T?,
): T? {
    // Explicit setting first; if that app is uninstalled it naturally falls back to priority order
    manual?.let { pkg -> componentFor(pkg)?.let { return it } }
    priority.forEach { pkg -> componentFor(pkg)?.let { return it } }
    return null
}

/**
 * Main-map Activity of the car-version map app (explicit entry for `am start -n`; null if unknown).
 *
 * Why specify it explicitly: the car map's LAUNCHER intent often lands on the splash/onboarding page (which drops
 * `--display`, so the UI ends up on the phone's main screen instead of the map display). Amap's car version
 * measurably needs an explicit start of `MainMapActivity`.
 *
 * Baidu's car version (`com.baidu.naviauto`) needs **no** explicit entry; return null and let the system resolve it:
 * on 22.0.1 (versionCode 1645) the launcher is
 * `com.baidu.naviauto/.SlashActivity.DefaultActivity`, and the package has **no separate main-map
 * Activity** - the map is rendered by `com.baidu.mapautosdk` inside that Activity.
 * (It was once wrongly written as `.NaviAutoActivity`: that does not exist in this version and `am start` reports
 * "Activity class does not exist"; a candidate fallback caught it, but every launch wasted a failed shell round trip
 * and left misleading logs; removed after real-device testing 2026-10-23.)
 *
 * WARNING: the explicit entry is **not guaranteed** to exist in every version, so callers must use [mapLaunchCandidates]
 * to get the candidate list and try each, falling back to the launcher-resolved component - a single hard-coded point would leave
 * the map display black with nothing to investigate.
 */
internal fun carMapMainActivity(packageName: String): String? =
    when (packageName) {
        "com.autonavi.amapauto" -> "com.autonavi.amapauto/.MainMapActivity"
        else -> null
    }

/**
 * Candidate targets for the map launch `am start -n` (tried in order):
 * (1) the car version's explicit main activity (if known) -> (2) the component resolved by the launcher.
 */
internal fun mapLaunchCandidates(
    packageName: String,
    launcherComponent: String,
): List<String> {
    val explicit = carMapMainActivity(packageName)?.takeIf { it != launcherComponent }
    return listOfNotNull(explicit, launcherComponent).distinct()
}

/** ComponentName overload of [mapLaunchCandidates] (real-device path; unit tests use the plain-string version). */
internal fun mapLaunchCandidates(component: ComponentName): List<String> =
    mapLaunchCandidates(component.packageName, component.flattenToShortString())
