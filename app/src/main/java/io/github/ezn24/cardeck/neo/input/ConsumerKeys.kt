package io.github.ezn24.cardeck.neo.input

import android.view.KeyEvent

/**
 * Head-unit Consumer Control HID report -> Android media keys.
 *
 * Head-unit/steering-wheel media keys travel the HID path in CarPlay (not AirPlay event commands); the device is usually
 * `eso_playback` (Audi `/info` hidDevices[0], uuid=0).
 *
 * **The report byte is an index into the Usage array, not a Usage ID** (pinned down by a 2026-10-17 field test):
 * the Audi descriptor is `LogicalMin 0 / LogicalMax 8 + ReportSize 8 + ReportCount 1 +
 * Input(Data,Array,Abs)`, with 9 Usages declared in order (0x0000, 0xB0, 0xB1, 0xB3, 0xB4,
 * 0xB5, 0xB6, 0xB9, 0xBC); under Array semantics the 1-byte report gives "which entry".
 * Observed: pressing "next" yields 0x05 and "previous" yields 0x06, matching usages[5]=0xB5 and
 * usages[6]=0xB6; treating the byte as a Usage ID gives 0x05/0x06, which map to no key (the original bug).
 *
 * So we **parse the device's own HID descriptor** for the Usage array order (nothing hard-coded for one head unit)
 * and look up by index; when the descriptor is unavailable we fall back to the direct "byte is the Usage ID" reading.
 */
object ConsumerKeys {
    /** Consumer page (the most common multimedia page besides Generic Desktop). */
    private const val USAGE_PAGE_CONSUMER = 0x0C

    /**
     * Consumer-key mapping of one HID device.
     *
     * @param usages Usage IDs by index (parsed from the descriptor, in declaration order)
     * @param isArray true = the report value is a usages index (standard Array semantics); false = the value is the Usage ID
     */
    class KeyMap(
        val usages: List<Int>,
        val isArray: Boolean,
    ) {
        /** Report value -> Usage ID; 0 or out of range returns null (0 = no key/release). */
        fun usageFor(reportValue: Int): Int? {
            if (reportValue == 0) return null
            return if (isArray) {
                usages.getOrNull(reportValue)?.takeIf { it != 0 }
            } else {
                reportValue
            }
        }

        /**
         * HID report -> the set of Usages **pressed** this time.
         *
         * The two report semantics must be kept apart; mixing them makes no key work at all:
         *  - Array (`81 00`): the byte value is a usages index, only one value at a time;
         *  - Variable / bitmap (`81 02`): **each usage takes 1 bit**, expanded bitwise in the report.
         *
         * The Honda HFT (Alpine 8A250-30A) StartHID descriptor is exactly a bitmap:
         * `... 75 01 95 05 09 B0 09 B1 09 B5 09 B6 09 B9 81 02 ...` -> 1-byte report,
         * bit0=play(0xB0), bit1=pause(0xB1), bit2=next(0xB5), bit3=previous(0xB6),
         * bit4=stop(0xB9); report 0x04 = next, 0x00 = all released.
         */
        fun usagesFor(report: ByteArray): List<Int> {
            if (report.isEmpty()) return emptyList()
            if (isArray) return listOfNotNull(usageFor(report[0].toInt() and 0xFF))
            val pressed = ArrayList<Int>(usages.size)
            usages.forEachIndexed { index, usage ->
                val byteIndex = index / 8
                if (byteIndex >= report.size || usage == 0) return@forEachIndexed
                val bit = (report[byteIndex].toInt() shr (index % 8)) and 0x01
                if (bit == 1) pressed += usage
            }
            return pressed
        }
    }

    /**
     * Parses the Consumer Usage array in a HID descriptor.
     * Takes only the Usage items inside the first Application Collection with UsagePage=Consumer;
     * returns null when the descriptor has no Consumer page (the device is not a media-key device).
     *
     * Supports short item encoding (bSize: 0 -> 0 bytes / 1 -> 1 / 2 -> 2 / 3 -> 4).
     */
    fun parseDescriptor(
        descriptor: ByteArray,
        pageWanted: Int = USAGE_PAGE_CONSUMER,
    ): KeyMap? {
        var index = 0
        var page = 0
        var inConsumerApp = false
        var sawConsumerPage = false
        val usages = ArrayList<Int>()
        var isArray = true

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
                // Global item: Usage Page
                type == 1 && tag == 0x0 -> {
                    page = value
                    if (value == pageWanted) sawConsumerPage = true
                }
                // Global item: Logical Maximum (bounds the index range in Array reports)
                type == 1 && tag == 0x2 -> Unit
                // Local item: Usage (1 or 2 bytes), collected into the current Consumer Application's array
                type == 2 && tag == 0x0 -> {
                    if (inConsumerApp && sawConsumerPage) usages += value
                }
                // Main item: Collection / EndCollection
                type == 0 && tag == 0xA -> {
                    // Start collecting only when the current page is Consumer and we are in a top-level Application
                    if (!inConsumerApp && page == pageWanted) inConsumerApp = true
                }
                type == 0 && tag == 0xC -> {
                    if (inConsumerApp) return KeyMap(usages, isArray)
                }
                // Main item: Input. bit1=0 means Array (value is an index), =1 means Variable (value is a bitmap).
                // bit0=1 is Constant (padding bits at the end of the descriptor, such as `75 03 95 01 81 03`),
                // which does not take part in key semantics and must not overwrite the real report format seen earlier.
                type == 0 && tag == 0x8 -> {
                    if ((value and 0x01) == 0) isArray = (value and 0x02) == 0
                }
            }
        }
        if (sawConsumerPage && usages.isNotEmpty()) return KeyMap(usages, isArray)
        return null
    }

    /** Usage ID -> Android media key; returns 0 when there is no matching key (e.g. Repeat). */
    fun keyCodeFor(usage: Int): Int =
        when (usage) {
            0xB0 -> KeyEvent.KEYCODE_MEDIA_PLAY
            0xB1 -> KeyEvent.KEYCODE_MEDIA_PAUSE
            0xB3 -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            0xB4 -> KeyEvent.KEYCODE_MEDIA_REWIND
            0xB5 -> KeyEvent.KEYCODE_MEDIA_NEXT
            0xB6 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            0xB9 -> KeyEvent.KEYCODE_MEDIA_STOP
            // Play/Pause toggle (ChairPlay's middle media button; the usual single play key on newer units)
            0xCD -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> 0
        }

    /** Telephony page (0x0B): head-unit answer / hang-up keys, an Array of usages like the Consumer devices. */
    const val USAGE_PAGE_TELEPHONY = 0x0B

    /** Telephony usages: Hook Switch (answer), Drop (end), Phone Mute. */
    const val PHONE_HOOK_SWITCH = 0x20
    const val PHONE_DROP = 0x26
    const val PHONE_MUTE = 0x2F

    /** Readable name (for logs). */
    fun nameOf(usage: Int): String =
        when (usage) {
            0xB0 -> "Play"
            0xB1 -> "Pause"
            0xB3 -> "Fast forward"
            0xB4 -> "Rewind"
            0xB5 -> "Next"
            0xB6 -> "Previous"
            0xB9 -> "Stop"
            0xBC -> "Repeat"
            0xCD -> "Play/Pause"
            else -> "unknown(0x%02x)".format(usage)
        }
}
