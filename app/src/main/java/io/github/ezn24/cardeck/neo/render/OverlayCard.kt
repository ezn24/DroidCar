package io.github.ezn24.cardeck.neo.render

import android.graphics.Bitmap

/**
 * Compositor overlay card (currently used for the "forced landscape not in effect" hint).
 *
 * The bitmap is drawn by the caller on a background thread (Android Canvas lays out the text); the GL thread only uploads the texture and
 * draws it; the rectangle uses **output-frame coordinates** (same coordinate system as the encoded stream, origin top-left),
 * so touch hit-testing can reuse the same numbers with no scaling/flipping conversion.
 */
class OverlayCard(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Overall opacity (for fade in/out on enter/exit). */
    val alpha: Float = 1f,
) {
    /**
     * Same bitmap with a new position / opacity: the compositor decides whether to re-upload the texture by bitmap
     * identity, so animation does not re-upload every frame.
     */
    fun moved(
        top: Float,
        alpha: Float,
    ): OverlayCard = OverlayCard(bitmap, left, top, right, top + (bottom - this.top), alpha)

    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x < right && y >= top && y < bottom
}
