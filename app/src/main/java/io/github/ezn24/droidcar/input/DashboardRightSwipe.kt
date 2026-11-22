package io.github.ezn24.droidcar.input

/** The dashboard sits to the left of the app pages; only a leftward swipe advances to the app pages. */
internal fun dashboardLeftSwipe(
    dx: Float,
    dy: Float,
    threshold: Float,
): Boolean = dx <= -threshold && -dx > kotlin.math.abs(dy) * 1.2f

/**
 * Dashboard gesture (reworked 2026-11-17):
 *
 * 1. **Finger-following page flip**: a leftward drag past the slop is claimed as a page flip, then each MOVE's
 * displacement goes to the pager's
 *    fakeDrag, so the page and the GL map layer follow the finger and the pager decides on release which page to
 *    settle on by position/velocity
 *    (before, "drag far enough to trigger a fixed animation" had no drag progress).
 * 2. **Anti mis-touch**: on press **do not inject** at first (the media card / embedded music app never receives
 * down) and wait for the gesture to be classified:
 *    lift with little movement = tap, replay down+up; vertical or rightward movement = hand back to the app (replay
 *    down, then inject as usual);
 *    leftward drag = page flip, and the app receives no event at all. Previously down was injected immediately, so
 *    during a flip the music card had already been pressed,
 *    and the pointerUp replayed after claiming was often taken as a click that summoned the music app.
 *
 * A HID lift may report 0,0: the lift coordinates during a drag are never used.
 */
internal class DashboardSwipeGesture {
    enum class Result {
        /** Not handled by this gesture, inject as usual. */
        NONE,

        /** Swallowed (surplus events while classifying / dragging). */
        CONSUME,

        /** Claimed as a page flip: start fakeDrag and drag the first segment by [dragDelta]. */
        BEGIN_DRAG,

        /** Dragging: keep dragging by [dragDelta] (relative to the previous event). */
        DRAG,

        /** Release: end fakeDrag and let the pager settle. */
        END_DRAG,

        /** Tap: the caller replays down + up at [startX]/[startY]. */
        TAP,

        /** Handed back to the app: the caller replays down at [startX]/[startY], then injects the current event as usual. */
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

    /** Horizontal displacement of [Result.BEGIN_DRAG]/[Result.DRAG] (stream pixels, negative = leftward). */
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
