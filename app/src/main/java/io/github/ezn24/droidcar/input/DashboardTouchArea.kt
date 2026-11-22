package io.github.ezn24.droidcar.input

import io.github.ezn24.droidcar.render.PaneBounds

internal enum class DashboardTouchArea { MAP, MEDIA, GAP }

internal fun dashboardTouchArea(
    map: PaneBounds,
    media: PaneBounds,
    x: Float,
    y: Float,
): DashboardTouchArea =
    when {
        map.contains(x, y) -> DashboardTouchArea.MAP
        media.contains(x, y) -> DashboardTouchArea.MEDIA
        else -> DashboardTouchArea.GAP
    }
