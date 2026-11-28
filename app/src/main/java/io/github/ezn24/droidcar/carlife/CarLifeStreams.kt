/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.carlife

/**
 * Video flow control between encoder and car. The car asks for video in steps (ENCODER_INIT -> START, PAUSE and START
 * again); frames before START are held from the last key frame so the first picture the car sees is decodable, and the
 * codec config (SPS/PPS) rides in front of the first key frame after every (re)start.
 */
class CarLifeVideoFeed(
    private val sendFrame: (ByteArray) -> Unit,
    private val sendEmpty: (heartbeat: Boolean) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Mode { OFF, HELD, STARTING, LIVE, PAUSED, RESUMING }

    private class Out(
        val frame: ByteArray?,
        val heartbeat: Boolean,
    )

    private val lock = Any()

    @Volatile var mode = Mode.OFF
        private set
    private var config = ByteArray(0)
    private var configSent = false
    private val held = ArrayList<ByteArray>()
    private var heldBytes = 0L
    private var dropped = 0

    @Volatile private var lastSentAt = 0L
    private var resumedAt = 0L
    private var keyAskedAt = 0L

    fun open() =
        synchronized(lock) {
            mode = Mode.HELD
            config = ByteArray(0)
            configSent = false
            dropped = 0
            clearHeld()
        }

    fun close() =
        synchronized(lock) {
            mode = Mode.OFF
            config = ByteArray(0)
            configSent = false
            clearHeld()
        }

    fun resendConfig() = synchronized(lock) { configSent = false }

    fun config(bytes: ByteArray) =
        synchronized(lock) {
            if (!bytes.contentEquals(config)) {
                config = bytes
                configSent = false
            }
        }

    fun start(): Mode =
        synchronized(lock) {
            when (mode) {
                Mode.HELD -> {
                    mode = Mode.STARTING
                    dropped = 0
                }
                Mode.PAUSED -> {
                    mode = Mode.RESUMING
                    dropped = 0
                    resumedAt = clock()
                    keyAskedAt = resumedAt
                }
                else -> Unit
            }
            mode
        }

    fun pause(): Mode =
        synchronized(lock) {
            if (mode == Mode.STARTING || mode == Mode.LIVE || mode == Mode.RESUMING) mode = Mode.PAUSED
            mode
        }

    /** True when the car came back but no key frame came by itself: ask the encoder for one. */
    fun needsKey(): Boolean =
        synchronized(lock) {
            val now = clock()
            val due = mode == Mode.RESUMING && now - keyAskedAt >= FORCE_KEY_MS
            if (due) keyAskedAt = now
            due
        }

    fun frame(
        frame: ByteArray,
        key: Boolean,
    ) {
        val out = synchronized(lock) { decide(frame, key) }
        out.forEach(::emit)
    }

    /** Heartbeat while nothing is encoded (static picture): the car drops the projection after a quiet gap. */
    fun idle(gapMs: Long): Boolean {
        val m = mode
        if (m == Mode.OFF || m == Mode.HELD) return false
        if (clock() - lastSentAt < gapMs) return false
        emit(Out(null, heartbeat = m != Mode.STARTING))
        return true
    }

    private fun decide(
        frame: ByteArray,
        key: Boolean,
    ): List<Out> =
        when (mode) {
            Mode.OFF -> emptyList()
            Mode.HELD -> {
                hold(frame, key)
                emptyList()
            }
            Mode.STARTING -> {
                hold(frame, key)
                if (held.isEmpty()) {
                    listOf(Out(null, heartbeat = false))
                } else {
                    val out = ArrayList<Out>(held.size)
                    held.forEachIndexed { i, f -> out += Out(payload(f, i == 0), false) }
                    clearHeld()
                    mode = Mode.LIVE
                    out
                }
            }
            Mode.LIVE -> listOf(Out(payload(frame, key), false))
            Mode.PAUSED -> listOf(Out(null, heartbeat = true))
            Mode.RESUMING -> {
                if (key && (dropped > RESUME_DROPS || clock() - resumedAt >= RESUME_WAIT_MS)) {
                    mode = Mode.LIVE
                    listOf(Out(payload(frame, true), false))
                } else {
                    dropped++
                    if (dropped % 3 == 0) listOf(Out(null, heartbeat = true)) else emptyList()
                }
            }
        }

    private fun payload(
        frame: ByteArray,
        key: Boolean,
    ): ByteArray {
        if (!key || configSent || config.isEmpty()) return frame
        configSent = true
        return config + frame
    }

    private fun hold(
        frame: ByteArray,
        key: Boolean,
    ) {
        if (key) clearHeld()
        if (held.isEmpty() && !key) return
        held += frame
        heldBytes += frame.size
        if (held.size > MAX_HELD || heldBytes > MAX_HELD_BYTES) clearHeld()
    }

    private fun clearHeld() {
        held.clear()
        heldBytes = 0
    }

    private fun emit(o: Out) {
        lastSentAt = clock()
        if (o.frame != null) sendFrame(o.frame) else sendEmpty(o.heartbeat)
    }

    companion object {
        const val RESUME_DROPS = 10
        const val RESUME_WAIT_MS = 1_000L
        const val FORCE_KEY_MS = 2_500L
        private const val MAX_HELD = 90
        private const val MAX_HELD_BYTES = 6L * 1024 * 1024
    }
}

