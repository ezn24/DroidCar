package io.github.ezn24.cardeck.neo.airplay

/**
 * Control-channel encrypted frames (byte-compatible with the reference receiver's ControlCipher):
 * each frame = [2-byte little-endian ciphertext length (used as AAD)][ciphertext][16-byte tag],
 * nonce = 4 zero bytes + 8-byte little-endian counter; each direction has its own key and counter, incremented independently.
 *
 * Max plaintext per frame is **1422** (1440 MTU - 2-byte AAD - 16-byte tag). Per catplay
 * `AirPlayCipherCodec`: it fragments at "Ethernet 1500 - IPv6 header 40 - TCP header 20 = 1440",
 * and **its decoder treats len > 1422 as a protocol violation**. Older real iPhones fragment at 1024 bytes;
 * both are within what receivers accept, but frames above 1440 may be rejected by strict head units (the old code used 0x4000,
 * so any request exceeding the MTU was split into illegal frames; aligned with catplay on 2026-10-19).
 * The read direction stays lenient (fragment sizes sent by the head unit are its own choice; do not drop frames for exceeding our MTU).
 */
class ControlCipher(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
) {
    class Decrypted(
        val data: ByteArray,
        val rest: ByteArray,
    )

    private var readCounter = 0L
    private var writeCounter = 0L

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            output += AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(readCounter), ciphertextAndTag, aad)
            readCounter++
            offset = frameEnd
        }
        val joined = if (output.isEmpty()) ByteArray(0) else output.reduce { acc, bytes -> acc + bytes }
        return Decrypted(joined, buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag =
                AirPlayCrypto.chachaSeal(
                    writeKey,
                    AirPlayCrypto.nonce64(writeCounter),
                    chunk,
                    header,
                )
            output += header
            output += ciphertextAndTag
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return output.reduce { acc, bytes -> acc + bytes }
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16

        /** 1440 MTU - 2B AAD - 16B tag (same basis as catplay AIRPLAY_CIPHER_MTU_MAX). */
        const val MAX_PAYLOAD = 1422
    }
}
