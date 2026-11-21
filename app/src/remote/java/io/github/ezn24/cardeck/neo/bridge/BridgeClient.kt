package io.github.ezn24.cardeck.neo.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.ezn24.cardeck.neo.BuildConfig
import io.github.ezn24.cardeck.neo.util.Bg
import io.github.ezn24.cardeck.neo.util.DebugFileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Remote debug bridge client (remote variant only).
 *
 * Responsibility: keep one long WSS/WS connection to the debug server: pairing, logs (batched backfill + live stream),
 * command whitelist, hint delivery, new-version push. The coupling points with session/business code are only two:
 * [DebugFileLog.tap] (reading logs) and [BridgeCommands] (executing commands via existing public APIs).
 *
 * Threading: owns one "bridge-client" connection thread (project convention: long-running loop threads stay out of the Bg pool);
 * messages received over WS are parsed on the connection thread, then switched to the main thread (overlays) or Bg (commands) as needed.
 */
object BridgeClient {
    /** Connection state (shown in the settings page; read on the main thread). */
    @Volatile
    var stateText: String = "未连接"
        private set

    /** Time of the next automatic reconnect (elapsedRealtime; 0 = none scheduled). Used for the countdown display. */
    @Volatile
    var nextRetryAtElapsed: Long = 0
        private set

    /** Reason of the latest disconnect/failure (shown alongside the status-line countdown). */
    @Volatile
    private var lastDisconnectReason: String? = null

    /** sha256 of the APK installed by this process (delta baseline, reported in hello; cached keyed by version to avoid recomputing each time) */
    @Volatile
    private var cachedBaseSha: String? = null

    /**
     * Settings status line: while backing off to reconnect, append an "auto retry in Ns" countdown; a disconnect must be
     * visible and carry a clear expectation to the user (user request 2026-10-22), not sit at a bare "connection lost".
     */
    fun statusLine(): String {
        val next = nextRetryAtElapsed
        if (next > 0) {
            val remain = ((next - android.os.SystemClock.elapsedRealtime()) / 1000 + 1).coerceAtLeast(0)
            val reason = lastDisconnectReason
            return "连接断开${if (reason != null) "：$reason" else ""}（${remain}s 后自动重试）"
        }
        return stateText
    }

    /**
     * Connection state transition notification (true = connected; false = a previously connected link dropped).
     * The App layer uses it to show user hints; the callback fires on the connection thread, the implementer switches to the main thread
     * itself.
     */
    @Volatile
    var connectionEvents: ((connected: Boolean) -> Unit)? = null

    private var wasConnected = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var running = false

    @Volatile
    private var ws: WsClient? = null

    /** Cellular internet channel: during a car session the process network is bound to the head unit hotspot, so the debug bridge must use this. */
    @Volatile
    private var route: InternetRoute? = null

    /** Whether the current connection attempt uses the default network (used to reset the route-failure verdict when welcome succeeds). */
    @Volatile
    private var connUsedDefaultNetwork = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Ring log buffer (backfilled to the server by seq after a reconnect). */
    private val ringLock = Any()
    private val ring = ArrayDeque<Pair<Long, String>>()
    private const val RING_CAP = 5_000

    /** Logs waiting to be sent live (the tap thread enqueues, the connection thread flushes in batches). */
    private val pendingLock = Any()
    private val pending = ArrayDeque<Pair<Long, String>>()

    private var tapInstalled = false

    fun start(context: Context) {
        appContext = context.applicationContext
        installTap()
        if (running) return
        running = true
        // The cellular channel must be in place first: the very first connection may already happen inside a car session (process network
        // already bound away)
        route = InternetRoute(context.applicationContext).also { it.start() }
        Thread({ runLoop() }, "bridge-client").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        route?.stop()
        route = null
        ws?.close(1000, "client stopped")
        setState("已断开")
    }

    /** Cellular internet channel (reused by APK download etc.): during a car session the process default network is bound to the
     *  head unit hotspot (no internet), so any networking that does not use it fails. */
    fun internetNetwork(): android.net.Network? = route?.current()

    private fun setState(text: String) {
        stateText = text
        DebugFileLog.tap?.let {} // no-op: keeps the object reference stable (state does not go into the file log, avoiding echo)
    }

    private fun installTap() {
        if (tapInstalled) return
        tapInstalled = true
        DebugFileLog.tap = { seq, line ->
            synchronized(ringLock) {
                ring.addLast(seq to line)
                while (ring.size > RING_CAP) ring.removeFirst()
            }
            synchronized(pendingLock) { pending.addLast(seq to line) }
        }
    }

