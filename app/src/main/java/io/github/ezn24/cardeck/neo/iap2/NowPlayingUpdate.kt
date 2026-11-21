package io.github.ezn24.cardeck.neo.iap2

/**
 * iAP2 now-playing data for the head unit's instrument cluster / media widget: the accessory subscribes with
 * 0x5000 StartNowPlayingUpdates, the phone answers and then pushes 0x5001 NowPlayingUpdate on every change, and the
 * accessory stops with 0x5002.
 *
 * Field layout comes from a real iPhone's 0x5001 (Frida capture on Audi MIB3, kept byte-exact as
 * `VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE`):
 *   group 0 MediaItemAttributes: 1 Title (utf8), 4 PlaybackDurationInMilliseconds (u32), 6 AlbumTitle, 12 Artist,
 *     16 Genre, 18 Composer (utf8), 26 ArtworkFileTransferIdentifier (u8, 0x80 even with nothing playing)
 *   group 1 PlaybackAttributes: 0 PlaybackStatus (u8), 1 PlaybackElapsedTimeInMilliseconds (u32), 5 ShuffleMode (u8),
 *     6 RepeatMode (u8), 7 PlaybackAppName (utf8), 16 PlaybackAppBundleID (utf8, empty in the capture)
 * Receiver parsers agree on these IDs (CarPlayStation reads 1/4/6/12/16/18 and 0/1/7/16). Attribute 0
 * PersistentIdentifier (u64) is not in the capture but WinPlay requests it; it is sent only when asked for.
 * [body] with no state reproduces that capture exactly (unit test), so the MIB3 bring-up reply is unchanged.
 *
 * Only attributes the accessory asked for in 0x5000 are sent (an iPhone does the same); if the request cannot be
 * parsed the captured default set is used. Artwork is not transferred (it needs the iAP2 file-transfer session).
 */
internal object NowPlayingUpdate {
    const val GROUP_MEDIA_ITEM = 0
    const val GROUP_PLAYBACK = 1

    const val MEDIA_PERSISTENT_ID = 0
    const val MEDIA_TITLE = 1
    const val MEDIA_DURATION_MS = 4
    const val MEDIA_ALBUM_TITLE = 6
    const val MEDIA_ARTIST = 12
    const val MEDIA_GENRE = 16
    const val MEDIA_COMPOSER = 18
    const val MEDIA_ARTWORK_ID = 26

    const val PLAYBACK_STATUS = 0
    const val PLAYBACK_ELAPSED_MS = 1
    const val PLAYBACK_SHUFFLE = 5
    const val PLAYBACK_REPEAT = 6
    const val PLAYBACK_APP_NAME = 7
    const val PLAYBACK_BUNDLE_ID = 16

    const val STATUS_STOPPED = 0
    const val STATUS_PLAYING = 1
    const val STATUS_PAUSED = 2

    /** Value the iPhone sent for ArtworkFileTransferIdentifier with nothing playing (no transfer follows). */
    const val ARTWORK_ID_NONE = 0x80

    private val DEFAULT_MEDIA =
        listOf(MEDIA_TITLE, MEDIA_DURATION_MS, MEDIA_ALBUM_TITLE, MEDIA_ARTIST, MEDIA_GENRE, MEDIA_COMPOSER, MEDIA_ARTWORK_ID)
    private val DEFAULT_PLAYBACK =
        listOf(PLAYBACK_STATUS, PLAYBACK_ELAPSED_MS, PLAYBACK_SHUFFLE, PLAYBACK_REPEAT, PLAYBACK_APP_NAME, PLAYBACK_BUNDLE_ID)

    /** Everything we can fill; a request may pick from these. */
    private val SUPPORTED_MEDIA = listOf(MEDIA_PERSISTENT_ID) + DEFAULT_MEDIA

    data class State(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L,
        val status: Int = STATUS_STOPPED,
        val elapsedMs: Long = 0L,
        val appName: String = "",
        val bundleId: String = "",
        /** File-transfer ID of this track's artwork ([ARTWORK_ID_NONE] = no artwork). */
        val artworkId: Int = ARTWORK_ID_NONE,
    ) {
        /** Stable per track, so the head unit can tell a new track from a metadata refresh. */
        val persistentId: Long
            get() = if (title.isEmpty() && artist.isEmpty()) 0L else "$title|$artist|$album".hashCode().toLong() and 0xFFFF_FFFFL
    }

