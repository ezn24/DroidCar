package io.github.ezn24.cardeck.neo.audio

import java.io.InputStream

/** The pipe may short-read on each read; assemble complete PCM frames instead of dropping a short read as a whole packet. */
internal fun readPcmFrame(
    input: InputStream,
    frame: ByteArray,
): Int {
    var offset = 0
    while (offset < frame.size) {
        val count = input.read(frame, offset, frame.size - offset)
        if (count <= 0) return -1
        offset += count
    }
    return offset
}
