package io.github.ezn24.droidcar.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.ezn24.droidcar.R

/**
 * Dark message dialog: title + body (scrollable when long) + action buttons.
 *
 * **Why not a bare `AlertDialog.Builder.setMessage`**: system dialogs under
 * `Theme.AppCompat.DayNight` use a light theme, clashing with the app's all-dark UI
 * (exposed by a real-device screenshot on 2026-10-18). A custom `bg_dialog_panel` panel is drawn here,
 * consistent with the disclaimer and target-car selection dialogs.
 *
 * Layout rules: 1-2 actions go in a row aligned right; 3 or more stack vertically at full width (a narrow screen would overflow in a row).
 */
object DarkMessageDialog {
    enum class Emphasis {
        /** Solid emphasis button (bg_cta_fill). */
        PRIMARY,

        /** Text button (bg_tbtn). */
        TEXT,

        /** Dangerous action (dark red background). */
        DANGER,
    }

    /**
     * @param dismissOnClick whether to dismiss automatically after a click (default true). Pass false when the dialog
     *        must stay (e.g. let the user retry after a validation failure).
     */
    data class Action(
        val label: String,
        val emphasis: Emphasis = Emphasis.TEXT,
        val dismissOnClick: Boolean = true,
        val onClick: () -> Unit,
    )

    /**
     * @param actions in display order; right-aligned in a row, top to bottom when stacked
     * @param cancelable whether back key / outside tap can dismiss
     * @param onCancel callback when dismissed by cancelling (null = don't care)
     */
    fun show(
        activity: Activity,
        title: String,
        message: String,
        actions: List<Action>,
        cancelable: Boolean = true,
        onCancel: (() -> Unit)? = null,
        /** Optional input under the message (e.g. a PIN field). */
        extra: android.view.View? = null,
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_message, null)
        val titleView = view.findViewById<TextView>(R.id.msgTitle)
        val bodyView = view.findViewById<TextView>(R.id.msgBody)
        val scroll = view.findViewById<ScrollView>(R.id.msgScroll)
        val actionRow = view.findViewById<LinearLayout>(R.id.msgActions)
        titleView.text = title
        bodyView.text = message
        if (extra != null) {
            (view as LinearLayout).addView(extra, view.indexOfChild(actionRow))
        }

        val density = activity.resources.displayMetrics.density
        val vertical = actions.size > 2
        actionRow.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        actionRow.gravity = if (vertical) Gravity.END else Gravity.END or Gravity.CENTER_VERTICAL

        val dialog =
            AlertDialog
                .Builder(activity)
                .setView(view)
                .create()

        actions.forEachIndexed { index, action ->
            actionRow.addView(
                buildButton(activity, action, density, vertical, isFirst = index == 0) {
                    action.onClick()
                    if (action.dismissOnClick) dialog.dismiss()
                },
            )
        }

        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        if (cancelable) {
            dialog.setOnCancelListener { onCancel?.invoke() }
        }
        // Size the window before show(): sizing it in the show listener drew the first frame at the system default width and
        // position, and the dialog visibly jumped to the centre (user report 2026-11-24)
        dialog.create()
        run {
            // With a very long body clamp to 46% of the screen height and scroll inside; short text hugs the content (a fixed height
            // would leave large blank space).
            // clampBody also serves as the rotation callback: after landscape shrinks the screen height it must clamp again, or it
            // overflows the screen.
            val clampBody: (Activity) -> Unit = { ctx ->
                val maxBodyHeight = (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt()
                bodyView.post {
                    val target =
                        if (bodyView.height > maxBodyHeight) {
                            maxBodyHeight
                        } else {
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    if (scroll.layoutParams.height != target) {
                        scroll.layoutParams =
                            scroll.layoutParams.apply { height = target }
                    }
                }
            }
            // Width 92%, height adapts to content; on rotation PanelWindow calls clampBody back to recompute
            PanelWindow.apply(dialog, activity, onResize = clampBody)
            clampBody(activity)
        }
        dialog.show()
        return dialog
    }

    private fun buildButton(
        activity: Activity,
        action: Action,
        density: Float,
        vertical: Boolean,
        isFirst: Boolean,
        onClick: () -> Unit,
    ): TextView =
        TextView(activity).apply {
            text = action.label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val padH = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            when (action.emphasis) {
                Emphasis.PRIMARY -> {
                    background = activity.getDrawable(R.drawable.bg_cta_fill)
                    setTextColor(activity.getColor(R.color.m3_on_primary))
                }

                Emphasis.DANGER -> {
                    // Dangerous action: dark red background + light red text (reuses the error container color)
                    background =
                        GradientDrawable().apply {
                            cornerRadius = 999f * density
                            setColor(activity.getColor(R.color.m3_err_container))
                        }
                    setTextColor(activity.getColor(R.color.m3_on_err_container))
                }

                Emphasis.TEXT -> {
                    // When stacked, secondary buttons also get the pill background: otherwise only the primary is a solid full-width pill,
                    // the other two are bare text, and visual weight is badly unbalanced (exposed by a real-device screenshot)
                    background =
                        activity.getDrawable(
                            if (vertical) R.drawable.bg_btn_tonal else R.drawable.bg_tbtn,
                        )
                    setTextColor(
                        activity.getColor(
                            if (vertical) R.color.m3_on_sec_container else R.color.m3_primary,
                        ),
                    )
                }
            }
            layoutParams =
                if (vertical) {
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            // Stacked: 6dp between buttons (none before the first)
                            if (!isFirst) topMargin = (6 * density).toInt()
                        }
                } else {
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            // In a row: first item against the right edge, 6dp between later ones
                            if (!isFirst) marginStart = (6 * density).toInt()
                        }
                }
            setOnClickListener {
                // Debounce: the callback usually navigates / reopens a flow / writes system settings
                isEnabled = false
                onClick()
            }
        }
}
