package io.github.ezn24.cardeck.neo.input

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
                "CarDeck-HID",
                "HID-RAW uuid=$uuid bytes=" + report.joinToString(" ") { "%02X".format(it) } +
                    " nav=${isNavigationDevice(descriptor)}",
            )
        }
        return if (descriptor != null && hasUsage(descriptor, USAGE_HAT_SWITCH) && report.size == 1) {
            parseHat(uuid, report[0].toInt() and 0xFF)
        } else {
            parseWheelAndButtons(uuid, report)
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
            else -> "$keyCode"
        }
}
