package io.github.ezn24.cardeck.neo.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher

/**
 * Notification listener service: uses notification access to enumerate active system media sessions and
 * expose "now playing" state (title/artist/playing) to the app. The dashboard music card renders from it.
 *
 * **Why notification access is required**: without it `MediaSessionManager.getActiveSessions()`
 * still returns sessions (so playback state is readable) but `metadata` is always null. The symptom is
 * "playback state changes, title/artist stay empty", easily mistaken for a parsing bug (2026-10-18).
 *
 * Grant path: system settings "Notification access", or Shizuku (shell) running
 * `cmd notification allow_listener <this component>` (see [NotificationAccess]).
 */
class MediaListenerService : NotificationListenerService() {
    companion object {
        fun componentName(context: android.content.Context): ComponentName = ComponentName(context, MediaListenerService::class.java)
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        io.github.ezn24.cardeck.neo.notifications.NotificationBannerController
            .post(this, sbn)
        // Turn-by-turn for the head unit's cluster (no-op unless a car subscribed to route guidance)
        runCatching {
            io.github.ezn24.cardeck.neo.nav.MapsNotificationSource
                .onPosted(this, sbn)
        }
        // Incoming / ongoing calls for the car's call card
        runCatching {
            io.github.ezn24.cardeck.neo.call.CallState
                .onPosted(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        io.github.ezn24.cardeck.neo.notifications.NotificationBannerController
            .remove(sbn.key)
        io.github.ezn24.cardeck.neo.nav.MapsNotificationSource
            .onRemoved(sbn)
        io.github.ezn24.cardeck.neo.call.CallState
            .onRemoved(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Connected means the system accepts our notification access (metadata is readable now).
        // Set both states: NotificationAccess uses it to decide whether to retry granting,
        // MediaSessionsState lets the UI tell "not playing" from "cannot read info".
        NotificationAccess.markGranted()
        NotificationAccess.markConnected()
        MediaSessionsState.accessGranted = true
        try {
            manager.addOnActiveSessionsChangedListener(
                sessionListener,
                componentName(this),
            )
            MediaSessionsState.resyncHook = { mainHandler.post { refresh() } }
            update(manager.getActiveSessions(componentName(this)))
            mainHandler.post(periodicRefresh)
            io.github.ezn24.cardeck.neo.call.CallState
                .attach(this)
            // A call already in progress when the listener (re)binds
            runCatching {
                activeNotifications?.forEach {
                    io.github.ezn24.cardeck.neo.call.CallState
                        .onPosted(it)
                }
            }
        } catch (_: SecurityException) {
            // Notification access revoked: stay silent (the system rebinds the service after re-grant)
            MediaSessionsState.accessGranted = false
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaSessionsState.accessGranted = false
        NotificationAccess.markDisconnected()
        // Must clear: otherwise the card keeps the pre-disconnect title/state, giving contradictory
        // displays such as "playing + not playing" or "playing + cannot read info" (2026-10-19:
        // after reinstall the listener was not bound by the system and the card stayed stale).
        MediaSessionsState.publish(null)
        io.github.ezn24.cardeck.neo.call.CallState
            .clear()
        watched.clear()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(periodicRefresh)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionListener) }
        MediaSessionsState.publish(null)
        super.onDestroy()
    }

    private val manager by lazy {
        getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            update(controllers ?: emptyList())
        }

    /** Sessions with a playback callback registered (avoids duplicate registration). */
    private val watched = HashSet<android.media.session.MediaSession.Token>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Fallback polling: keeps the card current for players that fire no callbacks or lose them.
     *
     * Real 1 Hz polling only **during a car session** (the music card only shows on the car). Previously,
     * whenever notification access existed it ran getActiveSessions + metadata deserialization (with
     * cover art) every second all day, even with no car; outside sessions it now only checks every 5s
     * whether a session started (no binder), while playback callbacks still push as usual.
     */
    private val periodicRefresh =
        object : Runnable {
            override fun run() {
                val inSession =
                    io.github.ezn24.cardeck.neo.launcher.VirtualLauncher
                        .appContext() != null
                if (inSession) refresh()
                mainHandler.postDelayed(this, if (inSession) 1_000L else 5_000L)
            }
        }

    /** Last cover extraction key (session+title+cover uri); getBitmap deserializes each time, so 1s polling must not repeat it. */
    private var lastArtKey: String? = null

    /** Cover bitmap cache (reuses the same instance while the key is unchanged, keeping NowPlaying equals stable). */
    private var cachedArt: android.graphics.Bitmap? = null

    /** Last time of "key matched but cover empty": right after a track change metadata is often
     *  incomplete (title first, cover field lagging), so an empty result is kept only 2s, then retried until the cover is ready. */
    private var artEmptyAt = 0L

    /** Dedicated single thread for cover uri decoding (serial; the main thread must not do IO/decoding). */
    private val artExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "media-art").apply { isDaemon = true }
        }

