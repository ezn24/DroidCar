/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.iccoa

import android.content.Context

/** Pairing and touch calibration belong to vehicles; the phone's ICCOA client identity stays global. */
object IccoaVehicleStores {
    internal fun name(
        base: String,
        vehicleProfileId: String,
    ): String {
        require(
            vehicleProfileId == IccoaProfile.ID ||
                runCatching {
                    java.util.UUID
                        .fromString(vehicleProfileId)
                        .toString() == vehicleProfileId
                }.getOrDefault(false),
        )
        return if (vehicleProfileId == IccoaProfile.ID) base else "$base-$vehicleProfileId"
    }

    fun clear(
        context: Context,
        vehicleProfileId: String,
    ) {
        listOf("iccoa-remembered-car", "iccoa-trusted-quick-v1", "iccoa-trusted-reconnect-v1", "ft-iccoa-touch")
            .forEach { base ->
                context
                    .getSharedPreferences(name(base, vehicleProfileId), Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }
    }
}
