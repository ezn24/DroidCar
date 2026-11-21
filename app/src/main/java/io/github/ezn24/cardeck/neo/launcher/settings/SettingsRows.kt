package io.github.ezn24.cardeck.neo.launcher.settings

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ezn24.cardeck.neo.R
import io.github.ezn24.cardeck.neo.launcher.DesktopApps
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher

/** Settings list rows: group header / pinyin letter header / app (shared by the three app lists). */
sealed class SettingsRow {
    abstract val id: Long

    data class Header(
        val title: String,
        val key: String,
    ) : SettingsRow() {
        override val id: Long get() = key.hashCode().toLong()
    }

    data class App(
        val entry: VirtualLauncher.AppEntry,
    ) : SettingsRow() {
        override val id: Long get() = entry.component.hashCode().toLong()
    }
}

/**
 * Row building and letter index bar (shared by manage desktop / app supersampling / app landscape):
 * two-level grouping - first by desktop selection ("apps on desktop" -> "apps not on desktop").
 * Only the "not on desktop" group is sectioned by pinyin initial (A/B/.../#, digit apps go to #), and the letter index bar
 * maps to that group alone - **the on-desktop group is not sectioned**: it is usually small, and if it were sectioned its letters would
 * appear
 * first in the index bar (before #/A) while not appearing again in the later sections, so the bar would look
 * "mis-sorted" (user feedback 2026-10-16: Amap on desktop -> G listed first in the index bar).
 */
object SettingsRows {
    /** Panel theme colors (head-unit theme = original dark values, phone theme = MD3 tokens; attributes defined in attrs.xml). */
    fun color(
        context: Context,
        attr: Int,
    ): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    fun build(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): List<SettingsRow> {
        val selected = DesktopApps.selected(context, apps)
        val onDesktop = apps.filter { it.component.flattenToString() in selected }
        val offDesktop = apps.filter { it.component.flattenToString() !in selected }
        val rows = ArrayList<SettingsRow>(apps.size * 2)
        if (onDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("在桌面的应用（${onDesktop.size}）", "on")
            // Flat output (the list is already in pinyin order and stays ordered after filtering): no letter sections, not in the index bar
            rows += onDesktop.map { SettingsRow.App(it) }
        }
        if (offDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("不在桌面的应用（${offDesktop.size}）", "off")
            rows += letterRows(offDesktop)
        }
        return rows
    }

    private fun letterRows(group: List<VirtualLauncher.AppEntry>): List<SettingsRow> {
        val byLetter = LinkedHashMap<String, MutableList<VirtualLauncher.AppEntry>>()
        group.forEach { entry ->
            byLetter
                .getOrPut(VirtualLauncher.indexLetterOf(entry.label)) { mutableListOf() }
                .add(entry)
        }
        val rows = ArrayList<SettingsRow>(group.size + byLetter.size)
        byLetter.forEach { (letter, list) ->
            rows += SettingsRow.Header(letter, "letter_$letter")
            rows += list.map { SettingsRow.App(it) }
        }
        return rows
    }

    /** Right-hand letter index bar: rebuilt by an AdapterDataObserver when the row set changes. */
    fun setupIndexBar(
        fragment: androidx.fragment.app.Fragment,
        bar: LinearLayout,
        list: RecyclerView,
        rowsProvider: () -> List<SettingsRow>,
    ) {
        val density = fragment.resources.displayMetrics.density

        fun rebuild() {
            bar.removeAllViews()
            val letters = LinkedHashSet<String>()
            rowsProvider().forEach { row ->
                if (row is SettingsRow.Header && row.key.startsWith("letter_")) {
                    letters += row.key.removePrefix("letter_")
                }
            }
            letters.forEach { letter ->
                bar.addView(
                    TextView(fragment.requireContext()).apply {
                        text = letter
                        textSize = 9f
                        setTextColor(color(context, io.github.ezn24.cardeck.neo.R.attr.panelTextSecondary))
                        setPadding(0, (1.5f * density).toInt(), 0, (1.5f * density).toInt())
                        setOnClickListener {
                            val target =
                                rowsProvider()
                                    .indexOfFirst { it is SettingsRow.Header && it.key == "letter_$letter" }
                            if (target >= 0) {
                                (list.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(target, 0)
                            }
                        }
                    },
                )
            }
        }
        rebuild()
        list.adapter?.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = rebuild()

                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeInserted(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeRemoved(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeMoved(
                    fromPosition: Int,
                    toPosition: Int,
                    itemCount: Int,
                ) = rebuild()
            },
        )
    }

    /** Reusable ViewHolder for group header rows. */
    class HeaderHolder(
        val title: TextView,
    ) : RecyclerView.ViewHolder(title) {
        fun bind(text: String) {
            title.text = text
        }
    }

    fun inflateHeader(parent: ViewGroup): TextView =
        android.view.LayoutInflater
            .from(parent.context)
            .inflate(R.layout.item_section_header, parent, false) as TextView
}

/**
 * Skeleton adapter for app-list screens (shared by the desktop / supersample / landscape panels): the two-level group headers are
 * rendered uniformly by the skeleton while the app-row layout and binding are supplied by subclasses - replaces three near-copied adapter
 * boilerplates.
 */
abstract class AppRowsAdapter(
    private val fragment: androidx.fragment.app.Fragment,
    private val rowLayout: Int,
    private val appsProvider: () -> List<VirtualLauncher.AppEntry>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    /** App-row holder (content rows other than group headers). */
    abstract class AppHolder(
        row: android.view.View,
    ) : RecyclerView.ViewHolder(row) {
        abstract fun bind(entry: VirtualLauncher.AppEntry)
    }

    /** Creates the app-row holder ([rowLayout] is already inflated; no need to care about viewType). */
    protected abstract fun onCreateAppHolder(row: android.view.View): AppHolder

    val rows: List<SettingsRow>
        get() = SettingsRows.build(fragment.requireContext(), appsProvider())

    final override fun getItemViewType(position: Int): Int = if (rows[position] is SettingsRow.Header) 0 else 1

    final override fun getItemId(position: Int): Long = rows[position].id

    final override fun getItemCount(): Int = rows.size

    final override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        if (viewType == 0) {
            SettingsRows.HeaderHolder(SettingsRows.inflateHeader(parent))
        } else {
            onCreateAppHolder(fragment.layoutInflater.inflate(rowLayout, parent, false))
        }

    final override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = rows[position]) {
            is SettingsRow.Header -> (holder as SettingsRows.HeaderHolder).bind(row.title)
            is SettingsRow.App -> (holder as AppHolder).bind(row.entry)
        }
    }
}
