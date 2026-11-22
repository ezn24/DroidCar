package io.github.ezn24.droidcar.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Blocking bidirectional byte stream: shared by the Bluetooth RFCOMM socket and the in-memory loopback used in tests. */
interface DuplexStream : Closeable {
    /** Blocking read; returns -1 at end of stream. */
    fun read(buffer: ByteArray): Int

    /** Blocking write of all bytes, or throws. */
    fun write(bytes: ByteArray)

    override fun close()
}

class StreamDuplexStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : DuplexStream {
    /**
     * Note: read/write must not share a lock. The reader blocks inside read() holding it while
     * waiting for data, so the writer would never get the lock (this did happen on real Bluetooth).
     * Write concurrency is already serialized by the Iap2LinkChannel lock (all writes happen under
     * it); reads only come from the single reader thread, and the Bluetooth socket's input and
     */
    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        onClose()
    }
}

/** In-memory loopback stream pair: each end holds one DuplexStream; for unit tests. */
class InMemoryStreamPair {
    private val aToB = Pipe()
    private val bToA = Pipe()

    val sideA: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = aToB.source.read(buffer)

            override fun write(bytes: ByteArray) = bToA.writeAndFlush(bytes)

            override fun close() {
                aToB.close()
                bToA.closePartner()
            }
        }

    val sideB: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = bToA.source.read(buffer)

            override fun write(bytes: ByteArray) = aToB.writeAndFlush(bytes)

            override fun close() {
                bToA.close()
                aToB.closePartner()
            }
        }

    private class Pipe {
        val source = PipedInputStream(1 shl 16)
        val sink = PipedOutputStream()

        init {
            sink.connect(source)
        }

        fun writeAndFlush(bytes: ByteArray) {
            sink.write(bytes)
            sink.flush()
        }

        fun close() {
            runCatching { sink.close() }
            runCatching { source.close() }
        }

        /** Closes the peer-facing end so the other side reads EOF. */
        fun closePartner() {
            runCatching { source.close() }
        }
    }
}

class DuplexClosedException(
    message: String,
) : IOException(message)
