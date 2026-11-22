package io.github.ezn24.droidcar.input

import android.view.KeyEvent

/**
 * Head-unit "navigation" HID (iDrive knob / four-way stick / confirm key) -> Android D-pad keys.
 *
 * Division of labor with [ConsumerKeys]:
 *  - Consumer page (0x0C) = media keys -> `AudioManager.dispatchMediaKeyEvent`, global, needs no focus;
 *  - Generic Desktop page (0x01) Wheel(0x38)/Hat(0x39) = knob/direction -> **DPAD_***,
 *    must be injected into the virtual display and handled by the focus system (`CarInputBridge.injectNavigationKey`).
 *
 * **Not hard-coded per head-unit model**: decide from the HID descriptor first (Wheel/Hat present and not a
 * Digitizer makes a navigation device),
 * and fall back to report length only when the descriptor is missing (2~4 bytes, not the 5-byte touch).
 *
 * Report layout (calibrated against a BMW iD8.5 field test; with [rawLogEnabled] on, the log carries the raw hex):
 *  - The last byte is parsed as a **signed** knob step (clockwise positive);
 *  - the preceding bytes are parsed as a button bitmap: bit0=confirm, 1=up, 2=down, 3=left, 4=right, 5=back.
 *  - When the descriptor declares a Hat switch, use the "low 4 bits = 8-way hat" semantics instead.
 */
object HidNavigation {
    /** Raw report log switch (Settings -> Display -> "HID raw log"). */
    @Volatile
    var rawLogEnabled: Boolean = false

    private const val PAGE_GENERIC_DESKTOP = 0x01
    private const val PAGE_BUTTON = 0x09
    private const val PAGE_DIGITIZER = 0x0D
    private const val USAGE_WHEEL = 0x38
    private const val USAGE_HAT_SWITCH = 0x39

    /** Maximum steps per report (so a fast knob turn does not inject dozens of keys at once and choke the UI). */
    private const val MAX_STEPS = 5

    private val lastButtonMask = HashMap<String, Int>()
    private val lastHat = HashMap<String, Int>()
    private val lastDirection = HashMap<String, Int>()

    private const val PAGE_CONSUMER = 0x0C
    private const val USAGE_X = 0x30
    private const val USAGE_Y = 0x31
    private const val USAGE_AC_HOME = 0x223
    private const val USAGE_AC_BACK = 0x224

    /** One Input field of a report: usage, bit position and width, signed when Logical Minimum < 0. */
    internal data class Field(
        val page: Int,
        val usage: Int,
        val bitOffset: Int,
        val bitSize: Int,
        val signed: Boolean,
    )

