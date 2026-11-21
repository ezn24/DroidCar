package io.github.ezn24.cardeck.neo.launcher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.ezn24.cardeck.neo.R
import io.github.ezn24.cardeck.neo.SessionController
import io.github.ezn24.cardeck.neo.bt.AutoConnectPrefs
import io.github.ezn24.cardeck.neo.input.TouchRouter
import io.github.ezn24.cardeck.neo.launcher.AppIcons
import io.github.ezn24.cardeck.neo.launcher.DefaultMapPrefs
import io.github.ezn24.cardeck.neo.launcher.DesktopApps
import io.github.ezn24.cardeck.neo.launcher.DockPrefs
import io.github.ezn24.cardeck.neo.launcher.SupersamplePrefs
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher
import io.github.ezn24.cardeck.neo.launcher.settings.AppRowsAdapter.AppHolder
import io.github.ezn24.cardeck.neo.ui.DarkMessageDialog
import io.github.ezn24.cardeck.neo.ui.UiStrings
import io.github.ezn24.cardeck.neo.util.Bg

/**
 * Head-unit settings content panels (as Fragments): the right pane of the head-unit SettingsActivity two-pane and
 * the phone-side secondary menu pages share the same panel instances, with zero logic duplication.
 */
class DesktopPanelFragment : SettingsPanelFragment() {
    private var countText: TextView? = null
    private var selected: MutableSet<String> = mutableSetOf()
    private lateinit var adapter: DesktopAdapter
    private var searchQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_desktop, container, false)

    override fun onViewCreated(view: View) {
        countText = view.findViewById(R.id.settingsCount)
        adapter = DesktopAdapter()
        val search = view.findViewById<android.widget.EditText>(R.id.desktopSearch)
        val empty = view.findViewById<View>(R.id.desktopSearchEmpty)

        fun refreshSearch() {
            adapter.notifyDataSetChanged()
            empty.visibility = if (apps.isNotEmpty() && filteredApps().isEmpty()) View.VISIBLE else View.GONE
            view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.appList).scrollToPosition(0)
        }
        search.setText(searchQuery)
        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    searchQuery = s?.toString().orEmpty()
                    refreshSearch()
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            },
        )
        setupAppList(view, R.id.appList, R.id.appListIndex, adapter) {
            selected.clear()
            selected += DesktopApps.selected(requireContext(), apps)
            updateCount()
            refreshSearch()
        }
    }

    private fun filteredApps(): List<VirtualLauncher.AppEntry> =
        apps.filter {
            matchesAppSearch(searchQuery, it.label, it.component.packageName)
        }

    private fun updateCount() {
        countText?.text = UiStrings.format(R.string.selected_apps, selected.size)
    }

    private fun toggle(
        entry: VirtualLauncher.AppEntry,
        checked: Boolean,
    ) {
        val key = entry.component.flattenToString()
        if (checked) selected.add(key) else selected.remove(key)
        DesktopApps.save(requireContext(), selected)
        updateCount()
        hostToast(if (checked) UiStrings.format(R.string.app_shown, entry.label) else UiStrings.format(R.string.app_hidden, entry.label))
        adapter.notifyDataSetChanged()
    }

    private inner class DesktopAdapter : AppRowsAdapter(this@DesktopPanelFragment, R.layout.item_app_pick, { filteredApps() }) {
        override fun onCreateAppHolder(row: View): AppHolder = PickerHolder(row)
    }

    private inner class PickerHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.pickIcon)
        private val name: TextView = row.findViewById(R.id.pickName)
        private val check: ImageView = row.findViewById(R.id.pickCheck)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val key = entry.component.flattenToString()
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            check.setImageResource(
                if (key in selected) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            row.setOnClickListener {
                val nowChecked = key !in selected
                toggle(entry, nowChecked)
                check.setImageResource(
                    if (nowChecked) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
                )
            }
        }
    }
}

class SupersamplePanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_supersample, container, false)

    override fun onViewCreated(view: View) {
        setupAppList(view, R.id.ssList, R.id.ssListIndex, SupersampleAdapter())
    }

    private inner class SupersampleAdapter : AppRowsAdapter(this@SupersamplePanelFragment, R.layout.item_app_ss, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = SsHolder(row)
    }

    private inner class SsHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.ssIcon)
        private val name: TextView = row.findViewById(R.id.ssName)
        private val segAuto: TextView = row.findViewById(R.id.ssAuto)
        private val segOn: TextView = row.findViewById(R.id.ssOn)
        private val segOff: TextView = row.findViewById(R.id.ssOff)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val iconPx = (28 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            refresh(SupersamplePrefs.mode(requireContext(), entry.component))

            fun pick(
                mode: Int,
                label: String,
            ) {
                SupersamplePrefs.setMode(requireContext(), entry.component, mode)
                refresh(mode)
                hostToast(UiStrings.format(R.string.supersampling_saved, entry.label, label))
            }
            segAuto.setOnClickListener {
                pick(
                    SupersamplePrefs.MODE_AUTO,
                    UiStrings
                        .text(R.string.ui_106),
                )
            }
            segOn.setOnClickListener {
                pick(
                    SupersamplePrefs.MODE_ON,
                    UiStrings
                        .text(R.string.ui_107),
                )
            }
            segOff.setOnClickListener {
                pick(
                    SupersamplePrefs.MODE_OFF,
                    UiStrings
                        .text(R.string.ui_108),
                )
            }
        }

        private fun refresh(mode: Int) {
            val segs =
                listOf(
                    segAuto to SupersamplePrefs.MODE_AUTO,
                    segOn to SupersamplePrefs.MODE_ON,
                    segOff to SupersamplePrefs.MODE_OFF,
                )
            segs.forEach { (view, segMode) ->
                val active = segMode == mode
                view.background = if (active) requireActivity().getDrawable(R.drawable.bg_segment_sel) else null
                view.setTextColor(
                    if (active) {
                        SettingsRows.color(
                            requireContext(),
                            R.attr.panelOnAccent,
                        )
                    } else {
                        SettingsRows.color(requireContext(), R.attr.panelTextSecondary)
                    },
                )
            }
        }
    }
}

