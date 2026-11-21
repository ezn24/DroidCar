package io.github.ezn24.cardeck.neo.input

/**
 * Touch HID report parsing.
 *
 * Two report formats, **distinguished by the device's HID descriptor** (not hard-coded per head-unit model or uuid):
 *  - **Multi-slot format** (DiPlay test bench): 12 bytes = 2 slots x 6 bytes
 *    [slot number][down 0/1][X u16LE][Y u16LE]; the descriptor is a Digitizer (0x0D) page, each finger with
 *    a Tip Switch + X/Y 16-bit fields.
 *  - **Single-finger absolute coordinates** (Audi MIB3 eso_touchScreen): 5 bytes
 *    [tip 1bit + 7 bits padding][X u16LE][Y u16LE]; the descriptor is also a Digitizer page but with a different field layout.
 *
 * Decision order: first the descriptor's X/Y Report Size (16 bit) and whether there is only one finger Collection;
 * when the descriptor is unavailable fall back to the report-length criterion (5B -> single finger, 12B+ -> multi-slot).
 */
object TouchReports {
    const val TOUCH_UUID_HEX = "2a2a2a2a"
    const val KNOB_UUID_HEX = "2a2a2a2b"
    private const val SLOTS = 2
    private const val BYTES_PER_FINGER = 6

    data class Contact(
        val slot: Int,
        val xPixels: Int,
        val yPixels: Int,
        val down: Boolean,
    )

    /** Touch report format (decided from the descriptor or the report length). */
    enum class Format { MULTI_SLOT, SINGLE_ABSOLUTE }

    /**
     * Decides the touch format from the descriptor; returns null for non-Digitizer pages (non-touch devices).
     *
     * Basis: the Digitizer page (0x0D) is present with 16-bit X/Y inputs -> touch device;
     * a single-finger Collection (an Application with only one 0x22=Contact Identifier in the descriptor)
     * and a 5-byte report layout -> SINGLE_ABSOLUTE.
     */
    fun formatFromDescriptor(descriptor: ByteArray): Format? {
        var page = 0
        var sawDigitizer = false
        var has16BitXy = false
        // Both single-finger and multi-slot have a Finger Collection with Contact ID(0x22); "X's ReportSize=16"
        // is the identification point common to both formats, and the descriptor length and bytes-per-finger differences tell them apart.
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size = prefix and 0x03
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            val dataBytes =
                when (size) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            if (index + 1 + dataBytes > descriptor.size) break
            var value = 0
            for (offset in 0 until dataBytes) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            index += 1 + dataBytes
            when {
                type == 1 && tag == 0x0 -> if (value == 0x0D) sawDigitizer = true
                // Report Size (global item tag=7) = 16 -> X/Y are 16-bit (both formats)
                type == 1 && tag == 0x7 -> if (value == 16) has16BitXy = true
            }
        }
        if (!sawDigitizer) return null
        if (!has16BitXy) return null
        // The Audi single-finger descriptor is 62B; the DiPlay two-finger descriptor is longer (two Finger Collections).
        // Single-finger Collection test: the number of Contact Identifier (0x09 0x22) occurrences.
        val fingerCollections = countOccurrences(descriptor, byteArrayOf(0x09, 0x22))
        return if (fingerCollections <= 1) Format.SINGLE_ABSOLUTE else Format.MULTI_SLOT
    }

    private fun countOccurrences(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        var count = 0
        var index = 0
        while (index <= haystack.size - needle.size) {
            var match = true
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    match = false
                    break
                }
            }
            if (match) {
                count++
                index += needle.size
            } else {
                index++
            }
        }
        return count
    }

    /**
     * Parses a touch report.
     * @param format the format decided from the descriptor; when null, fall back to report length (5B = single finger, >=12B = multi-slot)
     */
    fun parse(
        report: ByteArray,
        format: Format? = null,
    ): List<Contact>? {
        val resolved =
            format ?: when {
                report.size == 5 -> Format.SINGLE_ABSOLUTE
                report.size >= SLOTS * BYTES_PER_FINGER -> Format.MULTI_SLOT
                else -> return null
            }
        return when (resolved) {
            Format.SINGLE_ABSOLUTE -> parseSingleTouch(report)
            Format.MULTI_SLOT -> parseMultiSlot(report)
        }
    }

    /** Compatibility with old call sites: choose the parser by uuid (fallback path when no descriptor is available). */
    fun parseByUuid(
        uuid: String?,
        report: ByteArray,
    ): List<Contact>? =
        when (uuid) {
            TOUCH_UUID_HEX -> parseMultiSlot(report)
            // Single-finger device (uuid differs per head unit): self-identify by report length
            else -> parse(report, format = null)
        }

    /** Multi-slot format parsing (DiPlay test bench: 12 bytes, 2 slots). */
    fun parseMultiSlot(report: ByteArray): List<Contact> {
        if (report.size < SLOTS * BYTES_PER_FINGER) return emptyList()
        return (0 until SLOTS).mapNotNull { slot ->
            val offset = slot * BYTES_PER_FINGER
            Contact(
                slot = slot,
                down = report[offset + 1].toInt() and 0xFF == 1,
                xPixels = readU16Le(report, offset + 2),
                yPixels = readU16Le(report, offset + 4),
            )
        }
    }

    /** Single-finger absolute coordinates: 5 bytes [tip 1bit][X u16LE][Y u16LE]. */
    fun parseSingleTouch(report: ByteArray): List<Contact> {
        if (report.size < 5) return emptyList()
        return listOf(
            Contact(
                slot = 0,
                down = report[0].toInt() and 0x01 != 0,
                xPixels = readU16Le(report, 1),
                yPixels = readU16Le(report, 3),
            ),
        )
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}
