package io.github.ezn24.droidcar.audio

import io.github.ezn24.droidcar.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audio RTP packetization loopback test: the encrypted wire format must be restorable byte for byte by the "receiver decryption path"
 * (compare DiPlay `AudioStream.kt`: AAD = header 4..12, ciphertext = 12..n-8, nonce = trailing 8B little-endian).
 */
class AudioSenderTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    private fun sender() =
        AudioSender(
            host = "127.0.0.1",
            dataPort = 5000,
            streamKey = key,
            log = {},
        )

    @Test
    fun headerFieldsMatchCarPlayRtpConvention() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES) { it.toByte() }
        val wire = sender().sealPacket(pcm, sequence = 0x1234, timestamp = 512, nonceCounter = 0)

        // version=2; PT=100 (catplay uses the stream type directly as PT)
        assertEquals(0x80, wire[0].toInt() and 0xFF)
        assertEquals(100, wire[1].toInt() and 0x7F)
        // sequence BE
        assertEquals(0x12, wire[2].toInt() and 0xFF)
        assertEquals(0x34, wire[3].toInt() and 0xFF)
        // timestamp BE = 512
        assertEquals(0, wire[4].toInt() and 0xFF)
        assertEquals(0, wire[5].toInt() and 0xFF)
        assertEquals(2, wire[6].toInt() and 0xFF)
        assertEquals(0, wire[7].toInt() and 0xFF)
        // Total length = 12 header + plaintext + 16 tag + 8 nonce
        assertEquals(12 + pcm.size + 16 + 8, wire.size)
    }

    @Test
    fun receiverDecryptPathRestoresPcm() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES) { ((it * 13) and 0xFF).toByte() }
        val wire = sender().sealPacket(pcm, sequence = 7, timestamp = 1024, nonceCounter = 42)

        // -- Replicate receiver decryption (DiPlay AudioStream.runData + AndroidMediaSink LPCM path) --
        val aad = wire.copyOfRange(4, 12)
        val sealedEnd = wire.size - 8
        val sealed = wire.copyOfRange(12, sealedEnd)
        val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
        val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }

        val decrypted = AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
        // Wire format is **big-endian s16** (the receiver byteSwapS16s back to little-endian before feeding AudioTrack):
        // The decrypted result should equal the original PCM byte-swapped; swapping back matches the original PCM.
        val receiverPcm = AudioSender.bigEndianS16(decrypted) // Receiver byteSwapS16 (the swap is an involution)
        assertArrayEquals(pcm, receiverPcm)
    }

    @Test
    fun wirePayloadIsByteSwappedRelativeToCapture() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56) // Little-endian samples 0x1234, 0x5678
        val wire = sender().sealPacket(pcm, sequence = 0, timestamp = 0, nonceCounter = 0)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        // On the wire it should be big-endian: 0x12 0x34 0x56 0x78
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), decrypted)
    }

    @Test
    fun distinctNoncesProduceDistinctCiphertext() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES)
        val first = sender().sealPacket(pcm, 0, 0, nonceCounter = 0)
        val second = sender().sealPacket(pcm, 0, 0, nonceCounter = 1)
        val cipherA = first.copyOfRange(12, first.size - 8)
        val cipherB = second.copyOfRange(12, second.size - 8)
        // Same plaintext but incrementing nonce: ciphertext must differ (guards against nonce reuse)
        org.junit.Assert.assertFalse(cipherA.contentEquals(cipherB))
    }
}