class OrientationPanelFragment : SettingsPanelFragment() {
    /**
     * Set of packages for which the system side really has the override on (queried once in the background when the panel opens, rows only
     * do in-memory lookups -
     * a single `dumpsys platform_compat` is about 30ms/36KB, never one per row).
     * Refreshed when the panel opens / after clearing leftovers; row switch clicks update in place to avoid re-querying.
     */
    private var systemOn: Set<String> = emptySet()
    private var systemStateLoaded = false
    private lateinit var listAdapter: OrientationAdapter
    private lateinit var panelView: View

    /**
     * Whether this device supports "force landscape" - null = probing.
     *
     * The two ChangeIds are OEM-specific (absent from the AOSP standard table): Honor Android 15 registers them,
     * Redmi Android 13 does not. When unsupported the whole column is greyed out with the reason, otherwise users would keep tapping the
     * switch
     * and only get a failure hint each time (measured 2026-10-18).
     */
    private var supported: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_orientation, container, false)

    override fun onViewCreated(view: View) {
        panelView = view
        listAdapter = OrientationAdapter()
        // Recompute the banner once the app list is ready (on the first frame apps is empty and everything would be misjudged as "not in
        // the list")
        setupAppList(view, R.id.oriList, R.id.oriListIndex, listAdapter) { updateOrphanBanner() }
        // Capability probe: one dumpsys (about 30ms, cached in-process), in parallel with the system-state query
        Bg.run("orientation-capability") {
            val ok = AppOrientationCompat.changeIdsRegistered()
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                supported = ok
                applyCapabilityState()
                listAdapter.notifyDataSetChanged()
            }
        }
        Bg.run("orientation-system-state") {
            val state = AppOrientationCompat.systemEnabled()
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                systemOn = state
                systemStateLoaded = true
                listAdapter.notifyDataSetChanged()
                updateOrphanBanner()
            }
        }
    }

    /**
     * Adjusts the panel according to the capability probe: when unsupported, show a banner and make the whole column dim and non-clickable.
     *
     * Grey out rather than hide the list: users see "this device cannot do it" instead of facing a vanished feature
     * and guessing why. The banner text explains it is a missing ROM item that only another device model fixes (to avoid mistaking it for
     * a Shizuku problem).
     */
    private fun applyCapabilityState() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriUnsupportedBanner) ?: return
        val list = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.oriList)
        val index = panelView.findViewById<LinearLayout>(R.id.oriListIndex)
        if (supported != false) {
            banner.visibility = View.GONE
            list?.alpha = 1f
            index?.alpha = 1f
            return
        }
        banner.visibility = View.VISIBLE
        banner.text =
            UiStrings
                .text(R.string.ui_117) +
            UiStrings
                .text(R.string.ui_118) +
            "当前 Android ${android.os.Build.VERSION.RELEASE}" +
            "（SDK ${android.os.Build.VERSION.SDK_INT}），开关置灰不可用。"
        // A dimmed column conveys "not operable"; click events are intercepted in OriHolder by supported
        list?.alpha = 0.38f
        index?.alpha = 0.38f
    }

    /**
     * Banner for out-of-panel leftovers (keys unreachable in the list but with the override still on): tap to confirm in a dialog, then
     * clean up.
     * Covers two kinds: uninstalled apps (override visible only in rawOverrides) and `package/component`
     * keys from historical dirty data - neither has a row in the app list, so the in-row small text cannot reach them.
     */
    private fun updateOrphanBanner() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriOrphanBanner) ?: return
        // Do not judge before the app list is ready: an empty apps would make every enabled override "look like an out-of-list leftover"
        // (onAppsReady calls this again once the list is really loaded)
        if (apps.isEmpty()) return
        val known = apps.map { it.component.packageName }.toSet()
        val orphans = systemOn.filter { it !in known }.sorted()
        if (orphans.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        // Component keys (containing /) are long: show only the package-name part, keep the full key for the dialog
        val summary =
            orphans.take(3).joinToString("、") { it.substringBefore('/') } +
                if (orphans.size > 3) " 等 ${orphans.size} 个" else ""
        banner.text = "另有 ${orphans.size} 个列表外的横屏残留：$summary\n点击清除"
        banner.setOnClickListener {
            val host = activity ?: return@setOnClickListener
            DarkMessageDialog.show(
                activity = host,
                title =
                    UiStrings
                        .text(R.string.ui_119),
                message =
                    UiStrings
                        .text(R.string.ui_120) +
                        orphans.joinToString("\n") { "· $it" } +
                        UiStrings
                            .text(R.string.ui_121) +
                        UiStrings
                            .text(R.string.ui_122),
                actions =
                    listOf(
                        DarkMessageDialog.Action(
                            UiStrings
                                .text(R.string.ui_081),
                        ) {},
                        DarkMessageDialog.Action(
                            UiStrings
                                .text(R.string.ui_123),
                            DarkMessageDialog.Emphasis.DANGER,
                        ) {
                            Bg.run("orientation-orphan-clear") {
                                val cleared = orphans.count { AppOrientationCompat.clearSystemOnly(it) }
                                activity?.runOnUiThread {
                                    if (!isAdded || isDetached) return@runOnUiThread
                                    hostToast(UiStrings.format(R.string.overrides_cleared, cleared, orphans.size))
                                    systemOn = systemOn - orphans.toSet()
                                    listAdapter.notifyDataSetChanged()
                                    updateOrphanBanner()
                                }
                            }
                        },
                    ),
            )
        }
    }

    private inner class OrientationAdapter :
        AppRowsAdapter(this@OrientationPanelFragment, R.layout.item_orientation_car, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = OriHolder(row)
    }

    private inner class OriHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.oriIcon)
        private val name: TextView = row.findViewById(R.id.oriName)
        private val hint: TextView = row.findViewById(R.id.oriHint)
        private val check: ImageView = row.findViewById(R.id.oriCheck)
        private lateinit var entry: VirtualLauncher.AppEntry
        private var shown = false

        override fun bind(entry: VirtualLauncher.AppEntry) {
            this.entry = entry
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            // The checked state on screen is the source of truth: click = invert the displayed state (do not re-read the preference,
            // to avoid a distorted read flipping the direction - measured: "cancel" turned into "enable" again)
            shown = AppOrientationCompat.isEnabled(requireContext(), pkg())
            refresh(shown)

            row.setOnClickListener {
                // The panel top already detected this device as unsupported (ROM did not register the ChangeId): ignore the click directly,
                // do not start a shell round trip doomed to fail, and do not nag with toasts.
                if (supported == false) return@setOnClickListener
                // **Async**: setEnabled does 2 shell calls inside (4s timeout each, 8s worst case);
                // calling it synchronously would block the main thread running the click callback into an ANR.
                val target = !shown
                shown = target
                refresh(shown) // Echo immediately (optimistic update), roll back on failure
                AppOrientationCompat.setEnabledAsync(requireContext(), pkg(), target) { result ->
                    if (!isAdded || isDetached) return@setEnabledAsync
                    when (result) {
                        AppOrientationCompat.WriteResult.OK -> {
                            // Update the system-side cache in place (the write has completed synchronously, no need to re-query dumpsys)
                            systemOn = if (target) systemOn + pkg() else systemOn - pkg()
                            refresh(target)
                            // am compat takes effect on write: the system kills the target app on the spot and it restarts with the new
                            // orientation on return
                            // (measured 2026-10-15: pid vanishes right after enable/disable). The old "check alive +
                            // ask whether to end it" flow asked about an app the system had already killed, and pidof races with the kill,
                            // so the prompt was sometimes right and sometimes wrong ("not running" misled); removed entirely, just inform
                            // honestly.
                            hostToast(
                                UiStrings.format(
                                    R.string.orientation_saved,
                                    entry.label,
                                    if (target) UiStrings.text(R.string.ui_085) else UiStrings.text(R.string.ui_086),
                                ),
                            )
                        }

                        AppOrientationCompat.WriteResult.UNSUPPORTED -> {
                            shown = !target
                            refresh(shown)
                            supported = false
                            applyCapabilityState()
                            hostToast(
                                if (AppOrientationCompat.changeIdsRegistered()) {
                                    UiStrings
                                        .text(R.string.ui_124)
                                } else {
                                    UiStrings
                                        .text(R.string.ui_125)
                                },
                            )
                        }

                        AppOrientationCompat.WriteResult.FAILED -> {
                            shown = !target
                            refresh(shown)
                            hostToast(
                                UiStrings
                                    .text(R.string.ui_126),
                            )
                        }
                    }
                }
            }
        }

        private fun pkg() = entry.component.packageName

        /**
         * Checkbox + system-side state small text. Three states:
         *   checked on + system on  -> grey text "landscape enabled (effective during the session)" (normal)
         *  checked off + system on -> red highlighted text "system leftover: landscape still active, tap to clear" (abnormal, tap to clear)
         *   otherwise               -> hidden (the checkbox itself conveys the preference, no extra noise)
         */
        private fun refresh(enabled: Boolean) {
            check.setImageResource(
                if (enabled) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            val onSystem = systemStateLoaded && pkg() in systemOn
            when {
                onSystem && enabled -> {
                    hint.text =
                        UiStrings
                            .text(R.string.ui_127)
                    hint.setTextColor(SettingsRows.color(requireContext(), R.attr.panelTextSecondary))
                    hint.visibility = View.VISIBLE
                    hint.setOnClickListener(null)
                    hint.isClickable = false
                }

                onSystem -> {
                    hint.text =
                        UiStrings
                            .text(R.string.ui_128)
                    hint.setTextColor(SettingsRows.color(requireContext(), R.attr.panelDanger))
                    hint.visibility = View.VISIBLE
                    hint.isClickable = true
                    // Clears only the system-side override, not the preference (the preference is already off); the dialog warns the app
                    // will restart
                    hint.setOnClickListener {
                        val host = activity ?: return@setOnClickListener
                        DarkMessageDialog.show(
                            activity = host,
                            title =
                                UiStrings
                                    .text(R.string.ui_119),
                            message =
                                "「${entry.label}」的系统侧横屏覆盖仍在生效（可能是卸载前的残留）。" +
                                    UiStrings
                                        .text(R.string.ui_129),
                            actions =
                                listOf(
                                    DarkMessageDialog.Action(
                                        UiStrings
                                            .text(R.string.ui_081),
                                    ) {},
                                    DarkMessageDialog.Action(
                                        UiStrings
                                            .text(R.string.ui_123),
                                        DarkMessageDialog.Emphasis.DANGER,
                                    ) {
                                        Bg.run("orientation-row-clear") {
                                            val ok = AppOrientationCompat.clearSystemOnly(pkg())
                                            activity?.runOnUiThread {
                                                if (!isAdded || isDetached) return@runOnUiThread
                                                if (ok) {
                                                    systemOn = systemOn - pkg()
                                                    refresh(shown)
                                                    hostToast(UiStrings.format(R.string.orientation_cleared, entry.label))
                                                } else {
                                                    hostToast(
                                                        UiStrings.text(
                                                            R.string.ui_130,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                ),
                        )
                    }
                }

                else -> {
                    hint.visibility = View.GONE
                    hint.setOnClickListener(null)
                }
            }
        }
    }
}

class DockPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_dock, container, false)

    override fun onViewCreated(view: View) {
        val scrimSeek = view.findViewById<SeekBar>(R.id.dockScrimSeek)
        val scrimValue = view.findViewById<TextView>(R.id.dockScrimValue)
        val blurSeek = view.findViewById<SeekBar>(R.id.dockBlurSeek)
        val blurValue = view.findViewById<TextView>(R.id.dockBlurValue)
        // Bar width (50%-100%, step 5; **takes effect on the next connection** - unlike the live scrim/blur above:
        // the dock virtual display's pixel size / density are created from the preference at session setup)
        val widthSeek = view.findViewById<SeekBar>(R.id.dockWidthSeek)
        val widthValue = view.findViewById<TextView>(R.id.dockWidthValue)
        val savedWidth = DockPrefs.widthPercent(requireContext())
        widthSeek.progress = (savedWidth - DockPrefs.WIDTH_MIN) / DockPrefs.WIDTH_STEP
        widthValue.text = "$savedWidth%"
        widthSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    widthValue.text = "${DockPrefs.WIDTH_MIN + progress * DockPrefs.WIDTH_STEP}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent =
                        DockPrefs.WIDTH_MIN + widthSeek.progress * DockPrefs.WIDTH_STEP
                    DockPrefs.setWidthPercent(requireContext(), percent)
                    hostToast(UiStrings.format(R.string.dock_scale_saved, percent))
                }
            },
        )

        scrimSeek.progress =
            DockPrefs
                .scrimOpacity(requireContext())
        scrimValue.text = "${scrimSeek.progress}%"
        scrimSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scrimValue.text = "$value%"
                    DockPrefs
                        .setScrimOpacity(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        blurSeek.progress =
            DockPrefs
                .blur(requireContext())
        blurValue.text = "${blurSeek.progress}%"
        blurSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    blurValue.text = "$value%"
                    DockPrefs
                        .setBlur(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
    }
}

class DefaultMapPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_map, container, false)

    override fun onViewCreated(view: View) {
        loadApps {
            if (isAdded) renderOptions(view)
        }
    }

    private fun renderOptions(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.mapOptions)
        container.removeAllViews()
        val current = DefaultMapPrefs.resolve(requireContext())?.packageName
        val suggested =
            apps
                .filter {
                    it.component.packageName in VirtualLauncher.MAP_PACKAGES_PRIORITY ||
                        it.component.packageName == current
                }.distinctBy { it.component.packageName }
        suggested.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_map_option, container, false)
            row.findViewById<TextView>(R.id.mapOptionName).text = entry.label
            val iconPx = (34 * resources.displayMetrics.density).toInt()
            row
                .findViewById<ImageView>(R.id.mapOptionIcon)
                .setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            row.findViewById<ImageView>(R.id.mapOptionRadio).setImageResource(
                if (entry.component.packageName == current) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
            )
            row.setOnClickListener { select(entry.component.packageName, view) }
            container.addView(row)
        }
        container.addView(
            layoutInflater.inflate(R.layout.item_map_option, container, false).apply {
                findViewById<TextView>(R.id.mapOptionName).setText(R.string.choose_other_map)
                findViewById<ImageView>(R.id.mapOptionIcon).setImageResource(R.drawable.ic_menu_grid)
                findViewById<ImageView>(R.id.mapOptionRadio).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList =
                        android.content.res.ColorStateList
                            .valueOf(SettingsRows.color(requireContext(), R.attr.panelTextSecondary))
                }
                isFocusable = true
                setOnClickListener {
                    showMapPicker(R.string.choose_other_map, apps, current) { entry ->
                        select(entry.component.packageName, view)
                    }
                }
            },
        )
    }

    private fun showMapPicker(
        title: Int,
        candidates: List<VirtualLauncher.AppEntry>,
        selected: String?,
        onSelect: (VirtualLauncher.AppEntry) -> Unit,
    ) {
        val ctx = requireContext()
        val body = layoutInflater.inflate(R.layout.dialog_map_picker, null)
        val list = body.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.mapPickerList)
        val search = body.findViewById<android.widget.EditText>(R.id.mapPickerSearch)
        val empty = body.findViewById<View>(R.id.mapPickerEmpty)
        val all = candidates.distinctBy { it.component.packageName }
        var filtered = all
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(ctx, R.style.CarDeckAppPicker)
                .setTitle(title)
                .setView(body)
                .create()

        class Row(
            val root: View,
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(root)
        val adapter =
            object : androidx.recyclerview.widget.RecyclerView.Adapter<Row>() {
                override fun getItemCount(): Int = filtered.size

                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int,
                ): Row = Row(layoutInflater.inflate(R.layout.item_map_option, parent, false))

                override fun onBindViewHolder(
                    holder: Row,
                    position: Int,
                ) {
                    val entry = filtered[position]
                    holder.root.findViewById<TextView>(R.id.mapOptionName).text = entry.label
                    holder.root.findViewById<ImageView>(R.id.mapOptionIcon).setImageBitmap(
                        AppIcons.rounded(entry.icon, (34 * resources.displayMetrics.density).toInt()),
                    )
                    holder.root.findViewById<ImageView>(R.id.mapOptionRadio).setImageResource(
                        if (entry.component.packageName == selected) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
                    )
                    holder.root.setOnClickListener {
                        dialog.dismiss()
                        onSelect(entry)
                    }
                }
            }
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        list.adapter = adapter
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    filtered = all.filter { matchesAppSearch(s?.toString().orEmpty(), it.label, it.component.packageName) }
                    empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                    adapter.notifyDataSetChanged()
                    list.scrollToPosition(0)
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            },
        )
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }

    private fun select(
        pkg: String,
        view: View,
    ) {
        DefaultMapPrefs.set(requireContext(), pkg)
        renderOptions(view)
        val context = requireContext().applicationContext
        Bg.run("default-map-changed") {
            if (VirtualLauncher.mapFullscreen) {
                DefaultMapPrefs.resolve(context)?.let { VirtualLauncher.launchApp(it) }
            } else if (VirtualLauncher.dashboardMode) {
                DefaultMapPrefs.resolve(context)?.let { VirtualLauncher.switchMap(it) }
            }
        }
    }
}

