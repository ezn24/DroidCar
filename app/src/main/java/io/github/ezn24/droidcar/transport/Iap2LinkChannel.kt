package io.github.ezn24.droidcar.transport

import io.github.ezn24.droidcar.bt.AdapterMode
import io.github.ezn24.droidcar.iap2.Iap2LinkEngine
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Link channel: one reader thread plus one pacer thread drive an [Iap2LinkEngine] and expose
 * blocking sendControl/recvControl and awaitReady to the session layer.
 */
class Iap2LinkChannel(
    private val stream: DuplexStream,
    name: String = "iap2-link",
    /**
     * Wireless CarPlay adapter (smartBox) link: responder that waits for the box's opening and
     * echoes its SYN (see [Iap2LinkEngine.adapterResponder]). Decided once at construction.
     */
    val adapterMode: Boolean = AdapterMode.active,
    /**
     * Car Bluetooth link: random initial sequence plus renegotiation on the peer's marker (see
     * [Iap2LinkEngine]). Defaults to false so unit tests and replays keep the fixed sequence 99;
     */
    carLink: Boolean = false,
    private val log: (String) -> Unit = {},
) : Closeable {
    private val engine =
        Iap2LinkEngine(
            initialSequence =
                if (carLink && !adapterMode) {
                    Iap2LinkEngine.randomInitialSequence()
                } else {
                    Iap2LinkEngine.DEFAULT_INITIAL_SEQUENCE
                },
        ).apply {
            adapterResponder = adapterMode
            renegotiateOnPeerMarker = carLink && !adapterMode
        }
    private val lock = Object()
    private val controls = LinkedBlockingQueue<ByteArray?>()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var terminated = false
    private var readySignalled = false

    /** Raw byte dump budget (per direction per connection), for byte-level comparison with real cars. */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX

    /** Per-frame summary log budget (per direction per connection). */
    private var rxFrameLogs = 0
    private var txFrameLogs = 0

    val isAlive: Boolean get() = !terminated

    init {
        engine.onPeerSynchronizationAdopted = { sync ->
            log(
                "LINK: adopted peer session table maxOutgoing=${sync.maxOutgoing} maxLen=${sync.maxLength} " +
                    "sessions=" + sync.sessions.joinToString { "${it.id}/k${it.kind}/v${it.version}" },
            )
        }
        engine.onUnknownSessionData = { sessionId, size ->
            log("LINK: dropped data frame for unknown session $sessionId ($size bytes)")
        }
        engine.onAdapterEvent = { line -> log("ADAPTER: link $line") }
        engine.onPeerRenegotiation = { line -> log("LINK: $line") }
        engine.onFrameReceived = { control, sequence, ack, sessionId, size ->
            // Key observation for car debugging: whether and what the head unit replies during identification (silent divergences are located with this)
            if (rxFrameLogs < FRAME_LOG_LIMIT) {
                rxFrameLogs++
                log(
                    "LINK rx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        engine.onFrameSent = { control, sequence, ack, sessionId, size ->
            if (txFrameLogs < FRAME_LOG_LIMIT) {
                txFrameLogs++
                log(
                    "LINK tx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        Thread({ runReader() }, "$name-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ runPacer() }, "$name-pacer").apply {
            isDaemon = true
            start()
        }
    }

    /** Waits for link negotiation to finish (session 10 writable). */
    fun awaitReady(timeoutMillis: Long): Boolean {
        if (!readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        return !terminated
    }

    /** Max control payload negotiated by the peer (header and checksum already subtracted); null before negotiation. */
    fun peerMaxControlPayload(): Int? = synchronized(lock) { engine.peerSynchronization?.maxLength?.minus(Iap2LinkEngine.HEADER_BYTES + 1) }

    /** Queues one complete control payload; returns false if the link is dead. When the window is full it backs up internally and is sent as ACKs arrive. */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
    ): Boolean {
        synchronized(lock) {
            if (terminated) return false
            val accepted = engine.sendControl(bytes, nowMillis(), awaitAck)
            drainOutputLocked()
            return accepted
        }
    }

    /** Queues one file-transfer datagram (now-playing artwork); false if the link is dead or has no such session. */
    fun sendFileTransfer(bytes: ByteArray): Boolean {
        synchronized(lock) {
            if (terminated) return false
            val accepted = engine.sendFileTransfer(bytes, nowMillis())
            drainOutputLocked()
            return accepted
        }
    }

    /** Largest file-transfer datagram one link packet can carry. */
    fun maxFileTransferDatagram(): Int = synchronized(lock) { engine.maxSessionPayload() }

    /**
     * Head-unit datagrams on the file-transfer session. Called on the link reader thread with the channel lock held:
     * the handler must not block or send synchronously (post the work elsewhere).
     */
    fun setFileTransferHandler(handler: ((ByteArray) -> Unit)?) {
        synchronized(lock) { engine.onFileTransferData = handler }
    }

    /** Takes the next control payload; returns null on timeout or link end. */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        if (timeoutMillis == 0L && terminated && controls.isEmpty()) return null
        val polled =
            try {
                controls.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        return if (polled === null || polled === POISON) null else polled
    }

    override fun close() {
        finish(null)
    }

    // ---- internals ----

    private fun runReader() {
        try {
            if (adapterMode) {
                log("ADAPTER: link in responder mode - waiting for the adapter's detect marker (no own SYN)")
            } else {
                synchronized(lock) {
                    engine.start(nowMillis())
                    drainOutputLocked()
                }
            }
            var responderStarted = false
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    finish(null)
                    return
                }
                if (count == 0) continue
                synchronized(lock) {
                    if (terminated) return
                    dumpInbound(buffer.copyOf(count))
                    if (adapterMode && !responderStarted) {
                        // Reference (bluetooth.rs): start the link on the box's first bytes, answering
                        // its detect marker with ours (re-sent each 1 s until its SYN arrives).
                        responderStarted = true
                        val marker = buffer.copyOf(count).startsWith(Iap2LinkEngine.MARKER)
                        log(
                            "ADAPTER: rx ${if (marker) "detect marker" else "first ${count}B (no marker)"} - " +
                                "tx detect marker, waiting for the adapter's SYN",
                        )
                        engine.startResponder(nowMillis())
                    }
                    engine.feed(buffer.copyOf(count), nowMillis())
                    processEventsLocked()
                    drainOutputLocked()
                    signalReadyLocked()
                }
            }
        } catch (error: Throwable) {
            finish(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Raw byte capture (the only means of byte-level comparison with real cars): only the first
     * [RAW_DUMP_LIMIT] bytes per direction are dumped, so long sessions do not flood the log.
     */
    private fun dumpInbound(bytes: ByteArray) {
        if (bytes.isEmpty() || rxDumpBudget <= 0) return
        val shown = minOf(bytes.size, rxDumpBudget)
        log("RAW rx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        rxDumpBudget -= shown
    }

    private fun runPacer() {
        while (!terminated) {
            val deadline: Long?
            synchronized(lock) {
                if (terminated) return
                engine.advanceTime(nowMillis())
                processEventsLocked()
                drainOutputLocked()
                signalReadyLocked()
                deadline = engine.nextDeadlineMillis()
                if (deadline == null) {
                    try {
                        lock.wait(PACER_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
            if (deadline != null) {
                val sleep = (deadline - nowMillis()).coerceIn(1, PACER_POLL_MILLIS)
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** Call with the lock held: pulls engine events and moves output. */
    private fun processEventsLocked() {
        while (true) {
            when (val event = engine.pollEvent() ?: return) {
                is Iap2LinkEngine.Event.Control -> {
                    if (!controls.offer(event.bytes)) {
                        engine.feedEof()
                        return
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(event.reason?.let { IOException(it) })
                    signalReadyLocked()
                    return
                }
            }
        }
    }

    private fun drainOutputLocked() {
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return
        dumpOutbound(bytes)
        try {
            stream.write(bytes)
        } catch (error: IOException) {
            finishLocked(error)
        }
        signalReadyLocked()
    }

    private fun dumpOutbound(bytes: ByteArray) {
        if (txDumpBudget <= 0) return
        val shown = minOf(bytes.size, txDumpBudget)
        log("RAW tx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        txDumpBudget -= shown
    }

    private fun signalReadyLocked() {
        if (!readySignalled && engine.state == Iap2LinkEngine.State.NORMAL) {
            readySignalled = true
            readyLatch.countDown()
        }
    }

    private fun finish(reason: String?) {
        synchronized(lock) { finishLocked(reason?.let { IOException(it) }) }
    }

    private fun finishLocked(failure: IOException?) {
        if (terminated) return
        terminated = true
        controls.offer(POISON) // sentinel: wakes a blocked receiver (LBQ does not accept null)
        readyLatch.countDown()
        lock.notifyAll()
        runCatching { stream.close() }
        if (failure != null) {
            log("LINK closed: ${failure.message}")
        } else {
            log("LINK closed")
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        const val PACER_POLL_MILLIS = 20L

        /** Raw byte dump budget: rx must cover all of the head unit's negotiation frames (Audi measured ~140B), with margin. */
        // Budget is counted in bytes. It used to be 512/256, which could not cover one full bring-up
        // (the 0x1D01 frame alone is 565B), so replay fixtures extracted via tools/extract_car_fixtures.py
        // lost their tails. Raised to 4096 to cover identification, MFi and credential exchange; the cap keeps long sessions from flooding the log.
        const val RAW_DUMP_LIMIT = 4096
        const val RAW_DUMP_LIMIT_TX = 4096

        /** Per-frame summary log cap (per direction per connection). */
        const val FRAME_LOG_LIMIT = 40

        /** Link-termination sentinel; a real control payload contains at least one CSM frame, so it is never empty. */
        val POISON = ByteArray(0)
    }
}
