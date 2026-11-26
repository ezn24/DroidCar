package io.github.ezn24.droidcar.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ezn24.droidcar.R
import io.github.ezn24.droidcar.SettingsContentActivity
import io.github.ezn24.droidcar.bt.AutoConnectPrefs
import io.github.ezn24.droidcar.bt.TargetDevicePrefs
import io.github.ezn24.droidcar.launcher.AssistiveDot
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import io.github.ezn24.droidcar.launcher.settings.DisplayPrefs
import io.github.ezn24.droidcar.launcher.settings.SettingsMenus
import io.github.ezn24.droidcar.legal.DisclaimerDialog
import io.github.ezn24.droidcar.legal.DisclaimerPrefs
import io.github.ezn24.droidcar.media.NotificationAccess
import io.github.ezn24.droidcar.nav.NavAccessibilityAccess
import io.github.ezn24.droidcar.shizuku.ShizukuGate
import io.github.ezn24.droidcar.ui.DarkMessageDialog
import io.github.ezn24.droidcar.ui.WallpaperPresets
import io.github.ezn24.droidcar.ui.WallpaperStore
import io.github.ezn24.droidcar.util.Bg

/** Whether the first-run setup guide has been completed (or dismissed with "Get started"). */
object SetupPrefs {
    private const val PREFS = "setup"
    private const val KEY_DONE = "done_version"

    /** Bump when the guide gains a page every user should see once. */
    private const val VERSION = 1

    fun done(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DONE, 0) >= VERSION

    fun markDone(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DONE, VERSION)
            .apply()
    }
}

/**
 * First-run setup guide (user request 2026-11-26): many options were hidden in settings and users did not know they existed.
 * One page per topic, in this order: welcome, disclaimer (must be accepted), permissions with what each one is for, the car,
 * connection and lock mode, dashboard, wallpaper, car notifications, extras, done. Every page except the disclaimer can be
 * skipped; everything stays changeable in settings, and the guide can be run again from the Car tab.
 *
 * Views are built in code with the phone UI's m3 tokens so the guide follows dark/light mode and the system accent.
 */
class SetupActivity : AppCompatActivity() {
    private enum class Page { WELCOME, DISCLAIMER, PERMISSIONS, CAR, CONNECTION, DASHBOARD, WALLPAPER, NOTIFICATIONS, EXTRAS, DONE }

    private val pages = Page.values()
    private var index = 0

    private lateinit var host: FrameLayout
    private lateinit var progress: LinearLayout
    private lateinit var backButton: TextView
    private lateinit var nextButton: TextView

