package io.github.ezn24.cardeck.neo.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Head-unit microphone uplink: format negotiation and packet decryption (protocol facts in docs/MIC-RESEARCH.md). */
class CarMicTest {
    private fun info(
        input: Long,
        output: Long,
        audioType: String = "speechRecognition",
        latencyMicros: Long? = 106_000,
    ): Map<String, Any?> =
        mapOf(
            "audioFormats" to
                listOf(
                    mapOf("type" to 100L, "audioType" to "media", "audioInputFormats" to 0L, "audioOutputFormats" to 0x8800L),
                    mapOf("type" to 100L, "audioType" to audioType, "audioInputFormats" to input, "audioOutputFormats" to output),
                ),
            "audioLatencies" to
                listOfNotNull(
                    latencyMicros?.let {
                        mapOf("type" to 100L, "audioType" to audioType, "inputLatencyMicros" to it, "outputLatencyMicros" to it)
                    },
                ),
        )

    @Test fun prefers24kMonoWhenBothDirectionsSupportIt() {
        // catplay issue #15 head unit: input PCM 8/16/24k mono
        val format = CarMic.pickFormat(info(input = 0x54, output = 0x54))
        assertNotNull(format)
        assertEquals(1L shl 6, format!!.bit)
        assertEquals(24_000, format.sampleRate)
        assertEquals(106, format.latencyMs)
    }

    @Test fun usesIntersectionOfInputAndOutput() {
        // Uplink and downlink share one format: input has 24k, output only 16k -> pick 16k
        val format = CarMic.pickFormat(info(input = 0x54, output = 0x10))
        assertEquals(16_000, format!!.sampleRate)
    }

    @Test fun inputOnlyDeclarationStillWorks() {
        val format = CarMic.pickFormat(info(input = 1L shl 14, output = 0))
        assertEquals(48_000, format!!.sampleRate)
    }

    @Test fun noSpeechChannelOrNoPcmMonoMeansNoCarMic() {
        assertNull(CarMic.pickFormat(info(input = 0x54, output = 0x54, audioType = "telephony")))
        assertNull(CarMic.pickFormat(info(input = 0, output = 0x54)))
        // Opus only (0x40000000): not supported yet
        assertNull(CarMic.pickFormat(info(input = 0x40000000, output = 0x40000000)))
        assertNull(CarMic.pickFormat(null))
    }

    @Test fun missingLatencyFallsBackToDefault() {
        assertEquals(50, CarMic.pickFormat(info(input = 0x10, output = 0x10, latencyMicros = null))!!.latencyMs)
    }

    /** Packs one packet in the wire format of the head-unit side (DiPlay MicrophonePacketizer). */
    private fun carPacket(
        key: ByteArray,
        bigEndianPcm: ByteArray,
        counter: Long,
    ): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 100
        header[3] = 7 // seq
        header[7] = 0x40 // timestamp
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (8 * i)).toByte()
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, bigEndianPcm, header.copyOfRange(4, 12))
        val tail = ByteArray(8)
        for (i in 0 until 8) tail[i] = (counter ushr (8 * i)).toByte()
        return header + sealed + tail
    }

    @Test fun opensCarPacketAndConvertsToLittleEndian() {
        val key = CarMic.inputKey(ByteArray(32) { it.toByte() }, 12345L)
        val pcmBe = byteArrayOf(0x12, 0x34, 0x7F, 0x00, 0x80.toByte(), 0x01)
        val packet = carPacket(key, pcmBe, counter = 42)
        val opened = CarMic.openPacket(key, packet)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x00, 0x7F, 0x01, 0x80.toByte()), opened)
        // Receive buffer is larger than the packet: parse by actual length
        val padded = packet + ByteArray(100)
        assertArrayEquals(opened, CarMic.openPacket(key, padded, packet.size))
    }

    @Test fun acceptsWinPlayPacketsWhoseSequenceOverwroteTheVersionByte() {
        // WinPlay writes the sequence number in bytes 0..1 (overwriting 0x80/PT); encryption and AAD are unaffected, must still decrypt
        val key = CarMic.inputKey(ByteArray(32) { 1 }, 3575643869624359L)
        val packet = carPacket(key, byteArrayOf(0x00, 0x10), counter = 0)
        packet[0] = 0
        packet[1] = 0
        assertArrayEquals(byteArrayOf(0x10, 0x00), CarMic.openPacket(key, packet))
    }

    @Test fun rejectsTamperedShortOrWrongKeyPackets() {
        val key = CarMic.inputKey(ByteArray(32), 1L)
        val packet = carPacket(key, ByteArray(48) { 3 }, counter = 1)
        val tampered = packet.copyOf().also { it[6] = (it[6] + 1).toByte() } // AAD (timestamp) altered
        assertNull(CarMic.openPacket(key, tampered))
        assertNull(CarMic.openPacket(key, packet.copyOf(20)))
        assertNull(CarMic.openPacket(CarMic.inputKey(ByteArray(32), 2L), packet))
    }

    @Test fun downlinkPacketUsesSameLayoutAsCarMicPackets() {
        // Downlink is isomorphic to uplink (only the key label differs): decrypting with the same unpack function proves the layout matches
        val key = CarMic.outputKey(ByteArray(32) { 5 }, 99L)
        val silence = ByteArray(960)
        val wire = CarMic.sealDownlink(key, sequence = 3, timestamp = 1440, nonceCounter = 3, bigEndianPcm = silence)
        assertEquals(12 + 960 + 16 + 8, wire.size)
        assertEquals(100, wire[1].toInt())
        assertArrayEquals(silence, CarMic.openPacket(key, wire))
    }

    @Test fun inputKeyDiffersFromOutputKey() {
        val secret = ByteArray(32) { 9 }
        val output =
            AirPlayCrypto.hkdfSha512(
                secret,
                "DataStream-Salt77".toByteArray(),
                "DataStream-Output-Encryption-Key".toByteArray(),
            )
        assertFalse(output.contentEquals(CarMic.inputKey(secret, 77L)))
    }
}
