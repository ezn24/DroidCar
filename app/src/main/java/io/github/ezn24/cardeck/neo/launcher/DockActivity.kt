package io.github.ezn24.cardeck.neo.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.ezn24.cardeck.neo.R
import io.github.ezn24.cardeck.neo.ui.WallpaperStore
import io.github.ezn24.cardeck.neo.util.Bg

/**
 * CarPlay dock (separate virtual display: clock / battery / running apps / back to desktop).
 *
 * Two forms (layouts in res/layout):
 *  - Landscape head unit: dock is a left vertical strip -> activity_dock_sidebar.xml (clock on top, home at the bottom)
 *  - Portrait head unit: dock is a bottom bar -> activity_dock_bar.xml (home | apps in a row | time/battery)
 * The form is decided by the dock virtual display's own size (strip: height>width; bar: width>height), so it aligns with the head-unit
 * orientation automatically.
 */
class DockActivity : AppCompatActivity() {
    private var appsContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var batteryView: io.github.ezn24.cardeck.neo.ui.BatteryGlyphView? = null
    private var statusView: io.github.ezn24.cardeck.neo.ui.StatusGlyphsView? = null

    /** Cellular signal callback (SignalStrengthsListener needs no permission). */
    private val signalCallback =
        object :
            android.telephony.TelephonyCallback(),
            android.telephony.TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength) {
                statusView?.setCellLevel(cellLevelOf(signalStrength.level))
            }
        }

    /** With no SIM / not ready, dim the whole row of signal dots (-1). */
    private fun cellLevelOf(level: Int): Int {
        val tm = getSystemService(android.telephony.TelephonyManager::class.java) ?: return -1
        return if (tm.simState == android.telephony.TelephonyManager.SIM_STATE_READY) level else -1
    }

    /**
     * Wi-Fi bars: tracks the RSSI of any Wi-Fi network (during a session, the head-unit hotspot). The callback runs on a background thread,
     * so it is posted back to the main thread to update; the icon is hidden on disconnect.
     */
    private val wifiCallback =
        object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities,
            ) {
                val rssi = caps.signalStrength
                val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                val level =
                    if (rssi == android.net.NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED || wm == null) {
                        3
                    } else {
                        val max = wm.maxSignalLevel.coerceAtLeast(1)
                        ((wm.calculateSignalLevel(rssi) * 3f / max) + 0.5f).toInt().coerceIn(0, 3)
                    }
                runOnUiThread { statusView?.setWifiLevel(level) }
            }

            override fun onLost(network: android.net.Network) {
                runOnUiThread { statusView?.setWifiLevel(-1) }
            }
        }
    private var barForm = false

    /**
     * Current multiplier over the design baseline density (420dpi). The dock display density is sent by SessionController proportionally
     * to the canvas short side
     * (Audi 1160x540 -> 420 unchanged; test bench 2320x1080 -> 840, dock and text/icons all x2).
     * Layout XML uses dp and scales with density automatically; font size / blur radius etc. are set in code as "design px x this
     * multiplier",
     * so dock content keeps the canvas proportion on high-resolution head units (2026-10-17 fix for "looks small at high resolution").
     */
    private val designScale: Float
        get() = resources.displayMetrics.densityDpi / 420f

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                refresh()
                pollHandler.postDelayed(this, 3_000)
            }
        }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker =
        object : Runnable {
            override fun run() {
                // No seconds shown (user request): align refresh to the next minute boundary, no useless redraws within the minute
                clockView?.text =
                    android.text.format.DateFormat
                        .format("HH:mm", System.currentTimeMillis())
                clockView?.let { fitClockText(it) }
                val now = System.currentTimeMillis()
                clockHandler.postDelayed(this, 60_000L - now % 60_000L + 80L)
            }
        }

    /** Wallpaper background (slice of the full-stream canvas, same source and path as the desktop - pixel-continuous at the seam). */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** Wallpaper change listener (WallpaperStore main-thread callback; decoding is moved to a background thread by this class). */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** Dock appearance preference listener (the settings slider takes effect live). */
    private val dockPrefsListener: () -> Unit = { runOnUiThread { applyDockPrefs() } }

    private val batteryReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = updateBattery(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        barForm = dm.widthPixels > dm.heightPixels
        setContentView(if (barForm) R.layout.activity_dock_bar else R.layout.activity_dock_sidebar)

        clockView = findViewById(R.id.dockClock)
        batteryView = findViewById(R.id.dockBattery)
        statusView = findViewById(R.id.dockStatus)
        appsContainer = findViewById(R.id.dockApps)
        wallpaperView = findViewById(R.id.dockWallpaper)
        findViewById<ImageView>(R.id.dockHome).apply {
            setOnClickListener { Bg.run("dock-home") { VirtualLauncher.onHomePressed() } }
            // Icon follows the mode: dashboard active = house (tap returns to desktop); otherwise = grid (tap enters the dashboard).
            // In an app and on the desktop it is the grid in both cases - the action is "enter the dashboard" either way, so the semantics
            // stay consistent.
            VirtualLauncher.addDashboardListener { dashboard ->
                runOnUiThread {
                    setImageResource(
                        if (dashboard) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
                    )
                }
            }
            setImageResource(
                if (VirtualLauncher.dashboardMode) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
            )
        }

        VirtualLauncher.addRunningAppsListener(refreshListener)
        pollHandler.postDelayed(pollRunnable, 3_000)
        clockHandler.post(clockTicker)
        // Font sizes are set as "design px x designScale" (not in XML: on the Honor ROM autoSize does not
        // work for the narrow dock, so the clock still has to be shrunk by hand from the measured width after layout - see fitClockText;
        // 13/22 are prototype values)
        clockView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30f * designScale)
        // Battery icon height uses the same design baseline as the old percentage text (13 design px) and scales with the dock
        batteryView?.let { v -> v.layoutParams = v.layoutParams.apply { height = (21f * designScale).toInt().coerceAtLeast(1) } }
        statusView?.let { v -> v.layoutParams = v.layoutParams.apply { height = (17f * designScale).toInt().coerceAtLeast(1) } }
        startStatusTracking()
        clockView?.post { fitClockText(clockView!!) }
        setupWallpaper()
        // Register change notification + reconcile once immediately (covers changes before registration - events are not replayed)
        WallpaperStore
            .addListener(wallpaperListener)
        refreshWallpaperIfChanged()
        // Dock appearance (scrim/blur): apply the current value + listen for settings changes
        DockPrefs.addListener(dockPrefsListener)
        applyDockPrefs()
        // System protected broadcast: ACTION_BATTERY_CHANGED is sticky, so registering backfills the current level
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        pollHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacks(clockTicker)
        WallpaperStore
            .removeListener(wallpaperListener)
        DockPrefs.removeListener(dockPrefsListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching {
            getSystemService(android.telephony.TelephonyManager::class.java)?.unregisterTelephonyCallback(signalCallback)
        }
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(wifiCallback)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Wallpaper slice: draws, inside the dock window, the part of the "full-stream canvas wallpaper" that this strip covers.
     * The dock virtual display is 1:1 with the stream (420dpi) and the canvas view = stream canvas size:
     *  - Sidebar (landscape head unit): left strip of the stream, offset (0, 0)
     *  - Bottom bar (portrait head unit): bottom strip of the stream, offset (0, -(appHeight))
     * The window must be opaque: with a translucent window the system composites the phone's own wallpaper onto this virtual display
     * (this once made the dock show the phone wallpaper, misaligned with the desktop wallpaper - root cause measured 2026-10-12).
     */
    private fun setupWallpaper() {
        val launcher = VirtualLauncher
        val streamW = launcher.streamWidthPx
        val streamH = launcher.streamHeightPx
        if (streamW <= 0 || streamH <= 0) return
        val view = wallpaperView ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (barForm) {
            params.topMargin = -(streamH - launcher.dockHeightPx)
        } else {
            params.leftMargin = 0
            params.topMargin = 0
        }
        params.width = streamW
        params.height = streamH
        view.layoutParams = params
        refreshWallpaperIfChanged()
    }

    /**
     * Applies the dock appearance preferences: scrim strength (black alpha of the scrim View) + background Gaussian blur
     * (RenderEffect, API 31+; radius in dock physical pixels, max 40px - multiplied by designScale
     * so the blur looks the same on high-resolution head units as on the Audi baseline).
     */
    private fun applyDockPrefs() {
        val scrim = findViewById<View>(R.id.dockScrim) ?: return
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        scrim.background =
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(alpha, 0, 0, 0),
            )
        val wallpaper = wallpaperView ?: return
        val percent = DockPrefs.blur(this)
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * designScale
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
     * Re-decodes and swaps the image in the background when the wallpaper version changes (updated in sync with the desktop via the
     * listener).
     */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        Bg.run("wallpaper-dock") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@DockActivity,
                        params.width,
                        params.height,
                    )
                }.getOrNull()
            if (bitmap != null) {
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-DOCK: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params.width}x${params.height} top=${params.topMargin}",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("WALLPAPER-DOCK: decode failed")
            }
        }
    }

    /** Shrinks the font step by step until the text fits the width (design px x designScale; trip 22 -> 12, per the design prototype). */
    private fun fitClockText(view: TextView) {
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0) return
        val scale = designScale
        var size = 22f * scale
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        val paint = view.paint
        while (size > 12f * scale && paint.measureText(view.text.toString()) > available) {
            size -= 0.5f * scale
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun updateBattery(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        runOnUiThread { batteryView?.setLevel(percent, charging) }
    }

    /** Signal / Wi-Fi status row under the time: registers system callbacks (a failure only affects this row, not the dock). */
    private fun startStatusTracking() {
        runCatching {
            val tm = getSystemService(android.telephony.TelephonyManager::class.java) ?: return@runCatching
            statusView?.setCellLevel(cellLevelOf(tm.signalStrength?.level ?: 0))
            tm.registerTelephonyCallback(mainExecutor, signalCallback)
        }
        runCatching {
            val request =
                android.net.NetworkRequest
                    .Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
            getSystemService(android.net.ConnectivityManager::class.java)?.registerNetworkCallback(request, wifiCallback)
        }
    }

    private var lastRendered: List<String> = emptyList()

    /**
     * Icon table used in the last render (redraw after the desktop scan refreshes: the dock often finishes its first draw before the scan).
     */
    private var lastIconCache: Map<android.content.ComponentName, android.graphics.drawable.Drawable>? = null

    /**
     * Icon of a running app. The cache key is the launch component found by the desktop scan, while the map/media areas launch the
     * component
     * resolved via resolveActivity - when the launch entry is an activity-alias (e.g. Amap) the class names differ, and an exact lookup
     * misses and falls to the default robot icon. Fallbacks in order: cache entry with the same package -> system component icon -> app
     * icon.
     */
    private fun iconFor(
        component: android.content.ComponentName,
        cache: Map<android.content.ComponentName, android.graphics.drawable.Drawable>,
    ): android.graphics.drawable.Drawable =
        cache[component]
            ?: cache.entries.firstOrNull { it.key.packageName == component.packageName }?.value
            ?: runCatching { packageManager.getActivityIcon(component) }.getOrNull()
            ?: runCatching { packageManager.getApplicationIcon(component.packageName) }.getOrNull()
            ?: packageManager.defaultActivityIcon

    private fun refresh() {
        val container = appsContainer ?: return
        val apps = VirtualLauncher.dockApps()
        val current = apps.map { it.flattenToString() }
        val iconCache = VirtualLauncher.iconCache
        if (current == lastRendered && iconCache === lastIconCache) return
        lastRendered = current
        lastIconCache = iconCache
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val iconPx = ((if (barForm) 32 else 30) * density).toInt()
        val pad = (6 * density).toInt()
        apps.forEach { component ->
            val drawable = iconFor(component, iconCache)
            // Render as a bitmap (same path as the desktop grid): setImageDrawable directly would let the ImageView
            // hold the shared Drawable instance for a long time, and once the grid rebind rewrites its bounds the rendering is misaligned
            // (real-device failure: after tapping home the dock icons shrank and shifted left)
            val bitmap = AppIcons.rounded(drawable, iconPx)
            container.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = component.shortClassName
                    setOnClickListener {
                        Bg.run("launch-app") { VirtualLauncher.launchApp(component) }
                    }
                },
                LinearLayout.LayoutParams(iconPx + pad * 2, iconPx + pad * 2),
            )
        }
    }

    override fun onBackPressed() = Unit // The dock is resident and ignores Back
}
