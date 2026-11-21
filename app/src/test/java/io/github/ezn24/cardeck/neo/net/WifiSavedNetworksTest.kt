package io.github.ezn24.cardeck.neo.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `cmd wifi list-networks` parsing: used only for "asking the user whether to clear",
 * a misjudgment would wrongly delete the user's network, so the boundaries must be locked down.
 */
class WifiSavedNetworksTest {
    /** Measured output from a real device (Honor Android 17): space-aligned, no tabs. */
    private val realOutput =
        """
        Network Id      SSID                         Security type
        0            2-502                            wpa2-psk
        0            2-502                            wpa3-sae^
        1            李志华的iPhone                    wpa3-sae
        2            Weimob_office                    open
        2            Weimob_office                    owe 
        3            Audi_MMI_9310_5GHz               wpa2-psk
        """.trimIndent()

    @Test
    fun findsExactMatchIgnoringOtherNetworks() {
        assertEquals(listOf(3), WifiSavedNetworks.idsOf(realOutput, "Audi_MMI_9310_5GHz"))
    }

    @Test
    fun dedupesMultipleSecurityRowsForSameNetwork() {
        // The same netId may have two rows, wpa2/wpa3
        assertEquals(listOf(0), WifiSavedNetworks.idsOf(realOutput, "2-502"))
    }

    @Test
    fun handlesSsidWithSpacesAndNonAscii() {
        // Whitespace inside the SSID is reassembled into a single space
        assertEquals(listOf(2), WifiSavedNetworks.idsOf(realOutput, "Weimob_office"))
        assertEquals(listOf(1), WifiSavedNetworks.idsOf(realOutput, "李志华的iPhone"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        // A nonexistent SSID must never be matched approximately (otherwise another person's network gets wrongly deleted)
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, "Audi_MMI_9310"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, "Redmi K40"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, ""))
    }

    @Test
    fun headerOnlyOrEmptyOutputIsSafe() {
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("Network Id SSID Security type", "X"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("", "X"))
    }

    @Test
    fun skipsMalformedRows() {
        val messy =
            """
            Network Id      SSID                         Security type
            notanumber   Foo                          wpa2-psk
            7            OnlyTwoColumns
            8            Bar                          wpa2-psk
            """.trimIndent()
        assertEquals(listOf(8), WifiSavedNetworks.idsOf(messy, "Bar"))
    }
}
