package io.github.ezn24.cardeck.neo.video

import java.io.ByteArrayOutputStream

/**
 * VideoConfig body of the HEVC (H.265) screen stream: `[u32 BE box length]["hvcC"][HEVCDecoderConfigurationRecord]`.
 *
 * Protocol facts (checked against the CarPlayStation receiver, GPL, read-only, no code copied): the receiver **searches the
 * VideoConfig body for the four-character "hvcC" marker** to locate the record and checks the 0x08 bit at frame-header
 * offset 6 to detect HEVC; with no marker it parses as avcC (which necessarily fails). The H.264 path still sends a
 * bare avcC, matching the form verified on real cars.
 * The HEVC form on real (next-generation CarPlay) cars is unverified.
 */
internal object HevcConfig {
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    /** HEVC NAL type: bits 1..6 of the first NAL header byte. */
    fun nalType(firstByte: Byte): Int = (firstByte.toInt() ushr 1) and 0x3F

    /** Strips emulation prevention bytes (00 00 03 -> 00 00), for reading fixed-length fields in the SPS. */
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
     * Assembles an hvcC record from VPS/SPS/PPS (without start codes). profile/tier/level come from the SPS
     * profile_tier_level (the 12 bytes after the 2-byte NAL header + 1 byte of vps_id/sub-layer count).
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
        out.write(ptl, 0, 12) // profile_space/tier/profile_idc, compatibility flags 4B, constraint flags 6B, level_idc
        out.write(0xF0) // reserved 1111 + min_spatial_segmentation_idc = 0
        out.write(0x00)
        out.write(0xFC) // reserved + parallelismType = 0
        out.write(0xFD) // reserved + chroma_format_idc = 1 (4:2:0)
        out.write(0xF8) // reserved + bitDepthLumaMinus8 = 0
        out.write(0xF8) // reserved + bitDepthChromaMinus8 = 0
        out.write(0x00) // avgFrameRate = 0 (unspecified)
        out.write(0x00)
        // constantFrameRate=0 | numTemporalLayers | temporalIdNested | lengthSizeMinusOne=3 (4-byte length prefix)
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

    /** VideoConfig body: `[u32 BE box length]["hvcC"][record]`. */
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
