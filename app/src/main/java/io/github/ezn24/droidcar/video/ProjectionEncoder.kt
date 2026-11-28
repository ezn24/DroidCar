package io.github.ezn24.droidcar.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 Surface encoder for the non-CarPlay protocols (CarLife, ICCOA). Their head-unit decoders are built for the
 * vendors' phone apps: Baseline profile, Annex B start codes, SPS/PPS as codec config, a key frame every second
 * (FT uses the same shape). [constantBitrate] asks for CBR when the encoder has it (ICCOA's RTP pacing).
 */
class ProjectionEncoder(
    private val width: Int,
    private val height: Int,
    @Volatile private var fps: Int,
    private val bitrate: Int,
    private val log: (String) -> Unit,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (ByteArray, key: Boolean, ptsUs: Long) -> Unit,
    private val constantBitrate: Boolean = false,
) {
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)

    fun start(): Surface? {
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                if (constantBitrate) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // Static pictures produce no frames; repeat the last one so the car keeps decoding (heartbeats cover the rest)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 5)
            }
        val c =
            runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrElse {
                log("PROJECTION: no H.264 encoder: ${it.message}")
                return null
            }
        val surface =
            try {
                runCatching { c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }.getOrElse {
                    // Some encoders reject an explicit profile: retry with the default (usually Baseline-compatible High)
                    log("PROJECTION: encoder rejected Baseline (${it.message}), retrying without a profile")
                    format.removeKey(MediaFormat.KEY_PROFILE)
                    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                c.createInputSurface().also { c.start() }
            } catch (t: Throwable) {
                log("PROJECTION: encoder start failed: ${t.message}")
                runCatching { c.release() }
                return null
            }
        codec = c
        running.set(true)
        log("PROJECTION: encoder ${width}x$height@$fps ${bitrate / 1000} kbps (${c.name})")
        Thread({ drain(c) }, "carlife-encoder").apply {
            isDaemon = true
            start()
        }
        return surface
    }

    fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setFrameRate(next: Int) {
        fps = next
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaFormat.KEY_FRAME_RATE, next) }) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index = c.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                val buf = c.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onConfig(bytes)
                    } else {
                        onFrame(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs)
                    }
                }
                c.releaseOutputBuffer(index, false)
            }
        } catch (t: Throwable) {
            if (running.get()) log("PROJECTION: encoder drain ended: ${t.message}")
        }
    }

    companion object {
        /** Baidu's CarLife phone-app bitrates per stream width; head-unit decoders are tuned for them. */
        fun carLifeBitrateFor(streamWidth: Int): Int =
            when {
                streamWidth <= 768 -> 1_150_000
                streamWidth <= 848 -> 1_280_000
                streamWidth <= 1024 -> 1_920_000
                streamWidth <= 1280 -> 2_560_000
                else -> 3_840_000
            }
    }
}
