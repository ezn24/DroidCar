package io.github.ezn24.droidcar.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dashboard card margin (stream pixels): a contract value shared by the map tile and the music card, referenced by four parties:
 * this class's SPLIT draw rectangle, the tile size VirtualLauncher uses to create displays, the GridActivity music-card layout, and
 * the TouchRouter tile touch hit area. Previously 16 was defined/hard-coded separately in four files, aligned only by comments,
 * so changing one place silently misaligned them (touch area / card layout / draw rectangle disagreed; converged in
 * the code-smell review, P1).
 */
val CARD_MARGIN_PX: Int get() = (16f * DashMetrics.scale).toInt().coerceAtLeast(4)

/**
 * Dashboard layout scale (2026-11-17: all stream-pixel constants used to be hard-coded for 1920x720, so at other resolutions
 * the margins/gap/corner proportions all changed). Scales proportionally with the **stream canvas short side 720px = 1.0**, so on a
 * 1920x720 head unit every value equals the old one. Set by VirtualLauncher from the canvas at session attach; 1.0
 * in unit tests when unset.
 */
object DashMetrics {
    const val BASE_SHORT_SIDE = 720f

    @Volatile
    var scale: Float = 1f
        private set

    fun configure(
        streamWidth: Int,
        streamHeight: Int,
    ) {
        scale = (minOf(streamWidth, streamHeight) / BASE_SHORT_SIDE).coerceIn(0.4f, 3f)
    }
}

/**
 * Dashboard split gap width (stream pixels): the **visual** gap between the map tile and the music card, with the same geometry contract as
 * [CARD_MARGIN_PX]: tile content-side edge = handle center - DASH_GAP_PX/2, music-card content-side
 * edge = handle center + DASH_GAP_PX/2 (derived uniformly by VirtualLauncher.dashSplitGeom).
 */
val DASH_GAP_PX: Float get() = 24f * DashMetrics.scale

/**
 * Split handle **touch** width (stream pixels): the handle View width and the TouchRouter sticky-routing zone use this value,
 * wider than [DASH_GAP_PX]: it overflows (TOUCH-GAP)/2 on each side onto the cards to enlarge the touch target
 * (the music-card-side handle sits on top of it; the map-side overflow is covered by the tile, invisible but touchable:
 * visuals and touch are layered; the fix for the 2026-10-22 user feedback "the gap is too wide").
 */
val DASH_HANDLE_TOUCH_PX: Float get() = 60f * DashMetrics.scale

/**
 * How long the 33ms heartbeat is kept after the picture goes still: lets the encoder rate control sharpen the just-
 * changed picture before dropping the rate.
 */
internal const val SETTLE_HEARTBEAT_MS = 1_000L

/** Floor heartbeat during long stillness (1 frame per second). */
internal const val IDLE_HEARTBEAT_MS = 1_000L

/**
 * Compose interval: with a new frame the target frame rate; within [SETTLE_HEARTBEAT_MS] of stillness 33ms; afterwards [IDLE_HEARTBEAT_MS].
 *
 * Basis (2026-11-20 WinPlay diagnostics against a real iPhone): when the picture is still an iPhone **sends no frames at all**
 * (0~0.4fps in a 10-second window, ~0Mbps), while our old version used a constant 30fps heartbeat + 3s periodic
 * I-frames and still produced 1~2Mbps at rest.
 * It does not drop to 0 so as to keep the 2026-10-09 "no frames -> receiver freezes" safeguard.
 */
internal fun composeIntervalMs(
    hasNew: Boolean,
    sinceChangeMs: Long,
    targetFps: Int,
): Long =
    when {
        hasNew -> 1000L / targetFps.coerceIn(1, 120)
        sinceChangeMs < SETTLE_HEARTBEAT_MS -> 33L
        else -> IDLE_HEARTBEAT_MS
    }

/**
 * Dual virtual-display GL compositor: dock display + app display (native / supersampled, switched by activity) ->
 * stitched into the encoder input Surface.
 *
 * Three SurfaceTextures serve as the outputs of the virtual displays; the GL thread polls new-frame flags and
 * redraws the composed picture on a new frame.
 * The app area draws only the **active** one of the two app displays at any time (switched via [requestActiveApp]; the supersampled display
 * waits for its first frame before the switch, avoiding a black flash). Both app displays keep being consumed (textures stay current).
 * After EGL/textures are ready the input Surfaces are handed to the caller via [onSurfacesReady] to call createVirtualDisplay.
 */
