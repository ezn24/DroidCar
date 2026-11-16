package io.github.shaonialife.cardeck.input

import android.content.Context
import io.github.shaonialife.cardeck.input.TouchReports.Contact
import io.github.shaonialife.cardeck.launcher.CardGesture
import io.github.shaonialife.cardeck.launcher.LetterboxHintController
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.resolveCardGesture
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.util.Bg

/**
 * 触摸路由：把车机 hidSendReport 触点注入到车机侧虚拟屏（隐形屏模式）。
 *
 * 按主画面 x 坐标分流：x < dock 宽 → dock 屏；其余 → 应用屏（x 平移 dock 宽）。
 * 注入经 Shizuku UserService（1:1 真实坐标与毫秒级注入）。
 *
 * （原「桌面」（Presentation 直投）与「镜像」（无障碍 continueStroke）两种模式
 *   已按需移除，2026-10-10。）
 */
object TouchRouter {
    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - diagAt < 1000L) return
        diagAt = now
        logSink?.invoke("TOUCH: $message")
    }

    private var pressed = false
    private var pressedDisplay = -1
    private var failStreak = 0

    /** 叠加卡片手势状态（卡片命中后独占触摸，不再注入应用）。 */
    private var overlayPressed = false

    /** 分屏把手粘性路由状态（命中把手条后整条触摸流固定路由应用屏）。 */
    private var splitHandlePressed = false

    /** 本次按下用于关闭语音助理浮层（点 dock），直到抬起前的事件都吞掉。 */
    private var assistDockSwallow = false

    private fun inDockArea(
        px: Int,
        py: Int,
    ): Boolean = if (VirtualLauncher.dockAtBottom) py >= VirtualLauncher.appHeightPx else px < VirtualLauncher.dockWidthPx

    private val dashboardSwipe = DashboardSwipeGesture()
    private val touchReleases = TouchReleaseTracker()

    /**
     * MOVE 注入节流：0 = 关闭；>0 时距上次注入不足该毫秒数的 move
     * 直接合并丢弃（down/up 边界事件与跨屏切换永不节流，最终位置由 up 携带）。
     * 症状：交互滞后但帧率正常（事件派发堆积）。设置「显示」面板可调，默认 24ms。
     */
    @Volatile
    var moveThrottleMs: Long = 24L

    @Volatile
    private var throttleInited = false

    private var lastInjectAt = 0L
    private var throttledCount = 0
    private var lastThrottleLogAt = 0L

    // 车机触摸坐标（通告视图坐标系，如 1160×540）→ 流坐标（缩放后 1450×674）的换算系数。
    // 分辨率缩放 ≠100% 时流比视图大，不换算会导致点击整体向右下漂移（dock 分流也错位）。
    @Volatile
    private var coordScaleX = 1f

    @Volatile
    private var coordScaleY = 1f

    /** 会话建立时设置：视图（车机通告 display）尺寸 → 流（编码/虚拟屏）尺寸。 */
    fun setCoordinateScale(
        viewW: Int,
        viewH: Int,
        streamW: Int,
        streamH: Int,
    ) {
        coordScaleX = if (viewW > 0) streamW.toFloat() / viewW else 1f
        coordScaleY = if (viewH > 0) streamH.toFloat() / viewH else 1f
    }

    fun onContact(
        context: Context,
        report: Contact,
    ) {
        val contact = touchReleases.accept(report) ?: return
        val px = (contact.xPixels * coordScaleX).toInt().coerceAtLeast(0)
        val py = (contact.yPixels * coordScaleY).toInt().coerceAtLeast(0)

        // 叠加卡片（「强制横屏未生效」提示）优先消费触摸：它画在应用区的黑边上，
        // 必须先于注入判定命中，否则触摸会穿透到下面的应用。
        run {
            val visible = LetterboxHintController.isVisible()
            val hit = visible && LetterboxHintController.contains(px.toFloat(), py.toFloat())
            when (resolveCardGesture(visible, overlayPressed, contact.down, hit, appGestureActive = pressed)) {
                CardGesture.NOT_HANDLED -> overlayPressed = false
                CardGesture.CONSUME -> {
                    overlayPressed = contact.down
                    return
                }
                CardGesture.TAP -> {
                    overlayPressed = false
                    LetterboxHintController.onTap()
                    return
                }
            }
        }

        // 语音助理浮层在车机上时，点 dock（浮层所在屏之外、它收不到的地方）= 关掉浮层；
        // 整段按下→抬起都吞掉，免得同时触发 dock 按钮。浮层屏内的点击照常注入，由 Gemini
        // 自己处理（点空白处收起）。
        if (assistDockSwallow ||
            (
                contact.down &&
                    !pressed &&
                    io.github.shaonialife.cardeck.assist.AssistOverlayController
                        .isOnCar() &&
                    inDockArea(px, py)
            )
        ) {
            if (!assistDockSwallow) {
                io.github.shaonialife.cardeck.assist.AssistOverlayController
                    .dismissFromCar()
            }
            assistDockSwallow = contact.down
            return
        }
        if (io.github.shaonialife.cardeck.notifications.NotificationBannerController.onContact(
                contact.down,
                px.toFloat(),
                py.toFloat(),
            )
        ) {
            return
        }
        if (io.github.shaonialife.cardeck.launcher.MediaReturnControl
                .onContact(contact.down, px.toFloat(), py.toFloat(), pressed)
        ) {
            return
        }
        val launcher = VirtualLauncher
        // 分屏地图卡可直接操作（2026-11-15 用户要求，不再点按转全屏）：按在地图卡里的手势
        // 交给地图，不能起仪表盘左滑——否则横向拖地图就翻到了应用页
        val splitMapPane = splitMapPane(launcher)
        val assistLayer =
            io.github.shaonialife.cardeck.assist.AssistOverlayController
                .isOnAssistLayer()
        val canStartSwipe =
            !assistLayer &&
                !launcher.assistCoversDashboard &&
                launcher.dashboardMode &&
                !launcher.mapFullscreen &&
                !launcher.isLayoutTransitioning() &&
                splitMapPane?.contains(px.toFloat(), py.toFloat()) != true &&
                (if (launcher.dockAtBottom) py < launcher.appHeightPx else px >= launcher.dockWidthPx)
        val gesture =
            dashboardSwipe.update(
                contact.slot,
                contact.down,
                px.toFloat(),
                py.toFloat(),
                canStartSwipe,
                maxOf(16f, minOf(launcher.streamWidthPx, launcher.streamHeightPx) * 0.025f),
            )
        when (gesture) {
            DashboardSwipeGesture.Result.NONE -> Unit
            DashboardSwipeGesture.Result.CONSUME -> return
            DashboardSwipeGesture.Result.BEGIN_DRAG -> {
                logSink?.invoke("LAUNCHER: 仪表盘左拖 → 跟手翻页")
                if (!launcher.beginDashboardDragOut()) {
                    // 没有 pager 可拖（桌面未就绪）：退回旧行为，直接切到应用页
                    Bg.run("dashboard-swipe") { launcher.showDesktop() }
                    return
                }
                launcher.dashboardDragBy(dashboardDragPixels(launcher, dashboardSwipe.dragDelta))
                return
            }
            DashboardSwipeGesture.Result.DRAG -> {
                launcher.dashboardDragBy(dashboardDragPixels(launcher, dashboardSwipe.dragDelta))
                return
            }
            DashboardSwipeGesture.Result.END_DRAG -> {
                launcher.endDashboardDrag()
                return
            }
            DashboardSwipeGesture.Result.TAP -> {
                // 定性为点按：在按下点补发完整的 down + up
                val sx = dashboardSwipe.startX.toInt()
                val sy = dashboardSwipe.startY.toInt()
                inject(context, contact.copy(down = true), sx, sy)
                inject(context, contact.copy(down = false), sx, sy)
                return
            }
            DashboardSwipeGesture.Result.RELEASE -> {
                // 竖滑/右滑交还给应用：先在按下点补发 down，再照常注入当前位置
                inject(context, contact.copy(down = true), dashboardSwipe.startX.toInt(), dashboardSwipe.startY.toInt())
            }
        }
        inject(context, contact, px, py)
    }

    /** 流像素位移 → 桌面（Grid）屏像素：横屏 dock 在左时桌面屏可能超采样。 */
    private fun dashboardDragPixels(
        launcher: VirtualLauncher,
        dx: Float,
    ): Float = if (launcher.dockAtBottom) dx else dx * launcher.gridScale().coerceAtLeast(1f)

    /** 坐标分流 + 注入（手势判定之后的部分）。 */
    private fun inject(
        context: Context,
        contact: Contact,
        px: Int,
        py: Int,
    ) {
        val launcher = VirtualLauncher
        val splitMapPane = splitMapPane(launcher)
        val assistLayer =
            io.github.shaonialife.cardeck.assist.AssistOverlayController
                .isOnAssistLayer()
        if (launcher.isLayoutTransitioning()) {
            if (!contact.down) {
                if (pressed) runCatching { CarInputClient.service?.pointerUp(pressedDisplay, 0, 0) }
                pressed = false
                pressedDisplay = -1
            }
            return
        }
        // 动画期间不把静态坐标注入正在移动的画面；首帧后可正常操作。
        if (launcher.isLayoutTransitioning()) return
        val dockW = launcher.dockWidthPx
        if (dockW <= 0 || launcher.appDisplayId < 0 || launcher.dockDisplayId < 0) return
        var svc = CarInputClient.service
        if (svc == null) {
            // 主动触发重绑（手机重启/Shizuku 重启后 binder 丢失）。
            // **必须在后台**：ensure 内部是 bindUserService 同步 binder 调用，而本条
            // 触摸来自 iAP2 事件线程；主线程更不行（Shizuku 回调也走主线程，会自锁）。
            Bg.run("touch-rebind") { CarInputClient.ensure(context) }
            svc = CarInputClient.service
        }
        if (svc == null) {
            failStreak++
            // 首次以及每 200 次报一条：真机上每秒几十次触摸，逐条刷会淹没日志
            if (failStreak == 1 || failStreak % 200 == 0) {
                diag(
                    "注入服务不可用（连续 $failStreak 次，后台重绑中；" +
                        "若持续失效请打开 Shizuku App 或在系统设置中允许其后台运行）",
                )
            }
            return
        }
        failStreak = 0
        // 主画面坐标 → 分流：
        //  横屏：dock 左侧竖条（x < dockW），app 在右（x 平移 dockW）
        //  竖屏：dock 底部横条（y > appHeight），app 在上
        //  地图屏（仪表盘/全屏，优先于应用区）：全屏=应用区全部；分屏=dock 右侧 tile 区域。
        //  地图屏与流 1:1 创建，坐标直接平移；其余走活动应用屏（含超采样倍率换算）。
        var inDock: Boolean
        var targetDisplay = -1
        var localX = 0
        var localY = 0
        var routedToMap = false
        // 分屏把手粘性路由：按下落在把手条内 → 整条触摸流固定路由应用屏（Grid 所在屏）
        // 直至抬起。快拖时把手落后手指、坐标会越出把手条（甚至越过地图 tile 边缘），
        // 按坐标逐帧改判会跨屏重注（pointerUp 应用屏 + pointerDown 地图屏），掐断拖拽
        // 并给地图误触一下（2026-10-21 方案评审定谳）。模式同上方 overlay 卡手势独占。
        val handlePin =
            splitHandlePressed ||
                (contact.down && !pressed && launcher.dashHandleHit(px.toFloat(), py.toFloat()))
        if (handlePin) {
            splitHandlePressed = contact.down
            inDock = false
            targetDisplay =
                if (launcher.gridDisplayId >= 0) launcher.gridDisplayId else launcher.activeAppDisplayId()
            if (launcher.dockAtBottom) {
                localX = px
                localY = py
            } else {
                val scale = launcher.gridScale().coerceAtLeast(1f)
                localX = ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        } else if (launcher.dockAtBottom) {
            val appH = launcher.appHeightPx
            inDock = py >= appH
            // 地图 tile（应用区上半；全屏=应用区全部）。触摸/流坐标顶部起算：
            // tile 矩形 py ∈ [CARD_MARGIN_PX, +mapTileHeight]，屏内 y = py-边距（顶边距扣除）
            if (!inDock &&
                !launcher.assistCoversDashboard &&
                launcher.mapDisplayId >= 0 &&
                launcher.mapFullscreen &&
                launcher.tileSlideFraction <= 0.01f &&
                px < launcher.appAreaWidth &&
                (
                    launcher.mapFullscreen ||
                        (py >= CARD_MARGIN_PX && py < CARD_MARGIN_PX + launcher.mapTileHeight)
                )
            ) {
                inDock = false
                targetDisplay = launcher.mapDisplayId
                localX = px
                localY = if (launcher.mapFullscreen) py else py - CARD_MARGIN_PX
                routedToMap = true
            }
            if (!routedToMap) {
                // 横屏底部 dock（Android Auto 式）也有超采样应用屏：走活动应用屏 + 倍率
                // （竖屏车机无超采样屏，activeAppScale 恒 1，行为与旧版一致）
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
                val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
                localX = if (inDock) px else (px * scale).toInt()
                localY = if (inDock) py - appH else (py * scale).toInt()
            }
        } else {
            inDock = px < dockW
            if (!inDock &&
                !launcher.assistCoversDashboard &&
                launcher.mapDisplayId >= 0 &&
                launcher.mapFullscreen &&
                launcher.tileSlideFraction <= 0.01f
            ) {
                val tileX = px - dockW
                val tileVisibleW = if (launcher.mapFullscreen) Int.MAX_VALUE else launcher.mapTileWidth
                if (tileX in 0 until tileVisibleW) {
                    inDock = false
                    targetDisplay = launcher.mapDisplayId
                    // 地图屏按布局动态 resize（分屏=tile 尺寸、全屏=应用区尺寸），
                    // 屏内容即坐标域：相对应用区左缘的偏移就是屏内坐标，直接平移
                    localX = tileX
                    localY = py
                    routedToMap = true
                }
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
                val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
                localX = if (inDock) px else ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        }
        // 分屏地图卡：按下落在卡内 → 整段手势注入地图屏（拖出卡外仍粘在地图屏，同媒体卡）。
        // 地图屏在分屏时 resize 成卡片尺寸（与 mapPaneBounds 1:1），坐标只需平移。
        if (splitMapPane != null &&
            (
                (!pressed && splitMapPane.contains(px.toFloat(), py.toFloat())) ||
                    (pressed && pressedDisplay == launcher.mapDisplayId)
            )
        ) {
            inDock = false
            targetDisplay = launcher.mapDisplayId
            localX = (px - splitMapPane.left).toInt().coerceIn(0, splitMapPane.width - 1)
            localY = (py - splitMapPane.top).toInt().coerceIn(0, splitMapPane.height - 1)
        }
        if (launcher.isMediaPaneVisible() && !launcher.assistCoversDashboard) {
            val pane = launcher.mediaAppBounds()
            if (pane.contains(px.toFloat(), py.toFloat()) ||
                (pressed && pressedDisplay == launcher.mediaDisplayId)
            ) {
                inDock = false
                targetDisplay = launcher.mediaDisplayId
                localX = (px - pane.left).toInt().coerceIn(0, pane.width - 1)
                localY = (py - pane.top).toInt().coerceIn(0, pane.height - 1)
            }
        }
        // 语音助理层在顶上：应用区的触摸全给它（点空白处 Gemini 自己收起），优先于卡片分流。
        // 助理屏与应用区同尺寸、1:1，坐标只需扣掉应用区原点；按下时已在助理屏的整段手势粘住。
        if (!inDock && (assistLayer || (pressed && pressedDisplay == launcher.assistDisplayId)) && launcher.assistDisplayId >= 0) {
            targetDisplay = launcher.assistDisplayId
            localX = (if (launcher.dockAtBottom) px else px - dockW).coerceIn(0, launcher.appAreaWidth - 1)
            localY = py.coerceIn(0, launcher.appAreaHeight - 1)
        }
        if (targetDisplay < 0) {
            diag("目标屏无效 dock=${launcher.dockDisplayId} app=${launcher.appDisplayId}")
            return
        }
        // MOVE 节流：同屏的连续 move 间隔不足时合并丢弃（状态机不动，最新位置
        // 由后续 move/up 携带）。down/up/跨屏切换不节流。
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (!throttleInited) {
            throttleInited = true
            moveThrottleMs =
                DisplayPrefs
                    .moveThrottleMs(context)
                    .toLong()
        }
        if (moveThrottleMs > 0 &&
            contact.down &&
            pressed &&
            pressedDisplay == targetDisplay &&
            nowMs - lastInjectAt < moveThrottleMs
        ) {
            throttledCount++
            if (nowMs - lastThrottleLogAt > 5_000) {
                logSink?.invoke(
                    "TOUCH: move 节流 ${moveThrottleMs}ms 生效——累计合并 $throttledCount 次",
                )
                throttledCount = 0
                lastThrottleLogAt = nowMs
            }
            return
        }
        try {
            val ok: Boolean =
                when {
                    contact.down && !pressed -> {
                        pressed = true
                        pressedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    }

                    contact.down && pressedDisplay != targetDisplay -> {
                        // 跨屏拖动：先在旧屏收笔，再在新屏按下
                        runCatching { svc.pointerUp(pressedDisplay, 0, 0) }
                        pressedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    }

                    contact.down -> svc.pointerMove(targetDisplay, localX, localY)

                    else -> {
                        pressed = false
                        svc.pointerUp(pressedDisplay, localX, localY)
                    }
                }
            if (ok) lastInjectAt = nowMs
            if (!ok) {
                diag(
                    "注入被拒 disp=$targetDisplay ($localX,$localY) " +
                        "down=${contact.down} pressed=$pressed pressedDisplay=$pressedDisplay",
                )
            } else if (contact.down) {
                diag("注入OK disp=$targetDisplay ($localX,$localY)")
            }
        } catch (e: Exception) {
            diag("注入异常：${e.message}")
        }
    }

    fun reset() {
        pressed = false
        pressedDisplay = -1
        overlayPressed = false
        splitHandlePressed = false
        dashboardSwipe.reset()
        touchReleases.reset()
        io.github.shaonialife.cardeck.launcher.MediaReturnControl
            .reset()
        coordScaleX = 1f
        coordScaleY = 1f
    }

    /**
     * 分屏形态下可直接操作的地图卡区域（流坐标）；不在分屏 / 卡片在滑动 / 助理浮层占用时为 null。
     */
    private fun splitMapPane(launcher: VirtualLauncher): io.github.shaonialife.cardeck.render.PaneBounds? =
        if (!launcher.assistCoversDashboard &&
            launcher.dashboardMode &&
            !launcher.mapFullscreen &&
            launcher.mapDisplayId >= 0 &&
            launcher.tileSlideFraction <= 0.01f
        ) {
            launcher.mapPaneBounds()
        } else {
            null
        }
}
