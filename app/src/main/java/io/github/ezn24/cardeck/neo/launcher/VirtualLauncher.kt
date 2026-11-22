package io.github.ezn24.cardeck.neo.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.ezn24.cardeck.neo.launcher.settings.AppOrientationCompat
import io.github.ezn24.cardeck.neo.launcher.settings.DisplayPrefs
import io.github.ezn24.cardeck.neo.media.NotificationAccess
import io.github.ezn24.cardeck.neo.render.CARD_MARGIN_PX
import io.github.ezn24.cardeck.neo.render.DASH_GAP_PX
import io.github.ezn24.cardeck.neo.render.DashLayout
import io.github.ezn24.cardeck.neo.render.DashMetrics
import io.github.ezn24.cardeck.neo.render.DualDisplayComposer
import io.github.ezn24.cardeck.neo.render.OverlayCard
import io.github.ezn24.cardeck.neo.shizuku.CarInputClient
import io.github.ezn24.cardeck.neo.shizuku.Shell
import io.github.ezn24.cardeck.neo.shizuku.ShizukuGate
import io.github.ezn24.cardeck.neo.ui.ScreenDimmer
import io.github.ezn24.cardeck.neo.util.Bg

/**
 * Virtual-display launcher (dual-display + GL compositing architecture):
 *
 *   dock display (narrow strip, DockActivity resident) --+
 *                                                        +-- DualDisplayComposer --> encoder Surface
 *   app display (GridActivity / third-party apps) -------+
 *
 * - Apps launch onto the app display (shell am start --display <app display>); launching the same component again brings back the existing
 * task (resume).
 * - Back to desktop = bring GridActivity to the foreground; background app tasks are not killed.
 * - TouchRouter splits touches by the x coordinate of the main picture: x < dockWidth -> dock display, otherwise -> app display
 * (translated).
 */
object VirtualLauncher {
    private const val TAG = "VirtualLauncher"

    /**
     * Common apps that register a media session but are not music players (chat / recorders); they do not enter the dashboard media area.
     */
    private val NON_MUSIC_PACKAGES =
        setOf(
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "com.whatsapp",
            "com.tencent.mm",
            "jp.naver.line.android",
            "com.facebook.orca",
            "com.sec.android.app.voicenote",
            "com.google.android.apps.recorder",
        )

    /** Closing background apps: at most how many rounds of "remove -> re-check", how long to wait per round, and the re-check interval. */
    private const val CLOSE_ROUNDS = 3
    private const val CLOSE_VERIFY_MS = 1_500L
    private const val CLOSE_POLL_MS = 250L

    /**
     * Dock width (dp; usable app-display width = total width - the pixels converted from this value). Includes clock / battery since the
     * CarPlay-style redesign.
     * Density baseline 420dpi (<-> Audi design canvas 1160x540), sent by SessionController proportionally to the canvas short side
     * - on high-resolution head units the dock strip and its text/icons scale up together, taking a constant 23.3% of the canvas short side
     * (= design 126/540; 2026-10-17 fix for "dock looks small at high resolution").
     */
    const val DOCK_WIDTH_DP = 48

    /**
     * Letterbox aspect ratio for portrait-locked apps (width/height, written to the system via Shizuku at session setup).
     * With the default (about 1.78) the window is only ~303px = 188dp@240dpi; 1.05 widens it to about 514px.
     */
    private const val LETTERBOX_ASPECT_RATIO = 1.05f

    /** Target logical width (dp) of the letterbox window: ~ phone screen width, triggering the compact phone layout of e-commerce apps. */
    private const val LETTERBOX_TARGET_DP = 410f

    /**
     * Supersampling ratio of the landscape app display (the only lever to shrink the fixed-pixel footprint, see the attach comment).
     * At 2.0 the pixel cost is 4x (visibly laggy per user testing); at 1.5 it is 2.25x and the fixed top area is
     * 12% (originally 18%) - a quality/smoothness compromise (tuned on user feedback 2026-10-11).
     */
    private const val APP_SUPERSAMPLE = 1.5f

    /** Upper bound of the supersampled app-display height (px; limits GPU/memory cost). */
    private const val SUPERSAMPLE_MAX_HEIGHT = 1620

    // ---- Fixed dashboard split (old geometry helper interface kept for compatible coordinate calls) ----
    // Ratio = tile main-axis length over the "usable length" (app-area main axis - end margins - handle width).

    /** Map/media ratio at the 1920x720 baseline (3:1); other resolutions adapt via [dashSplitFractionFor]. */
    const val DASH_SPLIT_DEFAULT_FRACTION = 3f / 4f

    /**
     * Target media-card "aspect ratio" (landscape = media card width / card height). Taken from the actual shape of 3:1 at 1920x720
     * (424/688 ~ 0.62) - the media card keeps this shape and the map takes the remaining width. On a portrait head unit the media card is
     * below,
     * and the target is "card height / card width".
     */
    private const val MEDIA_CARD_ASPECT_LANDSCAPE = 0.62f
    private const val MEDIA_CARD_ASPECT_PORTRAIT = 0.45f

    /**
     * Clamp of the adaptive ratio: map at least half, at most 80% (an ultra-wide screen must not shrink the media card into a thin strip).
     */
    private const val ADAPTIVE_SPLIT_MIN = 0.55f
    private const val ADAPTIVE_SPLIT_MAX = 0.8f

    /**
     * Computes the map/media split ratio from the app-area size (pure function, pinned by unit tests): the media card keeps a fixed shape,
     * the map takes the rest. 1920x720 -> ~0.75 (same as the old 3:1); 16:9 (e.g. 1280x720) -> ~0.61;
     * ultra-wide capped at 0.8; narrow screens not below 0.55.
     *
     * @param mainLen split main-axis length (landscape = app-area width, portrait = app-area height)
     * @param crossLen the other axis length (landscape = app-area height, portrait = app-area width)
     */
    fun dashSplitFractionFor(
        mainLen: Int,
        crossLen: Int,
        portrait: Boolean,
    ): Float {
        val usable = (mainLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX).coerceAtLeast(1f)
        val cardCross = (crossLen - CARD_MARGIN_PX * 2).coerceAtLeast(1)
        val mediaLen = cardCross * if (portrait) MEDIA_CARD_ASPECT_PORTRAIT else MEDIA_CARD_ASPECT_LANDSCAPE
        return (1f - mediaLen / usable).coerceIn(ADAPTIVE_SPLIT_MIN, ADAPTIVE_SPLIT_MAX)
    }

    /**
     * Thickness factor of the landscape bottom dock relative to the side bar (side bar width ~ 23% of canvas short side, bottom bar takes
     * 72% of it ~ 17%).
     */
    private const val BOTTOM_DOCK_FACTOR = 0.72f

    /** Split ratio in effect this session (used by the compositor's default midline formula). */
    fun currentDashSplitFraction(): Float = dashSplitFraction

    /** Ratio clamp domain: each card keeps at least 20%, so it cannot be dragged into a line (buttons / corners all squeezed out). */
    const val DASH_SPLIT_MIN_FRACTION = 0.2f

    const val DASH_SPLIT_MAX_FRACTION = 0.8f

    private const val PREF_KEY_DASH_SPLIT = "dashSplitFraction"

    fun clampDashSplitFraction(fraction: Float): Float = fraction.coerceIn(DASH_SPLIT_MIN_FRACTION, DASH_SPLIT_MAX_FRACTION)

    /** Split geometry (app-area coordinates: landscape origin = dock right edge, portrait origin = stream top edge). */
    data class DashSplitGeom(
        val fraction: Float,
        /** Tile main-axis length (landscape = mapTileWidth, portrait = mapTileHeight). */
        val tileLen: Int,
        /** Handle-side edge of the tile (same semantics as the composer override). */
        val tileEdge: Float,
        /** Handle center. */
        val splitCenter: Float,
        /** Start of the content side of the opposite card (music card). */
        val otherStart: Float,
    )

    /**
     * Split geometry pure function (pinned by unit tests): usable length = main axis - margin x2 - handle width;
     * tileLen = ratio x usable; handle center = margin + tileLen + half handle (at 0.5 it sits exactly in the middle of
     * the app area, visually consistent with the old midline split); music card start = margin + tileLen + handle width.
     */
    fun dashSplitGeom(
        appMainLen: Int,
        fraction: Float,
    ): DashSplitGeom {
        val usable = (appMainLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()).coerceAtLeast(1)
        val clamped = clampDashSplitFraction(fraction)
        val tileLen = (clamped * usable).toInt()
        val edge = CARD_MARGIN_PX + tileLen
        return DashSplitGeom(
            fraction = clamped,
            tileLen = tileLen,
            tileEdge = edge.toFloat(),
            splitCenter = edge + DASH_GAP_PX / 2f,
            otherStart = edge + DASH_GAP_PX,
        )
    }

    /** Split main-axis length (app-area coordinates): landscape = appAreaWidth, portrait = appAreaHeight. */
    private fun splitMainLen(): Int = if (splitVertical) appAreaHeight else appAreaWidth

    val runningApps = LinkedHashSet<ComponentName>()

    fun sessionApps(): List<ComponentName> = synchronized(runningApps) { runningApps.toList().asReversed() }

    private fun recordSessionApp(
        component: ComponentName,
        displayId: Int,
    ) {
        synchronized(runningApps) {
            if (displayId <= 0 || displayId !in setOf(appDisplayId, appHiDisplayId, mapDisplayId, mediaDisplayId)) return
            runningApps.removeAll { it.packageName == component.packageName }
            runningApps.add(component)
        }
        notifyRunningAppsChanged()
    }

