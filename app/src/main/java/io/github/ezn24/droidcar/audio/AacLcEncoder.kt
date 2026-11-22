package io.github.ezn24.droidcar.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * PCM -> AAC-LC encoder (used by the type=102 MainHighAudio path).
 *
 * Why it exists (2026-10-20, Honda HFT / Alpine): the head unit's `/info` lists
 * `type=102` (MainHighAudio) `media` with only `0x400000` = **AAC-LC 44.1k stereo**,
 * no PCM bit; `type=100 media` only offers PCM 44.1k, and pushing PCM there is
 * received but never consumed (`/feedback` `sampleTime` stays 0), so the car tears
 * the session down after ~6.5s. A real iPhone's media audio uses 102 + AAC-LC.
 *
 * Wire format (per catplay `aac_encoder.rs` + `rtp_recorder.rs`): **raw AAC frames**,
 * no ADTS header and no RFC3640 AU header; the RTP payload is the frame itself,
 * one frame per packet. Android's `MediaCodec` AAC encoder also emits raw frames.
 *
 * Frame length is fixed at 1024 samples (AAC-LC), so the RTP timestamp steps by 1024.
 */
class AacLcEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitRate: Int = 192_000,
    /** Whether to prepend a 7-byte ADTS header to every frame (see [AudioTuning.ADTS]). */
    private val wrapAdts: Boolean = false,
    private val log: (String) -> Unit,
) {
    companion object {
        /** AAC-LC frames are always 1024 samples per channel. */
        const val FRAME_SAMPLES = 1024

        /** Bytes of PCM per frame (16-bit). */
        fun pcmBytes(channels: Int): Int = FRAME_SAMPLES * channels * 2

        /** ADTS header length (no CRC). */
        private const val ADTS_HEADER_SIZE = 7

        /** audioObjectType in ADTS: AAC-LC = 2 (stored as AOT-1). */
        private const val AAC_PROFILE_LC = 2

        private const val MIME = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L

        /**
         * Builds a 7-byte ADTS header (no CRC): MPEG-4 / AAC-LC / matching rate and channels.
         *
         * On the wire (RTP payload) Apple uses **raw AAC frames**, but some head units
         * decode through an ADTS pipeline (Android `audio/aac-adts`) and reject raw frames,
         * so a switchable wrapping path is kept ([AudioTuning.ADTS]).
         */
        fun adtsHeader(
            payloadSize: Int,
            sampleRate: Int,
            channels: Int,
        ): ByteArray {
            val frameLength = payloadSize + ADTS_HEADER_SIZE
            val freqIndex = frequencyIndex(sampleRate)
            val header = ByteArray(ADTS_HEADER_SIZE)
            header[0] = 0xFF.toByte()
            // remaining 4 syncword bits + MPEG-4(0) + layer(00) + protection_absent(1)
            header[1] = 0xF1.toByte()
            // profile=LC(2) stored as AOT-1; sample-rate index <<2; high 2 bits of channel config
            header[2] =
                (
                    ((AAC_PROFILE_LC - 1) shl 6) or
                        ((freqIndex and 0x0F) shl 2) or
                        ((channels shr 2) and 0x03)
                ).toByte()
            // low bit of channel config + high 2 bits of frame length
            header[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
            header[4] = ((frameLength shr 3) and 0xFF).toByte()
            // low 3 bits of frame length + buffer_fullness=0x7FF (VBR convention) + single frame (low 2 bits 0)
            header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            return header
        }

        /** Sample rate -> ADTS samplingFrequencyIndex. */
        fun frequencyIndex(rate: Int): Int =
            when (rate) {
                96_000 -> 0
                88_200 -> 1
                64_000 -> 2
                48_000 -> 3
                44_100 -> 4
                32_000 -> 5
                24_000 -> 6
                22_050 -> 7
                16_000 -> 8
                12_000 -> 9
                11_025 -> 10
                8_000 -> 11
                7_350 -> 12
                else -> 4
            }

        /**
         * Self-check: decode one raw AAC frame back to PCM with the platform decoder and
         * return `(peak, sampleCount)`, or null if it cannot be decoded.
         * Why (2026-10-20, third field test): when the car receives packets but stays silent,
         * the easy mistake is blaming the wrong side - bad/silent encoder stream or the car
         * not playing. Decoding locally settles it: peak > 0 means the bitstream is valid and
         * non-silent so the fault is on the car; peak = 0 means the encoder emits silence.
         * Never byte-swap the frame here: the old code swapped every payload as big-endian s16,
         * AAC bitstreams included, and the car decoder rejected them as corrupt
         * (the "packets but no sound" root cause).
         */
        fun decodePeak(
            frame: ByteArray,
            sampleRate: Int,
            channels: Int,
        ): Pair<Int, Int>? {
            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels)
                val codec = MediaCodec.createDecoderByType(MIME)
                created = codec
                codec.configure(format, null, null, 0)
                codec.start()
                val inputIndex = codec.dequeueInputBuffer(200_000L)
                if (inputIndex < 0) return null
                val input = codec.getInputBuffer(inputIndex) ?: return null
                input.clear()
                input.put(frame)
                codec.queueInputBuffer(inputIndex, 0, frame.size, 0L, 0)
                val info = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + 600_000_000L
                while (System.nanoTime() < deadline) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 60_000L)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                    ) {
                        continue
                    }
                    if (outputIndex < 0) continue
                    val output = codec.getOutputBuffer(outputIndex)
                    var peak = 0
                    var samples = 0
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        while (output.remaining() >= 2) {
                            val value = output.short.toInt()
                            val magnitude = if (value < 0) -value else value
                            if (magnitude > peak) peak = magnitude
                            samples++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (samples > 0) return peak to samples
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { created?.stop() }
                runCatching { created?.release() }
            }
        }
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    /** The ADTS-strip notice is logged only once to avoid flooding. */
    private var adtsStripped = false

    /** The ADTS-wrapping notice is logged only once. */
    private var adtsWrapped = false

    /** First-frame hex dump is logged only once. */
    private var firstFrameLogged = false

    /** PCM input bytes per frame. */
    val inputFrameBytes: Int = pcmBytes(channels)

    var started: Boolean = false
        private set

    fun start(): Boolean {
        if (started) return true
        return try {
            val format =
                MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputFrameBytes)
                }
            val created = MediaCodec.createEncoderByType(MIME)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            codec = created
            presentationUs = 0L
            started = true
            log(
                "AUDIO: AAC-LC encoder ready (${sampleRate}Hz ${channels}ch " +
                    "${bitRate / 1000}kbps, frame=${FRAME_SAMPLES} samples/${inputFrameBytes}B)",
            )
            true
        } catch (error: Exception) {
            log("AUDIO: AAC-LC encoder failed to start - ${error.message} (giving up this profile)")
            started = false
            false
        }
    }

    /**
     * Feeds one PCM frame ([inputFrameBytes] bytes) and returns all AAC frames produced.
     *
     * The encoder has internal latency (the first frame usually needs 1~2 inputs before
     * output), so the result may be empty; callers just send each returned frame.
     */
    fun encode(
        pcm: ByteArray,
        size: Int,
    ): List<ByteArray> {
        val encoder = codec ?: return emptyList()
        val output = mutableListOf<ByteArray>()
        try {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = encoder.getInputBuffer(inputIndex)
                if (buffer == null) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, presentationUs, 0)
                } else {
                    buffer.clear()
                    buffer.put(pcm, 0, size.coerceAtMost(buffer.capacity()))
                    encoder.queueInputBuffer(inputIndex, 0, size, presentationUs, 0)
                    presentationUs += FRAME_SAMPLES * 1_000_000L / sampleRate
                }
            }
            drain(encoder, output)
        } catch (error: Exception) {
            log("AUDIO: AAC encode error - ${error.message}")
        }
        return output
    }

    private fun drain(
        encoder: MediaCodec,
        output: MutableList<ByteArray>,
    ) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index < 0 -> return
                else -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig) {
                        val data = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(data)
                        output += finalizeFrame(data)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /**
     * Strips the ADTS header, if present.
     *
     * The wire format requires **raw AAC frames** (catplay `rtp_recorder` uses encoder output
     * as payload directly). Most MediaCodec implementations emit raw frames, but some hardware
     * encoders prepend a 7/9-byte ADTS header, which would be an invalid payload the car
     * drops the whole stream for. Detect it by syncword and strip it (defensive).
     */
    private fun stripAdts(frame: ByteArray): ByteArray {
        if (frame.size < 7) return frame
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return frame
        // ADTS protection_absent is the lowest bit of byte 1: 1 = no CRC (7-byte header), 0 = 9 bytes
        val headerSize = if ((b1 and 0x01) == 0x01) 7 else 9
        if (frame.size <= headerSize) return frame
        if (!adtsStripped) {
            adtsStripped = true
            log("AUDIO: encoder output carries an ADTS header (${headerSize}B) - stripped, sending raw AAC frames")
        }
        return frame.copyOfRange(headerSize, frame.size)
    }

    /**
     * Chooses the wire payload shape: raw frame (default) or "ADTS header + raw frame".
     *
     * Always strip any ADTS the encoder added first (avoids double headers), then rebuild if configured.
     */
    private fun finalizeFrame(frame: ByteArray): ByteArray {
        val raw = stripAdts(frame)
        if (!adtsWrapped && wrapAdts) {
            adtsWrapped = true
            log("AUDIO: switched to ADTS wrapping (7-byte header per frame, scheme 2)")
        }
        val out = if (wrapAdts) buildAdts(raw) else raw
        // Keep the first frame on record so a bad payload can be checked from the log instead of asking the user for a file
        if (!firstFrameLogged) {
            firstFrameLogged = true
            val head = out.take(16).joinToString(" ") { "%02X".format(it) }
            log(
                "AUDIO: first frame ${out.size}B (raw frame ${raw.size}B) head=$head" +
                    (if (wrapAdts) " (ADTS, sync=FFF)" else ""),
            )
        }
        return out
    }

    /** Builds a 7-byte ADTS header (no CRC): MPEG-4 / LC / matching rate and channels. */
    private fun buildAdts(raw: ByteArray): ByteArray = adtsHeader(raw.size, sampleRate, channels) + raw

    fun stop() {
        val encoder = codec ?: return
        codec = null
        started = false
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
    }
}
