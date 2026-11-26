package io.github.ezn24.droidcar.launcher

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.github.ezn24.droidcar.PhoneSettingsActivity
import io.github.ezn24.droidcar.R
import io.github.ezn24.droidcar.launcher.settings.DefaultMapPanelFragment
import io.github.ezn24.droidcar.launcher.settings.DesktopPanelFragment
import io.github.ezn24.droidcar.launcher.settings.DisplayPanelFragment
import io.github.ezn24.droidcar.launcher.settings.DockPanelFragment
import io.github.ezn24.droidcar.launcher.settings.OrientationPanelFragment
import io.github.ezn24.droidcar.launcher.settings.SettingsMenus
import io.github.ezn24.droidcar.launcher.settings.SettingsToastHost
import io.github.ezn24.droidcar.launcher.settings.SupersamplePanelFragment

/**
 * Head-unit settings (two-pane host: left menu / right content Fragment container).
 * Content pages are Fragments (settings package); the head-unit two-pane and the phone-side secondary menu share the same panels.
 */
class SettingsActivity :
    AppCompatActivity(),
    SettingsToastHost {
    private var toastView: TextView? = null
    private val menus = mutableListOf<LinearLayout>()
    private var currentMenu = -1

    override fun onResume() {
        super.onResume()
        // Knob focus frames this screen's controls while it is on the car
        io.github.ezn24.droidcar.input.KnobFocus
            .onOwnScreenResumed(this)
    }

    override fun onPause() {
        io.github.ezn24.droidcar.input.KnobFocus
            .onOwnScreenPaused(this)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light/dark follows the phone (same criterion as the head-unit wallpaper dark/light slots), accent follows system Material You
        super.onCreate(savedInstanceState)
        if (VirtualLauncher.isPortraitCanvas()) {
            // Narrow portrait: two panes get cramped - forward to the phone-side secondary menu (list -> content page)
            startActivity(
                android.content.Intent(
                    this,
                    PhoneSettingsActivity::class.java,
                ),
            )
            finish()
            return
        }
        setContentView(R.layout.activity_settings)
        VirtualLauncher.padForDock(this)
        toastView = findViewById(R.id.settingsToast)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { close() }

        collectMenus()
        if (savedInstanceState == null) showMenu(0) else restoreCurrentMenu()
    }

    /** Collected from the XML menu entries (id order is menu order). */
    private fun collectMenus() {
        val ids =
            intArrayOf(
                R.id.menuDesktopApps,
                R.id.menuSupersample,
                R.id.menuOrientation,
                R.id.menuDock,
                R.id.menuDefaultMap,
                R.id.menuDisplay,
            )
        ids.forEach { id -> menus.add(findViewById<LinearLayout>(id)) }
        menus.forEachIndexed { index, menu ->
            menu.setOnClickListener { showMenu(index) }
        }
    }

    /** Menu switch: selected-state styling + replaces the right-pane Fragment. */
    private fun showMenu(index: Int) {
        if (index == currentMenu) {
            applyMenuStyle(index)
            return
        }
        currentMenu = index
        applyMenuStyle(index)
        val fragment = fragmentFor(index)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settingsContainer, fragment)
            .commit()
    }

    private fun restoreCurrentMenu() {
        currentMenu = -1
        val restored = supportFragmentManager.findFragmentById(R.id.settingsContainer)
        val index =
            when (restored) {
                is DesktopPanelFragment -> SettingsMenus.DESKTOP
                is SupersamplePanelFragment -> SettingsMenus.SUPERSAMPLE
                is OrientationPanelFragment -> SettingsMenus.ORIENTATION
                is DockPanelFragment -> SettingsMenus.DOCK
                is DefaultMapPanelFragment -> SettingsMenus.DEFAULT_MAP
                is DisplayPanelFragment -> SettingsMenus.DISPLAY
                else -> 0
            }
        showMenu(index)
    }

    private fun applyMenuStyle(index: Int) {
        val ripple =
            runCatching {
                val out = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                getDrawable(out.resourceId)
            }.getOrNull()
        menus.forEachIndexed { i, menu ->
            menu.background = if (i == index) getDrawable(R.drawable.bg_menu_item) else ripple
            for (c in 0 until menu.childCount) {
                val child = menu.getChildAt(c)
                if (child is TextView) {
                    child.setTextColor(getColor(if (i == index) R.color.m3_on_sec_container else R.color.m3_on_var))
                    child.paint.isFakeBoldText = i == index
                }
            }
        }
    }

    private fun fragmentFor(index: Int): Fragment = SettingsMenus.createFragment(index)

    private fun close() {
        finish()
    }

    override fun onBackPressed() = close()

    /** In-page light hint (no system Toast: its position on a virtual display is uncontrollable). */
    override fun showToast(text: String) {
        val view = toastView ?: return
        view.text = text
        view.animate().cancel()
        view.alpha = 0f
        view
            .animate()
            .alpha(1f)
            .setDuration(160)
            .withEndAction {
                view.postDelayed({
                    view
                        .animate()
                        .alpha(0f)
                        .setDuration(220)
                        .start()
                }, 1_250)
            }.start()
    }
}
