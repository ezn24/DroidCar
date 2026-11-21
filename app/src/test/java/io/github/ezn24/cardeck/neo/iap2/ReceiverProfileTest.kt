package io.github.ezn24.cardeck.neo.iap2

import io.github.ezn24.cardeck.neo.net.AirPlayFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Head-unit profile determination (pure function): signal combination -> era/wifiGen + explainable basis. */
class ReceiverProfileTest {
    @Test
    fun unknownWhenNoSignals() {
        val p = ReceiverProfile.classify(ReceiverProfile.Signals())
        assertEquals(ReceiverProfile.Era.UNKNOWN, p.era)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertTrue(p.reasons.isNotEmpty())
    }

    @Test
    fun preHomeKitWhenBit38Clear() {
        // Audi 4xxx / CarPlay 210.81: features has no bit38 -> plaintext + AES-CTR zero-key tier
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) and
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT.inv()
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "210.81"),
            )
        assertEquals(ReceiverProfile.Era.PRE_HOMEKIT, p.era)
        assertTrue(p.reasons.any { it.contains("has no bit38") })
    }

    @Test
    fun modernWhenBit38Set() {
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) or
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "450.14.2"),
            )
        assertEquals(ReceiverProfile.Era.MODERN, p.era)
    }

    @Test
    fun wifiGenLegacyWhenListsDeclareOldProtocol() {
        // Audi form: the send list contains 0x5703
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = "Audi_MMI_3315",
                    model = "AUDI 5163",
                    manufacturer = "DELPHI",
                    sendIds = setOf(0x4301, 0xea03),
                    receiveIds = setOf(0x4e0c, 0x4e09, 0x4300, 0xea00),
                ),
            )
        // The Audi list is sparse (2+5 entries) and does not list 0x57xx -- although it actually uses the old protocol, the list is not evidence:
        // a sparse list judges UNKNOWN (conservative old protocol), only a complete list judges NEW
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertEquals("Audi_MMI_3315 AUDI 5163 DELPHI", p.identityKey)
    }

    @Test
    fun wifiGenNewWhenFullListsLackOldProtocol() {
        // BMW iD9 form: both lists complete (10+ entries), neither has 0x5702/0x5703
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    sendIds = setOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
                    receiveIds = setOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.NEW, p.wifiGen)
        assertTrue(p.reasons.any { it.contains("AP info comes from 0x4301") })
    }

    @Test
    fun sparseSendListAloneStaysUnknown() {
        // A single sparse list (2 entries) without 0x5703: not judged "new" -- the Audi counterexample proves a sparse list
        // not listing 0x57xx can still use the old protocol
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(sendIds = setOf(0x4301, 0xea03)),
            )
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
    }

    @Test
    fun describeCarriesReasons() {
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = 1L shl 38, srcvers = "450.14.2", sendIds = setOf(0x5703)),
            )
        val text = ReceiverProfile.describe(p)
        assertTrue(text.contains("era=MODERN"))
        assertTrue(text.contains("wifiGen=LEGACY"))
        assertTrue(text.contains("srcvers=450.14.2"))
    }

    // ---- Link generation determination (linkGenerationOf) ----
    // The cost of misjudgment is asymmetric: Audi misjudged as EVENT_LINK = marker negotiation hangs the head unit UI (requires restarting
    // the head unit); Honda misjudged as CLASSIC_MIB3 = media keys silently fail. The three conditions (delphi+audi+sparse list)
    // are the measured signature of Audi 3315/0628; once the firmware wording changes it silently flips back to the incident behavior -- each case pins one.

    private val audi3315Signals =
        ReceiverProfile.Signals(
            name = "Audi_MMI_3315",
            model = "AUDI 5163",
            manufacturer = "DELPHI",
            // Real form of 0x1D01: sends 2 + receives 5 (2026-10-23 real-device log)
            sendIds = setOf(0x4301, 0xea03),
            receiveIds = setOf(0x5703, 0x4e09, 0x4e0b, 0x4e0d, 0x4e0e),
        )

    @Test
    fun delphiAudiWithSparseListsIsClassicMib3() {
        assertEquals(ReceiverProfile.LinkGeneration.CLASSIC_MIB3, ReceiverProfile.linkGenerationOf(audi3315Signals))
    }

    @Test
    fun fullListsAreNeverClassicMib3() {
        // Lists total >=10 entries (iD9 form): even if identity fields coincidentally match, do not judge CLASSIC
        val p =
            ReceiverProfile.linkGenerationOf(
                audi3315Signals.copy(
                    sendIds = setOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
                    receiveIds = setOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
                ),
            )
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, p)
    }

    @Test
    fun missingManufacturerOrModelStaysEventLink() {
        // Firmware under-reports identity parameters (including NUL truncated to empty): prefer the compatible side over guessing
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(manufacturer = null)),
        )
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(model = null)),
        )
    }

    @Test
    fun missingListsStaysEventLink() {
        // 0x1D01 did not provide both lists: sparseness cannot be proven, do not judge CLASSIC
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(sendIds = null)),
        )
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(receiveIds = null)),
        )
    }

    @Test
    fun nonAudiDelphiHardwareStaysEventLink() {
        // Honda HFT (Alpine part) and other non-Audi DELPHI-built parts: no form is judged CLASSIC
        val honda =
            ReceiverProfile.Signals(
                name = "HFT",
                model = "Honda HFT",
                manufacturer = "ALPINE",
                sendIds = setOf(0x4301),
                receiveIds = setOf(0x5703, 0x6800),
            )
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, ReceiverProfile.linkGenerationOf(honda))
        val delphiNonAudi = audi3315Signals.copy(model = "T-Roc 23")
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, ReceiverProfile.linkGenerationOf(delphiNonAudi))
    }
}
