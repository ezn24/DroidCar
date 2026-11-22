package io.github.ezn24.droidcar.legal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.ezn24.droidcar.R
import io.github.ezn24.droidcar.SessionController
import io.github.ezn24.droidcar.ui.PanelWindow

/**
 * Disclaimer dialog (two forms):
 *
 * - **First launch (mandatory)**: not cancelable (back key and outside taps are ignored); "Accept" requires both the
 *   10-second countdown and "scrolled to the bottom"; "Decline" is tappable immediately (refusal must not be blocked).
 * - **Menu re-read (read-only)**: no countdown, no scroll requirement, a single "Close" button.
 *
 * The countdown uses an **absolute deadline** ([SystemClock.elapsedRealtime]) rather than a decrementing counter:
 * when rotation recreates the Activity, passing the deadline back resumes the remaining time
 * instead of waiting another 10 seconds from scratch.
 */
object DisclaimerDialog {
    /** Minimum dwell time before mandatory acceptance. */
    const val COUNTDOWN_MS = 10_000L

    private const val WINDOW_HEIGHT_FRACTION = 0.78f

    /**
     * Show the dialog.
     *
     * @param requireAcceptance true = mandatory acceptance on first launch; false = read-only from the menu
     * @param deadlineAt elapsedRealtime at which the countdown ends (mandatory form only)
     * @param onDecline callback after tapping "Decline" (mandatory form only)
     * @param onDismiss callback after dismissal by any means (the Activity uses it to drop references)
     */
    fun show(
        activity: Activity,
        requireAcceptance: Boolean,
        deadlineAt: Long = 0L,
        onDecline: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_disclaimer, null)
        val subtitle = view.findViewById<TextView>(R.id.disclaimerSubtitle)
        val scroll = view.findViewById<ScrollView>(R.id.disclaimerScroll)
        val sections = view.findViewById<LinearLayout>(R.id.disclaimerSections)
        val blockHint = view.findViewById<TextView>(R.id.disclaimerBlockHint)
        val btnDecline = view.findViewById<TextView>(R.id.btnDecline)
        val btnAccept = view.findViewById<TextView>(R.id.btnAccept)

        renderSections(activity, sections)

        if (requireAcceptance) {
            subtitle.text = activity.getString(R.string.disclaimer_subtitle_first)
        } else {
            subtitle.text = activity.getString(R.string.disclaimer_subtitle_view, DisclaimerPrefs.CURRENT_VERSION)
            btnDecline.visibility = View.GONE
            btnAccept.isEnabled = true
            btnAccept.text = activity.getString(R.string.disclaimer_close)
            blockHint.visibility = View.GONE
        }

        val dialog =
            AlertDialog
                .Builder(activity)
                .setView(view)
                .create()

        // Mandatory form: neither the back key nor outside taps may dismiss it
        dialog.setCancelable(!requireAcceptance)
        dialog.setCanceledOnTouchOutside(!requireAcceptance)

        var handler: Handler? = null
        var ticker: Runnable? = null

        if (requireAcceptance) {
            val mainHandler = Handler(Looper.getMainLooper())
            handler = mainHandler
            var scrolledToBottom = false
            SessionController.appendLog(
                "DISCLAIMER: mandatory notice shown (countdown ${COUNTDOWN_MS / 1000} s + must scroll to the bottom)" +
                    " remain=${(deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)}ms",
            )

            fun refresh() {
                // Before layout completes the ScrollView size is 0, so "not scrollable" would be taken as "content shorter than one
                // screen",
                // permanently setting scrolledToBottom (on a real device: accepting without scrolling).
                // Must wait for layout to finish: only height>0 counts.
                val layoutReady = scroll.height > 0
                val viewport = (scroll.height - scroll.paddingTop - scroll.paddingBottom).coerceAtLeast(0)
                val contentScrollable = layoutReady && sections.height > viewport
                if (layoutReady && !scroll.canScrollVertically(1)) scrolledToBottom = true
                val now = SystemClock.elapsedRealtime()
                val remainingMs = (deadlineAt - now).coerceAtLeast(0L)
                val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
                val countdownDone = remainingMs <= 0L
                val enabled = canAcceptDisclaimer(countdownDone, contentScrollable, scrolledToBottom)
                btnAccept.isEnabled = enabled
                btnAccept.text =
                    if (countdownDone) {
                        activity.getString(R.string.disclaimer_accept)
                    } else {
                        activity.getString(R.string.disclaimer_accept_wait, remainingSeconds)
                    }
                val reason =
                    disclaimerBlockReason(
                        countdownDone = countdownDone,
                        contentScrollable = contentScrollable,
                        scrolledToBottom = scrolledToBottom,
                    )
                blockHint.text =
                    when (reason) {
                        DisclaimerBlock.SCROLL_AND_WAIT -> activity.getString(R.string.disclaimer_hint_scroll_wait, remainingSeconds)
                        DisclaimerBlock.WAIT -> activity.getString(R.string.disclaimer_hint_wait, remainingSeconds)
                        DisclaimerBlock.SCROLL -> activity.getString(R.string.disclaimer_hint_scroll)
                        null -> ""
                    }
                blockHint.visibility = if (reason == null) View.GONE else View.VISIBLE
            }

            val looping =
                object : Runnable {
                    override fun run() {
                        refresh()
                        mainHandler.postDelayed(this, 200L)
                    }
                }
            ticker = looping
            mainHandler.post(looping)

            scroll.setOnScrollChangeListener { _, _, _, _, _ -> refresh() }
            // Decide again after the first layout (only now is it known whether content exceeds one screen)
            scroll.post { refresh() }

            btnAccept.setOnClickListener {
                if (!btnAccept.isEnabled) return@setOnClickListener
                DisclaimerPrefs.accept(activity)
                dialog.dismiss()
            }
            btnDecline.setOnClickListener {
                dialog.dismiss()
                onDecline?.invoke()
            }
        } else {
            btnAccept.setOnClickListener { dialog.dismiss() }
        }

        // Unified teardown: stop the timer + tell the caller to drop references (setting another listener in the caller used to override
        // this cleanup)
        dialog.setOnDismissListener {
            ticker?.let { handler?.removeCallbacks(it) }
            onDismiss?.invoke()
        }

        dialog.setOnShowListener {
            // Width 92%, height 78% of the screen; once registered, the Activity calls resizeShowing on rotation to recompute
            PanelWindow.apply(dialog, activity, WINDOW_HEIGHT_FRACTION)
        }
        dialog.show()
        return dialog
    }

    /** Render section by section: bold title + body, spacing between sections. */
    private fun renderSections(
        activity: Activity,
        container: LinearLayout,
    ) {
        val density = activity.resources.displayMetrics.density
        DisclaimerContent.sections.forEachIndexed { index, section ->
            val title = TextView(activity)
            title.text = activity.getString(section.title)
            title.setTextColor(activity.getColor(R.color.m3_primary))
            title.textSize = 13.5f
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            title.setPadding(0, (if (index == 0) 0 else 14 * density).toInt(), 0, (5 * density).toInt())
            container.addView(title)

            val body = TextView(activity)
            body.text = activity.getString(section.body)
            body.setTextColor(activity.getColor(R.color.m3_on_var))
            body.textSize = 12.5f
            body.setLineSpacing(4 * density, 1f)
            body.setPadding(0, 0, 0, 0)
            container.addView(
                body,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }
}
