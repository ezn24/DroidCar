/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.iccoa

/** What DroidCar encodes for an ICCOA receiver: its geometry, capped to what AVC Level 4.0 can carry. */
internal data class IccoaVideoPlanValue(
    val contentWidth: Int,
    val contentHeight: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val fps: Int,
    val bitrate: Int,
)

internal object IccoaVideoPlan {
    fun from(config: IccoaVideoConfig): IccoaVideoPlanValue {
        val width = config.width and -2
        val height = config.height and -2
        // AVC Level 4.0 max: 8,192 macroblocks/frame and 245,760/sec. 1920x1080@30 => 8,160/frame and 244,800/sec.
        val capNative = width > 1920 || height > 1080
        val streamWidth = if (capNative) 1920 else width
        val streamHeight = if (capNative) 1080 else height
        return IccoaVideoPlanValue(width, height, streamWidth, streamHeight, config.fps.coerceIn(1, 30), 8_000_000)
    }
}
