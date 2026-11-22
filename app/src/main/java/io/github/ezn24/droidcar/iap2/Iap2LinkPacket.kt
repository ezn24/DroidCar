package io.github.ezn24.droidcar.iap2

/**
 * iAP2 link layer packet codec.
 *
 * Wireless (Bluetooth RFCOMM) and wired (USB bulk) carry the same link layer byte stream:
 *
 *   FF 5A | u16 length | u8 control | u8 seq | u8 ack | u8 session ID | u8 header checksum | payload | u8 payload checksum
 *
 * SYN sync packets start with FF 55. The checksum is the two's complement of the byte sum: adding all bytes including the checksum
 * (mod 256) must give 0.
 *
 * Format source (public reverse-engineering material, byte-for-byte consistent):
 *  - reference/carplay-wifi-extractor example/iap2/link_layer.py (Struct(">HHBBBB") + gen_checksum)
 *  - Oligo Security "Pwn My Ride" description of the iAP2 link layer
 *
 * Session ID convention: 0 = control/authentication, 1 = data, 2+ = External Accessory streams.
 *
 * Phase 1 TODO: SYN/ACK state machine (send/receive sequence numbers, retransmission, LinkSynchronizationPayload
 * ">BBHHHBB" negotiation: version=1, maxOutgoing, maxLen, retransTimeout, ackTimeout, maxRetrans, maxAck).
 */
object Iap2LinkPacket {
    const val START_DATA = 0xFF5A
    const val START_SYN = 0xFF55

    const val CTRL_SYN: Int = 0x80
    const val CTRL_ACK: Int = 0x40
    const val CTRL_EAK: Int = 0x20
    const val CTRL_RST: Int = 0x10

    /** FF5A + len(2) + ctrl(1) + seq(1) + ack(1) + sessionId(1) + header checksum(1) */
    const val HEADER_BYTES = 9
    const val CHECKSUM_BYTES = 1

    const val SESSION_CONTROL = 0
    const val SESSION_DATA = 1

    data class LinkPacket(
        val control: Int,
        val seq: Int,
        val ack: Int,
        val sessionId: Int,
        val payload: ByteArray,
    ) {
        val isSyn: Boolean get() = control and CTRL_SYN != 0
        val isAck: Boolean get() = control and CTRL_ACK != 0

        override fun equals(other: Any?): Boolean =
            other is LinkPacket &&
                other.control == control &&
                other.seq == seq &&
                other.ack == ack &&
                other.sessionId == sessionId &&
                other.payload.contentEquals(payload)

        override fun hashCode(): Int = control * 31 + seq * 961 + ack * 31 + sessionId + payload.contentHashCode()
    }

    /** Sum of all bytes (including the checksum) mod 256 should be 0, so checksum = -(payload sum) & 0xFF. */
    fun checksum(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Int {
        var sum = 0
        for (index in from until to) sum += bytes[index].toInt() and 0xFF
        return (-sum) and 0xFF
    }

    fun verify(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Boolean {
        var sum = 0
        for (index in from until to) sum += bytes[index].toInt() and 0xFF
        return sum and 0xFF == 0
    }

    fun encode(packet: LinkPacket): ByteArray {
        val totalLen = HEADER_BYTES + packet.payload.size + CHECKSUM_BYTES
        val out = ByteArray(totalLen)
        writeU16(out, 0, START_DATA)
        writeU16(out, 2, totalLen)
        out[4] = packet.control.toByte()
        out[5] = packet.seq.toByte()
        out[6] = packet.ack.toByte()
        out[7] = packet.sessionId.toByte()
        out[8] = checksum(out, 0, 8).toByte()
        packet.payload.copyInto(out, HEADER_BYTES)
        out[out.size - 1] = checksum(out, HEADER_BYTES, out.size - 1).toByte()
        return out
    }

    /**
     * Fixed-length parse of a single data packet (FF5A); variable-length/streaming reassembly is in [Iap2Csm] and the Phase 1 link
     * state machine.
     */
    fun decode(bytes: ByteArray): LinkPacket? {
        if (bytes.size < HEADER_BYTES + CHECKSUM_BYTES) return null
        if (readU16(bytes, 0) != START_DATA) return null
        if (!verify(bytes, 0, HEADER_BYTES)) return null
        val length = readU16(bytes, 2)
        if (length != bytes.size) return null
        if (!verify(bytes, HEADER_BYTES, bytes.size)) return null
        return LinkPacket(
            control = bytes[4].toInt() and 0xFF,
            seq = bytes[5].toInt() and 0xFF,
            ack = bytes[6].toInt() and 0xFF,
            sessionId = bytes[7].toInt() and 0xFF,
            payload = bytes.copyOfRange(HEADER_BYTES, bytes.size - 1),
        )
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