    /** Re-renders the current page after a permission result or a return from system settings. */
    private var refreshPage: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPage?.invoke() }

    private val shizukuListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshPage?.invoke() } }

    private val density get() = resources.displayMetrics.density

    private fun dp(value: Float) = (value * density).toInt()

    private fun color(id: Int) = getColor(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = savedInstanceState?.getInt(KEY_PAGE, 0) ?: 0
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener) }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(color(R.color.m3_surface))
                fitsSystemWindows = true
            }
        progress =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(24f), dp(18f), dp(24f), dp(6f))
            }
        root.addView(progress, LinearLayout.LayoutParams(-1, -2))
        host = FrameLayout(this)
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(10f), dp(20f), dp(18f))
            }
        backButton =
            textButton(getString(R.string.setup_back)).apply {
                setOnClickListener { go(index - 1) }
            }
        bar.addView(backButton, LinearLayout.LayoutParams(-2, -2))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nextButton = pillButton(getString(R.string.setup_next)).apply { setOnClickListener { onNext() } }
        bar.addView(nextButton, LinearLayout.LayoutParams(-2, dp(48f)))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        index > 0 -> go(index - 1)
                        // Leaving before the disclaimer was accepted leaves the app, the same as declining it
                        !DisclaimerPrefs.hasAcceptedCurrent(this@SetupActivity) -> finishAffinity()
                        else -> finish()
                    }
                }
            },
        )
        show(animateFrom = 0)
    }

    override fun onResume() {
        super.onResume()
        refreshPage?.invoke()
    }

    override fun onDestroy() {
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, index)
    }

    private fun onNext() {
        when (pages[index]) {
            Page.DISCLAIMER -> if (!DisclaimerPrefs.hasAcceptedCurrent(this)) return showDisclaimer()
            Page.PERMISSIONS ->
                if (ShizukuGate.state(this) != ShizukuGate.State.READY) {
                    DarkMessageDialog.show(
                        activity = this,
                        title = getString(R.string.setup_perm_shizuku),
                        message = getString(R.string.setup_shizuku_needed),
                        actions =
                            listOf(
                                DarkMessageDialog.Action(getString(R.string.setup_back)) {},
                                DarkMessageDialog.Action(getString(R.string.setup_continue_anyway), DarkMessageDialog.Emphasis.PRIMARY) {
                                    go(index + 1)
                                },
                            ),
                    )
                    return
                }
            Page.DONE -> {
                SetupPrefs.markDone(this)
                finish()
                return
            }
            else -> Unit
        }
        go(index + 1)
    }

    private fun go(target: Int) {
        if (target < 0 || target >= pages.size || target == index) return
        val from = index
        index = target
        show(animateFrom = if (target > from) 1 else -1)
    }

    /** Swaps in the current page, sliding it in from the side it comes from. */
    private fun show(animateFrom: Int) {
        val old = host.getChildAt(0)
        val page = buildPage(pages[index])
        host.addView(page, FrameLayout.LayoutParams(-1, -1))
        val shift = 48 * density * animateFrom
        if (old != null && animateFrom != 0) {
            old
                .animate()
                .translationX(-shift)
                .alpha(0f)
                .setDuration(PAGE_MS)
                .withEndAction { host.removeView(old) }
                .start()
            page.translationX = shift
            page.alpha = 0f
            page
                .animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(PAGE_MS)
                .start()
        } else if (old != null) {
            host.removeView(old)
        }
        backButton.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text =
            getString(
                when (pages[index]) {
                    Page.DONE -> R.string.setup_finish
                    Page.DISCLAIMER -> if (DisclaimerPrefs.hasAcceptedCurrent(this)) R.string.setup_next else R.string.setup_disclaimer_read
                    else -> R.string.setup_next
                },
            )
        renderProgress()
    }

    private fun renderProgress() {
        progress.removeAllViews()
        pages.indices.forEach { i ->
            val segment =
                View(this).apply {
                    background =
                        GradientDrawable().apply {
                            cornerRadius = dp(2f).toFloat()
                            setColor(color(if (i <= index) R.color.m3_primary else R.color.m3_outline_var))
                        }
                }
            progress.addView(
                segment,
                LinearLayout.LayoutParams(0, dp(4f), 1f).apply { if (i > 0) leftMargin = dp(4f) },
            )
        }
    }

    // ---- Pages ----

    private fun buildPage(page: Page): View {
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24f), dp(20f), dp(24f), dp(24f))
            }
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                addView(column)
            }
        refreshPage = null
        when (page) {
            Page.WELCOME -> {
                column.gravity = Gravity.CENTER_HORIZONTAL
                column.addView(
                    ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
                    LinearLayout.LayoutParams(dp(96f), dp(96f)).apply { topMargin = dp(36f) },
                )
                column.addView(title(getString(R.string.setup_welcome_title)).apply { gravity = Gravity.CENTER })
                column.addView(body(getString(R.string.setup_welcome_body)).apply { gravity = Gravity.CENTER })
            }
            Page.DISCLAIMER -> {
                column.addView(title(getString(R.string.setup_disclaimer_title)))
                column.addView(body(getString(R.string.setup_disclaimer_body)))
                val card = card(column)
                val status = actionRow(card, getString(R.string.setup_disclaimer_title), null, R.drawable.ic_doc)
                refreshPage = {
                    val accepted = DisclaimerPrefs.hasAcceptedCurrent(this)
                    bindStatus(status, accepted, getString(R.string.setup_disclaimer_read)) { showDisclaimer() }
                    nextButton.text = getString(if (accepted) R.string.setup_next else R.string.setup_disclaimer_read)
                }
            }
            Page.PERMISSIONS -> buildPermissions(column)
            Page.CAR -> buildCar(column)
            Page.CONNECTION -> buildConnection(column)
            Page.DASHBOARD -> buildDashboard(column)
            Page.WALLPAPER -> buildWallpaper(column)
            Page.NOTIFICATIONS -> buildNotifications(column)
            Page.EXTRAS -> {
                column.addView(title(getString(R.string.setup_extras_title)))
                val card = card(column)
                switchRow(
                    card,
                    getString(R.string.ui_nav_cluster_title),
                    getString(R.string.setup_extras_cluster_desc),
                    NavAccessibilityAccess.enabledByUser(this),
                ) {
                    setClusterNavigation(it)
                }
                divider(card)
                switchRow(card, getString(R.string.assistive_dot), getString(R.string.setup_extras_dot_desc), AssistiveDot.enabled(this)) {
                    AssistiveDot.setEnabled(this, it)
                }
                buildDotSize(column)
            }
            Page.DONE -> {
                column.gravity = Gravity.CENTER_HORIZONTAL
                column.addView(
                    ImageView(this).apply {
                        setImageResource(R.drawable.ic_check_circle)
                        imageTintList = ColorStateList.valueOf(color(R.color.m3_primary))
                    },
                    LinearLayout.LayoutParams(dp(72f), dp(72f)).apply { topMargin = dp(48f) },
                )
                column.addView(title(getString(R.string.setup_done_title)).apply { gravity = Gravity.CENTER })
                column.addView(body(getString(R.string.setup_done_body)).apply { gravity = Gravity.CENTER })
            }
        }
        refreshPage?.invoke()
        return scroll
    }

    private fun buildPermissions(column: LinearLayout) {
        column.addView(title(getString(R.string.setup_perm_title)))
        column.addView(body(getString(R.string.setup_perm_body)))
        val card = card(column)
        val shizuku =
            actionRow(
                card,
                getString(R.string.setup_perm_shizuku),
                getString(R.string.setup_perm_shizuku_desc),
                R.drawable.ic_shizuku_shield,
            )
        divider(card)
        val bt = actionRow(card, getString(R.string.setup_perm_bt), getString(R.string.setup_perm_bt_desc), R.drawable.ic_bluetooth)
        divider(card)
        val wifi = actionRow(card, getString(R.string.setup_perm_wifi), getString(R.string.setup_perm_wifi_desc), R.drawable.ic_wifi)
        val notify =
            if (Build.VERSION.SDK_INT >= 33) {
                divider(card)
                actionRow(card, getString(R.string.setup_perm_notify), getString(R.string.setup_perm_notify_desc), R.drawable.ic_bell)
            } else {
                null
            }
        divider(card)
        val overlay =
            actionRow(card, getString(R.string.setup_perm_overlay), getString(R.string.setup_perm_overlay_desc), R.drawable.ic_brightness)
        divider(card)
        val listener =
            actionRow(card, getString(R.string.setup_perm_listener), getString(R.string.setup_perm_listener_desc), R.drawable.ic_music)
        refreshPage = {
            val state = ShizukuGate.state(this)
            val label =
                when (state) {
                    ShizukuGate.State.NOT_INSTALLED -> getString(R.string.setup_perm_shizuku_install)
                    ShizukuGate.State.NOT_RUNNING -> getString(R.string.setup_perm_shizuku_start)
                    else -> getString(R.string.setup_grant)
                }
            bindStatus(shizuku, state == ShizukuGate.State.READY, label) {
                when (state) {
                    ShizukuGate.State.NOT_INSTALLED ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL))) }
                    ShizukuGate.State.NOT_RUNNING ->
                        packageManager.getLaunchIntentForPackage(ShizukuGate.PACKAGE_NAME)?.let { runCatching { startActivity(it) } }
                    ShizukuGate.State.UNAUTHORIZED -> runCatching { ShizukuGate.requestPermission() }
                    ShizukuGate.State.READY -> Unit
                }
            }
            bindStatus(bt, granted(Manifest.permission.BLUETOOTH_CONNECT), getString(R.string.setup_grant)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
            }
            val wifiPermissions =
                if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf("android.permission.NEARBY_WIFI_DEVICES")
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            bindStatus(wifi, granted(wifiPermissions[0]), getString(R.string.setup_grant)) { permissionLauncher.launch(wifiPermissions) }
            notify?.let { row ->
                bindStatus(row, granted("android.permission.POST_NOTIFICATIONS"), getString(R.string.setup_grant)) {
                    permissionLauncher.launch(arrayOf("android.permission.POST_NOTIFICATIONS"))
                }
            }
            bindStatus(overlay, Settings.canDrawOverlays(this), getString(R.string.setup_grant)) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
            bindStatus(listener, listenerGranted(), getString(R.string.setup_grant)) { grantListener() }
        }
    }

    private fun buildCar(column: LinearLayout) {
        column.addView(title(getString(R.string.setup_car_title)))
        column.addView(body(getString(R.string.setup_car_body)))
        val card = card(column)
        refreshPage = {
            card.removeAllViews()
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                val row = actionRow(card, getString(R.string.setup_car_need_bt), null, R.drawable.ic_bluetooth)
                bindStatus(row, false, getString(R.string.setup_grant)) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
                }
            } else {
                val bonded = bondedDevices()
                val current = TargetDevicePrefs.get(this)?.address
                val radios = ArrayList<RadioButton>()

                fun pick(
                    selected: RadioButton,
                    target: TargetDevicePrefs.Target?,
                ) {
                    radios.forEach { it.isChecked = it === selected }
                    TargetDevicePrefs.set(this, target)
                }
                radios += radioRow(card, getString(R.string.setup_car_auto), null, current == null) { pick(it, null) }
                bonded.forEach { (name, address) ->
                    divider(card)
                    radios +=
                        radioRow(card, name, address, current.equals(address, ignoreCase = true)) {
                            pick(it, TargetDevicePrefs.Target(address = address, name = name))
                        }
                }
                if (bonded.isEmpty()) {
                    divider(card)
                    val row = actionRow(card, getString(R.string.setup_car_none), null, R.drawable.ic_bluetooth)
                    bindStatus(row, false, getString(R.string.setup_car_bt_settings)) {
                        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    }
                }
            }
        }
    }

    private fun buildConnection(column: LinearLayout) {
        column.addView(title(getString(R.string.setup_conn_title)))
        val card = card(column)
        switchRow(
            card,
            getString(R.string.setup_auto_connect),
            getString(R.string.setup_auto_connect_desc),
            AutoConnectPrefs.enabled(this),
        ) {
            AutoConnectPrefs.setEnabled(this, it)
        }
        divider(card)
        switchRow(card, getString(R.string.md_auto_play), getString(R.string.md_auto_play_desc), DisplayPrefs.autoPlayOnConnect(this)) {
            DisplayPrefs.setAutoPlayOnConnect(this, it)
        }
        divider(card)
        switchRow(
            card,
            getString(R.string.md_wait_reconnect),
            getString(R.string.md_wait_reconnect_desc),
            DisplayPrefs.waitForReconnect(this),
        ) {
            DisplayPrefs.setWaitForReconnect(this, it)
        }
        val lockCard = card(column)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val fake = prefs.getBoolean("alwaysOn", true)
        lateinit var fakeRadio: RadioButton
        lateinit var realRadio: RadioButton
        fakeRadio =
            radioRow(lockCard, getString(R.string.setup_lock_fake), getString(R.string.setup_lock_fake_desc), fake) {
                fakeRadio.isChecked = true
                realRadio.isChecked = false
                prefs.edit().putBoolean("alwaysOn", true).apply()
                io.github.ezn24.droidcar.ui.ScreenDimmer.enabled = true
            }
        divider(lockCard)
        realRadio =
            radioRow(lockCard, getString(R.string.setup_lock_real), getString(R.string.setup_lock_real_desc), !fake) {
                fakeRadio.isChecked = false
                realRadio.isChecked = true
                prefs.edit().putBoolean("alwaysOn", false).apply()
                io.github.ezn24.droidcar.ui.ScreenDimmer.enabled = false
            }
    }

    private fun buildDashboard(column: LinearLayout) {
        column.addView(title(getString(R.string.setup_dash_title)))
        column.addView(body(getString(R.string.setup_dash_body)))
        val card = card(column)
        val swapped = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dashboardPanesSwapped", false)
        lateinit var left: RadioButton
        lateinit var right: RadioButton
        left =
            radioRow(card, getString(R.string.setup_map_left), null, !swapped) {
                left.isChecked = true
                right.isChecked = false
                VirtualLauncher.setDashboardPanesSwapped(this, false)
            }
        divider(card)
        right =
            radioRow(card, getString(R.string.setup_map_right), null, swapped) {
                left.isChecked = false
                right.isChecked = true
                VirtualLauncher.setDashboardPanesSwapped(this, true)
            }
        val links = card(column)
        val map =
            actionRow(links, getString(R.string.setup_default_map), getString(R.string.setup_default_map_desc), R.drawable.ic_menu_map)
        bindStatus(map, false, getString(R.string.setup_open)) { openMenu(SettingsMenus.DEFAULT_MAP) }
        divider(links)
        val desktop =
            actionRow(
                links,
                getString(R.string.setup_desktop_apps),
                getString(R.string.setup_desktop_apps_desc),
                R.drawable.ic_menu_display,
            )
        bindStatus(desktop, false, getString(R.string.setup_open)) { openMenu(SettingsMenus.DESKTOP) }
    }

    private fun buildWallpaper(column: LinearLayout) {
        column.addView(title(getString(R.string.setup_wallpaper_title)))
        column.addView(body(getString(R.string.setup_wallpaper_body)))
        for (slot in listOf(WallpaperStore.Slot.DARK, WallpaperStore.Slot.LIGHT)) {
            column.addView(
                caption(getString(if (slot == WallpaperStore.Slot.DARK) R.string.setup_wallpaper_dark else R.string.setup_wallpaper_light)),
            )
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val scroller =
                android.widget.HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(strip)
                }
            column.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
            fillWallpaperStrip(strip, slot)
        }
    }

    private fun fillWallpaperStrip(
        strip: LinearLayout,
        slot: WallpaperStore.Slot,
    ) {
        strip.removeAllViews()
        val current = WallpaperStore.source(this, slot)
        val options = ArrayList<Pair<String, String>>()
        if (slot == WallpaperStore.Slot.LIGHT) options += WallpaperStore.SRC_SAME to getString(R.string.wp_same)
        options += WallpaperStore.SRC_DEFAULT to getString(R.string.wp_default)
        WallpaperPresets.ALL
            .sortedBy { it.light != (slot == WallpaperStore.Slot.LIGHT) }
            .forEach { options += (WallpaperStore.SRC_PRESET_PREFIX + it.id) to getString(it.nameRes) }
        val thumbW = dp(112f)
        val thumbH = dp(63f)
        for ((key, label) in options) {
            val selected = key == current
            val item =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, dp(10f), 0)
                }
            val image =
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background =
                        GradientDrawable().apply {
                            cornerRadius = dp(12f).toFloat()
                            setColor(color(R.color.m3_surface_high))
                            if (selected) setStroke(dp(3f), color(R.color.m3_primary))
                        }
                    val pad = if (selected) dp(3f) else 0
                    setPadding(pad, pad, pad, pad)
                    contentDescription = label
                    setOnClickListener {
                        WallpaperStore.setSource(this@SetupActivity, slot, key)
                        fillWallpaperStrip(strip, slot)
                    }
                }
            if (key == WallpaperStore.SRC_SAME) {
                image.scaleType = ImageView.ScaleType.CENTER
                image.setImageResource(R.drawable.ic_moon)
                image.imageTintList = ColorStateList.valueOf(color(R.color.m3_primary))
            } else {
                Bg.run("setup-wallpaper-thumb") {
                    val thumb =
                        runCatching {
                            WallpaperStore
                                .decodeSource(this, slot, key, maxOf(thumbW, thumbH) * 2)
                                ?.let { android.media.ThumbnailUtils.extractThumbnail(it, thumbW, thumbH) }
                        }.getOrNull()
                    runOnUiThread { if (!isDestroyed && thumb != null) image.setImageBitmap(thumb) }
                }
            }
            item.addView(image, LinearLayout.LayoutParams(thumbW, thumbH))
            item.addView(
                TextView(this).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(color(if (selected) R.color.m3_primary else R.color.m3_on_var))
                    if (selected) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4f), 0, 0)
                },
            )
            strip.addView(item)
        }
    }

    private fun buildNotifications(column: LinearLayout) {
        column.addView(title(getString(R.string.setup_notif_title)))
        column.addView(body(getString(R.string.setup_notif_body)))
        val card = card(column)
        val appsCard = card(column)

        fun showApps(show: Boolean) {
            appsCard.visibility = if (show) View.VISIBLE else View.GONE
        }
        switchRow(card, getString(R.string.show_car_notifications), null, DisplayPrefs.showCarNotifications(this)) {
            DisplayPrefs.setShowCarNotifications(this, it)
            if (it && !listenerGranted()) grantListener()
            showApps(it)
        }
        showApps(DisplayPrefs.showCarNotifications(this))
        appsCard.addView(body(getString(R.string.setup_notif_apps_loading)).apply { setPadding(dp(16f), dp(14f), dp(16f), dp(14f)) })
        val app = applicationContext
        Bg.run("setup-notif-apps") {
            val apps = runCatching { VirtualLauncher.cachedApps(app) }.getOrNull().orEmpty()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                appsCard.removeAllViews()
                val chosen = DisplayPrefs.notificationApps(this).toMutableSet()
                val list = apps.sortedBy { if (it.component.packageName in chosen) 0 else 1 }
                // Hundreds of apps: a recycling list of fixed height (building one row per app froze the page)
                val recycler =
                    androidx.recyclerview.widget.RecyclerView(this).apply {
                        layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@SetupActivity)
                        isNestedScrollingEnabled = true
                        adapter = AppAdapter(list, chosen)
                    }
                appsCard.addView(recycler, LinearLayout.LayoutParams(-1, dp(420f)))
            }
        }
    }

    /** Car notification app choice: icon, name and a check box per app; changes are saved at once. */
    private inner class AppAdapter(
        private val apps: List<VirtualLauncher.AppEntry>,
        private val chosen: MutableSet<String>,
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<AppAdapter.Holder>() {
        inner class Holder(
            val row: LinearLayout,
            val icon: ImageView,
            val label: TextView,
            val box: CheckBox,
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(row)

        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): Holder {
            val row =
                LinearLayout(this@SetupActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(52f)
                    setPadding(dp(16f), dp(6f), dp(12f), dp(6f))
                    background = ripple()
                    layoutParams =
                        androidx.recyclerview.widget.RecyclerView
                            .LayoutParams(-1, -2)
                }
            val icon = ImageView(this@SetupActivity)
            row.addView(icon, LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { rightMargin = dp(14f) })
            val label =
                TextView(this@SetupActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(color(R.color.m3_on_surface))
                }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val box =
                CheckBox(this@SetupActivity).apply {
                    buttonTintList = ColorStateList.valueOf(color(R.color.m3_primary))
                    isClickable = false
                }
            row.addView(box, LinearLayout.LayoutParams(-2, -2))
            return Holder(row, icon, label, box)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val entry = apps[position]
            val pkg = entry.component.packageName
            holder.icon.setImageDrawable(entry.icon)
            holder.label.text = entry.label
            holder.box.isChecked = pkg in chosen
            holder.row.setOnClickListener {
                if (pkg in chosen) chosen -= pkg else chosen += pkg
                holder.box.isChecked = pkg in chosen
                DisplayPrefs.setNotificationApps(this@SetupActivity, chosen.toSet())
            }
        }
    }

    /**
     * Dot size slider with a preview: a car screen in miniature (8:3, the wallpaper this phone theme uses) with the dot drawn
     * the same way and at the same proportion as on the car.
     */
    private fun buildDotSize(column: LinearLayout) {
        column.addView(caption(getString(R.string.setup_dot_size)))
        val card = card(column)
        val night = WallpaperStore.isNight(this)
        val previewH = dp(150f)
        val preview =
            FrameLayout(this).apply {
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(14f).toFloat()
                        setColor(color(R.color.m3_surface_high))
                    }
                clipToOutline = true
            }
        val wallpaper = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        preview.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
        val dot = ImageView(this)
        preview.addView(dot, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(12f) })
        card.addView(preview, LinearLayout.LayoutParams(-1, previewH).apply { setMargins(dp(16f), dp(14f), dp(16f), dp(4f)) })
        val slot = if (night) WallpaperStore.Slot.DARK else WallpaperStore.Slot.LIGHT
        Bg.run("setup-dot-wallpaper") {
            val bitmap = runCatching { WallpaperStore.decodeSource(this, slot, WallpaperStore.source(this, slot), dp(400f)) }.getOrNull()
            runOnUiThread { if (!isDestroyed && bitmap != null) wallpaper.setImageBitmap(bitmap) }
        }
        val value =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(color(R.color.m3_on_var))
                minWidth = dp(48f)
                gravity = Gravity.END
            }

        fun render(percent: Int) {
            value.text = "$percent%"
            dot.setImageBitmap(AssistiveDot.renderDot(night, (previewH * AssistiveDot.dotFractionOfHeight(percent)).toInt()))
        }
        val seek =
            android.widget.SeekBar(this).apply {
                max = AssistiveDot.SIZE_MAX - AssistiveDot.SIZE_MIN
                progress = AssistiveDot.sizePercent(this@SetupActivity) - AssistiveDot.SIZE_MIN
                progressTintList = ColorStateList.valueOf(color(R.color.m3_primary))
                thumbTintList = ColorStateList.valueOf(color(R.color.m3_primary))
                setOnSeekBarChangeListener(
                    object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            bar: android.widget.SeekBar,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            val percent = AssistiveDot.SIZE_MIN + progress
                            render(percent)
                            if (fromUser) AssistiveDot.setSizePercent(this@SetupActivity, percent)
                        }

                        override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit

                        override fun onStopTrackingTouch(bar: android.widget.SeekBar) = Unit
                    },
                )
            }
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(4f), dp(16f), dp(12f))
            }
        row.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value, LinearLayout.LayoutParams(-2, -2))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        render(AssistiveDot.sizePercent(this))
    }

    // ---- Actions ----

    private fun showDisclaimer() {
        DisclaimerDialog.show(
            activity = this,
            requireAcceptance = true,
            deadlineAt = SystemClock.elapsedRealtime() + DisclaimerDialog.COUNTDOWN_MS,
            onDecline = { finishAffinity() },
            onDismiss = {
                refreshPage?.invoke()
                if (DisclaimerPrefs.hasAcceptedCurrent(this) && pages[index] == Page.DISCLAIMER) go(index + 1)
            },
        )
    }

    private fun openMenu(id: Int) {
        SettingsMenus.ALL.firstOrNull { it.id == id }?.let { SettingsContentActivity.start(this, it) }
    }

    private fun setClusterNavigation(enabled: Boolean) {
        val app = applicationContext
        NavAccessibilityAccess.setEnabledByUser(app, enabled)
        Bg.run("setup-nav-a11y") {
            val active = NavAccessibilityAccess.sync(app) {}
            if (enabled && !active) {
                runOnUiThread { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            }
        }
    }

    private fun listenerGranted(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /** Notification access: silently through Shizuku when it is ready, else the system page. */
    private fun grantListener() {
        if (ShizukuGate.state(this) == ShizukuGate.State.READY) {
            val app = applicationContext
            Bg.run("setup-listener") {
                val ok = runCatching { NotificationAccess.ensureGranted(app) {} }.getOrDefault(false)
                runOnUiThread {
                    if (!ok) runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    refreshPage?.invoke()
                }
            }
        } else {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<Pair<String, String>> =
        runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.bondedDevices
                .orEmpty()
                .map { (it.name ?: it.address) to it.address }
                .sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())

    // ---- View helpers (m3 tokens, follow dark / light mode) ----

    private fun title(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.m3_on_surface))
            setPadding(0, dp(18f), 0, dp(10f))
        }

    private fun body(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.25f)
            setTextColor(color(R.color.m3_on_var))
            setPadding(0, 0, 0, dp(12f))
        }

    private fun caption(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.m3_primary))
            setPadding(0, dp(12f), 0, dp(8f))
        }

    private fun card(parent: LinearLayout): LinearLayout {
        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(20f).toFloat()
                        setColor(color(R.color.m3_surface_low))
                    }
                clipToOutline = true
            }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        return card
    }

    private fun divider(card: LinearLayout) {
        card.addView(
            View(this).apply { setBackgroundColor(color(R.color.m3_outline_var)) },
            LinearLayout.LayoutParams(-1, dp(1f)).apply { leftMargin = dp(16f) },
        )
    }

    private fun rowBase(
        card: LinearLayout,
        titleText: String,
        desc: String?,
        iconRes: Int?,
    ): Pair<LinearLayout, LinearLayout> {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64f)
                setPadding(dp(16f), dp(10f), dp(12f), dp(10f))
            }
        if (iconRes != null) {
            val tile =
                FrameLayout(this).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(color(R.color.m3_pri_container))
                        }
                }
            tile.addView(
                ImageView(this).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(color(R.color.m3_on_pri_container))
                },
                FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER),
            )
            row.addView(tile, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(14f) })
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = titleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(color(R.color.m3_on_surface))
            },
        )
        if (desc != null) {
            texts.addView(
                TextView(this).apply {
                    text = desc
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(color(R.color.m3_on_var))
                    setPadding(0, dp(2f), 0, 0)
                },
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row to texts
    }

    /** A row whose right side shows a "Done" badge or an action button (see [bindStatus]). */
    private fun actionRow(
        card: LinearLayout,
        titleText: String,
        desc: String?,
        iconRes: Int?,
    ): LinearLayout {
        val (row, _) = rowBase(card, titleText, desc, iconRes)
        val slot = FrameLayout(this)
        row.addView(slot, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        return row
    }

    private fun bindStatus(
        row: LinearLayout,
        ok: Boolean,
        actionLabel: String,
        action: () -> Unit,
    ) {
        val slot = row.getChildAt(row.childCount - 1) as FrameLayout
        slot.removeAllViews()
        if (ok) {
            slot.addView(
                TextView(this).apply {
                    text = getString(R.string.setup_done_badge)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.m3_ok))
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check_circle_small, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(color(R.color.m3_ok))
                    compoundDrawablePadding = dp(4f)
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
        } else {
            slot.addView(tonalButton(actionLabel).apply { setOnClickListener { action() } })
        }
    }

    private fun switchRow(
        card: LinearLayout,
        titleText: String,
        desc: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val (row, _) = rowBase(card, titleText, desc, null)
        val switch =
            SwitchCompat(this).apply {
                isChecked = checked
                thumbDrawable = ContextCompat.getDrawable(this@SetupActivity, R.drawable.sw_thumb)
                trackDrawable = ContextCompat.getDrawable(this@SetupActivity, R.drawable.sw_track)
                setOnCheckedChangeListener { _, value -> onChange(value) }
            }
        row.addView(switch, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        row.setOnClickListener { switch.toggle() }
    }

    private fun radioRow(
        card: LinearLayout,
        titleText: String,
        desc: String?,
        checked: Boolean,
        onPick: (RadioButton) -> Unit,
    ): RadioButton {
        val (row, _) = rowBase(card, titleText, desc, null)
        val radio =
            RadioButton(this).apply {
                isChecked = checked
                buttonTintList = ColorStateList.valueOf(color(R.color.m3_primary))
                isClickable = false
            }
        row.addView(radio, 0, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8f) })
        row.setOnClickListener { onPick(radio) }
        return radio
    }

    private fun textButton(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.m3_primary))
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            background = ripple()
        }

    private fun pillButton(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.m3_on_primary))
            gravity = Gravity.CENTER
            setPadding(dp(28f), 0, dp(28f), 0)
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(24f).toFloat()
                    setColor(color(R.color.m3_primary))
                }
            foreground = ripple()
        }

    private fun tonalButton(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.m3_on_pri_container))
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(18f).toFloat()
                    setColor(color(R.color.m3_pri_container))
                }
            foreground = ripple()
        }

    private fun ripple() =
        ContextCompat.getDrawable(
            this,
            TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId,
        )

    private companion object {
        const val KEY_PAGE = "setup_page"
        const val PAGE_MS = 220L
        const val SHIZUKU_URL = "https://shizuku.rikka.app/"
    }
}
