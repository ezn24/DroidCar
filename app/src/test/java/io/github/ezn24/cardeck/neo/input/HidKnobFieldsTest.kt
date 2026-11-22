package io.github.ezn24.cardeck.neo.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Knob decoding from the descriptor ChairPlay / xcertplay send in /info (captured 2026-11-23). */
class HidKnobFieldsTest {
    private val descriptor =
        (
            "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 05 0c 0a 23 02 0a 24 02 95 02 81 02 " +
                "95 05 81 01 05 01 09 01 a1 00 09 30 09 31 15 81 25 7f 75 08 95 02 81 02 c0 09 38 15 81 25 7f " +
                "75 08 95 01 81 06 c0"
        ).split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun report(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    @Test
    fun stickDirectionsComeFromXAndY() {
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), HidNavigation.handle("k1", descriptor, report(0, 0x7f, 0, 0)))
        assertEquals(emptyList<Int>(), HidNavigation.handle("k1", descriptor, report(0, 0, 0, 0)))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), HidNavigation.handle("k1", descriptor, report(0, 0, 0x7f, 0)))
        HidNavigation.handle("k1", descriptor, report(0, 0, 0, 0))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), HidNavigation.handle("k1", descriptor, report(0, 0, 0x81, 0)))
    }

    @Test
    fun buttonsAreSelectHomeBack() {
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_CENTER), HidNavigation.handle("k2", descriptor, report(0x01, 0, 0, 0)))
        HidNavigation.handle("k2", descriptor, report(0, 0, 0, 0))
        assertEquals(listOf(KeyEvent.KEYCODE_HOME), HidNavigation.handle("k2", descriptor, report(0x02, 0, 0, 0)))
        HidNavigation.handle("k2", descriptor, report(0, 0, 0, 0))
        assertEquals(listOf(KeyEvent.KEYCODE_BACK), HidNavigation.handle("k2", descriptor, report(0x04, 0, 0, 0)))
    }

    @Test
    fun wheelStepsAreSigned() {
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), HidNavigation.handle("k3", descriptor, report(0, 0, 0, 0x01)))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), HidNavigation.handle("k3", descriptor, report(0, 0, 0, 0xff)))
    }

    @Test
    fun telephonyArrayMapsAnswerAndEnd() {
        val phone =
            (
                "05 0b 09 07 a1 01 15 00 25 11 05 0b 09 00 09 20 09 21 09 26 09 2f 09 b0 09 b1 09 b2 09 b3 09 b4 " +
                    "09 b5 09 b6 09 b7 09 b8 09 b9 09 ba 09 bb 05 07 09 2a 75 08 95 01 81 00 c0"
            ).split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val keys = ConsumerKeys.parseDescriptor(phone, ConsumerKeys.USAGE_PAGE_TELEPHONY)!!
        assertEquals(ConsumerKeys.PHONE_HOOK_SWITCH, keys.usageFor(1))
        assertEquals(ConsumerKeys.PHONE_DROP, keys.usageFor(3))
        // The media device must not be read as a phone
        assertEquals(null, ConsumerKeys.parseDescriptor(report(0x05, 0x0c, 0x09, 0x01), ConsumerKeys.USAGE_PAGE_TELEPHONY))
    }
}
