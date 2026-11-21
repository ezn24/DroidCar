package io.github.ezn24.cardeck.neo.audio

/**
 * AirPlay audio **sync packet** (RTCP PT=84 / byte 0xD4, sent to the audio stream's `controlPort`).
 *
 * Why it is required (2026-10-20, Honda HFT / Alpine 8A250-30A, located in the third field test):
 * audio RTP is only a stream of samples; the receiver also needs the **RTP timestamp <-> wall clock (NTP)**
 * mapping to work out when the Nth sample must reach the DAC. That information is not in the audio packets;
 * it comes only from the sync packet (see "Unofficial AirPlay Specification" audio/rtp_streams:
 * "Sync packets are sent once per second to the control port. They are used to
 * correlate the RTP timestamps currently used in the audio stream to the NTP time").
 *
 * Typical symptom when it is missing, exactly what we hit: the car gives a port in SETUP, accepts UDP, and `/feedback`
 * `sampleTime` advances at 44.1k (the clock runs) but the speaker plays not one sample: no playback anchor was established.
 * Video is unaffected because every video frame carries its own presentation timestamp and needs no cross-clock anchor.
 *
 * Wire format (20 bytes, **no SSRC field**):
 * ```
 * 0      1      2..3        4..7                8..15      16..19
 * 0x80   0xD4   seq(BE)     RTP ts(BE, current)     NTP(BE)    RTP ts(BE, next packet)
 * ```
 *  - Byte 0: V=2 (0x80); **the first packet (after RECORD/FLUSH) sets the X bit = 0x90**
 *  - Byte 1: M=1 + PT=84 -> 0xD4
 *  - Bytes 8..15: current NTP time (seconds<<32 | 2^32 fraction), on the same time base (monotonic clock) as the
 *    [io.github.ezn24.cardeck.neo.airplay.TimingServer] replies to the car, otherwise the anchor the car computes is shifted.
 */
object AudioSyncPacket {
    /** Fixed sync packet length (spec: 8B header + 8B NTP + 4B next-packet timestamp). */
    const val SIZE = 20

    /** RTP payload type: 84 = time sync (AirPlay private RTCP type). */
    private const val PT_SYNC = 0xD4

    /** Version 2 + Marker=1. */
    private const val BASE_FIRST_BYTE = 0x80

    /** Extension bit (X): set on the first sync packet after RECORD/FLUSH. */
    private const val EXTENSION_BIT = 0x10

    /**
     * Builds the packet.
     *
     * @param sequence the sync packet's own sequence (independent of the audio RTP sequence, increments on its own)
     * @param currentRtpTimestamp timestamp of the audio packet just sent (the instant [ntp] corresponds to)
     * @param nextRtpTimestamp RTP timestamp of the next audio packet (= current + samples per packet)
     * @param ntp 8-byte big-endian NTP time
     * @param first whether this is the first sync packet after RECORD (sets the extension bit)
     */
    fun build(
        sequence: Int,
        currentRtpTimestamp: Long,
        nextRtpTimestamp: Long,
        ntp: ByteArray,
        first: Boolean,
    ): ByteArray {
        require(ntp.size == 8) { "NTP time must be 8 bytes, got ${ntp.size}" }
        val packet = ByteArray(SIZE)
        packet[0] = (BASE_FIRST_BYTE or (if (first) EXTENSION_BIT else 0)).toByte()
        packet[1] = PT_SYNC.toByte()
        packet[2] = ((sequence ushr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        writeBe32(packet, 4, currentRtpTimestamp)
        ntp.copyInto(packet, 8)
        writeBe32(packet, 16, nextRtpTimestamp)
        return packet
    }

    private fun writeBe32(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        val truncated = value.toInt()
        target[offset] = ((truncated ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((truncated ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((truncated ushr 8) and 0xFF).toByte()
        target[offset + 3] = (truncated and 0xFF).toByte()
    }
}
