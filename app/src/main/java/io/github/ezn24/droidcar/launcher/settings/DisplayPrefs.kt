package io.github.ezn24.droidcar.launcher.settings

import android.content.Context

/**
 * "Display" panel preferences: resolution scale and target frame rate of the video stream / virtual displays.
 *
 * - Resolution scale (30%-160%): the stream canvas and virtual displays are generated as a percentage of the head unit's advertised
 * resolution,
 *   with density scaled in step (logical layout unchanged, only the pixel count changes). **Takes effect on the next connection only** -
 *   the encoder and all virtual displays are created with the size at session setup, so changing it at runtime is meaningless.
 * - Frame rate (30/60): upper bound of the compositor's output cadence, switchable live at runtime.
 *
 * The "effective values" are recorded by SessionController at session setup (activeScalePercent /
 * activeTargetFps); the hero card shows runtime values rather than preference values.
 */
object DisplayPrefs {
    const val SCALE_MIN = 30
    const val SCALE_MAX = 160
    const val SCALE_STEP = 5
    const val SCALE_DEFAULT = 100

    /** Upper limit; the session uses min(this, head unit /info maxFPS), so 60 = follow the head unit. */
    const val FPS_DEFAULT = 60

    /**
     * Head-unit notifications show only these apps (package names); empty set = all apps. Independent of which apps the desktop shows - a
     * separate list.
     */
    fun notificationApps(context: Context): Set<String> =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getStringSet("notificationApps", emptySet())
            .orEmpty()
            .toSet()

    fun setNotificationApps(
        context: Context,
        packages: Set<String>,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("notificationApps", packages)
            .apply()
    }

    /** Push head-unit-side audio on connect (switch on the connection page; replaces the per-connection dialog). */

    /** Press play when a car session's audio comes up (off by default). */
    fun autoPlayOnConnect(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("autoPlayOnConnect", false)

    fun setAutoPlayOnConnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("autoPlayOnConnect", enabled)
            .apply()
    }

    fun carAudio(context: Context): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("carAudio", true)

    fun setCarAudio(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("carAudio", enabled)
            .apply()
    }

    /**
     * Whether to keep waiting for the head unit to reconnect after it disconnects on its own (default no: shut down Bluetooth / hotspot
     * directly).
     */
    fun waitForReconnect(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("waitForReconnect", false)

    fun setWaitForReconnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("waitForReconnect", enabled)
            .apply()
    }

    fun showCarNotifications(context: Context): Boolean =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("showCarNotifications", false)

    fun setShowCarNotifications(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("showCarNotifications", enabled)
            .apply()
        if (!enabled) {
            io.github.ezn24.droidcar.notifications.NotificationBannerController
                .clear()
        }
    }

    /**
     * Narrow-screen dashboard layout preference (PREF_* of [io.github.ezn24.droidcar.render.DashLayout]; default side-by-side; ignored
     * on wide screens). Takes effect on the next connection.
     */
    fun dashboardLayout(context: Context): String =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("dashboardLayout", io.github.ezn24.droidcar.render.DashLayout.PREF_DOCK_BOTTOM)
            ?: io.github.ezn24.droidcar.render.DashLayout.PREF_DOCK_BOTTOM

    fun setDashboardLayout(
        context: Context,
        value: String,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("dashboardLayout", value)
            .apply()
    }

    /**
     * Clarity first (default off): encode with H.264 High profile (8x8 transform, sharper text edges at the same bitrate).
     * Some head-unit decoders may not accept High (Audi DELPHI shows a black screen on a level mismatch), so it defaults off and takes
     * effect on the next connection;
     * adapter-box mode is always Baseline and unaffected by this switch.
     */
    fun clarityFirst(context: Context): Boolean =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("clarityFirst", false)

    fun setClarityFirst(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("clarityFirst", enabled)
            .apply()
    }

