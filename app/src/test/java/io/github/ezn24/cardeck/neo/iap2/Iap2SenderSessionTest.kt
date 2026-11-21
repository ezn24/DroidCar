package io.github.ezn24.cardeck.neo.iap2

import io.github.ezn24.cardeck.neo.transport.Iap2LinkChannel
import io.github.ezn24.cardeck.neo.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end loopback test of the sender session against a mock accessory:
 * each end of the in-memory duplex stream runs a link channel, and the mock replies with the accessory-side timing of DiPlay/xcertplay,
 * verifying the full Phase 1 sequence (identification -> MFi skip -> HID -> WiFi config -> CarPlay start -> notification).
 */
class Iap2SenderSessionTest {
    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * Audi MMI 9310 form: the identification frame only declares sending 0x5703/0xea03 (not 0x4301), so Phase 1
     * does not wait; the head unit sends 0x4301 later during keep-alive (after Phase 1).
     * The old implementation treated this message as keep-alive and only logged its length, so pairing would never restart -- this case locks in
     * the behavior that "a late 0x4301 must be parsed into an endpoint and called back".
     */
    @Test
    fun lateStartSessionAfterPhaseOneTriggersEndpointUpdate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.AUDI_9310, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val phaseOne = session.run(timeoutMillis = 10_000L)
        // The head unit declares it does not send 0x4301: Phase 1 must return an empty endpoint immediately rather than wait out the 3s window
        assertTrue(phaseOne.ipAddresses.isEmpty())

        val pump = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pump.isDaemon = true
        pump.start()