/**
 * "Display" panel: stream resolution scale (30%-160%, takes effect next connection) + target frame rate (30/60,
 * switchable live at runtime). The hero card shows the session's runtime effective values (SessionController snapshot),
 * distinct from this page's preference values.
 */
class DisplayPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_display, container, false)

    /** "Notification apps": multi-select list (empty = all). The cold app-list scan runs in the background. */
    private fun bindNotificationApps(row: TextView) {
        val ctx = requireContext()

        fun refresh() {
            val chosen = DisplayPrefs.notificationApps(ctx)
            row.text =
                if (chosen.isEmpty()) {
                    getString(R.string.notif_apps_row, getString(R.string.notif_apps_all))
                } else {
                    getString(R.string.notif_apps_row, getString(R.string.notif_apps_count, chosen.size))
                }
        }
        refresh()
        row.setOnClickListener {
            io.github.ezn24.cardeck.neo.util.Bg.run("notif-apps-scan") {
                val apps =
                    VirtualLauncher
                        .cachedApps(ctx.applicationContext)
                        .distinctBy { it.component.packageName }
                        .sortedBy { it.label.lowercase() }
                row.post {
                    if (!isAdded) return@post
                    showNotificationAppsPicker(apps) { refresh() }
                }
            }
        }
    }

    /**
     * Notification-app multi-select: same layout as "choose another installed app" (the map picker) - search box + rounded icon list,
     * with a checkbox at the row end instead of a radio; "All apps" = clear the list (no filtering), "Done" saves.
     */
    private fun showNotificationAppsPicker(
        candidates: List<VirtualLauncher.AppEntry>,
        onSaved: () -> Unit,
    ) {
        val ctx = requireContext()
        val body = layoutInflater.inflate(R.layout.dialog_map_picker, null)
        val list = body.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.mapPickerList)
        val search = body.findViewById<android.widget.EditText>(R.id.mapPickerSearch)
        val empty = body.findViewById<View>(R.id.mapPickerEmpty)
        val selected = DisplayPrefs.notificationApps(ctx).toMutableSet()
        // Selected ones first, for easy checking
        val all = candidates.sortedBy { if (it.component.packageName in selected) 0 else 1 }
        var filtered = all

        class Row(
            val root: View,
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(root)
        val adapter =
            object : androidx.recyclerview.widget.RecyclerView.Adapter<Row>() {
                override fun getItemCount(): Int = filtered.size

                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int,
                ): Row = Row(layoutInflater.inflate(R.layout.item_map_option, parent, false))

                override fun onBindViewHolder(
                    holder: Row,
                    position: Int,
                ) {
                    val entry = filtered[position]
                    val pkg = entry.component.packageName
                    val mark = holder.root.findViewById<ImageView>(R.id.mapOptionRadio)
                    holder.root.findViewById<TextView>(R.id.mapOptionName).text = entry.label
                    holder.root.findViewById<ImageView>(R.id.mapOptionIcon).setImageBitmap(
                        AppIcons.rounded(entry.icon, (34 * resources.displayMetrics.density).toInt()),
                    )
                    mark.setImageResource(if (pkg in selected) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off)
                    holder.root.setOnClickListener {
                        if (!selected.add(pkg)) selected.remove(pkg)
                        mark.setImageResource(if (pkg in selected) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off)
                    }
                }
            }
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        list.adapter = adapter
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    filtered = all.filter { matchesAppSearch(s?.toString().orEmpty(), it.label, it.component.packageName) }
                    empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                    adapter.notifyDataSetChanged()
                    list.scrollToPosition(0)
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            },
        )
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(ctx, R.style.CarDeckAppPicker)
                .setTitle(R.string.notif_apps)
                .setView(body)
                .setPositiveButton(R.string.notif_apps_done) { _, _ ->
                    DisplayPrefs.setNotificationApps(ctx, selected)
                    onSaved()
                }.setNeutralButton(R.string.notif_apps_all) { _, _ ->
                    DisplayPrefs.setNotificationApps(ctx, emptySet())
                    onSaved()
                }.create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }

    private fun bindDisplayToggle(
        row: View,
        title: Int,
        note: Int,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        row.findViewById<TextView>(R.id.displayToggleTitle).setText(title)
        row.findViewById<TextView>(R.id.displayToggleNote).setText(note)
        val on = row.findViewById<TextView>(R.id.displayToggleOn)
        val off = row.findViewById<TextView>(R.id.displayToggleOff)
        var current = initial

        fun render() {
            for ((button, selected) in listOf(on to current, off to !current)) {
                button.isSelected = selected
                button.setBackgroundResource(if (selected) R.drawable.bg_segment_sel else R.drawable.bg_segment)
                button.setTextColor(
                    if (selected) {
                        SettingsRows.color(
                            requireContext(),
                            R.attr.panelOnAccent,
                        )
                    } else {
                        SettingsRows.color(requireContext(), R.attr.panelTextSecondary)
                    },
                )
            }
        }

        fun select(value: Boolean) {
            if (current == value) return
            onChange(value)
            current = value
            render()
        }

        on.setOnClickListener { select(true) }
        off.setOnClickListener { select(false) }
        render()
    }

    override fun onViewCreated(view: View) {
        val ctx = requireContext()
        val languageTags = listOf("", "zh-Hant", "zh-Hans", "en")
        val languageNames = arrayOf(getString(R.string.follow_system), "繁體中文", "简体中文", "English")
        val currentLanguage =
            languageTags
                .indexOf(
                    io.github.ezn24.cardeck.neo.ui.AppLanguage
                        .current(ctx),
                ).coerceAtLeast(0)
        view.findViewById<TextView>(R.id.displayLanguagePicker).apply {
            text = languageNames[currentLanguage]
            setOnClickListener {
                androidx.appcompat.app.AlertDialog
                    .Builder(ctx, R.style.CarDeckAppPicker)
                    .setTitle(R.string.language)
                    .setSingleChoiceItems(languageNames, currentLanguage) { dialog, index ->
                        dialog.dismiss()
                        if (index != currentLanguage) {
                            io.github.ezn24.cardeck.neo.ui.AppLanguage
                                .set(ctx, languageTags[index])
                        }
                    }.show()
            }
        }

        // Narrow-screen dashboard layout (takes effect next connection, narrow landscape only): side-by-side / Dock at bottom
        val layoutKeys =
            listOf(
                io.github.ezn24.cardeck.neo.render.DashLayout.PREF_SIDE,
                io.github.ezn24.cardeck.neo.render.DashLayout.PREF_DOCK_BOTTOM,
            )
        val layoutNames =
            arrayOf(
                getString(R.string.dash_layout_side),
                getString(R.string.dash_layout_dock_bottom),
            )
        view.findViewById<TextView>(R.id.displayDashLayoutPicker).apply {
            fun currentIndex() = layoutKeys.indexOf(DisplayPrefs.dashboardLayout(ctx)).coerceAtLeast(0)
            text = layoutNames[currentIndex()]
            setOnClickListener {
                androidx.appcompat.app.AlertDialog
                    .Builder(ctx, R.style.CarDeckAppPicker)
                    .setTitle(R.string.dash_layout)
                    .setSingleChoiceItems(layoutNames, currentIndex()) { dialog, index ->
                        dialog.dismiss()
                        DisplayPrefs.setDashboardLayout(ctx, layoutKeys[index])
                        text = layoutNames[index]
                    }.show()
            }
        }

        bindDisplayToggle(
            view.findViewById(R.id.displaySwapPanes),
            R.string.swap_dashboard_panes,
            R.string.dashboard_layout_note,
            ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("dashboardPanesSwapped", false),
        ) { VirtualLauncher.setDashboardPanesSwapped(ctx, it) }
        run {
            val dot = io.github.ezn24.cardeck.neo.launcher.AssistiveDot
            val seek = view.findViewById<SeekBar>(R.id.assistiveDotSizeSeek)
            val value = view.findViewById<TextView>(R.id.assistiveDotSizeValue)
            seek.max = dot.SIZE_MAX - dot.SIZE_MIN
            seek.progress = dot.sizePercent(ctx) - dot.SIZE_MIN
            value.text = "${dot.sizePercent(ctx)}%"
            seek.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: SeekBar,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val percent = progress + dot.SIZE_MIN
                        value.text = "$percent%"
                        if (fromUser) dot.setSizePercent(ctx, percent)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
        }
        bindDisplayToggle(
            view.findViewById(R.id.displayChatMode),
            R.string.chat_mode,
            R.string.chat_mode_note,
            io.github.ezn24.cardeck.neo.launcher.ChatMode
                .enabled(ctx),
        ) {
            io.github.ezn24.cardeck.neo.launcher.ChatMode
                .setEnabled(ctx, it)
        }
        bindNotificationApps(view.findViewById(R.id.displayNotificationApps))
        bindDisplayToggle(
            view.findViewById(R.id.displayAssistOverlay),
            R.string.show_assist_overlay,
            R.string.assist_overlay_note,
            DisplayPrefs.showAssistOverlay(ctx),
        ) { DisplayPrefs.setShowAssistOverlay(ctx, it) }
        bindDisplayToggle(
            view.findViewById(R.id.displayClarityFirst),
            R.string.clarity_first,
            R.string.clarity_first_note,
            DisplayPrefs.clarityFirst(ctx),
        ) { DisplayPrefs.setClarityFirst(ctx, it) }
        bindDisplayToggle(
            view.findViewById(R.id.displayHevc),
            R.string.hevc_video,
            R.string.hevc_video_note,
            DisplayPrefs.hevc(ctx),
        ) { DisplayPrefs.setHevc(ctx, it) }
        bindDisplayToggle(
            view.findViewById(R.id.displayMutePhoneAudio),
            R.string.mute_phone_audio,
            R.string.mute_phone_audio_note,
            DisplayPrefs.mutePhoneAudio(ctx),
        ) { DisplayPrefs.setMutePhoneAudio(ctx, it) }

        val scaleSeek = view.findViewById<SeekBar>(R.id.displayScaleSeek)
        val scaleValue = view.findViewById<TextView>(R.id.displayScaleValue)
        val scaleNote = view.findViewById<TextView>(R.id.displayScaleNote)
        val fps30 = view.findViewById<TextView>(R.id.btnFps30)
        val fps60 = view.findViewById<TextView>(R.id.btnFps60)

        // Real screen-off on connect (physical panel powered down): default off; toggling mid-session takes effect immediately
        val screenOffOn = view.findViewById<TextView>(R.id.btnScreenOffOn)
        val screenOffOff = view.findViewById<TextView>(R.id.btnScreenOffOff)
        var screenOffEnabled = DisplayPrefs.screenOffOnConnect(ctx)

        fun refreshScreenOff(enabled: Boolean) {
            screenOffOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            screenOffOn.setTextColor(
                if (enabled) {
                    SettingsRows.color(
                        requireContext(),
                        R.attr.panelOnAccent,
                    )
                } else {
                    SettingsRows.color(requireContext(), R.attr.panelTextSecondary)
                },
            )
            screenOffOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            screenOffOff.setTextColor(
                if (enabled) {
                    SettingsRows.color(
                        requireContext(),
                        R.attr.panelTextSecondary,
                    )
                } else {
                    SettingsRows.color(requireContext(), R.attr.panelOnAccent)
                },
            )
        }

        refreshScreenOff(screenOffEnabled)

        fun applyScreenOff(enabled: Boolean) {
            if (enabled == screenOffEnabled) return
            screenOffEnabled = enabled
            DisplayPrefs.setScreenOffOnConnect(ctx, enabled)
            refreshScreenOff(enabled)
            // Operable on the head-unit screen: collect/release immediately, no need to wait for the next connection
            SessionController.applyScreenOffPreference(enabled)
            hostToast(
                if (enabled) {
                    UiStrings.text(
                        R.string.ui_131,
                    )
                } else {
                    UiStrings
                        .text(R.string.ui_132)
                },
            )
        }
        screenOffOn.setOnClickListener { applyScreenOff(true) }
        screenOffOff.setOnClickListener { applyScreenOff(false) }

        // Auto-connect when getting in the car (start a session automatically when the head-unit Bluetooth ACL connects): default on
        val autoConnectOn = view.findViewById<TextView>(R.id.btnAutoConnectOn)
        val autoConnectOff = view.findViewById<TextView>(R.id.btnAutoConnectOff)
        var autoConnectEnabled = AutoConnectPrefs.enabled(ctx)

        fun refreshAutoConnect(enabled: Boolean) {
            autoConnectOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            autoConnectOn.setTextColor(
                if (enabled) {
                    SettingsRows.color(
                        requireContext(),
                        R.attr.panelOnAccent,
                    )
                } else {
                    SettingsRows.color(requireContext(), R.attr.panelTextSecondary)
                },
            )
            autoConnectOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            autoConnectOff.setTextColor(
                if (enabled) {
                    SettingsRows.color(
                        requireContext(),
                        R.attr.panelTextSecondary,
                    )
                } else {
                    SettingsRows.color(requireContext(), R.attr.panelOnAccent)
                },
            )
        }

        refreshAutoConnect(autoConnectEnabled)

        fun applyAutoConnect(enabled: Boolean) {
            if (enabled == autoConnectEnabled) return
            autoConnectEnabled = enabled
            AutoConnectPrefs.setEnabled(ctx, enabled)
            refreshAutoConnect(enabled)
            hostToast(
                if (enabled) {
                    UiStrings.text(
                        R.string.ui_133,
                    )
                } else {
                    UiStrings
                        .text(R.string.ui_134)
                },
            )
        }
        autoConnectOn.setOnClickListener { applyAutoConnect(true) }
        autoConnectOff.setOnClickListener { applyAutoConnect(false) }

        // Raw HID log (key-mapping calibration): default off; when on, every hidSendReport frame writes a HID-RAW line
        val hidRawOn = view.findViewById<TextView>(R.id.btnHidRawOn)
        val hidRawOff = view.findViewById<TextView>(R.id.btnHidRawOff)
        var hidRawEnabled = DisplayPrefs.hidRawLog(ctx)

        fun refreshHidRaw(enabled: Boolean) {
            hidRawOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            hidRawOn.setTextColor(
                if (enabled) {
                    SettingsRows.color(
                        requireContext(),
                        R.attr.panelOnAccent,
                    )
                } else {
                    SettingsRows.color(requireContext(), R.attr.panelTextSecondary)
                },
            )
            hidRawOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            hidRawOff.setTextColor(
                if (enabled) {
                    SettingsRows.color(
                        requireContext(),
                        R.attr.panelTextSecondary,
                    )
                } else {
                    SettingsRows.color(requireContext(), R.attr.panelOnAccent)
                },
            )
        }

        refreshHidRaw(hidRawEnabled)

        fun applyHidRaw(enabled: Boolean) {
            if (enabled == hidRawEnabled) return
            hidRawEnabled = enabled
            DisplayPrefs.setHidRawLog(ctx, enabled)
            refreshHidRaw(enabled)
            hostToast(
                if (enabled) {
                    UiStrings.text(
                        R.string.ui_135,
                    )
                } else {
                    UiStrings
                        .text(R.string.ui_136)
                },
            )
        }
        hidRawOn.setOnClickListener { applyHidRaw(true) }
        hidRawOff.setOnClickListener { applyHidRaw(false) }

        // Slider progress (0..26) -> percent (30..160, step 5)
        fun progressOf(percent: Int) = (percent - DisplayPrefs.SCALE_MIN) / DisplayPrefs.SCALE_STEP

        fun percentOf(progress: Int) = DisplayPrefs.SCALE_MIN + progress * DisplayPrefs.SCALE_STEP

        val saved = DisplayPrefs.scalePercent(ctx)
        scaleSeek.progress = progressOf(saved)
        scaleValue.text = "$saved%"

        fun refreshScaleNote() {
            val runningNow =
                SessionController.videoSender
                    ?.isRunning == true
            scaleNote.text =
                if (runningNow) {
                    UiStrings
                        .text(R.string.ui_137) +
                        UiStrings.format(R.string.resolution_active, SessionController.activeScalePercent)
                } else {
                    UiStrings
                        .text(R.string.ui_093)
                }
        }
        refreshScaleNote()

        scaleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scaleValue.text = "${percentOf(progress)}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent = percentOf(scaleSeek.progress)
                    DisplayPrefs.setScalePercent(ctx, percent)
                    hostToast(UiStrings.format(R.string.resolution_saved, percent))
                    refreshScaleNote()
                }
            },
        )

        fun refreshFps(active30: Boolean) {
            // Unselected tiles use a dark base (same color as the container) rather than a null background, to avoid the background
            // resetting the padding
            fps30.setBackgroundResource(
                if (active30) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            fps30.setTextColor(
                if (active30) {
                    SettingsRows.color(
                        requireContext(),
                        R.attr.panelOnAccent,
                    )
                } else {
                    SettingsRows.color(requireContext(), R.attr.panelTextSecondary)
                },
            )
            fps60.setBackgroundResource(
                if (active30) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            fps60.setTextColor(
                if (active30) {
                    SettingsRows.color(
                        requireContext(),
                        R.attr.panelTextSecondary,
                    )
                } else {
                    SettingsRows.color(requireContext(), R.attr.panelOnAccent)
                },
            )
        }

        val savedFps = DisplayPrefs.fps(ctx)
        refreshFps(savedFps < 60)

        fun applyFps(fps: Int) {
            DisplayPrefs.setFps(ctx, fps)
            refreshFps(fps < 60)
            VirtualLauncher
                .setStreamFps(fps)
            val live =
                SessionController.videoSender
                    ?.isRunning == true
            hostToast(UiStrings.format(R.string.frame_rate_saved, fps))
            if (live) SessionController.activeTargetFps = fps
        }
        fps30.setOnClickListener { applyFps(30) }
        fps60.setOnClickListener { applyFps(60) }

        // Touch-injection throttle (0 = off, step 8ms): eases event pile-up when "interaction lags but frame rate is normal"
        val throttleSeek = view.findViewById<SeekBar>(R.id.throttleSeek)
        val throttleValue = view.findViewById<TextView>(R.id.throttleValue)

        fun throttleText(ms: Int) = if (ms <= 0) UiStrings.text(R.string.touch_off) else "${ms}ms"

        val savedThrottle = DisplayPrefs.moveThrottleMs(ctx)
        throttleSeek.progress = savedThrottle / 8
        throttleValue.text = throttleText(savedThrottle)
        TouchRouter.moveThrottleMs = savedThrottle.toLong()

        throttleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    val ms = progress * 8
                    throttleValue.text = throttleText(ms)
                    DisplayPrefs.setMoveThrottleMs(ctx, ms)
                    TouchRouter.moveThrottleMs = ms.toLong()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    hostToast(
                        UiStrings
                            .text(R.string.ui_138) +
                            throttleText(DisplayPrefs.moveThrottleMs(ctx)),
                    )
                }
            },
        )
    }
}