    private fun runLoop() {
        var backoffMs = 2_000L
        var attempt = 0
        while (running) {
            val ctx = appContext ?: return
            val url = BridgePrefs.serverUrl(ctx)
            if (url.isBlank()) {
                setState("未配置服务器地址")
                sleepQuiet(3_000)
                continue
            }
            // The route choice must enter the log stream: during a car session the process network is bound to the hotspot, and whether
            // the connection uses
            // cellular or the default, and why it failed, is blind debugging without logs (2026-10-22 observed:
            // WS dropped on joining the hotspot and healed only 4 minutes later, the log stream stalled with no trace to investigate)
            val network = route?.current()
            attempt++
            val client =
                WsClient(
                    url,
                    object : WsClient.Listener {
                        override fun onOpen() {
                            setState("已连接，配对中…")
                            // The protocol is device-driven: send hello (with the pairing code) first; the server replies welcome only
                            // after validating it.
                            // Waiting for welcome before sending hello would deadlock with the server waiting on us,
                            // and the state would stay on "pairing..." forever (hit on the first real device)
                            sendHello()
                        }

                        override fun onText(text: String) = onServerMessage(text)

                        override fun onClosed(reason: String) {
                            setState("连接断开：$reason")
                            lastDisconnectReason = reason.take(40)
                            debugLog("connection lost: $reason")
                        }
                    },
                    network,
                )
            ws = client
            setState("连接中…")
            nextRetryAtElapsed = 0
            connUsedDefaultNetwork = network == null
            debugLog(
                "connection attempt #$attempt route=${if (network != null) "cellular" else "default network"} " +
                    "backoff=${backoffMs}ms (a reset backoff means the previous round once succeeded)",
            )
            try {
                client.connect()
                backoffMs = 2_000L
            } catch (interrupted: InterruptedException) {
                return
            } catch (error: Exception) {
                // Connection failed/dropped: back off and reconnect (the failure reason goes into the log stream, so whether a retry storm
                // is happening is visible in-stream; otherwise the remote side only sees "logs stopped" and cannot guess why).
                // A default-network failure must be reported to the router: under a fake VALIDATED (cached verdict) every round hits
                // the same dead end; after noteFailure the next round switches to cellular.
                debugLog("connection failed (${error.message ?: error.javaClass.simpleName})")
                lastDisconnectReason = (error.message ?: error.javaClass.simpleName).take(40)
                route?.noteFailure(connUsedDefaultNetwork)
            }
            ws = null
            // Reaching here after a connection that was once up = a disconnect; tell the App layer to show a user hint (countdown in the
            // status line)
            if (wasConnected) {
                wasConnected = false
                runCatching { connectionEvents?.invoke(false) }
            }
            if (!running) break
            nextRetryAtElapsed = android.os.SystemClock.elapsedRealtime() + backoffMs
            sleepQuiet(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Write into the main log stream (same pipeline as business logs): the bridge's own connection events are visible for remote troubleshooting. */
    private fun debugLog(message: String) {
        appContext?.let { DebugFileLog.append(it, "BRIDGE: $message") }
    }

    private fun sleepQuiet(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- Handshake and messages after the connection is established ----

    private fun onServerMessage(text: String) {
        val msg =
            runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "welcome" -> {
                if (msg.optBoolean("ok")) {
                    setState("已连接（${msg.optLong("pairedAt")}）")
                    nextRetryAtElapsed = 0
                    debugLog("connected to the debug server (pairing passed), log stream resumed")
                    // This round used the default network and really worked: clear the "fake VALIDATED" failure verdict
                    route?.noteSuccess(connUsedDefaultNetwork)
                    if (!wasConnected) {
                        wasConnected = true
                        runCatching { connectionEvents?.invoke(true) }
                    }
                    // Full backfill: the server de-duplicates by seq and keeps a ring buffer
                    sendLogBatch(since = 0)
                } else {
                    setState("配对被拒：${msg.optString("error")}")
                    ws?.close(1008, "pairing rejected")
                }
            }

            "log.sync" -> sendLogBatch(msg.optLong("since"))

            "cmd" -> {
                val id = msg.optString("id")
                val name = msg.optString("name")
                val args = msg.optJSONObject("args") ?: JSONObject()
                Bg.run("bridge-cmd") {
                    val result = BridgeCommands.handle(appContext, name, args)
                    send(
                        JSONObject()
                            .put("t", "cmd.result")
                            .put("id", id)
                            .put("ok", result.optBoolean("ok"))
                            .put("detail", result.optString("detail")),
                    )
                }
            }

            "notify" -> {
                val message = msg.optString("text")
                mainHandler.post { BridgeOverlay.show(appContext, message, dismissMillis = 8_000) }
            }

            "apk.offer" -> {
                val delta = msg.optJSONObject("delta")
                ReinstallFlow.offer(
                    appContext,
                    msg.optString("url"),
                    msg.optString("sha256"),
                    msg.optString("version"),
                    deltaUrl = delta?.optString("url").takeIf { !it.isNullOrBlank() },
                    deltaSha256 = delta?.optString("sha256").takeIf { !it.isNullOrBlank() },
                    baseSha256 = delta?.optString("baseSha256").takeIf { !it.isNullOrBlank() },
                    deltaSizeKB = delta?.optInt("sizeKB", -1)?.takeIf { it > 0 },
                )
            }

            "ping" -> send(JSONObject().put("t", "pong").put("id", msg.optString("id")))

            // File log upload (history source of truth beyond the delta ring): read the tail of main_debug.log
            // -> gzip -> send back as a single base64 message. Reads only its own log file, no side effects.
            "log_file.req" -> {
                val reqId = msg.optString("id")
                val maxKB = msg.optInt("maxKB", 512).coerceIn(16, 4096)
                Bg.run("bridge-log-file") {
                    val ctx = appContext ?: return@run
                    val raw =
                        io.github.ezn24.cardeck.neo.util.DebugFileLog
                            .readTail(ctx, maxKB * 1024)
                            ?: ByteArray(0)
                    val gz =
                        java.io.ByteArrayOutputStream().use { bos ->
                            java.util.zip
                                .GZIPOutputStream(bos)
                                .use { it.write(raw) }
                            bos.toByteArray()
                        }
                    val payload =
                        JSONObject()
                            .put("t", "log_file.data")
                            .put("id", reqId)
                            .put("rawSize", raw.size)
                            .put("truncated", raw.size >= maxKB * 1024)
                            .put("gzB64", android.util.Base64.encodeToString(gz, android.util.Base64.NO_WRAP))
                    send(payload)
                    debugLog("file log sent back (raw ${raw.size / 1024}KB -> gzip ${gz.size / 1024}KB)")
                }
            }
        }
    }

    /**
     * sha256 of the installed APK (delta baseline). Hashing 15MB takes ~50ms, done once at the process's first hello
     * (the result is stored in prefs by version number; reconnects read the cache); returns null on failure (the server then uses full
     * packages only).
     */
    private fun baseSha256(ctx: Context): String? =
        runCatching {
            BridgePrefs.baseApkSha(ctx, BuildConfig.VERSION_NAME)?.let { return it }
            val source = File(ctx.applicationInfo.sourceDir)
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            BridgePrefs.setBaseApkSha(ctx, BuildConfig.VERSION_NAME, sha)
            sha
        }.getOrNull()

    private fun sendHello() {
        val ctx = appContext ?: return
        val hello =
            JSONObject()
                .put("t", "hello")
                .put("deviceId", BridgePrefs.deviceId(ctx))
                .put("deviceModel", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("code", BridgePrefs.pairCode(ctx))
                .put("lastSeq", DebugFileLog.lastSeq)
                .put("baseSha256", cachedBaseSha ?: baseSha256(ctx)?.also { cachedBaseSha = it } ?: "")
        send(hello)
    }

    /** Backfill logs in the ring buffer with seq > since (since=0 means all). */
    private fun sendLogBatch(since: Long) {
        val snapshot: List<Pair<Long, String>> =
            synchronized(ringLock) {
                ring.filter { it.first > since }
            }
        if (snapshot.isEmpty()) {
            send(JSONObject().put("t", "log.batch").put("lines", JSONArray()))
            return
        }
        // Chunked sending: keep a single JSON within ~256KB (friendly to the server's ws message limit)
        snapshot.chunked(200).forEach { chunk ->
            val lines = JSONArray()
            chunk.forEach { (seq, line) ->
                lines.put(JSONObject().put("seq", seq).put("text", line))
            }
            send(JSONObject().put("t", "log.batch").put("lines", lines))
        }
    }

    /** Flush the live log queue (called periodically by the connection thread). */
    fun flushPendingLogs() {
        val batch =
            synchronized(pendingLock) {
                if (pending.isEmpty()) return
                val taken = ArrayList<Pair<Long, String>>(pending.size)
                while (pending.isNotEmpty()) taken.add(pending.removeFirst())
                taken
            }
        val lines = JSONArray()
        batch.forEach { (seq, line) -> lines.put(JSONObject().put("seq", seq).put("text", line)) }
        send(JSONObject().put("t", "log.batch").put("lines", lines))
    }

    fun send(json: JSONObject) {
        ws?.let { client ->
            if (!client.sendText(json.toString())) {
                synchronized(pendingLock) {} // send failures are re-sent after reconnect (ring buffer backfill path)
            }
        }
    }

    /** Lets the settings page trigger one status broadcast manually. */
    fun requestStatus() {
        send(JSONObject().put("t", "status").put("state", stateText))
    }

    init {
        // Live log flush cadence: a separate daemon thread, one batch every 500ms
        Thread(
            {
                while (true) {
                    sleepQuiet(500)
                    if (running) flushPendingLogs()
                }
            },
            "bridge-log-flush",
        ).apply {
            isDaemon = true
            start()
        }
    }
}