    /**
     * HEVC (H.265, experimental, default off): use H.265 when the receiver declares hevcInfo in /info and SETUP accepts "hevc";
     * saves roughly 30-50% bitrate at the same quality. Verified only on the CarPlayStation test bench; real-car (next-generation CarPlay)
     * behavior is unverified;
     * receivers that do not support it are unaffected. Takes effect on the next connection.
     */
    fun hevc(context: Context): Boolean =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            // On by default (2026-11-26): HEVC is used only when the head unit declares it (/info hevcInfo + SETUP
            // enabledFeatures), so the setting now means "allow"; turning it off forces H.264
            .getBoolean("hevc", true)

    fun setHevc(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("hevc", enabled)
            .apply()
    }

    /**
     * Voice assistant in the car (experimental): default off - it needs an extra mirror of the phone main screen and is reliable only on
     * ROMs verified on real devices.
     */
    fun showAssistOverlay(context: Context): Boolean =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("showAssistOverlay", false)

    fun setShowAssistOverlay(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("showAssistOverlay", enabled)
            .apply()
        io.github.ezn24.droidcar.assist.AssistOverlayController
            .onPrefChanged(context, enabled)
    }

    /** On some ROMs playback capture is affected by the media stream volume, so global muting is off by default. */
    fun mutePhoneAudio(context: Context): Boolean =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("mutePhoneAudio", false)

    fun setMutePhoneAudio(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("mutePhoneAudio", enabled)
            .apply()
    }

    /**
     * Default touch move-injection throttle (ms): 0 = off by default; raise it in the "Display" panel experimentally if interaction lags.
     */
    const val MOVE_THROTTLE_DEFAULT = 0
    const val MOVE_THROTTLE_MAX = 48

    /** Default for real screen-off on connect (physical panel powered down). */
    // Default **off** (user request 2026-10-24): cutting the panel as soon as it connects is too aggressive for most people;
    // those who want it turn it on manually in Settings -> Display (the switch stays, only the default changed)
    const val SCREEN_OFF_DEFAULT = false

    /** Default for the raw HID report log: off (turn on temporarily while calibrating key mappings, to avoid log spam). */
    const val HID_RAW_LOG_DEFAULT = false

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MOVE_THROTTLE = "move_throttle_ms"
    private const val KEY_SCREEN_OFF = "screen_off_on_connect"
    private const val KEY_HID_RAW_LOG = "hid_raw_log"

    /**
     * Raw HID report log (for key-mapping calibration): when on, every `hidSendReport` frame writes a line
     * `HID-RAW: uuid=... bytes=...` to the main log, to compare against the bytes when a given key is pressed.
     */
    fun hidRawLog(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HID_RAW_LOG, HID_RAW_LOG_DEFAULT)

    fun setHidRawLog(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HID_RAW_LOG, enabled)
            .apply()
        io.github.ezn24.droidcar.input.HidNavigation.rawLogEnabled = enabled
    }

    /**
     * Real screen-off on connect: after the session is set up, power the physical panel down in hardware (same as scrcpy `-S`);
     * the system stays awake and virtual displays keep rendering; when off, only the dim curtain is used.
     * Toggling takes effect immediately during a session (see SessionController.applyScreenOffPreference).
     */
    fun screenOffOnConnect(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_OFF, SCREEN_OFF_DEFAULT)

    fun setScreenOffOnConnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREEN_OFF, enabled)
            .apply()
    }

    /** Stream resolution scale percent (30-160, step 5; default 100). */
    fun scalePercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCALE, SCALE_DEFAULT)
            .coerceIn(SCALE_MIN, SCALE_MAX)

    fun setScalePercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCALE, percent.coerceIn(SCALE_MIN, SCALE_MAX))
            .apply()
    }

    /** Target frame rate (30/60; drives the compositor's output cadence, switching at runtime takes effect live). */
    fun fps(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, FPS_DEFAULT)

    fun setFps(
        context: Context,
        fps: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FPS, if (fps >= 60) 60 else 30)
            .apply()
    }

    /** Touch move-injection throttle (ms; 0 = off). */
    fun moveThrottleMs(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MOVE_THROTTLE, MOVE_THROTTLE_DEFAULT)
            .coerceIn(0, MOVE_THROTTLE_MAX)

    fun setMoveThrottleMs(
        context: Context,
        ms: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MOVE_THROTTLE, ms.coerceIn(0, MOVE_THROTTLE_MAX))
            .apply()
    }
}