        assertTrue("The late 0x4301 did not trigger the endpoint callback", updateLatch.await(5, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals(49152, late.airPlayPort)
        assertEquals(listOf("192.168.49.1", "fd00::1"), late.ipAddresses)
        assertEquals("mock-device-id", late.deviceIdentifier)

        phoneChannel.close()
        accessoryChannel.close()
        pump.join(2_000)
    }

    /**
     * PR #19 (Cadillac CT5): a new-protocol head unit declares it will send 0x4301 but it does not arrive within the window --
     * **do not tear down the link**, return an empty endpoint and enter keep-alive; the keep-alive pump parses the late 0x4301 into a complete endpoint
     * and delivers it via the onEndpointUpdate callback (the same family as Audi MMI 9310's 2 min 35 s late arrival).
     */
    @Test
    fun newProtocolCarWithoutCredentialsKeepsLinkForLateStartSession() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory =
            MockAccessory(
                accessoryChannel,
                shape = MockAccessory.Shape.CT5_NEW_PROTOCOL,
                lateStartSession = true,
            )

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val endpoint = session.run(timeoutMillis = 10_000L)
        assertEquals("", endpoint.ssid)

        val pumpThread = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pumpThread.isDaemon = true
        pumpThread.start()
        assertTrue("The late 0x4301 was not delivered via onEndpointUpdate within 10s", updateLatch.await(10, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals("secret-passphrase", late.passphrase)
        assertEquals(49152, late.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **The real Audi MMI 3315 form must send 0x5702** (the pin for the 2026-10-22 regression).
     *
     * Its receivable list has only 5 entries without 0x5702; the old implementation judged "skip the old protocol" from that -> not a single
     * 0x5702 was sent in the whole round -> the head unit's AirPlay service **never came up** (all 45 connections ECONNREFUSED,
     * and `_airplay._tcp` was not advertised). Whereas the same car on the old version (blindly sending 0x5702) replied with 0x5703 within 122~368ms,
     * and the port was reachable 30ms after joining the network.
     *
     * The criterion is `ReceiverProfile.wifiGen`: sparse list -> UNKNOWN -> conservatively use the old protocol.
     */
    @Test
    fun sparseDeclarationsStillSendWifiRequest() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.AUDI_3315)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertTrue(
            "The Audi 3315 form must send 0x5702 (skipping it keeps the head unit from opening the AirPlay service)",
            accessory.sawWifiRequest,
        )
        // And must use the credentials given by 0x5703
        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **The BMW iD9 form must not send 0x5702** (the other half of the pin for the same change).
     *
     * Both its lists are complete (>= 10 entries in total) yet have no 0x57xx -> the profile judges NEW -> skip 0x5702,
     * the AP info comes entirely with 0x4301. If 0x5702 were wrongly sent and 0x5703 awaited, it would be the old "dead wait and time out each round"
     * problem that 8b64147 set out to fix.
     */
    @Test
    fun fullDeclarationsWithoutOldProtocolSkipWifiRequest() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.BMW_ID9)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertTrue("The BMW iD9 form should not send 0x5702 (AP info comes with 0x4301)", !accessory.sawWifiRequest)
        // AP info comes from the wireless group of 0x4301
        assertEquals("MockAP", endpoint.ssid)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **Do not tear down the link when there are no new credentials but the hotspot SSID is known** (the second pin of the 2026-10-22 Audi regression).
     *
     * Key timing evidence: a real iPhone joined the network at 14:23:02 and only received the head unit's ctrl invite at 14:23:23
     * (21 seconds); the old implementation "threw if no 0x4301 within 8 seconds" and killed itself each round, so the head unit's wait always
     * restarted from 0. As long as it still knows which hotspot to join, it must let the controller keep waiting on the current network.
     */
    @Test
    fun knownCarSsidAvoidsCredentiallessAbort() {
        val endpoint = credentiallessEndpoint("Audi_MMI_3315_5GHz")
        assertNotNull("A fallback endpoint must be synthesized when the hotspot is known, rather than tearing down the link", endpoint)
        assertEquals("Audi_MMI_3315_5GHz", endpoint!!.ssid)
        // It must be a "non-self-sufficient endpoint": only then does the controller take the "wait for ctrl invite -> mDNS/gateway" path
        assertEquals(0, endpoint.airPlayPort)
        assertTrue(endpoint.ipAddresses.isEmpty())
    }

    /** Failure is allowed only when even the SSID is unknown (continuing to wait would just spin). */
    @Test
    fun noCredentialAndNoKnownSsidStillFails() {
        assertEquals(null, credentiallessEndpoint(null))
        assertEquals(null, credentiallessEndpoint(""))
        assertEquals(null, credentiallessEndpoint("   "))
    }

    /**
     * Audi MMI 3315 form: the address fields of 0x4301 are NUL-padded to a fixed length (22 after IPv6, 5 after IPv4).
     * The old implementation stripped only **one** trailing NUL, giving `"10.174.189.1\0\0\0\0\0"` -- contains('.')=true,
     * contains(':')=false, which fools every "is this IPv4" check but Android's getaddrinfo cannot resolve it,
     * showing up as `Unable to resolve host "10.174.189.1"` (measured 2026-10-18: after pairing succeeded the video port
     * and keepAlive both failed and the session was torn down after 10 seconds).
     */
    @Test
    fun nulPaddedAddressesAreStrippedCompletely() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, padAddresses = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        // Every address must be clean: no NUL, no whitespace
        endpoint.ipAddresses.forEach { address ->
            check(address.none { it == '\u0000' }) { "Address contains NUL: ${address.toByteArray().toList()}" }
            check(address.trim() == address) { "Address contains leading/trailing whitespace: '$address'" }
        }
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)
        // Port parsing is unaffected by padding (u32 parameter in the same frame)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * 0x5703 is slow to answer: the sender must, within the wait window, **resend the device status greeting** and then **resend 0x5702**,
     * rather than waiting idly until the head unit tears down the link (the fix for BMW iD8.5 stuck on 0x5703).
     */
    @Test
    fun slowWifiConfigurationTriggersResends() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, slowWifi = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 15_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** Scripted replies following the timing of DiPlay Iap2WirelessControlClient. */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
        /**
         * Head-unit form (0x1D01 two lists + whether it answers 0x5702).
         *
         * These four forms are the **entire** source of evidence for WiFi credential protocol dispatch; a misjudgment on any one would
         * leave the matching car model unable to connect, so each is pinned:
         *  - [Shape.TEST_BENCH]: DiPlay/xcertplay test bench, no lists, uses 0x5702->0x5703;
         *  - [Shape.AUDI_9310]: sends a 2-entry list (including 0x5703), uses the old protocol;
         *  - [Shape.AUDI_3315]: sends 2 entries (**without** 0x5703), receives 5 (**without** 0x5702)
         *    -- sparse and incomplete, yet **it actually fully supports the old protocol**; judging it "skip 0x5702" would keep the head unit
         *    from starting the AirPlay service at all (2026-10-22 real-car log: 45 ECONNREFUSED);
         *  - [Shape.BMW_ID9]: both lists complete (10+ entries) yet no 0x57xx, AP info only in 0x4301.
         */
        private val shape: Shape = Shape.TEST_BENCH,
        /** Resend 0x4301 after Phase 1 ends (the real timing of a head-unit window opening late). */
        private val lateStartSession: Boolean = false,
        /**
         * Address fields use the fixed-length padding of a real head unit (Audi MMI 3315 measured: 22 NULs after IPv6,
         * 5 after IPv4). Used to lock in the behavior "all trailing NULs must be stripped".
         */
        private val padAddresses: Boolean = false,
        /** 0x5703 is slow to answer: the sender should resend the greeting, then resend 0x5702 (rather than waiting idly until the head unit tears down the link). */
        private val slowWifi: Boolean = false,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        /** Whether the sender has sent 0x5702 (used for dispatch assertions). */
        @Volatile
        var sawWifiRequest: Boolean = false
            private set

