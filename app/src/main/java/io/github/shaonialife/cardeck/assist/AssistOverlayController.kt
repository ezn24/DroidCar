package io.github.shaonialife.cardeck.assist

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.util.Bg

/**
 * 语音助理上车（实验）：让 Gemini **原生浮层**（四色光效 + 「問 Gemini」条）显示在车机上。
 *
 * 2026-11-15 S24 Ultra 实测结论（决定了这套做法）：
 *  - Gemini 浮层是 Google 应用里的普通 Activity（`FloatyActivity`），开在**当前焦点屏**；
 *    「Hey Google」通常落在手机主屏，但它的 task 可以用 `am display move-stack` 搬到车机屏，
 *    搬过去后原生渲染、可直接触摸——比镜像裁剪好看得多（镜像方案已放弃）；
 *  - 车机屏上它会被仪表盘地图/媒体卡（合成器画在应用屏之上）盖住，需要暂时让出卡片；
 *  - 应用侧录音回调、甚至 shell 侧录音配置都看不到 Gemini 的 HOTWORD 录音，
 *    可靠信号是 Google 应用的 RECORD_AUDIO / RECORD_AUDIO_HOTWORD AppOp（shell 查询）。
 *
 * 流程：每 [MIC_WATCH_MS] 问一次 shell「助理开麦了吗」（一次 binder）→ 开麦后每
 * [POLL_INTERVAL_MS] 跑 `dumpsys window windows` 找浮层 → 在手机屏就搬到桌面所在车机屏 →
 * 在车机屏就让出卡片 → 浮层消失后恢复。所有 shell/binder 调用都在 [thread] 上。
 */
object AssistOverlayController {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var log: (String) -> Unit = {}

    /** 以下状态只在 [thread] 上读写。 */
    private var polling = false
    private var misses = 0
    private var micActive = false
    private var watchState = ""

    /** 浮层当前所在的车机屏（-1 = 不在车机上）。 */
    @Volatile
    private var carDisplayId = -1

    /** 浮层是否正显示在车机上（TouchRouter：点 dock 关浮层）。 */
    fun isOnCar(): Boolean = carDisplayId >= 0

    /** 浮层在助理透明屏上（TouchRouter：应用区触摸全交给它）。 */
    fun isOnAssistLayer(): Boolean = carDisplayId >= 0 && carDisplayId == VirtualLauncher.assistDisplayId

    /** 已经搬过的 task：它若又回到手机屏，说明用户在车机上把它收起了（见 [poll]）。 */
    private var movedTaskId = -1

    /** 浮层当前的 task（关闭用）。 */
    @Volatile
    private var currentTaskId = -1

    private val micWatch =
        object : Runnable {
            override fun run() {
                val svc = CarInputClient.service
                if (svc != null) {
                    val listening =
                        try {
                            svc.isAssistantListening()
                        } catch (t: Throwable) {
                            // 旧版 UserService 进程没有该方法（重装后首连可能复用旧进程）
                            if (watchState != "unavailable") log("ASSIST: shell 开麦查询不可用：$t")
                            watchState = "unavailable"
                            null
                        }
                    if (listening != null) {
                        if (watchState != "ok") log("ASSIST: shell 开麦查询已就绪（AppOps）")
                        watchState = "ok"
                        if (listening != micActive) {
                            micActive = listening
                            log("ASSIST: 助理开麦 active=$listening")
                        }
                        if (listening) beginPolling()
                    }
                }
                // 只在本会话的线程上续排（stop→start 后旧线程残留的一次执行不得串进新线程）
                val h = handler ?: return
                if (h.looper == android.os.Looper.myLooper()) h.postDelayed(this, MIC_WATCH_MS)
            }
        }

