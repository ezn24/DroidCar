/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.iccoa

import io.github.ezn24.droidcar.carlife.ProtoWriter
import java.io.Closeable

/** Starts on control accept, independently of authentication and UI/encoder startup. */
internal class IccoaHeartbeat(
    private val send: (ByteArray) -> Boolean,
    private val schedule: (Long, () -> Unit) -> Unit,
) : Closeable {
    private var started = false
    private var closed = false

    @Synchronized fun start() {
        if (started || closed) return
        started = true
        tick()
    }

    @Synchronized private fun tick() {
        if (closed) return
        // The reference writes once before its first 2-second sleep.
        if (!send(ProtoWriter().int64(1, System.currentTimeMillis()).toByteArray())) {
            closed = true
            return
        }
        schedule(2_000, ::tick)
    }

    @Synchronized override fun close() {
        closed = true
    }
}
