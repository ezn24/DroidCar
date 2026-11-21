package io.github.ezn24.cardeck.neo.airplay

/**
 * Pure-logic part of the head-unit microphone uplink (format negotiation + packet decryption); protocol facts are in docs/MIC-RESEARCH.md.
 *
 * Direction: head unit -> phone. In SETUP the phone declares `input=true` plus the `dataPort` it listens on,
 * and the head unit sends microphone RTP packets to "phone address:dataPort". The packets are isomorphic to the media audio we send to the head unit:
 * 12B plaintext RTP header + ChaCha20-Poly1305 ciphertext (AAD = header[4..12]) + 16B tag + 8B little-endian nonce counter;
 * key = HKDF-SHA512(shared secret, "DataStream-Salt<connId>", "DataStream-Input-Encryption-Key").
 */
object CarMic {
    /** Negotiation result: AirPlay audioFormat bit + sample rate (mono 16-bit PCM). */
    data class Format(
        val bit: Long,
        val sampleRate: Int,
        val latencyMs: Int,
    )

    /**
     * PCM mono only (Opus would need a separate decoder; not done yet). 24k is preferred, as commonly used by Siri:
     * AirPlay format bits bit2/4/6/8/14 = 8k/16k/24k/32k/48k mono 16-bit
     * (DiPlay PR #898 measured 0x154 = 8/16/24/32k mono, 0x4000 = 48k mono).
     */
    private val PCM_MONO_PREFERENCE =
        listOf(
            (1L shl 6) to 24_000,
            (1L shl 4) to 16_000,
            (1L shl 14) to 48_000,
            (1L shl 8) to 32_000,
            (1L shl 2) to 8_000,
        )

    const val STREAM_TYPE_MAIN_AUDIO = 100L
    const val AUDIO_TYPE_SPEECH = "speechRecognition"
    private const val DEFAULT_LATENCY_MS = 50

    /**
     * Picks the microphone format from the head unit's /info: among entries with type=100 and audioType=speechRecognition,
     * **the uplink and downlink share one format** (the head unit builds the bidirectional stream from a single audioFormat), so take input intersected with output;
     * if the head unit declares no output bits, only input counts. Returns null = no usable speech input path.
     */
    fun pickFormat(info: Map<*, *>?): Format? {
        val formats = (info?.get("audioFormats") as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val speech =
            formats.firstOrNull {
                (it["type"] as? Number)?.toLong() == STREAM_TYPE_MAIN_AUDIO &&
                    (it["audioType"] as? String).equals(AUDIO_TYPE_SPEECH, ignoreCase = true)
            } ?: return null
        val input = (speech["audioInputFormats"] as? Number)?.toLong() ?: 0L
        if (input == 0L) return null
        val output = (speech["audioOutputFormats"] as? Number)?.toLong() ?: 0L
        val usable = if (output != 0L) input and output else input
        val chosen = PCM_MONO_PREFERENCE.firstOrNull { (bit, _) -> usable and bit != 0L } ?: return null
        return Format(chosen.first, chosen.second, latencyMs(info))
    }

    private fun latencyMs(info: Map<*, *>?): Int {
        val latencies = (info?.get("audioLatencies") as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val entry =
            latencies.firstOrNull {
                (it["type"] as? Number)?.toLong() == STREAM_TYPE_MAIN_AUDIO &&
                    (it["audioType"] as? String).equals(AUDIO_TYPE_SPEECH, ignoreCase = true)
            }
        val micros = (entry?.get("inputLatencyMicros") as? Number)?.toLong() ?: return DEFAULT_LATENCY_MS
        return (micros / 1000).toInt().coerceIn(10, 500)
    }

    const val RTP_HEADER_LEN = 12
    private const val TAG_LEN = 16
    private const val NONCE_LEN = 8

    /**
     * Decrypts one head-unit microphone packet and returns **s16le** PCM (the head unit is big-endian on the wire; Android wants little-endian);
     * returns null on bad length or failed authentication (just drop the packet; later packets are unaffected).
     */
    fun openPacket(
        key: ByteArray,
        packet: ByteArray,
        length: Int = packet.size,
    ): ByteArray? {
        if (length < RTP_HEADER_LEN + TAG_LEN + NONCE_LEN) return null
        // The RTP version bit is not checked: WinPlay's microphone packets use `h.writeUInt16BE(m.seq)` without an offset, so the sequence number is written at bytes 0..1,
        // overwriting 0x80 and the PT (2026-11-17 capture: the first packet's 12 header bytes were all zero). Authenticity is guaranteed by Poly1305,
        // and the AAD only covers bytes 4..12, so it is unaffected.
        val nonce = ByteArray(12)
        System.arraycopy(packet, length - NONCE_LEN, nonce, 4, NONCE_LEN)
        val aad = packet.copyOfRange(4, RTP_HEADER_LEN)
        val sealed = packet.copyOfRange(RTP_HEADER_LEN, length - NONCE_LEN)
        val plain =
            try {
                AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
            } catch (_: Exception) {
                return null
            }
        var i = 0
        while (i + 1 < plain.size) {
            val hi = plain[i]
            plain[i] = plain[i + 1]
            plain[i + 1] = hi
            i += 2
        }
        return plain
    }

    /**
     * Downlink packet (phone -> head unit), same layout as media audio: [RTP header][ciphertext+tag][8B little-endian nonce], PT = 100, SSRC = 0.
     * Must keep sending while the mic stream is up (even silence): receivers such as WinPlay **only open the mic after receiving the first downlink packet of this stream**
     * (their cpStack: "the mic runs while audio flows"); receive-only means the head unit never sends the microphone back.
     */
    fun sealDownlink(
        key: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        bigEndianPcm: ByteArray,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte()
        header[1] = STREAM_TYPE_MAIN_AUDIO.toByte()
        header[2] = (sequence ushr 8).toByte()
        header[3] = sequence.toByte()
        val ts = timestamp.toInt()
        header[4] = (ts ushr 24).toByte()
        header[5] = (ts ushr 16).toByte()
        header[6] = (ts ushr 8).toByte()
        header[7] = ts.toByte()
        val sealed =
            AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(nonceCounter), bigEndianPcm, header.copyOfRange(4, RTP_HEADER_LEN))
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_LEN)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        for (i in 0 until NONCE_LEN) wire[wire.size - NONCE_LEN + i] = (nonceCounter ushr (8 * i)).toByte()
        return wire
    }

    /** Downlink (phone -> head unit) key: label Output. */
    fun outputKey(
        sharedSecret: ByteArray,
        connectionId: Long,
    ): ByteArray =
        AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )

    /** Decryption key for the microphone stream (same derivation as downlink, with label Input). */
    fun inputKey(
        sharedSecret: ByteArray,
        connectionId: Long,
    ): ByteArray =
        AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Input-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )
}
