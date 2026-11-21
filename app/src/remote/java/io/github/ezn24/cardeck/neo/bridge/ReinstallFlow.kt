package io.github.ezn24.cardeck.neo.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import io.github.ezn24.cardeck.neo.SessionController
import io.github.ezn24.cardeck.neo.util.Bg
import io.github.ezn24.cardeck.neo.util.DebugFileLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Reinstall loop: the server pushes a new APK -> download and verify -> overlay hint -> after the tap,
 * **fully stop the session and release virtual displays first**, then go through PackageInstaller.
 *
 * Stopping the session to release the displays is a hard requirement: the install confirmation dialog would land on a still-alive
 * virtual display (car side) where nobody can tap it on the phone, the root cause of the "install hangs" recorded in AGENTS.md.
 */
object ReinstallFlow {
    private val main = Handler(Looper.getMainLooper())

    /**
     * System install confirmation Intent (carried by the PENDING_USER_ACTION broadcast). Honor observation: calling startActivity
     * on it directly from a BroadcastReceiver is silently blocked (no exception,
     * no UI, no failure receipt); .rd has no overlay permission and so is not exempt from background-start limits. It is now forwarded in
     * the foreground by
     * [BridgeSetupActivity] (2026-10-24).
     */
    @Volatile
    var pendingConfirmIntent: Intent? = null

    /**
     * Install session id submitted this time. PENDING/SUCCESS broadcasts are filtered by it: leftover sessions from
     * multiple failed attempts today may deliver late broadcasts, and a confirmation Intent from another session once led the new install
     * to the old version's
     * "install succeeded page" (2026-10-24 user photo: tapped install but saw the old version's success page + Done).
     */
    @Volatile
    var currentInstallSessionId: Int = -1

    /** Settings page component (absolute class name, used by am/intent; the applicationId suffix does not matter). */
    const val SETUP_ACTIVITY = "io.github.ezn24.cardeck.neo.bridge.BridgeSetupActivity"

