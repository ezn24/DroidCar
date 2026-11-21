package io.github.ezn24.cardeck.neo.bridge

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Block-delta patch application (a hard binary-format contract with the server's deltadiff.ts; never change it on its own):
 *
 *   "CDDELTA1"  8B magic
 *   targetLen   u64 LE
 *   targetSha256 32B
 *   baseLen     u64 LE
 *   baseSha256  32B
 *   ops: 0x01 COPY(u64 oldOffset,u64 len) | 0x02 LITERAL(u64 len + bytes)
 *
 * Safety does not rely on the patch being trusted: the output sha256 is verified after applying, and a mismatch voids it and falls
 * back to the full package. The base is always this process's own sourceDir (the only source that is readable and
 * byte-identical to the server baseline).
 */
object DeltaPatch {
    private const val MAGIC = "CDDELTA1"

    /**
     * Apply [patch] onto [base] (must be byte-identical to the server delta baseline) and write [out].
     * Returns null = success; otherwise the failure reason (the caller falls back to the full download).
     */
    fun apply(
        patch: File,
        base: File,
        out: File,
    ): String? {
        if (!base.isFile) return "base APK does not exist"
        val sha = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(patch, "r").use { pf ->
            val magic = ByteArray(8)
            pf.readFully(magic)
            if (!String(magic, Charsets.US_ASCII).startsWith(MAGIC)) return "bad patch format"
            val targetLen = pf.readLong()
            val targetSha = ByteArray(32).also { pf.readFully(it) }
            val baseLen = pf.readLong()
            val baseSha = ByteArray(32).also { pf.readFully(it) }
            if (base.length() != baseLen) return "base length mismatch (${base.length()}/$baseLen)"
            if (!sha256Of(base).contentEquals(baseSha)) return "base sha256 mismatch"
            RandomAccessFile(base, "r").use { bf ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        when (pf.readByte().toInt()) {
                            0x01 -> {
                                // COPY: seek in the base and copy
                                val off = pf.readLong()
                                var len = pf.readLong()
                                bf.seek(off)
                                while (len > 0) {
                                    val n = bf.read(buf, 0, minOf(buf.size.toLong(), len).toInt())
                                    if (n < 0) return "base read out of range"
                                    output.write(buf, 0, n)
                                    sha.update(buf, 0, n)
                                    written += n
                                    len -= n
                                }
                            }

                            0x02 -> {
                                // LITERAL: bytes inlined in the patch
                                var len = pf.readLong()
                                while (len > 0) {
                                    val n = pf.read(buf, 0, minOf(buf.size.toLong(), len).toInt())
                                    if (n < 0) return "patch read out of range"
                                    output.write(buf, 0, n)
                                    sha.update(buf, 0, n)
                                    written += n
                                    len -= n
                                }
                            }

                            else -> return "unknown opcode"
                        }
                        if (written > targetLen) return "output too long"
                        if (written == targetLen) break
                    }
                    if (!sha.digest().contentEquals(targetSha)) return "output sha256 mismatch (patch and base may not belong together)"
                    return null
                }
            }
        }
    }

    internal fun sha256Of(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }
}
