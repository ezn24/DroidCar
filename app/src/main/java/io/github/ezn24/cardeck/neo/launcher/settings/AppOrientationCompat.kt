package io.github.ezn24.cardeck.neo.launcher.settings

import android.content.Context
import android.os.Build
import io.github.ezn24.cardeck.neo.launcher.DefaultMapPrefs
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher
import io.github.ezn24.cardeck.neo.shizuku.Shell
import java.util.concurrent.Executors

/**
 * Single management entry for the "force app landscape" compat override:
 *   - the user preference is persisted in this app's SharedPreferences (read/written by the page switch);
 *   - the system-side override (am compat, persistent) is kept only **during a session**:
 *     at session setup [applyAllAsync] writes it app by app,
 *     at session end (disconnect / stream stop) [resetAllAsync] restores everything -
 *     so a leftover override does not affect the app's normal portrait use on the phone.
 *
 * **Must run off the connection critical path**: the two `am compat` calls per app are shell round trips,
 * measured ~1.6-2.0s for a list of 6 apps. Earlier, restore ran synchronously before pairing and the write ran
 * synchronously inside virtual-display attach, stretching the Audi connection by about 3.5s (measured 2026-10-18). Both share one
 * single-thread
 * executor so the order "restore the previous session, then write this one" is serialized (concurrent threads would overwrite each other).
 *
 * Effective combination (measured on Toutiao / Douyin):
 *   OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR + OVERRIDE_ANY_ORIENTATION
 */
object AppOrientationCompat {
    const val CHANGE_UNDEFINED_TO_NOSENSOR = 265451093
    const val CHANGE_ANY_ORIENTATION = 265464455

    /**
     * Minimum SDK that supports forced landscape (Android 14). The two ChangeIds are AOSP standard items,
     * defined in `android.content.pm.ActivityInfo` since Android 14
     * (`@ChangeId @Disabled @Overridable`) and consumed by `LetterboxUiController`;
     * Android 13 and earlier have no orientation-type ChangeId at all.
     */
    const val MIN_SUPPORTED_SDK = 34
    private const val PREFS = "app_orientation"

