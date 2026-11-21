package io.github.ezn24.cardeck.neo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ADTS header assembly and audio scheme switching.
 *
 * Background: head units (Honda HFT / Alpine 8A250-30A) using type=102 + AAC-LC "connect, consume, but
 * produce no sound"; the remaining difference that logs cannot tell apart is the payload encapsulation (raw frame vs ADTS). This pins every bit
 * of the ADTS header, so that during real-car trial and error a "wrong encapsulation" is not mistaken for "the head unit does not accept it".
 */
class AacLcEncoderTest {
    /** Parses the 13-bit frame length from the frame header. */
    private fun frameLength(header: ByteArray): Int =
        ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xFF) shr 5)

    /** Parses the channel configuration from the frame header. */
    private fun channelConfig(header: ByteArray): Int = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() and 0xFF) shr 6)

    @Test
    fun adtsHeaderFor44100Stereo() {
        val payload = 592
        val header = AacLcEncoder.adtsHeader(payload, 44_100, 2)

        assertEquals(7, header.size)
        // Sync word FFF + MPEG-4 + layer 0 + protection_absent=1
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF1, header[1].toInt() and 0xFF)
        // profile = AAC-LC (stores AOT-1 = 1)
        assertEquals(1, (header[2].toInt() and 0xFF) shr 6)
        // Sample rate index: 44100 -> 4
        assertEquals(4, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        // Channel configuration: stereo -> 2
        assertEquals(2, channelConfig(header))
        // Frame length (including the 7-byte header)
        assertEquals(payload + 7, frameLength(header))
        // buffer_fullness = 0x7FF (11 bits: low 5 bits of h5 + high 6 bits of h6), num_raw_data_blocks = 0
        assertEquals(
            0x7FF,
            ((header[5].toInt() and 0x1F) shl 6) or ((header[6].toInt() and 0xFC) shr 2),
        )
        assertEquals(0, header[6].toInt() and 0x03)
    }

    @Test
    fun adtsHeaderTracksSampleRateAndChannels() {
        // 48k -> index 3; mono -> 1
        val header = AacLcEncoder.adtsHeader(100, 48_000, 1)
        assertEquals(3, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        assertEquals(1, channelConfig(header))
        assertEquals(107, frameLength(header))
    }

    @Test
    fun frequencyIndexTable() {
        assertEquals(4, AacLcEncoder.frequencyIndex(44_100))
        assertEquals(3, AacLcEncoder.frequencyIndex(48_000))
        assertEquals(8, AacLcEncoder.frequencyIndex(16_000))
        // Unlisted sample rates fall back to 44100, avoiding writing an illegal index
        assertEquals(4, AacLcEncoder.frequencyIndex(37_000))
    }

    @Test
    fun tuningIsFixedToRawFrames() {
        // Final decision: raw AAC frame + spf=1024 + arrival timestamp, only this one set, no further switching
        assertEquals(1, AudioTuning.entries.size)
        assertTrue(!AudioTuning.RAW_SPF.adts)
        assertEquals(1024, AudioTuning.RAW_SPF.spf)
        assertTrue(!AudioTuning.RAW_SPF.highAccuracyTimestamps)
    }
}
