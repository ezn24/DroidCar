package io.github.ezn24.cardeck.neo.capture

import android.view.Surface

/**
 * Abstraction of the head-unit picture source: the encoder input Surface is filled by the source.
 * Only implementation today: [HiddenDisplaySource] (hidden display: trusted virtual display + apps launched into it via Shizuku).
 *
 * (The former "desktop" (Presentation self-drawn) and "mirror main screen" (MediaProjection AUTO_MIRROR)
 *   modes were removed on 2026-10-10.)
 */
interface ScreenSource {
    val name: String

    /** Binds (or rebinds) the encoder Surface; returns whether it succeeded. */
    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean

    /** Unbinds the Surface (keeps the underlying resources for a quick reconnect). */
    fun detach()

    /** Releases all resources. */
    fun release()
}
