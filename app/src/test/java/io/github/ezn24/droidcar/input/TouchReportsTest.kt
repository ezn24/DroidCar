package io.github.ezn24.droidcar.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Touch report format determination (by device HID descriptor, not hard-coded to head-unit model/uuid).
 *
 * Two kinds of devices measured:
 *  - Audi MIB3 eso_touchScreen: single finger, 5 bytes [tip][X u16LE][Y u16LE], descriptor 62B
 *  - DiPlay test bench Touchscreen: two slots, 12 bytes, descriptor contains two Finger Collections
 */
class TouchReportsTest {
    /** Audi touchscreen descriptor (Digitizer page, single finger, X/Y 16bit). */
    private val audiTouchDescriptor =
        byteArrayOf(
            0x05,
            0x0d,
            0x09,
            0x04,
            0xa1.toByte(),
            0x01,
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x05,
            0x0d,
            0x09,
            0x33,
            0x15,
            0x00,
            0x25,
            0x01,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x75,
            0x07,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x01,
            0x05,
            0x01,
            0x09,
            0x30,
            0x15,
            0x00,
            0x26,
            0x84.toByte(),
            0x05,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x09,
            0x31,
            0x15,
            0x00,
            0x26,
            0x8e.toByte(),
            0x02,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            0xc0.toByte(),
        )

    /** DiPlay two-finger descriptor (two Finger Collections). */
    private val diplayTouchDescriptor =
        byteArrayOf(
            0x05,
            0x0d,
            0x09,
            0x04,
            0xa1.toByte(),
            0x01,
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x09,
            0x38,
            0x75,
            0x08,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x15,
            0x00,
            0x25,
            0x01,
            0x09,
            0x33,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x95.toByte(),
            0x07,
            0x81.toByte(),
            0x03,
            0x05,
            0x01,
            0x26,
            0x84.toByte(),
            0x05,
            0x09,
            0x30,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x26,
            0x8e.toByte(),
            0x02,
            0x09,
            0x31,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            // Second finger Collection
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x09,
            0x38,
            0x75,
            0x08,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x15,
            0x00,
            0x25,
            0x01,
            0x09,
            0x33,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x95.toByte(),
            0x07,
            0x81.toByte(),
            0x03,
            0x05,
            0x01,
            0x26,
            0x84.toByte(),
            0x05,
            0x09,
            0x30,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x26,
            0x8e.toByte(),
            0x02,
            0x09,
            0x31,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            0xc0.toByte(),
        )

    @Test
    fun audiSingleTouchDescriptorDetected() {
        assertEquals(
            TouchReports.Format.SINGLE_ABSOLUTE,
            TouchReports.formatFromDescriptor(audiTouchDescriptor),
        )
    }

    @Test
    fun diplayMultitouchDescriptorDetected() {
        assertEquals(
            TouchReports.Format.MULTI_SLOT,
            TouchReports.formatFromDescriptor(diplayTouchDescriptor),
        )
    }

    @Test
    fun nonDigitizerDescriptorIsNotTouch() {
        // Consumer Control (media keys) is not a touch device
        val consumer =
            byteArrayOf(
                0x05,
                0x0c,
                0x09,
                0x01,
                0xa1.toByte(),
                0x01,
                0x15,
                0x00,
                0x25,
                0x08,
                0x05,
                0x0c,
                0x0a,
                0xb0.toByte(),
                0x00,
                0x75,
                0x08,
                0x95.toByte(),
                0x01,
                0x81.toByte(),
                0x00,
                0xc0.toByte(),
            )
        assertNull(TouchReports.formatFromDescriptor(consumer))
    }

    @Test
    fun audiSingleTouchReportParses() {
        // [tip=1][X=0x0200 LE][Y=0x0100 LE]
        val report = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01)
        val contacts =
            TouchReports.parse(
                report,
                TouchReports.formatFromDescriptor(audiTouchDescriptor),
            )!!
        assertEquals(1, contacts.size)
        assertTrue(contacts[0].down)
        assertEquals(0x0200, contacts[0].xPixels)
        assertEquals(0x0100, contacts[0].yPixels)
    }

    @Test
    fun diplayMultiSlotReportParses() {
        // Slot 0 down (100,200), slot 1 up (0,0)
        val report =
            byteArrayOf(
                0x00,
                0x01,
                100.toByte(),
                0,
                200.toByte(),
                0,
                0x01,
                0x00,
                0,
                0,
                0,
                0,
            )
        val contacts =
            TouchReports.parse(
                report,
                TouchReports.formatFromDescriptor(diplayTouchDescriptor),
            )!!
        assertEquals(2, contacts.size)
        assertTrue(contacts[0].down)
        assertEquals(100, contacts[0].xPixels)
        assertEquals(200, contacts[0].yPixels)
        assertEquals(false, contacts[1].down)
    }

    @Test
    fun lengthFallbackWhenDescriptorMissing() {
        // Descriptor unavailable: 5B -> single finger; 12B -> multi-slot; otherwise -> not parsed
        assertEquals(1, TouchReports.parse(byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01), null)!!.size)
        assertEquals(2, TouchReports.parse(ByteArray(12), null)!!.size)
        assertNull(TouchReports.parse(byteArrayOf(0x00, 0x00, 0x00, 0x00), null))
    }
}
