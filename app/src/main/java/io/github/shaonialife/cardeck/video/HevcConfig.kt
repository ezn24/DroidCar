package io.github.shaonialife.cardeck.video

import java.io.ByteArrayOutputStream

/**
 * HEVC（H.265）屏幕流的 VideoConfig 体：`[u32 BE 盒长]["hvcC"][HEVCDecoderConfigurationRecord]`。
 *
 * 协议事实（CarPlayStation 接收端对照，GPL 只读，未抄代码）：接收端在 VideoConfig 体里**搜索
 * "hvcC" 四字符标记**定位记录，并看帧头偏移 6 的 0x08 位判断 HEVC；找不到标记就按 avcC 解析
 * （必然失败）。H.264 路径仍发裸 avcC，与真车已验证的形态一致。
 * 真车（下一代 CarPlay）上的 HEVC 形态未验证。
 */
internal object HevcConfig {
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    /** HEVC NAL 类型：NAL 头第一个字节的 bit1..6。 */
    fun nalType(firstByte: Byte): Int = (firstByte.toInt() ushr 1) and 0x3F

    /** 去掉 emulation prevention 字节（00 00 03 → 00 00），用于读 SPS 里的定长字段。 */
    fun unescape(
        bytes: ByteArray,
        from: Int,
        maxOut: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(maxOut)
        var zeros = 0
        var index = from
        while (index < bytes.size && out.size() < maxOut) {
            val value = bytes[index].toInt() and 0xFF
            if (zeros >= 2 && value == 3) {
                zeros = 0
                index++
                continue
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
            index++
        }
        return out.toByteArray()
    }

    /**
     * 由 VPS/SPS/PPS（不含起始码）组装 hvcC 记录。profile/tier/level 取自 SPS 的
     * profile_tier_level（NAL 头 2 字节 + 1 字节 vps_id/子层数之后的 12 字节）。
     */
    fun record(
        vps: ByteArray,
        sps: ByteArray,
        pps: ByteArray,
    ): ByteArray? {
        val ptl = unescape(sps, 3, 12)
        if (ptl.size < 12) return null
        val maxSubLayersMinus1 = (sps[2].toInt() ushr 1) and 0x07
        val temporalIdNested = sps[2].toInt() and 0x01
        val out = ByteArrayOutputStream(23 + vps.size + sps.size + pps.size + 15)
        out.write(1) // configurationVersion
        out.write(ptl, 0, 12) // profile_space/tier/profile_idc、兼容标志 4B、约束标志 6B、level_idc
        out.write(0xF0) // reserved 1111 + min_spatial_segmentation_idc = 0
        out.write(0x00)
        out.write(0xFC) // reserved + parallelismType = 0
        out.write(0xFD) // reserved + chroma_format_idc = 1（4:2:0）
        out.write(0xF8) // reserved + bitDepthLumaMinus8 = 0
        out.write(0xF8) // reserved + bitDepthChromaMinus8 = 0
        out.write(0x00) // avgFrameRate = 0（未指定）
        out.write(0x00)
        // constantFrameRate=0 | numTemporalLayers | temporalIdNested | lengthSizeMinusOne=3（4 字节长度前缀）
        out.write((((maxSubLayersMinus1 + 1) and 0x07) shl 3) or (temporalIdNested shl 2) or 0x03)
        out.write(3) // numOfArrays
        for ((type, nal) in listOf(NAL_VPS to vps, NAL_SPS to sps, NAL_PPS to pps)) {
            out.write(0x80 or type) // array_completeness=1
            out.write(0x00)
            out.write(0x01) // numNalus = 1
            out.write((nal.size ushr 8) and 0xFF)
            out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** VideoConfig 体：`[u32 BE 盒长]["hvcC"][记录]`。 */
    fun configBody(record: ByteArray): ByteArray {
        val size = 8 + record.size
        val out = ByteArrayOutputStream(size)
        out.write((size ushr 24) and 0xFF)
        out.write((size ushr 16) and 0xFF)
        out.write((size ushr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write("hvcC".toByteArray(Charsets.US_ASCII))
        out.write(record)
        return out.toByteArray()
    }
}
