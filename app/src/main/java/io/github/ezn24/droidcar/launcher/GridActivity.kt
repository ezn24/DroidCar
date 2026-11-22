package io.github.ezn24.droidcar.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.ezn24.droidcar.R
import io.github.ezn24.droidcar.call.CallAvatar
import io.github.ezn24.droidcar.call.CallControl
import io.github.ezn24.droidcar.call.CallState
import io.github.ezn24.droidcar.media.MediaSessionsState
import io.github.ezn24.droidcar.render.CARD_MARGIN_PX
import io.github.ezn24.droidcar.render.DASH_HANDLE_TOUCH_PX
import io.github.ezn24.droidcar.ui.UiStrings
import io.github.ezn24.droidcar.ui.WallpaperStore
import io.github.ezn24.droidcar.util.Bg

/**
 * CarPlay main-area desktop: pure black background + paged icon grid (landscape 4x3 CarPlay spec; portrait adapts by cell
 * aspect ratio, up to 6 rows, see computeCellMetrics) + page dots at the bottom.
 * The layout is declared in res/layout (activity_grid / item_grid_page / item_app_cell);
 * code only does the dynamic cell-size computation and data binding. No letter index (matching the CarPlay home screen).
 *
 * By default only map apps are shown (DesktopApps default selection), and the first cell is the fixed "head-unit settings" entry;
 * the user can tick the apps to show in settings, and returning to the desktop (onResume) refreshes immediately.
 */
class GridActivity : AppCompatActivity() {
    /** Desktop cells: dashboard page (page 0) / fixed settings entry / ordinary app. */
    private sealed interface Cell {
        object Dashboard : Cell

        object Pinned : Cell

        /** Background-apps entry (moved from the dock to the desktop 2026-11-17, freeing the dock for running apps). */
        object Recents : Cell

        /** The car's own home button (/info oemIcons): shows the car UI, the session keeps running. */
        object Oem : Cell

        data class App(
            val entry: VirtualLauncher.AppEntry,
        ) : Cell
    }

    /** /info arrives after the launcher's first frame: rebuild the grid when the car's home button appears or goes. */
    private val oemListener: () -> Unit = { runOnUiThread { if (!isFinishing && !isDestroyed) reload() } }

    private var pages: List<List<Cell>> = emptyList()
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var iconSizePx = 0

    /** Current row count (landscape 3; portrait adapts by cell aspect ratio, page capacity = COLUMNS x rows). */
    private var rows = ROWS_MIN
    private var reloadAfterPageSwitch = false
    private var appScanInFlight = false

    /** App list cache (rescanning packageManager on every resume would slow returning to the desktop - scan only on first load). */
    private var appsCache: List<VirtualLauncher.AppEntry>? = null

    /** Wallpaper background layer (slice aligned to the full-stream canvas; swapped in the background when the version changes). */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** Wallpaper change listener (WallpaperStore main-thread callback; decoding is moved to a background thread by this class). */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** Dashboard-mode panel (in split mode shown in the right half of the app area; the left half is covered by the map tile). */
    private var dashboardView: android.view.View? = null

    /** Dashboard mode change (notified by VirtualLauncher). */
    private val dashboardListener = { dashboard: Boolean ->
        runOnUiThread {
            applyDashboardMode(dashboard)
            syncDotsLayer()
        }
    }

    /** Media state listener (MediaSessionsState main-thread callback). */
    private val mediaStateListener = {
        runOnUiThread { renderMediaCard() }
    }

    private var shownCallKey: String? = null
    private val callAppLabels = HashMap<String, String?>()

    /** Caller photo per call notification key (decoded once in the background; the icon may come from another app). */
    private var callAvatarKey: String? = null
    private var callAvatar: Bitmap? = null
    private var callViewName: String? = null
    private var callAvatarIcon: android.graphics.drawable.Icon? = null
    private var callAvatarViews: android.widget.RemoteViews? = null
    private var shownCallPhase: CallState.Phase? = null

    /** Call state (notification listener thread): a new ringing call brings the card side of the dashboard forward. */
    private val callStateListener = {
        runOnUiThread {
            val call = CallState.current
            if (call == null) {
                CallControl.onCallsGone(this)
                Bg.run("call-pane") { VirtualLauncher.closeCallAppInMediaPane() }
            } else if (call.phase == CallState.Phase.RINGING && (call.key != shownCallKey || shownCallPhase != CallState.Phase.RINGING)) {
                Bg.run("call-card") { VirtualLauncher.showDashboardForCall() }
            }
            shownCallKey = call?.key
            shownCallPhase = call?.phase
            renderMediaCard()
        }
    }

    /** Full-canvas bitmap ready (called back after WallpaperStore's first build / rebuild). */
    private val canvasReadyListener = { _: Long -> runOnUiThread { refreshDashWallpaper() } }

    /** Card visuals (scrim alpha / blur) - consistent with the dock (DockPrefs). */
    private val dockPrefsListener = {
        runOnUiThread {
            applyDashCardChrome()
            refreshDashWallpaper()
        }
    }

    /** Map layout change (fullscreen / split switching changes the music card's wallpaper slice region).
     * Named field (do not inline an anonymous lambda): only then can onDestroy remove it symmetrically, otherwise desktop rebuilds leak
     * cumulatively. */
    private val mapLayoutListener: (Boolean) -> Unit =
        { _ ->
            runOnUiThread {
                layoutDashboardMedia()
                refreshDashWallpaper()
                syncDotsLayer()
            }
        }

