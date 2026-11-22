package io.github.ezn24.droidcar.video

import io.github.ezn24.droidcar.airplay.BinaryPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Screen-stream keepAlive frame format regression.
 *
 * 2026-10-19 Audi MMI 3315 measured: with only UDP keepAlive + /feedback, the whole session is reset about
 * 10.2s after RECORD (video/event channels both healthy). catplay **still writes one keepAlive frame every second** to the screen data port
 * even while video is streaming continuously -- it is an independent liveness signal on the receiver's screen.
 * With a wrongly formatted frame the receiver silently ignores it and the symptom is still "disconnect after 10 seconds", so a unit test must lock it down.
 */
class VideoKeepAliveFrameTest {
    private fun header(frame: ByteArray) = frame.copyOfRange(0, 128)

    private fun bodySize(header: ByteArray): Int =
        ByteBuffer
            .wrap(header, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    @Test
    fun emptyKeepAliveIsOpcode2HeaderOnly() {
        val frame = VideoSender.buildKeepAliveFrame(withStats = false, frames = 30, bytesPerSecond = 1.0, framesPerSecond = 30)
        assertEquals(128, frame.size)
        assertEquals(0, bodySize(frame))
        assertEquals(2, frame[4].toInt() and 0xFF)
    }

    @Test
    fun statsKeepAliveIsOpcode5WithBplistBody() {
        val frame =
            VideoSender.buildKeepAliveFrame(
                withStats = true,
                frames = 30,
                bytesPerSecond = 900_000.0,
                framesPerSecond = 30,
            )
        val head = header(frame)
        assertEquals(5, head[4].toInt() and 0xFF)
        val size = bodySize(head)
        assertEquals(frame.size - 128, size)

        val body = frame.copyOfRange(128, frame.size)
        val stats = BinaryPlist.parse(body) as Map<*, *>
        assertEquals(30L, stats["encoderCurrentFPS"])
        assertEquals(30L, stats["sentFramesAvg"])
        assertEquals(900_000.0, stats["txUsageAvg"])
    }

    @Test
    fun statsBodyLengthIsMirroredIntoParams14AsLittleEndianFloat() {
        val frame = VideoSender.buildKeepAliveFrame(withStats = true, frames = 30, bytesPerSecond = 1.0, framesPerSecond = 30)
        val expected = (frame.size - 128).toFloat()
        // params[14] sits at offset 120..128; the last 4 bytes are the f32 LE body length (same as catplay)
        val declared = ByteBuffer.wrap(frame, 124, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(expected, declared, 0.0f)
        // The first 4 bytes are fixed 0.0f
        assertEquals(0.0f, ByteBuffer.wrap(frame, 120, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
    }

    @Test
    fun keepAliveHeaderCarriesNoChaChaTag() {
        // keepAlive is a plaintext frame: the body length must equal the actual body (excluding the 16B tag),
        // otherwise the receiver treats bytes after the stats body as the next frame header, and the frame boundaries are all misaligned.
        val frame = VideoSender.buildKeepAliveFrame(withStats = true, frames = 1, bytesPerSecond = 0.0, framesPerSecond = 1)
        val size = bodySize(header(frame))
        assertTrue("body=$size", size > 0)
        assertEquals(128 + size, frame.size)
    }

    /**
     * The VideoConfig frame header must match catplay `ScreenFrame::config`.
     *
     * Audi 4851 (AUDI 5163 / DELPHI, 0.1.4 log) measured: pairing, SETUP, RECORD, streaming,
     * touch all normal, **head unit screen entirely black**. catplay writes two fields in the config header that we missed:
     *  - `small_param[1] |= ScreenFlag::Encrypted (0x04)` -- declares video frames are sealed;
     *  - `params[1]` = (width, height) two f32 LE -- the head unit builds the decoder canvas from them.
     * DiPlay/WinPlay only read the body and do not validate the header, so the test bench never exposed it.
     */
    @Test
    fun videoConfigHeaderDeclaresEncryptionAndCanvasSize() {
        val header = VideoSender.buildVideoConfigHeader(bodySize = 35, width = 1412, height = 654)

        assertEquals(35, bodySize(header))
        assertEquals(1, header[4].toInt() and 0xFF) // opcode = VideoConfig
        // small_param[1] is at header offset 6; Encrypted = 1<<2
        assertEquals(0x04, header[6].toInt() and 0x04)

        // params[0] (offset 8..16) is the PTS slot, config must leave it 0
        for (index in 8 until 16) {
            assertEquals("params[0] should be 0 (offset $index)", 0, header[index].toInt())
        }

        // params[1] (offset 16..24) = width f32 LE, height f32 LE
        val width = ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).float
        val height = ByteBuffer.wrap(header, 20, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(1412.0f, width, 0.0f)
        assertEquals(654.0f, height, 0.0f)
    }

    @Test
    fun videoConfigHeaderUsesEncodedEvenDimensions() {
        // The encoder size is already aligned down to even at construction; the header must report the same value (the head unit builds the canvas from it)
        val header = VideoSender.buildVideoConfigHeader(bodySize = 20, width = 1260, height = 654)
        assertEquals(1260.0f, ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
        assertEquals(654.0f, ByteBuffer.wrap(header, 20, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
    }
}
