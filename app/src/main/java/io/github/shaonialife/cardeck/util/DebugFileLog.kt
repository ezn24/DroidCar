package io.github.shaonialife.cardeck.util

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
 * 全局调试文件日志（files/main_debug.log）。
 *
 * 荣耀机型 logcat 被 HKS 加密，本文件是 adb 侧唯一调试通道（run-as 读取）。
 *
 * **异步落盘**（2026-11-16 效能优化）：此前每行都在调用方线程上 open→append→close 一次
 * 文件，视频发送线程、触摸线程都会被磁盘 I/O 卡住。现在调用方只格式化并入队，单条后台
 * 线程持有一个 BufferedWriter 批量写、队列一空就 flush（崩溃最多丢最后几毫秒的日志）。
 * 读取（导出 / 远程调试拉日志）前先 [flushPending] 等已入队的行写完。
 */
object DebugFileLog {
    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * 调试桥 tap：每条日志同步交给监听者（**远程调试变体**在 Application 里安装；
     * 日常/发布变体恒为 null，零开销）。seq 单调递增——断线重连按 seq 回补。
     * 监听者异常被吞：绝不能让它拖慢或打断日志写入。
     */
    @Volatile
    var tap: ((seq: Long, line: String) -> Unit)? = null

    private val sequence =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** 最近一条日志的序号（0 = 本进程尚无日志）。 */
    val lastSeq: Long get() = sequence.get()

    /**
     * 「本次进程启动」标记行。日志文件跨进程保留（最多 2MB），用户反馈问题时
     * 历史轮次的日志会把真正有用的部分淹没——导出默认只取最后一条标记之后的内容。
     * 标记本身写进文件而不是只记内存偏移：文件可能被 2MB 截断，偏移会失效。
     */
    const val SESSION_MARKER = "===== CarDeck 进程启动（本次日志起点）====="

    private const val FILE_NAME = "main_debug.log"
    private const val MAX_BYTES = 2_000_000L

    /**
     * 写入本次启动标记。由 [io.github.shaonialife.cardeck.SenderApp.onCreate] 调用一次；
     * 重复调用会产生多条标记，导出仍取最后一条，行为安全。
     */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    /**
     * 读取日志正文。[currentSessionOnly] = true 时只返回最后一条 [SESSION_MARKER]
     * 之后的内容（无标记时返回全文——首次安装/旧版本文件都属这种情形）。
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
     * 读文件尾部原始字节（远程调试桥 `log_file.req` 用，最大 [maxBytes]）。
     * 与写线程并发安全：先等已入队的行落盘，再只读不写；文件不存在返回 null。
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
     * 取最后一条启动标记之后的内容（纯函数，单测覆盖边界）。
     * 无标记 → 全文：旧版本写入的文件里没有标记，此时全量比空更好。
     */
    internal fun currentSessionSlice(text: String): String {
        val markerAt = text.lastIndexOf(SESSION_MARKER)
        return if (markerAt < 0) text else text.substring(markerAt + SESSION_MARKER.length)
    }

    /** 追加一行：调用方线程只做格式化 + 入队，不碰磁盘。任意线程可调。 */
    fun append(
        context: Context,
        message: String,
    ) {
        try {
            // SimpleDateFormat 非线程安全：格式化放锁内（纯内存，微秒级）
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

    /** 等待已入队的日志全部落盘（最多 [timeoutMs]）。读取文件前调用。 */
    fun flushPending(timeoutMs: Long = 1_000L) {
        if (writerThread == null) return
        val latch = CountDownLatch(1)
        queue.offer(FlushBarrier(latch))
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    @Volatile
    private var logFile: File? = null

    /** 队列元素：日志行（String）或刷盘屏障。 */
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
                // 超限直接删会连「本次启动」标记一起丢（导出就退化为全量历史）。
                // 删后立即重写一条标记，保证截断后的新文件仍能按会话切分。
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
                // 队列空了就刷盘：高频时批量写，静默时日志立即可见
                if (queue.isEmpty()) writer.flush()
            } catch (_: Throwable) {
                runCatching { writer?.close() }
                writer = null
            }
        }
    }
}