    /**
     * Applies card visuals: scrim alpha and wallpaper blur radius (RenderEffect; the radius is scaled 1.5x with the grid display
     * density to match the dock visuals).
     */
    private fun applyDashCardChrome() {
        val panel = dashboardView ?: return
        val scrim = panel.findViewById<View>(R.id.dashScrim)
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        scrim.background =
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(alpha, 0, 0, 0),
            )
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper)
        val percent = DockPrefs.blur(this)
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * resources.displayMetrics.density
            wallpaper.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    /**
     * Music card wallpaper: the crop of the "music card region" in the full-canvas slice (same mapping as the fullscreen wallpaper,
     * region = right of the map card to the card's right margin).
     */
    private fun refreshDashWallpaper() {
        val panel = dashboardView ?: return
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper) ?: return
        val params = wallpaper.layoutParams ?: return
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        Bg.run("wallpaper-dash") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        canvasWidth,
                        canvasHeight,
                    )
                }.getOrNull() ?: return@run
            val pane = VirtualLauncher.mediaPaneBounds()
            val cropX = (pane.left * scale).toInt().coerceIn(0, bitmap.width - 2)
            val cropY = (pane.top * scale).toInt().coerceIn(0, bitmap.height - 2)
            val cropW = (pane.width * scale).toInt().coerceIn(1, bitmap.width - cropX)
            val cropH = (pane.height * scale).toInt().coerceIn(1, bitmap.height - cropY)

            runOnUiThread {
                wallpaper.setImageBitmap(
                    Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH),
                )
                // Re-apply RenderEffect once the bitmap is ready (view effects must still take effect after the bitmap updates)
                applyDashCardChrome()
            }
        }
    }

    /** Music card progress self-refresh (1s; re-scheduled at the end of rendering, position extrapolated from anchor + speed). */
    private val mediaTick =
        object : Runnable {
            override fun run() {
                renderMediaCard()
            }
        }

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** mm:ss (h:mm:ss beyond 1 hour). */
    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1_000
        val h = totalSec / 3_600
        val m = totalSec % 3_600 / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }

    /**
     * Music card rendering: cover / title / artist / progress / play-pause button (playing = pause icon, paused = play icon).
     *
     * The copy must distinguish three causes of "no song title", otherwise it misleads:
     *  1. A session is playing but **notification access was not granted** -> metadata is always null (the session and playback state
     *     are still visible). Show "cannot read playback info" and where to enable it;
     *  2. A session exists but is paused / has no metadata -> "not playing";
     *  3. No session at all -> also "not playing".
     *
     * Title and subtitle must be decided as a pair: there was a self-contradictory display of the title "now playing" (static layout text)
     * with the subtitle
     * "not playing" (seen in a real screenshot); the static text was changed to "media", and here both lines are
     * written together by state, so the two never come from different sources.
     */
    private fun renderMediaCard() {
        val panel = dashboardView ?: return
        val call = CallState.current
        panel.findViewById<View>(R.id.dashMediaContent)?.visibility = if (call == null) View.VISIBLE else View.GONE
        panel.findViewById<View>(R.id.dashCallContent)?.visibility = if (call == null) View.GONE else View.VISIBLE
        if (call != null) {
            renderCallCard(panel, call)
            return
        }
        val now = MediaSessionsState.nowPlaying
        val title = now?.title?.takeIf { it.isNotBlank() }
        val artist = now?.artist?.takeIf { it.isNotBlank() }
        // A session exists but no metadata is readable + not authorized -> a permission problem, not "not playing"
        val missingDueToPermission =
            now != null && title == null && !MediaSessionsState.accessGranted
        panel.findViewById<TextView>(R.id.dashMediaTitle).text =
            title
                ?: if (missingDueToPermission) {
                    UiStrings.text(
                        R.string.ui_114,
                    )
                } else {
                    UiStrings
                        .text(R.string.ui_112)
                }
        panel.findViewById<TextView>(R.id.dashMediaArtist).text =
            artist
                ?: when {
                    missingDueToPermission ->
                        UiStrings
                            .text(R.string.ui_115)
                    now?.playing == true ->
                        UiStrings
                            .text(R.string.ui_116)
                    else -> ""
                }
        panel.findViewById<ImageView>(R.id.dashPlayPause).setImageResource(
            if (now?.playing == true) R.drawable.ic_media_pause else R.drawable.ic_media_play,
        )
        panel.findViewById<TextView>(R.id.dashMediaTime)?.let { time ->
            if (now != null && now.durationMs > 0) {
                time.visibility = View.VISIBLE
                time.text =
                    "${formatTime(now.displayPositionAt(android.os.SystemClock.elapsedRealtime()))} / " +
                    formatTime(now.durationMs)
            } else {
                time.visibility = View.GONE
            }
        }
        panel.findViewById<ProgressBar>(R.id.dashMediaProgress)?.let { bar ->
            if (now != null && now.durationMs > 0) {
                bar.visibility = View.VISIBLE
                bar.progress =
                    (now.displayPositionAt(android.os.SystemClock.elapsedRealtime()) * 1000 / now.durationMs)
                        .toInt()
                        .coerceIn(0, 1000)
            } else {
                bar.visibility = View.GONE
            }
        }
        panel.findViewById<ImageView>(R.id.dashCover)?.let { cover ->
            val art = now?.art
            if (art != null) {
                cover.setImageBitmap(art)
                cover.visibility = View.VISIBLE
                // Cover corner clipping (outline set once)
                if (cover.clipToOutline != true) {
                    cover.clipToOutline = true
                    val dp = resources.displayMetrics.density
                    cover.outlineProvider =
                        object : android.view.ViewOutlineProvider() {
                            override fun getOutline(
                                view: View,
                                outline: android.graphics.Outline,
                            ) {
                                outline.setRoundRect(
                                    0,
                                    0,
                                    view.width,
                                    view.height,
                                    10 * dp,
                                )
                            }
                        }
                }
                cover.setOnClickListener { openMediaApp() }
            } else {
                // The placeholder does not disappear (INVISIBLE): content does not jump up and down while the cover loads asynchronously /
                // between tracks
                cover.visibility = View.INVISIBLE
                cover.setOnClickListener { openMediaApp() }
            }
        }
        // The progress display needs to tick every second: re-schedule at the end of rendering (de-dup: remove first)
        tickHandler.removeCallbacks(mediaTick)
        tickHandler.postDelayed(mediaTick, 1_000)
    }

    /**
     * Call card: caller, state and duration, with decline / answer while ringing and mute / hang up once connected.
     * The buttons act through the call notification's own actions (CallControl).
     */
    private fun renderCallCard(
        panel: View,
        call: CallState.Call,
    ) {
        val ringing = call.phase == CallState.Phase.RINGING
        // App label from another APK's resources: loaded once in the background (no label loading on the main thread)
        val appLabel = callAppLabels[call.pkg]
        if (!callAppLabels.containsKey(call.pkg)) {
            callAppLabels[call.pkg] = null
            Bg.run("call-label") {
                val label =
                    runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(call.pkg, 0)).toString()
                    }.getOrNull()
                runOnUiThread {
                    callAppLabels[call.pkg] = label
                    renderMediaCard()
                }
            }
        }
        val status = UiStrings.text(if (ringing) R.string.call_incoming else R.string.call_active)
        panel.findViewById<TextView>(R.id.dashCallStatus).text = listOfNotNull(status, appLabel).joinToString(" · ")
        // WeChat's title is empty or the app name; its custom view carries the caller (read with the photo)
        val caller = call.caller?.takeIf { it.isNotBlank() && it != appLabel }
        panel.findViewById<TextView>(R.id.dashCallName).text =
            caller ?: callViewName.takeIf { callAvatarKey == call.key } ?: UiStrings.text(R.string.call_unknown)
        panel.findViewById<TextView>(R.id.dashCallTime).apply {
            if (!ringing && call.startedAtWallMs > 0) {
                visibility = View.VISIBLE
                text = formatTime((System.currentTimeMillis() - call.startedAtWallMs).coerceAtLeast(0))
            } else {
                visibility = View.GONE
            }
        }
        renderCallAvatar(panel.findViewById(R.id.dashCallAvatar), call)
        val left = panel.findViewById<ImageView>(R.id.dashCallLeft)
        val right = panel.findViewById<ImageView>(R.id.dashCallRight)
        if (ringing) {
            left.setImageResource(R.drawable.ic_call_end)
            left.setBackgroundResource(R.drawable.bg_call_end)
            left.imageTintList = null
            left.contentDescription = UiStrings.text(R.string.call_decline)
            left.setOnClickListener { CallControl.decline(this) }
            right.setImageResource(R.drawable.ic_call_answer)
            right.setBackgroundResource(R.drawable.bg_call_answer)
            right.contentDescription = UiStrings.text(R.string.call_answer)
            right.setOnClickListener { CallControl.answer(this) }
        } else {
            val muted = CallControl.isMuted(this)
            left.setImageResource(if (muted) R.drawable.ic_call_mic_off else R.drawable.ic_call_mic)
            left.setBackgroundResource(if (muted) R.drawable.bg_call_active else R.drawable.bg_call_neutral)
            // Active (muted) = light disc with a dark icon, like a pressed toggle
            left.imageTintList =
                if (muted) {
                    android.content.res.ColorStateList
                        .valueOf(0xFF1C1C1E.toInt())
                } else {
                    null
                }
            left.contentDescription = UiStrings.text(if (muted) R.string.call_unmute else R.string.call_mute)
            left.setOnClickListener {
                CallControl.toggleMute(this)
                renderMediaCard()
            }
            right.setImageResource(R.drawable.ic_call_end)
            right.setBackgroundResource(R.drawable.bg_call_end)
            right.contentDescription = UiStrings.text(R.string.call_hang_up)
            right.setOnClickListener { CallControl.hangUp(this) }
        }
        tickHandler.removeCallbacks(mediaTick)
        tickHandler.postDelayed(mediaTick, 1_000)
    }

    private fun renderCallAvatar(
        view: ImageView,
        call: CallState.Call,
    ) {
        // WeChat re-posts the same ringing notification once its avatar is loaded: reload on new content, not only a new call
        val newCall = callAvatarKey != call.key
        if (newCall || callAvatarIcon !== call.avatar || callAvatarViews !== call.views) {
            callAvatarKey = call.key
            callAvatarIcon = call.avatar
            callAvatarViews = call.views
            if (newCall) {
                callAvatar = null
                callViewName = null
            }
            val icon = call.avatar
            val views = call.views
            val appLabel = callAppLabels[call.pkg]
            if (icon != null || views != null) {
                Bg.run("call-avatar") {
                    var viewName: String? = null
                    val bitmap =
                        runCatching {
                            val extracted =
                                views?.let {
                                    runCatching { CallAvatar.fromViews(this@GridActivity, it, appLabel) }
                                        .onFailure {
                                            VirtualLauncher.logSink?.invoke(
                                                "CALL: call view could not be read (${it.javaClass.simpleName}: ${it.message})",
                                            )
                                        }.getOrNull()
                                }
                            viewName = extracted?.name
                            extracted?.candidates?.takeIf { it.isNotEmpty() }?.let {
                                VirtualLauncher.logSink?.invoke("CALL: call view images: ${it.joinToString(" | ")}")
                            }
                            val drawable =
                                icon?.loadDrawable(this@GridActivity)
                                    ?: extracted?.photo?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
                            drawable?.let { drawable ->
                                val size = 192
                                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { out ->
                                    drawable.setBounds(0, 0, size, size)
                                    drawable.draw(android.graphics.Canvas(out))
                                }
                            }
                        }.onFailure {
                            VirtualLauncher.logSink?.invoke(
                                "CALL: caller photo could not be loaded (${it.javaClass.simpleName}: ${it.message})",
                            )
                        }.getOrNull()
                    // Presence only: the name itself is personal data and stays out of the log
                    VirtualLauncher.logSink?.invoke(
                        "CALL: caller photo=${bitmap != null} name from view=${viewName != null}",
                    )
                    runOnUiThread {
                        if (callAvatarKey == call.key && (bitmap != null || viewName != null)) {
                            // A later re-post without a picture keeps the one already shown
                            if (bitmap != null) callAvatar = bitmap
                            if (viewName != null) callViewName = viewName
                            renderMediaCard()
                        }
                    }
                }
            }
        }
        val bitmap = callAvatar
        if (bitmap == null) {
            view.visibility = View.GONE
            return
        }
        view.setImageBitmap(bitmap)
        view.visibility = View.VISIBLE
        if (!view.clipToOutline) {
            view.clipToOutline = true
            view.outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        v: View,
                        outline: android.graphics.Outline,
                    ) {
                        outline.setOval(0, 0, v.width, v.height)
                    }
                }
        }
    }

    /** Dashboard mode -> pager position sync (0 = dashboard, 1+ = desktop; do nothing if already at the target). */
    private fun applyDashboardMode(dashboard: Boolean) {
        val pager =
            this.pager ?: run {
                VirtualLauncher.finishDashboardPageSwitch(dashboard)
                return
            }
        if ((pager.adapter?.itemCount ?: 0) < 2) {
            VirtualLauncher.finishDashboardPageSwitch(dashboard)
            return
        }
        val target = if (dashboard) 0 else 1
        if (!VirtualLauncher.dashboardPageAnimating) {
            // App launch only syncs the hidden desktop position; it does not start the Home animation or rewrite the GL display layer.
            pager.setCurrentItem(target, false)
            pager.isUserInputEnabled = !dashboard
            return
        }
        pager.isUserInputEnabled = false
        if (pager.currentItem == target) {
            VirtualLauncher.finishDashboardPageSwitch(dashboard)
            pager.isUserInputEnabled = !dashboard
            return
        }
        // Only the Dock initiates mode switches; the native ViewPager2 drives the whole-page slide and the GL sync progress.
        pager.setCurrentItem(target, true)
    }

    private fun layoutDashboardMedia() {
        val panel = dashboardView ?: return
        val scale = VirtualLauncher.gridScale()
        val pane = VirtualLauncher.mediaPaneBounds()
        val appLeft = if (VirtualLauncher.dockAtBottom) 0 else VirtualLauncher.dockWidthPx
        panel.layoutParams =
            android.widget.FrameLayout
                .LayoutParams(
                    (pane.width * scale).toInt(),
                    (pane.height * scale).toInt(),
                ).apply {
                    leftMargin = ((pane.left - appLeft) * scale).toInt()
                    topMargin = (pane.top * scale).toInt()
                }
    }

    private fun openMediaApp() {
        // During a call the card belongs to the call: a tap outside its buttons opens the call app's call screen
        CallState.current?.let { call ->
            val app = applicationContext
            Bg.run("open-call-app") { VirtualLauncher.openCallAppInMediaPane(app, call.pkg, call.open) }
            return
        }
        // If what is playing is not a music app (Telegram voice, recorder playback), do not open it; fall back to the user's default music
        // app
        val active =
            MediaSessionsState.nowPlaying?.pkg?.takeIf { VirtualLauncher.isMusicApp(this, it) }
        val preferred =
            getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("defaultMediaPackage", null)
        val candidates =
            listOfNotNull(active, preferred) +
                listOf(
                    "com.spotify.music",
                    "com.google.android.apps.youtube.music",
                    "com.apple.android.music",
                )
        val component =
            candidates.distinct().firstNotNullOfOrNull {
                packageManager.getLaunchIntentForPackage(it)?.component
            }
        if (component != null) {
            Bg.run("open-media") { VirtualLauncher.launchMediaApp(component) }
        } else {
            val app = applicationContext
            Bg.run("media-apps-scan") {
                val choices = VirtualLauncher.cachedApps(app)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    androidx.appcompat.app.AlertDialog
                        .Builder(this)
                        .setTitle(R.string.choose_media_app)
                        .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                            val entry = choices[index]
                            getSharedPreferences("settings", Context.MODE_PRIVATE)
                                .edit()
                                .putString("defaultMediaPackage", entry.component.packageName)
                                .apply()
                            Bg.run("open-media") { VirtualLauncher.launchMediaApp(entry.component) }
                        }.show()
                }
            }
        }
    }

    /** Music control: system media keys (AudioManager routes globally to the active media session, no permission needed). */
    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setContentView(R.layout.activity_grid)

        // Cell size / row count computed dynamically from the screen (layout relations in XML, size measurement in code)
        computeCellMetrics()
        // Virtual-display rebuild race: when dm is briefly 0 the cell/icon sizes compute to 0 and the first-frame bind crashes
        // (Bitmap.createBitmap with 0 width/height). Clamp to a minimum as a safeguard, and recompute + rebind after the pager's
        // first-frame layout.
        if (iconSizePx <= 0) {
            iconSizePx = 48
            findViewById<ViewPager2>(R.id.gridPager).post {
                computeCellMetrics()
                iconSizePx = iconSizePx.coerceAtLeast(48)
                if (cellWidthPx > 0) reload()
            }
        }

        // Wallpaper background (index 0 = bottom): the window must be opaque - a transparent window in front of the app window
        // lets the app content show through (root cause of desktop icons overlapping Toutiao after tapping home).
        // Slice mapping: the aspectFill wallpaper of the "whole video-stream canvas" is offset by the dock position,
        // so this window exposes exactly the app-area slice, pixel-continuous with the slice at the dock (same rendering path).
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth > 0 && canvasHeight > 0) {
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            wallpaperView =
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams =
                        android.widget.FrameLayout
                            .LayoutParams(
                                canvasWidth,
                                canvasHeight,
                            ).apply {
                                if (VirtualLauncher.dockAtBottom) {
                                    // Portrait head unit: dock at the bottom, desktop slice = top of the canvas
                                    leftMargin = 0
                                    topMargin = 0
                                } else {
                                    // Landscape head unit: dock on the left, desktop slice = right part of the canvas
                                    leftMargin = -(VirtualLauncher.dockWidthPx * scale).toInt()
                                    topMargin = 0
                                }
                            }
                }
            root.addView(wallpaperView, 0)
            // Register change notification + reconcile once immediately (covers changes before registration - events are not replayed)
            WallpaperStore
                .addListener(wallpaperListener)
            refreshWallpaperIfChanged()

            // The dashboard panel is now the ViewPager2 home page (created when PageAdapter binds);
            // here only the global listeners are registered: card visuals / media state / wallpaper slice / dashboard mode.
            DockPrefs.addListener(dockPrefsListener)
            MediaSessionsState
                .addListener(mediaStateListener)
            CallState.addListener(callStateListener)
            WallpaperStore
                .addCanvasReadyListener(canvasReadyListener)
            VirtualLauncher.addDashboardListener(dashboardListener)
            VirtualLauncher.addMapLayoutListener(mapLayoutListener)
            VirtualLauncher.addOemListener(oemListener)
            applyDashboardMode(VirtualLauncher.dashboardMode)
        }

        pager =
            findViewById<ViewPager2>(R.id.gridPager).apply {
                offscreenPageLimit = 2
                isUserInputEnabled = !VirtualLauncher.dashboardMode
                adapter = PageAdapter()
            }
        VirtualLauncher.dashboardDragSink = dashboardDragSink
        dotsRow = findViewById(R.id.dotsRow)
        dotsRow?.alpha = 0f // Page dots hidden normally, faded in only while paging
        pager?.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                }

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    offsetPixels: Int,
                ) {
                    if (VirtualLauncher.dashboardPageAnimating) {
                        VirtualLauncher.setTileSlide(dashboardPageFraction(position, positionOffset))
                        VirtualLauncher.showMapTile()
                    }
                    syncDotsLayer()
                }

                override fun onPageScrollStateChanged(state: Int) {
                    // Page dots appear only while paging (same as CarPlay): shown during drag / fling, fade out after a short wait once
                    // stopped
                    setDotsShowing(state != androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE)
                    if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING &&
                        pager?.currentItem == 1 &&
                        !VirtualLauncher.dashboardMode
                    ) {
                        VirtualLauncher.beginDashboardPageDrag()
                    }
                    if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                        val dashboard = pager?.currentItem == 0
                        if (VirtualLauncher.dashboardUserDragging) VirtualLauncher.syncDashboard(dashboard)
                        VirtualLauncher.finishDashboardPageSwitch(dashboard)
                        pager?.isUserInputEnabled = !dashboard
                        if (reloadAfterPageSwitch) {
                            reloadAfterPageSwitch = false
                            reload()
                        }
                    }
                    syncDotsLayer()
                }
            },
        )
    }

    /**
     * Re-decodes and swaps the image in the background when the wallpaper version changes (the desktop updates within 2s after the phone
     * changes wallpaper).
     */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? android.widget.FrameLayout.LayoutParams
        Bg.run("wallpaper-grid") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        params?.width ?: 0,
                        params?.height ?: 0,
                    )
                }.getOrNull()
            if (bitmap != null) {
                // Diagnostics: sample the bitmap at three points (25%/50%/75% of the width on the midline) - distinguishes "bitmap content
                // missing"
                // from "view / compositing problem" (logcat is suppressed on this device, so go through logSink to the file log)
                val samples =
                    listOf(0.25f, 0.5f, 0.75f).joinToString(",") {
                        "%06x".format(bitmap.getPixel((bitmap.width * it).toInt(), bitmap.height / 2))
                    }
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-GRID: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params?.width}x${params?.height} left=${params?.leftMargin} " +
                        "samples=[$samples]",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("WALLPAPER-GRID: decode failed")
            }
        }
    }

    /** Whether the desktop grid (not an app, not the dashboard page) is what the car shows. */
    fun isDesktopOnCar(): Boolean =
        resumedOnCar && !VirtualLauncher.dashboardMode && VirtualLauncher.activeAppDisplayId() == VirtualLauncher.gridDisplayId

    @Volatile
    private var resumedOnCar = false

    fun currentPage(): Int = pager?.currentItem ?: 0

    /** Cells of the desktop page on screen (knob focus). */
    fun desktopFocusViews(): List<View> {
        val recycler = pager?.getChildAt(0) as? RecyclerView ?: return emptyList()
        val holder = recycler.findViewHolderForAdapterPosition(currentPage()) as? PageHolder ?: return emptyList()
        return holder.cells()
    }

    /** Knob: slide from the dashboard to the first desktop page with the same pager animation as a left swipe. */
    fun animateDashboardToDesktop() {
        val p = pager ?: return
        if (!VirtualLauncher.beginDashboardDragOut()) return
        val total = p.width.toFloat() * 0.6f
        val animator =
            android.animation.ValueAnimator
                .ofFloat(0f, total)
                .setDuration(260)
        var done = 0f
        animator.addUpdateListener {
            val value = it.animatedValue as Float
            VirtualLauncher.dashboardDragBy(-(value - done))
            done = value
        }
        animator.addListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    VirtualLauncher.endDashboardDrag()
                }
            },
        )
        // The drag sink posts to the same main thread: start after its beginFakeDrag
        tickHandler.post { animator.start() }
    }

    /** Knob: the next / previous desktop page (page 0 is the dashboard and is reached from the dock or Home). */
    fun turnDesktopPage(forward: Boolean): Boolean {
        val p = pager ?: return false
        val target = p.currentItem + if (forward) 1 else -1
        if (target < 1 || target >= pages.size) return false
        p.setCurrentItem(target, true)
        return true
    }

    /** Dashboard media card first, then its previous / play / next buttons (knob focus); empty off the dashboard. */
    fun dashboardFocusViews(): List<View> {
        val panel = dashboardView ?: return emptyList()
        return listOfNotNull(
            panel,
            panel.findViewById(R.id.dashPrev),
            panel.findViewById(R.id.dashPlayPause),
            panel.findViewById(R.id.dashNext),
        )
    }

    override fun onPause() {
        super.onPause()
        resumedOnCar = false
    }

    override fun onResume() {
        super.onResume()
        resumedOnCar = true
        // Backed out of an app the dock opened: the dashboard, not the desktop, is where it came from
        if (VirtualLauncher.returnToDashboardOnExit && !VirtualLauncher.dashboardMode) {
            VirtualLauncher.returnToDashboardOnExit = false
            Bg.run("back-to-dashboard") { VirtualLauncher.showDashboard() }
        }
        io.github.ezn24.droidcar.input.KnobFocus
            .onDroidCarResumed()
        // Also covers returning from the settings page: checkbox changes are reflected immediately
        reload()
        refreshWallpaperIfChanged()
    }

    /**
     * Computes cell size and row count:
     * Landscape (CarPlay spec): fixed 4 columns x 3 rows, same behavior as before.
     * Portrait: a fixed 3 rows would stretch cells into ~1:2.5 tall strips (icons take only 30%, the screen full of empty boxes,
     * exposed by the user's "show layout bounds" screenshot 2026-10-17); rows are added to
     * adapt to "cell height ~ width x 1.25" (cap 6, matching the iPhone portrait home screen 4x6 look), and the icon size
     * follows the cell width, avoiding oversized cells / undersized icons.
     */
    private fun computeCellMetrics() {
        val dm = resources.displayMetrics
        val density = dm.density
        val chromeHorizontalPx = (2 * 10 * density).toInt() // item_grid_page left/right padding
        val chromeVerticalPx = ((8 + 26 + 16) * density).toInt() // top/bottom padding + page-dot area
        val usableW = dm.widthPixels - chromeHorizontalPx
        val usableH = dm.heightPixels - chromeVerticalPx
        if (usableW <= 0 || usableH <= 0) {
            cellWidthPx = 0
            cellHeightPx = 0
            iconSizePx = 0
            return
        }
        cellWidthPx = usableW / COLUMNS
        // Same for landscape and portrait: first try 3 rows; when cells are too tall (portrait) add rows to converge on the target ratio
        var rowCount = ROWS_MIN
        if (cellWidthPx > 0) {
            rowCount = (usableH / (cellWidthPx * CELL_HEIGHT_RATIO)).toInt().coerceIn(ROWS_MIN, ROWS_MAX)
        }
        rows = rowCount
        cellHeightPx = usableH / rowCount
        iconSizePx =
            (cellHeightPx * 0.63f)
                .toInt()
                .coerceAtMost((cellWidthPx * 0.72f).toInt())
    }

    /** Re-reads the app list and the desktop selection (called on first frame and every time we return to the desktop). */
    private fun reload() {
        if (VirtualLauncher.dashboardPageAnimating) {
            reloadAfterPageSwitch = true
            return
        }
        val shared = VirtualLauncher.peekCachedApps()
        if (shared == null) {
            findViewById<View>(R.id.gridLoading).visibility = View.VISIBLE
            if (appScanInFlight) return
            appScanInFlight = true
            val app = applicationContext
            Bg.run("grid-apps-scan") {
                val scanned = runCatching { VirtualLauncher.cachedApps(app) }.getOrNull()
                runOnUiThread {
                    appScanInFlight = false
                    if (isFinishing || isDestroyed || scanned == null) return@runOnUiThread
                    appsCache = scanned
                    VirtualLauncher.replaceIconCache(scanned.associate { it.component to it.icon })
                    reload()
                }
            }
            return
        }
        findViewById<View>(R.id.gridLoading).visibility = View.GONE
        appsCache = shared
        val apps = shared

        val selected = DesktopApps.selected(this, apps)
        val cells =
            buildList {
                add(Cell.Pinned) // First desktop cell: head-unit settings (fixed, cannot be removed)
                add(Cell.Recents) // Second cell: background apps (fixed)
                if (VirtualLauncher.oemBlock != null) add(Cell.Oem)
                apps.filter { it.component.flattenToString() in selected }.forEach { add(Cell.App(it)) }
            }
        // Page 0 = dashboard, then the desktop grid pages: swiping left/right switches between them
        pages = listOf(listOf<Cell>(Cell.Dashboard)) + cells.chunked(COLUMNS * rows)

        val current = pager?.currentItem ?: 0
        pager?.adapter?.notifyDataSetChanged()
        rebuildDots()
        // Reconcile the position with the dashboard mode (the home-key cycle / app launch changes dashboardMode):
        // only corrects the direction, and does not drag a user resting on desktop page N back to page 1
        if (VirtualLauncher.dashboardMode && current != 0) pager?.setCurrentItem(0, false)
        if (!VirtualLauncher.dashboardMode && current == 0) pager?.setCurrentItem(1, false)
        if ((pager?.currentItem ?: 0) >= pages.size) pager?.setCurrentItem(pages.size - 1, false)
        updateDots(pager?.currentItem ?: 0)
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = if (pages.getOrNull(position)?.firstOrNull() is Cell.Dashboard) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                // Dashboard page = margin-less container: music card panel + split-drag handle (the handle sits inside the widened
                // gap, not covered by the compositor-overlaid map tile, and touches are dispatched normally through the app display)
                val container = android.widget.FrameLayout(parent.context)
                container.layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                val panel = layoutInflater.inflate(R.layout.view_dashboard, container, false)
                container.addView(panel)
                container.addView(DashSplitHandle(parent.context))
                DashboardHolder(container, panel)
            } else {
                val grid = layoutInflater.inflate(R.layout.item_grid_page, parent, false) as GridLayout
                grid.columnCount = COLUMNS
                // Row count is decided by computeCellMetrics per orientation (landscape 3, portrait up to 6)
                grid.rowCount = rows
                PageHolder(grid)
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (holder) {
                is DashboardHolder -> holder.bind()
                is PageHolder -> holder.bind(position)
            }
        }
    }

    /** Dashboard page holder: a margin-less container (music card panel + split handle) created with the page. */
    private inner class DashboardHolder(
        container: View,
        val panel: View,
    ) : RecyclerView.ViewHolder(container) {
        private var handle: DashSplitHandle? = null

        fun bind() {
            dashboardView = panel
            val scale = VirtualLauncher.gridScale()
            val cardMargin = (CARD_MARGIN_PX * scale).toInt()
            panel.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        if (VirtualLauncher.dockAtBottom) {
                            leftMargin = cardMargin
                            rightMargin = cardMargin
                            topMargin = cardMargin
                            bottomMargin = cardMargin
                        } else {
                            topMargin = cardMargin
                            rightMargin = cardMargin
                            bottomMargin = cardMargin
                        }
                    }
            panel.outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: android.graphics.Outline,
                    ) {
                        outline.setRoundRect(0, 0, view.width, view.height, cardMargin * 1.4f)
                    }
                }
            panel.clipToOutline = true
            panel.findViewById<ImageView>(R.id.dashPrev).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            panel.findViewById<ImageView>(R.id.dashNext).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            panel.findViewById<ImageView>(R.id.dashPlayPause).setOnClickListener {
                // Explicitly send play/pause by the current state; optimistically flip the icon (the state callback may lag),
                // and after 400ms ask the listener service to re-sync the real state
                val state = MediaSessionsState.nowPlaying
                val playing = state?.playing == true
                dispatchMediaKey(
                    if (playing) {
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    } else {
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    },
                )
                // Optimistic update keeps the cover and other fields (copy); re-sync the real state after 400ms
                MediaSessionsState.publish(
                    state
                        ?.copy(playing = !playing)
                        ?: MediaSessionsState.NowPlaying(
                            null,
                            null,
                            !playing,
                        ),
                )
                panel.postDelayed(
                    {
                        MediaSessionsState
                            .requestResync()
                    },
                    400,
                )
            }
            // Leaving the fullscreen map goes through the dock's home button (the showDashboard routing gate automatically calls
            // setMapFullscreen(false)), so the dashboard card no longer carries a "fullscreen map" button
            bindSplitHandle(scale)
            panel.isFocusable = true
            panel.contentDescription = getString(R.string.open_media_app)
            panel.setOnClickListener { openMediaApp() }
            applyDashCardChrome()
            renderMediaCard()
            refreshDashWallpaper()
        }

        /** Configures the split handle (orientation / size / drag callback) and lays out the panel and handle by the current split. */
        private fun bindSplitHandle(scale: Float) {
            val child = (itemView as? android.widget.FrameLayout)?.getChildAt(1) as? DashSplitHandle
            handle = child ?: return
            val portrait = VirtualLauncher.splitVertical
            // The touch strip is wider than the visual gap (DASH_HANDLE_TOUCH_PX > DASH_GAP_PX): it overflows on both sides
            // onto the cards to enlarge the touch target - the map-side overflow is covered by the tile, invisible but touchable
            val thickness = (DASH_HANDLE_TOUCH_PX * scale).toInt()
            child.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else thickness,
                        if (portrait) thickness else ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            child.visibility = View.GONE
            child.isEnabled = false
            layoutSplit(VirtualLauncher.dashHandleCenterScreen(), scale)
        }

        /** Handle center (app-display pixels) -> margins of the music card and handle. When not attached (<0), fall back to the midline. */
        private fun layoutSplit(
            centerScreen: Float,
            scale: Float,
        ) {
            layoutDashboardMedia()
            handle?.visibility = View.GONE
        }
    }

    private inner class PageHolder(
        private val grid: GridLayout,
    ) : RecyclerView.ViewHolder(grid) {
        fun cells(): List<View> = (0 until grid.childCount).map { grid.getChildAt(it) }

        fun bind(page: Int) {
            grid.removeAllViews()
            pages[page].forEach { cell ->
                val view = layoutInflater.inflate(R.layout.item_app_cell, grid, false)
                val icon =
                    view.findViewById<ImageView>(R.id.cellIcon).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                val label = view.findViewById<TextView>(R.id.cellLabel)
                when (cell) {
                    is Cell.Dashboard -> Unit // The dashboard is its own page, not in the grid
                    is Cell.Pinned -> {
                        icon.setImageResource(R.drawable.ic_app_settings)
                        label.text = getString(R.string.car_settings)
                        view.setOnClickListener { openSettings() }
                    }

                    is Cell.Recents -> {
                        icon.setImageResource(R.drawable.ic_app_recents)
                        label.text = getString(R.string.recent_apps)
                        view.setOnClickListener { VirtualLauncher.showRecentApps(this@GridActivity) }
                    }

                    is Cell.Oem -> {
                        val block = VirtualLauncher.oemBlock
                        val bitmap = block?.icon
                        if (bitmap != null) {
                            icon.setImageBitmap(AppIcons.plated(bitmap, iconSizePx))
                        } else {
                            icon.setImageResource(R.drawable.ic_app_recents)
                        }
                        label.text = block?.label.orEmpty()
                        view.contentDescription = block?.label
                        view.setOnClickListener {
                            io.github.ezn24.droidcar.SessionController
                                .requestCarUi()
                        }
                    }

                    is Cell.App -> {
                        icon.setImageBitmap(AppIcons.rounded(cell.entry.icon, iconSizePx))
                        label.text = cell.entry.label
                        view.setOnClickListener {
                            // No system Toast: its position on a virtual display is uncontrollable (the origin of SettingsToastHost);
                            // the app launching is itself the feedback
                            Bg.run("launch-app") { VirtualLauncher.launchApp(cell.entry.component) }
                        }
                    }
                }
                // Focus: DPAD events injected by the iDrive knob / steering-wheel direction keys land through the focus system,
                // so grid cells must be focusable + have a visible highlight frame (key presses automatically leave touch mode).
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.foreground =
                    androidx.core.content.ContextCompat
                        .getDrawable(this@GridActivity, R.drawable.focus_ring)
                grid.addView(
                    view,
                    GridLayout.LayoutParams().apply {
                        width = cellWidthPx
                        height = cellHeightPx
                    },
                )
            }
        }
    }

    private fun openSettings() {
        // The slide transition is provided by the SettingsActivity theme's windowAnimationStyle
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun rebuildDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
        // A single page shows no page dots (matching the prototype); the capsule background is hidden too, otherwise an empty capsule
        // remains
        row.visibility = if (pages.size < 2) View.GONE else View.VISIBLE
        if (pages.size < 2) return
        val density = resources.displayMetrics.density
        // CarPlay style: the dots sit on a translucent dark capsule, readable even over bright backgrounds such as a map.
        // The background belongs to this row View, so the snapshot (row.draw in publishDots) paints it into the compositor layer too.
        row.background =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(0xB31C1C1E.toInt())
            }
        row.setPadding((7 * density).toInt(), (5 * density).toInt(), (7 * density).toInt(), (5 * density).toInt())
        repeat(pages.size) {
            row.addView(
                android.view.View(this).apply {
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(0x66FFFFFF)
                        }
                },
                LinearLayout.LayoutParams((7 * density).toInt(), (7 * density).toInt()).apply {
                    marginStart = (3.5f * density).toInt()
                    marginEnd = (3.5f * density).toInt()
                },
            )
        }
        updateDots(0)
    }

    private fun updateDots(selected: Int) {
        val row = dotsRow ?: return
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (i == selected) 0xFFFFFFFF.toInt() else 0x66FFFFFF.toInt())
        }
        // Wait for this round of layout to finish before snapshotting (the children just added by rebuildDots are not measured yet)
        row.post { publishDots() }
    }

    /**
     * The dashboard's map / media cards are drawn by the compositor above this app display and would cover the page dots: the page-dot
     * snapshot is handed to the compositor
     * to be drawn on top of the cards, and the local copy is hidden to avoid two overlapping copies. See [VirtualLauncher.pageDotsOnTop]
     * for the criterion.
     */
    private fun publishDots() {
        val row = dotsRow ?: return
        syncDotsLayer()
        if (row.childCount == 0 || row.width <= 0 || row.height <= 0) {
            VirtualLauncher.setPageDots(null, 0f, 0f)
            return
        }
        // View.draw does not apply the View's own alpha, so hiding the local page dots does not affect the snapshot
        val bitmap = android.graphics.Bitmap.createBitmap(row.width, row.height, android.graphics.Bitmap.Config.ARGB_8888)
        row.draw(android.graphics.Canvas(bitmap))
        val loc = IntArray(2)
        row.getLocationOnScreen(loc)
        VirtualLauncher.setPageDots(bitmap, loc[0].toFloat(), loc[1].toFloat())
    }

    /** Page-dot ownership switch (local <-> compositor top layer): called when dashboard mode / swiping / map fullscreen changes. */
    private fun syncDotsLayer() {
        val row = dotsRow ?: return
        // When the compositor top layer takes over, the local copy stays hidden; otherwise it follows "while paging" fade in/out
        val target = if (VirtualLauncher.pageDotsOnTop() || !dotsShowing) 0f else 1f
        // Start an animation only when the target changes: onPageScrolled calls here every frame, and restarting per frame would slow the
        // fade
        if (target == dotsTargetAlpha) return
        dotsTargetAlpha = target
        row.animate().cancel()
        row
            .animate()
            .alpha(target)
            .setDuration(if (target > 0f) 120L else 220L)
            .start()
    }

    private var dotsTargetAlpha = 0f

    /** Whether the page dots should currently be visible (while paging + a short dwell after stopping). */
    private var dotsShowing = false

    private val hideDots = Runnable { applyDotsShowing(false) }

    private fun setDotsShowing(moving: Boolean) {
        val row = dotsRow ?: return
        row.removeCallbacks(hideDots)
        if (moving) applyDotsShowing(true) else row.postDelayed(hideDots, DOTS_LINGER_MS)
    }

    private fun applyDotsShowing(show: Boolean) {
        if (dotsShowing == show) return
        dotsShowing = show
        VirtualLauncher.setPageDotsShowing(show)
        syncDotsLayer()
    }

    /**
     * Dashboard follow-finger left drag: TouchRouter displacement is turned into a pager fakeDrag on the main thread (events execute in
     * delivery order).
     */
    private val dashboardDragSink =
        object : VirtualLauncher.DashboardDragSink {
            override fun begin(): Boolean {
                if (pager == null) return false
                tickHandler.post {
                    val p = pager
                    if (p == null || !p.beginFakeDrag()) {
                        // The pager refused (e.g. it is animating): reset the transition state so it does not get stuck in "dragging" and
                        // swallow touches
                        VirtualLauncher.finishDashboardPageSwitch(VirtualLauncher.dashboardMode)
                    }
                }
                return true
            }

            override fun dragBy(px: Float) {
                tickHandler.post { pager?.takeIf { it.isFakeDragging }?.fakeDragBy(px) }
            }

            override fun end() {
                tickHandler.post { pager?.takeIf { it.isFakeDragging }?.endFakeDrag() }
            }
        }

    override fun onDestroy() {
        if (current === this) current = null
        if (VirtualLauncher.dashboardDragSink === dashboardDragSink) VirtualLauncher.dashboardDragSink = null
        tickHandler.removeCallbacks(mediaTick)
        WallpaperStore
            .removeListener(wallpaperListener)
        VirtualLauncher.removeDashboardListener(dashboardListener)
        VirtualLauncher.removeMapLayoutListener(mapLayoutListener)
        VirtualLauncher.removeOemListener(oemListener)
        VirtualLauncher.setPageDots(null, 0f, 0f)
        MediaSessionsState
            .removeListener(mediaStateListener)
        CallState.removeListener(callStateListener)
        DockPrefs.removeListener(dockPrefsListener)
        WallpaperStore
            .removeCanvasReadyListener(canvasReadyListener)
        super.onDestroy()
    }

    override fun onBackPressed() = Unit // The desktop does not exit

    companion object {
        /** The desktop on the car (knob focus reads its cells and the dashboard card). */
        @Volatile
        var current: GridActivity? = null

        const val COLUMNS = 4 // CarPlay spec: forced 4 columns

        /** How long the page dots linger after paging stops before fading out. */
        const val DOTS_LINGER_MS = 700L

        /** Row count of the landscape CarPlay spec (used as the lower bound when portrait adapts). */
        const val ROWS_MIN = 3

        /** Upper bound of portrait adaptive rows (matching the iPhone portrait home screen 4x6 look). */
        const val ROWS_MAX = 6

        /** Target cell aspect ratio (height / width): the convergence target when portrait adds rows. */
        const val CELL_HEIGHT_RATIO = 1.25f
    }
}
