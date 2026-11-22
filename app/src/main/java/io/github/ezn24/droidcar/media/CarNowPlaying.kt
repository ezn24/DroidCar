package io.github.ezn24.droidcar.media

import android.content.Context
import android.os.SystemClock
import io.github.ezn24.droidcar.iap2.ArtworkTransfer
import io.github.ezn24.droidcar.iap2.NowPlayingUpdate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Feeds the phone's now-playing state to head units that subscribed with iAP2 0x5000 (instrument cluster / media
 * widget), as 0x5001 updates. Each link (Bluetooth control session, Wi-Fi tunnel) holds its own subscription.
 *
 * Updates follow what an iPhone does: one on subscribe, then one per change (track, metadata, play/pause, app), plus
 * one when the position jumps (seek) away from what the head unit extrapolates from the last update. The position
 * itself is not streamed: the accessory advances it from PlaybackStatus=playing. Changes are coalesced for
 * [DEBOUNCE_MS] because metadata arrives in stages (title first, duration later).
 *
 * Artwork: the cover from the media session is scaled to [ARTWORK_MAX_PX], JPEG-encoded once per cover and sent
 * through the link's [ArtworkTransfer] (iAP2 file-transfer session). Links without one announce "no artwork".
 */
object CarNowPlaying {
    private class Subscription(
        val name: String,
        val request: NowPlayingUpdate.Request,
        val send: (ByteArray) -> Boolean,
        val artwork: ArtworkTransfer?,
    ) {
        @Volatile var last: NowPlayingUpdate.State? = null

        @Volatile var lastSentAt = 0L
    }

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val labels = ConcurrentHashMap<String, String>()
    private val scheduled = AtomicBoolean(false)
    private val executor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "car-now-playing").apply { isDaemon = true }
        }

    @Volatile
    var log: (String) -> Unit = {}

    @Volatile
    private var appContext: Context? = null

    private val listener: () -> Unit = { schedule() }

    /** Cover currently encoded for the car: source bitmap (identity), transfer ID and JPEG bytes. */
    private class Artwork(
        val source: android.graphics.Bitmap,
        val id: Int,
        val jpeg: ByteArray,
    )

    @Volatile
    private var artwork: Artwork? = null
    private var nextArtworkId = FIRST_ARTWORK_ID

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The accessory sent 0x5000 on link [name]. Returns the first 0x5001 frame, which the caller sends itself in the
     * same step (the MIB3 tunnel must answer inside its own lock and timing); later updates go through [send].
     */
    internal fun subscribe(
        name: String,
        requestBody: ByteArray,
        fixedRequest: NowPlayingUpdate.Request? = null,
        artworkTransfer: ArtworkTransfer? = null,
        send: (ByteArray) -> Boolean,
    ): ByteArray {
        val request = fixedRequest ?: NowPlayingUpdate.parseRequest(requestBody)
        val subscription = Subscription(name, request, send, artworkTransfer)
        // Artwork, app name and anything that changed meanwhile follow from the background pass
        val state = currentState(resolveLabels = false)?.copy(artworkId = NowPlayingUpdate.ARTWORK_ID_NONE)
        subscription.last = state
        subscription.lastSentAt = SystemClock.elapsedRealtime()
        subscriptions[name] = subscription
        MediaSessionsState.addRawListener(listener)
        log(
            "MEDIA-CAR: $name subscribed to now playing (${NowPlayingUpdate.requestedIds(requestBody)}) -> " +
                "sending ${describe(state)}",
        )
        schedule()
        return NowPlayingUpdate.frame(state, request)
    }

    /** Runs [task] on the feed thread (file-transfer answers arrive on link reader threads holding link locks). */
    fun post(task: () -> Unit) {
        executor.execute { runCatching(task).onFailure { log("MEDIA-CAR: ${it.message}") } }
    }

    fun unsubscribe(
        name: String,
        reason: String,
    ) {
        if (subscriptions.remove(name) != null) log("MEDIA-CAR: $name now-playing subscription ended ($reason)")
        if (subscriptions.isEmpty()) MediaSessionsState.removeRawListener(listener)
    }

    private fun schedule() {
        if (subscriptions.isEmpty() || !scheduled.compareAndSet(false, true)) return
        executor.schedule({
            scheduled.set(false)
            runCatching { pushChanges() }.onFailure { log("MEDIA-CAR: update failed - ${it.message}") }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun pushChanges() {
        val shared = currentState(resolveLabels = true)
        val cover = shared?.let { refreshArtwork() }
        val now = SystemClock.elapsedRealtime()
        for (subscription in subscriptions.values) {
            val canSendArtwork = cover != null && subscription.artwork != null
            val state = shared?.copy(artworkId = if (canSendArtwork) cover!!.id else NowPlayingUpdate.ARTWORK_ID_NONE)
            if (!needsUpdate(subscription.last, subscription.lastSentAt, state, now)) continue
            val ok = subscription.send(NowPlayingUpdate.frame(state, subscription.request))
            if (ok) {
                val newCover = canSendArtwork && subscription.last?.artworkId != state?.artworkId
                subscription.last = state
                subscription.lastSentAt = now
                log("MEDIA-CAR: ${subscription.name} <- ${describe(state)}")
                // The update named the transfer; the file follows on the file-transfer session
                if (newCover) subscription.artwork?.begin(cover!!.id, cover.jpeg)
            } else {
                // The link is gone; its owner normally unsubscribes first, this covers a missed path
                unsubscribe(subscription.name, "send failed")
            }
        }
    }

    private fun currentState(resolveLabels: Boolean): NowPlayingUpdate.State? {
        val now = MediaSessionsState.nowPlaying ?: return null
        val pkg = now.pkg
        val label = pkg?.let { if (resolveLabels) labelOf(it) else labels[it] }.orEmpty()
        return NowPlayingUpdate.State(
            title = now.title.orEmpty(),
            artist = now.artist.orEmpty(),
            album = now.album.orEmpty(),
            durationMs = now.durationMs.coerceAtLeast(0L),
            status = if (now.playing) NowPlayingUpdate.STATUS_PLAYING else NowPlayingUpdate.STATUS_PAUSED,
            elapsedMs = now.displayPositionAt(SystemClock.elapsedRealtime()),
            appName = label,
            bundleId = pkg.orEmpty(),
        )
    }

    /** Encodes the current session cover once per bitmap (bitmaps are reused while the cover is unchanged). */
    private fun refreshArtwork(): Artwork? {
        val bitmap = MediaSessionsState.nowPlaying?.art ?: return null
        artwork?.let { if (it.source === bitmap) return it }
        val jpeg =
            runCatching {
                val scale = minOf(1f, ARTWORK_MAX_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
                val scaled =
                    if (scale < 1f) {
                        android.graphics.Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1),
                            true,
                        )
                    } else {
                        bitmap
                    }
                java.io
                    .ByteArrayOutputStream()
                    .also { out ->
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out)
                    }.toByteArray()
            }.getOrElse {
                log("MEDIA-CAR: artwork encode failed - ${it.message}")
                return null
            }
        val id = nextArtworkId
        nextArtworkId = if (id >= LAST_ARTWORK_ID) FIRST_ARTWORK_ID else id + 1
        return Artwork(bitmap, id, jpeg).also { artwork = it }
    }

    private fun labelOf(pkg: String): String =
        labels.getOrPut(pkg) {
            val pm = appContext?.packageManager ?: return ""
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
        }

    private fun describe(state: NowPlayingUpdate.State?): String =
        state?.let {
            // Titles are shown on the car anyway; the log keeps them short
            "\"${it.title.take(40)}\" / \"${it.artist.take(30)}\" status=${it.status} " +
                "pos=${it.elapsedMs / 1000}s/${it.durationMs / 1000}s app=${it.appName} " +
                "artwork=" + if (it.artworkId == NowPlayingUpdate.ARTWORK_ID_NONE) "none" else "#${it.artworkId}"
        } ?: "nothing playing"

    /**
     * Whether the head unit needs a new 0x5001: any field other than the position changed, or the position is more
     * than [SEEK_TOLERANCE_MS] away from what the head unit extrapolates (it advances only while playing).
     */
    internal fun needsUpdate(
        last: NowPlayingUpdate.State?,
        lastSentAt: Long,
        current: NowPlayingUpdate.State?,
        now: Long,
    ): Boolean {
        if (last == null || current == null) return last != current
        if (last.copy(elapsedMs = 0) != current.copy(elapsedMs = 0)) return true
        // No duration (live stream): Android reports no usable position, nothing to correct
        if (last.durationMs <= 0) return false
        val expected =
            if (last.status == NowPlayingUpdate.STATUS_PLAYING) last.elapsedMs + (now - lastSentAt) else last.elapsedMs
        val clamped = if (last.durationMs > 0) expected.coerceAtMost(last.durationMs) else expected
        return abs(current.elapsedMs - clamped) > SEEK_TOLERANCE_MS
    }

    /** Cluster tiles are small; 320 px keeps one cover around 15-30 KB. */
    private const val ARTWORK_MAX_PX = 320
    private const val ARTWORK_JPEG_QUALITY = 85

    /** 0x80 means "no artwork" (as in the iPhone capture); transfers rotate through the rest of the u8 range. */
    private const val FIRST_ARTWORK_ID = 0x81
    private const val LAST_ARTWORK_ID = 0xFF

    private const val DEBOUNCE_MS = 300L
    private const val SEEK_TOLERANCE_MS = 2_000L
}
