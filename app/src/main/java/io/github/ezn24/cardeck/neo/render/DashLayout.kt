package io.github.ezn24.cardeck.neo.render

/**
 * Dashboard layout (2026-11-17). "Where the dock is" and "how map/media are split" used to be expressed by the single
 * `dockAtBottom` (portrait head unit = dock at bottom + top/bottom split); narrow screens need the two combined independently:
 *
 * | Layout        | dock   | split              | Use                                   |
 * |---------------|--------|----------------|------------------------------|
 * | SIDE_BY_SIDE  | left   | map left, media right | wide (1920x720, 1280x488)          |
 * | DOCK_BOTTOM   | bottom | map left, media right | narrow landscape, Android Auto style |
 * | PORTRAIT      | bottom | map top, media bottom | portrait head unit (preference ignored) |
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
        /** Legacy "auto" value: merged into the default (dock at bottom on narrow screens); treated as [PREF_DOCK_BOTTOM] when read. */
        const val PREF_AUTO = "auto"
        const val PREF_SIDE = "side"

        /**
         * Removed "music at bottom" layout (2026-11-17, the user settled on dock at bottom after trying it); treated as
         * the default when read.
         */
        const val PREF_MUSIC_BOTTOM = "music_bottom"
        const val PREF_DOCK_BOTTOM = "dock_bottom"

        /**
         * Narrow-screen criterion: only below this canvas aspect ratio is the preference consulted. 8:3 (1920x720 = 2.67, 1280x488 = 2.62)
         * is always side-by-side; 16:9 (1.78) and 800x480 (1.67) follow the layout the user picked in settings.
         */
        const val NARROW_ASPECT = 1.9f

        fun isNarrow(
            canvasWidth: Int,
            canvasHeight: Int,
        ): Boolean = canvasWidth.toFloat() / canvasHeight.coerceAtLeast(1) < NARROW_ASPECT

        /**
         * Resolves this session's layout from the preference and canvas (pure function, pinned by unit tests). The
         * preference **applies only to narrow landscape**
         * (2026-11-17 user request: wide screens keep the default); a portrait head unit is always [PORTRAIT];
         * narrow screens default to dock at bottom,
         * and only an explicit "side by side" choice splits left/right.
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
