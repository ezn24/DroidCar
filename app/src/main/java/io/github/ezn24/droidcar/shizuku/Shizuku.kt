package io.github.ezn24.droidcar.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.ezn24.droidcar.R
import io.github.ezn24.droidcar.ui.UiStrings
import io.github.ezn24.droidcar.util.Bg
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku gateway + shell execution + CarInput UserService binding.
 * Activation (wireless debugging, Android 11+, no root): Developer options -> Wireless debugging -> pair -> tap Start in the Shizuku app.
 * (Ported from the reference project android-auto)
 */
object ShizukuGate {
    enum class State { NOT_INSTALLED, NOT_RUNNING, UNAUTHORIZED, READY }

    /** Shizuku permission request code (the grant callback filters on it). */
    const val PERMISSION_REQUEST_CODE = 101

    /** Shizuku app package name (shared by launch/detection). */
    const val PACKAGE_NAME = "moe.shizuku.privileged.api"

    fun state(context: Context): State {
        return try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return State.NOT_INSTALLED
        }.let {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) return State.NOT_RUNNING
                if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    State.READY
                } else {
                    State.UNAUTHORIZED
                }
            } catch (_: Throwable) {
                State.NOT_RUNNING
            }
        }
    }

    internal fun localizedGuidanceOf(state: State): ShizukuGuidance =
        when (state) {
            State.NOT_INSTALLED ->
                ShizukuGuidance(
                    UiStrings.text(R.string.shizuku_install),
                    UiStrings.text(R.string.shizuku_install_body),
                    null,
                )
            State.NOT_RUNNING ->
                ShizukuGuidance(
                    UiStrings.text(R.string.shizuku_start),
                    UiStrings.text(R.string.shizuku_start_body),
                    UiStrings.text(R.string.shizuku_open),
                )
            State.UNAUTHORIZED ->
                ShizukuGuidance(
                    UiStrings.text(R.string.shizuku_allow),
                    UiStrings.text(R.string.shizuku_allow_body),
                    UiStrings.text(R.string.shizuku_request),
                )
            State.READY -> ShizukuGuidance(UiStrings.text(R.string.shizuku_ready), "", null)
        }

    fun requestPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /**
     * Guidance text for the not-ready states (pure function, unit-testable).
     *
     * The three not-ready states need **completely different actions** (install/start/authorize); a single
     * "Shizuku not ready" leaves users stuck, which is the first thing friends get asked about once the app is shared.
     */
    internal fun guidanceOf(state: State): ShizukuGuidance =
        when (state) {
            State.NOT_INSTALLED ->
                ShizukuGuidance(
                    title = "需要先安装 Shizuku",
                    message =
                        "连接车机依赖 Shizuku 提供的 shell 权限：创建虚拟屏、注入触摸、" +
                            "读取窗口状态都要经过它。\n\n" +
                            "请先安装 Shizuku App 并按官方说明启动服务（无线调试激活，免 root），" +
                            "完成后回到这里点「重试检查」。",
                    primaryLabel = null,
                )

            State.NOT_RUNNING ->
                ShizukuGuidance(
                    title = "Shizuku 服务未运行",
                    message =
                        "Shizuku 已安装，但服务当前没有运行，此时连接会失败" +
                            "（车机没有画面、触摸也不可用）。\n\n" +
                            "请打开 Shizuku App 点「启动」启动服务（手机重启后需要重新启动一次），" +
                            "然后回来点「重试检查」。",
                    primaryLabel = "打开 Shizuku",
                )

            State.UNAUTHORIZED ->
                ShizukuGuidance(
                    title = "未授权本应用使用 Shizuku",
                    message =
                        "Shizuku 服务已在运行，但本应用还没有获得授权，此时连接会失败" +
                            "（车机没有画面、触摸也不可用）。\n\n" +
                            "点「请求授权」并在弹出的窗口中点「允许」；若之前拒绝过，" +
                            "请到 Shizuku App →「授权应用」里手动允许本应用。",
                    primaryLabel = "请求授权",
                )

            State.READY -> ShizukuGuidance("Shizuku 就绪", "", null)
        }
}

