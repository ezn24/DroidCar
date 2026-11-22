package io.github.ezn24.droidcar.launcher

internal fun dashboardPageFraction(
    position: Int,
    offset: Float,
): Float = (position + offset).coerceIn(0f, 1f)

/** While a finger drives the pager, MOVE/UP must keep being injected; only automatic animation suppresses static coordinates. */
internal fun suppressTouchDuringLayout(
    pageAnimating: Boolean,
    userDragging: Boolean,
    composerAnimating: Boolean,
): Boolean = !userDragging && (pageAnimating || composerAnimating)
