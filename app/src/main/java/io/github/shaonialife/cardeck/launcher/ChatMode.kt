package io.github.shaonialife.cardeck.launcher

import android.content.Context
import io.github.shaonialife.cardeck.shizuku.Shell

/**
 * 聊天模式（2026-11-17）：聊天 App 开到车机上时，把它所在那块屏的密度调低到「最窄边 ≈ 610dp」，
 * 让 App 判定自己在平板上、改用双栏版面（左列表右对话）。离开聊天 App 时还原。
 *
 * 为什么有效：车机应用屏 1752×720@267dpi 换算后是 1050×431dp，最窄边 431dp < 600dp，
 * App 一律用拉宽的手机版；WhatsApp 实测 190dpi（606dp）即切成平板版。代价是字小约 28%。
 * 只改 CarDeck 自己的虚拟屏，手机屏不受影响；虚拟屏每次会话重建，残留不会跨会话。
 *
 * 所有方法都会跑 shell，必须在后台线程调用。
 */
object ChatMode {
    /**
     * 有平板 / 宽屏版面、且扛得住「进程运行中改密度」的聊天 App。
     *
     * 刻意排除（2026-11-18 车机实测）：微信——自带密度适配，降密度后标题栏重复、比例错乱；
     * Discord（React Native）——密度变化后字形渲染成方块乱码。两者都是不支持运行中改密度，
     * 只能保持原密度（微信在其 App 内开「横屏模式」即可拉宽）。
     */
    val PACKAGES =
        setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "jp.naver.line.android",
            "com.facebook.orca",
            "com.instagram.android",
            "org.thoughtcrime.securesms",
            "com.kakao.talk",
            // 非聊天但同样有平板版面（2026-11-18 实测 188dpi：ChatGPT 侧栏 + 对话，YouTube 多列网格）
            "com.openai.chatgpt",
            "com.google.android.youtube",
        )

    /**
     * 量字用手机屏密度、画字用所在屏密度的 App（React Native）。车机屏密度只要与手机不同，
     * 文字就被裁掉半截（Discord 2026-11-18 实测：原密度、平板密度、冷启动都一样坏，
     * 密度与手机相同才正常）。对策 [matchPhone]：把屏的**逻辑尺寸**放大到「密度=手机密度」
     * 时仍是同样 dp 的大小，SurfaceFlinger 再缩回原缓冲——App 看到的 dp 版面不变（宽屏即
     * 平板双栏），量字与画字密度一致。触摸坐标随之乘 [scaleFor]。与聊天模式开关无关（这是渲染修复）。
     */
    val PHONE_DENSITY_PACKAGES = setOf("com.discord")

    /** 目标最窄边：略高于 600dp 的平板门槛，留一点余量。 */
    private const val TARGET_SMALLEST_DP = 610f

    @Volatile
    private var loweredDisplay = -1

    /** [matchPhone] 生效时该屏「逻辑 / 物理」像素倍率；未生效 = 1。 */
    @Volatile
    private var displayScale = 1f

    /** 触摸注入倍率：流坐标 → 该屏逻辑坐标。 */
    fun scaleFor(displayId: Int): Float = if (displayId >= 0 && displayId == loweredDisplay) displayScale else 1f

    fun enabled(context: Context): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("chatMode", true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("chatMode", enabled)
            .apply()
    }

    /** 某个 App 要开到 [displayId] 前调用：聊天 App → 降密度；其它 App → 还原这块屏。 */
    fun beforeLaunch(
        context: Context,
        displayId: Int,
        packageName: String,
        log: (String) -> Unit,
    ) {
        if (displayId < 0) return
        if (packageName in PHONE_DENSITY_PACKAGES) {
            matchPhone(displayId, packageName, log)
        } else if (enabled(context) && packageName in PACKAGES) {
            lower(displayId, packageName, log)
        } else if (loweredDisplay == displayId) {
            restore(log)
        }
    }

    /** 回桌面 / 仪表盘 / 设置关闭时调用：撤掉聊天密度。 */
    fun restore(log: (String) -> Unit) {
        val display = loweredDisplay
        if (display < 0) return
        loweredDisplay = -1
        val resized = displayScale != 1f
        displayScale = 1f
        if (resized) Shell.exec("wm size reset -d $display", timeoutMs = 3_000L)
        Shell.exec("wm density reset -d $display", timeoutMs = 3_000L)
        log("CHAT: 还原屏 $display 的密度")
    }

    /** 会话结束：虚拟屏随之释放，只清状态。 */
    fun forget() {
        loweredDisplay = -1
        displayScale = 1f
        physicalCache.clear()
        phoneDensityCache = 0
    }

    /**
     * 每块屏的物理尺寸/密度在会话内不变，查一次就缓存（每次 `wm` 都是一次 shell 往返，
     * 原先每开一次聊天 App 要先跑 2~3 条，直接拖慢 App 打开）。屏 ID 每次会话重建，[forget] 清空。
     */
    private val physicalCache = java.util.concurrent.ConcurrentHashMap<Int, Triple<Int, Int, Int>>()

    @Volatile
    private var phoneDensityCache = 0

    /** (宽, 高, 物理密度)，读不到返回 null。 */
    private fun physicalOf(displayId: Int): Triple<Int, Int, Int>? {
        physicalCache[displayId]?.let { return it }
        val size = Shell.exec("wm size -d $displayId", timeoutMs = 3_000L).output
        val dims = Regex("""Physical size: (\d+)x(\d+)""").find(size) ?: return null
        val density = Shell.exec("wm density -d $displayId", timeoutMs = 3_000L).output
        val physical =
            Regex("""Physical density: (\d+)""")
                .find(density)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        return Triple(dims.groupValues[1].toInt(), dims.groupValues[2].toInt(), physical)
            .also { physicalCache[displayId] = it }
    }

    /** 手机主屏当前生效的密度（用户改「显示大小」会变，但会话内基本不变，按会话缓存）。 */
    private fun phoneDensity(): Int? {
        if (phoneDensityCache > 0) return phoneDensityCache
        val out = Shell.exec("wm density", timeoutMs = 3_000L).output
        val value =
            (
                Regex("""Override density: (\d+)""").find(out)
                    ?: Regex("""Physical density: (\d+)""").find(out)
            )?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        phoneDensityCache = value
        return value
    }

    private fun matchPhone(
        displayId: Int,
        packageName: String,
        log: (String) -> Unit,
    ) {
        // 同一块屏已是手机密度（从一个 RN App 切到另一个）：什么都不用做
        if (loweredDisplay == displayId && displayScale > 1f) return
        if (loweredDisplay >= 0) restore(log)
        val phone = phoneDensity() ?: return
        val (width, height, physical) = physicalOf(displayId) ?: return
        val scale = phone.toFloat() / physical
        if (scale <= 1.01f) return
        // 偶数尺寸（与流尺寸约定一致）
        val logicalW = (width * scale).toInt() and 1.inv()
        val logicalH = (height * scale).toInt() and 1.inv()
        Shell.exec("wm size ${logicalW}x$logicalH -d $displayId", timeoutMs = 3_000L)
        Shell.exec("wm density $phone -d $displayId", timeoutMs = 3_000L)
        displayScale = logicalW.toFloat() / width
        loweredDisplay = displayId
        log(
            "CHAT: $packageName → 屏 $displayId 逻辑 ${width}x$height→${logicalW}x$logicalH 密度 $physical→$phone（与手机一致，触摸 ×${"%.2f".format(
                displayScale,
            )}）",
        )
    }

    private fun lower(
        displayId: Int,
        packageName: String,
        log: (String) -> Unit,
    ) {
        // 同一块屏已是平板密度（聊天 App 之间切换）：不再重设
        if (loweredDisplay == displayId && displayScale == 1f) return
        if (loweredDisplay >= 0) restore(log)
        val (width, height, physical) = physicalOf(displayId) ?: return
        val shortSide = minOf(width, height)
        val target = (shortSide * 160f / TARGET_SMALLEST_DP).toInt()
        // 本来就够宽（高分辨率车机）就不动
        if (target >= physical) return
        Shell.exec("wm density $target -d $displayId", timeoutMs = 3_000L)
        loweredDisplay = displayId
        log("CHAT: $packageName → 屏 $displayId 密度 $physical→$target（最窄边 ≈ ${(shortSide * 160 / target)}dp，平板版面）")
    }
}
