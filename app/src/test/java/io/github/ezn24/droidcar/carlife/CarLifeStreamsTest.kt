package io.github.ezn24.droidcar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeStreamsTest {
    @Test fun streamSizeFollowsBaiduTable() {
        assertEquals(1280 to 720, CarLifeSession.streamSize(1920, 720, newVehicle = false))
        assertEquals(1920 to 720, CarLifeSession.streamSize(1920, 720, newVehicle = true))
        assertEquals(848 to 480, CarLifeSession.streamSize(800, 480, newVehicle = false))
        assertEquals(768 to 432, CarLifeSession.streamSize(640, 480, newVehicle = true))
    }

    @Test fun framesBeforeStartAreHeldFromTheLastKeyFrameAndCarryTheConfig() {
        val sent = ArrayList<ByteArray>()
        val feed = CarLifeVideoFeed({ sent += it }, {})
        feed.open()
        feed.config(byteArrayOf(9))
        feed.frame(byteArrayOf(1), key = false) // no key frame yet: dropped
        feed.frame(byteArrayOf(2), key = true)
        feed.frame(byteArrayOf(3), key = false)
        assertTrue(sent.isEmpty())
        feed.start()
        feed.frame(byteArrayOf(4), key = false)
        assertEquals(3, sent.size)
        assertArrayEquals(byteArrayOf(9, 2), sent[0])
        assertArrayEquals(byteArrayOf(3), sent[1])
        assertArrayEquals(byteArrayOf(4), sent[2])
    }

    @Test fun mediaStreamOpensOnDataAndPausesWhenQuiet() {
        var now = 0L
        val ids = ArrayList<Int>()
        val modules = ArrayList<Pair<Int, Int>>()
        val media = CarLifeMediaStream({ id, _ -> ids += id }, { m, s -> modules += m to s }, { now })
        media.oldVehicle = false
        media.data(ByteArray(4))
        assertEquals(listOf(CarLifeProtocol.MEDIA_INIT, CarLifeProtocol.MEDIA_DATA), ids)
        assertEquals(CarLifeProtocol.MODULE_MUSIC to 1, modules.last())
        now += CarLifeMediaStream.QUIET_MS
        assertTrue(media.idle())
        assertEquals(CarLifeProtocol.MEDIA_PAUSE, ids.last())
        assertEquals(CarLifeProtocol.MODULE_MUSIC to 0, modules.last())
    }
}
