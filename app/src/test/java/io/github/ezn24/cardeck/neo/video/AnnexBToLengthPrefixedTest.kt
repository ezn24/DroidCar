package io.github.ezn24.cardeck.neo.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The new single-scan conversion must be byte-for-byte identical to the old implementation (split NALs -> patch SPS -> write length+data one by one). */
class AnnexBToLengthPrefixedTest {
    /** The old implementation moved over verbatim for comparison. */
    private fun reference(bytes: ByteArray): ByteArray? {
        val units = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
        } else {
            for (unit in 0 until starts.size) {
                val start = starts[unit]
                val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
                val bodyStart = start + prefix
                val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
                if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
            }
        }
        if (units.isEmpty()) return null
        val packed = ByteArrayOutputStream()
        for (nal in units) {
            val out = if (nal.isNotEmpty() && (nal[0].toInt() and 0x1F) == 7) patchSpsLevel(nal) else nal
            packed.write((out.size ushr 24) and 0xFF)
            packed.write((out.size ushr 16) and 0xFF)
            packed.write((out.size ushr 8) and 0xFF)
            packed.write(out.size and 0xFF)
            packed.write(out)
        }
        return packed.toByteArray()
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun typicalKeyFrameWithSpsPpsIdr() {
        val frame =
            bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1F, 0xAA) + // SPS level 31 → 40
                bytes(0, 0, 0, 1, 0x68, 0xEE, 0x3C) + // PPS
                bytes(0, 0, 1, 0x65) + ByteArray(300) { (it * 7 + 3).toByte() } // IDR (3-byte start code)
        assertArrayEquals(reference(frame), annexBToLengthPrefixed(frame))
    }

    @Test
    fun edgeCases() {
        val cases =
            listOf(
                bytes(0x41, 0x9A, 0x01), // No start code -> whole block is one NAL
                bytes(0, 0, 0, 1), // Only a start code (not recognized within the last 3 bytes) -> whole block
                bytes(0, 0, 0, 1, 0, 0, 0, 1, 0x41, 0x01), // Empty NAL is skipped
                bytes(0x09, 0x10, 0, 0, 1, 0x41, 0x02, 0, 0, 1), // Stray leading bytes dropped; trailing start code not recognized
                bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x33), // SPS level already >= 40 is left unchanged
            )
        for (c in cases) assertArrayEquals(c.joinToString(), reference(c), annexBToLengthPrefixed(c))
    }

    @Test
    fun randomStreamsMatchReference() {
        val rnd = java.util.Random(42)
        repeat(500) {
            val len = rnd.nextInt(200)
            // Biased random: lots of zeros make start codes appear frequently
            val data =
                ByteArray(len) {
                    if (rnd.nextInt(3) == 0) {
                        0
                    } else if (rnd.nextInt(5) == 0) {
                        1
                    } else {
                        rnd.nextInt(256).toByte()
                    }
                }
            assertArrayEquals(reference(data), annexBToLengthPrefixed(data))
        }
    }

    @Test
    fun emptyInputGivesNull() {
        assertNull(annexBToLengthPrefixed(ByteArray(0)))
    }
}