    /**
     * Clean up stuck install sessions owned by this app. When an older version could not receive the PENDING_USER_ACTION broadcast
     * (receiver without intent-filter), nobody launched the confirmation and the session stayed Active forever,
     * also blocking later installs (2026-10-21 observed: after a session hung, two consecutive installs "succeeded" but nothing was
     * installed).
     * Called at app start; only affects sessions created by itself.
     */
    fun abandonStaleSessions(context: Context) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            installer.mySessions.forEach { info ->
                runCatching { installer.abandonSession(info.sessionId) }
                DebugFileLog.append(context, "BRIDGE: cleaning up leftover install session ${info.sessionId}")
            }
        }
    }

    fun offer(
        context: Context?,
        url: String,
        sha256: String,
        version: String,
        deltaUrl: String? = null,
        deltaSha256: String? = null,
        baseSha256: String? = null,
        deltaSizeKB: Int? = null,
    ) {
        val ctx = context?.applicationContext ?: return
        if (url.isBlank()) return
        DebugFileLog.append(
            ctx,
            "BRIDGE: new version push received v$version (" +
                if (deltaUrl.isNullOrBlank()) {
                    "full package"
                } else {
                    "delta package ${deltaSizeKB ?: "?"}KB" +
                        ")"
                },
        )
        Bg.run("bridge-apk-download") {
            // Delta first: typically 15MB -> 0.1~2MB (cellular-data friendly). If any step fails, unconditionally fall back to the
            // full download; delta is only an optimization and must never block an update.
            var file: File? = null
            var deltaNote: String? = null
            if (!deltaUrl.isNullOrBlank() && !deltaSha256.isNullOrBlank()) {
                file = tryDelta(ctx, deltaUrl, deltaSha256, baseSha256, version)
                if (file == null) {
                    DebugFileLog.append(ctx, "BRIDGE: delta update failed -- falling back to full download")
                } else if (deltaSizeKB != null) {
                    deltaNote = "差分包 ${deltaSizeKB}KB · 完整包 ${file.length() / 1024 / 1024}MB"
                }
            }
            if (file == null) {
                DebugFileLog.append(ctx, "BRIDGE: starting download $url")
                // A large package over the public internet may break mid-stream (observed on weak cellular), retry once
                file = download(ctx, url) ?: download(ctx, url)
                if (file == null) {
                    DebugFileLog.append(ctx, "BRIDGE: APK download failed: $url")
                    return@run
                }
                val digest = sha256Of(file)
                if (sha256.isNotBlank() && !digest.equals(sha256, ignoreCase = true)) {
                    DebugFileLog.append(ctx, "BRIDGE: APK verification failed (local=$digest expected=$sha256) -- refusing to install")
                    file.delete()
                    return@run
                }
            }
            DebugFileLog.append(
                ctx,
                "BRIDGE: new version v$version ready (${file.length() / 1024 / 1024}MB, sha256 OK" +
                    (deltaNote?.let { ", $it" } ?: "") + ")",
            )
            promptInstallReady(ctx, file, version, deltaNote)
        }
    }

    /**
     * Delta path: download the patch -> assemble using this process's sourceDir as the base -> strictly verify the output sha256.
     * Returns null = any failure (the caller falls back to the full package).
     */
    private fun tryDelta(
        ctx: Context,
        deltaUrl: String,
        deltaSha256: String,
        baseSha256: String?,
        version: String,
    ): File? {
        if (baseSha256.isNullOrBlank()) return null
        val patch =
            download(ctx, deltaUrl) ?: run {
                DebugFileLog.append(ctx, "BRIDGE: delta package download failed")
                return null
            }
        val patchDigest = sha256Of(patch)
        if (!patchDigest.equals(deltaSha256, ignoreCase = true)) {
            DebugFileLog.append(ctx, "BRIDGE: delta package verification failed (local=$patchDigest expected=$deltaSha256)")
            patch.delete()
            return null
        }
        val base = File(ctx.applicationInfo.sourceDir)
        val out = File(ctx.cacheDir, "bridge-update-delta.apk")
        val error = DeltaPatch.apply(patch, base, out)
        if (error != null) {
            DebugFileLog.append(ctx, "BRIDGE: delta apply failed ($error) -- v$version")
            out.delete()
            patch.delete()
            return null
        }
        val patchKB = patch.length() / 1024
        patch.delete()
        DebugFileLog.append(
            ctx,
            "BRIDGE: delta apply succeeded (patch ${patchKB}KB + base -> ${out.length() / 1024 / 1024}MB) -- v$version",
        )
        return out
    }

    /**
     * Install confirmation once the new version is ready: a **proper dialog** (DarkMessageDialog), not an overlay,
     * since overlays are easily ignored and the tap target is small (user feedback). When no Activity of ours is in the foreground,
     * the settings page is launched with arguments and shows it in onCreate/onNewIntent.
     */
    private fun promptInstallReady(
        ctx: Context,
        file: File,
        version: String,
        deltaNote: String? = null,
    ) {
        main.post {
            val activity = BridgeSetupActivity.resumed()
            if (activity != null) {
                activity.promptInstall(file, version, deltaNote)
            } else {
                runCatching {
                    ctx.startActivity(
                        Intent()
                            .setClassName(ctx.packageName, SETUP_ACTIVITY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("prompt", "install")
                            .putExtra("apkPath", file.absolutePath)
                            .putExtra("version", version)
                            .putExtra("deltaNote", deltaNote),
                    )
                }
            }
        }
    }

    fun installNow(
        ctx: Context,
        file: File,
    ) {
        BridgePrefs.setResumePending(ctx, true)
        // First stop the session and release all virtual displays (including those held by UserService): otherwise the system install
        // confirmation dialog lands on the car-side virtual display and cannot be confirmed on the phone
        runCatching { SessionController.disconnect(ctx) }
        Bg.run("bridge-install") {
            try {
                val installer = ctx.packageManager.packageInstaller
                // Leftover sessions (earlier failed attempts stuck in PENDING) block later installs and their late
                // status broadcasts cross-talk; clean up our own old sessions before every submission
                abandonStaleSessions(ctx)
                // Must createSession first to get the session id, then openSession; calling openSession(0) directly
                // throws SecurityException "Caller has no access to session 0"
                // (2026-10-21 real device: tapping the overlay did nothing, the install was never started)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                currentInstallSessionId = sessionId
                val session = installer.openSession(sessionId)
                session.openWrite("apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                // Implicit action broadcast on purpose: InstallReceiver is inside this package (exported=false)
                val intent =
                    Intent("io.github.ezn24.cardeck.neo.BRIDGE_INSTALL_RESULT")
                        .setPackage(ctx.packageName)
                val sender =
                    PendingIntent.getBroadcast(
                        ctx,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(sender.intentSender)
                session.close()
                DebugFileLog.append(ctx, "BRIDGE: install started (waiting for system confirmation)")
            } catch (error: Exception) {
                DebugFileLog.append(ctx, "BRIDGE: install start failed - ${error.message}")
            }
        }
    }

    private fun download(
        ctx: Context,
        url: String,
    ): File? {
        return try {
            val target = URL(url)
            // Prefer downloading over the cellular network: when a new version is pushed mid-session the process default network is on
            // the car hotspot
            // (no internet), and the HttpURLConnection default route is bound to fail
            val connection =
                (
                    BridgeClient
                        .internetNetwork()
                        ?.let { net -> runCatching { net.openConnection(target) }.getOrNull() }
                        ?: target.openConnection()
                ) as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            val file = File(ctx.cacheDir, "bridge-update.apk")
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (error: Exception) {
            DebugFileLog.append(ctx, "BRIDGE: download error - ${error.message}")
            null
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
