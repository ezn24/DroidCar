package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context

/**
 * 「显示」面板偏好：视频流/虚拟屏的分辨率缩放与目标帧率。
 *
 * - 分辨率缩放（30%–160%）：按车机通告分辨率的百分比生成流画布与虚拟屏，
 *   密度同步缩放（逻辑布局不变，只改像素量）。**仅下次连接生效**——
 *   编码器与全部虚拟屏按会话建立时的尺寸创建，运行中改无意义。
 * - 帧率（30/60）：合成器出帧节奏上限，运行中可实时切换。
 *
 * 「实际生效值」由 SessionController 在会话建立时记录（activeScalePercent /
 * activeTargetFps），英雄卡显示运行时值而非偏好值。
 */
object DisplayPrefs {
    const val SCALE_MIN = 30
    const val SCALE_MAX = 160
    const val SCALE_STEP = 5
    const val SCALE_DEFAULT = 100

    const val FPS_DEFAULT = 30

    /**
     * 车机通知只显示这些 App（包名）；空集合 = 全部 App。与桌面显示哪些 App 无关，是独立名单。
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

    /** 连接时推送车机侧声音（连线页开关；取代每次连接前的弹窗）。 */
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

    /** 车机主动断开后是否继续等待它回连（默认否：直接收起蓝牙/热点）。 */
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
            io.github.shaonialife.cardeck.notifications.NotificationBannerController
                .clear()
        }
    }

    /** 窄屏仪表盘版面偏好（[io.github.shaonialife.cardeck.render.DashLayout] 的 PREF_*；默认左右分屏；宽屏不看）。下次连接生效。 */
    fun dashboardLayout(context: Context): String =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("dashboardLayout", io.github.shaonialife.cardeck.render.DashLayout.PREF_DOCK_BOTTOM)
            ?: io.github.shaonialife.cardeck.render.DashLayout.PREF_DOCK_BOTTOM

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
     * 清晰优先（默认关）：编码改用 H.264 High profile（8×8 变换，同码率下文字边缘更锐）。
     * 部分车机解码器可能不认 High（奥迪 DELPHI 对 level 不符就黑屏），故默认关、下次连接生效；
     * 转接盒模式恒为 Baseline，不受此开关影响。
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
     * HEVC（H.265，实验，默认关）：接收端在 /info 声明 hevcInfo 且 SETUP 接受 "hevc" 时改用 H.265，
     * 同画质码率约省三到五成。只在 CarPlayStation 测试台验证过，真车（下一代 CarPlay）形态未验证；
     * 不支持的接收端不受影响。下次连接生效。
     */
    fun hevc(context: Context): Boolean =
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("hevc", false)

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

    /** 语音助理上车（实验）：默认关——要额外镜像手机主屏，且只在真机验证过的 ROM 上可靠。 */
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
        io.github.shaonialife.cardeck.assist.AssistOverlayController
            .onPrefChanged(context, enabled)
    }

    /** 部分 ROM 的播放捕获受媒体流音量影响，默认不全局静音。 */
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

    /** 触摸 move 注入节流默认值（ms）：0=默认关闭，交互滞后时在「显示」面板调高试验。 */
    const val MOVE_THROTTLE_DEFAULT = 0
    const val MOVE_THROTTLE_MAX = 48

    /** 连接时真息屏（物理面板断电）默认值。 */
    // 默认**关**（2026-10-24 用户要求）：连接成功就断面板对多数人太激进，
    // 想要的人在 设置→显示 手动开启（开关保留，只改默认值）
    const val SCREEN_OFF_DEFAULT = false

    /** HID 原始报文日志默认值：默认关（校准键位时临时打开，避免刷屏）。 */
    const val HID_RAW_LOG_DEFAULT = false

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MOVE_THROTTLE = "move_throttle_ms"
    private const val KEY_SCREEN_OFF = "screen_off_on_connect"
    private const val KEY_HID_RAW_LOG = "hid_raw_log"

    /**
     * HID 原始报文日志（键位校准用）：打开后每帧 `hidSendReport` 会写一行
     * `HID-RAW: uuid=… bytes=…` 到主日志，用来对照按下某个键时的字节。
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
        io.github.shaonialife.cardeck.input.HidNavigation.rawLogEnabled = enabled
    }

    /**
     * 连接时真息屏（默认开）：会话建立后把物理面板硬件断电（同 scrcpy `-S`），
     * 系统保持唤醒、虚拟屏照常渲染；关闭则只用暗幕。
     * 切换在会话中立即生效（见 SessionController.applyScreenOffPreference）。
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

    /** 流分辨率缩放百分比（30–160，步进 5；默认 100）。 */
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

    /** 目标帧率（30/60；驱动合成器出帧节奏，运行中切换实时生效）。 */
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

    /** 触摸 move 注入节流（ms；0=关闭）。 */
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