    private val runningAppsListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Dock registration callback: refresh when the running list changes (the dock owns a display, is always resumed, and onResume no
     * longer fires).
     */
    fun addRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.add(listener)
    }

    fun removeRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.remove(listener)
    }

    private fun notifyRunningAppsChanged() {
        runningAppsListeners.forEach { runCatching { it() } }
    }

    /**
     * Desktop icon cache (used by DockActivity to show running-app icons). The desktop scan thread replaces the whole table while the
     * dock's
     * main thread reads concurrently - a volatile reference swap, so the old table a reader gets is still complete and self-consistent
     * (before, clear + per-entry
     * writes on one HashMap with no cross-display synchronization could corrupt it under concurrency).
     */
    @Volatile
    private var iconCacheRef: Map<ComponentName, Drawable> = emptyMap()

    val iconCache: Map<ComponentName, Drawable>
        get() = iconCacheRef

    /** Replaces the whole icon cache when the desktop scan completes. */
    fun replaceIconCache(fresh: Map<ComponentName, Drawable>) {
        iconCacheRef = fresh
        // The dock may finish its first draw before the desktop scan (only default icons could be drawn then): notify it to redraw after
        // the swap
        notifyRunningAppsChanged()
    }

    @Volatile
    var dockDisplayId: Int = -1
        private set

    @Volatile
    var appDisplayId: Int = -1
        private set

    /** Pixel width the dock occupies in the main composited picture (TouchRouter split threshold; landscape = width of the left strip). */
    @Volatile
    var dockWidthPx: Int = 0
        private set

    /**
     * The dock is a bottom bar (portrait head unit, or landscape with the "Dock at bottom" layout); expresses dock position only, see
     * [splitVertical] for split direction.
     */
    @Volatile
    var dockAtBottom: Boolean = false
        private set

    /** Map on top, media below (portrait head unit / music-bar layout); false = side-by-side split. */
    @Volatile
    var splitVertical: Boolean = false
        private set

    /** Layout of this session (resolved at attach from preference and canvas, see [DashLayout.resolve]). */
    @Volatile
    var dashLayout: DashLayout = DashLayout.SIDE_BY_SIDE
        private set

    /** The head-unit canvas is portrait (basis for settings pages etc. to treat as "narrow screen", independent of dock position). */
    fun isPortraitCanvas(): Boolean = streamHeightPx > streamWidthPx

    /** Dock bar height (portrait; TouchRouter splits by y). */
    @Volatile
    var dockHeightPx: Int = 0
        private set

    /** App-area height (portrait; y < appHeightPx belongs to the app display). */
    @Volatile
    var appHeightPx: Int = 0
        private set

    /**
     * Supersampling ratio of the app display (render ratio of the supersampled app display; 1 = no supersampled display created).
     * Touch coordinates are converted by the ratio of the current **active app display** (see [activeAppScale]).
     */
    @Volatile
    var hiScale: Float = 1f
        private set

    /** Supersampled app display (exists on landscape head units; -1 on portrait head units, where everything uses the native display). */
    @Volatile
    var appHiDisplayId: Int = -1
        private set

    /**
     * Display hosting the main-area desktop (GridActivity): landscape = supersampled display (keeps the existing look), portrait = native
     * display.
     */
    @Volatile
    var gridDisplayId: Int = -1
        private set

    /** Video-stream canvas size (baseline of the aspectFill mapping of desktop wallpaper slices). */
    @Volatile
    var streamWidthPx: Int = 0
        private set

    @Volatile
    var streamHeightPx: Int = 0
        private set

    // ---- Map display (dashboard split): a display dedicated to the map app; split = tile size, fullscreen = app-area size ----

    /** Map display displayId (created on landscape head units in trusted-display mode; -1 = none). */
    @Volatile
    var mapDisplayId: Int = -1
        private set

    @Volatile
    var mediaDisplayId: Int = -1
        private set

    /** Display of the last press injection (the assistive dot's "Back" in the dashboard goes to the last-operated map / music app). */
    @Volatile
    var lastTouchedDisplay: Int = -1

    @Volatile
    var mediaPaneOpen: Boolean = false
        private set

    private var mediaDisplay: VirtualDisplay? = null

    @Volatile
    var dashboardPanesSwapped = false
        private set

    @Volatile
    private var activeMapComponent: ComponentName? = null

    val activeMapPackage: String? get() = activeMapComponent?.packageName

    /** App currently in the media area (CarPlayMedia display); the dock does not duplicate it or the current map. */
    @Volatile
    private var activeMediaComponent: ComponentName? = null

    /**
     * Running apps shown by the dock: apps opened during the session, **minus the current map and the media-area app** -
     * they already have fixed places on the dashboard, and the dock is left for additionally opened apps (user request 2026-11-17).
     */
    fun dockApps(): List<ComponentName> {
        val hidden = setOfNotNull(activeMapComponent?.packageName, activeMediaComponent?.packageName)
        return synchronized(runningApps) { runningApps.filter { it.packageName !in hidden } }
    }

    fun setDashboardPanesSwapped(
        context: Context,
        swapped: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dashboardPanesSwapped", swapped)
            .apply()
        dashboardPanesSwapped = swapped
        notifyMapLayout()
    }

    fun mapPaneBounds(): io.github.ezn24.cardeck.neo.render.PaneBounds =
        io.github.ezn24.cardeck.neo.render.mapPaneBounds(
            streamWidthPx,
            appAreaHeight,
            if (dockAtBottom) 0 else dockWidthPx,
            dashSplitPos,
            splitVertical,
            dashboardPanesSwapped,
        )

    /** Switches the foreground task on the same map display, keeping this selection; does not change the persisted default map. */
    @Synchronized
    fun switchMap(component: ComponentName): Boolean {
        val target = mapDisplayId
        if (target < 0) return false
        val ok = openMapTask(component, target)
        if (target != mapDisplayId) return false
        if (ok) {
            activeMapComponent = component
            recordSessionApp(component, target)
            // Default map = last used map (user request 2026-11-15): if another map was opened on the desktop,
            // the split uses it on the next connection, with no need to change it in settings
            appContextRef?.let { DefaultMapPrefs.set(it, component.packageName) }
        }
        logSink?.invoke("MAP: ${component.packageName} -> map area display=$target ok=$ok")
        return ok
    }

    fun mediaPaneBounds(): io.github.ezn24.cardeck.neo.render.PaneBounds =
        io.github.ezn24.cardeck.neo.render.mediaPaneBounds(
            streamWidthPx,
            appAreaHeight,
            if (dockAtBottom) 0 else dockWidthPx,
            dashSplitPos,
            splitVertical,
            dashboardPanesSwapped,
        )

    fun mediaAppBounds(): io.github.ezn24.cardeck.neo.render.PaneBounds = mediaPaneBounds()

    @Volatile
    var dashboardPageAnimating = false
        private set

    @Volatile
    var dashboardUserDragging = false
        private set

    /** On swiping right from the first app page, the map follows the pager; the logical mode is committed once it settles. */
    fun beginDashboardPageDrag() {
        if (dashboardMode || mapFullscreen) return
        dashboardUserDragging = true
        dashboardPageAnimating = true
        setTileSlide(1f)
        composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
        refreshOverlayCard()
    }

    /** Captures the composited head-unit picture (returns false when not connected); the callback runs on the GL thread. */
    fun requestCarFrame(callback: (android.graphics.Bitmap?) -> Unit): Boolean {
        val c = composer ?: return false
        c.requestCapture(callback)
        return true
    }

    /** fakeDrag interface of the desktop pager (registered by GridActivity; the callback switches to the main thread itself). */
    interface DashboardDragSink {
        fun begin(): Boolean

        fun dragBy(px: Float)

        fun end()
    }

    @Volatile
    var dashboardDragSink: DashboardDragSink? = null

    /**
     * Dashboard follow-finger left drag (called after TouchRouter claims the gesture): the GL map layer slides out with the finger driven
     * by the pager's onPageScrolled;
     * after release the pager settles and at IDLE the mode is committed by the landing point (same ending as dragging right from an app
     * page back to the dashboard).
     */
    fun beginDashboardDragOut(): Boolean {
        if (!dashboardMode || mapFullscreen) return false
        val sink = dashboardDragSink ?: return false
        dashboardUserDragging = true
        dashboardPageAnimating = true
        setTileSlide(0f)
        composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
        if (!sink.begin()) {
            dashboardUserDragging = false
            dashboardPageAnimating = false
            return false
        }
        return true
    }

    fun dashboardDragBy(px: Float) {
        if (dashboardUserDragging) dashboardDragSink?.dragBy(px)
    }

    fun endDashboardDrag() {
        dashboardDragSink?.end()
    }

    fun finishDashboardPageSwitch(dashboard: Boolean) {
        if (!dashboardPageAnimating) return
        dashboardUserDragging = false
        dashboardPageAnimating = false
        setTileSlide(if (dashboardMode) 0f else 1f)
        composer?.setTileLayout(
            when {
                mapFullscreen -> DualDisplayComposer.TileLayout.FULLSCREEN
                dashboardMode -> DualDisplayComposer.TileLayout.SPLIT
                else -> DualDisplayComposer.TileLayout.HIDDEN
            },
        )
        refreshOverlayCard()
    }

    fun isMediaPaneVisible(): Boolean =
        mediaPaneOpen &&
            dashboardMode &&
            !mapFullscreen &&
            tileSlideFraction <= 0.01f &&
            composer?.hasMediaContent() == true

    fun closeMediaPane() {
        mediaPaneOpen = false
        composer?.showMediaPane = false
        notifyMapLayout()
    }

    /** Launch only on the separate media display; do not toggle the fullscreen-app state, do not move map or desktop tasks. */
    @Synchronized
    fun launchMediaApp(component: ComponentName): Boolean {
        val target = mediaDisplayId
        if (target < 0) {
            logSink?.invoke("MEDIA: media display unavailable, keeping the music card")
            return false
        }
        AppOrientationCompat.awaitPending()
        val onTarget = reuseVehicleTask(component.packageName, target)
        val result = startMapApp(component, target, forceNewTask = !onTarget, extraArgs = "--windowingMode 1")
        val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
        if (ok) {
            activeMediaComponent = component
            mediaPaneOpen = true
            composer?.showMediaPane = true
            notifyMapLayout()
            recordSessionApp(component, target)
            LetterboxHintController.clear()
        }
        logSink?.invoke("MEDIA: ${component.packageName} -> media area display=$target ok=$ok")
        return ok
    }

    /** Dashboard <-> desktop swipe progress (0 = tile in place, 1 = fully slid out): tile event injection is suppressed while swiping. */
    @Volatile
    var tileSlideFraction: Float = 0f
        private set

    /** Driven by GridActivity with the pager swipe to move the tile visually. */
    fun setTileSlide(fraction: Float) {
        tileSlideFraction = fraction
        composer?.tileSlideFraction = fraction
    }

    /**
     * The "Display" setting switches the target frame rate live (silent when the compositor is not created - attach reads it from
     * preferences).
     */
    fun isLayoutTransitioning(): Boolean =
        suppressTouchDuringLayout(dashboardPageAnimating, dashboardUserDragging, composer?.isLayoutTransitioning() == true)

    fun setStreamFps(fps: Int) {
        composer?.targetFps = fps
    }

    /** Map-display tile size (actual display size of the split form; consistent with the SPLIT draw rectangle of DualDisplayComposer). */
    @Volatile
    var mapTileWidth: Int = 0
        private set

    @Volatile
    var mapTileHeight: Int = 0
        private set

    /**
     * Dashboard split handle center (stream pixels, origin at top; landscape = x, portrait = y). <0 = not attached.
     * During a drag [previewDashSplit] previews continuously; on release [commitDashSplit] settles it and derives
     * [mapTileWidth]/[mapTileHeight]. All geometry derivation goes through the [dashSplitGeom] pure function.
     */
    @Volatile
    var dashSplitPos: Float = -1f
        private set

    /** Split ratio in effect (restored from preferences at attach, written back on commit). */
    @Volatile
    private var dashSplitFraction = DASH_SPLIT_DEFAULT_FRACTION

    /** App-area size (target size of the map display in fullscreen form). */
    @Volatile
    var appAreaWidth: Int = 0
        private set

    @Volatile
    var appAreaHeight: Int = 0
        private set

    @Volatile
    var mapFullscreen: Boolean = false
        private set

    /** Current logical size of the map display (idempotent baseline for resize; 0 = not created / released). */
    @Volatile
    private var mapDisplayW = 0

    @Volatile
    private var mapDisplayH = 0

    /**
     * Logical size of the native app display (source of truth for the display size in letterbox bar computation; constant after creation).
     */
    @Volatile
    private var appDisplayW = 0

    @Volatile
    private var appDisplayH = 0

    /** Logical size of the supersampled app display (same as above). */
    @Volatile
    private var appHiDisplayW = 0

    @Volatile
    private var appHiDisplayH = 0

    /**
     * Map display / media display density: based on 720 short side = 200dpi and scaled with the canvas (2026-11-17).
     * Previously fixed at 200dpi - an 800x480 map display was only ~534x271dp, the Google Maps search bar and bottom panel
     * filled the height and left only a sliver of map; after scaling it is ~800x408dp, the same proportions as 1920x720.
     * resize does not change density (logical size scales with pixels). Lower bound 120 so the ROM does not reject an abnormal density.
     */
    private val MAP_DISPLAY_DPI: Int get() = (200f * DashMetrics.scale).toInt().coerceIn(120, 600)

    /**
     * The media display renders at this multiple of its pane in pixels and density: normal apps keep their dp size (just
     * sharper), while apps that lay out with the phone's density regardless of the display (WeChat's call screen,
     * 2026-11-22: buttons blown up and cut off in a 427x688 pane; 2x on the 600 dpi S24 still gave it only ~228 dp, buttons
     * overlapping) get room in pixels. Chosen at attach so the pane is at least [MEDIA_MIN_PHONE_DP] wide in the phone's own
     * density (S24: 3x), 2..4. The compositor scales it back down.
     */
    @Volatile
    var mediaSupersample = 2
        private set

    private const val MEDIA_MIN_PHONE_DP = 320

    private fun mediaSupersampleFor(paneWidthPx: Int): Int {
        val phoneDensity =
            android.content.res.Resources
                .getSystem()
                .displayMetrics.density
        if (paneWidthPx <= 0) return 2
        return kotlin.math
            .ceil(MEDIA_MIN_PHONE_DP * phoneDensity / paneWidthPx)
            .toInt()
            .coerceIn(2, 4)
    }

    /** Whether dashboard (split) mode is active. */
    @Volatile
    var dashboardMode: Boolean = false
        private set

    /** Dashboard mode change listener (GridActivity switches layout). */
    private val dashboardListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.add(listener)
    }

    fun removeDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.remove(listener)
    }

    private fun notifyDashboard() {
        refreshOverlayCard()
        dashboardListeners.forEach { runCatching { it(dashboardMode) } }
    }

    /** Map tile layout change listener (hidden / split / fullscreen). */

    /** The car's own home button from /info `oemIcons` (label + icon); null = the car offers none. */
    data class OemBlock(
        val label: String,
        val icon: android.graphics.Bitmap?,
    )

    @Volatile
    var oemBlock: OemBlock? = null
        private set

    private val oemListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOemListener(listener: () -> Unit) {
        oemListeners.add(listener)
    }

    fun removeOemListener(listener: () -> Unit) {
        oemListeners.remove(listener)
    }

    /** Published when /info arrives (after the launcher's first frame) and cleared when the session ends. */
    fun setOemBlock(block: OemBlock?) {
        if (block == oemBlock) return
        oemBlock = block
        oemListeners.forEach { runCatching { it() } }
    }

    private val mapLayoutListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.add(listener)
    }

    fun removeMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.remove(listener)
    }

    private fun notifyMapLayout() {
        refreshOverlayCard()
        mapLayoutListeners.forEach { runCatching { it(mapFullscreen) } }
    }

    /** Built-in map priority order; other manually specified packages are also routed to the map display. */
    val MAP_PACKAGES_PRIORITY =
        listOf(
            "com.autonavi.amapauto",
            "com.baidu.naviauto",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.tencent.map",
            "com.google.android.apps.maps",
            "com.google.android.apps.mapslite",
            "com.waze",
            "com.here.app.maps",
            "net.osmand",
            "net.osmand.plus",
            "app.organicmaps",
            "com.mapswithme.maps.pro",
            "com.sygic.aura",
            "com.tomtom.gplay.navapp",
        )

    /**
     * "Music apps" that may enter the dashboard media area: they provide a system media-browse service (MediaBrowserService, standard for
     * car media apps)
     * and are not chat apps. Telegram voice and Samsung recorder recording/playback both register a "now playing" media session,
     * which once got them opened onto the media display by the music card, and then tapping them from the desktop also brought them back
     * to the media area (user feedback 2026-11-17).
     */
    fun isMusicApp(
        context: Context,
        packageName: String,
    ): Boolean {
        if (packageName in NON_MUSIC_PACKAGES) return false
        val pm = context.packageManager
        val category =
            runCatching { pm.getApplicationInfo(packageName, 0).category }
                .getOrDefault(android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED)
        if (category == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL) return false
        val browser =
            android.content
                .Intent("android.media.browse.MediaBrowserService")
                .setPackage(packageName)
        return runCatching { pm.queryIntentServices(browser, 0).isNotEmpty() }.getOrDefault(false)
    }

    fun isMapPackage(packageName: String): Boolean =
        packageName in MAP_PACKAGES_PRIORITY ||
            packageName == activeMapComponent?.packageName ||
            appContextRef?.let { DefaultMapPrefs.manualPackage(it) == packageName } == true

    /**
     * Installed default map (explicit user setting first - DefaultMapPrefs; otherwise the
     * first installed one in priority order; null = no map app at all).
     *
     * WARNING: this function is **only** a forward to [DefaultMapPrefs.resolve], and the latter must never call back
     * this function (infinite recursion -> StackOverflowError). The priority scan is implemented in place inside resolve,
     * which owns its correctness; this is kept only for the external API semantics (VirtualLauncher is the main
     * entry for callers). Settled 2026-10-18.
     */
    fun defaultMapComponent(context: Context): ComponentName? = DefaultMapPrefs.resolve(context)

    /**
     * Render ratio of the display hosting the desktop relative to the video stream (native display = 1, supersampled display = hiScale).
     * Used for size/offset conversion when the desktop draws its "full-stream canvas wallpaper slice".
     */
    fun gridScale(): Float = if (gridDisplayId >= 0 && gridDisplayId == appHiDisplayId) hiScale else 1f

    private var dockDisplay: VirtualDisplay? = null
    private var appDisplay: VirtualDisplay? = null
    private var appHiDisplay: VirtualDisplay? = null

    /** Handle of the map display in fallback form (trusted form creates it via shell, so there is no handle in this process). */
    private var mapDisplay: VirtualDisplay? = null
    private var composer: DualDisplayComposer? = null

    /** ID of the current active app display (TouchRouter injection target). */
    fun activeAppDisplayId(): Int = if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) appHiDisplayId else appDisplayId

    /** Coordinate ratio of the current active app display (supersampled display = hiScale, native display = 1). */
    fun activeAppScale(): Float = if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) hiScale else ChatMode.scaleFor(appDisplayId)

    /** Rectangle of the app area in the output picture (origin at top) - used to position overlay cards. */
    fun appAreaRect(): RectF? {
        if (streamWidthPx <= 0 || streamHeightPx <= 0) return null
        return if (dockAtBottom) {
            RectF(0f, 0f, streamWidthPx.toFloat(), appHeightPx.toFloat())
        } else {
            RectF(dockWidthPx.toFloat(), 0f, streamWidthPx.toFloat(), streamHeightPx.toFloat())
        }
    }

    /** Canvas UI density **scale** (dpi/160; same baseline as the dock display: 420dpi @ 540 short side). */
    fun uiDensity(): Float = ((420f * minOf(streamWidthPx, streamHeightPx) / 540f).coerceIn(120f, 1680f)) / 160f

    /**
     * Reads [rect] (map display pixels) of the map display's current frame from the compositor, see
     * [DualDisplayComposer.requestMapRegion]. False when there is no session / map display; the caller then falls
     * back to a system screenshot.
     */
    fun requestMapRegion(
        rect: android.graphics.Rect,
        callback: (android.graphics.Rect, IntArray?) -> Unit,
    ): Boolean {
        val c = composer ?: return false
        if (mapDisplayId < 0 || mapDisplayW <= 0 || mapDisplayH <= 0) return false
        // Clamped to the display: the callback gets the rectangle actually read
        val r = android.graphics.Rect(rect)
        if (!r.intersect(0, 0, mapDisplayW, mapDisplayH)) return false
        return c.requestMapRegion(r.left, r.top, r.width(), r.height(), mapDisplayW, mapDisplayH) { pixels -> callback(r, pixels) }
    }

    /** Logical pixel size of a given virtual display (fallback source of truth when letterbox probing lacks mMaxBounds). */
    fun hintDisplaySize(displayId: Int): Pair<Int, Int>? =
        when (displayId) {
            appDisplayId -> if (appDisplayW > 0) appDisplayW to appDisplayH else null
            appHiDisplayId -> if (appHiDisplayW > 0) appHiDisplayW to appHiDisplayH else null
            mapDisplayId -> if (mapDisplayW > 0) mapDisplayW to mapDisplayH else null
            mediaDisplayId -> mediaAppBounds().let { it.width * mediaSupersample to it.height * mediaSupersample }
            else -> null
        }

    /** Attach / detach an overlay card on the compositor. */
    @Volatile
    private var hintOverlay: OverlayCard? = null

    @Volatile
    private var notificationOverlay: OverlayCard? = null

    fun setOverlayCard(card: OverlayCard?) {
        hintOverlay = card
        refreshOverlayCard()
    }

    fun setNotificationOverlay(card: OverlayCard?) {
        notificationOverlay = card
        refreshOverlayCard()
    }

    /** Knob focus frame (stream coordinates); null hides it. */
    fun setFocusRing(card: OverlayCard?) {
        composer?.focusRing = card
    }

    fun setAssistiveDot(card: OverlayCard?) {
        composer?.assistiveDot = card
    }

    fun isNotificationOverlayVisible(): Boolean = hintOverlay == null && notificationOverlay != null

    fun refreshOverlayCard() {
        composer?.overlayCard = hintOverlay ?: notificationOverlay ?: MediaReturnControl.card()
        composer?.pageDots = pageDotsCard
    }

    /** Desktop page-dot snapshot (output-picture coordinates); see [DualDisplayComposer.pageDots]. */
    @Volatile
    private var pageDotsCard: OverlayCard? = null

    /**
     * Whether the page dots are instead drawn by the compositor above the map / media cards: when the dashboard split is visible
     * (including swiping to and from the app page)
     * and the map is not fullscreen. GridActivity hides its own page dots by the same criterion.
     */
    fun pageDotsOnTop(): Boolean = (dashboardMode || dashboardPageAnimating) && !mapFullscreen

    /** Whether the page dots should show (only while paging; driven by GridActivity from the pager swipe state). */
    @Volatile
    var pageDotsShowing: Boolean = false
        private set

    fun setPageDotsShowing(show: Boolean) {
        if (pageDotsShowing == show) return
        pageDotsShowing = show
        composer?.setPageDotsVisible(show)
    }

    /**
     * GridActivity reports the page-dot bitmap. [left]/[top] are pixel coordinates of the dots inside the **display hosting the desktop**;
     * that display may be the supersampled one (pixels = output x [gridScale]), so convert back to the output picture and then translate
     * to [appAreaRect].
     */
    fun setPageDots(
        bitmap: android.graphics.Bitmap?,
        left: Float,
        top: Float,
    ) {
        val area = appAreaRect()
        val scale = gridScale().coerceAtLeast(0.01f)
        pageDotsCard =
            if (bitmap == null || area == null) {
                null
            } else {
                val l = area.left + left / scale
                val t = area.top + top / scale
                OverlayCard(bitmap, l, t, l + bitmap.width / scale, t + bitmap.height / scale)
            }
        composer?.pageDots = pageDotsCard
    }

    /**
     * True when the voice-assistant overlay (Gemini) is open on the head-unit display hosting the desktop: the compositor pauses drawing
     * map/media cards,
     * and touches are no longer routed to the cards, so the native overlay is fully visible and tappable.
     */
    @Volatile
    var assistCoversDashboard = false
        private set

    /**
     * Display hosting the overlay (-1 = closed). Yielding the cards is needed only when it lands on the desktop display / the current
     * active app display -
     * overlays on other displays (map/media displays) are shown together with the cards anyway. Returns whether the cards were yielded.
     */
    fun setAssistOnCarDisplay(displayId: Int): Boolean {
        val cover = displayId >= 0 && (displayId == gridDisplayId || displayId == activeAppDisplayId())
        assistCoversDashboard = cover
        composer?.tilesSuppressed = cover
        refreshOverlayCard()
        return cover
    }

    fun isMediaReturnVisible(): Boolean =
        hintOverlay == null &&
            notificationOverlay == null &&
            !assistCoversDashboard &&
            dashboardMode &&
            mediaPaneOpen &&
            !mapFullscreen &&
            !dashboardPageAnimating

    /** Voice-assistant transparent display (-1 = not built); created on demand, see [ensureAssistDisplay]. */
    @Volatile
    var assistDisplayId: Int = -1
        private set

    @Volatile
    private var assistDensityDpi = 200

    /**
     * Builds the voice-assistant transparent display on demand (CarPlayAssist, app-area size, same density as the native app display,
     * trusted).
     * Background thread only (binder + shell). Returns -1 on failure and the caller falls back to the old approach.
     */
    @Synchronized
    fun ensureAssistDisplay(): Int {
        if (assistDisplayId >= 0) return assistDisplayId
        val svc = CarInputClient.service ?: return -1
        val surface = composer?.assistLayerSurface() ?: return -1
        if (appAreaWidth <= 0 || appAreaHeight <= 0) return -1
        val id =
            runCatching {
                svc.createDisplay("CarPlayAssist", appAreaWidth, appAreaHeight, assistDensityDpi, surface, true)
            }.getOrDefault(-1)
        if (id >= 0) {
            assistDisplayId = id
            applyIgnoreOrientationRequest(id)
        }
        logSink?.invoke(
            "ASSIST: transparent display " +
                if (id >= 0) "built id=$id ${appAreaWidth}x$appAreaHeight@${assistDensityDpi}dpi" else "creation failed",
        )
        return id
    }

    /** Assistant layer visibility (overlay on / not on the assistant transparent display). */
    fun setAssistLayerVisible(visible: Boolean) {
        composer?.assistLayerVisible = visible
        refreshOverlayCard()
    }

    /** Session context (needed for re-probing after a letterbox restart; null when not attached). */
    fun appContext(): Context? = appContextRef

    /**
     * Attach: build the GL compositor -> in its ready callback build the two virtual displays -> launch Dock/Grid.
     * [encoderSurface] is the encoder input Surface; returns whether everything succeeded.
     */
    @SuppressLint("WrongConstant")
    fun attach(
        context: Context,
        encoderSurface: Surface,
        totalWidth: Int,
        totalHeight: Int,
        densityDpi: Int,
        log: (String) -> Unit,
    ): Boolean {
        detach()
        // New session: user-level decisions such as "no more hints this session" are invalidated
        LetterboxHintController.resetSession()
        // The "force app landscape" override **must be written before any app launch** (settled on a real Audi 3315
        // 2026-10-23): `am compat enable` kills the target app immediately (overrides only take effect in a new process) -
        // if written after the apps are launched, the map/desktop processes just spawned on the displays are killed on the spot and the
        // virtual displays
        // show only a static wallpaper, so the head unit receives a picture that never changes and never switches CarPlay UI (seen as
        // "streaming
        // but the head unit still shows its original UI"). The write is enqueued at pairing start (endpoint handling in SessionController),
        // and attach only drains it - usually already done (~0ms). Do not run applyAll synchronously here:
        // the screen-port keepAlive frames are sent only from the drainEncoder loop, and the drain thread starts only after this method
        // returns -
        // every 1s blocked here directly eats the ~10s tear-down budget of "RECORD -> first keepAlive"
        // (threshold measured on Audi).
        if (!AppOrientationCompat.awaitPending()) {
            log(
                "ORIENTATION: WARNING override writes did not drain within 6s - apps may launch with the old orientation config " +
                    "(first app launched after the write finishes gets killed once)",
            )
        }
        dashboardPanesSwapped =
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("dashboardPanesSwapped", false)
        val density = densityDpi / 160f
        // Dock bar width scale ("Display" panel 50%-100%, takes effect next connection): thickness pixels are multiplied by the ratio
        // together with the dock display
        // density - the dp layout of the content is unchanged but rendered into fewer pixels, so the whole dock (icons / clock /
        // text) narrows proportionally; shrinking only thickness and not density would overflow or leave gaps. The 80px lower bound is
        // multiplied by the scale to prevent
        // it from being squeezed out on extreme canvases. Density lower bound 120 so the ROM does not reject an abnormal density.
        // Layout: dock position and split direction (narrow landscape can choose the music-bar / bottom-dock layout; portrait head units
        // are fixed)
        val layout = DashLayout.resolve(DisplayPrefs.dashboardLayout(context), totalWidth, totalHeight)
        dashLayout = layout
        splitVertical = layout.splitVertical
        // The landscape bottom dock is thinner than the side bar (Android Auto style): thickness is multiplied with the dock display
        // density, so content shrinks proportionally without overflow
        val dockScale = DockPrefs.widthPercent(context) / 100f * if (layout == DashLayout.DOCK_BOTTOM) BOTTOM_DOCK_FACTOR else 1f
        val thicknessPx =
            (DOCK_WIDTH_DP * density * dockScale)
                .toInt()
                .coerceAtLeast((80 * dockScale).toInt().coerceAtLeast(1))
        val dockDensityDpi = (densityDpi * dockScale).toInt().coerceAtLeast(120)
        // Head-unit orientation decides dock position: landscape -> left vertical strip; portrait -> bottom bar (user request 2026-10-11)
        dockAtBottom = layout.dockAtBottom
        // Layout scale must precede all geometry derivation (margins / gap / corner radius all follow it)
        DashMetrics.configure(totalWidth, totalHeight)
        streamWidthPx = totalWidth
        streamHeightPx = totalHeight
        val appWidth: Int
        if (dockAtBottom) {
            dockWidthPx = totalWidth
            dockHeightPx = thicknessPx
            appHeightPx = (totalHeight - thicknessPx).coerceAtLeast(200)
            appWidth = totalWidth
        } else {
            dockWidthPx = thicknessPx
            dockHeightPx = totalHeight
            appHeightPx = totalHeight
            appWidth = (totalWidth - thicknessPx).coerceAtLeast(200)
        }
        appAreaWidth = appWidth
        appAreaHeight = appHeightPx
        // Portrait app-display density: target logical window width ~ phone portrait width (LETTERBOX_TARGET_DP).
        // Wrongly using the dock's densityDpi (scaled with canvas resolution, not the phone density) squeezes the window to ~206dp - text
        // looks twice as large
        // (2026-10-14 portrait test: root cause of visibly oversized fonts in apps and desktop).
        val appPortraitDensity =
            (appWidth / LETTERBOX_TARGET_DP * 160f).toInt().coerceIn(160, 640)
        // Map display: created at the full app-area size, dynamically resized between split / fullscreen (see resizeMapDisplay).
        // Tile size = SPLIT draw rectangle; the margin uniformly uses the contract value CARD_MARGIN_PX (do not hard-code numbers again).
        // The ratio adapts to the app-area shape ([dashSplitFractionFor], 3:1 at 1920x720); the old
        // drag ratio is ignored. Geometry derivation is centralized in the [dashSplitGeom] pure function (unit tests pin four-way
        // consistency).
        // Tile main-axis length (landscape = width, portrait = height) = ratio x usable length.
        dashSplitFraction =
            when {
                splitVertical -> dashSplitFractionFor(appHeightPx, appWidth, portrait = true)
                else -> dashSplitFractionFor(appWidth, appHeightPx, portrait = false)
            }
        log(
            "LAUNCHER: dashboard layout canvas=${totalWidth}x$totalHeight layout=$layout scale=${"%.2f".format(DashMetrics.scale)} " +
                "map ratio=${"%.2f".format(dashSplitFraction)}",
        )
        if (splitVertical) {
            // Top/bottom split: main axis = y, origin is the stream top edge (the dock is not above)
            val geom = dashSplitGeom(appHeightPx, dashSplitFraction)
            mapTileWidth = appWidth - CARD_MARGIN_PX * 2
            mapTileHeight = geom.tileLen
            dashSplitPos = geom.splitCenter
        } else {
            val geom = dashSplitGeom(appWidth, dashSplitFraction)
            mapTileWidth = geom.tileLen
            mapTileHeight = appHeightPx - CARD_MARGIN_PX * 2
            // Side-by-side split: the app area starts at the dock's right edge; with the dock at the bottom the x origin is the stream
            // origin
            dashSplitPos = geom.splitCenter + if (dockAtBottom) 0 else dockWidthPx
        }
        // Density choice for the landscape app display (calibrated by measurement 2026-10-11):
        // portrait-locked apps (e.g. Toutiao) are letterboxed into a narrow window on a landscape display; the window's logical width
        // = display height px / letterbox aspect ratio, then converted to dp by the display density. With high density the window is only
        // ~343dp
        // and the app shows the "big text, big search box" narrow-screen layout; at density ~200 the window is ~411dp ~ phone width,
        // and the app shows the compact phone layout (verified by screenshots). Goal: letterbox window ~ LETTERBOX_TARGET_DP.
        val landscapeApp = appWidth > appHeightPx
        // Supersampling: some apps (measured on Toutiao) reserve the top area by "phone status-bar physical pixels"
        // (28dp@560dpi = 98px raw pixels, independent of display density), taking 18% in a 540px-high stream
        // versus only 3.5% on the phone - it cannot be removed (drawn inside the app), only "render more pixels and scale back"
        // shrinks the share of fixed pixels. x2: 98px -> 49px (9%); supersampled height cap 1620px.
        val appScaleFactor =
            if (landscapeApp) {
                minOf(APP_SUPERSAMPLE, SUPERSAMPLE_MAX_HEIGHT.toFloat() / appHeightPx)
            } else {
                1f
            }
        val appHiWidth = (appWidth * appScaleFactor).toInt()
        val appHiHeight = (appHeightPx * appScaleFactor).toInt()
        this.hiScale = appScaleFactor
        val appHiDensity =
            if (landscapeApp) {
                val letterboxWindowPx = appHiHeight / LETTERBOX_ASPECT_RATIO
                (letterboxWindowPx / LETTERBOX_TARGET_DP * 160f).toInt().coerceIn(180, 640)
            } else {
                appPortraitDensity
            }
        // Native app-display density: keep the **same logical size** as the supersampled display (app layout is unaffected by the display
        // it is on,
        // supersampling is only a render-resolution difference). Landscape: native 1034x540 @~200dpi = 827x432dp,
        // equal to the supersampled display's (1551x810 @301dpi) 824x431dp; portrait head unit: use the stream density directly.
        val appNativeDensity =
            if (landscapeApp) {
                val hiLogicalHeightDp = appHiHeight * 160f / appHiDensity
                (appHeightPx * 160f / hiLogicalHeightDp).toInt().coerceIn(160, 640)
            } else {
                appPortraitDensity
            }
        assistDensityDpi = appNativeDensity

        // Wait for the Shizuku UserService to bind (a trusted virtual display needs shell privilege to create)
        var svc = CarInputClient.service
        if (svc == null &&
            ShizukuGate
                .state(context) ==
            ShizukuGate.State.READY
        ) {
            val deadline = SystemClock.uptimeMillis() + 2_500L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                CarInputClient
                    .ensure(context)
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val useTrusted = svc != null
        log("LAUNCHER: virtual-display mode=${if (useTrusted) "trusted (shell)" else "app-side (fallback)"}")

        val composer = DualDisplayComposer(encoderSurface, totalWidth, totalHeight, dockAtBottom, splitVertical)
        composer.targetFps =
            DisplayPrefs
                .fps(context)
        this.composer = composer
        composer.statsLog = { line -> log(line) }
        val displaysCreated = arrayOf(false)
        composer.onSurfacesReady = { dockSurface, appSurface, appHiSurface, mapSurface, mediaSurface ->
            // Preferred: create a trusted virtual display via Shizuku shell (VIRTUAL_DISPLAY_FLAG_TRUSTED) -
            // lets new tasks of third-party apps land on that display; fixes "tasks from in-app jumps get redirected to the phone main
            // screen".
            var viaService = false
            if (useTrusted) {
                runCatching {
                    // Leftover cleanup: same-name virtual displays left by a process restart / the previous session keep occupying their
                    // slots if not released
                    // (new width / new surface does not take effect - root cause of "no visual change after widening the dock" 2026-10-11)
                    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val preserved = preservedDisplayIds()
                    dm.displays
                        .filter { it.displayId !in preserved }
                        .filter {
                            it.name == "CarPlayDock" ||
                                it.name == "CarPlayApp" ||
                                it.name == "CarPlayAppHi" ||
                                it.name == "CarPlayMap" ||
                                it.name == "CarPlayMedia" ||
                                it.name == "CarPlayAssist"
                        }.forEach { stale ->
                            runCatching { svc!!.releaseDisplay(stale.displayId) }
                                .onSuccess { log("LAUNCHER: released leftover virtual display ${stale.name}#${stale.displayId}") }
                        }
                    Thread.sleep(150) // Wait for the system to finish unregistering
                    // Lock-screen streaming: displays go on a virtual device associated with this car (empty = legacy)
                    runCatching {
                        svc!!.setCompanionDevice(
                            io.github.ezn24.cardeck.neo.bt.BtSessionGate.lastPeerAddress
                                .orEmpty(),
                        )
                    }
                    // The dock display is not declared trusted: it hosts only our own DockActivity, preventing third-party tasks from
                    // escaping onto it
                    val dockId =
                        svc!!.createDisplay(
                            "CarPlayDock",
                            dockWidthPx,
                            dockHeightPx,
                            dockDensityDpi,
                            dockSurface,
                            false,
                        )
                    val appId =
                        svc!!.createDisplay(
                            "CarPlayApp",
                            appWidth,
                            appHeightPx,
                            appNativeDensity,
                            appSurface,
                            true,
                        )
                    // Supersampled display (absent on portrait head units, and also absent in app-side fallback)
                    val appHiId =
                        if (appHiSurface != null) {
                            svc!!.createDisplay(
                                "CarPlayAppHi",
                                appHiWidth,
                                appHiHeight,
                                appHiDensity,
                                appHiSurface,
                                true,
                            )
                        } else {
                            -1
                        }
                    // Map display (created for both orientations; created at full app-area size, dynamically resized between split /
                    // fullscreen)
                    val mapId =
                        if (mapSurface != null) {
                            svc!!.createDisplay(
                                "CarPlayMap",
                                appWidth,
                                appHeightPx,
                                MAP_DISPLAY_DPI,
                                mapSurface,
                                true,
                            )
                        } else {
                            -1
                        }
                    if (dockId >= 0 && appId >= 0) {
                        dockDisplayId = dockId
                        appDisplayId = appId
                        appDisplayW = appWidth
                        appDisplayH = appHeightPx
                        if (appHiId >= 0) {
                            appHiDisplayId = appHiId
                            appHiDisplayW = appHiWidth
                            appHiDisplayH = appHiHeight
                        } else if (appHiSurface != null) {
                            log("LAUNCHER: supersampled display creation failed - this session uses native displays throughout")
                        }
                        if (mapId >= 0) {
                            mapDisplayId = mapId
                            // Creation size = full app-area size (idempotent baseline of resizeMapDisplay)
                            mapDisplayW = appWidth
                            mapDisplayH = appHeightPx
                        } else if (mapSurface != null) {
                            log("LAUNCHER: map display creation failed - dashboard has no map tile")
                        }
                        viaService = true
                    } else {
                        // Leave a trace per display: ROMs such as EMUI **silently return null**
                        // (no exception) from shell-side createVirtualDisplay - without this line a whole-session fallback would log only
                        // the word
                        // "fallback", and there would be no way to tell which display was rejected (located on a Huawei Mate30Pro / EMUI12
                        // 2026-10-20)
                        if (dockId < 0) {
                            log(
                                "LAUNCHER: trusted display CarPlayDock creation failed " +
                                    "(createVirtualDisplay returned null - ROM limits shell-side displays?)",
                            )
                        }
                        if (appId < 0) {
                            log("LAUNCHER: trusted display CarPlayApp creation failed (same as above)")
                        }
                        // Partial success: release the ones already built
                        if (dockId >= 0) runCatching { svc!!.releaseDisplay(dockId) }
                        if (appId >= 0) runCatching { svc!!.releaseDisplay(appId) }
                        if (appHiId >= 0) runCatching { svc!!.releaseDisplay(appHiId) }
                        if (mapId >= 0) runCatching { svc!!.releaseDisplay(mapId) }
                        runCatching { svc!!.closeVirtualDevice() }
                    }
                }.onFailure { log("LAUNCHER: trusted display creation error ${it.message}") }
            }
            if (viaService) {
                displaysViaService = true
                log(
                    "LAUNCHER: three displays created (trusted) dock=$dockDisplayId(${dockWidthPx}px) " +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor)",
                )
                // Session established: write the compat override for the "force app landscape" list - **moved to the start of attach**
                // (write the override first, then launch apps; see the comment at the start of attach; not enqueued again here).
                // Music-card data source first: session setup is a reliable moment where "Shizuku is certainly available",
                // so top up notification access once more (idempotent; returns directly if already granted).
                // Covers the whole path "Shizuku was not ready at app start and became ready later".
                Bg.run("notification-access-session") {
                    NotificationAccess.ensureGranted(context.applicationContext, log)
                    // Cluster navigation info (only if the user opted in): a reinstall / force-stop drops the service
                    io.github.ezn24.cardeck.neo.nav.NavAccessibilityAccess
                        .ensureEnabled(context.applicationContext, log)
                }
                // Delete again 8 seconds later: session start is busy creating displays / launching the map / producing the first frame,
                // and dumpsys competes with it for CPU and binder
                Bg.run("stale-car-tasks") {
                    Thread.sleep(8_000)
                    if (appContextRef != null) purgeRememberedTasks(context.applicationContext)
                }
                // The override write is asynchronous (probe internally does awaitPending to wait for it to drain);
                // if the app is already running and was earlier restored as a portrait window, it can be detected here and the hint given.
                LetterboxHintController.scheduleProbe(context, "session established")
            } else {
                // Fallback: app-side PUBLIC displays (tasks from in-app jumps escape to the phone main screen; a degraded form that stays
                // as usable as possible)
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dockDisplay =
                    dm.createVirtualDisplay(
                        "CarPlayDock",
                        dockWidthPx,
                        dockHeightPx,
                        dockDensityDpi,
                        dockSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                appDisplay =
                    dm.createVirtualDisplay(
                        "CarPlayApp",
                        appWidth,
                        appHeightPx,
                        appNativeDensity,
                        appSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                if (appHiSurface != null) {
                    appHiDisplay =
                        dm.createVirtualDisplay(
                            "CarPlayAppHi",
                            appHiWidth,
                            appHiHeight,
                            appHiDensity,
                            appHiSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                            null,
                            null,
                        )
                    appHiDisplayId = appHiDisplay?.display?.displayId ?: -1
                    appHiDisplayW = appHiWidth
                    appHiDisplayH = appHiHeight
                }
                // The map display is built too (app-side PUBLIC): the map launches via shell `am start --display`,
                // which needs no trust; resize uses the app-side public API (see resizeMapDisplay).
                // The fallback form once never built a map display - after trusted creation failed silently on EMUI12,
                // the dashboard never had a map tile (measured on a Huawei Mate30Pro 2026-10-20).
                if (mapSurface != null) {
                    mapDisplay =
                        dm.createVirtualDisplay(
                            "CarPlayMap",
                            appWidth,
                            appHeightPx,
                            MAP_DISPLAY_DPI,
                            mapSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                            null,
                            null,
                        )
                    val fallbackMapId = mapDisplay?.display?.displayId ?: -1
                    if (fallbackMapId >= 0) {
                        mapDisplayId = fallbackMapId
                        mapDisplayW = appWidth
                        mapDisplayH = appHeightPx
                    } else {
                        log("LAUNCHER: map display (fallback) creation failed - dashboard has no map tile")
                    }
                }
                dockDisplayId = dockDisplay?.display?.displayId ?: -1
                appDisplayId = appDisplay?.display?.displayId ?: -1
                appDisplayW = appWidth
                appDisplayH = appHeightPx
                log(
                    "LAUNCHER: three displays created (fallback) dock=$dockDisplayId(${dockWidthPx}px) " +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor) " +
                        "map=$mapDisplayId(${mapDisplayW}x$mapDisplayH) - trusted displays unavailable, fell back to PUBLIC",
                )
            }
            // Media display is optional: on failure keep the control card, and do not let the main session fail along with it
            val mediaBounds = mediaAppBounds()
            runCatching {
                if (viaService) {
                    mediaDisplayId =
                        svc!!.createDisplay(
                            "CarPlayMedia",
                            mediaBounds.width * mediaSupersample,
                            mediaBounds.height * mediaSupersample,
                            MAP_DISPLAY_DPI * mediaSupersample,
                            mediaSurface,
                            true,
                        )
                } else {
                    val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    mediaDisplay =
                        manager.createVirtualDisplay(
                            "CarPlayMedia",
                            mediaBounds.width * mediaSupersample,
                            mediaBounds.height * mediaSupersample,
                            MAP_DISPLAY_DPI * mediaSupersample,
                            mediaSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        )
                    mediaDisplayId = mediaDisplay?.display?.displayId ?: -1
                }
            }.onFailure { log("MEDIA: media display creation failed: ${it.message}") }
            displaysCreated[0] = dockDisplayId >= 0 && appDisplayId >= 0
        }
        // Decided before composer.start: its media input buffer and the CarPlayMedia display (created in onSurfacesReady)
        // must use the same factor, or the display draws into a smaller buffer and only its top-left part shows
        mediaSupersample = mediaSupersampleFor(mediaAppBounds().width)
        log("MEDIA: media display supersampled x$mediaSupersample (${mediaAppBounds().width}x${mediaAppBounds().height} pane)")
        return try {
            composer.start(
                dockWidthPx,
                dockHeightPx,
                appWidth,
                appHeightPx,
                if (landscapeApp) appHiWidth else 0,
                if (landscapeApp) appHiHeight else 0,
                appWidth,
                appHeightPx,
                mediaAppBounds().width * mediaSupersample,
                mediaAppBounds().height * mediaSupersample,
            )
            // Push the split-handle position to the compositor (overrides the default midline formula; previewDashSplit keeps updating it
            // during a drag)
            composer.setTileEdgeOverride(dashSplitPos - DASH_GAP_PX / 2f)
            if (!displaysCreated[0]) {
                log("LAUNCHER: virtual display creation failed")
                detach()
                return false
            }
            // App-display orientation policy: **ignore the orientation requested by apps**, keeping the virtual display in the form it was
            // created.
            // Portrait apps (Toutiao etc.) on a landscape head unit request portrait; if the system complies it rotates the whole
            // virtual display (rotation 0->3 in dumpsys, logical size 2251x1152->1152x2251),
            // while the compositor maps the whole picture and touches are converted with unrotated coordinates - seen as "resolution
            // changed +
            // clicks drift and miss targets", and returning to the desktop is still in the rotated state (measured 2026-10-18).
            // Staying shrunk inside the letterbox (see aspectRatio below) is the proper form for a head unit.
            applyIgnoreOrientationRequest(appDisplayId, appHiDisplayId, mapDisplayId, mediaDisplayId)
            // Widen the letterbox window of portrait apps (default narrow strip -> phone width); restored on disconnect (detach)
            Shell.exec("cmd window set-letterbox-style --aspectRatio $LETTERBOX_ASPECT_RATIO")
            log(
                "LAUNCHER: letterbox aspect ratio=$LETTERBOX_ASPECT_RATIO " +
                    "appDensity=${appNativeDensity}dpi appHiDensity=${appHiDensity}dpi",
            )
            // Self-launched components must be written as "applicationId/absolute class name": `am -n` only accepts the form with a slash,
            // and a relative class name is expanded against applicationId - under the .rd debug package (applicationId suffix)
            // `pkg/.launcher.DockActivity` points to a nonexistent class and a bare class name is rejected outright
            // (Bad component name); both spellings make dock/grid launch fail and the head unit black
            // at 0fps (measured with the .rd package 2026-10-21). The absolute class name comes from the Class reference and follows
            // refactors automatically.
            val selfPkg = context.packageName
            // The first picture settles directly into the split; it must not depend on the user pressing Home to launch the map.
            appContextRef = context.applicationContext
            dashboardMode = true
            setTileSlide(0f)
            composer.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
            val dockOk = startOn(dockDisplayId, "$selfPkg/${DockActivity::class.java.name}")
            gridDisplayId = if (appHiDisplayId >= 0) appHiDisplayId else appDisplayId
            val gridOk = startOn(gridDisplayId, "$selfPkg/${GridActivity::class.java.name}")
            log("LAUNCHER: dock=$dockOk grid=$gridOk gridDisplay=$gridDisplayId")
            if (dockOk && gridOk) prepareDashboardMap()
            // When the desktop is on the supersampled display, the default active display = the supersampled display (the desktop shows at
            // launch)
            if (appHiDisplayId >= 0) composer.requestActiveApp(true)
            startShizukuHealer()
            // Dimming curtain component ready (keep-awake + curtain feature; the main screen switch controls start/stop)
            ScreenDimmer
                .attach(context)
            io.github.ezn24.cardeck.neo.assist.AssistOverlayController
                .start(context, log)
            AssistiveDot.attach(context)
            dockOk && gridOk
        } catch (error: IllegalStateException) {
            log("LAUNCHER: GL compositor failed to start - ${error.message}")
            detach()
            false
        }
    }

    /** Trusted-display flag: when true the virtual displays are held by the shell UserService and must be released through the service. */
    @Volatile
    private var displaysViaService = false

    @Volatile
    private var pendingDisplayRelease: java.util.concurrent.Future<*>? = null
    private val displayReleaseLock = Any()

    private data class ParkedMap(
        val displayId: Int,
        val localDisplay: VirtualDisplay?,
        val pkg: String,
        val taskId: String,
    )

    @Volatile private var parkedMap: ParkedMap? = null

    @Volatile private var detachingDisplays = emptySet<Int>()

    /** These displays are handled by detach/reconnect and must be excluded from residual cleanup. */
    fun preservedDisplayIds(): Set<Int> = detachingDisplays + listOfNotNull(parkedMap?.displayId)

    fun detach() {
        AssistiveDot.detach()
        io.github.ezn24.cardeck.neo.assist.AssistOverlayController
            .stop()
        io.github.ezn24.cardeck.neo.notifications.NotificationBannerController
            .clear()
        hintOverlay = null
        notificationOverlay = null
        pageDotsCard = null
        pageDotsShowing = false
        assistCoversDashboard = false
        ScreenDimmer
            .detach()
        // Restore the orientation policy (false = follow app requests, the system default).
        //
        // **Must be asynchronous and without read-back**: detach is triggered by disconnect() on the UI thread, and every
        // `Shell.exec` here is a Shizuku newProcess + process wait (~100ms each, up to several
        // seconds of timeout on errors). Three displays x (set+get) = 6 round trips are enough to trigger an ANR (measured 2026-10-18:
        // "app not responding" popped up repeatedly after a manual disconnect). It is now posted once to the background without read-back -
        // this is the **cleanup** path: a failure only leaves a stray orientation policy and does not affect session correctness;
        // only the setup path (applyIgnoreOrientationRequest) needs read-back verification.
        //
        // Timing: displays are released right after posting, but the background task runs concurrently with the release - to make sure the
        // command
        // hits a **still-alive displayId**, the id strings are taken synchronously here and the commands are queued at posting time;
        // if a display was already released (id invalid) the command merely errors and does not affect later sessions (it is set again
        // when the next round creates it).
        val orientationIds = intArrayOf(appDisplayId, appHiDisplayId, mapDisplayId, mediaDisplayId).filter { it >= 0 }
        if (orientationIds.isNotEmpty()) {
            Bg.run("orientation-cleanup") {
                orientationIds.forEach { runCatching { setIgnoreOrientationRequest(it, false, verify = false) } }
            }
        }
        // Leave the map on its virtual display so Android never brings it to the phone's foreground.
        val svc = if (displaysViaService) CarInputClient.service else null
        val vehicleDisplays = listOf(appDisplayId, appHiDisplayId, mapDisplayId).filter { it >= 0 }
        val localDisplays =
            listOfNotNull(dockDisplay, appDisplay, appHiDisplay, mapDisplay, mediaDisplay)
                .associateBy { it.display.displayId }
        val releaseIds =
            buildSet {
                if (displaysViaService) {
                    listOf(dockDisplayId, appDisplayId, appHiDisplayId, mapDisplayId, mediaDisplayId).filterTo(this) { it >= 0 }
                }
                addAll(localDisplays.keys)
                if (assistDisplayId >= 0) add(assistDisplayId)
            }
        val oldMapDisplay = mapDisplayId
        val oldMapPackage = activeMapPackage
        val ownCtx = appContextRef
        displaysViaService = false
        assistDisplayId = -1
        if (releaseIds.isNotEmpty()) {
            synchronized(displayReleaseLock) {
                val previousRelease = pendingDisplayRelease
                detachingDisplays = detachingDisplays + releaseIds
                pendingDisplayRelease =
                    Bg.pool.submit {
                        runCatching { previousRelease?.get() }
                        val service = svc ?: CarInputClient.service
                        var retained: ParkedMap? = null
                        try {
                            val snapshot = readTaskSnapshot()
                            retained = retainMapTask(ownCtx, oldMapPackage, oldMapDisplay, vehicleDisplays, snapshot, localDisplays)
                            if (retained != null) {
                                val previous = parkedMap
                                parkedMap = retained
                                scheduleParkedMapExpiry(retained)
                                if (previous != null && previous.displayId != retained.displayId) releaseParkedMap(previous)
                                val surfaceDetached =
                                    runCatching {
                                        retained.localDisplay?.let {
                                            it.setSurface(null)
                                            true
                                        }
                                            ?: (service?.detachDisplaySurface(retained.displayId) == true)
                                    }.getOrDefault(false)
                                logSink?.invoke(
                                    "MAP: parked task #${retained.taskId} on virtual display ${retained.displayId}, surface detached=$surfaceDetached",
                                )
                            }
                            rememberCarTasks(snapshot.orEmpty(), releaseIds, ownCtx)
                            var removed = 0
                            for (display in releaseIds) {
                                for ((pkg, ids) in snapshot?.get(display).orEmpty()) {
                                    if (pkg == ownCtx?.packageName) continue
                                    for (id in ids) {
                                        if (id != retained?.taskId && removeTask(id)) removed++
                                    }
                                }
                            }
                            logSink?.invoke("LAUNCHER: removed $removed non-retained head-unit tasks before disconnect")
                        } finally {
                            for (id in releaseIds) {
                                if (id == retained?.displayId) continue
                                runCatching { localDisplays[id]?.release() ?: service?.releaseDisplay(id) }
                            }
                            synchronized(displayReleaseLock) { detachingDisplays = detachingDisplays - releaseIds }
                            // Virtual device of this session; deferred by the service while a parked map remains on it
                            runCatching { service?.closeVirtualDevice() }
                        }
                        ownCtx?.let { purgeRememberedTasks(it) }
                    }
            }
        }
        appContextRef = null
        watchdogActive = false
        // Restore the letterbox aspect ratio to the system default (do not leave session tuning in the system).
        // **Must run in the background**: it is a shell command (timeout cap 6s), and detach may be triggered by the main thread
        // or the Shizuku callback thread; the 2026-10-22 K60E log shows "SHELL: WARNING main-thread call to
        // shell (#1) cmd=cmd window reset-letterbox-style aspectRatio".
        Bg.run("letterbox-reset") {
            runCatching { Shell.exec("cmd window reset-letterbox-style aspectRatio") }
        }
        runCatching { composer?.stop() }
        composer = null
        dockDisplay = null
        appDisplay = null
        appHiDisplay = null
        mapDisplay = null
        mediaDisplay = null
        mediaDisplayId = -1
        mediaPaneOpen = false
        dashboardPageAnimating = false
        dashboardUserDragging = false
        activeMapComponent = null
        activeMediaComponent = null
        dockDisplayId = -1
        appDisplayId = -1
        appHiDisplayId = -1
        gridDisplayId = -1
        ChatMode.forget()
        synchronized(runningApps) { runningApps.clear() }
        notifyRunningAppsChanged()
        mapDisplayId = -1
        mapTileWidth = 0
        mapTileHeight = 0
        dashSplitPos = -1f
        mapDisplayW = 0
        mapDisplayH = 0
        appDisplayW = 0
        appDisplayH = 0
        appHiDisplayW = 0
        appHiDisplayH = 0
        appAreaWidth = 0
        appAreaHeight = 0
        mapFullscreen = false
        // The hint card is session state: cleared together when the displays are torn down (the compositor has stopped, and the card
        // texture dies with the GL context)
        LetterboxHintController.clear()
        if (dashboardMode) {
            dashboardMode = false
            notifyDashboard()
        }
        dockWidthPx = 0
        dockHeightPx = 0
        appHeightPx = 0
        dockAtBottom = false
        splitVertical = false
        dashLayout = DashLayout.SIDE_BY_SIDE
        hiScale = 1f
        streamWidthPx = 0
        streamHeightPx = 0
    }

    private fun retainMapTask(
        context: Context?,
        pkg: String?,
        mapDisplay: Int,
        vehicleDisplays: List<Int>,
        snapshot: Map<Int, Map<String, List<String>>>?,
        localDisplays: Map<Int, VirtualDisplay>,
    ): ParkedMap? {
        if (pkg == null || context == null) return null
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_RETAINED_MAP, null)
        val task =
            if (snapshot == null) {
                // A failed dumpsys is not proof that the active task is gone. Keep its known dedicated display.
                val id = retainedMapId(saved?.takeIf { it.startsWith("$pkg:") })
                if (id != null && mapDisplay > 0) id to mapDisplay else null
            } else {
                findMapTask(snapshot, pkg, mapDisplay, vehicleDisplays, saved)
            }
        val (id, display) = task ?: return null
        if (display !in vehicleDisplays) return null
        prefs.edit().putString(KEY_RETAINED_MAP, "$pkg:$id").apply()
        return ParkedMap(display, localDisplays[display], pkg, id)
    }

    /**
     * Releases the parked map (removing its task, so a navigation left running stops talking) once the disconnect
     * work has finished. Called on a user disconnect and when a park expires.
     */
    fun releaseParkedMap(reason: String) {
        val handoff = pendingDisplayRelease
        Bg.run("release-parked-map") {
            runCatching { handoff?.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            val parking = parkedMap ?: return@run
            releaseParkedMap(parking)
            logSink?.invoke("MAP: released parked task #${parking.taskId} on display ${parking.displayId} ($reason)")
        }
    }

    /** A car-side end keeps the map for [PARKED_MAP_TTL_MS]; not reconnected by then = the trip is over. */
    private fun scheduleParkedMapExpiry(parking: ParkedMap) {
        Bg.run("parked-map-expiry") {
            try {
                Thread.sleep(PARKED_MAP_TTL_MS)
            } catch (_: InterruptedException) {
                return@run
            }
            if (parkedMap == parking) releaseParkedMap("not reconnected within ${PARKED_MAP_TTL_MS / 60_000} min")
        }
    }

    private fun releaseParkedMap(parking: ParkedMap) {
        // If the map was not moved (e.g. the default was changed), remove only this exact old car task.
        // Public fallback displays can otherwise raise their old contents onto the phone when released.
        val remaining = taskIdsOnDisplayPkg(parking.pkg, parking.displayId)
        if (parking.taskId in remaining) removeTask(parking.taskId)
        val released =
            runCatching {
                parking.localDisplay?.let {
                    it.release()
                    true
                }
                    ?: (CarInputClient.service?.releaseDisplay(parking.displayId) == true)
            }.getOrDefault(false)
        if (released && parkedMap == parking) parkedMap = null
    }

    private fun startOn(
        displayId: Int,
        component: String,
    ): Boolean {
        if (displayId < 0) return false
        val result =
            Shell.exec(
                "am start -n ${component.asShellArgument()} --display $displayId --windowingMode 1",
                timeoutMs = 8000,
            )
        return result.ok && result.output.contains("Starting:", ignoreCase = true)
    }

    /**
     * Back to desktop: bring the GridActivity task to the foreground (background apps kept); the compositor switches back to the display
     * hosting the desktop.
     *
     *  The shell launch must be asynchronous: callers (dock back-to-desktop / desktop pager settling) are all on the main thread,
     * and waiting synchronously on the shell with a worst-case 8s timeout is enough for an ANR (main-thread call warning triggered
     * 2026-10-21). */
    fun goGrid() {
        val grid = if (gridDisplayId >= 0) gridDisplayId else appDisplayId
        val pkg = appContextRef?.packageName ?: return
        Bg.run("go-grid") {
            if (!startOn(grid, "$pkg/${GridActivity::class.java.name}")) Log.w(TAG, "back to desktop failed")
            // Leaving a chat app: remove the chat density (the desktop is on the supersampled display and chat apps are mostly on the app
            // display, so the desktop does not flicker on restore)
            ChatMode.restore { logSink?.invoke(it) }
        }
        composer?.requestActiveApp(appHiDisplayId >= 0 && grid == appHiDisplayId)
        // A hint on the desktop is meaningless (the top is not a third-party app), so collapse it directly
        LetterboxHintController.clear()
    }

    // ---- Dashboard (split) and the home-key mode cycle ----
    //
    // Home-key semantics (user-defined): app -> dashboard; desktop -> dashboard; dashboard -> desktop.
    // Dashboard = left half of the app area is the map tile (map display resized to the tile size), right half is the
    // GridActivity music card; the map task stays on the map display, and fullscreen <-> split only resizes without migrating,
    // so navigation and other app state is not lost.

    /**
     * The dock's home key: mode cycle (dashboard <-> desktop; app / fullscreen map -> dashboard).
     * Note mapFullscreen must be reset first - the routing gate depends on it, and forgetting to reset would keep sending desktop touches
     * into the already hidden map display (measured: root cause of "desktop cannot be tapped although the log shows injection succeeded").
     */

    /**
     * Incoming call: bring the dashboard's card side forward so the call card is visible (fullscreen map or app ->
     * dashboard, a player in the media pane -> card). Background thread, like the dock's home key.
     */
    fun showDashboardForCall() {
        if (isMediaPaneVisible()) closeMediaPane()
        when {
            mapFullscreen -> {
                setMapFullscreen(false)
                showDashboard()
            }

            !dashboardMode -> showDashboard()
        }
    }

    fun onHomePressed() {
        when {
            mapFullscreen -> {
                setMapFullscreen(false)
                showDashboard()
            }

            dashboardMode -> showDesktop()
            else -> showDashboard()
        }
    }

    /**
     * Enter the dashboard: map display shrinks back to the tile, the default map takes the display (launched if missing), the desktop
     * switches to the split layout.
     */
    fun showDashboard() {
        returnToDashboardOnExit = false
        syncDashboard(true)
        goGrid()
        composer?.requestActiveApp(appHiDisplayId >= 0 && gridDisplayId == appHiDisplayId)
        logSink?.invoke("LAUNCHER: dashboard mode (map tile + music card)")
    }

    /**
     * The single reconciliation point of dashboard mode: set dashboardMode -> notify (dock icon / GridActivity pager)
     * -> on entry ensure the map task and switch to SPLIT, on exit hide the tile.
     * Pager swiping only previews the tile displacement (showMapTile); state is always reconciled here when it settles (IDLE).
     */
    fun syncDashboard(dashboard: Boolean) {
        val changed = dashboardMode != dashboard
        dashboardMode = dashboard
        if (changed) {
            dashboardPageAnimating = true
            // On entry first put the map off-screen; on exit keep the map until the whole page has slid out.
            if (dashboard) setTileSlide(1f)
            composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
            notifyDashboard()
        }
        if (dashboard) {
            prepareDashboardMap()
        } else if (!dashboardPageAnimating) {
            composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
        }
    }

    private fun prepareDashboardMap() {
        val target = mapDisplayId
        if (target < 0) return
        Bg.run("dashboard-enter") {
            if (target != mapDisplayId || !dashboardMode) return@run
            if (mapFullscreen) setMapFullscreen(false)
            resizeMapDisplay(fullscreen = false)
            ensureMapTask()
        }
    }

    /** Swipe preview: only toggles tile visibility (does not touch dashboardMode / listeners); reconciled by syncDashboard once settled. */
    fun showMapTile() {
        composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
    }

    /**
     * Back to the plain desktop: hide the map layer and leave the dashboard; the desktop restores the full-width icon grid (map task kept).
     */
    fun showDesktop() {
        returnToDashboardOnExit = false
        syncDashboard(false)
        goGrid()
        logSink?.invoke("LAUNCHER: desktop mode (icon grid)")
    }

    /**
     * Map display fullscreen <-> split: dynamically resizes the map display to the target size (fullscreen = app area, split = tile
     * display size);
     * the map app on the display receives a configuration change from the system and relayouts (the task is not migrated, so navigation
     * state stays continuous).
     * The compositor switches layout in step: after the resize the map display content is the target-region content, mapped whole with no
     * cropped sampling.
     */
    fun setMapFullscreen(fullscreen: Boolean) {
        if (mapDisplayId < 0) return
        // When opening the fullscreen map from the app page, clear the dashboard slide-out progress so touches can hit the map display.
        if (fullscreen) setTileSlide(0f)
        if (mapFullscreen == fullscreen) return
        mapFullscreen = fullscreen
        // Binder / screen-configuration operations are always serialized in the background; on fast toggling only the latest target is
        // applied.
        mapResizeExecutor.execute { resizeMapDisplay(mapFullscreen) }
        composer?.setTileLayout(
            if (fullscreen) {
                DualDisplayComposer.TileLayout.FULLSCREEN
            } else {
                DualDisplayComposer.TileLayout.SPLIT
            },
        )
        notifyMapLayout()
        logSink?.invoke("LAUNCHER: map display ${if (fullscreen) "fullscreen" else "split"}")
        // After the map display resize the letterbox inner/outer frames change: re-probe on fullscreen; the split tile is too small to hint
        if (fullscreen) {
            appContextRef?.let {
                // The resize has taken effect and the target is the map display (no need to wait for the compositor to switch layout)
                LetterboxHintController.scheduleProbe(it, "map fullscreen", displayIdHint = mapDisplayId)
            }
        } else {
            LetterboxHintController.clear()
        }
    }

    /**
     * Preview the split position during a drag: **only changes the compositor's draw rectangle** (the map texture is stretched whole, the
     * buffer and the virtual display are untouched) - the drag stays smooth and triggers no map-app configuration change. The real resize
     * happens on release in
     * [commitDashSplit].
     *
     * @param streamPos handle center (stream pixels, origin at top; landscape x / portrait y), clamped internally
     */
    fun previewDashSplit(streamPos: Float) = Unit

    /**
     * Settle on release: clamp -> update the handle position and tile-derived sizes -> real resize of the map display (including
     * consumer-side buffer sync) -> persist the ratio -> notify (the music card wallpaper slice is re-cut).
     *
     * @return the clamped handle center (stream pixels) - the UI uses it to correct margins, preventing display/state drift
     */
    fun commitDashSplit(streamPos: Float): Float = dashSplitPos

    /** Converts the current handle center to app-display pixels (used by GridActivity layout; returns -1 when not attached). */
    fun dashSplitScreenPos(): Float = if (dashSplitPos < 0) -1f else dashSplitPos * gridScale()

    /** Handle center in app-display pixels (app-area coordinates, dock offset subtracted; the handle DOWN origin, <0 = not attached). */
    fun dashHandleCenterScreen(): Float = if (dashSplitPos < 0) -1f else (dashSplitPos - splitMainOffset()) * gridScale()

    /** Content-side start of the music card in app-display pixels (landscape = left edge, portrait = top edge; <0 = not attached). */
    fun dashMusicStartScreen(): Int =
        if (dashSplitPos < 0) {
            -1
        } else {
            ((dashSplitPos - splitMainOffset() + DASH_GAP_PX / 2f) * gridScale()).toInt()
        }

    /**
     * App-display-pixel input version of [previewDashSplit] (called directly by handle dragging, converted back to stream pixels
     * internally).
     */
    fun previewDashSplitScreen(centerScreen: Float) {
        previewDashSplit(splitMainOffset() + centerScreen / gridScale().coerceAtLeast(1e-6f))
    }

    /** App-display-pixel input version of [commitDashSplit]; returns the clamped handle center (app-display pixels). */
    fun commitDashSplitScreen(centerScreen: Float): Float =
        (
            commitDashSplit(splitMainOffset() + centerScreen / gridScale().coerceAtLeast(1e-6f)) -
                splitMainOffset()
        ) * gridScale()

    /**
     * Whether a touch point falls inside the split handle strip (stream pixel coordinates) - used by TouchRouter sticky routing:
     * once a press hits it, the whole touch stream is routed to the app display (during a drag the handle lags behind the finger and
     * coordinates go out of bounds without re-routing,
     * otherwise cross-display re-injection would cut off the drag and mis-touch the map; settled in the 2026-10-21 design review).
     */
    fun dashHandleHit(
        x: Float,
        y: Float,
    ): Boolean {
        return false // Fixed ratio: the gap no longer has a drag hit area.
    }

    /**
     * Stream-coordinate handle center -> app-area coordinate offset (landscape subtracts the dock width; in portrait the y origin is the
     * stream origin).
     */
    private fun splitMainOffset(): Float = if (splitVertical || dockAtBottom) 0f else dockWidthPx.toFloat()

    /** Usable main-axis length = app-area main axis - end margins - gap width (same formula as dashSplitGeom). */
    private fun splitUsableLen(): Int = (if (splitVertical) appAreaHeight else appAreaWidth) - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()

    /**
     * Clamps the stream-coordinate handle center; returns null when not attached.
     * The bounds must be converted from the **ratio clamp domain** (20%-80%) - clamping to [margin, margin+usable] is equivalent to
     * allowing
     * ratio [0,1], and a card could be dragged into a line (root cause of the 2026-10-22 user report "can be dragged to 0").
     */
    private fun clampDashSplitStream(streamPos: Float): Float? {
        val usable = splitUsableLen()
        if (usable <= 0) return null
        val base = splitMainOffset() + CARD_MARGIN_PX + DASH_GAP_PX / 2f
        val min = base + DASH_SPLIT_MIN_FRACTION * usable
        val max = base + DASH_SPLIT_MAX_FRACTION * usable
        return streamPos.coerceIn(min, max)
    }

    /**
     * Resizes the map display to the target layout size (idempotent: no call when the size is unchanged - every resize triggers a
     * configuration change / relayout
     * of the app on the display, so avoid it when possible). When Shizuku is not bound or the call fails the current size is kept,
     * and the compositor's whole-picture mapping still holds (only the map UI density and visible area temporarily do not match).
     */
    private val mapResizeExecutor =
        java.util.concurrent.Executors
            .newSingleThreadExecutor()

    @Synchronized
    private fun resizeMapDisplay(fullscreen: Boolean) {
        val id = mapDisplayId
        if (id < 0) return
        val w = if (fullscreen) appAreaWidth else mapTileWidth
        val h = if (fullscreen) appAreaHeight else mapTileHeight
        if (w <= 0 || h <= 0 || (w == mapDisplayW && h == mapDisplayH)) return
        runCatching {
            val applied =
                if (displaysViaService) {
                    // Trusted form: shell-side resize
                    val svc = CarInputClient.service ?: return@runCatching
                    svc.resizeDisplay(id, w, h, MAP_DISPLAY_DPI)
                } else {
                    // Fallback form: the display was created by this app, so use the app-side public API directly - the shell
                    // has no resize permission on displays created by others (token owner check)
                    val display = mapDisplay ?: return@runCatching
                    display.resize(w, h, MAP_DISPLAY_DPI)
                    true
                }
            if (applied) {
                mapDisplayW = w
                mapDisplayH = h
                // The consumer-side buffer size must be synced - otherwise the new-size content is drawn into the top-left of the old
                // buffer
                // (tile content shifted left, width reduced to the ratio value, right side all black)
                composer?.resizeMapInput(w, h)
                logSink?.invoke(
                    "LAUNCHER: map display resize -> ${w}x$h@${MAP_DISPLAY_DPI}dpi (${if (fullscreen) "fullscreen" else "split"})",
                )
            }
        }
    }

    /**
     * Launch a map app onto the map display (fullscreen form): resize the map display -> launch -> compositor fullscreen layout.
     * Switching between map apps (Amap <-> Baidu) also happens on the same display, and the old task is cleared (mutually exclusive).
     */
    private fun launchMapOnMapDisplay(component: ComponentName): Boolean {
        if (!switchMap(component)) return false
        dashboardUserDragging = false
        dashboardPageAnimating = false
        dashboardMode = false
        notifyDashboard()
        setMapFullscreen(true)
        return true
    }

    /** A singleTask app may ignore the launch display, so first move the existing head-unit task to the target display. */
    private fun reuseVehicleTask(
        pkg: String,
        target: Int,
    ): Boolean {
        val tasks = taskIdsByDisplayPkg()
        if (tasksOf(tasks, pkg, target).isNotEmpty()) return true
        val sources = listOf(appDisplayId, appHiDisplayId, mapDisplayId, mediaDisplayId)
        val task =
            sources
                .asSequence()
                .filter { it > 0 && it != target }
                .flatMap { tasksOf(tasks, pkg, it).asSequence() }
                .firstOrNull() ?: return false
        val moved = Shell.exec("am display move-stack $task $target", timeoutMs = 4000)
        return moved.ok && task in taskIdsOnDisplayPkg(pkg, target)
    }

    /** List of task IDs of a given package on a given display (used for clearing a display). */
    private fun taskIdsOnDisplayPkg(
        pkg: String,
        displayId: Int,
    ): List<String> = tasksOf(taskIdsByDisplayPkg(), pkg, displayId)

    /**
     * A call app that opened its call screen on a car app display (the answer action started it on the focused display)
     * goes back to the phone, so the dashboard call card stays visible. Background thread. Returns the number of tasks moved.
     */

    /** The media pane currently shows the call app opened from the call card (closed again when the call ends). */
    @Volatile
    var callAppInMediaPane: String? = null
        private set

    /**
     * Tap on the call card: open the call app's call screen in the dashboard media pane (the notification's tap target on the
     * media display). A call screen moved to the phone on answer is reused by the system on the phone, so it is moved over
     * afterwards. Background thread.
     */
    fun openCallAppInMediaPane(
        context: Context,
        pkg: String,
        open: android.app.PendingIntent?,
    ): Boolean {
        val target = mediaDisplayId
        if (target < 0) return false
        val sent =
            open != null &&
                runCatching {
                    val options =
                        android.app.ActivityOptions
                            .makeBasic()
                            .setLaunchDisplayId(target)
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        options.setPendingIntentBackgroundActivityStartMode(
                            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                    }
                    open.send(context, 0, null, null, null, null, options.toBundle())
                    true
                }.getOrDefault(false)
        // No intent (ringtone-only call): the app's call screen is already open on the phone, move it over as it is
        val moved =
            !sent &&
                tasksOf(taskIdsByDisplayPkg(), pkg, 0)
                    .onEach { Shell.exec("am display move-stack $it $target", timeoutMs = 4000) }
                    .isNotEmpty()
        if (!sent && !moved) {
            val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component ?: return false
            startMapApp(component, target, forceNewTask = false, extraArgs = "--windowingMode 1")
        }
        Thread.sleep(700)
        if (tasksOf(taskIdsByDisplayPkg(), pkg, target).isEmpty()) {
            for (task in tasksOf(taskIdsByDisplayPkg(), pkg, 0)) Shell.exec("am display move-stack $task $target", timeoutMs = 4000)
        }
        callAppInMediaPane = pkg
        mediaPaneOpen = true
        composer?.showMediaPane = true
        notifyMapLayout()
        logSink?.invoke(
            "CALL: call app $pkg -> media area display=$target via=${if (sent) {
                "notification"
            } else if (moved) {
                "moved"
            } else {
                "launcher"
            }}",
        )
        return true
    }

    /** Call ended: a call screen opened in the media pane makes way for the music card again. */
    fun closeCallAppInMediaPane() {
        if (callAppInMediaPane == null) return
        callAppInMediaPane = null
        if (mediaPaneOpen) closeMediaPane()
    }

    fun moveCallAppToPhone(pkg: String): Int {
        val byDisplay = taskIdsByDisplayPkg()
        var moved = 0
        for (display in listOf(appDisplayId, appHiDisplayId, gridDisplayId).filter { it >= 0 }.distinct()) {
            for (task in tasksOf(byDisplay, pkg, display)) {
                if (Shell.exec("am display move-stack $task 0", timeoutMs = 4000).ok) moved++
            }
        }
        if (moved > 0) logSink?.invoke("CALL: moved $moved call-app task(s) of $pkg from the car display to the phone")
        return moved
    }

    /**
     * Launch a map app on a given display: try `am start -n` over the [mapLaunchCandidates] one by one.
     *
     * The explicit main activity of the car-version map (Amap `MainMapActivity`, Baidu `NaviAutoActivity`) may not exist on
     * some customized firmware / versions, and hard-coding a single point would leave the map display black with the log missing even a
     * `Starting:` line.
     * So when the first candidate fails it falls back to the launcher-resolved component, and every attempt is logged.
     */

    /**
     * Third-party apps launch the way "tapping a desktop icon" does (2026-11-18): on Android 12+, for a task started with MAIN+LAUNCHER,
     * pressing back at the root page only "moves it to the background" and keeps state; a bare `am start -n` launch finishes directly -
     * the map /
     * music app in the dashboard would exit entirely on a back press (user test).
     */
    private const val LAUNCHER_INTENT = "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER"

    /**
     * Called after the back key is sent to the map / media display (background thread): if that display became empty as a result (root
     * page moved to the background or finished),
     * bring it back at once - the dashboard map card / music area must not turn blank. Tasks launched with LAUNCHER are still in the
     * background,
     * and bringing them forward is a hot resume that loses no state.
     */

    /** Returns true when Back had left the pane empty (the app was at its root) and it was brought back. */
    fun restorePaneAfterBack(displayId: Int): Boolean {
        if (displayId < 0 || (displayId != mapDisplayId && displayId != mediaDisplayId)) return false
        Thread.sleep(600)
        if (displayHasVisibleTask(displayId)) return false
        logSink?.invoke("LAUNCHER: display $displayId has no visible task after back - bringing it to the foreground")
        if (displayId == mapDisplayId) {
            activeMapComponent?.let { switchMap(it) } ?: ensureMapTask()
        } else {
            activeMediaComponent?.let { startMapApp(it, displayId, forceNewTask = false, extraArgs = "--windowingMode 1") }
        }
        return true
    }

    /**
     * An app opened from the dock returns to the dashboard when it is backed out of (one opened from the desktop returns
     * to the desktop by itself). Cleared by any explicit mode change.
     */
    @Volatile
    var returnToDashboardOnExit = false

    /**
     * Whether the display has a visible task (parses the Task lines of dumpsys for visible=true). Treated as yes when unreadable, to avoid
     * a spurious launch.
     */
    private fun displayHasVisibleTask(displayId: Int): Boolean {
        val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4000).output
        val header = Regex("""^Display #$displayId\b""", RegexOption.MULTILINE).find(dump) ?: return true
        val rest = dump.substring(header.range.last)
        val end = Regex("""^Display #\d+""", RegexOption.MULTILINE).find(rest)?.range?.first ?: rest.length
        return Regex("""\* Task\{[^\n]*visible=true""").containsMatchIn(rest.substring(0, end))
    }

    private fun openMapTask(
        component: ComponentName,
        target: Int,
    ): Boolean {
        // A reconnect can race the previous display's async release. Wait on this launch worker before looking for its task.
        val handoff = pendingDisplayRelease
        try {
            handoff?.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            logSink?.invoke("MAP: previous display release is still pending")
            return false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (failure: java.util.concurrent.ExecutionException) {
            logSink?.invoke("MAP: previous display cleanup failed: ${failure.cause?.message}")
        }
        val context = appContextRef ?: return false
        if (target !in setOf(mapDisplayId, appDisplayId, appHiDisplayId)) return false
        val pkg = component.packageName
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_RETAINED_MAP, null)
        val snapshot = readTaskSnapshot() ?: return false
        val existing = findMapTask(snapshot, pkg, target, listOf(mapDisplayId, appDisplayId, appHiDisplayId), saved)
        if (existing != null) {
            val (id, display) = existing
            if (display != target &&
                (!Shell.exec("am display move-stack $id $target", timeoutMs = 4000).ok || id !in taskIdsOnDisplayPkg(pkg, target))
            ) {
                logSink?.invoke("MAP: task #$id could not move from display $display to $target")
                return false
            }
            if (display == target && !Shell.exec("am task focus $id", timeoutMs = 4000).ok) return false
            prefs.edit().putString(KEY_RETAINED_MAP, "$pkg:$id").apply()
            parkedMap?.takeIf { it.displayId != target }?.let { releaseParkedMap(it) }
            logSink?.invoke("MAP: resumed $pkg task #$id on display $target")
            return true
        }

        val result = startMapApp(component, target, forceNewTask = true, extraArgs = "--windowingMode 1")
        val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
        if (ok) {
            parkedMap?.takeIf { it.displayId != target }?.let { releaseParkedMap(it) }
            taskIdsOnDisplayPkg(pkg, target).firstOrNull()?.let { id ->
                prefs.edit().putString(KEY_RETAINED_MAP, "$pkg:$id").apply()
            }
        }
        return ok
    }

    private fun startMapApp(
        component: ComponentName,
        displayId: Int,
        forceNewTask: Boolean,
        extraArgs: String = "",
    ): Shell.Result {
        // Ignoring the orientation request on the map only triggers the system's portrait letterbox bars; finish the session landscape
        // override before launching.
        if (displayId == mapDisplayId) appContextRef?.let { AppOrientationCompat.prepareMap(it, component.packageName) }
        val suffix =
            (if (forceNewTask) " -f 0x18000000" else "") +
                (if (extraArgs.isEmpty()) "" else " $extraArgs")
        var last: Shell.Result? = null
        for (target in mapLaunchCandidates(component)) {
            val result =
                Shell.exec(
                    "am start $LAUNCHER_INTENT -n ${target.asShellArgument()} --display $displayId$suffix",
                    timeoutMs = 8000,
                )
            val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
            if (ok) return result
            logSink?.invoke(
                "LAUNCH(${component.packageName}): entry $target failed to launch (${result.output.take(80)}) - trying the next candidate",
            )
            last = result
        }
        return last
            ?: Shell.exec(
                "am start $LAUNCHER_INTENT -n ${component.flattenToShortString().asShellArgument()} --display $displayId$suffix",
                timeoutMs = 8000,
            )
    }

    /**
     * One `dumpsys activity activities` is parsed into "displayId -> package -> task ID list".
     * Previously the three query functions each dumped separately (one launchApp ran up to 3 dumps, each a shell command
     * with a 4s timeout) - now they all reuse a snapshot taken here.
     */

    /** Car-owned tasks are cleaned up across sessions. The active map is tracked separately for handoff. */
    private const val KEY_CAR_TASKS = "carTaskIds"
    private const val KEY_RETAINED_MAP = "retainedVehicleMapTask"
    private const val PARKED_MAP_TTL_MS = 30 * 60_000L

    private fun retainedMapId(entry: String?): String? = entry?.substringAfter(':', "")?.takeIf { it.toIntOrNull() != null }

    private fun rememberCarTasks(
        snapshot: Map<Int, Map<String, List<String>>>,
        displays: Collection<Int>,
        ctx: Context? = appContextRef,
    ) {
        if (ctx == null) return
        val own = ctx.packageName
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val retained = retainedMapId(prefs.getString(KEY_RETAINED_MAP, null))
        val ids =
            displays
                .flatMap { id ->
                    snapshot[id]
                        .orEmpty()
                        .filterKeys { it != own }
                        .values
                        .flatten()
                }.filter { it.all(Char::isDigit) }
        val remembered = prefs.getStringSet(KEY_CAR_TASKS, emptySet()).orEmpty()
        val merged = (remembered + ids).filterNot { it == retained }.toSet()
        if (merged != remembered) prefs.edit().putStringSet(KEY_CAR_TASKS, merged).apply()
    }

    /**
     * Remove the head-unit tasks left over from the previous session (background thread). Tasks being used on the head-unit displays in
     * the current session are all kept -
     * the new session may already have opened apps and recorded them in the same list.
     */
    private fun purgeRememberedTasks(ctx: Context) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val remembered = prefs.getStringSet(KEY_CAR_TASKS, emptySet()).orEmpty()
        if (remembered.isEmpty()) return
        val live = (listOf(appDisplayId, appHiDisplayId, mapDisplayId, mediaDisplayId) + preservedDisplayIds()).filter { it >= 0 }
        val snapshot = if (live.isEmpty()) emptyMap() else taskIdsByDisplayPkg()
        val keep = live.flatMap { id -> snapshot[id].orEmpty().values.flatten() }.toSet()
        val retained = retainedMapId(prefs.getString(KEY_RETAINED_MAP, null))
        val ids = (remembered - keep).filterNot { it == retained }
        if (ids.isEmpty()) return
        val removed =
            runCatching {
                CarInputClient.service?.removeTasks(ids.mapNotNull { it.toIntOrNull() }.toIntArray())
            }.getOrNull()
        prefs.edit().putStringSet(KEY_CAR_TASKS, remembered - ids.toSet()).apply()
        logSink?.invoke(
            "LAUNCHER: cleaned up ${ids.size} leftover head-unit tasks of the last session " +
                "(removeTask ok ${removed ?: "service unavailable"})",
        )
    }

    /**
     * Remove a task completely: prefer the UserService's removeTask (also deletes the phone's "recents" record),
     * and when unavailable fall back to `am stack remove` (deletes only the task, leaving a card in recents). Called on a background
     * thread.
     */
    fun removeTask(taskId: String): Boolean {
        val id = taskId.toIntOrNull() ?: return false
        val viaService = runCatching { CarInputClient.service?.removeTasks(intArrayOf(id)) }.getOrNull()
        if (viaService == 1) return true
        return Shell.exec("am stack remove $id", timeoutMs = 4000).ok
    }

    private fun taskIdsByDisplayPkg(): Map<Int, Map<String, List<String>>> = readTaskSnapshot().orEmpty()

    private fun readTaskSnapshot(): Map<Int, Map<String, List<String>>>? {
        val result = Shell.exec("dumpsys -t 3 activity activities", timeoutMs = 4000)
        if (!result.ok || !result.output.contains("Display")) return null
        return parseTaskDisplays(result.output).takeIf { it.isNotEmpty() }
    }

    /** Background management only views head-unit tasks; a read failure throws and must not be disguised as an empty list. */
    fun vehicleAppTasks(): Map<String, List<String>> {
        val result = Shell.exec("dumpsys activity activities", timeoutMs = 4000)
        check(result.ok && result.output.contains("Display")) {
            logSink?.invoke("RECENTS: head-unit task query failed exit=${result.exitCode}")
            "Unable to read vehicle tasks"
        }
        return vehicleTasks(
            parseTaskDisplays(result.output),
            setOf(appDisplayId, appHiDisplayId, mapDisplayId, mediaDisplayId),
            appContextRef?.packageName.orEmpty(),
        )
    }

    /**
     * Re-fetch the real tasks and then remove the given head-unit windows, without force-stopping the phone process. Called in the
     * background.
     */
    fun closeVehicleApps(packages: Set<String>): Boolean {
        val requested = packages.intersect(sessionApps().map { it.packageName }.toSet())
        if (requested.isEmpty()) return true
        val mediaPackages = taskIdsByDisplayPkg()[mediaDisplayId]?.keys.orEmpty()
        // A re-check right after removal would give false alarms: task removal lags, and navigation / music / chat apps may rebuild the
        // task right after being removed.
        // At most three rounds of "remove -> staged wait and re-check"; only those still present count as failures, and the package and
        // task id are written to the log.
        var after = vehicleAppTasks()
        for (round in 1..CLOSE_ROUNDS) {
            val ids = requested.flatMap { after[it].orEmpty() }.distinct()
            if (ids.isEmpty()) break
            ids.forEach { id ->
                check(id.all(Char::isDigit))
                removeTask(id)
            }
            val deadline = SystemClock.uptimeMillis() + CLOSE_VERIFY_MS
            do {
                Thread.sleep(CLOSE_POLL_MS)
                after = vehicleAppTasks()
            } while (requested.any { it in after } && SystemClock.uptimeMillis() < deadline)
            if (requested.none { it in after }) break
            logSink?.invoke(
                "RECENTS: still present after round $round of closing: " +
                    requested.filter { it in after }.joinToString { "$it${after[it]}" },
            )
        }
        val closed = requested.filter { it !in after }.toSet()
        synchronized(runningApps) { runningApps.removeAll { it.packageName in closed } }
        if (activeMapComponent?.packageName in closed) activeMapComponent = null
        if (activeMediaComponent?.packageName in closed) activeMediaComponent = null
        if (closed.any { it in mediaPackages }) closeMediaPane()
        notifyRunningAppsChanged()
        return requested.none { it in after }
    }

    fun showRecentApps(context: Context) {
        val display = gridDisplayId.takeIf { it >= 0 } ?: return
        dashboardUserDragging = false
        dashboardPageAnimating = false
        dashboardMode = false
        mapFullscreen = false
        notifyDashboard()
        composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
        composer?.requestActiveApp(display == appHiDisplayId)
        val options =
            android.app.ActivityOptions
                .makeBasic()
                .apply { launchDisplayId = display }
        context.startActivity(
            android.content.Intent(context, RecentAppsActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            options.toBundle(),
        )
    }

    private fun tasksOf(
        snapshot: Map<Int, Map<String, List<String>>>,
        pkg: String,
        displayId: Int,
    ): List<String> = snapshot[displayId]?.get(pkg).orEmpty()

    /**
     * Ensure the default map is on the map display: am start on an existing task = bring it to the foreground (state kept, navigation
     * uninterrupted),
     * no task = fresh launch. Called on a background thread.
     */
    @Synchronized
    private fun ensureMapTask() {
        if (mapDisplayId < 0) {
            // Diagnostics (2026-10-18 "map card does not render after reinstall"): this used to return silently, making it impossible to
            // tell
            // "map display not created" from "map display created but app not launched"
            logSink?.invoke("LAUNCHER: map display not created (mapDisplayId=-1) - skipping default map launch")
            return
        }
        val ctx =
            appContextRef ?: run {
                logSink?.invoke("LAUNCHER: appContext not ready - skipping default map launch")
                return
            }
        val component =
            activeMapComponent?.takeIf { ctx.packageManager.getLaunchIntentForPackage(it.packageName) != null }
                ?: defaultMapComponent(ctx) ?: run {
                logSink?.invoke("LAUNCHER: no map app found (within the whitelist) - map tile empty")
                return
            }
        // Without MULTIPLE_TASK: when the task is already on the map display this is equivalent to bringing it to the foreground
        val target = mapDisplayId
        if (activeMapComponent != component || taskIdsOnDisplayPkg(component.packageName, target).isEmpty()) {
            switchMap(component)
        }
    }

    /**
     * Launch / switch an app.
     * The target display is routed by the per-app supersampling preference (SupersamplePrefs):
     * - supersampling on (or auto-detected as a portrait app) -> supersampled display (1551x810 scaled back, the fixed-pixel top band
     * takes a small share)
     *  - supersampling off (landscape / map apps) -> native display (1:1 rendering, sharper, GPU cost 1/2.25)
     *
     * Behavior measured on this ROM (MagicOS): `am start --display X` on an existing task only "brings it to the foreground"
     * and does not land it on display X; `am task move` silently does nothing; `am task remove` does not exist, and tasks are cleared with
     * `am stack remove <taskId>` (RootTask id == taskId in this version). The reliable approach:
     *  1) the target display already has a task of the package -> plain launch brings it forward (reuse, keeping the task stack)
     * 2) the target display has none -> first clear the package's task on the **other app display** (to avoid dual instances after a
     * preference switch),
     *     then create a new instance on the target display with `-f 0x18000000` (NEW_TASK|MULTIPLE_TASK)
     *
     * Known limitation (measured 2026-10-10): when the app also has an instance on the phone side, the process baseline density stays at
     * the phone display (560dpi) and text on the head-unit display renders too large (~2.2x). The only real fix is force-restarting that
     * process,
     * but it would kill the instance the user is using on the phone (unacceptable) - so it is left as is.
     */
    fun launchApp(component: ComponentName): Boolean {
        if (appDisplayId < 0) return false
        // The "force landscape" override at session setup is written asynchronously, and am compat kills the target app immediately:
        // the queue must drain before launching, otherwise the app is killed by the override write right after it starts (seen as a crash
        // / falling back to the phone display).
        // The executor is single-thread FIFO, so normally the override was written long ago and this returns immediately.
        AppOrientationCompat.awaitPending()
        // Map app -> map display fullscreen (the task stays on the map display; tapping from the desktop grid also goes to the map display,
        // sharing the same task with the dashboard, so navigation state is not lost)
        if (isMapPackage(component.packageName) && mapDisplayId >= 0) {
            return launchMapOnMapDisplay(component)
        }
        val ctx = appContextRef
        val wantHi =
            ctx != null &&
                appHiDisplayId >= 0 &&
                SupersamplePrefs.shouldSupersample(ctx, component)
        val targetDisplay = if (wantHi) appHiDisplayId else appDisplayId
        // Chat mode: before a chat app launches, set the target display to tablet density (it starts directly in two-pane, without
        // flashing the phone version once); restore for other apps
        if (ctx != null) ChatMode.beforeLaunch(ctx, targetDisplay, component.packageName) { logSink?.invoke(it) }
        // Task queries share one dumpsys snapshot (previously onTarget + cleaning two displays ran up to 3 commands with 4s timeouts back
        // to back)
        val tasks = taskIdsByDisplayPkg()
        rememberCarTasks(tasks, listOf(appDisplayId, appHiDisplayId, mapDisplayId, mediaDisplayId).filter { it >= 0 })
        // A singleTask media entry reuses the task on the media display, and switching to the ordinary app display would only show blank.
        // Stay in the media area as the user set, and bring the dashboard to the foreground.
        val onMedia = mediaDisplayId >= 0 && tasksOf(tasks, component.packageName, mediaDisplayId).isNotEmpty()
        if (onMedia && ctx != null && isMusicApp(ctx, component.packageName)) {
            showDashboard()
            return launchMediaApp(component)
        }
        if (onMedia) {
            // A non-music app wrongly entered the media display: move it away and open it on the app display as usual, so it is no longer
            // brought back to the media area every time
            tasksOf(tasks, component.packageName, mediaDisplayId).forEach { taskId ->
                if (taskId.all(Char::isDigit)) removeTask(taskId)
            }
            if (activeMediaComponent?.packageName == component.packageName) {
                activeMediaComponent = null
                closeMediaPane()
            }
            logSink?.invoke("MEDIA: ${component.packageName} is not a music app, moving it from the media area back to the app display")
        }
        val onTarget = tasksOf(tasks, component.packageName, targetDisplay).isNotEmpty()
        if (!onTarget && !isMapPackage(component.packageName)) {
            // After a preference switch clean up the old task on the other app display (dual instances on different displays each keep
            // their own state and are confusing)
            val otherDisplay = if (targetDisplay == appHiDisplayId) appDisplayId else appHiDisplayId
            if (otherDisplay >= 0) {
                tasksOf(tasks, component.packageName, otherDisplay).forEach { taskId ->
                    removeTask(taskId)
                    Log.i(TAG, "cleaning old-display task pkg=${component.packageName} task=$taskId display=$otherDisplay")
                }
            }
        }
        var ok: Boolean
        val result: Shell.Result
        if (isMapPackage(component.packageName)) {
            ok = openMapTask(component, targetDisplay)
            if (ok) activeMapComponent = component
            result = Shell.Result(if (ok) 0 else -1, "map task")
        } else {
            val target = component.flattenToShortString()
            result =
                if (onTarget) {
                    Shell.exec("am start $LAUNCHER_INTENT -n ${target.asShellArgument()} --display $targetDisplay", timeoutMs = 8000)
                } else {
                    // MULTIPLE_TASK: even if the app has a task on another display, force a new instance on the target display
                    Shell.exec(
                        "am start $LAUNCHER_INTENT -n ${target.asShellArgument()} --display $targetDisplay -f 0x18000000",
                        timeoutMs = 8000,
                    )
                }
            ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
            if (!ok && onTarget) {
                // The reuse path failed (the task may have been moved away by the system): fall back to a fresh MULTIPLE_TASK launch
                val retry =
                    Shell.exec(
                        "am start $LAUNCHER_INTENT -n ${target.asShellArgument()} --display $targetDisplay -f 0x18000000",
                        timeoutMs = 8000,
                    )
                ok = retry.ok && retry.output.contains("Starting:", ignoreCase = true)
            }
        }
        logSink?.invoke(
            "LAUNCH(${component.packageName}): hi=$wantHi disp=$targetDisplay onTarget=$onTarget ok=$ok " +
                "out=${result.output.take(80)}",
        )
        if (ok) {
            recordSessionApp(component, targetDisplay)
            // Opening an ordinary app in dashboard / fullscreen map: leave the dashboard and hide the map layer,
            // otherwise the SPLIT band keeps drawing over the app (measured: the map tile covered the opened app)
            dashboardPageAnimating = false
            dashboardUserDragging = false
            if (dashboardMode || mapFullscreen) {
                dashboardMode = false
                mapFullscreen = false
                notifyDashboard()
                logSink?.invoke("LAUNCHER: leaving dashboard (an ordinary app was opened)")
            }
            composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
            // The compositor switches to the target display (supersampled display waits for its first frame; native display switches
            // instantly)
            composer?.requestActiveApp(targetDisplay == appHiDisplayId)
            composer?.animateAppEntry()
            // When the app is already running and was brought forward by the system with its old configuration: probe once after launch
            // whether it is still
            // letterboxed (the hint card only applies to apps on the "app landscape" list; other apps do not count)
            val ctx = appContextRef
            if (ctx != null) {
                LetterboxHintController.scheduleProbe(ctx, "app launch", displayIdHint = targetDisplay)
            }
        } else {
            Log.w(TAG, "launch failed: ${result.output.take(120)}")
        }
        return ok
    }

    // ICU Han-character -> pinyin transliteration (built into Android, no third-party pinyin library needed).
    // "Han-Latin" output carries tones; append Latin-ASCII to strip them (gao with tone -> gao).
    // Transliterator is not thread-safe, so all access takes a lock.
    private val hanLatinLock = Any()
    private val hanLatin by lazy {
        android.icu.text.Transliterator
            .getInstance("Han-Latin; Latin-ASCII")
    }

    private fun toLatin(text: String): String =
        synchronized(hanLatinLock) {
            runCatching { hanLatin.transliterate(text) }.getOrDefault(text)
        }

    /**
     * Sort key: full name to lowercase pinyin, keeping only letters and digits (digits < letters, so the # group naturally comes first).
     */
    private fun pinyinKey(label: String): String = toLatin(label).lowercase().filter { it.isLetterOrDigit() }

    /** Pinyin sort key (used by external lists such as settings pages for letter-grouped sorting). */
    fun sortKeyOf(label: String): String = pinyinKey(label)

    @Volatile
    private var appsCache: List<AppEntry>? = null

    /** App list cache (shared by the desktop and head-unit settings; invalidated on install/uninstall broadcasts). */
    private val appsScanLock = Any()

    fun cachedApps(context: Context): List<AppEntry> {
        appsCache?.let { return it }
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Cold app scan must run off the main thread" }
        return synchronized(appsScanLock) {
            appsCache ?: scanLaunchableApps(context.applicationContext).also { appsCache = it }
        }
    }

    /**
     * Reads only the shared cache and **never scans**: screens that must fetch the list on the main thread (settings pages etc.) use this,
     * and on a cache miss they scan asynchronously themselves (the synchronous fallback of cachedApps would put a whole-page app scan on
     * the UI thread).
     */
    fun peekCachedApps(): List<AppEntry>? = appsCache

    /** Feeds a result scanned by an external thread into the shared cache (so the settings page opens instantly after the desktop scan). */
    fun adoptAppsCache(apps: List<AppEntry>) {
        if (appsCache == null) appsCache = apps
    }

    fun invalidateAppsCache() {
        appsCache = null
    }

    fun launchableApps(context: Context): List<AppEntry> = cachedApps(context)

    /**
     * Scans all launchable apps (shared by the desktop grid and the settings page).
     *
     * Three places must be fault-tolerant (rechecked after the 2026-10-23 user report "some apps were not scanned"):
     *  1. **No more `take(200)`**: the old implementation put the cap **before** `sortedBy`, taking the first 200 in the
     *     order returned by `queryIntentActivities` - with many apps installed the later ones were simply lost,
     *     and which ones depended on the system's return order (looking like "a few random misses"). All are kept now.
     *  2. **try/catch per app**: `loadLabel`/`loadIcon` throw `Resources.NotFoundException` etc. for APKs with broken resources; the old
     *     implementation was a Sequence chain, and one bad app made
     *     `toList()` throw as a whole -> the desktop showed **no apps at all**. Now a single failure is skipped and counted.
     *  3. **Same-package dedup**: one package can have several launcher activities (measured on K40: 83
     *     activities map to only 82 packages, com.android.contacts has 4), and the old implementation displayed duplicates.
     *
     * Apps that still cannot be found belong to Android package visibility / multi-user layers, outside this function's control:
     *  - work profile, app clones / dual apps run under another userId,
     *    and `queryIntentActivities` returns only the current user's;
     *  - the ROM's "hide apps" feature makes apps invisible to this process;
     *  - apps with only a LEANBACK / custom category entry do not fall under LAUNCHER semantics.
     */
    private fun scanLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent =
            android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
        val resolved =
            try {
                pm.queryIntentActivities(intent, 0)
            } catch (error: Throwable) {
                logSink?.invoke("LAUNCHER: app scan failed - ${error.message} (the desktop will be empty)")
                return emptyList()
            }
        var skipped = 0
        val infos = resolved.mapNotNull { it.activityInfo }
        // Same-package multi-entry dedup (strategy in dedupeLauncherPackages, unit-tested): keep the first entry of each package
        val keepOrder = dedupeLauncherPackages(infos.map { it.packageName }, context.packageName)
        val firstInfoByPackage = LinkedHashMap<String, android.content.pm.ActivityInfo>(keepOrder.size)
        infos.forEach { info -> firstInfoByPackage.putIfAbsent(info.packageName, info) }
        // Pinyin key computed once per app (previously repeated transliteration per pair inside the comparator, 150 apps ~ thousands of
        // ICU calls)
        val apps =
            keepOrder
                .mapNotNull { firstInfoByPackage[it] }
                .mapNotNull { info ->
                    // A single app's loadLabel/loadIcon throwing must not bring down the whole list
                    runCatching {
                        val label = info.loadLabel(pm).toString().ifBlank { info.packageName }
                        AppEntry(
                            component = ComponentName(info.packageName, info.name),
                            label = label,
                            // Do not cache the oversized raw icon of each APK, to avoid hundreds of adaptive icons blowing up memory.
                            icon =
                                android.graphics.drawable.BitmapDrawable(
                                    context.resources,
                                    AppIcons.rounded(info.loadIcon(pm), 144),
                                ),
                        ) to pinyinKey(label)
                    }.onFailure {
                        skipped++
                    }.getOrNull()
                }
        if (skipped > 0 || resolved.size != apps.size) {
            // Diagnostic trace: when users report "missing apps", this single line is enough to tell
            // "the system only returned this many / same-package entries were merged" from "label/icon loading failed and was skipped"
            logSink?.invoke(
                "LAUNCHER: app scan system returned ${resolved.size} activities -> ${apps.size} apps" +
                    if (skipped > 0) " (skipped $skipped: label/icon load failed)" else " (same-package entries merged)",
            )
        }
        return apps.sortedBy { it.second }.map { it.first }
    }

    /** Letter index group key: digit -> "#", ASCII letter -> uppercase letter, Han character -> pinyin initial. */
    fun indexLetterOf(label: String): String {
        val first = label.firstOrNull() ?: return "#"
        return when {
            first.isDigit() -> "#"
            first.isLetter() && first.code < 128 -> first.uppercaseChar().toString()
            else -> {
                val latin = toLatin(first.toString()).lowercase()
                latin.firstOrNull { it in 'a'..'z' }?.uppercaseChar()?.toString() ?: "#"
            }
        }
    }

    // ---- Shizuku self-healing ----

    @Volatile
    private var watchdogActive = false
    private var watchdogThread: Thread? = null

    val taskRegex = Regex("""Task\{[0-9a-f]+ #(\d+) type=standard A=\d+:(\S+)""")

    /** Diagnostics log outlet (wired to the file log by MainActivity). */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** Application context (needed by Shizuku self-healing). */
    @Volatile
    private var appContextRef: android.content.Context? = null

    /**
     * Shizuku touch-injection self-healing: when the service disconnects, try to unfreeze and rebind (once every 4s; when healthy
     * the service is non-null and it is skipped at zero cost). Fixes the "must force-stop the app and reopen it" problem.
     *
     * Note: the former "task guard" (pulling an app back after it escaped to the main screen) was removed after real-device verification
     * on 2026-10-10:
     * under a trusted virtual display in-app jumps naturally stay on the head-unit display, opening on the phone side naturally follows to
     * the phone,
     * and reopening from the head-unit dock returns to the head-unit side - no pull-back intervention is needed any more.
     */
    private fun startShizukuHealer() {
        if (watchdogActive) return
        watchdogActive = true
        watchdogThread =
            Thread({
                while (watchdogActive && appDisplayId >= 0) {
                    try {
                        val ctx = appContextRef
                        if (ctx != null && CarInputClient.service == null) {
                            runCatching {
                                Shell.exec("am unfreeze moe.shizuku.privileged.api", timeoutMs = 2500)
                            }
                            CarInputClient
                                .ensure(ctx)
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        Thread.sleep(4000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "shizuku-healer").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * Make the app display **ignore the orientation requested by apps** (keeping the landscape/portrait form the virtual display was
     * created with).
     *
     * The command accepts only true/1/false/0 - passing `reset` prints
     * `Error: expecting true, 1, false, 0, but we get reset` and **writes only to stderr**,
     * while `Shell.exec` does not read stderr, so it silently had no effect (this method's predecessor was written that way:
     * the orientation policy never took effect, and Toutiao rotated the whole AppHi display to portrait). Hence read-back verification is
     * required after writing.
     */
    private fun applyIgnoreOrientationRequest(vararg ids: Int) {
        val targets = ids.filter { it >= 0 }
        if (targets.isEmpty()) return
        val failed = targets.filter { id -> !setIgnoreOrientationRequest(id, true) }
        if (failed.isEmpty()) {
            logSink?.invoke(
                "LAUNCHER: app displays ignore orientation requests ${targets.joinToString("/")} (guards against rotation)",
            )
        } else {
            logSink?.invoke(
                "LAUNCHER: WARNING failed to make app displays ignore orientation requests $failed (read-back is not true)" +
                    " - portrait apps may rotate the virtual display, causing a wrong picture ratio and touch drift",
            )
        }
    }

    /**
     * Sets the ignore-orientation policy of a single display.
     *
     * @param verify when true, read back to verify (**only for the session-setup path** - that path is already on a background thread,
     *  and a policy that does not take effect would rotate the whole display, so it must be confirmed). The cleanup path passes false, to
     * avoid triggering a chain of
     *   shell round trips (the root cause of the ANR, 2026-10-18).
     * @return always true when verify=false (only means the command was posted)
     */
    private fun setIgnoreOrientationRequest(
        displayId: Int,
        enabled: Boolean,
        verify: Boolean = true,
    ): Boolean {
        if (displayId < 0) return true
        val value = if (enabled) "true" else "false"
        Shell.exec("cmd window set-ignore-orientation-request -d $displayId $value", timeoutMs = 3_000)
        if (!verify) return true
        val readBack =
            Shell
                .exec("cmd window get-ignore-orientation-request -d $displayId", timeoutMs = 3_000)
                .output
        return readBack.contains(value)
    }

    data class AppEntry(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )
}

/**
 * **Dedup and filtering** of app scan results (the pure-function part of [VirtualLauncher.scanLaunchableApps]).
 *
 * Extracted for unit testing: the old implementation used `take(200)` **placed before sorting**, which on devices with many apps
 * cut off the tail in the system's return order, seen as "a few apps occasionally missing" (user report 2026-10-23).
 *
 * @param candidates packageName sequence from the system `queryIntentActivities` (original order kept)
 * @param selfPackage this app's package name (does not appear on the desktop)
 */
internal fun dedupeLauncherPackages(
    candidates: List<String>,
    selfPackage: String,
): List<String> {
    val seen = HashSet<String>(candidates.size)
    return candidates.filter { pkg -> pkg != selfPackage && seen.add(pkg) }
}
