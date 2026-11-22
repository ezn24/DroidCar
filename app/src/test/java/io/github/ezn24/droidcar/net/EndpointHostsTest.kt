package io.github.ezn24.droidcar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Receiver address candidates and mDNS features parsing.
 *
 * Both are root-cause candidates for "old head units cannot connect" (2026-10-19 Audi 4xxx / CarPlay 210.81):
 *  - The old code only recognised IPv4 (`contains('.')`) -> head units publishing only link-local IPv6 were judged to have no address;
 *  - The old code ignored TXT features -> pre-HomeKit head units could not be identified, spinning through the 120s pairing window.
 */
class EndpointHostsTest {
    @Test
    fun ipv6OnlyEndpointIsUsable() {
        assertTrue(EndpointHosts.hasUsable(listOf("fe80::f822:90ff:fe13:3b18%wlan0")))
        assertTrue(EndpointHosts.hasUsable(listOf("fe80::1")))
        assertFalse(EndpointHosts.hasUsable(emptyList()))
        assertFalse(EndpointHosts.hasUsable(listOf("", "not-an-ip")))
    }

    @Test
    fun ipv4PreferredThenGlobalIpv6ThenLinkLocal() {
        val ordered =
            EndpointHosts.candidates(
                listOf(
                    "fe80::1%wlan0",
                    "2001:db8::5",
                    "10.174.189.1",
                ),
            )
        assertEquals(listOf("10.174.189.1", "2001:db8::5", "fe80::1%wlan0"), ordered)
    }

    @Test
    fun duplicateScopedAndUnscopedAddressesCollapse() {
        val ordered = EndpointHosts.candidates(listOf("fe80::1", "fe80::1%wlan0", "FE80::1%3"))
        assertEquals(1, ordered.size)
        assertEquals("fe80::1", ordered.first())
    }

    @Test
    fun ipv4NetworkFilterUses24Prefix() {
        assertTrue(EndpointHosts.isSameNetwork("10.174.189.1", "10.174.189.57"))
        assertFalse(EndpointHosts.isSameNetwork("172.30.8.246", "10.174.189.57"))
        // No limit when the local IPv4 cannot be obtained (old behavior)
        assertTrue(EndpointHosts.isSameNetwork("10.174.189.1", null))
    }

    @Test
    fun linkLocalIpv6AlwaysAcceptedGlobalIpv6NeedsMatchingPrefix() {
        assertTrue(EndpointHosts.isSameNetwork("fe80::1%wlan0", "10.0.0.2"))
        assertTrue(
            EndpointHosts.isSameNetwork(
                "2001:db8:1:2::5",
                "10.0.0.2",
                listOf("2001:db8:1:2::99"),
            ),
        )
        assertFalse(
            EndpointHosts.isSameNetwork(
                "2001:db8:9:9::5",
                "10.0.0.2",
                listOf("2001:db8:1:2::99"),
            ),
        )
        // No local IPv6 info: reject global IPv6 (prefer the gateway fallback over mistakenly connecting to a home network device)
        assertFalse(EndpointHosts.isSameNetwork("2001:db8:1:2::5", "10.0.0.2"))
    }

    @Test
    fun ipv6Prefix64ExpandsCompressedForms() {
        assertEquals("2001:0db8:0001:0002", EndpointHosts.ipv6Prefix64("2001:db8:1:2::5"))
        assertEquals("fe80:0000:0000:0000", EndpointHosts.ipv6Prefix64("fe80::1%wlan0"))
        assertEquals("2001:0db8:0000:0000", EndpointHosts.ipv6Prefix64("2001:db8::"))
        assertNull(EndpointHosts.ipv6Prefix64("not-ipv6"))
    }
}

class AirPlayFeaturesTest {
    @Test
    fun parsesDiplayStyleFeatureBitmap() {
        // DiPlay: features=0x5653aee2,0x61
        val features = AirPlayFeatures.parse("0x5653aee2,0x61")
        assertEquals((0x61L shl 32) or 0x5653aee2L, features)
        // bit38 of the high 32 bits -> global bit38
        assertTrue(features!! and AirPlayFeatures.HK_PAIRING_AND_ENCRYPT != 0L)
        assertEquals(true, AirPlayFeatures.supportsPairing(features))
    }

    @Test
    fun preHomeKitBitmapLacksPairingBit() {
        // CarPlay 210.81 era (catplay README compatibility table): no HK_PAIRING_AND_ENCRYPT
        val features = AirPlayFeatures.parse("0x00000080,0x0")
        assertEquals(false, AirPlayFeatures.supportsPairing(features))
    }

    @Test
    fun missingOrMalformedFeaturesStayUnknown() {
        assertNull(AirPlayFeatures.parse(null))
        assertNull(AirPlayFeatures.parse(""))
        assertNull(AirPlayFeatures.parse("not-hex"))
        assertNull(AirPlayFeatures.parse("0x1,0x2,0x3"))
        // Unknown must return null, and must never be treated as "pairing unsupported"
        assertNull(AirPlayFeatures.supportsPairing(null))
    }

    /** WinPlay 2026-11-20: 0x4301 gives 17000 while another service on the same subnet advertises 7000 -- must not be merged. */
    @Test
    fun mdnsWithDifferentPortOrKeyIsAnotherReceiver() {
        assertTrue(EndpointHosts.mdnsMismatch(17000, null, 7000, null) != null)
        assertTrue(EndpointHosts.mdnsMismatch(7000, "aa11", 7000, "bb22") != null)
    }

    /** Merge as before when port/public key match or are unknown (absence is not refutation). */
    @Test
    fun mdnsMatchingOrUnknownIsMerged() {
        assertNull(EndpointHosts.mdnsMismatch(17000, "AA11", 17000, "aa11"))
        assertNull(EndpointHosts.mdnsMismatch(0, null, 7000, "aa11"))
        assertNull(EndpointHosts.mdnsMismatch(7000, "aa11", 7000, null))
    }
}
