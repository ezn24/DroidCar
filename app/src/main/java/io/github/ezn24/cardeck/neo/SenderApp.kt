package io.github.ezn24.cardeck.neo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher
import io.github.ezn24.cardeck.neo.ui.ScreenDimmer
import io.github.ezn24.cardeck.neo.ui.UiStrings
import io.github.ezn24.cardeck.neo.util.DebugFileLog

/**
 * Process-wide safety net: an uncaught exception on any thread is only logged and ends that thread,
 * instead of the default handler killing the whole process (a connection session spans several background
 * threads; one socket exception must not take down every link and the foreground service).
 * Each thread should still handle its own errors with a precise try/catch; this is the last line of defense.
 */
open class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Head-unit wallpaper follows the phone's light/dark mode: notify the head-unit side to reload on config change
        io.github.ezn24.cardeck.neo.ui.WallpaperStore
            .attach(this)
        // Retry pending restoration after process restart or Shizuku reconnect.
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
            ScreenDimmer.syncScreenTimeout(this)
        }
        UiStrings
            .initialize(this)
        io.github.ezn24.cardeck.neo.ui.AppLanguage
            .restore(this)
        // Session log start marker: the log file accumulates across processes (2MB cap) and export defaults to the part after
        // this launch. It must be written here (Application is the earliest process entry point); anything later than
        // the first business log would count it as "history".
        DebugFileLog.markSessionStart(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception (thread ${thread.name}, swallowed to keep the process alive)", throwable)
            // Do not call previous: the default handler would killProcess the whole app
        }
        // App install/uninstall/update: invalidate the shared app-list cache used by the desktop and head-unit settings
        val packageFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    VirtualLauncher
                        .invalidateAppsCache()
                    Log.i(TAG, "App list cache invalidated (package change)")
                }
            },
            packageFilter,
        )
    }

    companion object {
        private const val TAG = "CarPlaySender"
    }
}
