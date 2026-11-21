package io.github.ezn24.cardeck.neo.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Control channel encrypted frames interoperate with the reference receiver format (bidirectional, framed, fragmented delivery). */
class ControlCipherTest {
    @Test
    fun phoneAndReceiverCiphersInteropBothDirections() {
        val phoneWriteKey = ByteArray(32) { it.toByte() }
        val phoneReadKey = ByteArray(32) { (it + 7).toByte() }
        val phone = ControlCipher(phoneReadKey, phoneWriteKey)
        val receiver = ControlCipher(phoneWriteKey, phoneReadKey) // Receiver's view: reads what we write, writes what we read

        val request =
            "SETUP rtsp://192.168.149.253:7000/ RTSP/1.0\r\nContent-Length: 4\r\n\r\nbody"
                .toByteArray()
        assertArrayEquals(request, receiver.decrypt(phone.encrypt(request)).data)

        val reply = "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
        assertArrayEquals(reply, phone.decrypt(receiver.encrypt(reply)).data)
    }

    @Test
    fun largePayloadIsChunkedIntoMultipleFrames() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 1 })
        val receiver = ControlCipher(ByteArray(32) { 1 }, ByteArray(32))
        val big = ByteArray(0x5000) { (it % 251).toByte() } // Exceeds the single-frame 1422 limit
        assertArrayEquals(big, receiver.decrypt(phone.encrypt(big)).data)
    }

    /**
     * The control frame plaintext limit must be 1422 (1440 MTU - 2B AAD - 16B tag), matching catplay
     * `AirPlayCipherCodec`. The old implementation used 0x4000: frames larger than the MTU would be judged by a strict head unit as
     * a protocol violation (catplay's decoder reports PayloadTooBig directly for len > 1422).
     */
    @Test
    fun eachFrameStaysWithinCatplayMtu() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 3 })
        val receiver = ControlCipher(ByteArray(32) { 3 }, ByteArray(32))
        val big = ByteArray(5000) { (it % 127).toByte() }
        val wire = phone.encrypt(big)

        // Walk the wire format frame by frame: 2B length + plaintext + 16B tag; no frame's plaintext may exceed 1422
        var offset = 0
        var frames = 0
        while (offset < wire.size) {
            val length = (wire[offset].toInt() and 0xFF) or ((wire[offset + 1].toInt() and 0xFF) shl 8)
            assertTrue("Frame plaintext length $length exceeds 1422 (MTU 1440)", length <= 1422)
            offset += 2 + length + 16
            frames++
        }
        assertEquals("5000 bytes must split into at least 4 frames", 4, frames)
        assertArrayEquals(big, receiver.decrypt(wire).data)
    }

    @Test
    fun partialFramesDeliveredInPieces() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 2 })
        val receiver = ControlCipher(ByteArray(32) { 2 }, ByteArray(32))
        val message = "RTSP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val encrypted = receiver.encrypt(message + message)

        val half = encrypted.size / 2
        val first = phone.decrypt(encrypted.copyOfRange(0, half))
        val second = phone.decrypt(first.rest + encrypted.copyOfRange(half, encrypted.size))
        assertArrayEquals(message + message, first.data + second.data)
    }
}