    /**
     * Input fields of a descriptor (no report IDs: these head-unit devices send one report each). Variable items get
     * one field per usage; array and constant items only advance the bit position.
     */
    internal fun fieldsOf(descriptor: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        var page = 0
        var reportSize = 0
        var reportCount = 0
        var logicalMin = 0
        val usages = ArrayList<Int>()
        var bit = 0
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size =
                when (prefix and 0x03) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            if (index + 1 + size > descriptor.size) break
            var value = 0
            for (offset in 0 until size) value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            val signedValue =
                when (size) {
                    1 -> value.toByte().toInt()
                    2 -> value.toShort().toInt()
                    else -> value
                }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            index += 1 + size
            when (type) {
                1 ->
                    when (tag) {
                        0x0 -> page = value
                        0x1 -> logicalMin = signedValue
                        0x7 -> reportSize = value
                        0x9 -> reportCount = value
                    }
                2 -> if (tag == 0x0) usages += if (size == 4) value and 0xFFFF else value
                0 -> {
                    if (tag == 0x8) {
                        val constant = (value and 0x01) != 0
                        val variable = (value and 0x02) != 0
                        if (!constant && variable) {
                            for (i in 0 until reportCount) {
                                val usage = usages.getOrNull(i) ?: usages.lastOrNull() ?: 0
                                fields += Field(page, usage, bit + i * reportSize, reportSize, logicalMin < 0)
                            }
                        }
                        bit += reportSize * reportCount
                    }
                    usages.clear()
                }
            }
        }
        return fields
    }

    internal fun valueOf(
        report: ByteArray,
        field: Field,
    ): Int {
        var raw = 0L
        for (i in 0 until field.bitSize) {
            val bitIndex = field.bitOffset + i
            val byte = bitIndex / 8
            if (byte >= report.size) return 0
            if ((report[byte].toInt() shr (bitIndex % 8)) and 1 == 1) raw = raw or (1L shl i)
        }
        if (field.signed && field.bitSize in 1..31 && (raw and (1L shl (field.bitSize - 1))) != 0L) {
            raw -= 1L shl field.bitSize
        }
        return raw.toInt()
    }

    /**
     * Knob devices that describe a 4-way stick as Generic Desktop X/Y and Home/Back as Consumer AC usages (ChairPlay /
     * xcertplay knob: `[select|home|back bits][X s8][Y s8][wheel s8]`). The older bitmap layout below stays for
     * descriptors without them (BMW iD8.5 calibration).
     */
    private fun usesFieldLayout(fields: List<Field>): Boolean =
        fields.any {
            (it.page == PAGE_GENERIC_DESKTOP && (it.usage == USAGE_X || it.usage == USAGE_Y)) ||
                (it.page == PAGE_CONSUMER && (it.usage == USAGE_AC_HOME || it.usage == USAGE_AC_BACK))
        }

    private fun parseFields(
        uuid: String,
        fields: List<Field>,
        report: ByteArray,
    ): List<Int> {
        val keys = ArrayList<Int>(3)
        var mask = 0
        var x = 0
        var y = 0
        var wheel = 0
        for (field in fields) {
            val value = valueOf(report, field)
            when {
                field.page == PAGE_BUTTON && field.usage == 1 -> if (value != 0) mask = mask or 0x01
                field.page == PAGE_CONSUMER && field.usage == USAGE_AC_HOME -> if (value != 0) mask = mask or 0x02
                field.page == PAGE_CONSUMER && field.usage == USAGE_AC_BACK -> if (value != 0) mask = mask or 0x04
                field.page == PAGE_GENERIC_DESKTOP && field.usage == USAGE_X -> x = value
                field.page == PAGE_GENERIC_DESKTOP && field.usage == USAGE_Y -> y = value
                field.page == PAGE_GENERIC_DESKTOP && field.usage == USAGE_WHEEL -> wheel = value
            }
        }
        val pressed = mask and lastButtonMask[uuid].orZero().inv()
        lastButtonMask[uuid] = mask
        if ((pressed and 0x01) != 0) keys += KeyEvent.KEYCODE_DPAD_CENTER
        if ((pressed and 0x02) != 0) keys += KeyEvent.KEYCODE_HOME
        if ((pressed and 0x04) != 0) keys += KeyEvent.KEYCODE_BACK
        // The stick reports a held direction: one key when it leaves the centre or changes direction
        val direction =
            when {
                x > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
                x < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
                y < 0 -> KeyEvent.KEYCODE_DPAD_UP
                y > 0 -> KeyEvent.KEYCODE_DPAD_DOWN
                else -> 0
            }
        if (direction != 0 && direction != lastDirection[uuid]) keys += direction
        lastDirection[uuid] = direction
        if (wheel != 0) {
            val key = if (wheel > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            repeat(kotlin.math.abs(wheel).coerceAtMost(MAX_STEPS)) { keys += key }
        }
        return keys
    }

    /** Whether the report's device is a navigation device (has Wheel/Hat and is not a touch screen). */
    fun isNavigationDevice(descriptor: ByteArray?): Boolean {
        if (descriptor == null || descriptor.isEmpty()) return false
        if (hasPage(descriptor, PAGE_DIGITIZER)) return false
        return hasPage(descriptor, PAGE_GENERIC_DESKTOP) &&
            (hasUsage(descriptor, USAGE_WHEEL) || hasUsage(descriptor, USAGE_HAT_SWITCH))
    }

    /**
     * Handles one navigation report and returns the key sequence to inject (zero or more).
     *
     * @param uuid HID device uuid (edge state is kept separately per uuid)
     */
    fun handle(
        uuid: String,
        descriptor: ByteArray?,
        report: ByteArray,
    ): List<Int> {
        if (report.isEmpty()) return emptyList()
        val nav = isNavigationDevice(descriptor) || (descriptor == null && report.size in 2..4)
        if (!nav) return emptyList()
        if (rawLogEnabled) {
            android.util.Log.i(
                "DroidCar-HID",
                "HID-RAW uuid=$uuid bytes=" + report.joinToString(" ") { "%02X".format(it) } +
                    " nav=${isNavigationDevice(descriptor)}",
            )
        }
        val fields = descriptor?.let(::fieldsOf).orEmpty()
        return when {
            usesFieldLayout(fields) -> parseFields(uuid, fields, report)
            descriptor != null && hasUsage(descriptor, USAGE_HAT_SWITCH) && report.size == 1 ->
                parseHat(uuid, report[0].toInt() and 0xFF)
            else -> parseWheelAndButtons(uuid, report)
        }
    }

    /** 8-way hat: 0=up 1=up-right 2=right 3=down-right 4=down 5=down-left 6=left 7=up-left, anything else = released. */
    private fun parseHat(
        uuid: String,
        value: Int,
    ): List<Int> {
        val hat = value and 0x0F
        if (hat == lastHat[uuid]) return emptyList()
        lastHat[uuid] = hat
        return when (hat) {
            0 -> listOf(KeyEvent.KEYCODE_DPAD_UP)
            1 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            2 -> listOf(KeyEvent.KEYCODE_DPAD_RIGHT)
            3 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            4 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN)
            5 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            6 -> listOf(KeyEvent.KEYCODE_DPAD_LEFT)
            7 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)
            else -> emptyList()
        }
    }

    /** Last byte = signed step, preceding bytes = button bitmap (edge triggered). */
    private fun parseWheelAndButtons(
        uuid: String,
        report: ByteArray,
    ): List<Int> {
        val keys = ArrayList<Int>(3)
        val buttonBytes = if (report.size >= 2) report.size - 1 else report.size
        var mask = 0
        for (i in 0 until buttonBytes.coerceAtMost(2)) {
            mask = mask or ((report[i].toInt() and 0xFF) shl (8 * i))
        }
        val pressed = mask and lastButtonMask[uuid].orZero().inv()
        lastButtonMask[uuid] = mask
        if (pressed != 0) {
            if ((pressed and 0x01) != 0) keys += KeyEvent.KEYCODE_DPAD_CENTER
            if ((pressed and 0x02) != 0) keys += KeyEvent.KEYCODE_DPAD_UP
            if ((pressed and 0x04) != 0) keys += KeyEvent.KEYCODE_DPAD_DOWN
            if ((pressed and 0x08) != 0) keys += KeyEvent.KEYCODE_DPAD_LEFT
            if ((pressed and 0x10) != 0) keys += KeyEvent.KEYCODE_DPAD_RIGHT
            if ((pressed and 0x20) != 0) keys += KeyEvent.KEYCODE_BACK
        }
        if (report.size >= 2) {
            val delta = report[report.size - 1].toInt()
            if (delta != 0) {
                val key = if (delta > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                repeat(kotlin.math.abs(delta).coerceAtMost(MAX_STEPS)) { keys += key }
            }
        }
        return keys
    }

    private fun Int?.orZero(): Int = this ?: 0

    /**
     * Whether a Usage Page appears in the descriptor.
     * Only an "item scan": UsagePage is a global item (bTag=0x0),
     * and here any 1~2 byte data item whose value equals the page is matched loosely; a false positive only affects routing
     * and never turns a touch screen into a knob (the Digitizer page is excluded first).
     */
    private fun hasPage(
        descriptor: ByteArray,
        page: Int,
    ): Boolean {
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size =
                when (prefix and 0x03) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (index + 1 + size > descriptor.size) return false
            var value = 0
            for (offset in 0 until size) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            if (type == 1 && tag == 0x0 && value == page) return true
            index += 1 + size
        }
        return false
    }

    /** Whether a Usage appears in the descriptor (local item bTag=0x0, 1/2 bytes). */
    private fun hasUsage(
        descriptor: ByteArray,
        usage: Int,
    ): Boolean {
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size =
                when (prefix and 0x03) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (index + 1 + size > descriptor.size) return false
            var value = 0
            for (offset in 0 until size) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            if (type == 2 && tag == 0x0 && value == usage) return true
            index += 1 + size
        }
        return false
    }

    /** Readable key name (for logs). */
    fun nameOf(keyCode: Int): String =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_CENTER -> "confirm"
            KeyEvent.KEYCODE_BACK -> "back"
            KeyEvent.KEYCODE_HOME -> "home"
            else -> "$keyCode"
        }
}
