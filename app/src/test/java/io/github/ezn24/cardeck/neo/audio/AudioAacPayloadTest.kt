package io.github.ezn24.cardeck.neo.audio

import io.github.ezn24.cardeck.neo.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Regression test** for the audio encoding path.
 *
 * Background (2026-10-20 Honda HFT measured, three times "packets but no sound"): the old implementation unconditionally applied
 * big-endian s16 byte swapping to all payloads, swapping even the AAC bitstream by 2 bytes. After the swap the head unit's decoder judges the AAC frame corrupt
 * -- so packets flow, `/feedback` `sampleTime` keeps advancing, and the speakers stay silent.
 * This test locks in "AAC frames must go on the wire untouched, only PCM is byte-swapped".
 */
class AudioAacPayloadTest {
    private val key = ByteArray(32) { (it * 11 + 3).toByte() }

    private fun sender(codec: AudioCodec) =
        AudioSender(
            host = "127.0.0.1",
            dataPort = 5000,
            streamKey = key,
            log = {},
            codec = codec,
            streamType = if (codec == AudioCodec.AAC_LC) 102L else 100L,
        )

    @Test
    fun aacFrameGoesOnTheWireByteForByte() {
        // Header of a real MediaCodec first frame (raw AAC: id_syn_ele=CPE, tag=1)
        val frame = ByteArray(557) { index -> ((index * 37 + 0x21) and 0xFF).toByte() }
        val wire = sender(AudioCodec.AAC_LC).sealPacket(frame, 0, 0, 0)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        assertArrayEquals(frame, decrypted)
    }

    @Test
    fun pcmIsStillByteSwapped() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val wire = sender(AudioCodec.PCM).sealPacket(pcm, 0, 0, 0)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), decrypted)
    }

    @Test
    fun firstPacketCarriesMarkerBit() {
        val frame = ByteArray(16)
        val first = sender(AudioCodec.AAC_LC).sealPacket(frame, 0, 0, 0, marker = true)
        val second = sender(AudioCodec.AAC_LC).sealPacket(frame, 1, 1024, 1, marker = false)
        // Byte 1: M(0x80) | PT(102)
        assertEquals(0x80 or 102, first[1].toInt() and 0xFF)
        assertEquals(102, second[1].toInt() and 0xFF)
    }
}

/** Sync packet (RTCP 0xD4) wire format test: 20 bytes, no SSRC, extension bit set on the first packet. */
class AudioSyncPacketTest {
    private val ntp = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun layoutMatchesSpecification() {
        val packet = AudioSyncPacket.build(0x0102, 44100L, 45124L, ntp, first = false)
        assertEquals(AudioSyncPacket.SIZE, packet.size)
        assertEquals(0x80, packet[0].toInt() and 0xFF) // V=2, no X bit on non-first packets
        assertEquals(0xD4, packet[1].toInt() and 0xFF) // M=1 + PT=84
        assertEquals(0x01, packet[2].toInt() and 0xFF) // Sequence number BE
        assertEquals(0x02, packet[3].toInt() and 0xFF)
        assertEquals(0x00, packet[4].toInt() and 0xFF) // Current timestamp BE = 44100
        assertEquals(0x00, packet[5].toInt() and 0xFF)
        assertEquals(0xAC, packet[6].toInt() and 0xFF)
        assertEquals(0x44, packet[7].toInt() and 0xFF)
        assertArrayEquals(ntp, packet.copyOfRange(8, 16)) // NTP kept as-is, 8 bytes
        assertEquals(0x00, packet[16].toInt() and 0xFF) // Next packet timestamp BE = 45124
        assertEquals(0x00, packet[17].toInt() and 0xFF)
        assertEquals(0xB0, packet[18].toInt() and 0xFF)
        assertEquals(0x44, packet[19].toInt() and 0xFF)
    }

    @Test
    fun firstSyncSetsExtensionBit() {
        val packet = AudioSyncPacket.build(0, 0, 1024, ntp, first = true)
        assertEquals(0x90, packet[0].toInt() and 0xFF)
    }

    @Test
    fun rejectsWrongNtpLength() {
        var failed = false
        try {
            AudioSyncPacket.build(0, 0, 0, ByteArray(4), first = false)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        org.junit.Assert.assertTrue("NTP must be 8 bytes", failed)
    }
}