    private val pollTask =
        object : Runnable {
            override fun run() {
                if (!polling) return
                poll()
                if (polling) handler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    /** 会话 attach 时调用（VirtualLauncher）；偏好关闭时什么都不做。 */
    @Synchronized
    fun start(
        context: Context,
        logSink: (String) -> Unit,
    ) {
        stop()
        log = logSink
        val app = context.applicationContext
        if (!DisplayPrefs.showAssistOverlay(app)) return
        val t = HandlerThread("assist-overlay").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        log("ASSIST: 已启用（等待语音助理开麦）")
        h.post(micWatch)
    }

    /** 会话 detach 时调用：恢复卡片、停线程。 */
    @Synchronized
    fun stop() {
        val h = handler ?: return
        val t = thread
        handler = null
        thread = null
        h.post {
            polling = false
            leaveCarDisplay()
            t?.quitSafely()
        }
    }

    /** 设置面板切换：会话中立即生效。 */
    fun onPrefChanged(
        context: Context,
        enabled: Boolean,
    ) {
        if (enabled) {
            if (VirtualLauncher.appContext() != null && handler == null) start(context, log)
        } else {
            stop()
        }
    }

    /**
     * 车机上点了浮层够不着的地方（dock 屏）：结束浮层。返回 true = 已处理（调用方吞掉这次点击）。
     *
     * 不能发 BACK：Gemini 把 BACK 当「收起」，task 会跳回手机屏继续挂着（实测还带着
     * 从音乐里误识别的文字待发送）。直接移除 task 才是真正结束。
     */
    fun dismissFromCar(): Boolean {
        val display = carDisplayId
        val task = currentTaskId
        if (display < 0 || task < 0) return false
        Bg.run("assist-dismiss") { removeTask(task) }
        log("ASSIST: 车机点击 dock → 结束助理浮层 task=$task")
        return true
    }

    private fun removeTask(task: Int) {
        Shell.exec("am stack remove $task", timeoutMs = 3_000L)
    }

    private fun beginPolling() {
        if (polling) return
        polling = true
        misses = 0
        log("ASSIST: 开始核对助理窗口")
        handler?.post(pollTask)
    }

    private fun poll() {
        val result = Shell.exec("dumpsys window windows", timeoutMs = 2_000L)
        val window = if (result.ok) parseAssistWindow(result.output) else AssistWindow(false)
        if (window.visible) {
            misses = 0
            currentTaskId = window.taskId
            if (window.displayId == 0 && window.taskId >= 0 && window.taskId == movedTaskId) {
                // 搬上车机的浮层自己回到了手机屏：用户在车机上点空白处收起了它。
                // Gemini 收起后会在手机上挂着（可能带误识别的文字），直接结束。
                log("ASSIST: 助理浮层从车机屏回到手机 task=${window.taskId} → 视为在车机上关闭，结束它")
                removeTask(window.taskId)
                leaveCarDisplay()
                return
            }
            val target = targetDisplay()
            if (target >= 0 && window.displayId != target && moveTo(window.taskId, target)) return
            if (window.displayId != 0) showOnCarDisplay(window.displayId)
            return
        }
        misses++
        if (carDisplayId >= 0 && misses >= HIDE_AFTER_MISSES) leaveCarDisplay()
        if (carDisplayId < 0 && !micActive && misses >= HIDE_AFTER_MISSES) {
            polling = false
            log("ASSIST: 助理窗口不在，停止核对")
        } else if (carDisplayId < 0 && misses >= GIVE_UP_MISSES) {
            polling = false
            log("ASSIST: 持续开麦但无助理窗口（$misses 次），停止核对")
        }
    }

    /**
     * 浮层该待的车机屏（2026-11-15 用户两轮反馈后的定案）：
     *  - 首选**助理透明屏**（CarPlayAssist）：合成器把它当顶层半透明叠在整个应用区上——
     *    光效铺满全屏、对话条在底部，地图/媒体卡照常可见（「让出卡片」会让地图消失，
     *    「搬到地图屏」又只占地图卡，两者都被否决）；
     *  - 透明屏建不出来时退回：仪表盘/全屏地图 → 地图屏；其它 → 当前活动应用屏。
     */
    private fun targetDisplay(): Int {
        val assist = VirtualLauncher.ensureAssistDisplay()
        if (assist >= 0) return assist
        return if ((VirtualLauncher.dashboardMode || VirtualLauncher.mapFullscreen) && VirtualLauncher.mapDisplayId >= 0) {
            VirtualLauncher.mapDisplayId
        } else {
            VirtualLauncher.activeAppDisplayId()
        }
    }

    /** 已搬过的 (task, 目标屏)：同一组合只搬一次，失败或被系统拒绝也不反复重试。 */
    private var movedTarget = -1

    /** 把浮层 task 搬到 [target]；返回是否发起了搬移（本轮不再处理显示状态）。 */
    private fun moveTo(
        task: Int,
        target: Int,
    ): Boolean {
        if (task < 0 || (task == movedTaskId && target == movedTarget)) return false
        movedTaskId = task
        movedTarget = target
        val r = Shell.exec("am display move-stack $task $target", timeoutMs = 3_000L)
        log("ASSIST: 助理浮层 task=$task → 搬到车机屏 $target ok=${r.ok} ${r.output.take(80)}")
        return r.ok
    }

    /** 浮层在车机屏；若落在被仪表盘卡片盖住的屏上（搬移失败时的兜底），暂时让出卡片。 */
    private fun showOnCarDisplay(displayId: Int) {
        if (carDisplayId == displayId) return
        carDisplayId = displayId
        VirtualLauncher.setAssistLayerVisible(displayId == VirtualLauncher.assistDisplayId)
        val uncovered = VirtualLauncher.setAssistOnCarDisplay(displayId)
        log("ASSIST: 助理浮层在车机屏 display=$displayId → ${if (uncovered) "让出仪表盘卡片" else "非桌面所在屏，原样显示"}")
    }

    private fun leaveCarDisplay() {
        if (carDisplayId < 0) return
        log("ASSIST: 车机屏上的助理浮层已关闭 display=$carDisplayId → 恢复仪表盘卡片")
        carDisplayId = -1
        VirtualLauncher.setAssistLayerVisible(false)
        VirtualLauncher.setAssistOnCarDisplay(-1)
    }

    private const val MIC_WATCH_MS = 400L
    private const val POLL_INTERVAL_MS = 450L

    /** 连续多少次看不到窗口才算关闭（dumpsys 偶发漏块，单次不可信）。 */
    private const val HIDE_AFTER_MISSES = 2

    /** 一直开麦却始终没有助理窗口：放弃核对，等下一次开麦。 */
    private const val GIVE_UP_MISSES = 20
}
