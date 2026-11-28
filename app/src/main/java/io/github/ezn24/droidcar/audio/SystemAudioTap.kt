package io.github.ezn24.droidcar.audio

/**
 * System output PCM through the Shizuku service (REMOTE_SUBMIX, never the microphone) for the non-CarPlay protocols.
 * Delivers 16-bit little-endian interleaved frames of [frameMs]; all-zero frames are skipped so a receiver's media
 * stream can idle while nothing plays.
 */
class SystemAudioTap(
    private val sampleRate: Int,
    private val channels: Int,
    private val log: (String) -> Unit,
    private val frameMs: Int = 20,
    private val onPcm: (ByteArray) -> Unit,
) {
    @Volatile private var thread: Thread? = null

    @Volatile private var running = false

    fun start(): Boolean {
        val service =
            io.github.ezn24.droidcar.shizuku.CarInputClient.service ?: run {
                log("AUDIO: no Shizuku service, music stays on the phone")
                return false
            }
        val pipe =
            runCatching { service.startAudioCapture(sampleRate, channels) }
                .onFailure { log("AUDIO: system audio capture unavailable: ${it.message}") }
                .getOrNull() ?: return false
        running = true
        thread =
            Thread({
                val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)
                val frame = ByteArray(sampleRate * frameMs / 1000 * channels * 2)
                var sent = 0L
                try {
                    while (running) {
                        if (readPcmFrame(input, frame) < 0) break
                        if (frame.any { it.toInt() != 0 }) {
                            onPcm(frame.copyOf())
                            if (++sent == 1L || sent % 3000 == 0L) log("AUDIO: system audio frames sent: $sent")
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { input.close() }
                }
            }, "system-audio-tap").apply {
                isDaemon = true
                start()
            }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching {
            io.github.ezn24.droidcar.shizuku.CarInputClient.service
                ?.stopAudioCapture()
        }
        thread?.interrupt()
        thread = null
    }
}
