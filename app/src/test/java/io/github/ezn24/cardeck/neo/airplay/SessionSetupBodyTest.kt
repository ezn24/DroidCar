package io.github.ezn24.cardeck.neo.airplay

import io.github.ezn24.cardeck.neo.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression protection for the session-level SETUP body.
 *
 * WinPlay (measured 2026-10-19) filters sessions by "the iPhone Bluetooth address the user selected";
 * a deviceID in the body that does not match gets a 403 -- the hard-coded fake value from catplay was once used,
 * ff:ee:dd:cc:bb:aa, which showed up as auto-disconnect about 45 seconds after connecting (receiver waited for video, timed out).
 */
class SessionSetupBodyTest {
    private fun client() =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "DESKTOP-43KNTO6",
                passphrase = "secret",
                channel = 0,
                securityType = 2,
                airPlayPort = 17000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("192.168.137.1"),
            ),
            log = {},
        )

    @Test
    fun deviceIdIsCarriedVerbatimIntoSetupBody() {
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertEquals("10:C1:97:1A:C7:44", root["deviceID"])
    }

    @Test
    fun deviceIdIsNotTheCatplayPlaceholder() {
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertNotEquals("ff:ee:dd:cc:bb:aa", root["deviceID"])
    }

    @Test
    fun bodyKeepsTheFieldsReceiversNegotiateOn() {
        val port = 35449
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", port)
        val root = BinaryPlist.parse(body) as Map<*, *>
        // timingPort is the media clock sync entry; features determine enabledFeatures (missing means the video pipeline is not enabled)
        assertEquals(port.toLong(), root["timingPort"])
        assertTrue((root["features"] as List<*>).contains("viewAreas"))
        assertEquals(true, root["keepAliveLowPower"])
    }

    /**
     * An unpaired (et=NONE) session must explicitly carry `et=1` (catplay `AirPlayEncryptionType::NONE`).
     * Pre-HomeKit old head units only accept plaintext sessions; without this key a strict receiver treats it as
     * "encryption declared but no key" and rejects it.
     */
    @Test
    fun unpairedSessionDeclaresEtNone() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", 35449, encrypted = false),
            ) as Map<*, *>
        assertEquals(1L, root["et"])
        assertEquals(false, root["updateSessionRequest"])
    }

    @Test
    fun pairedSessionOmitsEt() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", 35449, encrypted = true),
            ) as Map<*, *>
        assertNull(root["et"])
    }
}
