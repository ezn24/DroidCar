package io.github.ezn24.cardeck.neo.airplay

/**
 * 车机麦克风上行的纯逻辑部分（格式协商 + 封包解密），协议事实见 docs/MIC-RESEARCH.md。
 *
 * 方向：车机 → 手机。手机在 SETUP 里声明 `input=true` + 自己监听的 `dataPort`，
 * 车机把麦克风 RTP 包送到「手机地址:dataPort」。封包与我们送车机的媒体音频同构：
 * 12B 明文 RTP 头 + ChaCha20-Poly1305 密文（AAD = 头[4..12]）+ 16B tag + 8B 小端 nonce 计数；
 * 密钥 = HKDF-SHA512(共享密钥, "DataStream-Salt<connId>", "DataStream-Input-Encryption-Key")。
 */
object CarMic {
    /** 协商结果：AirPlay audioFormat 位 + 采样率（单声道 16-bit PCM）。 */
    data class Format(
        val bit: Long,
        val sampleRate: Int,
        val latencyMs: Int,
    )

    /**
     * 只取 PCM 单声道（Opus 需要另接解码器，先不做）。按 Siri 常用 24k 优先：
     * AirPlay 格式位 bit2/4/6/8/14 = 8k/16k/24k/32k/48k 单声道 16-bit
     * （DiPlay PR #898 实测 0x154 = 8/16/24/32k 单声道、0x4000 = 48k 单声道）。
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
     * 从车机 /info 选麦克风格式：type=100、audioType=speechRecognition 的档位里，
     * **上下行共用同一格式**（车机按一个 audioFormat 建双向流），所以取输入 ∩ 输出；
     * 车机没声明输出位时只看输入。返回 null = 车机没有可用的语音输入通路。
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
     * 解开一个车机麦克风包，返回 **s16le** PCM（车机线上是大端，Android 要小端）；
     * 长度不对或认证失败返回 null（丢包即可，不影响后续包）。
     */
    fun openPacket(
        key: ByteArray,
        packet: ByteArray,
        length: Int = packet.size,
    ): ByteArray? {
        if (length < RTP_HEADER_LEN + TAG_LEN + NONCE_LEN) return null
        // 不校验 RTP 版本位：WinPlay 的麦克风包 `h.writeUInt16BE(m.seq)` 漏了 offset，序号写在字节 0..1、
        // 盖掉了 0x80 与 PT（2026-11-17 抓包：首包头 12 字节全 0）。真伪靠 Poly1305 认证把关，
        // AAD 只覆盖字节 4..12，不受影响。
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
     * 下行封包（手机 → 车机），布局同媒体音频：[RTP 头][密文+tag][8B 小端 nonce]，PT = 100、SSRC = 0。
     * 麦克风流期间要持续发（哪怕是静音）：WinPlay 等接收端**收到这条流的第一个下行包才开麦**
     * （其 cpStack：「the mic runs while audio flows」），只收不发 = 车机永远不回传麦克风。
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

    /** 下行（手机 → 车机）密钥：标签 Output。 */
    fun outputKey(
        sharedSecret: ByteArray,
        connectionId: Long,
    ): ByteArray =
        AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )

    /** 麦克风流的解密密钥（与下行同一推导，标签换成 Input）。 */
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