/**
 * Music PCM on the media channel: MEDIA_INIT (48 kHz, stereo, 16 bit) opens it with the first non-empty buffer, a
 * quiet gap pauses it again so the car's own sources can play. Older head units (protocol before 3.2) also want a
 * STOP + INIT pair before the first data.
 */
class CarLifeMediaStream(
    private val send: (serviceId: Int, payload: ByteArray) -> Unit,
    private val status: (module: Int, state: Int) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile var oldVehicle = true

    @Volatile var open = false
        private set

    @Volatile private var resetWanted = false
    private var lastDataAt = 0L
    private var resetAt = Long.MIN_VALUE / 2

    fun data(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (resetWanted) applyReset()
        if (!open) {
            send(CarLifeProtocol.MEDIA_INIT, INIT)
            if (oldVehicle) {
                send(CarLifeProtocol.MEDIA_STOP, EMPTY)
                send(CarLifeProtocol.MEDIA_INIT, INIT)
            }
            status(CarLifeProtocol.MODULE_MUSIC, 1)
            open = true
        }
        lastDataAt = clock()
        send(CarLifeProtocol.MEDIA_DATA, pcm)
    }

    fun idle(): Boolean {
        if (resetWanted) return applyReset()
        if (!open || clock() - lastDataAt < QUIET_MS) return false
        close()
        return true
    }

    /** The car re-launched CarLife (LAUNCH_MODE_NORMAL): reopen the stream with the next data. */
    fun launchedAgain() {
        val now = clock()
        val due = now - resetAt > RESET_GUARD_MS
        resetAt = now
        if (due) resetWanted = true
    }

    fun forget() {
        open = false
        resetWanted = false
        lastDataAt = 0L
        resetAt = Long.MIN_VALUE / 2
    }

    private fun applyReset(): Boolean {
        resetWanted = false
        if (!open) return false
        close()
        return true
    }

    private fun close() {
        send(CarLifeProtocol.MEDIA_PAUSE, EMPTY)
        status(CarLifeProtocol.MODULE_MUSIC, 0)
        open = false
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val QUIET_MS = 750L
        const val RESET_GUARD_MS = 5000L
        val INIT: ByteArray =
            ProtoWriter()
                .int32(1, SAMPLE_RATE)
                .int32(2, CHANNELS)
                .int32(3, 16)
                .toByteArray()
        private val EMPTY = ByteArray(0)
    }
}
