package io.github.ezn24.cardeck.neo.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** hvcC 组装：profile_tier_level 去 emulation prevention 后原样搬进记录，三组参数集按 VPS/SPS/PPS。 */
class HevcConfigTest {
    private fun hex(text: String) = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    // 典型 Main profile、level 3.1 的参数集（SPS 的兼容/约束标志里含 00 00 03 防竞争字节）
    private val vps = hex("40 01 0c 01 ff ff 01 60 00 00 03 00 90 00 00 03 00 00 03 00 5d 95 98 09")
    private val sps = hex("42 01 01 01 60 00 00 03 00 90 00 00 03 00 00 03 00 5d a0 02 80 80 2d 16 59 59 a4 93 2b c0 5a 70 20")
    private val pps = hex("44 01 c1 72 b4 62 40")

    @Test
    fun nalTypesAreDecoded() {
        assertEquals(HevcConfig.NAL_VPS, HevcConfig.nalType(vps[0]))
        assertEquals(HevcConfig.NAL_SPS, HevcConfig.nalType(sps[0]))
        assertEquals(HevcConfig.NAL_PPS, HevcConfig.nalType(pps[0]))
    }

    @Test
    fun recordCarriesProfileTierLevelWithoutEmulationBytes() {
        val record = HevcConfig.record(vps, sps, pps)
        assertNotNull(record)
        record!!
        assertEquals(1, record[0].toInt())
        assertEquals(0x01, record[1].toInt()) // profile_space 0、tier 0、Main
        assertArrayEquals(hex("60 00 00 00"), record.copyOfRange(2, 6))
        assertArrayEquals(hex("90 00 00 00 00 00"), record.copyOfRange(6, 12))
        assertEquals(0x5d, record[12].toInt() and 0xFF)
        assertEquals(0x0F, record[21].toInt()) // 1 个时间层、嵌套、4 字节长度前缀
        assertEquals(3, record[22].toInt())
        assertEquals(0x80 or HevcConfig.NAL_VPS, record[23].toInt() and 0xFF)
        assertEquals(23 + 3 * 5 + vps.size + sps.size + pps.size, record.size)
    }

    @Test
    fun configBodyIsTaggedHvcCBox() {
        val record = HevcConfig.record(vps, sps, pps)!!
        val body = HevcConfig.configBody(record)
        assertEquals(
            body.size,
            ((body[0].toInt() and 0xFF) shl 24) or ((body[1].toInt() and 0xFF) shl 16) or ((body[2].toInt() and 0xFF) shl 8) or
                (body[3].toInt() and 0xFF),
        )
        assertEquals("hvcC", String(body, 4, 4, Charsets.US_ASCII))
        assertArrayEquals(record, body.copyOfRange(8, body.size))
    }
}