    /** Serial executor: restore and write run strictly in call order and never occupy the connection / UI threads. */
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orientation-compat").apply { isDaemon = true }
        }

    /**
     * Preference keys are stored by package name (am compat also targets package names; the read side uses removePrefix to stay compatible
     * with old keys).
     */
    private fun key(pkg: String) = "force_$pkg"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(
        context: Context,
        pkg: String,
    ): Boolean = prefs(context).getBoolean(key(pkg), false)

    /**
     * Result of the system-side write. Distinguishes "permanently unsupported" from "temporary failure" - the two call for opposite
     * preference handling:
     * permanently unsupported must not leave a preference (otherwise the next connection wastes a run and the panel disagrees with system
     * state),
     * a temporary failure should keep the user's intent (the next connection / a retry may still take effect).
     */
    enum class WriteResult {
        /** Both ChangeIds were written successfully. */
        OK,

        /**
         * This device's **platform lacks the capability**; retrying never succeeds. It can be decided from the SDK version:
         *
         * **The two ChangeIds are AOSP standard items, introduced in Android 14 (SDK 34)**
         * (`android.content.pm.ActivityInfo`, with `@ChangeId @Disabled @Overridable`),
         * effective in `LetterboxUiController`; refactored into
         * `AppCompatOrientationOverrides` from Android 16. Android 13 and earlier have **no orientation-type
         * ChangeId at all** (measured: 0 in A13's ActivityInfo, 9 in A14).
         *
         * So the causes of "unsupported on this device", by likelihood:
         * 1. **Android 13 and earlier** (SDK < 34): the platform never defines them -> `dumpsys
         *    platform_compat` has no name for the ChangeId, and `am compat` first warns
         *    `Change ... is not known yet`. Example: Redmi K40 (Android 13 / SDK 33).
         * 2. **The OEM stripped these ChangeIds**: in theory all A14+ have them, but some ROMs may remove them;
         *    same criterion (no name in dumpsys).
         * 3. **user build + non-debuggable app**: even when the ChangeId is registered,
         *    `PlatformCompat` only allows changing debuggable apps and prints
         *    `Cannot override a change on a non-debuggable app and user build.`
         *
         * None of these is a Shizuku problem (shell and UserService are both uid 2000, same result).
         * What the user can do: move to a device running Android 14+.
         */
        UNSUPPORTED,

        /** Temporary failure (Shizuku not ready / timeout / command error) - retryable. */
        FAILED,
    }

    /**
     * **Asynchronous wrapper** for the user switch: the only entry from the UI thread.
     *
     * `setEnabled` internally does 2 `Shell.exec` calls (4s timeout each), 8s worst case - putting it directly in
     * the checkbox click callback would far exceed the ANR threshold (fixed in the same batch 2026-10-18). It is now posted to the
     * serial executor and the result is called back on the main thread; **the serial executor** guarantees that repeated clicks on one app
     * apply write/restore in click order and do not overwrite each other.
     */
    fun setEnabledAsync(
        context: Context,
        pkg: String,
        enable: Boolean,
        onResult: (WriteResult) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { setEnabled(app, pkg, enable) }.getOrElse { WriteResult.FAILED }
            android.os
                .Handler(android.os.Looper.getMainLooper())
                .post { onResult(result) }
        }
    }

    /**
     * User switch: writes the system override (used by the settings page). **The preference is saved only after success.**
     *
     * **Background thread only** (2 shell round trips inside, 8s worst case). The UI uses [setEnabledAsync].
     *
     * For failure classes see [WriteResult]: on `UNSUPPORTED` the preference stays unchanged (the user's intent is not persisted,
     * since persisting only makes the next connection waste another applyAll); `FAILED` keeps the user's intent.
     */
    fun setEnabled(
        context: Context,
        pkg: String,
        enable: Boolean,
    ): WriteResult {
        val action = if (enable) "enable" else "disable"
        val first = Shell.exec("am compat $action $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
        val second =
            if (first.ok) {
                Shell.exec("am compat $action $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            } else {
                // If the first write is rejected, skip the second (same restriction, saves a pointless shell round trip)
                first
            }
        if (first.ok && second.ok) {
            prefs(context).edit().putBoolean(key(pkg), enable).apply()
            return WriteResult.OK
        }
        val unsupported = isPermanentRejection(first.output) || isPermanentRejection(second.output)
        if (!unsupported) {
            // Temporary failure: keep the user's intent; the next connection's applyAll will try again
            prefs(context).edit().putBoolean(key(pkg), enable).apply()
        }
        // Diagnostics must tell apart the two permanent causes (ChangeId unregistered / user build non-debuggable)
        diagSink?.invoke(
            "ORIENTATION: compat $action " +
                (if (unsupported) "unsupported on this device" else "failed") +
                " pkg=$pkg change=$CHANGE_UNDEFINED_TO_NOSENSOR exit=${first.exitCode} out=${first.output.take(160)}" +
                if (unsupported) " (${describeUnsupported()})" else "",
        )
        return if (unsupported) WriteResult.UNSUPPORTED else WriteResult.FAILED
    }

    /**
     * Whether this device **has the capability** (a platform fact).
     *
     * Decided in two steps, fast and slow combined:
     * 1. **SDK version gate** (zero cost): the two ChangeIds are AOSP standard items, defined in
     *    `android.content.pm.ActivityInfo` since
     *    **Android 14 / SDK 34** (`@ChangeId @Disabled @Overridable`, consumed by `LetterboxUiController`;
     *    refactored into `AppCompatOrientationOverrides` from Android 16).
     *    Android 13 and earlier ActivityInfo has **no orientation-type ChangeId at all**
     *    (measured: A13 = 0, A14 = 9) -> decided unsupported directly, no shell needed.
     * 2. **dumpsys re-check** (only when SDK >= 34): confirm the ROM really registered the two IDs
     *    (some OEMs may strip them). Registered entries carry `name=...`; unregistered ones have only the bare
     *    `ChangeId(...; rawOverrides={...})`.
     *
     * The result is cached in-process (it cannot change within one install).
     */
    fun changeIdsRegistered(): Boolean {
        cachedRegistered?.let { return it }
        // 1) Version gate: platforms below Android 14 have no such ChangeIds at all
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK) {
            cachedRegistered = false
            return false
        }
        // 2) dumpsys re-check: take and verify each ID's line separately - a single `name=` search over the whole output must not be used
        //    (if only one is registered it would be misjudged as both usable, and the second write would fail afterwards).
        val first = changeIdLine(CHANGE_UNDEFINED_TO_NOSENSOR)
        val second = changeIdLine(CHANGE_ANY_ORIENTATION)
        val value = first.contains("name=") && second.contains("name=")
        // When dumpsys is unavailable (command failed), do not cache; leave it for the next retry
        if (first.isNotBlank() || second.isNotBlank()) cachedRegistered = value
        return value
    }

    /** The line of a ChangeId in `dumpsys platform_compat` (may be empty when unregistered). */
    private fun changeIdLine(changeId: Int): String =
        Shell
            .exec(
                "dumpsys platform_compat | grep -F 'ChangeId($changeId;' | head -1",
                timeoutMs = 6_000L,
            ).output

    @Volatile
    private var cachedRegistered: Boolean? = null

    /** Concrete cause of a permanent failure (written to the log to ease cross-device investigation). */
    private fun describeUnsupported(): String =
        when {
            Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK ->
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) is below 14" +
                    " - these two ChangeIds only entered the platform in Android 14"

            !changeIdsRegistered() ->
                "SDK ${Build.VERSION.SDK_INT} but this ROM did not register the two ChangeIds (suspected OEM stripping)"

            else ->
                "ChangeId registered, but a user build does not allow modifying non-debuggable apps"
        }

    /**
     * Whether this command output is a "permanent failure" (not retryable).
     *
     * Covers two wordings: when a ChangeId is unregistered `am compat` first warns `is not known yet` and then refuses;
     * when registered but restricted by a user build it prints `non-debuggable app and user build`.
     * Both are unrelated to Shizuku, so retrying is useless.
     */
    private fun isPermanentRejection(output: String): Boolean = classifyCompatFailure(output) == WriteResult.UNSUPPORTED

    /** Diagnostics log outlet (SessionController.appendLog wired to the file log). */
    @Volatile
    var diagSink: ((String) -> Unit)? = null

    /** Async version of [applyAll]: called on the connection critical path (log is called back after completion, to ease investigation). */
    fun applyAllAsync(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val count = runCatching { applyAll(app) }.getOrDefault(0)
            if (count > 0) log("ORIENTATION: forced landscape enabled for $count apps (effective during the session)")
        }
    }

    /** Async version of [resetAll]: called when the session retires, without blocking later pairing. */
    fun resetAllAsync(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val count = runCatching { resetAll(app) }.getOrDefault(0)
            if (count > 0) log("ORIENTATION: restored display settings of $count apps")
        }
    }

    /**
     * Waits for queued override writes/restores to finish (at most [timeoutMs]).
     *
     * **Must be called before an app is launched**: `am compat enable` kills the target app immediately. Since override writes became
     * asynchronous, a user tapping a desktop icon before the write completes would see the app killed by the write right after it starts.
     * The executor is single-thread FIFO, so submitting an empty task and waiting for it is equivalent to a drain.
     *
     * @return whether it drained within the time limit; false = timeout (the caller must leave a trace - silent failure would quietly void
     * the guarantee "write the override first, then launch the app", and the app still gets killed; settled in the 2026-10-23 review).
     */
    fun awaitPending(timeoutMs: Long = 6_000L): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute { done.countDown() }
        return try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Set before the first map launch, serialized after restore; records the session list and restores the phone orientation after
     * disconnect.
     */
    fun prepareMap(
        context: Context,
        pkg: String,
    ) {
        val app = context.applicationContext
        runCatching {
            executor
                .submit {
                    val store = prefs(app)
                    val prepared = store.getStringSet("session_maps", emptySet()).orEmpty()
                    if (pkg in prepared || Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK) return@submit
                    // Register first, so that if the process is interrupted or the second write fails the next round can still undo a
                    // half-written state.
                    store.edit().putStringSet("session_maps", prepared + pkg).commit()
                    val first = Shell.exec("am compat enable $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
                    val second = if (first.ok) Shell.exec("am compat enable $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000) else first
                    diagSink?.invoke("ORIENTATION: map adaptive pkg=$pkg ok=${first.ok && second.ok}")
                }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { diagSink?.invoke("ORIENTATION: map adaptive incomplete pkg=$pkg") }
    }

    /**
     * Session setup: writes the override app by app for the listed apps (idempotent). Returns the number of **successfully** written apps.
     */
    fun applyAll(context: Context): Int {
        val pkgs = enabledPkgs(context)
        if (pkgs.isEmpty()) return 0
        // Probe the platform capability once first: when the ROM did not register the two ChangeIds (Redmi Android 13, measured),
        // every app on the list would waste two shell round trips with 4s timeouts.
        // One dumpsys decides it; cost about 30ms, result cached in-process.
        if (!changeIdsRegistered()) {
            diagSink?.invoke(
                "ORIENTATION: this ROM did not register the landscape compat ChangeIds - skipping all overrides this time" +
                    " (${pkgs.size} apps on the list; this device does not support forced landscape)",
            )
            return 0
        }
        var count = 0
        for (pkg in pkgs) {
            val noKill = if (isMap(context, pkg)) " --no-kill" else ""
            val first = Shell.exec("am compat enable$noKill $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
            if (!first.ok) {
                // ChangeId registered but still refused -> a user build does not allow changing non-debuggable apps;
                // likewise stop as a whole instead of running empty per app.
                if (isPermanentRejection(first.output)) {
                    diagSink?.invoke(
                        "ORIENTATION: system restriction - a user build only allows changing debuggable apps'" +
                            " compat settings; skipping all landscape overrides this time (${pkgs.size} apps on the list)",
                    )
                    return 0
                }
                continue
            }
            val second = Shell.exec("am compat enable$noKill $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            if (second.ok) count++
        }
        return count
    }

    /** Session end: restores the listed apps (preference kept, only the system override is revoked). Returns the number of apps handled. */
    fun resetAll(context: Context): Int {
        var count = 0
        val sessionMaps = prefs(context).getStringSet("session_maps", emptySet()).orEmpty()
        for (pkg in enabledPkgs(context) + sessionMaps) {
            // A map may still be speaking directions after disconnect. A compat write must not kill its process.
            val noKill = if (pkg in sessionMaps || isMap(context, pkg)) " --no-kill" else ""
            Shell.exec("am compat disable$noKill $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
            Shell.exec("am compat disable$noKill $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            count++
        }
        prefs(context).edit().remove("session_maps").commit()
        return count
    }

    private fun isMap(
        context: Context,
        pkg: String,
    ): Boolean = pkg in VirtualLauncher.MAP_PACKAGES_PRIORITY || pkg == DefaultMapPrefs.manualPackage(context)

    /**
     * Set of "override keys" for which the system side currently really has the override on (source of truth; a key may be a package name
     * or a
     * `package/component` string from historical dirty data).
     *
     * The preference file cannot show the "actual system state": **uninstall deletes preferences, yet the system-side am compat override
     * stays**
     * (measured 2026-10-16: the device had leftovers for Feishu / Dianping / Honor Cloud Music). The query can only use
     * `dumpsys platform_compat` (this ROM has no `cmd platform_compat`); measured 30ms/36KB in full,
     * about 3KB once filtered to the two ChangeIds of interest - **query once when the panel opens**, rows only do in-memory lookups,
     * never one query per row (40+ apps x ~100ms = jank).
     *
     * **Both packageOverrides and rawOverrides must be parsed**: an uninstalled app's override exists only in
     * rawOverrides - packageOverrides is the platform's mapping resolved over "installed packages", so entries vanish when the package is
     * gone
     * (measured 2026-10-16: an override written for an uninstalled package is visible only in rawOverrides). The "other system
     * leftovers" summary is designed exactly for uninstall leftovers; missing this read would leave the banner empty forever.
     *
     * Union over the two ChangeIds: a half-on state with only one enabled also counts as "enabled" (cleanup writes both).
     */
    fun systemEnabled(): Set<String> {
        val out =
            Shell
                .exec(
                    "dumpsys platform_compat | grep -E 'ChangeId\\(${CHANGE_UNDEFINED_TO_NOSENSOR};|ChangeId\\(${CHANGE_ANY_ORIENTATION};'",
                    timeoutMs = 6_000L,
                ).output
        if (out.isBlank()) return emptySet()
        return out
            .lineSequence()
            .flatMap { line ->
                // packageOverrides={com.a=true, ...}; rawOverrides may also contain package/component keys
                Regex("(?:packageOverrides|rawOverrides)=\\{([^}]*)\\}")
                    .findAll(line)
                    .flatMap { m ->
                        // Key allows most printable characters (including component keys with /); only take =true
                        Regex("([^=,{}\\s]+)=true").findAll(m.groupValues[1]).map { it.groupValues[1] }
                    }
            }.toSet()
    }

    /**
     * Whether the system-side override for a package is really on (precondition for triggering the letterbox hint).
     *
     * The preference alone is not enough: when `am compat` fails temporarily the preference is kept (user intent) but the system has no
     * override,
     * and a "force restart" hint would be doomed to fail. The probe runs once on candidate apps only, cost about one grep.
     */
    fun isSystemEnabledFor(pkg: String): Boolean = pkg in systemEnabled()

    /**
     * Clears the system-side override of a single "override key" (writes disable for both ChangeIds).
     * Touches only the system override, **not the preference** - used to clean entries whose "preference is gone (uninstall leftover) but
     * override is still on";
     * for apps on the list use [setEnabled] (it syncs the preference so the UI does not diverge from system state).
     * Note: am compat disable kills the target app process immediately (new settings need an app restart to take effect).
     */
    fun clearSystemOnly(key: String): Boolean {
        val ok1 = Shell.exec("am compat disable $CHANGE_UNDEFINED_TO_NOSENSOR $key", timeoutMs = 4000).ok
        val ok2 = Shell.exec("am compat disable $CHANGE_ANY_ORIENTATION $key", timeoutMs = 4000).ok
        return ok1 && ok2
    }

    private fun enabledPkgs(context: Context): List<String> =
        prefs(context)
            .all
            .filterValues { it == true }
            .keys
            .map { it.removePrefix("force_") }

    /** "App landscape" list (set of package names): used by letterbox-hint probing, read-only. */
    fun enabledPackages(context: Context): Set<String> = enabledPkgs(context).toSet()
}

/**
 * Classifies the failure output of `am compat` (pure function, unit-testable).
 *
 * Three wordings are judged **permanent failure** (all unrelated to Shizuku, retrying is useless):
 *  1. `Cannot override a change on a non-debuggable app and user build.`
 *  2. `Cannot override a default enabled/disabled change on a user build.`
 *  3. `Change <id> is not known yet.` - this ROM did not register the ChangeId
 *     (these two IDs are OEM-specific, absent from the AOSP standard table; missing on Redmi Android 13, measured)
 *
 * Measured output (2026-10-18):
 *  - Redmi (unregistered):
 *      `Warning! Change 265451093 is not known yet. Enabling/disabling it could have no effect.`
 *      `Cannot override a change on a non-debuggable app and user build.`
 *  - Honor (registered, but the same kind of refusal when the target is a non-debuggable app): the second wording above
 *  - Shizuku not connected: `Shizuku not connected` (temporary, retryable)
 */
internal fun classifyCompatFailure(output: String): AppOrientationCompat.WriteResult =
    if (output.contains("non-debuggable app and user build") ||
        output.contains("default enabled/disabled change on a user build") ||
        output.contains("is not known yet")
    ) {
        AppOrientationCompat.WriteResult.UNSUPPORTED
    } else {
        AppOrientationCompat.WriteResult.FAILED
    }
