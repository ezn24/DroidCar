package io.github.ezn24.cardeck.neo.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Head-unit Consumer Control HID media key parsing (Audi eso_playback measured).
 *
 * Key semantics (concluded by measurement 2026-10-17): in an Array report the **byte value is the Usage array index** --
 * when Audi presses "next track" it receives 0x05, corresponding to usages[5]=0xB5; "previous track" 0x06 -> usages[6]=0xB6.
 * Early on the byte was taken directly as the Usage ID, giving 0x05/0x06 -> no matching key (user feedback: "message received but track does not change").
 */
class ConsumerKeysTest {
    /** Descriptor/report originals written byte by byte, for byte-by-byte comparison with the hex in logs. */
    private fun hex(text: String): ByteArray =
        text
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** Audi /info hidDevices[0].hidDescriptor original (46B). */
    private val audiDescriptor =
        hex(
            "05 0c 09 01 a1 01 15 00 25 08 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a b3 00 0a b4 00 0a b5 00 0a b6 " +
                "00 0a b9 00 0a bc 00 75 08 95 01 81 00 c0",
        )

    @Test
    fun descriptorParsedAsArrayWithNineUsages() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)
        assertNotNull("Should be able to parse a Consumer mapping", map)
        assertEquals(true, map!!.isArray)
        assertEquals(9, map.usages.size)
        // Order must match the descriptor declaration (index semantics depend on it)
        assertEquals(
            listOf(0x0000, 0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC),
            map.usages,
        )
    }

    @Test
    fun reportValueIsArrayIndexNotUsageId() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)!!
        // Measured: next track is byte 0x05, previous track 0x06
        assertEquals(0xB5, map.usageFor(0x05))
        assertEquals(0xB6, map.usageFor(0x06))
        // Lesson: taking 0x05 directly as the Usage ID would give "unknown" -- these two assertions lock in the correct semantics
        assertEquals(0, ConsumerKeys.keyCodeFor(0x05))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(0xB5))
        // Play/pause = index 1/2
        assertEquals(0xB0, map.usageFor(1))
        assertEquals(0xB1, map.usageFor(2))
        // 0 and out-of-range = no key
        assertNull(map.usageFor(0))
        assertNull(map.usageFor(20))
    }

    @Test
    fun nonConsumerDevicesAreNotMediaKeys() {
        // Determination follows the descriptor, not the report length (DiPlay's knob=4B and telephony=1B would overlap with media keys):
        // the telephony descriptor (Telephony page 0x0B) must not be treated as media keys -- if its usage 0xB3 were misjudged
        // it would emit "fast forward".
        val telephonyDescriptor =
            hex(
                "05 0b 09 07 a1 01 15 00 25 11 05 0b 09 00 09 20 09 21 09 26 09 2f 09 b0 09 b1 09 b2 09 b3 75 08 95 " +
                    "01 81 00 c0",
            )
        assertNull(ConsumerKeys.parseDescriptor(telephonyDescriptor))
    }

    @Test
    fun diplayMediaDescriptorUsesIndexSemantics() {
        // DiPlay (test bench) media descriptor: Usage array of 6 entries, report values are likewise indices
        val diplayMedia =
            hex(
                "05 0c 09 01 a1 01 15 00 25 06 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a cd 00 0a b5 00 0a b6 00 0a 9e " +
                    "02 02 75 08 95 01 81 00 c0",
            )
        val map = ConsumerKeys.parseDescriptor(diplayMedia)!!
        assertEquals(0xB0, map.usageFor(1))
        assertEquals(0xB1, map.usageFor(2))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(map.usageFor(4)!!))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ConsumerKeys.keyCodeFor(map.usageFor(5)!!))
    }

    @Test
    fun usageMapsToAndroidMediaKeys() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, ConsumerKeys.keyCodeFor(0xB0))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, ConsumerKeys.keyCodeFor(0xB1))
        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, ConsumerKeys.keyCodeFor(0xB3))
        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, ConsumerKeys.keyCodeFor(0xB4))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(0xB5))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ConsumerKeys.keyCodeFor(0xB6))
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, ConsumerKeys.keyCodeFor(0xB9))
        // Repeat has no matching Android key -> 0 (caller ignores)
        assertEquals(0, ConsumerKeys.keyCodeFor(0xBC))
    }

    @Test
    fun descriptorWithoutConsumerPageReturnsNull() {
        // Audi touchscreen (uuid=1) descriptor: Generic Desktop / Digitizer, no Consumer page
        val touchDescriptor =
            hex(
                "05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 15 00 25 01 75 01 95 01 81 02 c0 c0",
            )
        assertNull(ConsumerKeys.parseDescriptor(touchDescriptor))
    }

    @Test
    fun allDescriptorUsagesNamed() {
        listOf(0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC).forEach { usage ->
            assertFalse(
                "usage=0x%02x should be readable".format(usage),
                ConsumerKeys.nameOf(usage).startsWith("未知"),
            )
        }
    }

    // ---- Honda HFT (Alpine 8A250-30A): bitmap (Variable) reports ----

    /**
     * Descriptor carried in the head unit's 0x6800 StartHID (33B), `81 02` = Data/Variable/Absolute:
     * 5 usages occupy 1 bit each, the trailing `75 03 95 01 81 03` is 3-bit padding.
     */
    private val hondaDescriptor =
        hex(
            "05 0c 09 01 a1 01 15 00 25 01 75 01 95 05 09 b0 09 b1 09 b5 09 b6 09 b9 81 02 75 03 95 01 81 03 c0",
        )

    @Test
    fun hondaDescriptorIsBitmapWithFiveUsages() {
        val map = ConsumerKeys.parseDescriptor(hondaDescriptor)!!
        assertFalse("`81 02` is a bitmap report, not an Array", map.isArray)
        assertEquals(listOf(0xB0, 0xB1, 0xB5, 0xB6, 0xB9), map.usages)
    }

    @Test
    fun hondaBitmapReportsDecodeToMediaKeys() {
        val map = ConsumerKeys.parseDescriptor(hondaDescriptor)!!
        // Measured report: holding "next track" repeatedly pushes 0x04, releasing pushes 0x00
        assertEquals(listOf(0xB5), map.usagesFor(byteArrayOf(0x04)))
        assertEquals(listOf(0xB6), map.usagesFor(byteArrayOf(0x08)))
        assertEquals(listOf(0xB0), map.usagesFor(byteArrayOf(0x01)))
        assertEquals(listOf(0xB1), map.usagesFor(byteArrayOf(0x02)))
        assertEquals(listOf(0xB9), map.usagesFor(byteArrayOf(0x10)))
        assertEquals(emptyList<Int>(), map.usagesFor(byteArrayOf(0x00)))
        // The old implementation took 0x04 directly as the Usage ID -> no matching key, which is the root cause of "key arrived but track did not change"
        assertEquals(0, ConsumerKeys.keyCodeFor(0x04))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(map.usagesFor(byteArrayOf(0x04))[0]))
    }

    @Test
    fun audiArrayReportStillWorksThroughUsagesFor() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)!!
        assertEquals(listOf(0xB5), map.usagesFor(byteArrayOf(0x05)))
        assertEquals(listOf(0xB6), map.usagesFor(byteArrayOf(0x06)))
        assertEquals(emptyList<Int>(), map.usagesFor(byteArrayOf(0x00)))
    }
}
