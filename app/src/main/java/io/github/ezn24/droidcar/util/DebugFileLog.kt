package io.github.ezn24.droidcar.util

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Global debug file log (files/main_debug.log).
 *
 * On Honor devices logcat is encrypted by HKS; this file is the only adb-side debug channel (read via run-as).
 *
 * **Asynchronous persistence** (2026-11-16 performance work): previously every line did open->append->close on the caller's thread,
 * so the video sender and touch threads were stalled by disk I/O. Now callers only format and enqueue; a single background
 * thread holds one BufferedWriter, writes in batches and flushes whenever the queue empties (a crash loses at most the last few ms of log).
 * Before reading (export / remote-debug log pull), call [flushPending] to wait for queued lines to be written.
 */
object DebugFileLog {
    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Debug bridge tap: every log line is handed synchronously to the listener (**the remote-debug variant** installs it in Application;
     * the everyday/release variant keeps it null, zero overhead). seq increases monotonically, so a reconnect backfills by seq.
     * Listener exceptions are swallowed: it must never slow down or interrupt log writing.
     */
    @Volatile
    var tap: ((seq: Long, line: String) -> Unit)? = null

    private val sequence =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** Sequence number of the latest log line (0 = this process has no log yet). */
    val lastSeq: Long get() = sequence.get()

    /**
     * "This process started" marker line. The log file persists across processes (up to 2MB), and when users report problems
     * logs from earlier rounds drown the useful part, so export defaults to content after the last marker only.
     * The marker is written into the file rather than kept as an in-memory offset: the file may be truncated at 2MB, invalidating offsets.
     */
    const val SESSION_MARKER = "===== DroidCar 进程启动（本次日志起点）====="

    private const val FILE_NAME = "main_debug.log"
    private const val MAX_BYTES = 2_000_000L

    /**
     * Write this launch's marker. Called once by [io.github.ezn24.droidcar.SenderApp.onCreate];
     * repeated calls produce several markers, export still takes the last one, so it is safe.
     */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    /**
     * Read the log text. With [currentSessionOnly] = true, return only the content after the last [SESSION_MARKER]
     * (without a marker the full text is returned: first install / files from older versions are like this).
     */
    fun read(
        context: Context,
        currentSessionOnly: Boolean,
    ): String? =
        runCatching {
            flushPending()
            val text = File(context.filesDir, FILE_NAME).readText(Charsets.UTF_8)
            if (currentSessionOnly) currentSessionSlice(text) else text
        }.getOrNull()

    /**
     * Read raw bytes from the file tail (used by the remote-debug bridge `log_file.req`, at most [maxBytes]).
     * Safe against the writer thread: first wait for queued lines to hit disk, then only read; returns null if the file is missing.
     */
    fun readTail(
        context: Context,
        maxBytes: Int,
    ): ByteArray? =
        runCatching {
            flushPending()
            val file = File(context.filesDir, FILE_NAME)
            if (!file.isFile) return null
            val len = file.length()
            val start = if (len <= maxBytes) 0L else len - maxBytes
            file.inputStream().use { input ->
                input.channel.position(start)
                input.readBytes()
            }
        }.getOrNull()

    /**
     * Take the content after the last launch marker (pure function, unit tests cover edge cases).
     * No marker -> full text: files written by older versions have none, and the full log is better than nothing.
     */
    internal fun currentSessionSlice(text: String): String {
        val markerAt = text.lastIndexOf(SESSION_MARKER)
        return if (markerAt < 0) text else text.substring(markerAt + SESSION_MARKER.length)
    }

    /** Append a line: the caller thread only formats + enqueues, never touches disk. Callable from any thread. */
    fun append(
        context: Context,
        message: String,
    ) {
        try {
            // SimpleDateFormat is not thread-safe: format inside the lock (pure memory, microseconds)
            val line = synchronized(lock) { "[" + timestamp.format(Date()) + "] " + message }
            if (logFile == null) logFile = File(context.filesDir, FILE_NAME)
            ensureWriter()
            queue.offer(line)
            tap?.let { listener ->
                runCatching { listener(sequence.incrementAndGet(), line) }
            }
        } catch (_: Throwable) {
        }
    }

    /** Wait until all queued log lines are on disk (at most [timeoutMs]). Call before reading the file. */
    fun flushPending(timeoutMs: Long = 1_000L) {
        if (writerThread == null) return
        val latch = CountDownLatch(1)
        queue.offer(FlushBarrier(latch))
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    @Volatile
    private var logFile: File? = null

    /** Queue element: a log line (String) or a flush barrier. */
    private class FlushBarrier(
        val latch: CountDownLatch,
    )

    private val queue = LinkedBlockingQueue<Any>()

    @Volatile
    private var writerThread: Thread? = null

    @Synchronized
    private fun ensureWriter() {
        if (writerThread != null) return
        writerThread =
            Thread({ writeLoop() }, "debug-file-log").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
    }

    private fun writeLoop() {
        var writer: BufferedWriter? = null
        var written = 0L

        fun open(): BufferedWriter? {
            val file = logFile ?: return null
            written = if (file.isFile) file.length() else 0L
            return BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 16 * 1024)
        }
        while (true) {
            val item =
                try {
                    queue.take()
                } catch (_: InterruptedException) {
                    return
                }
            try {
                if (item is FlushBarrier) {
                    writer?.flush()
                    item.latch.countDown()
                    continue
                }
                val line = item as String
                val out = writer ?: open() ?: continue
                writer = out
                // Deleting on overflow would also drop the "this launch" marker (export would degrade to the full history).
                // Rewrite a marker right after deleting, so the new file can still be split by session.
                if (written > MAX_BYTES) {
                    runCatching { out.close() }
                    logFile?.delete()
                    val fresh = open() ?: continue
                    writer = fresh
                    val marker = "[" + synchronized(lock) { timestamp.format(Date()) } + "] " + SESSION_MARKER
                    fresh.write(marker)
                    fresh.newLine()
                    written += marker.length + 1
                }
                writer.write(line)
                writer.newLine()
                written += line.length + 1
                // Flush when the queue empties: batch writes under load, log visible immediately when quiet
                if (queue.isEmpty()) writer.flush()
            } catch (_: Throwable) {
                runCatching { writer?.close() }
                writer = null
            }
        }
    }
}
