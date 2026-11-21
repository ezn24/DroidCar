package io.github.ezn24.cardeck.neo

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.github.ezn24.cardeck.neo.util.Bg
import io.github.ezn24.cardeck.neo.util.SessionHooks
import io.github.ezn24.cardeck.neo.util.SessionHooks.Extra
import io.github.ezn24.cardeck.neo.util.SessionHooks.ExtraType
import io.github.ezn24.cardeck.neo.util.SessionHooks.HookIntent
import io.github.ezn24.cardeck.neo.util.SessionHooks.Target

/**
 * "Connection automation" settings page (MacroDroid "send Intent" style): two triggers (connected / disconnected), each with a list of Intents,
 * each Intent edited per field (target, Action, package + app picker, class name, Data, MIME, Categories, typed
 * Extras), individually toggled and testable. The UI is built in code (many fields and dynamic rows; XML would be harder to maintain),
 * with colors/background from the phone-side MD3 tokens.
 */
class AutomationActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(color(R.color.m3_surface))
            }
        root.addView(header())
        content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(24))
            }
        root.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(content)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        render()
    }

    // ---- Page ----

    private fun header(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(20), dp(10))
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_chev_left)
                    setBackgroundResource(R.drawable.bg_ripple_circle)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    contentDescription = getString(R.string.ui_064)
                    setOnClickListener { finish() }
                },
                LinearLayout.LayoutParams(dp(38), dp(38)),
            )
            addView(
                text(getString(R.string.md_hooks), 18f, R.color.m3_on_surface, bold = true).apply {
                    setPadding(dp(10), 0, 0, 0)
                },
            )
        }

    private fun render() {
        content.removeAllViews()
        content.addView(text(getString(R.string.md_hooks_intro), 12.5f, R.color.m3_on_var).apply { setPadding(dp(4), 0, dp(4), dp(4)) })
        section(SessionHooks.EVENT_CONNECTED, R.string.md_hooks_connected)
        section(SessionHooks.EVENT_DISCONNECTED, R.string.md_hooks_disconnected)
    }

    private fun section(
        event: String,
        title: Int,
    ) {
        val hooks = SessionHooks.load(this, event)
        val card = card()
        card.addView(text(getString(title), 15f, R.color.m3_on_surface, bold = true))
        if (hooks.isEmpty()) {
            card.addView(text(getString(R.string.md_hooks_empty), 12.5f, R.color.m3_on_var).apply { setPadding(0, dp(6), 0, dp(4)) })
        }
        hooks.forEachIndexed { index, hook -> card.addView(hookRow(event, hooks, index, hook)) }
        card.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
                addView(tonalButton(getString(R.string.md_hooks_add)) { edit(event, hooks, -1) })
                if (hooks.isNotEmpty()) {
                    addView(
                        tonalButton(getString(R.string.md_hooks_test_all)) {
                            Bg.run("hook-test-all") {
                                val results = hooks.filter { it.enabled }.map { SessionHooks.send(this@AutomationActivity, it, event) }
                                runOnUiThread { toast(results.joinToString("\n").ifEmpty { getString(R.string.md_hooks_none_enabled) }) }
                            }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(8)
                        },
                    )
                }
            },
        )
        content.addView(card)
    }

    private fun hookRow(
        event: String,
        hooks: List<HookIntent>,
        index: Int,
        hook: HookIntent,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            background = rippleBackground()
            setOnClickListener { edit(event, hooks, index) }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        text(
                            hook.summary().ifBlank { getString(R.string.md_hooks_unnamed) },
                            14f,
                            R.color.m3_on_surface,
                            bold = true,
                        ).apply {
                            maxLines = 2
                        },
                    )
                    val detail =
                        listOf(targetLabel(hook.target), hook.packageName, if (hook.extras.isEmpty()) "" else "${hook.extras.size} extras")
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    addView(text(detail, 12f, R.color.m3_on_var))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                SwitchCompat(context).apply {
                    isChecked = hook.enabled
                    thumbTintList = ColorStateList.valueOf(color(R.color.m3_primary))
                    setOnCheckedChangeListener { _, checked ->
                        val next = hooks.toMutableList().also { it[index] = hook.copy(enabled = checked) }
                        SessionHooks.save(this@AutomationActivity, event, next)
                        render()
                    }
                },
            )
        }

    // ---- Editor ----

    private fun edit(
        event: String,
        hooks: List<HookIntent>,
        index: Int,
    ) {
        val original = hooks.getOrNull(index) ?: HookIntent()
        var target = original.target
        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }

        form.addView(label(R.string.md_hooks_target))
        val segments = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val segmentViews = mutableListOf<TextView>()

        fun paintSegments() {
            segmentViews.forEachIndexed { i, view ->
                val selected = Target.entries[i] == target
                view.setBackgroundResource(if (selected) R.drawable.bg_segment_sel else R.drawable.bg_segment)
                view.setTextColor(color(if (selected) R.color.m3_on_primary else R.color.m3_on_var))
            }
        }
        Target.entries.forEach { t ->
            val view =
                text(targetLabel(t), 13f, R.color.m3_on_var).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    setOnClickListener {
                        target = t
                        paintSegments()
                    }
                }
            segmentViews += view
            segments.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        paintSegments()
        form.addView(segments)

        val action = field(form, R.string.md_hooks_action, original.action, "com.example.ACTION")
        val pkg = field(form, R.string.md_hooks_package, original.packageName, "com.example.app")
        form.addView(
            tonalButton(getString(R.string.md_hooks_pick_app)) { pickApp { pkg.setText(it) } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )
        val cls = field(form, R.string.md_hooks_class, original.className, ".MainActivity")
        val data = field(form, R.string.md_hooks_data, original.data, "https://… / tel:…")
        val mime = field(form, R.string.md_hooks_mime, original.mimeType, "text/plain")
        val cats = field(form, R.string.md_hooks_categories, original.categories.joinToString(", "), "android.intent.category.DEFAULT")

        form.addView(label(R.string.md_hooks_extras))
        val extrasBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(extrasBox)
        val extraRows = mutableListOf<ExtraRow>()

        fun addExtra(extra: Extra) {
            val row = ExtraRow(extra.type)
            row.view =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, 0)
                }
            row.key = input(extra.key, getString(R.string.md_hooks_extra_key))
            row.value = input(extra.value, getString(R.string.md_hooks_extra_value))
            val typeChip =
                text(row.type.name.lowercase(), 12f, R.color.m3_on_sec_container).apply {
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    setOnClickListener {
                        row.type = ExtraType.entries[(row.type.ordinal + 1) % ExtraType.entries.size]
                        text = row.type.name.lowercase()
                    }
                }
            val remove =
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_close)
                    imageTintList = ColorStateList.valueOf(color(R.color.m3_on_var))
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener {
                        extrasBox.removeView(row.view)
                        extraRows.remove(row)
                    }
                }
            row.view.addView(row.key, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.view.addView(
                typeChip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart =
                        dp(4)
                    ; marginEnd = dp(4)
                },
            )
            row.view.addView(row.value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.view.addView(remove, LinearLayout.LayoutParams(dp(32), dp(32)))
            extraRows += row
            extrasBox.addView(row.view)
        }
        original.extras.forEach(::addExtra)
        form.addView(
            tonalButton(getString(R.string.md_hooks_add_extra)) { addExtra(Extra("", ExtraType.STRING, "")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )
        form.addView(text(getString(R.string.md_hooks_extra_note), 11.5f, R.color.m3_on_var).apply { setPadding(0, dp(10), 0, 0) })

        fun collect(): HookIntent =
            HookIntent(
                target = target,
                action = action.text.toString().trim(),
                packageName = pkg.text.toString().trim(),
                className = cls.text.toString().trim(),
                data = data.text.toString().trim(),
                mimeType = mime.text.toString().trim(),
                categories =
                    cats.text
                        .toString()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                extras =
                    extraRows
                        .map {
                            Extra(
                                it.key.text
                                    .toString()
                                    .trim(),
                                it.type,
                                it.value.text.toString(),
                            )
                        }.filter { it.key.isNotEmpty() },
                enabled = original.enabled,
            )

        fun valid(hook: HookIntent): String? {
            if (hook.action.isEmpty() && hook.className.isEmpty() && hook.packageName.isEmpty() && hook.data.isEmpty()) {
                return getString(R.string.md_hooks_err_empty)
            }
            if (hook.className.isNotEmpty() && hook.packageName.isEmpty()) return getString(R.string.md_hooks_err_class)
            return runCatching { SessionHooks.build(hook) }.exceptionOrNull()?.let { getString(R.string.md_hooks_err_extra, it.message) }
        }

        val builder =
            AlertDialog
                .Builder(this, R.style.CarDeckAppPicker)
                .setTitle(if (index < 0) R.string.md_hooks_add else R.string.md_hooks_edit)
                .setView(ScrollView(this).apply { addView(form) })
                .setPositiveButton(R.string.md_hooks_save, null)
                .setNeutralButton(R.string.md_hooks_test, null)
        if (index >=
            0
        ) {
            builder.setNegativeButton(R.string.md_hooks_delete, null)
        } else {
            builder.setNegativeButton(android.R.string.cancel, null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hook = collect()
                valid(hook)?.let {
                    toast(it)
                    return@setOnClickListener
                }
                val next = hooks.toMutableList()
                if (index >= 0) next[index] = hook else next += hook
                SessionHooks.save(this, event, next)
                dialog.dismiss()
                render()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val hook = collect()
                valid(hook)?.let {
                    toast(it)
                    return@setOnClickListener
                }
                Bg.run("hook-test") {
                    val result = SessionHooks.send(this, hook, event)
                    runOnUiThread { toast(result) }
                }
            }
            if (index >= 0) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    SessionHooks.save(this, event, hooks.filterIndexed { i, _ -> i != index })
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }

    private class ExtraRow(
        var type: ExtraType,
    ) {
        lateinit var view: LinearLayout
        lateinit var key: EditText
        lateinit var value: EditText
    }

    /** Installed apps with a launcher entry (scanned in the background); the chosen one fills in the package name. */
    private fun pickApp(onPicked: (String) -> Unit) {
        Bg.run("hook-pick-app") {
            val pm = packageManager
            val apps =
                pm
                    .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                AlertDialog
                    .Builder(this, R.style.CarDeckAppPicker)
                    .setTitle(R.string.md_hooks_pick_app)
                    .setItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i -> onPicked(apps[i].first) }
                    .show()
            }
        }
    }

    // ---- Widgets ----

    private fun targetLabel(target: Target): String =
        getString(
            when (target) {
                Target.BROADCAST -> R.string.md_hooks_target_broadcast
                Target.ACTIVITY -> R.string.md_hooks_target_activity
                Target.SERVICE -> R.string.md_hooks_target_service
            },
        )

    private fun field(
        form: LinearLayout,
        label: Int,
        value: String,
        hint: String,
    ): EditText {
        form.addView(label(label))
        return input(value, hint).also { form.addView(it) }
    }

    private fun label(res: Int): TextView =
        text(getString(res), 13f, R.color.m3_on_surface, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun input(
        value: String,
        hint: String,
    ): EditText =
        EditText(this).apply {
            setText(value)
            this.hint = hint
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 13.5f
            setTextColor(color(R.color.m3_on_surface))
            setHintTextColor(color(R.color.m3_outline))
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
        }

    private fun tonalButton(
        label: String,
        onClick: () -> Unit,
    ): TextView =
        text(label, 13.5f, R.color.m3_on_sec_container, bold = true).apply {
            setBackgroundResource(R.drawable.bg_btn_tonal)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { onClick() }
        }

    private fun text(
        value: String,
        sizeSp: Float,
        colorRes: Int,
        bold: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color(colorRes))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun rippleBackground() =
        TypedValue().let { tv ->
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            getDrawable(tv.resourceId)
        }

    private fun color(res: Int): Int = getColor(res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
