package io.github.ezn24.droidcar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTransferTest {
    private val sent = ArrayList<ByteArray>()

    private fun transfer(max: Int = 4_098) =
        ArtworkTransfer({
            sent += it
            true
        }, { max }, {})

    @Test
    fun setupCarriesSizeAndArtworkFileType() {
        transfer().begin(0x81, ByteArray(300))
        assertArrayEquals(
            byteArrayOf(0x81.toByte(), 0x04, 0, 0, 0, 0, 0, 0, 0x01, 0x2C, 0x00, 0x02),
            sent.single(),
        )
    }

    @Test
    fun smallFileGoesAsFirstAndOnlyDataAfterStart() {
        val t = transfer()
        val data = ByteArray(100) { it.toByte() }
        t.begin(0x81, data)
        t.onDatagram(byteArrayOf(0x81.toByte(), 0x01))
        val datagram = sent[1]
        assertEquals(0x81.toByte(), datagram[0])
        assertEquals(0xC0.toByte(), datagram[1])
        assertArrayEquals(data, datagram.copyOfRange(2, datagram.size))
    }

    @Test
    fun largeFileIsSplitIntoFirstDataLast() {
        val t = transfer(max = 12) // 10 data bytes per datagram
        val data = ByteArray(25) { it.toByte() }
        t.begin(0x90, data)
        t.onDatagram(byteArrayOf(0x90.toByte(), 0x01))
        val pieces = sent.drop(1)
        assertEquals(listOf(0x80, 0x00, 0x40), pieces.map { it[1].toInt() and 0xFF })
        assertArrayEquals(data, pieces.flatMap { it.drop(2) }.toByteArray())
    }

    @Test
    fun startForAnotherTransferIsIgnoredAndNewerReplacesOlder() {
        val t = transfer()
        t.begin(0x81, ByteArray(10))
        t.begin(0x82, ByteArray(10))
        t.onDatagram(byteArrayOf(0x81.toByte(), 0x01))
        assertEquals(2, sent.size) // two setups, no data for the stale one
        t.onDatagram(byteArrayOf(0x82.toByte(), 0x01))
        assertEquals(3, sent.size)
        t.onDatagram(byteArrayOf(0x82.toByte(), 0x05))
        t.onDatagram(byteArrayOf(0x82.toByte(), 0x01))
        assertTrue("after Success nothing is resent", sent.size == 3)
    }
}
