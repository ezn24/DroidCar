package io.github.ezn24.cardeck.neo.media

import android.content.ComponentName
import android.content.Context
import io.github.ezn24.cardeck.neo.shizuku.Shell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Query and grant of notification access (precondition for the music card data source).
 *
 * **Why a separate object**: granting used to be attempted once, in `MainActivity.onCreate`, only when
 * Shizuku was ready. If that timing was wrong (Shizuku not running after a phone reboot, or Shizuku not yet
 * authorized on first launch after a package rename) it was never retried, and users saw "music playing but
 * the card says media info not connected" (hit after the 2026-10-18 package rename). It is now **idempotent
 * and retryable**: call [ensureGranted] whenever Shizuku becomes ready; if already granted it returns without running a shell.
 */
object NotificationAccess {
    /** Last confirmed grant state in this process (the service's onListenerConnected also updates it). */
    @Volatile
    var granted: Boolean = false
        private set

    private val granting = AtomicBoolean(false)

    /** Call from the service connected callback: the system accepts the grant, no shell probe needed. */
    internal fun markGranted() {
        granted = true
    }

    /** This app's listener component (`package/class` form, the format `cmd notification allow_listener` needs). */
    fun componentSpec(context: Context): String = ComponentName(context, MediaListenerService::class.java).flattenToString()

    /**
     * Decide from the system setting string whether this app is in the allowed-listener list.
     *
     * The system joins listeners with `:` into one string (`pkg/class:pkg/class:...`);
     * **entries must be compared exactly**, because `contains(package)` misjudges when another app name shares the same prefix.
     */
    internal fun isGrantedIn(
        enabledListeners: String?,
        componentSpec: String,
    ): Boolean =
        enabledListeners
            ?.split(':')
            ?.any { it.trim().equals(componentSpec, ignoreCase = true) } == true

    /**
     * Ensure notification access is granted (idempotent, retryable; **call on a background thread**, it does shell round trips).
     *
     * @return true = granted (already present, or granted this time)
     */
    fun ensureGranted(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        val spec = componentSpec(context)
        val current =
            Shell
                .exec("settings get secure enabled_notification_listeners", timeoutMs = 4_000)
                .output
        if (isGrantedIn(current, spec)) {
            granted = true
            // "In the allowed list" does not mean "the system bound the listener": after an APK reinstall the list
            // entry remains but the notification service binding may not exist (2026-10-19: no entry in
            // Live listeners, metadata always null, card stuck on "cannot read playback info";
            // a manual disallow+allow restored it at once). Do a self-healing rebind here.
            rebindIfNotConnected(context, spec, log)
            return true
        }
        if (!granting.compareAndSet(false, true)) return granted
        try {
            val result = Shell.exec("cmd notification allow_listener $spec", timeoutMs = 4_000)
            // The command exitCode is not a criterion (some ROMs return non-zero on success);
            // and an **immediate** recheck is also unreliable: settings writes are delayed, and on a real device
            // the command succeeded but an immediate read still showed the old value (2026-10-18: cmd result=0 but recheck said not
            // granted).
            // So confirm with short retries and only fail when all of them fail.
            var ok = false
            for (attempt in 0 until 4) {
                if (attempt > 0) Thread.sleep(250)
                val after =
                    Shell
                        .exec("settings get secure enabled_notification_listeners", timeoutMs = 4_000)
                        .output
                if (isGrantedIn(after, spec)) {
                    ok = true
                    break
                }
            }
            granted = ok
            log(
                if (ok) {
                    "MEDIA: notification access granted (music card can show title/playback state)"
                } else {
                    "MEDIA: notification access not granted (cmd result=${result.exitCode}) --" +
                        "music card shows playback state only, no title; enable it manually in system settings \"Notification access\""
                },
            )
            return ok
        } finally {
            granting.set(false)
        }
    }

    /**
     * When already granted but the listener is not really connected, trigger a system rebind via disallow + allow.
     *
     * The criterion comes from `dumpsys notification` "Live notification listeners": only when listed there
     * is [MediaListenerService.onListenerConnected] called and `metadata` readable.
     * This only happens occasionally "after leaving the app for long / after reinstall"; the cost is one shell round trip, acceptable.
     */
    private fun rebindIfNotConnected(
        context: Context,
        spec: String,
        log: (String) -> Unit,
    ) {
        // Service already connected (onListenerConnected set it): no probe needed, avoids dumpsys on every session setup.
        if (serviceConnected) return
        if (!rebinding.compareAndSet(false, true)) return
        try {
            val live = Shell.exec("dumpsys notification --noredact", timeoutMs = 6_000).output
            if (isLiveIn(live, spec)) {
                serviceConnected = true
                return
            }
            log("MEDIA: notification access is listed but the listener is not bound -- triggering one rebind")
            Shell.exec("cmd notification disallow_listener $spec", timeoutMs = 4_000)
            Thread.sleep(300)
            Shell.exec("cmd notification allow_listener $spec", timeoutMs = 4_000)
            // Binding is asynchronous: confirm with short polling so downstream does not keep a "granted but unreadable" state
            for (attempt in 0 until 6) {
                Thread.sleep(400)
                val after = Shell.exec("dumpsys notification --noredact", timeoutMs = 6_000).output
                if (isLiveIn(after, spec)) {
                    serviceConnected = true
                    log("MEDIA: listener rebound (music card metadata restored)")
                    return
                }
            }
            log("MEDIA: WARNING listener still not connected after rebind -- music card may show playback state only")
        } catch (_: Exception) {
            // Shell restricted/timed out: does not affect the main flow, retry next session
        } finally {
            rebinding.set(false)
        }
    }

    /**
     * Decide from `dumpsys notification` output whether this component is in the Live listeners section.
     * Looking only at that section is necessary: "All notification listeners" may still list this component (grant persists),
     * but what decides whether metadata is readable is the Live section.
     */
    internal fun isLiveIn(
        dump: String,
        componentSpec: String,
    ): Boolean {
        val start = dump.indexOf("Live notification listeners")
        if (start < 0) return false
        val section = dump.substring(start).substringBefore("Snoozed notification listeners")
        return section.contains(componentSpec, ignoreCase = true)
    }

    /** Set when the service connects ([MediaListenerService.onListenerConnected] calls it). */
    @Volatile
    private var serviceConnected: Boolean = false

    internal fun markConnected() {
        serviceConnected = true
    }

    internal fun markDisconnected() {
        serviceConnected = false
    }

    private val rebinding = AtomicBoolean(false)
}