        /** Head-unit form. */
        enum class Shape {
            TEST_BENCH,
            AUDI_9310,
            AUDI_3315,
            BMW_ID9,

            // Cadillac CT5 (GM/Cinemo): declares it will send 0x4301, no 0x5702/0x5703 --
            // AP info can only be in 0x4301, which often does not arrive within the window (PR #19, 2026-10-21 log)
            CT5_NEW_PROTOCOL,
        }

        /** Old-style Audi/test bench: no receive list declared, no StartHID sent. */
        private val legacyHid: Boolean get() = shape != Shape.TEST_BENCH

        /** Whether it answers 0x5702 (the old protocol has a credential exchange on the network side). CT5 and BMW are both pure new protocol. */
        private val answersWifiRequest: Boolean get() = shape != Shape.BMW_ID9 && shape != Shape.CT5_NEW_PROTOCOL

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, identityName()),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
                *declarationParams(),
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64) { it.toByte() }))
            expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            // The device status greeting follows 0xAA05 (real-device order, before subscriptions and 0x5702; PR17 staged waiting relies on it too)
            expectDeviceStateGreetings()

            // Subscriptions + StartHID (Audi/BMW/CT5 forms do not send them; the sender should skip waiting directly)
            if (!legacyHid) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(Iap2MessageIds.START_POWER_UPDATES)
                send(
                    Iap2MessageIds.START_HID,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.u16(1, 2),
                    Iap2Csm.u16(2, 1),
                    Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
                )
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // WiFi config: BMW/CT5 forms do not send 0x5702 (AP info comes with 0x4301).
            // If the sender still sends 0x5702 in the BMW form, the first expect(0x4e0d) below will immediately
            // report "expected 0x4e0d got 0x5702" -- a dispatch error has nowhere to hide.
            if (answersWifiRequest) {
                expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                sawWifiRequest = true
                if (slowWifi) {
                    // Sender did not get 0x5703 in 1.5s -> resend the greeting; another 1.3s -> resend 0x5702
                    // (PR17 staged-wait rescue script, BMW iD8.5 measured)
                    expectDeviceStateGreetings()
                    expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                }
                // One late subscription request (common in real head-unit bring-up: 0x5000 and 0x5703 arrive adjacent).
                // It must be parked rather than re-read repeatedly inside expectOrNull -- the old implementation stuffed unexpected frames back into
                // the "read first by itself next round" queue, so the same frame looped forever (root cause of the 2026-10-18 log flood).
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(
                    Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                    Iap2Csm.string(1, "MockAP"),
                    Iap2Csm.string(2, "secret-passphrase"),
                    Iap2Csm.u8(3, 2),
                    Iap2Csm.u8(4, 36),
                )
            }

            // CarPlay start
            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            if (!lateStartSession) {
                sendStartSession()
            }

            expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
            if (lateStartSession) {
                // Sent late after the head unit opens the CarPlay window (Phase 1 is long over)
                Thread.sleep(150)
                sendStartSession()
            }
        }

        /** Device status greeting quintet (0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C). */
        private fun expectDeviceStateGreetings() {
            expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            expect(Iap2MessageIds.DEVICE_UUID_UPDATE)
        }

        /** The name in 0x1D01 (Audi/BMW/CT5 forms use real head-unit names, so the MFi memory and profile keys agree). */
        private fun identityName(): String =
            when (shape) {
                Shape.AUDI_9310, Shape.AUDI_3315 -> "Audi_MMI_3315"
                Shape.BMW_ID9 -> "BMW 87866"
                Shape.CT5_NEW_PROTOCOL -> "CADILLAC_2179"
                Shape.TEST_BENCH -> "MockCarPlay"
            }

        /**
         * Parameter 6 of 0x1D01 (what the head unit sends) and parameter 7 (what the head unit can receive).
         *
         * The 5-entry receive list of Audi 3315 comes from a real identification frame (2026-10-22 log); the two complete BMW iD9 lists
         * come from the same form in ReceiverProfileTest -- both are "scenes of dispatch misjudgment".
         */
        private fun declarationParams(): Array<ByteArray> =
            when (shape) {
                // Test bench: declares nothing at all -> profile UNKNOWN -> use the old protocol
                Shape.TEST_BENCH -> emptyArray()
                // Audi MMI 9310: only declares sending 0x5703/0xea03 (no receive list)
                Shape.AUDI_9310 -> arrayOf(Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03)))
                // Audi MMI 3315: sends 2 (0x4301/0xea03), receives 5 (none with 0x57xx)
                Shape.AUDI_3315 ->
                    arrayOf(
                        Iap2Csm.u16List(6, intArrayOf(0x4301, 0xea03)),
                        Iap2Csm.u16List(7, intArrayOf(0x4e0c, 0x4e09, 0x4300, 0xea00, 0xea01)),
                    )
                // BMW iD9: two lists of 10 each, 20 in total (>=10) yet no 0x57xx -> NEW
                Shape.BMW_ID9 ->
                    arrayOf(
                        Iap2Csm.u16List(
                            6,
                            intArrayOf(0xaa01, 0xaa03, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157, 0x4301),
                        ),
                        Iap2Csm.u16List(
                            7,
                            intArrayOf(0xaa00, 0xaa02, 0xaa04, 0xaa05, 0xea00, 0xea01, 0x5001, 0x5201, 0x4300, 0x4e0d),
                        ),
                    )
                // Cadillac CT5 (PR #19): sends 11 including 0x4301, receives 9, none with 0x57xx -> NEW
                Shape.CT5_NEW_PROTOCOL ->
                    arrayOf(
                        Iap2Csm.u16List(
                            6,
                            intArrayOf(0xaa06, 0x6800, 0x6802, 0x6803, 0x4c00, 0x4c02, 0x4c03, 0x4c05, 0x5000, 0x5002, 0x4301),
                        ),
                        Iap2Csm.u16List(
                            7,
                            intArrayOf(0x6801, 0x4c01, 0x4c04, 0x5001, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4300),
                        ),
                    )
            }

        private fun sendStartSession() {
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    addressParam(3, "192.168.49.1", padTo = 17),
                    addressParam(3, "fd00::1", padTo = 47),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )
        }

        /** Address parameter: real head units NUL-pad to a fixed length; the default (no padding) matches the earlier behavior. */
        private fun addressParam(
            id: Int,
            value: String,
            padTo: Int,
        ): ByteArray {
            val raw = value.encodeToByteArray()
            if (!padAddresses) return Iap2Csm.param(id, raw + byteArrayOf(0))
            // Keep at least one NUL terminator (CSM string semantics), pad the rest to a fixed width
            val width = maxOf(padTo, raw.size + 1)
            val padded = raw + ByteArray(width - raw.size)
            return Iap2Csm.param(id, padded)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        private fun expect(messageId: Int) {
            println("MOCK expect 0x${messageId.toString(16)}")
            val frame =
                recvFrame(10_000)
                    ?: error("mock timed out waiting 0x${messageId.toString(16)}")
            check(frame.messageId == messageId) {
                "mock expected 0x${messageId.toString(16)} got 0x${frame.messageId.toString(16)}"
            }
            println("MOCK got 0x${frame.messageId.toString(16)}")
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            return frames.first()
        }
    }
}
