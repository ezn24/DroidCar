package io.github.ezn24.droidcar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.ezn24.droidcar.MainActivity
import io.github.ezn24.droidcar.R
import io.github.ezn24.droidcar.SessionController
import io.github.ezn24.droidcar.audio.SilentProjectionActivity
import io.github.ezn24.droidcar.util.Bg
import io.github.ezn24.droidcar.util.DebugFileLog

/**
 * Foreground keep-alive service: stays resident with the connectedDevice type while a connection is being established,
 * preventing the system / MagicOS power saving from killing the app after it goes to the background and dropping the connection.
 * The connection logic still runs in threads spawned from MainActivity; this service is responsible for keeping the process alive.
 */
class CarPlayService : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Keep-alive with screen off: the CPU wake lock keeps the encoder/protocol threads and TCP liveness probes responsive;
     * the WiFi low-latency lock stops the radio from entering aggressive power saving (otherwise the receiver declares the link
     * dead within 20s and tears it down).
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock =
            power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CarPlaySender:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock =
            wifi
                .createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "CarPlaySender:wifi",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val autoStopRunnable =
        Runnable {
            when {
                SessionController.isStreaming() -> fileLog("SVC: streaming started; cancelling the leave-car teardown")
                SessionController.isSessionRunning() -> {
                    fileLog(
                        "SVC: car Bluetooth disconnect not recovered within ${AUTO_STOP_GRACE_MILLIS / 1000}s; tearing the " +
                            "session down",
                    )
                    SessionController.disconnect(applicationContext)
                }
                else -> Unit
            }
        }

    private fun scheduleAutoStop() {
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.postDelayed(autoStopRunnable, AUTO_STOP_GRACE_MILLIS)
        fileLog(
            "SVC: car Bluetooth disconnected while connecting; tearing the session down only if not recovered within " +
                "${AUTO_STOP_GRACE_MILLIS / 1000}s",
        )
    }

    private fun cancelPendingAutoStop() = mainHandler.removeCallbacks(autoStopRunnable)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Leaving the car: car Bluetooth ACL disconnected (delivered via startService: while the service runs the process is
        // already in foreground service state,
        // not subject to background start limits). disconnect internally does stopService -> onDestroy.
        if (intent?.action == ACTION_AUTO_STOP_SESSION) {
            // Ignore while streaming: after the session hands over to WiFi the receiver dropping Bluetooth is normal (WinPlay
            // measured the ACL dropping
            // 3s after RECORD, root cause of "disconnects right after connecting", 2026-11-18). On a real exit the hotspot
            // disappears with it,
            // and WiFi onLost / streaming onEnded handle the teardown, so Bluetooth need not be used to decide.
            if (SessionController.isStreaming()) {
                fileLog(
                    "SVC: car Bluetooth disconnected but streaming (session already on WiFi); ignoring, WiFi disconnect handles " +
                        "teardown",
                )
                return START_NOT_STICKY
            }
            if (SessionController.isSessionRunning()) {
                // While connecting: our own RFCOMM attempts make the ACL connect and drop repeatedly, so tearing down immediately
                // would interrupt ourselves
                // (zz85/cardeck measured on the smartBox: connect-drop-teardown loop). Cancelled if the ACL reconnects within the
                // grace period
                // (AUTO_START) or streaming begins.
                scheduleAutoStop()
            } else {
                fileLog("SVC: received \"leave-car auto stop\" with no session; stopping the service")
                cancelPendingAutoStop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // The type must be specified explicitly as connectedDevice:
        // the 2-arg startForeground picks up all types declared in the manifest (including mediaProjection),
        // and Android 14 starting the mediaProjection type requires AppOps PROJECT_MEDIA (valid only for the user's current grant),
        // so after a restart / expired grant it crashes with SecurityException (pitfall measured 2026-10-09).
        // But **when a projection already exists** the mediaProjection type must stay: otherwise this startForeground
        // drops the type and Android 14 stops the projection with it (the token auto-connect just obtained is wasted).
        val fgsTypes =
            if (mediaProjection != null) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            fgsTypes,
        )
        acquireLocks()
        // When a grant result is present (the user just confirmed screen capture): upgrade the foreground type before creating the
        // projection (try/catch fallback, no crash)
        if (pendingProjectionResult != null) {
            upgradeToProjection()
            takeProjection()
        } else if (mediaProjection != null) {
            upgradeToProjection()
        }
        // Auto-connect when getting in the car: the process may have been cold-started by the ACL broadcast, so build the session
        // objects first (BT client/server,
        // WiFi connector and mDNS advertiser are all in ensureSessionObjects), then follow the same flow as the home page button.
        // Run on a background thread: on a cold start ensure reads prefs / binds the UserService, which must not sit on the service
        // main thread.
        if (intent?.action == ACTION_AUTO_START_SESSION) {
            cancelPendingAutoStop()
            if (SessionController.isSessionRunning()) {
                // The connection attempt itself triggers ACL_CONNECTED: if a session is already running, do not start again
                fileLog("SVC: received \"car-entry auto start\"; session already in progress, ignoring")
                return START_STICKY
            }
            val held = SessionController.autoConnectSuppressedForMillis()
            if (held > 0) {
                fileLog("SVC: received \"car-entry auto start\"; user disconnected by hand, ignoring for ${held / 1000}s more")
                return START_STICKY
            }
            fileLog("SVC: received \"car-entry auto start\"; starting session")
            val app = applicationContext
            Bg.run("auto-session-start") {
                SessionController.ensure(app)
                // Auto-connect has audio by default: silently acquire the projection token first; on failure fall back to video only
                // Audio always on; with Shizuku the system output is captured directly, so no projection token is taken up
                // front (the session falls back to one only if that capture fails)
                SessionController.audioEnabledForSession =
                    io.github.ezn24.droidcar.shizuku.CarInputClient.service != null ||
                    io.github.ezn24.droidcar.shizuku.ShizukuGate
                        .state(applicationContext) ==
                    io.github.ezn24.droidcar.shizuku.ShizukuGate.State.READY ||
                    acquireProjectionSilently()
                SessionController.startConnection(app)
            }
        }
        return START_STICKY
    }

    /**
     * Silently acquire MediaProjection during auto-connect (the grant source for AudioPlaybackCapture).
     *
     * Hard ordering constraint on Android 14: **first** have the grant result -> **then** upgrade the FGS type to
     * mediaProjection -> only then call `getMediaProjection`. This directly reuses
     * [pendingProjectionResult] + [upgradeToProjection] + [takeProjection],
     * the same path as the manual grant (the only difference is the token comes from [SilentProjectionActivity]).
     *
     * @return whether an instance was obtained; on false auto-connect pushes video only (no dialog, no retry)
     */
    private fun acquireProjectionSilently(): Boolean {
        val grant =
            SilentProjectionActivity.request(applicationContext)
                ?: run {
                    fileLog("SVC: silent projection token acquisition failed; this auto-connect pushes video only")
                    return false
                }
        pendingProjectionResult = grant.resultCode to grant.data
        upgradeToProjection()
        takeProjection()
        val ok = mediaProjection != null
        fileLog(
            if (ok) {
                "SVC: silent projection token ready (auto-connect with audio)"
            } else {
                "SVC: silent projection token invalid (getMediaProjection failed)"
            },
        )
        return ok
    }

    private fun takeProjection() {
        val (code, data) = pendingProjectionResult ?: return
        pendingProjectionResult = null
        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = manager.getMediaProjection(code, data)
            // Android 14+: a callback must be registered before createVirtualDisplay (enforced by resource management)
            projection?.registerCallback(
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        mediaProjection = null
                        // Key diagnostic: losing the grant makes audio/mirroring unavailable (previously written only to logcat,
                        // invisible under HKS encryption)
                        fileLog("SVC: MediaProjection stopped by the system (onStop); audio/mirroring needs re-authorization")
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            mediaProjection = projection
            if (projection != null) {
                fileLog("SVC: MediaProjection ready (projection created)")
            } else {
                fileLog("SVC: getMediaProjection returned null (grant result expired?)")
            }
        } catch (error: Exception) {
            fileLog("SVC: getMediaProjection failed - ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun upgradeToProjection() {
        try {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (error: Exception) {
            fileLog("SVC: upgrading the mediaProjection FGS type failed - ${error.message}")
        }
    }

    override fun onDestroy() {
        cancelPendingAutoStop()
        releaseLocks()
        // Android 14+: MediaProjection is bound to the foreground mediaProjection type:
        // when the keep-alive service stops (e.g. the user taps "Disconnect") the system stops the projection with it.
        fileLog(
            "SVC: onDestroy (keep-alive service stopped) mediaProjection=" +
                (if (mediaProjection != null) "still present (about to be reclaimed by the system)" else "null"),
        )
        super.onDestroy()
    }

    /** Key events such as the projection lifecycle: written to both logcat and file (under HKS the file is the only readable channel). */
    private fun fileLog(message: String) {
        android.util.Log.i("CarPlayService", message)
        DebugFileLog
            .append(this, message)
    }

    companion object {
        private const val CHANNEL_ID = "carplay_connection"
        private const val NOTIFICATION_ID = 1

        /** Auto-connect when getting in the car: delivered by CarBluetoothReceiver when the car Bluetooth ACL connects. */
        const val ACTION_AUTO_START_SESSION = "io.github.ezn24.droidcar.action.AUTO_START"

        /**
         * Auto-teardown when leaving the car: delivered on ACL disconnect (only sent when the service is running, see
         * CarBluetoothReceiver).
         */
        const val ACTION_AUTO_STOP_SESSION = "io.github.ezn24.droidcar.action.AUTO_STOP"

        /**
         * Teardown grace period after the car ACL drops while connecting (our own RFCOMM attempts make the ACL connect and drop
         * repeatedly).
         */
        private const val AUTO_STOP_GRACE_MILLIS = 60_000L

        /**
         * Mirroring mode: written by MainActivity after authorization; the virtual display can only be created once the service
         * re-declares the FGS type.
         */
        @Volatile
        var mediaProjection: android.media.projection.MediaProjection? = null

        /**
         * Grant result held over: the service calls getMediaProjection after the foreground type upgrade (Android 14+ ordering
         * requirement).
         */
        @Volatile
        var pendingProjectionResult: Pair<Int, Intent>? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPlayService::class.java))
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.fgs_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持与车机的连接"
                    setShowBadge(false)
                },
            )
        }
        // Tapping the notification opens the app main screen (setContentIntent was missing before, so taps did nothing)
        val contentIntent =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
