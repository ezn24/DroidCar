package io.github.ezn24.droidcar.capture

import android.content.Context
import android.view.Surface
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import io.github.ezn24.droidcar.shizuku.CarInputClient
import io.github.ezn24.droidcar.shizuku.ShizukuGate

/**
 * Hidden-display mode: creates a PUBLIC virtual display at the head unit's resolution through the regular API (its surface is the encoder input),
 * and launches apps into it via Shizuku (shell) `am start --display`; the phone's main screen can turn off normally.
 *
 * The launch target prefers the Amap car edition (landscape by design; its onboarding page drops --display, so
 * MainMapActivity is started explicitly and forced fullscreen), falling back to the phone edition of Amap if it is not installed.
 * (Ported from the reference project android-auto, VirtualDisplaySource.)
 */
class HiddenDisplaySource(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : ScreenSource {
    override val name: String = "隐形屏"

    /** Compatibility field: points at the app display's displayId in the multi-display architecture. */
    var displayId: Int = -1
        private set

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        detach()
        // Per-state diagnostics: Shizuku "running" does not mean this app is authorized (the server-side grant needs a one-time dialog)
        when (val gate = ShizukuGate.state(context)) {
            ShizukuGate.State.READY -> Unit

            ShizukuGate.State.NOT_INSTALLED -> {
                log("SHIZUKU: Shizuku app not installed (shizuku.rikka.app)")
                return false
            }

            ShizukuGate.State.NOT_RUNNING -> {
                log("SHIZUKU: service not running - open the Shizuku app and tap Start (needed again after a phone reboot)")
                return false
            }

            ShizukuGate.State.UNAUTHORIZED -> {
                log("SHIZUKU: this app is not authorized - permission request sent; tap Allow in the dialog, then reconnect")
                ShizukuGate.requestPermission()
                return false
            }
        }
        // Wait until the UserService is actually ready before creating displays: the session attaches right after RECORD, and the service may still be
        // spawning (slower still with the debug build loading dex). Declaring it unavailable immediately would needlessly fall back to the app-side PUBLIC display,
        // a path that depends even more on the UserService; with the service down there is no touch at all (2026-10-22 K60E).
        // This method already runs on a background thread (VideoSender.start callback), so blocking here is safe.
        val svc = CarInputClient.awaitReady(context, USER_SERVICE_WAIT_MS)
        if (svc == null) {
            log(
                "SHIZUKU: UserService not ready within ${USER_SERVICE_WAIT_MS}ms" +
                    " - falling back to the app-side PUBLIC display (touch may be unavailable; check whether the system froze Shizuku)",
            )
        }
        // Dual-display launcher: GL composer (dock display + app display) -> encoder Surface
        val ok =
            VirtualLauncher.attach(
                context,
                surface,
                width,
                height,
                densityDpi,
                log,
            )
        displayId =
            if (ok) VirtualLauncher.appDisplayId else -1
        return ok
    }

    override fun detach() {
        VirtualLauncher
            .detach()
        displayId = -1
    }

    override fun release() = detach()

    private companion object {
        /**
         * Upper bound on waiting for the UserService. Trade-off: too short misjudges it as unavailable and falls back to the public display (touch lost),
         * too long delays the first frame. The head unit has a watchdog on screen-stream SETUP -> activation, but RECORD is already done and
         * the video data port is connected, so waiting 3s more does not endanger the session.
         */
        const val USER_SERVICE_WAIT_MS = 3_000L
    }
}
