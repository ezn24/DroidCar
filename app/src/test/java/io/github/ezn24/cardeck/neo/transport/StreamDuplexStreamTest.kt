package io.github.ezn24.cardeck.neo.transport

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test: while the reader thread is blocked in read(), the writer thread must be able to complete write().
 * The old implementation had read/write share one @Synchronized monitor; while the read blocked it held the lock,
 * so write waited forever -- actually triggered on real-device Bluetooth (the log stopped right after link ready).
 */
class StreamDuplexStreamTest {
    @Test
    fun writeCompletesWhileReaderBlocksInRead() {
        val source = PipedInputStream(1 shl 16)
        val sink = PipedOutputStream()
        sink.connect(source)
        val stream = StreamDuplexStream(source, sink)

        val reader =
            Thread {
                val buffer = ByteArray(1024)
                try {
                    while (stream.read(buffer) >= 0) {
                        // Keep blocking on read, simulating the link channel's reader thread
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }

        Thread.sleep(200) // Make sure the reader thread is already blocked in read()

        val written = CountDownLatch(1)
        Thread({
            stream.write(byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x1D, 0x00))
            written.countDown()
        }, "writer").apply {
            isDaemon = true
            start()
        }

        assertTrue("write deadlocked while the reader thread was blocked", written.await(2, TimeUnit.SECONDS))

        stream.close()
        reader.join(1_000)
    }
}
