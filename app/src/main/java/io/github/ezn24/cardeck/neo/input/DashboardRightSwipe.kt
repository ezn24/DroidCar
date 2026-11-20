package io.github.ezn24.cardeck.neo.input

/** 仪表盘位于应用页左侧，向左滑才前进到应用页。 */
internal fun dashboardLeftSwipe(
    dx: Float,
    dy: Float,
    threshold: Float,
): Boolean = dx <= -threshold && -dx > kotlin.math.abs(dy) * 1.2f

/**
 * 仪表盘手势（2026-11-17 重做）：
 *
 * 1. **跟手翻页**：向左拖过 slop 即认领为翻页，之后每个 MOVE 的位移交给 pager 的
 *    fakeDrag——页面与 GL 地图层跟着手指走，松手由 pager 按位置/速度决定落到哪页
 *    （此前是「滑够距离就触发一段固定动画」，没有拖动进度）。
 * 2. **防误触**：按下先**不注入**（媒体卡/嵌入音乐 App 收不到 down），等手势定性——
 *    抬起且没怎么移动 = 点按，补发 down+up；竖向或向右移动 = 交还给应用（补发 down 后照常注入）；
 *    向左拖 = 翻页，应用全程收不到任何事件。此前 down 立即注入，翻页时音乐卡已被按下，
 *    认领后补的 pointerUp 常被当成一次点击把音乐 App 叫出来。
 *
 * HID 的抬手可能回传 0,0：拖动中的抬手坐标一律不用。
 */
internal class DashboardSwipeGesture {
    enum class Result {
        /** 不归本手势管，照常注入。 */
        NONE,

        /** 吞掉（定性中 / 拖动中的多余事件）。 */
        CONSUME,

        /** 认领为翻页：开始 fakeDrag，并按 [dragDelta] 拖第一段。 */
        BEGIN_DRAG,

        /** 拖动中：按 [dragDelta]（相对上一事件）继续拖。 */
        DRAG,

        /** 松手：结束 fakeDrag，由 pager 落定。 */
        END_DRAG,

        /** 点按：调用方在 [startX]/[startY] 补发 down + up。 */
        TAP,

        /** 交还给应用：调用方在 [startX]/[startY] 补发 down，再照常注入当前事件。 */
        RELEASE,
    }

    private enum class State { IDLE, PENDING, DRAGGING, RELEASED }

    private var state = State.IDLE
    private var slot = -1
    var startX = 0f
        private set
    var startY = 0f
        private set
    private var lastX = 0f

    /** [Result.BEGIN_DRAG]/[Result.DRAG] 的横向位移（流像素，向左为负）。 */
    var dragDelta = 0f
        private set

    fun tracks(contactSlot: Int): Boolean = state != State.IDLE && slot == contactSlot

    fun update(
        contactSlot: Int,
        down: Boolean,
        x: Float,
        y: Float,
        canStart: Boolean,
        slop: Float,
    ): Result {
        if (state == State.IDLE) {
            if (!down || !canStart) return Result.NONE
            state = State.PENDING
            slot = contactSlot
            startX = x
            startY = y
            lastX = x
            return Result.CONSUME
        }
        if (contactSlot != slot) return Result.NONE
        return when (state) {
            State.PENDING -> {
                if (!down) {
                    reset()
                    return Result.TAP
                }
                val dx = x - startX
                val dy = y - startY
                when {
                    dashboardLeftSwipe(dx, dy, slop) -> {
                        state = State.DRAGGING
                        dragDelta = dx
                        lastX = x
                        Result.BEGIN_DRAG
                    }
                    kotlin.math.hypot(dx, dy) > slop -> {
                        state = State.RELEASED
                        Result.RELEASE
                    }
                    else -> Result.CONSUME
                }
            }
            State.DRAGGING -> {
                if (!down) {
                    reset()
                    Result.END_DRAG
                } else {
                    dragDelta = x - lastX
                    lastX = x
                    Result.DRAG
                }
            }
            State.RELEASED -> {
                if (!down) reset()
                Result.NONE
            }
            State.IDLE -> Result.NONE
        }
    }

    fun reset() {
        state = State.IDLE
        slot = -1
        dragDelta = 0f
    }
}
