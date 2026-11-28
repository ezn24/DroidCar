/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.iccoa

/** Parsing a frame on an auxiliary socket cannot confirm ownership of CONTROL. */
internal data class IccoaInboundFrame(
    val body: ByteArray,
    val authenticatedControl: Boolean,
) {
    companion object {
        fun decode(
            port: Int,
            frame: IccoaControl.Frame,
            cipher: IccoaControl.Cipher,
        ): IccoaInboundFrame {
            val encrypted = IccoaControl.isEncrypted(frame.body)
            val body = if (encrypted) cipher.decrypt(frame.body) else frame.body
            return IccoaInboundFrame(
                body,
                encrypted && port == IccoaLaneServer.CONTROL_PORT &&
                    frame.category == 1 && frame.method != 23,
            )
        }
    }
}
