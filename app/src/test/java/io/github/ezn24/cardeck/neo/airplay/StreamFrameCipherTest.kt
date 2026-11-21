package io.github.ezn24.cardeck.neo.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two forms of stream frame encryption (catplay `ScreenFrameCodec`'s three-way classification).
 *
 * Key regression points:
 *  1. The root object must land at byte 8 -- old CarPlay 210.81 head units do not read the trailer's topObject,
 *     taking the root directly at offset 8 (the reason for catplay upstream fix 9ad3a7e);
 *  2. AES-128-CTR key/iv derivation must match catplay `derive_aes_stream_keys` byte for byte;
 *     the keystream is **continuous across frames** (creating a new instance per frame garbles everything from the second frame);
 *  3. The session key of an unpaired session is all zeros (the `et=NONE` convention, not a random value).
 */
class StreamFrameCipherTest {
    @Test
    fun encodedPlistRootObjectStartsAtByteEight() {
        // Only more than 255 objects use a multi-byte offset table (a real car's SETUP body has hundreds of objects)
        val values = linkedMapOf<String, Any?>()
        repeat(150) { values["field%03d".format(it)] = it.toLong() }
        val data = BinaryPlist.encode(values)

        assertEquals("bplist00", String(data, 0, 8, Charsets.US_ASCII))
        // The root is a dictionary (0xD0 | count); the first byte is the root marker
        assertEquals(0xD0, data[8].toInt() and 0xF0)

        val trailer = data.size - 32
        val width = data[trailer + 6].toInt() and 0xFF
        assertTrue("Many objects should use a multi-byte offset table (width=$width)", width > 1)
        val count = readUint(data, trailer + 8, 8).toInt()
        val root = readUint(data, trailer + 16, 8).toInt()
        val table = readUint(data, trailer + 24, 8).toInt()
        val rootOffset = readUint(data, table + root * width, width).toInt()
        assertEquals("CarPlay 210.81 reads byte 8 directly", 8, rootOffset)

        // After reordering it must still be readable by a standard parser (offset table fixed up correctly)
        val decoded = BinaryPlist.parse(data) as Map<*, *>
        assertEquals(150, decoded.size)
        assertEquals(149L, decoded["field149"])
    }

    @Test
    fun smallPlistKeepsRootAtByteEightToo() {
        val data = BinaryPlist.encode(linkedMapOf("a" to 1L, "b" to "x"))
        assertEquals(0xD0, data[8].toInt() and 0xF0)
        assertEquals(1L, (BinaryPlist.parse(data) as Map<*, *>)["a"])
    }

    @Test
    fun aesStreamKeysMatchCatplayDerivation() {
        val sessionKey = ByteArray(16) { (it + 1).toByte() }
        val connectionId = 0x0102_0304_0506_0708L
        val (key, iv) = AirPlayStreamKeys.aesStreamKeys(sessionKey, connectionId)

        fun expected(prefix: String): ByteArray =
            AirPlayCrypto
                .sha512(
                    prefix.toByteArray(Charsets.US_ASCII),
                    "72623859790382856".toByteArray(Charsets.US_ASCII),
                    sessionKey,
                ).copyOfRange(0, 16)

        assertArrayEquals(expected("AirPlayStreamKey"), key)
        assertArrayEquals(expected("AirPlayStreamIV"), iv)
        assertEquals(16, key.size)
        assertEquals(16, iv.size)
    }

    @Test
    fun unpairedSessionUsesZeroSessionKey() {
        assertArrayEquals(ByteArray(16), AirPlayStreamKeys.ZERO_SESSION_KEY)
        val (key, iv) = AirPlayStreamKeys.aesStreamKeys(AirPlayStreamKeys.ZERO_SESSION_KEY, 1L)
        assertEquals(16, key.size)
        assertEquals(16, iv.size)
        // The stream key derived from the zero key should not also be all zeros (otherwise encryption degenerates into plaintext)
        assertNotEquals(ByteArray(16).toList(), key.toList())
    }

    @Test
    fun aesCtrKeystreamIsContinuousAcrossFrames() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val iv = ByteArray(16) { (it * 5).toByte() }
        val frameA = ByteArray(152) { it.toByte() }
        val frameB = ByteArray(152) { (it + 7).toByte() }

        // Sender: two consecutive frames from the same instance
        val sender = AirPlayCrypto.Aes128CtrStream(key, iv)
        val wireA = frameA.copyOf().also(sender::applyKeystream)
        val wireB = frameB.copyOf().also(sender::applyKeystream)

        // Receiver: decrypt continuously with the same instance
        val receiver = AirPlayCrypto.Aes128CtrStream(key, iv)
        val plainA = wireA.copyOf().also(receiver::applyKeystream)
        val plainB = wireB.copyOf().also(receiver::applyKeystream)

        assertArrayEquals(frameA, plainA)
        assertArrayEquals(frameB, plainB)
        // If a new instance were wrongly created per frame (keystream reset), wireB would equal the prefix of wireA
        assertNotEquals(wireA.take(16), wireB.take(16))
    }

    @Test
    fun chachaStreamKeyMatchesHkdfDerivation() {
        val secret = ByteArray(32) { it.toByte() }
        val key = AirPlayStreamKeys.chachaStreamKey(secret, 42L)
        assertArrayEquals(
            AirPlayCrypto.hkdfSha512(
                secret,
                "DataStream-Salt42".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            ),
            key,
        )
        assertEquals(32, key.size)
    }

    private fun readUint(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xFF) }
        return value
    }
}