    /** Attribute IDs the accessory requested per group, in our send order. */
    data class Request(
        val media: List<Int>,
        val playback: List<Int>,
    ) {
        companion object {
            val DEFAULT = Request(DEFAULT_MEDIA, DEFAULT_PLAYBACK)
        }
    }

    /**
     * Parses 0x5000: each group holds one empty parameter per wanted attribute ID. A group the accessory left out is
     * not sent; a request with no recognizable attribute at all falls back to [Request.DEFAULT].
     */
    fun parseRequest(body: ByteArray): Request {
        val groups = runCatching { Iap2Csm.parseParams(body) }.getOrDefault(emptyList())

        fun idsOf(group: Int): Set<Int> =
            groups
                .filter { it.id == group }
                .flatMap { runCatching { Iap2Csm.parseParams(it.value) }.getOrDefault(emptyList()) }
                .map { it.id }
                .toSet()
        val media = idsOf(GROUP_MEDIA_ITEM)
        val playback = idsOf(GROUP_PLAYBACK)
        val request = Request(SUPPORTED_MEDIA.filter { it in media }, DEFAULT_PLAYBACK.filter { it in playback })
        return if (request.media.isEmpty() && request.playback.isEmpty()) Request.DEFAULT else request
    }

    /** IDs the accessory asked for (for the log line), regardless of whether we support them. */
    fun requestedIds(body: ByteArray): String {
        val groups = runCatching { Iap2Csm.parseParams(body) }.getOrDefault(emptyList())
        return groups.joinToString(" ") { group ->
            val ids = runCatching { Iap2Csm.parseParams(group.value) }.getOrDefault(emptyList()).map { it.id }
            "group${group.id}=$ids"
        }
    }

    fun body(
        state: State?,
        request: Request = Request.DEFAULT,
    ): ByteArray {
        val s = state ?: State()
        val media =
            request.media.map { id ->
                when (id) {
                    MEDIA_PERSISTENT_ID -> Iap2Csm.param(id, u64(s.persistentId))
                    MEDIA_TITLE -> Iap2Csm.string(id, clean(s.title))
                    MEDIA_DURATION_MS -> Iap2Csm.u32(id, u32(s.durationMs))
                    MEDIA_ALBUM_TITLE -> Iap2Csm.string(id, clean(s.album))
                    MEDIA_ARTIST -> Iap2Csm.string(id, clean(s.artist))
                    MEDIA_ARTWORK_ID -> Iap2Csm.u8(id, s.artworkId)
                    else -> Iap2Csm.string(id, "") // genre / composer: not available from Android media sessions
                }
            }
        val playback =
            request.playback.map { id ->
                when (id) {
                    PLAYBACK_STATUS -> Iap2Csm.u8(id, s.status)
                    PLAYBACK_ELAPSED_MS -> Iap2Csm.u32(id, u32(s.elapsedMs))
                    PLAYBACK_APP_NAME -> Iap2Csm.string(id, clean(s.appName))
                    PLAYBACK_BUNDLE_ID -> Iap2Csm.string(id, clean(s.bundleId))
                    else -> Iap2Csm.u8(id, 0) // shuffle / repeat off
                }
            }
        val out = java.io.ByteArrayOutputStream()
        if (media.isNotEmpty()) out.write(Iap2Csm.group(GROUP_MEDIA_ITEM, *media.toTypedArray()))
        if (playback.isNotEmpty()) out.write(Iap2Csm.group(GROUP_PLAYBACK, *playback.toTypedArray()))
        return out.toByteArray()
    }

    fun frame(
        state: State?,
        request: Request = Request.DEFAULT,
    ): ByteArray = Iap2Csm.encode(Iap2MessageIds.NOW_PLAYING_UPDATE, body(state, request))

    /**
     * Keeps a string inside the u16 length field with room for the rest of the message (a 4 KB title is not a real
     * case, but a malformed one must not break the frame); NUL is not allowed in an iAP2 utf8 value.
     */
    private fun clean(value: String): String {
        val noNul = value.replace(0.toChar(), ' ')
        if (noNul.encodeToByteArray().size <= MAX_STRING_BYTES) return noNul
        var cut = noNul.take(MAX_STRING_BYTES)
        while (cut.encodeToByteArray().size > MAX_STRING_BYTES) cut = cut.dropLast(1)
        return cut
    }

    private fun u32(value: Long): Long = value.coerceIn(0L, 0xFFFF_FFFFL)

    private fun u64(value: Long): ByteArray = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }

    private const val MAX_STRING_BYTES = 255
}
