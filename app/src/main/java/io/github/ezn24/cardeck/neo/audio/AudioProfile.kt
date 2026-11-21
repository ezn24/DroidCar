package io.github.ezn24.cardeck.neo.audio

/** How the audio payload is encoded: decides how captured samples are packed into RTP. */
enum class AudioCodec {
    /** Raw PCM (big-endian s16), type=100 path. */
    PCM,

    /** Raw AAC-LC frames (no ADTS/AU header), type=102 path. */
    AAC_LC,
}

/**
 * One selectable audio profile: stream type + `audioType` + format bit + sample rate + latency + encoding.
 *
 * Why profiles exist (2026-10-20, Honda HFT / Alpine 8A250-30A):
 * the head unit's `/info` `audioFormats` declares a **separate bitmap per (type, audioType)**,
 * and the real "media audio path" sits on different entries on different cars:
 *  - Audi etc.: `type=100 media` + PCM 48k/44.1k plays (verified 2026-10-17);
 *  - this Honda: `type=100 media` only declares PCM 44.1k. SETUP returns 200 with a dataPort
 *    but `controlPort=0` and only 3 reply fields; `/feedback` shows that stream's `sampleTime`
 *    stuck at 0 (not one sample consumed) and ~6.5s later the car tears the whole session down.
 *    Its real media path is **`type=102` (MainHighAudio) + `media` + AAC-LC 44.1k**
 *    (`0x400000`, bit22), which is what a real iPhone uses.
 *
 * So candidates must span stream types and encodings: when one fails, try the next and persist
 * the verdict per head unit ([AudioProfileMemory]).
 */
data class AudioProfile(
    /** Raw audioType value (media / compatibility / default ...), echoed back unchanged in SETUP. */
    val audioType: String,
    /** Audio format bit (catplay `AudioFormat`: PCM48k=1<<15, PCM44.1k=1<<11, AAC-LC44.1k=1<<22). */
    val formatBit: Long,
    /** Capture sample rate matching [formatBit]; `AudioSender` must capture at the same value. */
    val sampleRate: Int,
    /** SETUP audioLatencyMs. */
    val latencyMs: Long,
    /** RTP stream type: 100=MainAudio (PCM path), 102=MainHighAudio (AAC-LC path). */
    val streamType: Long = 100L,
    /** Payload encoding. */
    val codec: AudioCodec = AudioCodec.PCM,
) {
    /** Key for persisted memory (includes stream type and encoding: the same audioType/format differs entirely per path). */
    val key: String get() = "$streamType|${audioType.lowercase()}|$formatBit|${codec.name}"

    /** Short label for logs. */
    val label: String
        get() = "t$streamType/$audioType/0x${formatBit.toString(16)}(${sampleRate}Hz,${codec.name},$latencyMs ms)"
}
