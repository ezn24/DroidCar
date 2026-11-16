package io.github.shaonialife.cardeck.render

/**
 * 仪表盘版面（2026-11-17）。「dock 在哪」与「地图/媒体怎么分」原本由同一个
 * `dockAtBottom` 表达（竖屏车机 = dock 底部 + 上下分屏）；窄屏需要两者独立组合：
 *
 * | 版面          | dock   | 分屏           | 适用                         |
 * |---------------|--------|----------------|------------------------------|
 * | SIDE_BY_SIDE  | 左侧   | 左地图右媒体   | 宽屏（1920×720、1280×488）   |
 * | DOCK_BOTTOM   | 底部   | 左地图右媒体   | 窄横屏，Android Auto 式       |
 * | PORTRAIT      | 底部   | 上地图下媒体   | 竖屏车机（不受偏好影响）     |
 */
enum class DashLayout(
    val dockAtBottom: Boolean,
    val splitVertical: Boolean,
) {
    SIDE_BY_SIDE(dockAtBottom = false, splitVertical = false),
    DOCK_BOTTOM(dockAtBottom = true, splitVertical = false),
    PORTRAIT(dockAtBottom = true, splitVertical = true),
    ;

    companion object {
        /** 旧版「自动」值：已并入默认（窄屏 Dock 在底部），读到按 [PREF_DOCK_BOTTOM] 处理。 */
        const val PREF_AUTO = "auto"
        const val PREF_SIDE = "side"

        /** 已移除的「音乐在底部」版面（2026-11-17 用户试用后选定 Dock 在底部）；读到按默认处理。 */
        const val PREF_MUSIC_BOTTOM = "music_bottom"
        const val PREF_DOCK_BOTTOM = "dock_bottom"

        /**
         * 窄屏判据：画布宽高比低于它才看偏好。8:3（1920×720 = 2.67、1280×488 = 2.62）
         * 一律左右分屏；16:9（1.78）与 800×480（1.67）按用户在设置里选的版面。
         */
        const val NARROW_ASPECT = 1.9f

        fun isNarrow(
            canvasWidth: Int,
            canvasHeight: Int,
        ): Boolean = canvasWidth.toFloat() / canvasHeight.coerceAtLeast(1) < NARROW_ASPECT

        /**
         * 按偏好与画布解析本会话版面（纯函数，单测钉死）。偏好**只对窄横屏生效**
         * （2026-11-17 用户要求：宽屏保持默认）；竖屏车机恒为 [PORTRAIT]；窄屏默认 Dock 在底部，
         * 只有显式选「左右分屏」才左右分屏。
         */
        fun resolve(
            pref: String,
            canvasWidth: Int,
            canvasHeight: Int,
        ): DashLayout {
            if (canvasHeight > canvasWidth) return PORTRAIT
            if (!isNarrow(canvasWidth, canvasHeight)) return SIDE_BY_SIDE
            return when (pref) {
                PREF_SIDE -> SIDE_BY_SIDE
                else -> DOCK_BOTTOM
            }
        }
    }
}
