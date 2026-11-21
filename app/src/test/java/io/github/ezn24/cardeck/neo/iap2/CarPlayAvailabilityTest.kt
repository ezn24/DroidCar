package io.github.ezn24.cardeck.neo.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0x4300: default single group (group 1); generic head units use the same two groups as an iPhone (group 0 device identifier + group 1 Bluetooth MAC). */
class CarPlayAvailabilityTest {
    private val mac = "A4:A4:90:D6:CA:25"

    @Test
    fun defaultIsSingleBluetoothGroup() {
        val params = Iap2Csm.parseParams(CarPlayAvailability.body(mac, dualGroup = false))
        assertEquals(listOf(1), params.map { it.id })
    }

    @Test
    fun gmUsesDeviceGroupThenBluetoothGroup() {
        val params = Iap2Csm.parseParams(CarPlayAvailability.body(mac, dualGroup = true))
        assertEquals(listOf(0, 1), params.map { it.id })
        val deviceGroup = Iap2Csm.parseParams(params[0].value).associateBy { it.id }
        assertEquals(CarPlayAvailability.deviceIdentifier(mac), String(deviceGroup[1]!!.value, Charsets.UTF_8).trimEnd(Char(0)))
    }

    @Test
    fun deviceIdentifierIsStable24UpperHex() {
        val id = CarPlayAvailability.deviceIdentifier(mac)
        assertEquals(24, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'A'..'F' })
        assertEquals(id, CarPlayAvailability.deviceIdentifier(mac.lowercase()))
    }
}
