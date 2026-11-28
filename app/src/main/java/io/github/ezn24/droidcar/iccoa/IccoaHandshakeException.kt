/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.iccoa

/** Only fixed reason codes and the receiver's numeric result may enter diagnostics. */
internal class IccoaHandshakeException(
    val reason: Reason,
    val result: Long? = null,
) : IllegalArgumentException(reason.name) {
    enum class Reason {
        FRAME_LENGTH,
        HEADER_CRC,
        RESPONSE_LANE,
        RESPONSE_RESULT,
        IDENTITY_LENGTH,
        AGREEMENT_LENGTH,
        NONCE_LENGTH,
        PIN_MAC,
        AGREEMENT_SIGNATURE,
        TRUSTED_IDENTITY_MISMATCH,
    }

    val diagnostic: String get() = reason.name + (result?.let { " result=$it" } ?: "")
}
