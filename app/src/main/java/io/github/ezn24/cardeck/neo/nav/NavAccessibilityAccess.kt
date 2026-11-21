package io.github.ezn24.cardeck.neo.nav

import android.content.Context
import io.github.ezn24.cardeck.neo.shizuku.Shell

/**
 * Turns [NavAccessibilityService] on through Shizuku, the same way notification access is granted
 * (shell holds WRITE_SECURE_SETTINGS). Opt-in: only when the user switched "cluster navigation info" on
 * ([enabledByUser]); then every session attach re-checks it, because a force-stop or reinstall removes the service
 * from the system list (measured on the S24).
 *
 * The system setting is one `:`-joined list shared by every accessibility service: entries are compared exactly and
 * only our own entry is ever added or removed, never the user's other services.
 */
object NavAccessibilityAccess {
    private const val PREFS = "settings"
    private const val KEY = "nav_cluster"

    fun enabledByUser(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabledByUser(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
        CarRouteGuidance.setEnabled(enabled)
    }

    /** Apply the current preference on a worker, including after rapid switch changes. */
    @Synchronized
    fun sync(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        if (enabledByUser(context)) return ensureEnabled(context, log)
        disable(context, log)
        return false
    }

    /** List with our entry added (no duplicates, others kept in order). */
    internal fun withEntry(
        current: String?,
        entry: String,
    ): String {
        val entries = entriesOf(current)
        if (entries.any { it.equals(entry, ignoreCase = true) }) return entries.joinToString(":")
        return (entries + entry).joinToString(":")
    }

    /** List with our entry removed. */
    internal fun withoutEntry(
        current: String?,
        entry: String,
    ): String = entriesOf(current).filterNot { it.equals(entry, ignoreCase = true) }.joinToString(":")

    internal fun entriesOf(current: String?): List<String> =
        current
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
            ?.split(':')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /**
     * When the user opted in, makes sure the service is in the system list (idempotent; background thread only,
     * it runs shell round trips). Returns whether it is enabled afterwards.
     */
    @Synchronized
    fun ensureEnabled(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        if (!enabledByUser(context)) return false
        val entry = NavAccessibilityService.component(context).flattenToString()
        val current = read()
        if (entriesOf(current).any { it.equals(entry, ignoreCase = true) }) return true
        Shell.exec("settings put secure enabled_accessibility_services ${quote(withEntry(current, entry))}", timeoutMs = 4_000)
        Shell.exec("settings put secure accessibility_enabled 1", timeoutMs = 4_000)
        // Read back (settings writes can lag)
        for (attempt in 0 until 4) {
            if (attempt > 0) Thread.sleep(250)
            if (entriesOf(read()).any { it.equals(entry, ignoreCase = true) }) {
                log("NAV-A11Y: navigation info service enabled through Shizuku")
                return true
            }
        }
        log("NAV-A11Y: could not enable the navigation info service (Shizuku not ready?) - enable it in system Accessibility")
        return false
    }

    /** User switched it off: remove only our entry. */
    private fun disable(
        context: Context,
        log: (String) -> Unit,
    ) {
        val entry = NavAccessibilityService.component(context).flattenToString()
        val current = read()
        if (entriesOf(current).none { it.equals(entry, ignoreCase = true) }) return
        Shell.exec("settings put secure enabled_accessibility_services ${quote(withoutEntry(current, entry))}", timeoutMs = 4_000)
        log("NAV-A11Y: navigation info service disabled")
    }

    private fun read(): String? =
        Shell
            .exec("settings get secure enabled_accessibility_services", timeoutMs = 4_000)
            .output
            .trim()

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
