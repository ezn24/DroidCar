package io.github.ezn24.droidcar.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import io.github.ezn24.droidcar.shizuku.Shell
import io.github.ezn24.droidcar.util.DebugFileLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **Transparent trampoline** that silently obtains a MediaProjection token (auto-connect only).
 *
 * Why: AudioPlaybackCapture needs a MediaProjection instance, and on Android 14+ the token is
 * **single use** (void once capture stops), and during auto-connect nobody can tap "Allow screen capture".
 *
 * Two system gates and how we handle them:
 *  1. **Consent dialog**: `appops set <pkg> PROJECT_MEDIA allow` makes SystemUI's
 *     `MediaProjectionPermissionActivity` return RESULT_OK directly (same approach as Tasker's docs);
 *  2. **Background activity launch (BAL)**: Shizuku runs
 *     `am start -n <pkg>/<activity> --activity-no-animation` to start this page; the shell holds
 *     `START_ACTIVITIES_FROM_BACKGROUND` and is not subject to the app-side BAL limit. On failure fall back to
 *     `context.startActivity` (needs `SYSTEM_ALERT_WINDOW`, granted via `appops` first if missing).
 *
 * The page itself: 1x1, untouchable, unfocusable, no animation, kept out of recents; finishes as soon as it has a result.
 *
 * **Use and discard**: `CarPlayService` immediately turns the token into a MediaProjection instance; this page holds no state.
 */
class SilentProjectionActivity : Activity() {
    /** Grant result (resultCode + the Intent needed to obtain a MediaProjection). */
    data class Grant(
        val resultCode: Int,
        val data: Intent,
    )

    private fun complete(
        grant: Grant?,
        error: String?,
    ) {
        val future = pending.getAndSet(null) ?: return
        if (grant != null) {
            future.complete(grant)
        } else {
            future.completeExceptionally(IllegalStateException(error ?: "unknown reason"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // With the phone in a pocket (locked + screen off) background activities are not resumed and onActivityResult
        // hangs forever; these two flags let this page finish even on the lock screen (with dim cover/true screen-
        // off, the user notices nothing).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // 1x1, untouchable, unfocusable: invisible to the user
        window.setLayout(1, 1)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (t: Throwable) {
            complete(null, "createScreenCaptureIntent failed: $t")
            finishSilently()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        if (resultCode == RESULT_OK && data != null) {
            complete(Grant(resultCode, data), null)
        } else {
            complete(null, "consent denied resultCode=$resultCode")
        }
        finishSilently()
    }

    override fun onDestroy() {
        // Destroyed without a result (user cancelled / reclaimed by the system): fail the waiter at once instead of waiting out the timeout
        complete(null, "Activity destroyed before returning a result")
        super.onDestroy()
    }

    private fun finishSilently() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_CODE = 10086

        /** Only one request at a time (auto-connect is serial). */
        private val pending = AtomicReference<CompletableFuture<Grant>?>(null)

        /**
         * Blocking token acquisition (**must be called on a background thread**, it runs 2~3 Shizuku commands).
         *
         * @return null = failed/timed out: the caller should fall back to video only
         */
        fun request(
            context: Context,
            timeoutMs: Long = 8_000L,
        ): Grant? {
            val app = context.applicationContext
            val future = CompletableFuture<Grant>()
            pending.getAndSet(future)?.completeExceptionally(IllegalStateException("superseded by a new request"))
            val pkg = app.packageName
            // 1) Allow projection: SystemUI sees PROJECT_MEDIA=allow and returns RESULT_OK without a dialog
            Shell.exec("appops set $pkg PROJECT_MEDIA allow", 5_000L)
            // 2) Without the overlay permission, background Activity/foreground-service launches are blocked; the dim cover needs it anyway
            if (!Settings.canDrawOverlays(app)) {
                val r = Shell.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow", 5_000L)
                DebugFileLog.append(app, "PROJ: granted 'display over other apps' ok=${r.ok} (${r.output.take(60)})")
            }
            // 3) Recording permission (needed by AudioPlaybackCapture): grant it along the way if missing
            if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                val r = Shell.exec("appops set $pkg RECORD_AUDIO allow", 5_000L)
                DebugFileLog.append(app, "PROJ: granted recording permission ok=${r.ok} (${r.output.take(60)})")
            }
            // 4) Use shell am start to bypass the background-activity-launch limit; fall back to in-app start on failure
            val component = "$pkg/${SilentProjectionActivity::class.java.name}"
            val viaShell = Shell.exec("am start -n $component --activity-no-animation", 8_000L)
            if (!viaShell.ok) {
                DebugFileLog.append(
                    app,
                    "PROJ: am start failed (exit=${viaShell.exitCode} ${viaShell.output.take(80)}) - falling back to in-app startActivity",
                )
                runCatching {
                    app.startActivity(
                        Intent(app, SilentProjectionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    )
                }.onFailure {
                    DebugFileLog.append(app, "PROJ: in-app startActivity also failed (${it.javaClass.simpleName}: ${it.message})")
                }
            }
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                DebugFileLog.append(app, "PROJ: silent projection token acquisition failed (${t.javaClass.simpleName}: ${t.message})")
                null
            } finally {
                pending.compareAndSet(future, null)
            }
        }
    }
}
