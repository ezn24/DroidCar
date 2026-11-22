package io.github.ezn24.droidcar.render

import android.graphics.Bitmap
import android.view.Surface
import io.github.ezn24.droidcar.nav.NavInfo

/**
 * Encoder input and content of the alternate (cluster) screen stream. Held here because the alt stream is negotiated
 * by the session before the launcher's composer exists, and either side can end first.
 *
 * The receiver chooses the content with event commands (ChairPlay AltScreen, 2026-11-23):
 *  - `showUI` url `maps:/car/instrumentcluster/map` = map only (ChairPlay's "Map" and "Map + card", the latter
 *    drawing its own card); `.../instructioncard` = the turn card alone; `maps:/car/instrumentcluster[?maneuverLayout=
 *    leftAligned|rightAligned|topAligned]` = map with the turn card at that side;
 *  - `updateViewArea` viewAreaIndex = which of the display's /info viewAreas the content occupies.
 */
object AltScreenOutput {
    class Target(
        val surface: Surface,
        val width: Int,
        val height: Int,
        val fps: Int,
    )

    enum class Mode { MAP, CARD, CLUSTER }

    @Volatile
    var target: Target? = null
        private set

    /** Wakes the composer when something changes (set by the composer while it runs). */
    @Volatile
    internal var wake: (() -> Unit)? = null

    @Volatile
    var mode: Mode = Mode.MAP
        private set

    @Volatile
    private var layout: String = "rightAligned"

    /** View areas of the alt display from /info (x, y, w, h; top-left origin). */
    @Volatile
    private var viewAreas: List<IntArray> = emptyList()

    /** Current content area (x, y, w, h); null = the whole display. */
    @Volatile
    var viewArea: IntArray? = null
        private set

    /** Turn card bitmap and where it goes (x, y, w, h); null = no card. */
    @Volatile
    var card: Bitmap? = null
        private set

    @Volatile
    var cardRect: IntArray? = null
        private set

    @Volatile
    private var navInfo: NavInfo? = null

    @Volatile
    var log: (String) -> Unit = {}

    fun set(value: Target?) {
        target = value
        if (value == null) {
            card = null
            cardRect = null
            mode = Mode.MAP
            viewArea = null
        }
        rebuildCard()
        wake?.invoke()
    }

    fun setViewAreas(
        areas: List<IntArray>,
        initial: Int,
    ) {
        viewAreas = areas
        viewArea = areas.getOrNull(initial)
        rebuildCard()
    }

    /** `showUI` for the alt display. */
    fun showUi(url: String) {
        mode =
            when {
                url.contains("/instructioncard") -> Mode.CARD
                url.contains("/map") -> Mode.MAP
                else -> Mode.CLUSTER
            }
        layout = Regex("maneuverLayout=(\\w+)").find(url)?.groupValues?.get(1) ?: "rightAligned"
        log("ALT: showUI $mode layout=$layout")
        rebuildCard()
        wake?.invoke()
    }

    /** `updateViewArea` for the alt display. */
    fun updateViewArea(index: Int) {
        viewArea = viewAreas.getOrNull(index) ?: viewArea
        log("ALT: view area #$index -> ${viewArea?.joinToString("x")}")
        rebuildCard()
        wake?.invoke()
    }

    /** Navigation reading changed (null = no route). */
    fun onNav(info: NavInfo?) {
        navInfo = info
        if (mode != Mode.MAP) {
            rebuildCard()
            wake?.invoke()
        }
    }

    @Synchronized
    private fun rebuildCard() {
        val t = target
        val info = navInfo
        if (t == null || info == null || mode == Mode.MAP) {
            card = null
            cardRect = null
            return
        }
        val area = viewArea ?: intArrayOf(0, 0, t.width, t.height)
        val (x, y, w, h) = area.toList()
        val rect =
            when (mode) {
                Mode.CARD -> intArrayOf(x, y, w, h)
                else ->
                    when (layout) {
                        "topAligned" -> {
                            val cw = (w * 0.6f).toInt()
                            intArrayOf(x + (w - cw) / 2, y, cw, (h * 0.34f).toInt())
                        }
                        "leftAligned" -> intArrayOf(x, y, minOf((w * 0.42f).toInt(), (h * 1.1f).toInt()), h)
                        else -> {
                            val cw = minOf((w * 0.42f).toInt(), (h * 1.1f).toInt())
                            intArrayOf(x + w - cw, y, cw, h)
                        }
                    }
            }
        card = AltScreenCard.render(info, rect[2], rect[3])
        cardRect = rect
    }
}