class DualDisplayComposer(
    private val output: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    /** true: the dock is a bottom bar (portrait head unit); false: the dock is a left strip (landscape head unit). */
    private val dockAtBottom: Boolean = false,
    /** Map on top, media below (portrait head unit / music strip); defaults to following the dock position (old behavior). */
    private val splitVertical: Boolean = dockAtBottom,
) {
    /**
     * Dashboard <-> desktop slide progress (0 = tile in place, 1 = fully slid out to the left), driven by GridActivity
     * during the slide.
     */
    /** The supersampled input spans the dock strip (VirtualLauncher.hiFullWidth): drawn from x = 0, under the glass dock. */
    @Volatile
    var hiFullWidth = false

    @Volatile
    var tileSlideFraction: Float = 0f
        set(value) {
            field = value
            synchronized(slideHistory) {
                slideHistoryAt[slideHistoryHead] = System.nanoTime()
                slideHistory[slideHistoryHead] = value
                slideHistoryHead = (slideHistoryHead + 1) % SLIDE_HISTORY
            }
        }

    /**
     * Recent (time, fraction) pairs of [tileSlideFraction]. The map tile is drawn by this compositor, but the music card and the
     * desktop next to it are pixels of the grid display that arrive one or two frames later; drawing the tile at the newest
     * fraction made the gap between map and music card breathe while a finger dragged the page (user report 2026-11-26). The tile
     * now uses the fraction that was current when the grid frame on screen was produced (SurfaceTexture timestamps use the same
     * monotonic clock as System.nanoTime).
     */
    private val slideHistory = FloatArray(SLIDE_HISTORY)
    private val slideHistoryAt = LongArray(SLIDE_HISTORY)
    private var slideHistoryHead = 0

    /** [tileSlideFraction] as it was at [frameNanos]; the newest value once it has rested for a while (the grid may stop drawing). */
    private fun slideFractionAt(frameNanos: Long): Float {
        synchronized(slideHistory) {
            val newestIndex = (slideHistoryHead - 1 + SLIDE_HISTORY) % SLIDE_HISTORY
            val newestAt = slideHistoryAt[newestIndex]
            if (frameNanos <= 0L || newestAt == 0L || System.nanoTime() - newestAt > SLIDE_SETTLE_NANOS) return tileSlideFraction
            var best = -1
            for (i in 0 until SLIDE_HISTORY) {
                val at = slideHistoryAt[i]
                if (at != 0L && at <= frameNanos && (best < 0 || at > slideHistoryAt[best])) best = i
            }
            return if (best >= 0) slideHistory[best] else tileSlideFraction
        }
    }

    /** Diagnostics sink: a 30 s line with each input's frame rate and the composed rate. */
    @Volatile
    var statsLog: ((String) -> Unit)? = null

    /**
     * Override for the map tile's handle-side edge (stream pixels, origin top-left; landscape = tile right edge x, portrait = tile
     * bottom edge y). When >0 it replaces the SPLIT formula value; VirtualLauncher writes it every frame while the split handle is dragged,
     * **changing only the draw rectangle (the texture is stretched whole), not the buffer or the virtual display**; only the commit on
     * release actually resizes. <=0 = use the default centerline formula. Semantics like [tileSlideFraction]: a continuous visual state.
     */
    @Volatile
    private var tileEdgeOverride = -1f

    fun setTileEdgeOverride(edgePx: Float) {
        tileEdgeOverride = edgePx
    }

    /**
     * Target frame rate (30/60, upper bound of the composition cadence; switched live from the "Display" settings).
     * With a new input frame it is throttled to 1000/targetFps; static pictures keep a 30fps floor heartbeat (the encoder must not starve).
     */
    @Volatile
    var targetFps: Int = 30

    companion object {
        private const val SLIDE_HISTORY = 16

        /** After this long without a new slide value the tile uses the newest one (the slide has ended). */
        private const val SLIDE_SETTLE_NANOS = 120_000_000L

        /** Glass backdrops are drawn and blurred at 1/4 of the output resolution (16x fewer pixels; the blur hides the loss). */
        private const val GLASS_DOWNSCALE = 4f

        /** One direction of a 9-tap Gaussian, folded into 5 bilinear fetches (offsets/weights for sigma ~2 texels). */
        private val BLUR_FRAGMENT =
            """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform vec2 uDir;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord) * 0.2270270270;
                c += texture2D(uTexture, vTexCoord + uDir * 1.3846153846) * 0.3162162162;
                c += texture2D(uTexture, vTexCoord - uDir * 1.3846153846) * 0.3162162162;
                c += texture2D(uTexture, vTexCoord + uDir * 3.2307692308) * 0.0702702703;
                c += texture2D(uTexture, vTexCoord - uDir * 3.2307692308) * 0.0702702703;
                gl_FragColor = c;
            }
            """.trimIndent()

        /** Blurred backdrop drawn into the frame, with a rounded clip in window coordinates. */
        private val GLASS_FRAGMENT =
            """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform vec4 uClipRect;
            uniform float uClipRadius;
            void main() {
                if (uClipRadius > 0.0) {
                    vec2 p = gl_FragCoord.xy;
                    vec2 r = vec2(uClipRadius);
                    vec2 c = clamp(p, uClipRect.xy + r, uClipRect.zw - r);
                    if (distance(p, c) > uClipRadius) discard;
                }
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
            """.trimIndent()

        private const val TAG = "DualComposer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** Upper bound for one map-region read (about 1.2 MB of RGBA); arrows and lane rows are far smaller. */
        private const val MAX_REGION_PIXELS = 300_000L

        /** Corner radius of the map card and media pane in stream pixels (also the knob focus ring's inner radius). */
        internal val TILE_CORNER_RADIUS: Float get() = 24f * DashMetrics.scale

        /** Identity texture matrix for the 2D texture program (the Bitmap vertices are already laid out for the target orientation). */
        private val IDENTITY_ST =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )
    }

    private val running = AtomicBoolean(false)
    private var glThread: Thread? = null

    /**
     * Readiness latch for start(): released after GL hands over the input Surfaces (note it must not wait for the
     * composition loop to exit)
     */
    @Volatile
    private var startLatch: CountDownLatch? = null

    /** Whether a supersampled app display was created (decided at start() from the sizes passed in). */
    @Volatile
    private var hasHiInput = false

    /** Whether a map display input was created (decided at start() from the sizes passed in). */
    @Volatile
    private var hasMapInput = false

    /** Whether the app area currently shows the supersampled display (true) or the native one (false). Touch routing follows it. */
    @Volatile
    private var activeAppHi = false

    /**
     * Target display awaiting a switch (true = supersampled; null = none pending); the switch waits for the target's
     * frame, avoiding a black/stale flash.
     */
    @Volatile
    private var pendingTargetHi: Boolean? = null

    @Volatile
    private var pendingBaseFrame = 0

    @Volatile
    private var pendingSince = 0L

    /**
     * Overlay cards (output-frame coordinates, origin top-left). Set by the caller after generating the bitmap on a background thread;
     * the GL thread uploads the texture and draws it on the top layer. Touch hit-testing uses the same coordinates,
     * see [OverlayCard.contains].
     */
    @Volatile
    var overlayCard: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
            wake()
        }

    /** Wake signal for the composition loop: woken at once on new input frames / overlay changes / start of a layout animation. */
    private val frameSignal = Object()

    private fun wake() {
        synchronized(frameSignal) { frameSignal.notifyAll() }
    }

    @Volatile
    private var overlayDirty = false

    /**
     * Pending screenshot request (read back after the GL thread's next composition and called back; the callback runs on
     * the GL thread, the caller switches threads itself).
     */
    @Volatile
    private var captureRequest: ((android.graphics.Bitmap?) -> Unit)? = null

    /**
     * Captures the whole composed car picture (the same frame as the stream: dock + desktop/app + map/media cards + overlays).
     * A screencap of each virtual display alone yields only fragments; the complete picture exists only here.
     */
    fun requestCapture(callback: (android.graphics.Bitmap?) -> Unit) {
        captureRequest = callback
        wake()
    }

    /** A pending read of one rectangle of the map display's current frame, see [requestMapRegion]. */
    private class RegionRequest(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val callback: (IntArray?) -> Unit,
    )

    private val regionRequests = java.util.concurrent.ConcurrentLinkedQueue<RegionRequest>()

    /**
     * Reads one rectangle (map display pixels, top-left origin) of the map display's current frame as ARGB, row by
     * row from the top. Used by the navigation reader for maneuver arrows and lane rows: the map frame is already a
     * texture here, so only that small block is rendered off-screen and read back, instead of the system taking a
     * whole-display screenshot (3.5 MB copy, about 1 per second at most). [callback] runs on the GL thread.
     * Returns false when there is no map input to read.
     */
    fun requestMapRegion(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        displayWidth: Int,
        displayHeight: Int,
        callback: (IntArray?) -> Unit,
    ): Boolean {
        if (!running.get() || mapInput?.hasContent != true) return false
        if (width <= 0 || height <= 0 || displayWidth <= 0 || displayHeight <= 0) return false
        if (width.toLong() * height > MAX_REGION_PIXELS) return false
        regionRequests += RegionRequest(left, top, width, height, displayWidth, displayHeight, callback)
        wake()
        return true
    }

    /** glReadPixels read-back of the output picture (GL origin is bottom-left, so flip vertically). */
    private fun readOutputFrame(): android.graphics.Bitmap? =
        runCatching {
            val buffer =
                java.nio.ByteBuffer
                    .allocateDirect(outWidth * outHeight * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, outWidth, outHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()
            val raw = android.graphics.Bitmap.createBitmap(outWidth, outHeight, android.graphics.Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
            android.graphics.Bitmap
                .createBitmap(raw, 0, 0, outWidth, outHeight, flip, false)
                .also { raw.recycle() }
        }.getOrNull()

    /**
     * Desktop page dots (output-frame coordinates). They live in GridActivity on the app display, but the dashboard's map/media
     * cards are drawn by GL above the app display and would cover them; so GridActivity snapshots the page dots into a bitmap and hands it
     * over here, to be drawn after the cards and before the dock. Whether to draw is decided by [VirtualLauncher.pageDotsOnTop],
     * the same criterion GridActivity uses to hide its own page dots (avoids two overlapping copies or none at all).
     */

    /** Page-dot show/hide fade (same pace as GridActivity's local page dots: in 120ms / out 220ms). */
    @Volatile
    private var pageDotsTarget = 0f

    @Volatile
    private var pageDotsFrom = 0f

    @Volatile
    private var pageDotsChangedAt = 0L

    fun setPageDotsVisible(visible: Boolean) {
        val target = if (visible) 1f else 0f
        if (target == pageDotsTarget) return
        val now = android.os.SystemClock.uptimeMillis()
        pageDotsFrom = pageDotsAlphaAt(now)
        pageDotsTarget = target
        pageDotsChangedAt = now
        wake()
    }

    private fun pageDotsDuration(): Long = if (pageDotsTarget > 0f) 120L else 220L

    private fun pageDotsAlphaAt(now: Long): Float {
        val t = ((now - pageDotsChangedAt).toFloat() / pageDotsDuration()).coerceIn(0f, 1f)
        return pageDotsFrom + (pageDotsTarget - pageDotsFrom) * t
    }

    private fun pageDotsFading(now: Long): Boolean = now - pageDotsChangedAt < pageDotsDuration()

    @Volatile
    var pageDots: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
            wake()
        }

    /** Assistive dot ([io.github.ezn24.droidcar.launcher.AssistiveDot]): its own texture slot, drawn on the top layer. */
    @Volatile
    var assistiveDot: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
            wake()
        }

    /** Knob focus frame ([io.github.ezn24.droidcar.input.KnobFocus]), drawn above the cards and below the assistive dot. */
    @Volatile
    var focusRing: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
            wake()
        }

    // ---- Frosted glass (layer model, user request 2026-11-26): wallpaper < map / music card / desktop apps < dock and
    // notifications (glass that blurs everything under them) < Gemini. The backdrop under a glass element is redrawn at
    // 1/GLASS_DOWNSCALE resolution into a small off-screen target and blurred there, so the cost is a few thousand pixels per
    // pass instead of a copy of the whole frame; the dock's backdrop is cached and only redrawn while something moves under it.

    /** Layer 0 of the glass backdrop: the car wallpaper (only ever sampled at reduced size, so a small bitmap is enough). */
    @Volatile
    var backdropWallpaper: android.graphics.Bitmap? = null
        set(value) {
            field = value
            backdropWallpaperCard = value?.let { OverlayCard(it, 0f, 0f, outWidth.toFloat(), outHeight.toFloat()) }
            glassGeneration++
            overlayDirty = true
            wake()
        }

    @Volatile
    private var backdropWallpaperCard: OverlayCard? = null

    /** The dock display renders a transparent background (DockActivity glass mode): draw the blurred backdrop under it. */
    @Volatile
    var dockGlass = false
        set(value) {
            field = value
            glassGeneration++
            overlayDirty = true
            wake()
        }

    /** Gaussian sigma of the glass in output pixels (VirtualLauncher.glassSigmaPx). */
    @Volatile
    var glassSigmaPx = 0f
        set(value) {
            field = value
            glassGeneration++
            overlayDirty = true
            wake()
        }

    /** Bumped whenever the dock's cached backdrop must be rebuilt (wallpaper, radius, mode). */
    @Volatile
    private var glassGeneration = 0

    private val backdropSlot = OverlaySlot()

    /** GL texture of one overlay bitmap and the card reference already uploaded (GL thread only). */
    private class OverlaySlot {
        var texture = 0
        var uploaded: OverlayCard? = null
    }

    private val overlaySlot = OverlaySlot()
    private val pageDotsSlot = OverlaySlot()
    private val assistiveDotSlot = OverlaySlot()
    private val focusRingSlot = OverlaySlot()

    /** Supersampled input (created on the GL thread; safe to read from other threads after start() returns). */
    @Volatile
    private var appHi: Input? = null

    /** Native app-display input (same; requestActiveApp reads its frame counter as the switch criterion). */
    @Volatile
    private var appInput: Input? = null

    /** Map-display input (created on the GL thread). */
    @Volatile
    private var mapInput: Input? = null

    @Volatile
    var onSurfacesReady: ((dock: Surface, app: Surface, appHi: Surface?, map: Surface?, media: Surface) -> Unit)? = null

    @Volatile
    var showMediaPane = false

    @Volatile
    private var mediaInput: Input? = null

    fun hasMediaContent(): Boolean = mediaInput?.hasContent == true

    /**
     * Voice-assistant layer: the transparent virtual display hosting the Gemini overlay (CarPlayAssist, same size as the app area).
     * When visible it is composited with premultiplied alpha above the map/media cards and below the dock; the
     * overlay itself is semi-transparent,
     * so the cards stay visible, and the glow and dialogue bar fill the whole app area.
     */
    @Volatile
    var assistLayerVisible = false

    @Volatile
    private var assistLayerInput: Input? = null

    /** Surface handed to the assistant virtual display (null when the compositor is not ready). */
    fun assistLayerSurface(): Surface? = assistLayerInput?.surface

    /** Voice-assistant overlay is open on the app display: pause drawing the map/media cards (they would cover the native overlay). */
    @Volatile
    var tilesSuppressed = false

    @Throws(IllegalStateException::class)
    fun start(
        dockWidthPx: Int,
        dockHeightPx: Int,
        appWidthPx: Int,
        appHeightPx: Int,
        appHiWidthPx: Int = 0,
        appHiHeightPx: Int = 0,
        mapWidthPx: Int = 0,
        mapHeightPx: Int = 0,
        mediaWidthPx: Int = 1,
        mediaHeightPx: Int = 1,
    ) {
        if (running.getAndSet(true)) return
        hasHiInput = appHiWidthPx > 0 && appHiHeightPx > 0
        hasMapInput = mapWidthPx > 0 && mapHeightPx > 0
        val ready = CountDownLatch(1)
        startLatch = ready
        var failure: IllegalStateException? = null
        glThread =
            Thread({
                try {
                    runGl(
                        dockWidthPx,
                        dockHeightPx,
                        appWidthPx,
                        appHeightPx,
                        appHiWidthPx,
                        appHiHeightPx,
                        mapWidthPx,
                        mapHeightPx,
                        mediaWidthPx,
                        mediaHeightPx,
                    )
                } catch (error: IllegalStateException) {
                    failure = error
                } finally {
                    ready.countDown()
                }
            }, "dual-composer").apply {
                isDaemon = true
                start()
            }
        ready.await()
        failure?.let {
            running.set(false)
            throw it
        }
    }

    /**
     * Switches the app area display: false = native display, true = supersampled display. Takes effect after the
     * target display produces a frame
     * (the old picture stays meanwhile): waits for "the target's frame counter to advance" (the new app's first frame); a 600ms fallback
     * (smooth switch when the target already has history content); a 2s timeout fallback (switch even if the target
     * is very slow to produce frames).
     * With no supersampled display (not created) it is always the native display.
     */
    fun requestActiveApp(hi: Boolean) {
        val target = if (!hasHiInput) false else hi
        if (activeAppHi == target && pendingTargetHi == null) return
        val targetInput = if (target) appHi else appInput
        pendingBaseFrame = targetInput?.frameCount ?: 0
        pendingSince = android.os.SystemClock.uptimeMillis()
        pendingTargetHi = target
        if (!hasHiInput) {
            // No supersampled display: no need to wait, takes effect directly
            activeAppHi = false
            pendingTargetHi = null
        }
    }

    /** The app area's currently active display (touch routing/coordinate conversion follow it). */
    fun isActiveAppHi(): Boolean = activeAppHi

    /**
     * Map display (tile) layout state: HIDDEN = normal desktop; SPLIT = the central band of the sampled picture is mapped to
     * the left half of the app area (map center ~ vehicle position, correct crop semantics); FULLSCREEN = the whole app area.
     * The map display is always created at full-screen size (stable app window, no task migration); split/fullscreen
     * only change the sampling geometry.
     */
    enum class TileLayout { HIDDEN, SPLIT, FULLSCREEN }

    @Volatile
    private var tileLayout = TileLayout.HIDDEN

    @Volatile
    private var lastPortraitTileDiag = 0L

    private fun launcherDashHint(): Boolean =
        try {
            VirtualLauncher.dashboardMode
        } catch (_: Throwable) {
            false
        }

    /** Switches the map tile layout. */
    fun setTileLayout(layout: TileLayout) {
        if (tileLayout == layout) return
        val now = android.os.SystemClock.uptimeMillis()
        val previous = layoutMotion
        layoutMotion =
            LayoutMotion(
                previous?.valueAt(now) ?: if (tileLayout == TileLayout.FULLSCREEN) 1f else 0f,
                if (layout == TileLayout.FULLSCREEN) 1f else 0f,
                now,
            )
        tileLayout = layout
        wake()
    }

    @Volatile
    private var layoutMotion: LayoutMotion? = null

    @Volatile
    private var appEntryAt = 0L

    @Volatile
    private var appEntryFromMedia = false

    fun animateAppEntry(fromMediaCard: Boolean = false) {
        appEntryFromMedia = fromMediaCard
        appEntryAt = android.os.SystemClock.uptimeMillis()
        wake()
    }

    fun isLayoutTransitioning(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        return layoutMotion?.isRunning(now) == true || now - appEntryAt < LayoutMotion.DURATION_MS
    }

    /**
     * Syncs the consumer buffer size after a dynamic map-display resize. VirtualDisplay.resize only changes the system-side
     * display device; the SurfaceTexture's defaultBufferSize stays locked at the old creation size, so the projection draws
     * the new-size content into the top-left of the old buffer (tile content shifted left, only a fraction of the
     * width, black on the right).
     */
    fun resizeMapInput(
        width: Int,
        height: Int,
    ) {
        mapInput?.surfaceTexture?.setDefaultBufferSize(width, height)
        mapBufferWidth = width
        mapBufferHeight = height
    }

    /**
     * The supersampled display changed width (full width under the dock for the desktop, the app area for other apps):
     * the consumer buffer follows it, and [hiFullWidth] moves where it is drawn from, both before the next frame.
     */
    fun resizeHiInput(
        width: Int,
        height: Int,
        fullWidth: Boolean,
    ) {
        appHi?.surfaceTexture?.setDefaultBufferSize(width, height)
        hiFullWidth = fullWidth
        wake()
    }

    /** Current map display buffer size (the alt screen crops it to its own aspect ratio). */
    @Volatile
    private var mapBufferWidth = 0

    @Volatile
    private var mapBufferHeight = 0

    fun stop() {
        if (!running.getAndSet(false)) return
        glThread?.interrupt()
        glThread = null
    }

    private class Input(
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
    ) {
        @Volatile
        var hasNewFrame = false

        /**
         * Whether at least one frame has been produced (inputs that have produced none take no part in drawing, avoiding
         * empty textures)
         */
        @Volatile
        var hasContent = false

        /** Cumulative frames produced (the "has produced a frame" criterion for switching the active display). */
        @Volatile
        var frameCount = 0
    }

    private fun runGl(
        dockW: Int,
        dockH: Int,
        appW: Int,
        appH: Int,
        hiW: Int,
        hiH: Int,
        mapW: Int,
        mapH: Int,
        mediaW: Int,
        mediaH: Int,
    ) {
        // ---- EGL ----
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        checkEgl(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
        checkEgl(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1), "eglInitialize")

        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        checkEgl(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0,
            "eglChooseConfig",
        )
        val context: EGLContext =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
        val outputSurface: EGLSurface =
            EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                output,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
        checkEgl(outputSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
        checkEgl(
            EGL14.eglMakeCurrent(display, outputSurface, outputSurface, context),
            "eglMakeCurrent",
        )

        // ---- Input textures ----
        fun newInput(
            width: Int,
            height: Int,
        ): Input {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val st = SurfaceTexture(tex[0])
            st.setDefaultBufferSize(width, height)
            val input = Input(tex[0], st, Surface(st))
            st.setOnFrameAvailableListener {
                input.hasNewFrame = true
                wake()
            }
            return input
        }

        val dock = newInput(dockW, dockH)
        val app = newInput(appW, appH).also { appInput = it }
        val hi = if (hasHiInput) newInput(hiW, hiH).also { appHi = it } else null
        val map = if (hasMapInput) newInput(mapW, mapH).also { mapInput = it } else null
        if (mapBufferWidth == 0) {
            mapBufferWidth = mapW
            mapBufferHeight = mapH
        }
        val media = newInput(mediaW, mediaH).also { mediaInput = it }
        val assistLayer = newInput(appW, appH).also { assistLayerInput = it }
        // ---- Shaders ----
        val program = buildProgram(oes = true)
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val uClipEnabled = GLES20.glGetUniformLocation(program, "uClipEnabled")
        val uClipRect = GLES20.glGetUniformLocation(program, "uClipRect")
        val uClipRadius = GLES20.glGetUniformLocation(program, "uClipRadius")
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val matrix = FloatArray(16)

        // ---- Map-region read-back (navigation reader): an off-screen RGBA target sized to the request ----
        var regionFbo = 0
        var regionTexture = 0
        var regionWidth = 0
        var regionHeight = 0
        var regionBuffer: java.nio.ByteBuffer? = null

        fun readMapRegion(
            input: Input,
            request: RegionRequest,
        ): IntArray? {
            val w = request.width
            val h = request.height
            if (regionFbo == 0) {
                val ids = IntArray(1)
                GLES20.glGenFramebuffers(1, ids, 0)
                regionFbo = ids[0]
                GLES20.glGenTextures(1, ids, 0)
                regionTexture = ids[0]
            }
            if (w != regionWidth || h != regionHeight) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, regionTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, regionFbo)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    regionTexture,
                    0,
                )
                regionWidth = w
                regionHeight = h
                regionBuffer =
                    java.nio.ByteBuffer
                        .allocateDirect(w * h * 4)
                        .order(java.nio.ByteOrder.nativeOrder())
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, regionFbo)
            try {
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return null
                GLES20.glViewport(0, 0, w, h)
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glUseProgram(program)
                GLES20.glUniform1i(uClipEnabled, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                input.surfaceTexture.getTransformMatrix(matrix)
                GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
                // The whole texture is the whole display; v = 0 is the picture's bottom (see drawInputUV)
                val u0 = request.left.toFloat() / request.displayWidth
                val u1 = (request.left + w).toFloat() / request.displayWidth
                val vTop = 1f - request.top.toFloat() / request.displayHeight
                val vBottom = 1f - (request.top + h).toFloat() / request.displayHeight
                val positions = vertexBuffer(posBuf, -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                val coords = vertexBuffer(texBuf, u0, vBottom, u1, vBottom, u0, vTop, u1, vTop)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
                GLES20.glEnableVertexAttribArray(aTexCoord)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                val buffer = regionBuffer ?: return null
                buffer.rewind()
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                buffer.rewind()
                val bytes = ByteArray(w * h * 4)
                buffer.get(bytes)
                // GL rows run bottom-up; callers want top-down ARGB
                return IntArray(w * h) { i ->
                    val x = i % w
                    val y = i / w
                    val o = ((h - 1 - y) * w + x) * 4
                    (0xFF shl 24) or ((bytes[o].toInt() and 255) shl 16) or ((bytes[o + 1].toInt() and 255) shl 8) or
                        (bytes[o + 2].toInt() and 255)
                }
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, outWidth, outHeight)
            }
        }

        // ---- Overlay-card shaders (plain 2D textures; the Bitmap is pre-laid-out by Canvas) ----
        val overlayProgram = buildProgram(oes = false)
        val oaPos = GLES20.glGetAttribLocation(overlayProgram, "aPos")
        val oaTexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        val ouSTMatrix = GLES20.glGetUniformLocation(overlayProgram, "uSTMatrix")
        val ouTexture = GLES20.glGetUniformLocation(overlayProgram, "uTexture")
        val ouAlpha = GLES20.glGetUniformLocation(overlayProgram, "uAlpha")

        /** Uploads/updates an overlay-card texture (GL thread only; re-uploads only when the card reference changes). */
        fun syncOverlayTexture(
            slot: OverlaySlot,
            source: OverlayCard?,
        ): OverlayCard? {
            val card =
                source ?: run {
                    slot.uploaded = null
                    return null
                }
            if (slot.texture != 0 && card.bitmap === slot.uploaded?.bitmap) {
                slot.uploaded = card
                return card
            }
            if (slot.texture == 0) {
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                slot.texture = tex[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, card.bitmap, 0)
            slot.uploaded = card
            return card
        }

        /**
         * Draws an overlay card. [left]/[top]/[right]/[bottom] are output-frame coordinates (origin top-left),
         * converted here to GL bottom-left origin; the Bitmap's first row is at the top, so the texture v axis is
         * opposite to the vertex direction.
         */
        fun drawOverlay(
            slot: OverlaySlot,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            alpha: Float = 1f,
        ) {
            GLES20.glUseProgram(overlayProgram)
            GLES20.glUniform1i(ouTexture, 0)
            GLES20.glUniform1f(ouAlpha, alpha)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.texture)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)

            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val yTop = outHeight - top
            val yBottom = outHeight - bottom
            val positions =
                vertexBuffer(
                    posBuf,
                    px(left),
                    py(yBottom),
                    px(right),
                    py(yBottom),
                    px(left),
                    py(yTop),
                    px(right),
                    py(yTop),
                )
            // Bitmap v=0 is at the top: bottom vertices take v=1, top vertices take v=0
            val coords = vertexBuffer(texBuf, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(oaPos)
            GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(oaTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisable(GLES20.GL_BLEND)
            // Restore the main shader state (later frames keep using the OES program)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTexture, 0)
        }

        /** false while drawing the low-resolution glass backdrop: the rounded clip compares gl_FragCoord in output pixels. */
        var clipAllowed = true

        /** [u0]/[u1]: horizontal texture sampling range (used to sample the central band of the buffer in map split mode). */
        fun drawInputUV(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
            u0: Float = 0f,
            u1: Float = 1f,
            cornerRadius: Float = 0f,
        ) {
            input.surfaceTexture.getTransformMatrix(matrix)
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
            // Rounded-corner clipping: the shader compares gl_FragCoord (GL bottom-left origin) with uClipRect,
            // so vertex coordinates are passed directly (here y is already GL coordinates). It used to be flipped to screen coordinates;
            // for a full-height tile (landscape) the symmetric range happened to coincide and went unnoticed; for a
            // portrait half-height tile the vertex area
            // and clip area did not intersect at all and the whole tile was discarded (root cause of "map card invisible")
            if (cornerRadius > 0f && clipAllowed) {
                GLES20.glUniform1i(uClipEnabled, 1)
                GLES20.glUniform4f(uClipRect, x0, y0, x1, y1)
                GLES20.glUniform1f(uClipRadius, cornerRadius)
            } else {
                GLES20.glUniform1i(uClipEnabled, 0)
            }

            // Pixel coordinates -> clip coordinates (GL bottom-left origin; the texture flip is handled by uSTMatrix, do not flip again)
            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val positions =
                vertexBuffer(
                    posBuf,
                    px(x0),
                    py(y0),
                    px(x1),
                    py(y0),
                    px(x0),
                    py(y1),
                    px(x1),
                    py(y1),
                )
            val coords = vertexBuffer(texBuf, u0, 0f, u1, 0f, u0, 1f, u1, 1f)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun drawInput(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
        ) = drawInputUV(input, x0, x1, y0, y1, u0 = 0f, u1 = 1f)

        // ---- Alternate (cluster) screen: the whole map display, fitted into the receiver's view area, plus our turn
        // card when the receiver asks for one (see AltScreenOutput). Fitted, not cropped: cropping cut Google Maps'
        // own turn banner off the top (2026-11-23).
        var altBound: AltScreenOutput.Target? = null
        var altSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var altLastAt = 0L
        var altFailed: AltScreenOutput.Target? = null
        var altCardTexture = 0
        var altCardUploaded: android.graphics.Bitmap? = null

        /** Quad in alt-screen pixels (top-left origin) -> GL clip space of a [w] x [h] target. */
        fun altQuad(
            x: Float,
            y: Float,
            qw: Float,
            qh: Float,
            w: Int,
            h: Int,
        ): FloatBuffer {
            val x0 = x / w * 2f - 1f
            val x1 = (x + qw) / w * 2f - 1f
            val y0 = (h - (y + qh)) / h * 2f - 1f
            val y1 = (h - y) / h * 2f - 1f
            return vertexBuffer(posBuf, x0, y0, x1, y0, x0, y1, x1, y1)
        }

        fun renderAlt(input: Input?) {
            val want = AltScreenOutput.target
            if (want !== altBound) {
                if (altSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, altSurface) }
                altSurface = EGL14.EGL_NO_SURFACE
                altBound = want
                if (want != null && want !== altFailed) {
                    altSurface =
                        runCatching {
                            EGL14.eglCreateWindowSurface(display, configs[0], want.surface, intArrayOf(EGL14.EGL_NONE), 0)
                        }.getOrDefault(EGL14.EGL_NO_SURFACE)
                    if (altSurface == EGL14.EGL_NO_SURFACE) altFailed = want
                }
            }
            val target = altBound ?: return
            if (altSurface == EGL14.EGL_NO_SURFACE) return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - altLastAt < 1000L / target.fps.coerceIn(1, 60)) return
            altLastAt = now
            if (!EGL14.eglMakeCurrent(display, altSurface, altSurface, context)) return
            val w = target.width
            val h = target.height
            try {
                GLES20.glViewport(0, 0, w, h)
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val area = AltScreenOutput.viewArea ?: intArrayOf(0, 0, w, h)
                if (AltScreenOutput.mode != AltScreenOutput.Mode.CARD && input != null && input.hasContent) {
                    GLES20.glUseProgram(program)
                    GLES20.glUniform1i(uClipEnabled, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    input.surfaceTexture.getTransformMatrix(matrix)
                    GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
                    // Fit the whole map into the area, centred
                    val srcAspect = mapBufferWidth.toFloat() / mapBufferHeight.coerceAtLeast(1)
                    val areaAspect = area[2].toFloat() / area[3].coerceAtLeast(1)
                    val qw = if (srcAspect < areaAspect) area[3] * srcAspect else area[2].toFloat()
                    val qh = if (srcAspect < areaAspect) area[3].toFloat() else area[2] / srcAspect
                    val positions = altQuad(area[0] + (area[2] - qw) / 2f, area[1] + (area[3] - qh) / 2f, qw, qh, w, h)
                    val coords = vertexBuffer(texBuf, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
                    GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
                    GLES20.glEnableVertexAttribArray(aPos)
                    GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
                    GLES20.glEnableVertexAttribArray(aTexCoord)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
                val card = AltScreenOutput.card
                val rect = AltScreenOutput.cardRect
                if (card != null && rect != null) {
                    if (altCardTexture == 0) {
                        val ids = IntArray(1)
                        GLES20.glGenTextures(1, ids, 0)
                        altCardTexture = ids[0]
                    }
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, altCardTexture)
                    if (card !== altCardUploaded) {
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, card, 0)
                        altCardUploaded = card
                    }
                    GLES20.glUseProgram(overlayProgram)
                    GLES20.glUniform1i(ouTexture, 0)
                    GLES20.glUniform1f(ouAlpha, 1f)
                    GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    val positions =
                        altQuad(rect[0].toFloat(), rect[1].toFloat(), rect[2].toFloat(), rect[3].toFloat(), w, h)
                    val coords = vertexBuffer(texBuf, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
                    GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, positions)
                    GLES20.glEnableVertexAttribArray(oaPos)
                    GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
                    GLES20.glEnableVertexAttribArray(oaTexCoord)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisable(GLES20.GL_BLEND)
                }
                EGL14.eglSwapBuffers(display, altSurface)
            } finally {
                EGL14.eglMakeCurrent(display, outputSurface, outputSurface, context)
                GLES20.glViewport(0, 0, outWidth, outHeight)
                GLES20.glUseProgram(program)
                GLES20.glUniform1i(uTexture, 0)
            }
        }

        // ---- Glass: blur and glass programs (plain 2D textures) ----
        val blurProgram = buildProgramFrom(BLUR_FRAGMENT)
        val bPos = GLES20.glGetAttribLocation(blurProgram, "aPos")
        val bTexCoord = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
        val bST = GLES20.glGetUniformLocation(blurProgram, "uSTMatrix")
        val bTexture = GLES20.glGetUniformLocation(blurProgram, "uTexture")
        val bDir = GLES20.glGetUniformLocation(blurProgram, "uDir")
        val glassProgram = buildProgramFrom(GLASS_FRAGMENT)
        val gPos = GLES20.glGetAttribLocation(glassProgram, "aPos")
        val gTexCoord = GLES20.glGetAttribLocation(glassProgram, "aTexCoord")
        val gST = GLES20.glGetUniformLocation(glassProgram, "uSTMatrix")
        val gTexture = GLES20.glGetUniformLocation(glassProgram, "uTexture")
        val gClipRect = GLES20.glGetUniformLocation(glassProgram, "uClipRect")
        val gClipRadius = GLES20.glGetUniformLocation(glassProgram, "uClipRadius")

        /** Two small ping-pong targets holding one element's blurred backdrop (GL thread only). */
        class Backdrop {
            val fbo = IntArray(2)
            val tex = IntArray(2)
            var w = 0
            var h = 0
            var generation = -1
            var dynamicLast = false
        }

        fun ensureTargets(
            b: Backdrop,
            w: Int,
            h: Int,
        ) {
            if (b.fbo[0] == 0) {
                GLES20.glGenFramebuffers(2, b.fbo, 0)
                GLES20.glGenTextures(2, b.tex, 0)
            }
            if (b.w == w && b.h == h) return
            for (i in 0..1) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex[i])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, b.fbo[i])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, b.tex[i], 0)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            b.w = w
            b.h = h
        }

        /** Full-target quad for the 2D blur program (texture v = 0 at the bottom, like the FBO). */
        fun drawFullQuad(
            aPosLoc: Int,
            aTexLoc: Int,
        ) {
            val positions = vertexBuffer(posBuf, -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val coords = vertexBuffer(texBuf, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        // Set by composeFrame before any backdrop pass: draws the content layers (map, music card, apps) in output coordinates
        var drawContent: () -> Unit = {}

        /**
         * Redraws the layers under the output rectangle [left]..[bottom] (top-left origin) at 1/GLASS_DOWNSCALE into [b] and blurs
         * them there. The viewport is the whole output scaled down and shifted so the same drawing code lands on the region.
         */
        fun renderBackdrop(
            b: Backdrop,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ) {
            val w = ((right - left) / GLASS_DOWNSCALE).toInt().coerceAtLeast(1)
            val h = ((bottom - top) / GLASS_DOWNSCALE).toInt().coerceAtLeast(1)
            ensureTargets(b, w, h)
            val sx = w / (right - left)
            val sy = h / (bottom - top)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, b.fbo[0])
            GLES20.glViewport(
                (-left * sx).toInt(),
                (-(outHeight - bottom) * sy).toInt(),
                (outWidth * sx).toInt().coerceAtLeast(1),
                (outHeight * sy).toInt().coerceAtLeast(1),
            )
            GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // Layer 0: wallpaper, then the content layers
            syncOverlayTexture(backdropSlot, backdropWallpaperCard)?.let {
                drawOverlay(backdropSlot, 0f, 0f, outWidth.toFloat(), outHeight.toFloat())
            }
            clipAllowed = false
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            drawContent()
            clipAllowed = true
            // Separable Gaussian, horizontal into [1] then vertical back into [0]. One round of the 9-tap kernel at step s has
            // sigma 1.414 x s texels, n rounds sqrt(n) times that; steps above 3 texels would show the taps, so a wide blur takes
            // more rounds
            val lowSigma = glassSigmaPx / GLASS_DOWNSCALE
            val rounds =
                kotlin.math
                    .ceil((lowSigma / (1.4142f * 3f)).let { it * it })
                    .toInt()
                    .coerceIn(1, 4)
            val step = (lowSigma / (1.4142f * kotlin.math.sqrt(rounds.toFloat()))).coerceIn(0.3f, 3f)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(blurProgram)
            GLES20.glUniform1i(bTexture, 0)
            GLES20.glUniformMatrix4fv(bST, 1, false, IDENTITY_ST, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            repeat(rounds) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, b.fbo[1])
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex[0])
                GLES20.glUniform2f(bDir, step / w, 0f)
                drawFullQuad(bPos, bTexCoord)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, b.fbo[0])
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex[1])
                GLES20.glUniform2f(bDir, 0f, step / h)
                drawFullQuad(bPos, bTexCoord)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTexture, 0)
        }

        /** Draws [b]'s blurred backdrop over the output rectangle (top-left origin), rounded by [radius] (0 = square). */
        fun drawGlass(
            b: Backdrop,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
        ) {
            GLES20.glUseProgram(glassProgram)
            GLES20.glUniform1i(gTexture, 0)
            GLES20.glUniformMatrix4fv(gST, 1, false, IDENTITY_ST, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex[0])
            // Rounded clip in GL window coordinates (origin bottom-left)
            GLES20.glUniform4f(gClipRect, left, outHeight - bottom, right, outHeight - top)
            GLES20.glUniform1f(gClipRadius, radius)
            GLES20.glDisable(GLES20.GL_BLEND)

            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val positions =
                vertexBuffer(
                    posBuf,
                    px(left),
                    py(outHeight - bottom),
                    px(right),
                    py(outHeight - bottom),
                    px(left),
                    py(outHeight - top),
                    px(right),
                    py(outHeight - top),
                )
            val coords = vertexBuffer(texBuf, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            GLES20.glVertexAttribPointer(gPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(gPos)
            GLES20.glVertexAttribPointer(gTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(gTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTexture, 0)
        }

        val dockBackdrop = Backdrop()

        // Last desktop frame the dock's backdrop was built from (a new frame may have moved something under the dock)
        var hiFrameSeen = -1
        val cardBackdrop = Backdrop()

        fun composeFrame(fullRedraw: Boolean) {
            // Pending app-display switch: switch once the target produces a frame (frame counter advances / 600ms
            // fallback if it has content / 2s timeout fallback)
            pendingTargetHi?.let { targetHi ->
                val nowMs = android.os.SystemClock.uptimeMillis()
                val target = if (targetHi) hi else app
                val advanced = target != null && target.frameCount > pendingBaseFrame
                if (advanced ||
                    (target?.hasContent == true && nowMs - pendingSince >= 600L) ||
                    nowMs - pendingSince >= 2_000L
                ) {
                    activeAppHi = targetHi
                    pendingTargetHi = null
                }
            }
            // The window framebuffer is cleared only after the glass backdrops are drawn (see below)
            // Call updateTexImage only for inputs with a new frame; inputs that already have content keep the old
            // texture (picture persists).
            // Both app displays are consumed continuously (the inactive one also stays current, so no stale-frame flash when switching).
            if (dock.hasNewFrame) {
                dock.hasNewFrame = false
                dock.hasContent = true
                dock.frameCount++
                dock.surfaceTexture.updateTexImage()
            }
            if (app.hasNewFrame) {
                app.hasNewFrame = false
                app.hasContent = true
                app.frameCount++
                app.surfaceTexture.updateTexImage()
            }
            hi?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            map?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            if (media.hasNewFrame) {
                media.hasNewFrame = false
                media.hasContent = true
                media.frameCount++
                media.surfaceTexture.updateTexImage()
            }
            if (assistLayer.hasNewFrame) {
                assistLayer.hasNewFrame = false
                assistLayer.hasContent = true
                assistLayer.frameCount++
                assistLayer.surfaceTexture.updateTexImage()
            }
            val activeInput = if (activeAppHi) hi else app
            val nowMs = android.os.SystemClock.uptimeMillis()
            // Tile slide matched to the grid frame being shown (see slideHistory)
            val slideFraction = slideFractionAt(activeInput?.surfaceTexture?.timestamp ?: 0L)
            val appLeft = if (dockAtBottom) 0f else dockW.toFloat()
            val appBottom = if (dockAtBottom) dockH.toFloat() else 0f
            // Content layers (apps / desktop, map tile, media pane): drawn into the frame, and again at low resolution under glass
            drawContent = {
                if (activeInput?.hasContent == true) {
                    // The full-width supersampled display starts under the dock
                    val appLeft = if (hiFullWidth && activeInput === hi) 0f else appLeft
                    val entry = LayoutMotion.progress(nowMs - appEntryAt)
                    val inset = (1f - entry) * (outWidth - appLeft) * 0.035f
                    val mediaStart = VirtualLauncher.dashSplitPos + DASH_GAP_PX / 2f
                    val originLeft = if (splitVertical) appLeft + CARD_MARGIN_PX else mediaStart
                    val originTop = if (splitVertical) mediaStart else CARD_MARGIN_PX.toFloat()

                    fun expand(
                        from: Float,
                        to: Float,
                    ) = from + (to - from) * entry
                    if (appEntryFromMedia && entry < 1f) {
                        drawInputUV(
                            activeInput,
                            expand(originLeft, appLeft),
                            expand(outWidth - CARD_MARGIN_PX.toFloat(), outWidth.toFloat()),
                            expand(appBottom + CARD_MARGIN_PX, appBottom),
                            expand(outHeight - originTop, outHeight.toFloat()),
                            cornerRadius = (1f - entry) * TILE_CORNER_RADIUS,
                        )
                    } else {
                        drawInputUV(
                            activeInput,
                            appLeft + inset,
                            outWidth - inset,
                            appBottom + inset,
                            outHeight - inset,
                            cornerRadius = (1f - entry) * TILE_CORNER_RADIUS,
                        )
                    }
                }
                if (map?.hasContent == true && tileLayout != TileLayout.HIDDEN && !tilesSuppressed) {
                    val expanded =
                        layoutMotion?.valueAt(nowMs)
                            ?: if (tileLayout == TileLayout.FULLSCREEN) 1f else 0f
                    val margin = CARD_MARGIN_PX.toFloat()
                    val slide = slideFraction * (outWidth - if (hiFullWidth) 0f else appLeft) * (1f - expanded)
                    val mainLen =
                        if (splitVertical) {
                            outHeight - if (dockAtBottom) dockH else 0
                        } else {
                            outWidth - if (dockAtBottom) 0 else dockW
                        }
                    val tileEdge =
                        if (tileEdgeOverride > 0f) {
                            tileEdgeOverride
                        } else {
                            (if (splitVertical) 0f else appLeft) +
                                VirtualLauncher.dashSplitGeom(mainLen, VirtualLauncher.currentDashSplitFraction()).tileEdge
                        }
                    val mapPane = VirtualLauncher.mapPaneBounds()
                    val splitLeft = mapPane.left - slide
                    val splitRight = mapPane.right - slide
                    val splitBottom = outHeight - mapPane.bottom

                    fun mix(
                        from: Float,
                        to: Float,
                    ) = from + (to - from) * expanded
                    drawInputUV(
                        map!!,
                        mix(splitLeft, appLeft),
                        mix(splitRight, outWidth.toFloat()),
                        mix(splitBottom, appBottom),
                        mix(outHeight - mapPane.top, outHeight.toFloat()),
                        cornerRadius = TILE_CORNER_RADIUS * (1f - expanded),
                    )
                }
                if (showMediaPane &&
                    !tilesSuppressed &&
                    media.hasContent &&
                    tileLayout == TileLayout.SPLIT &&
                    (VirtualLauncher.dashboardMode || VirtualLauncher.dashboardPageAnimating)
                ) {
                    val pane = VirtualLauncher.mediaAppBounds()
                    val slide = slideFraction * (outWidth - if (hiFullWidth) 0f else appLeft)
                    drawInputUV(
                        media,
                        pane.left - slide,
                        pane.right - slide,
                        outHeight - pane.bottom,
                        outHeight - pane.top,
                        cornerRadius = TILE_CORNER_RADIUS,
                    )
                }
            }
            // ---- Glass backdrops first, into their small off-screen targets, before the window framebuffer is touched:
            // switching render targets in the middle of the frame made the tiled GPU store the half-drawn 1920x720 frame
            // to memory and load it back (twice per frame with a banner), which cost the 60 fps the glass build lost
            // (user report 2026-11-26). Rendered here, the frame below is one uninterrupted pass.
            val dockTop = if (dockAtBottom) (outHeight - dockH).toFloat() else 0f
            val dockRight = if (dockAtBottom) outWidth.toFloat() else dockW.toFloat()
            if (dock.hasContent && dockGlass) {
                // The cached backdrop holds while nothing moves under the dock (only the wallpaper is there at rest): it is
                // redrawn while a sliding tile passes under it, once more after it settles, and when the full-width desktop
                // drew a new frame (pages and the music card pass under the dock)
                val hiFrames = hi?.frameCount ?: 0
                val dynamic =
                    (tileLayout != TileLayout.HIDDEN && slideFraction > 0.001f) ||
                        (hiFullWidth && activeAppHi && hi?.hasContent == true && hiFrames != hiFrameSeen)
                hiFrameSeen = hiFrames
                if (dynamic || dockBackdrop.dynamicLast || dockBackdrop.generation != glassGeneration) {
                    renderBackdrop(dockBackdrop, 0f, dockTop, dockRight, outHeight.toFloat())
                    dockBackdrop.generation = glassGeneration
                }
                dockBackdrop.dynamicLast = dynamic
            }
            val glassCard = overlayCard?.takeIf { it.glassRadius > 0f && it.alpha > 0.01f }
            glassCard?.let { card ->
                val inset = card.glassInset
                renderBackdrop(cardBackdrop, card.left + inset, card.top + inset, card.right - inset, card.bottom - inset)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawContent()
            // Page dots sit above the map/media cards (fixed, not sliding with the tile, same position as GridActivity's original)
            val dotsAlpha = pageDotsAlphaAt(nowMs)
            if (VirtualLauncher.pageDotsOnTop() && dotsAlpha > 0.001f) {
                syncOverlayTexture(pageDotsSlot, pageDots)?.let { dots ->
                    drawOverlay(pageDotsSlot, dots.left, dots.top, dots.right, dots.bottom, dotsAlpha)
                }
            }
            // ---- Glass layer: dock and notifications blur everything drawn so far ----
            if (dock.hasContent) {
                if (dockGlass) {
                    drawGlass(dockBackdrop, 0f, dockTop, dockRight, outHeight.toFloat(), 0f)
                    // The dock renders with a transparent background: premultiplied blend over the glass
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                }
                if (dockAtBottom) {
                    drawInput(dock, 0f, outWidth.toFloat(), 0f, dockH.toFloat())
                } else {
                    drawInput(dock, 0f, dockW.toFloat(), 0f, outHeight.toFloat())
                }
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            // Overlay cards (notification banner, hints): card coordinates are output-frame coordinates (origin top-left)
            // Banner glass: its backdrop was rendered fresh above (the map or an app may move under the banner)
            glassCard?.let { source ->
                val inset = source.glassInset
                drawGlass(
                    cardBackdrop,
                    source.left + inset,
                    source.top + inset,
                    source.right - inset,
                    source.bottom - inset,
                    source.glassRadius,
                )
            }
            syncOverlayTexture(overlaySlot, overlayCard)?.let { card ->
                drawOverlay(overlaySlot, card.left, card.top, card.right, card.bottom, card.alpha)
            }
            // ---- Top layer: the voice assistant (Gemini) over everything; its blank pixels have alpha 0 ----
            if (assistLayerVisible && assistLayer.hasContent) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                drawInput(assistLayer, appLeft, outWidth.toFloat(), appBottom, outHeight.toFloat())
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            syncOverlayTexture(focusRingSlot, focusRing)?.let { ring ->
                drawOverlay(focusRingSlot, ring.left, ring.top, ring.right, ring.bottom, ring.alpha)
            }
            syncOverlayTexture(assistiveDotSlot, assistiveDot)?.let { dot ->
                drawOverlay(assistiveDotSlot, dot.left, dot.top, dot.right, dot.bottom, dot.alpha)
            }
            // Screenshot request: read back the frame just composed (the picture pushed to the car), before the buffer swap
            captureRequest?.let { callback ->
                captureRequest = null
                callback(readOutputFrame())
            }
            // Map-region reads for the navigation reader (off-screen; the composed picture is untouched)
            while (true) {
                val request = regionRequests.poll() ?: break
                val pixels = map?.takeIf { it.hasContent }?.let { runCatching { readMapRegion(it, request) }.getOrNull() }
                runCatching { request.callback(pixels) }
            }
            if (fullRedraw ||
                overlayDirty ||
                dock.hasContent ||
                app.hasContent ||
                hi?.hasContent == true ||
                map?.hasContent == true
            ) {
                EGL14.eglSwapBuffers(display, outputSurface)
            }
            overlayDirty = false
            runCatching { renderAlt(map) }
        }

        // Initial black frame (clear only, do not touch empty textures) + hand over the input Surfaces + release start()
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, outputSurface)
        AltScreenOutput.wake = { wake() }
        onSurfacesReady?.invoke(dock.surface, app.surface, hi?.surface, map?.surface, media.surface)
        startLatch?.countDown()

        // ---- Composition loop ----
        // Cadence see [composeIntervalMs]: with a new frame the target frame rate; after stillness first keep a 33ms
        // heartbeat for 1 second (so the encoder
        // rate control sharpens the just-changed picture), then drop to 1 frame per second. The old version kept a
        // 33ms heartbeat at rest, and on 2026-10-09
        // having no frames at all made the receiver "freeze the picture" (touch arrived but no frame reflected it);
        // the 1-second floor heartbeat
        // keeps that safeguard, so a missed picture change appears at most 1 second late.
        try {
            var lastComposeAt = System.currentTimeMillis()
            var lastChangeAt = lastComposeAt
            var statsAt = lastComposeAt
            var statsComposed = 0L
            var statsBase = longArrayOf(0, 0, 0, 0, 0)

            fun inputCounts() =
                longArrayOf(
                    dock.frameCount.toLong(),
                    app.frameCount.toLong(),
                    (hi?.frameCount ?: 0).toLong(),
                    (map?.frameCount ?: 0).toLong(),
                    media.frameCount.toLong(),
                )
            while (running.get()) {
                val statsNow = System.currentTimeMillis()
                if (statsNow - statsAt >= 30_000L) {
                    val counts = inputCounts()
                    val seconds = (statsNow - statsAt) / 1000f
                    val rates = counts.indices.map { "%.1f".format(java.util.Locale.ROOT, (counts[it] - statsBase[it]) / seconds) }
                    statsLog?.invoke(
                        "COMPOSER: input fps dock=${rates[0]} app=${rates[1]} appHi=${rates[2]} map=${rates[3]} media=${rates[4]}" +
                            " composed=${"%.1f".format(java.util.Locale.ROOT, statsComposed / seconds)}",
                    )
                    statsAt = statsNow
                    statsBase = counts
                    statsComposed = 0
                }
                // The fast lane (60fps throttle) is driven only by inputs that "will be drawn into the output picture":
                // dock + the current active app display (app/hi, one of the two) + the map display while the tile is visible.
                // Amap runs on the map display and portrait apps on the supersampled display; looking only at dock/native would miss them,
                // and the 60fps tier would be locked at 30fps by the 33ms still heartbeat.
                // New frames of inactive displays are not counted (the picture does not contain them): frames are still
                // consumed in composeFrame (textures stay current, avoiding a backlogged SurfaceTexture queue that stalls background apps),
                // but no GPU/bandwidth is burned on content nobody sees.
                val hasNew =
                    isLayoutTransitioning() ||
                        pageDotsFading(android.os.SystemClock.uptimeMillis()) ||
                        (assistLayerVisible && assistLayerInput?.hasNewFrame == true) ||
                        overlayDirty ||
                        dock.hasNewFrame ||
                        (if (activeAppHi) appHi else app)?.hasNewFrame == true ||
                        (tileLayout != TileLayout.HIDDEN && mapInput?.hasNewFrame == true) ||
                        // The alt (cluster) screen shows the map even while the dashboard hides it
                        (AltScreenOutput.target != null && mapInput?.hasNewFrame == true) ||
                        (showMediaPane && tileLayout == TileLayout.SPLIT && mediaInput?.hasNewFrame == true)
                val now = System.currentTimeMillis()
                if (hasNew) lastChangeAt = now
                val intervalMs = composeIntervalMs(hasNew, now - lastChangeAt, targetFps)
                val waitMs = intervalMs - (now - lastComposeAt)
                if (waitMs <= 0) {
                    statsComposed++
                    composeFrame(fullRedraw = false)
                    lastComposeAt = now
                } else {
                    // Wait for the next frame point, or be woken early by a new frame/state change (the old fixed sleep(8) polling:
                    // woke ~125 times per second at rest for nothing, and a new frame was noticed up to 8ms late)
                    synchronized(frameSignal) { frameSignal.wait(waitMs) }
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            for (b in arrayOf(dockBackdrop, cardBackdrop)) {
                if (b.fbo[0] != 0) {
                    runCatching { GLES20.glDeleteFramebuffers(2, b.fbo, 0) }
                    runCatching { GLES20.glDeleteTextures(2, b.tex, 0) }
                }
            }
            for (slot in arrayOf(overlaySlot, pageDotsSlot, assistiveDotSlot, focusRingSlot, backdropSlot)) {
                if (slot.texture != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(slot.texture), 0) }
                    slot.texture = 0
                }
                slot.uploaded = null
            }
            runCatching { media.surface.release() }
            runCatching { media.surfaceTexture.release() }
            mediaInput = null
            assistLayerInput = null
            runCatching { assistLayer.surface.release() }
            runCatching { assistLayer.surfaceTexture.release() }
            runCatching { dock.surface.release() }
            runCatching { app.surface.release() }
            runCatching { dock.surfaceTexture.release() }
            runCatching { app.surfaceTexture.release() }
            map?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            hi?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            AltScreenOutput.wake = null
            if (altSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, altSurface) }
            runCatching { EGL14.eglDestroySurface(display, outputSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    /** 2D program with the shared vertex shader and [fragment] (glass and blur passes). */
    private fun buildProgramFrom(fragment: String): Int = buildProgram(oes = false, fragmentOverride = fragment)

    /** [oes] = true: external texture sampling (SurfaceTexture); false: plain 2D sampling (wallpaper bitmap). */
    private fun buildProgram(
        oes: Boolean,
        fragmentOverride: String? = null,
    ): Int {
        val fragment =
            if (fragmentOverride != null) {
                fragmentOverride
            } else if (oes) {
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                uniform int uClipEnabled;
                uniform vec4 uClipRect;
                uniform float uClipRadius;
                void main() {
                    if (uClipEnabled == 1) {
                        vec2 p = gl_FragCoord.xy;
                        vec2 half2 = vec2(uClipRadius);
                        vec2 c = clamp(p, uClipRect.xy + half2, uClipRect.zw - half2);
                        if (distance(p, c) > uClipRadius) discard;
                    }
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
                """.trimIndent()
            } else {
                """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                uniform float uAlpha;
                void main() {
                    // The bitmap is premultiplied alpha: all four channels must be scaled together for a correct overall fade
                    gl_FragColor = texture2D(uTexture, vTexCoord) * uAlpha;
                }
                """.trimIndent()
            }
        val vertex =
            """
            attribute vec2 aPos;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
            """.trimIndent()

        fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            // A compile failure must leave a trace and throw: silently continuing = black screen with no way to
            // diagnose (EGL has checkEgl, the GLSL layer was missed before)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "GLSL compile failed (type=$type): ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("GLSL compile type=$type")
            }
            return shader
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        return program
    }

    /**
     * Each draw's vertex/texture coordinates are written into these two **reused** direct buffers (GL thread only). Previously every
     * draw called allocateDirect for two new buffers: 6~10 direct-memory allocations per frame, reclaimed by the Cleaner,
     * a steady GC pressure on the composition thread. glDrawArrays reads the client array at call time, so reuse is safe.
     */
    private val posBuf = floatBuffer(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val texBuf = floatBuffer(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    private fun vertexBuffer(
        buffer: FloatBuffer,
        vararg values: Float,
    ): FloatBuffer {
        buffer.clear()
        buffer.put(values)
        buffer.position(0)
        return buffer
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun checkEgl(
        ok: Boolean,
        stage: String,
    ) {
        if (!ok) {
            Log.e(TAG, "EGL failed: $stage")
            throw IllegalStateException("EGL $stage")
        }
    }
}
