package io.github.ezn24.droidcar.iap2

import io.github.ezn24.droidcar.airplay.VirtualIap2Link
import io.github.ezn24.droidcar.media.CarNowPlaying
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingUpdateTest {
    @Test
    fun emptyStateMatchesCapturedIphoneFrameByteForByte() {
        // The MIB3 bring-up depends on this reply; the encoder must not drift from the real iPhone capture
        assertArrayEquals(VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE, NowPlayingUpdate.frame(null))
    }

    @Test
    fun trackFieldsAreEncodedInTheirGroups() {
        val state =
            NowPlayingUpdate.State(
                title = "晴天",
                artist = "Jay Chou",
                album = "叶惠美",
                durationMs = 269_000,
                status = NowPlayingUpdate.STATUS_PLAYING,
                elapsedMs = 12_345,
                appName = "Spotify",
            )
        val groups = Iap2Csm.parseParams(NowPlayingUpdate.body(state))
        val media = Iap2Csm.parseParams(groups.single { it.id == NowPlayingUpdate.GROUP_MEDIA_ITEM }.value)
        val playback = Iap2Csm.parseParams(groups.single { it.id == NowPlayingUpdate.GROUP_PLAYBACK }.value)
        assertEquals("晴天", media.single { it.id == NowPlayingUpdate.MEDIA_TITLE }.asString())
        assertEquals("Jay Chou", media.single { it.id == NowPlayingUpdate.MEDIA_ARTIST }.asString())
        assertEquals("叶惠美", media.single { it.id == NowPlayingUpdate.MEDIA_ALBUM_TITLE }.asString())
        assertEquals(269_000L, u32(media.single { it.id == NowPlayingUpdate.MEDIA_DURATION_MS }.value))
        assertEquals(
            1,
            playback
                .single { it.id == NowPlayingUpdate.PLAYBACK_STATUS }
                .value
                .single()
                .toInt(),
        )
        assertEquals(12_345L, u32(playback.single { it.id == NowPlayingUpdate.PLAYBACK_ELAPSED_MS }.value))
        assertEquals("Spotify", playback.single { it.id == NowPlayingUpdate.PLAYBACK_APP_NAME }.asString())
    }

    @Test
    fun onlyRequestedAttributesAreSent() {
        val request =
            NowPlayingUpdate.parseRequest(
                Iap2Csm.group(0, Iap2Csm.param(1, ByteArray(0)), Iap2Csm.param(12, ByteArray(0)), Iap2Csm.param(99, ByteArray(0))) +
                    Iap2Csm.group(1, Iap2Csm.param(0, ByteArray(0))),
            )
        assertEquals(listOf(1, 12), request.media)
        assertEquals(listOf(0), request.playback)
        val groups = Iap2Csm.parseParams(NowPlayingUpdate.body(NowPlayingUpdate.State(title = "x"), request))
        assertEquals(listOf(1, 12), Iap2Csm.parseParams(groups[0].value).map { it.id })
        assertEquals(listOf(0), Iap2Csm.parseParams(groups[1].value).map { it.id })
    }

    @Test
    fun unparseableRequestFallsBackToCapturedSet() {
        assertEquals(NowPlayingUpdate.Request.DEFAULT, NowPlayingUpdate.parseRequest(ByteArray(0)))
        assertEquals(NowPlayingUpdate.Request.DEFAULT, NowPlayingUpdate.parseRequest(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun oversizedTitleStaysWithinOneFrame() {
        val frame = NowPlayingUpdate.frame(NowPlayingUpdate.State(title = "長".repeat(2_000)))
        assertTrue(frame.size < 1_000)
    }

    @Test
    fun updatesOnlyOnChangeOrSeek() {
        val playing =
            NowPlayingUpdate.State(
                title = "a",
                durationMs = 200_000,
                status = NowPlayingUpdate.STATUS_PLAYING,
                elapsedMs = 10_000,
            )
        // Position advancing as the head unit extrapolates: no update
        assertFalse(CarNowPlaying.needsUpdate(playing, 1_000, playing.copy(elapsedMs = 15_000), 6_000))
        // Seek: update
        assertTrue(CarNowPlaying.needsUpdate(playing, 1_000, playing.copy(elapsedMs = 90_000), 6_000))
        // Paused: the position must stand still
        val paused = playing.copy(status = NowPlayingUpdate.STATUS_PAUSED)
        assertFalse(CarNowPlaying.needsUpdate(paused, 1_000, paused, 60_000))
        assertTrue(CarNowPlaying.needsUpdate(paused, 1_000, paused.copy(elapsedMs = 30_000), 2_000))
        // Track / state changes
        assertTrue(CarNowPlaying.needsUpdate(playing, 1_000, playing.copy(title = "b"), 1_100))
        assertTrue(CarNowPlaying.needsUpdate(playing, 1_000, paused, 1_100))
        assertTrue(CarNowPlaying.needsUpdate(null, 0, playing, 0))
        assertTrue(CarNowPlaying.needsUpdate(playing, 0, null, 0))
        assertFalse(CarNowPlaying.needsUpdate(null, 0, null, 0))
        // Live stream without duration: no position corrections
        val live = playing.copy(durationMs = 0, elapsedMs = 0)
        assertFalse(CarNowPlaying.needsUpdate(live, 1_000, live, 60_000))
    }

    private fun u32(value: ByteArray): Long = value.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
}
