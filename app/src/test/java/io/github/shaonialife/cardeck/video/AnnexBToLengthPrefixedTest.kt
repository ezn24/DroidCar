package io.github.shaonialife.cardeck.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 新的一次扫描转换必须与旧实现（拆 NAL → 补丁 SPS → 逐个写长度+数据）逐字节一致。 */
class AnnexBToLengthPrefixedTest {
    /** 旧实现原样搬过来作对照。 */
    private fun reference(bytes: ByteArray): ByteArray? {
        val units = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
        } else {
            for (unit in 0 until starts.size) {
                val start = starts[unit]
                val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
                val bodyStart = start + prefix
                val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
                if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
            }
        }
        if (units.isEmpty()) return null
        val packed = ByteArrayOutputStream()
        for (nal in units) {
            val out = if (nal.isNotEmpty() && (nal[0].toInt() and 0x1F) == 7) patchSpsLevel(nal) else nal
            packed.write((out.size ushr 24) and 0xFF)
            packed.write((out.size ushr 16) and 0xFF)
            packed.write((out.size ushr 8) and 0xFF)
            packed.write(out.size and 0xFF)
            packed.write(out)
        }
        return packed.toByteArray()
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun typicalKeyFrameWithSpsPpsIdr() {
        val frame =
            bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1F, 0xAA) + // SPS level 31 → 40
                bytes(0, 0, 0, 1, 0x68, 0xEE, 0x3C) + // PPS
                bytes(0, 0, 1, 0x65) + ByteArray(300) { (it * 7 + 3).toByte() } // IDR（3 字节起始码）
        assertArrayEquals(reference(frame), annexBToLengthPrefixed(frame))
    }

    @Test
    fun edgeCases() {
        val cases =
            listOf(
                bytes(0x41, 0x9A, 0x01), // 无起始码 → 整块一个 NAL
                bytes(0, 0, 0, 1), // 只有起始码（尾部 3 字节内不认）→ 整块
                bytes(0, 0, 0, 1, 0, 0, 0, 1, 0x41, 0x01), // 空 NAL 被跳过
                bytes(0x09, 0x10, 0, 0, 1, 0x41, 0x02, 0, 0, 1), // 起始码前的杂字节被丢弃、尾部起始码不认
                bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x33), // SPS level 已 ≥40 不改
            )
        for (c in cases) assertArrayEquals(c.joinToString(), reference(c), annexBToLengthPrefixed(c))
    }

    @Test
    fun randomStreamsMatchReference() {
        val rnd = java.util.Random(42)
        repeat(500) {
            val len = rnd.nextInt(200)
            // 偏置随机：大量 0 让起始码频繁出现
            val data =
                ByteArray(len) {
                    if (rnd.nextInt(3) == 0) {
                        0
                    } else if (rnd.nextInt(5) == 0) {
                        1
                    } else {
                        rnd.nextInt(256).toByte()
                    }
                }
            assertArrayEquals(reference(data), annexBToLengthPrefixed(data))
        }
    }

    @Test
    fun emptyInputGivesNull() {
        assertNull(annexBToLengthPrefixed(ByteArray(0)))
    }
}