/** Guidance content when Shizuku is not ready (see [ShizukuGate.guidanceOf]). */
internal data class ShizukuGuidance(
    val title: String,
    val message: String,
    /** null = no primary action available on this device (nowhere to jump when not installed). */
    val primaryLabel: String?,
)

/** Runs commands as the shell user (same as adb shell). Shizuku api 13 must call newProcess through the IShizukuService binder. */
object Shell {
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** Cumulative count of `Shell.exec` calls made on the main thread (diagnostic; see the guard in [exec]). */
    @Volatile
    var mainThreadViolations: Int = 0
        private set

    /**
     * Warning sink for shell calls on the main thread (SessionController.appendLog).
     * One shell command is typically ~100ms and can time out after seconds; a few in a row is enough for an ANR.
     * This only shows up on real devices (logcat is unreadable under HKS), so it must go to the file log.
     */
    @Volatile
    var violationSink: ((String) -> Unit)? = null

    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result {
        // **No shell on the UI thread**: every exec is a Shizuku newProcess + process wait.
        // We do not hard-block it here (that would break features that currently work), but we log a warning:
        // two real-device ANRs in the past came from this (detach cleanup, the landscape checkbox).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mainThreadViolations++
            violationSink?.invoke(
                "SHELL: WARNING shell called on main thread (#$mainThreadViolations) cmd=${cmd.take(60)}" +
                    " (${timeoutMs}ms limit) - use Bg.run / a background thread",
            )
        }
        val binder =
            rikka.shizuku.Shizuku.getBinder()
                ?: return Result(-1, "Shizuku not connected")
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)

            Bg.run("shell-stderr") {
                try {
                    process.errorStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes()
                    }
                } catch (_: Exception) {
                }
            }

            val output =
                try {
                    process.inputStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes().decodeToString().trim()
                    }
                } catch (_: Exception) {
                    ""
                }

            if (!process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())) {
                process.destroy()
            }
            val exit =
                try {
                    process.exitValue()
                } catch (_: Exception) {
                    -1
                }
            Result(exit, output)
        } catch (e: Exception) {
            Result(-1, "command execution failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/**
 * CarInput UserService binding management: bind once, then all touch injection goes straight to the resident shell
 * process (millisecond level).
 */
object CarInputClient {
    @Volatile
    var service: ICarInput? = null
        private set

    /**
     * Waits for the UserService to be ready (blocking, **call only from a background thread**).
     *
     * Right after the session activates the service may still be spawning. Judging "trusted display unavailable" at that
     * point and falling back to an app-side PUBLIC display needlessly gives up the trusted display's IME/touch advantages,
     * and the fallback relies on the UserService even more (2026-10-22 K60E: first touch injection failed, log
     * showed "three displays created (fallback)").
     *
     * @return the ready service; null on timeout (the caller applies its existing fallback logic).
     */
    fun awaitReady(
        context: Context,
        timeoutMs: Long = 3_000L,
    ): ICarInput? {
        service?.let { return it }
        ensure(context)
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            service?.let { return it }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return service
            }
            ensure(context)
        }
        return service
    }

    @Volatile
    private var binding = false

    @Volatile
    private var appContext: Context? = null

    /** UserService arguments (unbind needs the same instance). */
    @Volatile
    private var userServiceArgs: rikka.shizuku.Shizuku.UserServiceArgs? = null

    /** Diagnostic log sink (wired to the file log, since logcat is unreadable under HKS). */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(
        message: String,
        force: Boolean = false,
    ) {
        Log.i(TAG, message)
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - diagAt < 1_000L) return
        diagAt = now
        logSink?.invoke("SHIZUKU: $message")
    }

    private var binderListenerAdded = false

    /**
     * Reaps leftover UserService processes of old versions; returns the number actually killed (0 if the preconditions are not met).
     *
     * Shizuku reuses the resident shell process by "component + process name + version (lastUpdateTime)". After an APK
     * reinstall the old-version process is unaffected by force-stop and Shizuku does not reap it, while a virtual display can
     * only be released by the process that created it (releaseDisplay checks only its own map). The symptom is
     * "leftover displays detected after restart, cleanup releases 0/N". Here we enumerate :car_input processes and kill all
     * but the currently bound one: old processes hold only leftover displays, so reaping a process releases its displays
     * (touch injection is not interrupted, the current session's displays belong to the bound process).
     * Together with the UserService-side stale self-reap (CarInputServiceImpl) this is double insurance.
     *
     * Blocking (shell ps/kill), run it on a background thread; triggered once automatically after a successful bind.
     */
    fun reapStaleUserServices(): Int {
        val bound = service ?: return 0
        val keepPid =
            try {
                bound.pid()
            } catch (t: Throwable) {
                // Old dex has no pid() transaction (should not happen: Shizuku reuses by version, so the bound one is the current version)
                diag("reap skipped: pid() unavailable (${t.javaClass.simpleName})")
                return 0
            }
        val procName = (appContext?.packageName ?: return 0) + ":car_input"
        val ps = Shell.exec("ps -A -o PID,ARGS", 5_000L)
        if (!ps.ok) {
            diag("reap skipped: ps failed (${ps.output.take(40)})")
            return 0
        }
        val stale =
            ps.output
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    if (parts.getOrNull(1) == procName && pid != keepPid) pid else null
                }.toList()
        if (stale.isEmpty()) return 0
        val killed = stale.count { pid -> Shell.exec("kill -9 $pid", 3_000L).ok }
        // force: the 1s throttle window right after bind would swallow this key result (always check it when troubleshooting)
        diag(
            "reaped $killed/${stale.size} old-version UserService processes " +
                "(their leftover virtual displays are released with them)",
            force = true,
        )
        return killed
    }

    /**
     * Powers the physical main display off/on (true screen-off, like scrcpy `-S`). Returns false on failure:
     * the ROM did not give the shell the needed permission, or no display token is obtainable on SDK 34+.
     */
    fun setPhysicalScreenPower(on: Boolean): Boolean =
        try {
            service?.setPhysicalScreenPower(on) ?: false
        } catch (t: Throwable) {
            diag("setPhysicalScreenPower($on) failed: ${t.javaClass.simpleName} ${t.message}")
            false
        }

    /**
     * Injects a key into the given display (iDrive knob -> D-pad/confirm key).
     * Goes through the UserService's injectInputEvent (millisecond level); on failure the caller falls back to shell `input -d`.
     */
    fun keyEvent(
        displayId: Int,
        keyCode: Int,
    ): Boolean =
        try {
            service?.keyEvent(displayId, keyCode) ?: false
        } catch (t: Throwable) {
            diag("keyEvent($displayId,$keyCode) failed: ${t.javaClass.simpleName} ${t.message}")
            false
        }

    private val conn =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                // WARNING: this callback is posted by the Shizuku library to the **main thread** (ShizukuServiceConnection uses
                // new Handler(Looper.getMainLooper())). So **never make blocking calls** here:
                // ping() is a cross-process binder call and can block for hundreds of ms or more when the service process is busy
                // (creating displays/injecting); once the main thread is blocked, all later callbacks queue up and
                // binding stays true forever
                // - the "injection always deadlocks" chain pinned down on a real K60E on 2026-10-22.
                val bound = ICarInput.Stub.asInterface(binder)
                service = bound
                binding = false
                bindingSince = 0L
                Bg.run("shizuku-ping") {
                    val ok =
                        try {
                            bound?.ping() == true
                        } catch (_: Throwable) {
                            false
                        }
                    diag("connected ping=$ok", force = true)
                    if (!ok) {
                        // Clear only if this is still the result of the current bind (a rebind may have succeeded meanwhile)
                        if (service === bound) {
                            service = null
                            binding = false
                        }
                    } else {
                        // After a successful bind, reap leftover old-version processes (virtual displays they hold cannot be released
                        // across processes; only reaping the process makes them disappear). Runs on a background
                        // thread: it contains shell ps/kill.
                        reapStaleUserServices()
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                diag("service disconnected", force = true)
                service = null
                binding = false
                bindingSince = 0L
            }
        }

    /** Time of the last bind attempt (prevents high-frequency retries). */
    @Volatile
    private var lastBindAttemptAt = 0L

    /** Time the current bind was issued (used to judge "callback is very late"; 0 = no bind in progress). */
    @Volatile
    private var bindingSince = 0L

    /**
     * Ensures the UserService is bound. May be called at high frequency: when the service is null and >1.5s since
     * the last attempt it rebinds
     * (after a phone/Shizuku restart the binder may be lost while the process lingers, so the connection must be rebuilt actively).
     *
     * **This method may be called on the main thread** (MainActivity's startup probe), and bindUserService is
     * a synchronous binder call, so it is posted to the background and the main thread only hands it off.
     */
    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        addBinderListeners()
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // No binder call on the main thread: the callback itself also runs on the main Looper, so blocking here would self-lock
            Bg.run("shizuku-bind") { ensureInternal(app) }
            return
        }
        ensureInternal(app)
    }

    private fun addBinderListeners() {
        if (binderListenerAdded) return
        binderListenerAdded = true
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListener {
                // The Shizuku server came (back) up: the old connection is necessarily dead, allow one rebind
                diag("Shizuku binder ready - rebinding UserService", force = true)
                binding = false
                bindingSince = 0L
                lastBindAttemptAt = 0L
                appContext?.let { Bg.run("shizuku-bind") { ensureInternal(it) } }
            }
            rikka.shizuku.Shizuku.addBinderDeadListener {
                diag("Shizuku binder disconnected (server exited/restarted) - touch injection temporarily unavailable", force = true)
                service = null
                binding = false
                bindingSince = 0L
            }
        } catch (_: Throwable) {
        }
    }

    private fun ensureInternal(app: Context) {
        if (service != null) return
        val now = android.os.SystemClock.uptimeMillis()
        // 1.5s throttle: while Shizuku is frozen by the system a bind fails silently and must be restored quickly after thaw
        if (now - lastBindAttemptAt < BIND_THROTTLE_MS) return
        // **Reissue only when we have waited too long.** The old code judged "stuck" at a fixed 1.5s and, before rebinding, called
        // unbindUserService(remove=true), which **kills the just-started service process**:
        //   Shizuku's connected callback is posted to the main thread -> a busy main thread delays it ->
        //   after 1.5s judged "stuck" -> process killed -> respawned -> callback late again -> killed again ...
        // This is self-sustaining flapping that never converges (2026-10-22 real K60E: 1700+ injection failures,
        // a "bind stuck state cleared" line every 2~3 seconds).
        // Now: the wait window is widened to 8s and we **never kill the service process here** (rebinding is idempotent,
        // Shizuku calls back the binder of an already-running service again).
        if (binding) {
            val waited = if (bindingSince == 0L) 0L else now - bindingSince
            if (!shouldRebind(binding = true, waitedMs = waited, stuckTimeoutMs = BIND_STUCK_TIMEOUT_MS)) {
                // Still within the normal wait: no log, no interruption
                return
            }
            binding = false
            diag(
                "bind wait ${waited}ms still no callback - reissuing (service process not killed)",
                force = true,
            )
        }
        lastBindAttemptAt = now
        val gate = ShizukuGate.state(app)
        if (gate != ShizukuGate.State.READY) {
            diag("bind paused: state=$gate")
            return
        }
        binding = true
        bindingSince = now
        try {
            val args =
                userServiceArgs ?: rikka.shizuku.Shizuku
                    .UserServiceArgs(
                        // Package name is taken at runtime (do not hard-code it): follows automatically after a rename
                        ComponentName(app.packageName, CarInputServiceImpl::class.java.name),
                    ).processNameSuffix("car_input")
                    .debuggable(true)
                    // Version is the APK lastUpdateTime (see the serviceVersion note)
                    .version(serviceVersion())
                    .also { userServiceArgs = it }
            // **Never unbindUserService(remove=true)**: that parameter kills the remote service process.
            // The old code called it before every retry -> killed the just-spawned process -> the callback never arrived
            // -> retry and kill again, self-sustaining flapping (one root cause of the 2026-10-22 K60E injection deadlock).
            // bindUserService itself is idempotent: when the service is already running Shizuku calls the existing binder back
            // to the same ServiceConnection (deduplicated by Set), so no prior unbind is needed.
            rikka.shizuku.Shizuku.bindUserService(args, conn)
            diag("bindUserService requested (waiting for callback, timeout ${BIND_STUCK_TIMEOUT_MS}ms)")
        } catch (e: Throwable) {
            binding = false
            bindingSince = 0L
            diag("bindUserService failed: ${e.message}")
        }
    }

    /**
     * UserService version = APK lastUpdateTime (seconds). Shizuku reuses the resident service process by
     * "component + process name + version": with a hard-coded version a reinstalled APK does not change the process, and
     * the reused old process still runs the old dex so new code silently has no effect (2026-10-15 field test: the root cause of
     * trusted-display flags staying at the old 0x448 after adding SUPPORTS_IME; force-stop cannot kill these shell
     * processes, so they pile up).
     * lastUpdateTime always changes on reinstall -> a new process is always started; constant when the APK is
     * unchanged -> the resident process is reused normally.
     */
    private fun serviceVersion(): Int =
        try {
            val pm = appContext?.packageManager ?: return 15

            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(appContext!!.packageName, 0)
            (info.lastUpdateTime / 1000L).toInt()
        } catch (_: Throwable) {
            15 // Fallback: continues the historical hand-written version sequence, guaranteed not below 14
        }

    private const val TAG = "CarInputClient"

    /**
     * Minimum interval between two bind attempts (a bind fails silently while Shizuku is frozen, and must be restored
     * quickly after thaw).
     */
    private const val BIND_THROTTLE_MS = 1_500L

    /**
     * Upper bound on waiting before judging "this bind is stuck".
     *
     * Must be far larger than the normal callback delay: Shizuku's connected callback is posted to the main thread, and the service
     * process itself must spawn + load dex (slower for debug builds). The old 1.5s value equalled the throttle window, causing the
     * self-sustaining "issue -> judged dead after 1.5s -> kill process -> retry" flapping (2026-10-22 field test: a
     * line every 2~3 seconds).
     */
    private const val BIND_STUCK_TIMEOUT_MS = 8_000L
}

/**
 * Pure function for "should this bind be judged stuck and reissued" (core of [CarInputClient]'s bind state machine).
 *
 * Extracted as a pure function to pin down a regression found on a real device (2026-10-22 Redmi K60E): the old code
 * judged stuck unconditionally at "at least 1.5s since the last issue", **and that window is the same order as the normal
 * callback delay**: Shizuku's connected callback is posted to the main thread and the service process must also spawn +
 * load dex, so 1.5s is often not enough. After judging dead the old code also called `unbindUserService(remove=true)` and
 * killed the just-started service process, so "issue -> judged dead -> kill -> reissue" never converged and touch injection
 * was dead from the first touch (the log showed "bind stuck state cleared" every 2~3 seconds).
 *
 * @param binding whether a bind is currently in progress
 * @param waitedMs how long this bind has been waiting (ignored when [binding] is false)
 * @return true = reissue; false = keep waiting (or already idle and may issue)
 */
internal fun shouldRebind(
    binding: Boolean,
    waitedMs: Long,
    stuckTimeoutMs: Long,
): Boolean = !binding || waitedMs >= stuckTimeoutMs
