package io.github.ezn24.droidcar.bridge

import android.os.Handler
import android.os.Looper
import io.github.ezn24.droidcar.SenderApp
import io.github.ezn24.droidcar.ui.ScreenDimmer
import io.github.ezn24.droidcar.util.Bg
import io.github.ezn24.droidcar.util.DebugFileLog

/**
 * Application of the remote variant (the manifest overrides android:name).
 *
 * Installs the log tap and the debug bridge at the earliest process entry point; the standard variant does not contain this file, so none
 * of it exists there.
 */
class BridgeApp : SenderApp() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // Remote troubleshooting needs a live view of the phone screen: automatic fake screen-off/dim (black screen after 45s idle) is
        // always disabled in this variant, keeping only always-on; manual fake screen-off is unaffected (user request 2026-10-24).
        ScreenDimmer.autoDimAllowed = false
        DebugFileLog.append(
            this,
            "BRIDGE: remote debug variant started (${io.github.ezn24.droidcar.BuildConfig.VERSION_NAME}, auto dim disabled)",
        )
        // Disconnects are visible to the user: overlay hint + auto-reconnect countdown in the settings status line (2026-10-22
        // user request). On recovery a short hint closes it out; repeated flapping is naturally de-duplicated by the overlay singleton (a
        // new popup replaces the old one).
        BridgeClient.connectionEvents = { connected ->
            main.post {
                if (connected) {
                    BridgeOverlay.show(this, "调试连接已恢复", dismissMillis = 3_000)
                } else {
                    BridgeOverlay.show(
                        this,
                        "调试连接已断开——正在自动重连（进度见调试页状态行）",
                        dismissMillis = 6_000,
                    )
                }
            }
        }
        // First clear any stuck old install session (so it cannot block later installs), then decide connect/resume
        Bg.run("bridge-clean-sessions") { ReinstallFlow.abandonStaleSessions(this) }

        // Resume after reinstall: the flag set before the last install -> launch the settings page and ask "Continue into debug mode?"
        // (a proper dialog rather than an overlay, since overlays are easily ignored; user feedback)
        if (BridgePrefs.resumePending(this)) {
            DebugFileLog.append(this, "BRIDGE: pre-reinstall marker detected -- asking whether to continue debugging")
            BridgeSetupActivity.startForResume(this)
        } else if (BridgePrefs.autoConnect(this) && BridgePrefs.serverUrl(this).isNotBlank()) {
            Bg.run("bridge-start") { BridgeClient.start(this) }
        }
    }
}