    private fun update(controllers: List<android.media.session.MediaController>) {
        controllers.forEach { controller ->
            if (watched.add(controller.sessionToken)) {
                controller.registerCallback(
                    object : android.media.session.MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            refresh()
                        }

                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            refresh()
                        }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }
        }
        refresh()
    }

    /** Extract the now-playing state from active sessions: prefer a PLAYING session, else keep the first with metadata. */
    private fun refresh() {
        val controllers =
            try {
                manager.getActiveSessions(componentName(this))
            } catch (_: SecurityException) {
                emptyList()
            }
        val picked =
            controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull { it.metadata != null }
        val now =
            picked?.let { controller ->
                val meta = controller.metadata
                val state = controller.playbackState
                val playing = state?.state == PlaybackState.STATE_PLAYING
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                MediaSessionsState.NowPlaying(
                    title = title,
                    artist =
                        meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    playing = playing,
                    album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    art = artFor(controller, meta, title),
                    pkg = controller.packageName,
                    positionMs = state?.position ?: 0L,
                    playbackSpeed = if (playing) (state?.playbackSpeed ?: 1f) else 0f,
                    durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                    anchoredAtMs = android.os.SystemClock.elapsedRealtime(),
                )
            }
        MediaSessionsState.publish(now)
    }

    /**
     * Cover extraction (cached): re-extract only when "session+title+cover uri" changes. Bitmap keys come
     * straight from metadata; uri covers (content:// etc.) decode on a background thread, with a no-cover state until done.
     */
    private fun artFor(
        controller: android.media.session.MediaController,
        meta: MediaMetadata?,
        title: String?,
    ): android.graphics.Bitmap? {
        val artUri =
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val key = "${controller.sessionToken}/$title/$artUri"
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastArtKey) {
            if (cachedArt != null) return cachedArt
            // Key unchanged but cover empty: no retry within 2s (getBitmap has a cost); after that re-extract,
            // which covers apps that add the cover after the track change (bitmap key goes from absent to present)
            if (now - artEmptyAt < 2_000) return null
        }
        lastArtKey = key
        cachedArt = null
        val direct =
            meta
                ?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (direct != null) {
            cachedArt = scaleArt(direct)
            return cachedArt
        }
        artEmptyAt = now
        if (artUri != null) {
            // Decoding runs on the dedicated thread; no refresh once ready, periodicRefresh picks it up next round (<=1s)
            artExecutor.execute {
                val bmp =
                    runCatching {
                        val input =
                            if (artUri.startsWith("http")) {
                                java.net.URL(artUri).openStream()
                            } else {
                                contentResolver.openInputStream(android.net.Uri.parse(artUri))
                            }
                        input?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                if (bmp != null && key == lastArtKey) {
                    cachedArt = scaleArt(bmp)
                }
            }
        }
        return null
    }

    /** Cover downscaled to <=512px (the card shows ~120dp; keeps memory down). */
    private fun scaleArt(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= 512) return src
        val sample =
            generateSequence(1) { it * 2 }.first { maxSide / it <= 512 }
        return android.graphics.Bitmap
            .createScaledBitmap(src, src.width / sample, src.height / sample, true)
    }
}

/** Global "now playing" state (shared in this process; the GridActivity music card listens and renders). */
object MediaSessionsState {
    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val playing: Boolean,
        val album: String? = null,
        val art: android.graphics.Bitmap? = null,
        /** App owning the media session (tapping the cover brings the playing app back). */
        val pkg: String? = null,
        /** Playback position at the sampling instant (ms). */
        val positionMs: Long = 0L,
        /** Playback speed (0 while paused, so no extrapolation). */
        val playbackSpeed: Float = 1f,
        val durationMs: Long = 0L,
        /** Anchor of the position sample in SystemClock.elapsedRealtime. */
        val anchoredAtMs: Long = 0L,
    ) {
        /** Estimated position at render time (extrapolated by speed while playing, clamped to the duration). */
        fun displayPositionAt(elapsedRealtimeMs: Long): Long {
            if (durationMs <= 0) return 0
            val base =
                if (playing && anchoredAtMs > 0) {
                    positionMs + ((elapsedRealtimeMs - anchoredAtMs) * playbackSpeed).toLong()
                } else {
                    positionMs
                }
            return base.coerceIn(0, durationMs)
        }
    }

    @Volatile
    var nowPlaying: NowPlaying? = null
        private set

    /**
     * Whether notification access has been granted.
     *
     * **Without it `metadata` is always null** (the session is still visible and playback state still changes),
     * so the card must tell "really not playing" from "playing but info unreadable";
     * otherwise the text would misleadingly say "media info not connected".
     */
    @Volatile
    var accessGranted: Boolean = false

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Notified on every publish, including position-only refreshes that [addListener] skips: the head-unit
     * now-playing feed (CarNowPlaying) needs them to notice seeks and does its own diffing.
     */
    private val rawListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addRawListener(listener: () -> Unit) {
        rawListeners.add(listener)
    }

    fun removeRawListener(listener: () -> Unit) {
        rawListeners.remove(listener)
    }

    fun publish(value: NowPlaying?) {
        val old = nowPlaying
        nowPlaying = value
        rawListeners.forEach { runCatching { it() } }
        // Position changes every second (1s fallback polling); log only the content key to avoid spam;
        // listeners are still notified each time (the UI refreshes the progress text from it)
        val contentChanged =
            old?.title != value?.title ||
                old?.artist != value?.artist ||
                old?.playing != value?.playing ||
                old?.art !== value?.art
        if (contentChanged) {
            VirtualLauncher.logSink?.invoke(
                "MEDIA: " + (
                    value?.let { "${it.title ?: "?"} - ${it.artist ?: "?"} playing=${it.playing}" }
                        ?: "no active session"
                ),
            )
        }
        // No notification when content is unchanged (only the position anchor moved with polling); progress ticking is self-refreshed by
        // the UI mediaTick each second
        // (previously a constantly changing anchoredAtMs notified all listeners every second, double-rendering with mediaTick)
        if (!contentChanged) return
        listeners.forEach { runCatching { it() } }
    }

    /** Resync hook (installed when the service connects): call after an optimistic UI update to pull the real state. */
    @Volatile
    var resyncHook: (() -> Unit)? = null

    fun requestResync() {
        resyncHook?.invoke()
    }
}
