package io.github.ezn24.cardeck.neo.ui

import android.app.Activity
import android.app.Dialog
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference

/**
 * Unified window-size entry for custom-drawn panel dialogs: width fixed at 92% of screen width, height as an optional ratio.
 *
 * **Must be recomputed after rotation**: these sizes are computed once at show time for the then-current orientation; without
 * recomputing, the dialog keeps its portrait width (only the middle half in landscape) or overflows the screen.
 * What is showing is registered via [register] (weak references, no leak; stale entries are purged at the next recompute).
 */
object PanelWindow {
    /** Panel width as a fraction of screen width (consistent with target-car selection and the disclaimer). */
    const val WIDTH_FRACTION = 0.92f

    private data class Entry(
        val dialog: WeakReference<AlertDialog>,
        /** null = WRAP_CONTENT (short dialogs hug their content). */
        val heightFraction: Float?,
        /** Extra recompute after rotation (e.g. re-clamp the scrolling body to the new screen height). */
        val onResize: ((Activity) -> Unit)? = null,
    )

    private val showing = mutableListOf<Entry>()

    /**
     * Set the window size and register it (for the Activity's onConfigurationChanged to recompute uniformly).
     *
     * @param heightFraction null = WRAP_CONTENT
     * @param onResize extra callback after rotation (the window has already been resized to the new orientation)
     */
    fun apply(
        dialog: AlertDialog,
        activity: Activity,
        heightFraction: Float? = null,
        onResize: ((Activity) -> Unit)? = null,
    ) {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sizeTo(dialog, activity, heightFraction)
        register(dialog, heightFraction, onResize)
    }

    /** Recompute all still-showing dialogs after orientation/size changes. */
    fun resizeShowing(activity: Activity) {
        showing.removeAll { it.dialog.get()?.isShowing != true }
        showing.forEach { entry ->
            val dialog = entry.dialog.get() ?: return@forEach
            sizeTo(dialog, activity, entry.heightFraction)
            entry.onResize?.invoke(activity)
        }
    }

    private fun register(
        dialog: AlertDialog,
        heightFraction: Float?,
        onResize: ((Activity) -> Unit)?,
    ) {
        showing.removeAll { it.dialog.get() == null || it.dialog.get() == dialog }
        showing.add(Entry(WeakReference(dialog), heightFraction, onResize))
    }

    private fun sizeTo(
        dialog: Dialog,
        activity: Activity,
        heightFraction: Float?,
    ) {
        val metrics = activity.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        val height =
            if (heightFraction == null) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                (metrics.heightPixels * heightFraction).toInt()
            }
        dialog.window?.setLayout(width, height)
    }
}
