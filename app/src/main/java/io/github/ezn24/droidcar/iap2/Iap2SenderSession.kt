package io.github.ezn24.droidcar.iap2

import io.github.ezn24.droidcar.media.CarNowPlaying
import io.github.ezn24.droidcar.nav.CarRouteGuidance
import io.github.ezn24.droidcar.transport.Iap2LinkChannel
import java.io.IOException
import java.security.SecureRandom

/**
 * iAP2 sender (iPhone role) control session: walks the whole wireless CarPlay bring-up sequence and finally
 * parses the receiver hotspot and AirPlay endpoint from 0x5703 / 0x4301, the Go/No-Go deliverable of Phase 1.
 *
 * Sequence (follows the peer behavior of reference/xcertplay Iap2WirelessControlClient):
 *   0x1D00 -> 0x1D01 -> 0x1D02 (identification)
 *   0xAA00 -> 0xAA01 -> 0xAA02 -> 0xAA03 -> 0xAA05 (MFi, certificate/signature verification skipped)
 *   [the accessory sends 5 subscription frames + 0x6800 StartHID, we reply 0x6801]
 *   0x5702 -> 0x5703 (get hotspot SSID/password/channel)
 *   0x4300 -> 0x4301 (get AirPlay port/public key/wireless parameters)
 *   0x4E0D + 0x4E0E (announce wireless availability and the transport identifier)
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /**
     * Real Bluetooth MAC of this phone (transport identifier of 0x4300/0x4E0E and the id of the _carplay-ctrl advertisement; real
     * cars check it).
     */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** Bluetooth name of this phone (0x4E09 device information). */
    private val deviceName: String = "iPhone",
    /**
     * Called when 0x5703 (hotspot credentials) is received. The controller must immediately bind the head unit WiFi + advertise
     * `_carplay-ctrl._tcp` (with the real Bluetooth MAC). Real car (Audi MIB3) flow:
     * the head unit discovers the service on WiFi and invites the phone with `GET /ctrl-int/1/connect`,
     * **and sends 0x4301 only after the invitation is accepted**; advertising only after 0x4301 would deadlock both sides.
     */
    private val onWifiCredentials: ((WifiConfiguration) -> Unit)? = null,
    /**
     * Callback when 0x4301 arrives only after Phase 1 ended (the late signal that the head unit opened its CarPlay window).
     *
     * The keep-alive pump used to treat every message as a heartbeat and only log its length: on Audi MMI 9310, 0x4301 was
     * measured arriving
     * **2 min 35 s** after the end of Phase 1 (the head unit window had just opened), and once the log swallowed it we
     * never started the AirPlay connection; after 15 seconds with nobody connecting the head unit tore down the whole RFCOMM,
     * which the user saw as "disconnects by itself after connecting". Once received it must go to the controller to hot-update the
     * endpoint / retry pairing.
     */
    private val onEndpointUpdate: ((CarPlayEndpoint) -> Unit)? = null,
    /**
     * Car hotspot SSID known to this process (from the previous round's 0x5703/0x4301, or the one already joined).
     *
     * Use: when this round got no 0x5703 and no 0x4301 within 8 seconds, **do not tear the link down right away**;
     * as long as the phone is already on (or knows) the head unit hotspot, synthesize an endpoint with `airPlayPort=0`
     * and let the controller bind the current network and keep waiting via mDNS/gateway (a late 0x4301 still hot-updates the endpoint).
     * Audi log 2026-10-22: from round 2 on the phone was already on the head unit hotspot, yet it killed itself every round after
     * 8 seconds for "no credentials", resetting the head unit invitation window again and again.
     */
    private val knownCarSsid: (() -> String?)? = null,
    /**
     * Only for per-head-unit persistent memory of the MFi challenge length ([MfiLengthMemory]). null = in-process
     * table only (unit tests / pure JVM); the protocol flow is unaffected.
     */
    private val context: android.content.Context? = null,
    /**
     * Adapter mode only: pause between link NORMAL and 0x1D00. The reference sender needed it
     * (IAP2_BOOTSTRAP_DELAY_MS=1000: "keeps the final link ACK separate from StartIdentification").
     */
    private val adapterIdentificationDelayMillis: Long = 1_000L,
) {
    /** Receiver information parsed from 0x5703 + 0x4301. */
    data class CarPlayEndpoint(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKey: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
        /**
         * features bitmap of the `_airplay._tcp` TXT (high 32 bits merged in).
         *
         * Key bit: bit38 `HK_PAIRING_AND_ENCRYPT` = supports HomeKit pairing/encryption.
         * null = unknown (only 0x4301, no mDNS discovered): treated as "pairing required", keeping the old behavior.
         * catplay uses it to tell three eras of receivers apart: pre-HomeKit (CarPlay 210.81, no such bit,
         * only a plaintext `et=NONE` session + AES-CTR screen stream) / supported but skippable (280 era) /
         * mandatory pairing (modern). The failure to connect the Audi 4xxx (4851) on 2026-10-19 is directly tied to this classification.
         */
        val advertisedFeatures: Long? = null,
    )

    private val parser = Iap2Csm.Parser()

    /** Frames just parsed and not yet classified (in arrival order). */
    private val receivedFrames = ArrayDeque<Iap2Csm.Frame>()

    /**
     * Holding area for "unexpected" frames taken during [expectOrNull] waits; [recvFrame] takes from here first.
     *
     * **Must be separate from [receivedFrames]**: if the two are mixed, expectOrNull pushes a non-target frame back into
     * the queue it reads first next round, and the same frame is taken out, recorded and pushed back endlessly, a livelock
     * (measured 2026-10-18: during head unit bring-up it sent 0x5000 StartNowPlayingUpdates and the
     * log spammed ~1000 lines/second until the wait timed out).
     */
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()

    /** Holding area cap: head unit subscription messages may keep arriving during a long wait, and no cap would eat memory. */
    private val maxPendingFrames = 256
    private var pendingOverflowLogged = false

    private val random = SecureRandom()

    /** Result of parsing 0x5703 (needed to answer again if the head unit resends 0x5702 during keep-alive). */
    @Volatile
    private var wifi: WifiConfiguration? = null

    /** Whether the raw 0x4301 body was already logged (once per session round, avoids spam from periodic resends). */
    private var startSessionRawLogged = false

    /**
     * Endpoint fingerprint of the latest late 0x4301 (details are not logged again when the head unit periodically resends
     * identical content).
     */
    private var lastLateStartSessionSignature: Int? = null

    /**
     * 0x1D01 param 6: list of message IDs the head unit declares it can SEND.
     * Used to decide whether 0x6800 (StartHID) / 0x4301 will really come; if not declared, do not wait blindly:
     * the Audi was measured declaring only 0x5703/0xea03, so the old flow idly waited 10s for 0x4301 and 2s for StartHID.
     */
    private var accessorySendIds: Set<Int> = emptySet()

    /** 0x1D01 param 7: list of message IDs the head unit declares it can RECEIVE. */
    private var accessoryReceiveIds: Set<Int> = emptySet()

    /** Whether the identification frame really carried a receive list (an empty list must be distinguished from "not provided"). */
    private var receiveListDeclared = false

    /**
     * WiFi generation verdict of the head unit profile (output of [ReceiverProfile.classify], computed once when 0x1D01 arrives).
     * WiFi credential exchange dispatch **follows only this**; do not judge directly by "is 0x5702 in the list" again:
     * the Audi MMI 3315 list (sends 2 / receives 5) has no 0x5702, yet it fully supports
     * 0x5702->0x5703 and **needs to receive 0x5702 to bring up its AirPlay service**; a hard block by the list
     * ends in the "port 7000 ECONNREFUSED all the way" dead end (real-car log ruling, 2026-10-22).
     */
    private var wifiGen: ReceiverProfile.WifiGen = ReceiverProfile.WifiGen.UNKNOWN

    /**
     * Head unit link generation ([ReceiverProfile.linkGenerationOf], computed once when 0x1D01 arrives).
     * CLASSIC_MIB3 (Audi DELPHI real car) = ignore marker after detect;
     * EVENT_LINK (others/unknown) = upgrade control channel negotiation by threshold. See [ReceiverProfile].
     */
    var linkGeneration: ReceiverProfile.LinkGeneration = ReceiverProfile.LinkGeneration.EVENT_LINK
        private set

    /** Whether the head unit declares it will send a message; null = no list provided (unknown, wait conservatively). */
    private fun accessoryDeclaresSend(messageId: Int): Boolean? = if (accessorySendIds.isEmpty()) null else messageId in accessorySendIds

    /** Whether the head unit declares it can receive a message; null = no list provided (unknown, proceed conservatively). */
    private fun accessoryDeclaresReceive(messageId: Int): Boolean? = if (!receiveListDeclared) null else messageId in accessoryReceiveIds

    private companion object {
        /** Subscription name of this link in CarNowPlaying (the Wi-Fi tunnel holds its own). */
        const val NOW_PLAYING_LINK = "Bluetooth"

        /** Window to wait for StartHID: xcertplay sends it (arrives right after subscription), DiPlay does not. */
        const val HID_WAIT_NANOS = 2_000_000_000L

        /**
         * Short window to wait for 0x4301 (a test bench that declares it answers within milliseconds; head units that do not
         * declare it skip it).
         * The full window is only used when "the head unit gave no declaration list": cut from 10s to 3s, since the whole
         * real-car bring-up has only a budget of tens of seconds and answers were measured in milliseconds.
         */
        const val START_SESSION_WAIT_NANOS = 3_000_000_000L

        /**
         * Window to wait for 0x5703. **Short**: Audi MMI 3315 over eight rounds measured the 0x5702->0x5703 answer at
         * 122/136/166/166/198/246/266/368ms, so 1.5s has 4x headroom; for head units that do not support the legacy protocol
         * (BMW iD9 takes the NEW branch and never sends it) this wait is also only 1.5s and does not drag down bring-up.
         */
        const val WIFI_CONFIG_WAIT_NANOS = 1_500_000_000L

        /**
         * Wait window for new-protocol head units (no 0x5703, AP information only in 0x4301).
         * These head units must give the hotspot information to the phone before it can join the network; a window that is too
         * short is a death sentence.
         * BMW iD9 measured the whole bring-up at ~10 seconds, so 8 seconds is given.
         */
        const val START_SESSION_WAIT_NEW_PROTOCOL_NANOS = 8_000_000_000L

        /** Cap for logging the raw 0x4301 body (Audi measured 261B, logged in full; abnormally large ones keep only the head). */
        const val START_SESSION_RAW_LOG_BYTES = 1_024

        /**
         * Retry window of lengths known to have been "silent". The first probe keeps the original window (may be a slow head unit),
         * but a length that already failed once need not wait the full 6 seconds again; a real iPhone answers within ~100ms.
         */
        const val MFI_SILENT_RETRY_WINDOW_MS = 1_500L

        /** Challenge lengths (length, first probe window): order and windows were decided by DiPlay/Audi two-ended measurements. */
        private val MFI_ATTEMPTS = listOf(32 to 3_000L, 20 to 6_000L, 16 to 3_000L)

        /** Real-car receiver signatures (substrings of 0x1D01 name/model/manufacturer, compared in lowercase). */
        private val LEGACY_RECEIVER_MARKERS = listOf("audi", "delphi", "mib", "harman", "porsche")

        /** Adapter mode waits on the link only (~100 days; avoids nanoTime overflow). */
        const val ADAPTER_NO_DEADLINE_NANOS = 100L * 24 * 3_600 * 1_000_000_000L

        /** Adapter-mode MFi challenge order: the smartBox answers 20 B (reference field result). */
        private val ADAPTER_MFI_ORDER = listOf(20, 32, 16)

        /** 0x4E0A value the reference sent to the smartBox (its 0x1D01 lists en/ko). */
        const val ADAPTER_LANGUAGE = "en"
    }

    /**
     * Extract the receiver identity text from 0x1D01 (name/model/manufacturer concatenated in lowercase), used for MFi challenge
     * length selection.
     */
    private fun identityText(frame: Iap2Csm.Frame): String {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return listOfNotNull(
            params[0]?.value?.asStringOrEmpty(),
            params[1]?.value?.asStringOrEmpty(),
            params[2]?.value?.asStringOrEmpty(),
        ).joinToString(" ").lowercase()
    }

    fun run(timeoutMillis: Long = 60_000L): CarPlayEndpoint {
        if (channel.adapterMode) return runAdapter()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        if (!channel.awaitReady(remainingMillis(deadline))) {
            throw IOException("iAP2 link negotiation timed out")
        }
        log("iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()}")

        // ---- Identification ----
        send(Iap2MessageIds.START_IDENTIFICATION)
        val identification = expect(Iap2MessageIds.IDENTIFICATION_INFORMATION, deadline)
        logIdentification(identification)
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        log("iap2 tx=0x1d02 identification-accepted")

        // EA protocol parsing (start timing is after MFi completes, see below; measured on DiPlay 2026-10-11:
        // sending 0xEA00 before authentication stalls the test bench state machine so it no longer answers 0xAA01; the Audi is
        // insensitive to it)
        val eaProtocols = parseEaProtocols(identification)

        // ---- MFi (verification skipped: 0xAA05 self-reports success) ----
        val identityText = identityText(identification)
        gmReceiver = identityText.contains("general motors")
        val legacyCar = LEGACY_RECEIVER_MARKERS.any { identityText.contains(it) }
        if (legacyCar) {
            log(
                "iap2 identified as a real-car family ($identityText); MFi prefers 20B (length recorded from a real iPhone; 32B is " +
                    "handled unreliably by real cars)",
            )
        }
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        val certificate = expect(Iap2MessageIds.MFI_CERTIFICATE, deadline)
        log("iap2 rx=0xaa01 certificate bytes=${certificate.body.size} (verification skipped)")
        // Challenge length chosen by receiver identity (corrected by Audi byte-level measurement 2026-10-11):
        //  - Real-car families (Audi/Delphi MIB etc.): **try 20B first**. Real iPhone recordings (carplay-wifi-extractor
        //    client) and the catplay sender both use 20B; the Audi answers 20B at once. Going 32B first makes the head unit
        // ACK the challenge frame and then go **silent for the whole stretch** (no ACK, no answer, the session turns zombie);
        // measured over several rounds
        //    that day, every 32B->20B session hung and every 20B->(memory hit) session passed instantly.
        //  - Test bench DiPlay (Carlinkit AuthV3 EC identity): **accepts exactly 32B only**, any other length drops the link.
        // No answer at all does not abort either: we do not verify signatures (0xAA05 is self-reported) and whether 0x5702 is
        // answered decides the pass.
        runMfiChallenge(deadline, prefer20First = legacyCar, receiverKey = identityText)
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        log("iap2 tx=0xaa05 authentication-succeeded (self-reported)")

        // ---- Device status greeting (must immediately follow 0xAA05: the head unit uses it to judge the accessory ready) ----
        sendDeviceStateGreetings()

        // ---- Start EA sessions (after MFi authentication completes) ----
        // The Audi 0x1D01 declares three EA protocols: de.audi.mmicconnectdatatransfer / com.audi.cpp.eadata0/1;
        // touch HID related data goes through the EA session. Sending before authentication chokes DiPlay (see above).
        for ((protocolId, name) in eaProtocols) {
            sendFireAndForget(
                Iap2MessageIds.EA_OPEN_SESSION,
                // param 1 = EA protocol name (UTF-8 string); sending a protocol number makes the head unit not answer 0xEA03
                Iap2Csm.u8(0, protocolId) + Iap2Csm.string(1, name),
            )
            log("iap2 tx=0xea00 startEASession protocol=$protocolId name=$name")
        }
        // EA session status (0xEA03): confirmed within a short window so it does not eat the later 0x5703 wait time.
        if (eaProtocols.isNotEmpty() &&
            accessoryDeclaresSend(Iap2MessageIds.EA_SESSION_STATUS) != false
        ) {
            val status =
                expectOrNull(
                    Iap2MessageIds.EA_SESSION_STATUS,
                    wifiRescueDeadline(deadline, 500L),
                )
            if (status != null) {
                log("iap2 rx=0xea03 EA session opened (${status.body.size}B)")
            } else {
                log("iap2 0xea03 not received (EA session unconfirmed); continuing with the WiFi exchange")
            }
        }

        // ---- Subscription and HID: 0x6800 is an optional step ----
        // xcertplay sends StartHID after subscription; DiPlay dropped this step (waits for 0x5702 right after subscribing).
        // So we only wait briefly: on receiving 0x6800 we answer 0x6801, and after 2 seconds without it we just continue.
        awaitOptionalHid(deadline)

        // ---- WiFi configuration exchange (two protocol generations) ----
        // Legacy protocol (0x5702 -> 0x5703): the phone requests, the head unit answers with the AP in plaintext.
        // New protocol (0x4300 -> 0x4301): **the AP information comes with 0x4301** (WirelessCarPlayParams
        // of the newer Addendum), taken by the BMW iD9.
        //
        // Dispatch basis = [ReceiverProfile.wifiGen], **not** "does the list declare 0x5702":
        //  - NEW (both lists 10+ items yet no 0x57xx, BMW iD9 shape) -> skip 0x5702;
        //  - LEGACY (list explicitly names 0x5702/0x5703) -> send;
        //  - UNKNOWN (sparse list or missing declaration) -> **send as well**.
        // Evidence that UNKNOWN must send (real-car log of Audi MMI 3315, 2026-10-22): its receive list
        // has only 5 items and no 0x5702, yet on the same day on the same car the old version got 0x5703 back 122~368ms
        // after sending 0x5702, and AirPlay port 7000 was connectable 30ms after joining the network; the version that skipped
        // "because the list does not declare it" sent 0x5702 zero times over 4 session rounds, and the head unit AirPlay service
        // **never came up**
        // (all 45 connections ECONNREFUSED, _airplay._tcp was not advertised either).
        // A list is a declaration of "capabilities that can be implemented"; an incomplete list cannot disprove "unsupported". The
        // head unit sides of catplay and
        // xcertplay both put 0x5702 in the wireless list, so the spec requires declaring it but real head units
        // often omit it; head units ACK undeclared messages as usual (same-day log: 0x4e0d/0x4e0b/0x4e0e were not in the
        // list, and the head unit ACKed each one properly).
        val declaresReceiveWifi =
            accessoryDeclaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
        val declaresSendWifi = accessoryDeclaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)
        val legacyWifiExchange = wifiGen != ReceiverProfile.WifiGen.NEW
        if (!legacyWifiExchange) {
            log(
                "iap2 head unit profile says NEW (BMW iD9 shape); skipping 0x5702, AP information comes from 0x4301" +
                    " (list declares receive=${declaresReceiveWifi ?: "unknown"} send=${declaresSendWifi ?: "unknown"})",
            )
        } else if (declaresReceiveWifi == false || declaresSendWifi == false) {
            log(
                "iap2 lists do not declare 0x5702/0x5703 (receive=${declaresReceiveWifi ?: "unknown"} " +
                    "send=${declaresSendWifi ?: "unknown"}) but the profile says $wifiGen; still sending 0x5702" +
                    " (an incomplete list cannot disprove \"unsupported\"; head units usually process undeclared messages as usual)",
            )
        }
        val wifiFromExchange: WifiConfiguration? =
            if (!legacyWifiExchange) {
                null
            } else {
                send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                // Staged wait for 0x5703: resend the greeting first, then resend 0x5702, and only then give up and take 0x4300/0x4301.
                var wifiConfiguration =
                    expectOrNull(
                        Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                        wifiRescueDeadline(deadline, 1_500L),
                    )
                if (wifiConfiguration == null) {
                    sendDeviceStateGreetings(force = true)
                    log("iap2 0x5703 not here after 1.5s; resending the device status greeting and continuing to wait")
                    wifiConfiguration =
                        expectOrNull(
                            Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                            wifiRescueDeadline(deadline, 1_300L),
                        )
                }
                if (wifiConfiguration == null) {
                    send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                    log("iap2 0x5702 resent once (the first got only a link ACK)")
                    wifiConfiguration =
                        expectOrNull(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, deadline)
                }
                if (wifiConfiguration == null) {
                    log(
                        "iap2 0x5703 still missing after three rescue stages (greeting / 0x5702 resend); switching to 0x4300/0x4301 " +
                            "(new protocol carries AP info)",
                    )
                    null
                } else {
                    // Explicit assertion: assigned three times by the staged waits above, avoids relying on var smart casts
                    val parsed = parseWifiConfiguration(wifiConfiguration!!)
                    log(
                        "iap2 rx=0x5703 ssid=${parsed.ssid} channel=${parsed.channel} " +
                            "security=${parsed.securityType} psk=${if (parsed.passphrase.isEmpty()) "(empty)" else "***"}",
                    )
                    // Real-car deadlock antidote (confirmed by catplay CarPlayInviter): once the head unit discovers the phone's
                    // _carplay-ctrl._tcp on the WiFi network it actively sends GET /ctrl-int/1/connect to invite, and **sends
                    // 0x4301 only after a successful invitation**.
                    // So the controller must be told immediately here to bind WiFi + advertise ctrl (with the real Bluetooth MAC as
                    // the TXT id).
                    onWifiCredentials?.invoke(parsed)
                    parsed
                }
            }

        // ---- Device status greeting (0x4E0D/0x4E0A/0x4E09/0x4E0B -> 0x4300) ----
        // Audi MIB3 measured: without this batch of messages the head unit only gives a link ACK to 0x4300 and never produces 0x4301.
        // Idempotent: it may already have been sent early during the 0x5703 wait (see wifiRescueDeadline).
        sendDeviceStateGreetings()

        // ---- CarPlay session start ----
        // Two peer shapes:
        //  - Test bench DiPlay / some carmakers: after receiving 0x4300 they answer 0x4301 (with AirPlay port/IP/public key)
        // - Audi MIB3: the identification list only declares sending 0x5703/0xea03, **does not recognize 0x4300 and does not send
        // 0x4301**;
        //    the AirPlay endpoint goes through mDNS (_airplay._tcp) on WiFi. So 0x4301 gets only a short window,
        //    and on timeout we return a CarPlayEndpoint without an endpoint, to be synthesized by the controller via mDNS.
        // 0x4300 is also fire-and-forget: in the 2026-10-11 12:52 round the head unit was measured to stop ACKing it
        // (during the period when an event channel SYN flood confused it), and the link watchdog nearly killed itself again.
        sendFireAndForget(
            Iap2MessageIds.CARPLAY_AVAILABILITY,
            CarPlayAvailability.body(bluetoothMac, dualGroup = gmReceiver),
        )
        if (gmReceiver) {
            log(
                "iap2 generic head unit: 0x4300 uses the iPhone dual-group format (group 0 device identifier + group 1 " +
                    "Bluetooth MAC)",
            )
        }
        // If the head unit declaration does not include 0x4301, skip the wait directly (Audi measured wasting 10s every round):
        // a real iPhone does not wait for such head units either; the AirPlay endpoint is synthesized from mDNS/gateway.
        val declaresStartSession = accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)
        // When 0x5703 was not obtained (new-protocol head unit), 0x4301 is the **only** source of AP information, so give the
        // window in full;
        // legacy-protocol head units that really have 0x5703 keep the 3 second short window (answers are in milliseconds).
        val startSessionWindow =
            if (wifiFromExchange == null) START_SESSION_WAIT_NEW_PROTOCOL_NANOS else START_SESSION_WAIT_NANOS
        var startSession: Iap2Csm.Frame? = null
        if (declaresStartSession == false) {
            log("iap2 head unit does not declare it will send 0x4301; skipping the wait (AirPlay endpoint via mDNS/gateway)")
        } else {
            // A head unit may send only 0x4301 and not answer 0x5702 at all: after sending, wait one round first;
            // if 0x4301 arrived first in that time (the normal order of the new protocol), use it directly.
            startSession =
                expectOrNull(
                    Iap2MessageIds.CARPLAY_START_SESSION,
                    minOf(deadline, System.nanoTime() + startSessionWindow),
                )
        }
        val endpoint =
            if (startSession != null) {
                parseStartSession(startSession, wifiFromExchange)
            } else {
                // We must not assert "this head unit does not send it": the Audi MMI 9310 declared it, did send it, but late
                // by 2 min 35 s (2026-10-18 log). The wording must keep the expectation of "may be sent later",
                // otherwise a later investigator is misled by this line into "this path does not work on this car".
                log(
                    if (declaresStartSession == true) {
                        "iap2 0x4301 not here within the ${startSessionWindow / 1_000_000_000}s window" +
                            " (head unit declares it will send; may be sent later, and receiving it during keep-alive re-triggers " +
                            "pairing); " +
                            "using mDNS/gateway first"
                    } else {
                        "iap2 0x4301 not here (head unit does not declare it will send; AirPlay endpoint via mDNS _airplay._tcp)"
                    },
                )
                if (wifiFromExchange != null) {
                    // 0x5703 credentials exist and only 0x4301 is missing: leave the endpoint to mDNS/gateway synthesis
                    CarPlayEndpoint(
                        ssid = wifiFromExchange.ssid,
                        passphrase = wifiFromExchange.passphrase,
                        channel = wifiFromExchange.channel,
                        securityType = wifiFromExchange.securityType,
                        airPlayPort = 0,
                        deviceIdentifier = null,
                        publicKey = null,
                        sourceVersion = null,
                        ipAddresses = emptyList(),
                    )
                } else {
                    // No 0x5703. Three-level fallback, the first two do not tear the link down:
                    // 1. Known hotspot (obtained last round / already joined; Audi 2026-10-22: from round 2 the phone is already on the
                    //    head unit hotspot, so an empty endpoint lets the controller use mDNS/gateway, and a late 0x4301 still hot-updates)
                    val fallback = credentiallessEndpoint(knownCarSsid?.invoke())
                    if (fallback != null) {
                        log(
                            "iap2 no new credentials; reusing the known hotspot \"${fallback.ssid}\" and continuing" +
                                " (bind the current network then use mDNS/gateway; a late 0x4301 hot-updates the endpoint), not " +
                                "tearing the link down",
                        )
                        fallback
                    } else if (declaresStartSession == true) {
                        // 2. New-protocol head unit (GM/Cinemo, same family as BMW iD9: no 0x5702/0x5703, AP information only in
                        //    0x4301) declared it will send but it did not arrive within the window. Cadillac CT5 measured (2026-10-21 log):
                        // the 8s window was empty throughout, and over 6 loop rounds after teardown the head unit stayed silent;
                        // yet a real iPhone that forgot the hotspot
                        // still connects wirelessly: the head unit does send 0x4301 (credentials pushed over Bluetooth), just later
                        // / waiting for a precondition.
                        //    Precedent Audi MMI 9310: sent 2 min 35 s after Phase 1 ended. **Do not tear down**:
                        // return an empty endpoint, the controller enters a waiting state; a late 0x4301 re-triggers pairing via
                        // the keep-alive pump
                        //    handleLateStartSession -> onEndpointUpdate.
                        log(
                            "iap2 0x4301 not here within the window but the head unit declares it will send; not tearing down, " +
                                "returning an empty endpoint into keep-alive;" +
                                " a late 0x4301 will re-trigger pairing (Audi MMI 9310 precedent: 2 min 35 s)",
                        )
                        CarPlayEndpoint(
                            ssid = "",
                            passphrase = "",
                            channel = 0,
                            securityType = 0,
                            airPlayPort = 0,
                            deviceIdentifier = null,
                            publicKey = null,
                            sourceVersion = null,
                            ipAddresses = emptyList(),
                        )
                    } else {
                        // 3. No clue at all (not even the SSID is known and the head unit promised nothing); waiting on would only spin.
                        throw IOException(
                            "no head unit hotspot credentials: neither 0x5702 nor 0x4301 gave AP information this round, " +
                                "and this process has no known head unit hotspot (reselect this phone on the head unit first)",
                        )
                    }
                }
            }
        if (startSession != null) {
            logStartSessionRaw(startSession)
            log(
                "iap2 rx=0x4301 airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}",
            )
        }

        // Real-phone order: reply 0x4E0E after 0x4301 (transport identifier confirmation).
        // Audi measured: the head unit **accepts but never ACKs** this message (other packets are ACKed within milliseconds), so it
        // must be fire-and-forget,
        // otherwise the retransmit watchdog kills the whole Bluetooth link at +20s (the EA session / touch carrier is lost with it).
        sendFireAndForget(
            Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
            Iap2Csm.string(0, bluetoothMac),
        )
        log("iap2 0x4e0d/0x4e0e/0x4300: Phase 1 complete; got hotspot credentials and a (possible) AirPlay endpoint")

        // Next (Phase 2): join the hotspot -> mDNS resolves _airplay._tcp -> AirPlay main session.
        // When 0x5703 is absent the AP information comes from 0x4301 (already merged into the endpoint); during keep-alive,
        // a resent 0x5703/0x4301 from the head unit must be parsed with it, so it cannot be left empty.
        // An empty endpoint (declared it will send 0x4301 but it did not arrive within the window) must keep wifi=null: otherwise
        // during keep-alive
        // the `ap = wifi ?: fromSession` in parseStartSession would overwrite the AP information carried by a late 0x4301
        // with empty credentials, losing the only credential source.
        if (endpoint.ssid.isNotEmpty()) {
            this.wifi =
                wifiFromExchange ?: WifiConfiguration(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    securityType = endpoint.securityType,
                )
        }
        return endpoint
    }

    /**
     * Adapter-mode control session, ported from the field-tested reference sender
     * (unapple-phone bootstrap.rs/bootstrap_rx.rs Path::Carlinkit, smartBox-136F):
     * 0x1D00 → 0x1D01 → 0x1D02+0xAA00 → 0xAA01 → 0xAA02(20 B) → 0xAA03 → 0xAA05+0x5702 → 0x5703
     * → 0x4E0A/0x4E0B/0x4E0D/0x4E0E/0x4300. No deadlines: the box ends the link itself (it drops
     * Bluetooth after AirPlay's disableBluetooth), so this never times out on its own.
     */
    private fun runAdapter(): CarPlayEndpoint {
        val noDeadline = System.nanoTime() + ADAPTER_NO_DEADLINE_NANOS
        while (!channel.awaitReady(60_000L)) {
            if (!channel.isAlive) throw IOException("iAP2 link closed before the adapter finished link negotiation")
            log("ADAPTER: still waiting for the adapter's SYN / ACK (link held open)")
        }
        log(
            "ADAPTER: iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()} - " +
                "waiting $adapterIdentificationDelayMillis ms before 0x1D00",
        )
        if (adapterIdentificationDelayMillis > 0) {
            try {
                Thread.sleep(adapterIdentificationDelayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted before 0x1D00")
            }
        }
        if (!channel.isAlive) throw IOException("iAP2 link closed before 0x1D00")

        send(Iap2MessageIds.START_IDENTIFICATION)
        log("ADAPTER: tx=0x1d00 StartIdentification")
        val identification = adapterExpect(Iap2MessageIds.IDENTIFICATION_INFORMATION, noDeadline)
        logIdentification(identification)
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        log("ADAPTER: tx=0x1d02 identification-accepted + 0xaa00 request certificate")
        val certificate = adapterExpect(Iap2MessageIds.MFI_CERTIFICATE, noDeadline)
        log("ADAPTER: rx=0xaa01 certificate bytes=${certificate.body.size} (verification skipped)")
        // The reference: "the smartBox answers 20 B" - 20 first whatever MfiLengthMemory says
        runMfiChallenge(noDeadline, receiverKey = identityText(identification), forcedOrder = ADAPTER_MFI_ORDER)
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
        log("ADAPTER: tx=0xaa05 authentication-succeeded + 0x5702 request Wi-Fi configuration (no greetings first, no 0x6800 wait)")

        val wifiFrame = adapterExpect(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, noDeadline)
        val wifiConfiguration = parseWifiConfiguration(wifiFrame)
        this.wifi = wifiConfiguration
        log(
            "ADAPTER: rx=0x5703 ssid=${wifiConfiguration.ssid} channel=${wifiConfiguration.channel} " +
                "security=${wifiConfiguration.securityType} psk=${if (wifiConfiguration.passphrase.isEmpty()) "(empty)" else "***"}" +
                " - starting Wi-Fi join now, Bluetooth bootstrap continues",
        )
        // Queue the burst first (non-blocking), then hand off: the join starts while BT continues.
        sendAdapterBurst()
        onWifiCredentials?.invoke(wifiConfiguration)

        // 0x4301 may or may not come (the box's 0x1D01 declares only 0x5703); a later one is
        // handled by the keep-alive pump, a second 0x5703 is only logged there (no second burst).
        val startSession =
            if (accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION) == false) {
                log("ADAPTER: adapter doesn't declare 0x4301 - AirPlay endpoint via known SSID / mDNS / gateway")
                null
            } else {
                val received = expectOrNull(Iap2MessageIds.CARPLAY_START_SESSION, System.nanoTime() + START_SESSION_WAIT_NANOS)
                if (received == null) {
                    log("ADAPTER: no 0x4301 within ${START_SESSION_WAIT_NANOS / 1_000_000_000}s - using mDNS / gateway")
                }
                received
            }
        val endpoint =
            if (startSession != null) {
                logStartSessionRaw(startSession)
                parseStartSession(startSession, wifiConfiguration).also {
                    log("ADAPTER: rx=0x4301 airPlayPort=${it.airPlayPort} ips=${it.ipAddresses}")
                }
            } else {
                CarPlayEndpoint(
                    ssid = wifiConfiguration.ssid,
                    passphrase = wifiConfiguration.passphrase,
                    channel = wifiConfiguration.channel,
                    securityType = wifiConfiguration.securityType,
                    airPlayPort = 0,
                    deviceIdentifier = null,
                    publicKey = null,
                    sourceVersion = null,
                    ipAddresses = emptyList(),
                )
            }
        log("ADAPTER: Bluetooth bootstrap done - RFCOMM held open until the adapter drops it")
        return endpoint
    }

    /** Adapter-mode device-state burst after 0x5703, in the reference/iPhone order. */
    private fun sendAdapterBurst() {
        val transportId = AdapterTransportId.get(context)
        send(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, ADAPTER_LANGUAGE))
        val nowMillis = System.currentTimeMillis()
        val timezoneMinutes =
            java.util.TimeZone
                .getDefault()
                .getOffset(nowMillis) / 60_000
        send(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2Csm.param(0, u64Bytes(nowMillis / 1000L)) + Iap2Csm.u16(1, timezoneMinutes and 0xFFFF),
        )
        send(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1))
        send(
            Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
            Iap2Csm.string(0, bluetoothMac) + Iap2Csm.string(1, transportId),
        )
        send(
            Iap2MessageIds.CARPLAY_AVAILABILITY,
            Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothMac)),
        )
        log(
            "ADAPTER: tx burst 0x4e0a(lang=$ADAPTER_LANGUAGE) 0x4e0b(tz=${timezoneMinutes}min) 0x4e0d(available=1) " +
                "0x4e0e(bt=$bluetoothMac udid=$transportId) 0x4300",
        )
    }

    private fun adapterExpect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException("iAP2 link closed while waiting for 0x${messageId.toString(16)} (adapter mode)")

    /**
     * Phase 2 transition: after bring-up keep the link open and continuously record the control messages sent by the accessory
     * (such as the 0x5703 resent after 0x4E0E, the location request 0xFFFA, etc.) until the link drops.
     * In real wireless CarPlay this control channel coexists with the AirPlay session for the whole connection lifetime.
     */
    fun pumpIncomingForever() {
        log("SESSION keep-alive (link stays open, recording accessory messages)")
        // Throttle when the same message ID arrives consecutively: once the head unit is playing media it periodically sends
        // NowPlaying updates,
        // and logging each one blows up the file log (a file over 2MB is deleted outright, losing the genuinely useful context).
        var lastLoggedId = -1
        var repeatCount = 0
        while (true) {
            val frame = recvFrame(30_000L)
            if (frame == null) {
                if (!channel.isAlive) break
                continue
            }
            // Lifecycle messages must be handled before log throttling: 0x4301 may arrive after Phase 1 as the late signal
            // that "the head unit just opened its CarPlay window". If it followed the throttle logic it would either be treated as
            // keep-alive and only have its length logged, or be skipped by `continue` for having the same ID as the previous one;
            // both amount to
            // discarding it (root cause of Audi MMI 9310 "auto-disconnects 15 seconds after connecting", 2026-10-18).
            if (frame.messageId == Iap2MessageIds.CARPLAY_START_SESSION) {
                handleLateStartSession(frame)
            }
            if (frame.messageId == Iap2MessageIds.START_NOW_PLAYING_UPDATES) {
                startNowPlayingUpdates(frame)
            } else if (frame.messageId == Iap2MessageIds.STOP_NOW_PLAYING_UPDATES) {
                CarNowPlaying.unsubscribe(NOW_PLAYING_LINK, "head unit sent 0x5002")
            } else if (frame.messageId == Iap2MessageIds.START_ROUTE_GUIDANCE_UPDATES) {
                CarRouteGuidance
                    .subscribe(NOW_PLAYING_LINK, frame.body) { update -> sendCsmFrameOrFalse(update) }
                    .forEach { sendCsmFrameOrFalse(it) }
            } else if (frame.messageId == Iap2MessageIds.STOP_ROUTE_GUIDANCE_UPDATES) {
                CarRouteGuidance.unsubscribe(NOW_PLAYING_LINK, "head unit sent 0x5203")
            }
            if (frame.messageId == lastLoggedId) {
                repeatCount++
                if (repeatCount % 100 != 0) continue
            } else {
                if (repeatCount >= 99) {
                    log("iap2 previous 0x${lastLoggedId.toString(16)} repeated ${repeatCount + 1} times (same kind throttled)")
                }
                lastLoggedId = frame.messageId
                repeatCount = 0
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) keep-alive bytes=${frame.body.size}",
            )
        }
        CarNowPlaying.unsubscribe(NOW_PLAYING_LINK, "Bluetooth link dropped")
        CarRouteGuidance.unsubscribe(NOW_PLAYING_LINK, "Bluetooth link dropped")
        log("SESSION keep-alive ended (link dropped)")
    }

    /**
     * 0x5000 on the Bluetooth control session: answer with the current now-playing state and keep pushing 0x5001 on
     * changes (instrument cluster / media widget). Before, the subscription was only logged, so cars showed nothing.
     */
    private fun startNowPlayingUpdates(frame: Iap2Csm.Frame) {
        val artwork =
            ArtworkTransfer(
                send = { datagram -> channel.sendFileTransfer(datagram) },
                maxDatagram = { channel.maxFileTransferDatagram() },
                log = { line -> log(line) },
            )
        channel.setFileTransferHandler { bytes -> CarNowPlaying.post { artwork.onDatagram(bytes) } }
        val first =
            CarNowPlaying.subscribe(NOW_PLAYING_LINK, frame.body, artworkTransfer = artwork) { update ->
                sendCsmFrameOrFalse(update)
            }
        sendCsmFrameOrFalse(first)
    }

    /** Sends a complete CSM frame from any thread (now-playing updates come from the media feed thread). */
    private fun sendCsmFrameOrFalse(frame: ByteArray): Boolean =
        try {
            sendCsmFrame(frame)
            true
        } catch (error: IOException) {
            log("iap2 tx failed: ${error.message}")
            false
        }

    /**
     * 0x4301 received during keep-alive: parse it into an endpoint for the controller (hot update within the pairing window, or
     * redo after a failed previous round).
     * When there is no cached 0x5703 (new-protocol head unit: the BMW iD9 has only 0x4301) the AP information is parsed directly
     * from the wireless group of
     * 0x4301, which is the only credential source of such head units; it must not be ignored for "no 0x5703" like the old code did.
     */
    private fun handleLateStartSession(frame: Iap2Csm.Frame) {
        logStartSessionRaw(frame)
        val endpoint = parseStartSession(frame, wifi)
        if (wifi == null && endpoint.ssid.isNotEmpty()) {
            wifi =
                WifiConfiguration(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    securityType = endpoint.securityType,
                )
            log("iap2 0x4301 carries AP information: ssid=${endpoint.ssid}; cached for reuse by later 0x5703/0x4301")
        }
        if (endpoint.ssid.isEmpty()) {
            log("iap2 rx=0x4301 (late): no SSID parsed from the wireless group, ignoring this one")
            return
        }
        // The head unit may periodically resend the same 0x4301: details are logged only when the endpoint changes, avoiding log spam.
        if (endpoint.hashCode() != lastLateStartSessionSignature) {
            lastLateStartSessionSignature = endpoint.hashCode()
            log(
                "iap2 rx=0x4301 (late, after Phase 1) airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}" +
                    "; handing to the controller to hot-update the endpoint / retry pairing",
            )
        }
        onEndpointUpdate?.invoke(endpoint)
    }

    /**
     * The raw 0x4301 body is logged only once: differences of endpoint fields between head units (IPv4/port/public key present or
     * not) can only be settled with it.
     */
    private fun logStartSessionRaw(frame: Iap2Csm.Frame) {
        if (startSessionRawLogged) return
        startSessionRawLogged = true
        val body = frame.body
        val shown = if (body.size <= START_SESSION_RAW_LOG_BYTES) body else body.copyOf(START_SESSION_RAW_LOG_BYTES)
        val truncated = if (body.size > shown.size) " (first ${shown.size}/${body.size} bytes)" else ""
        log("iap2 0x4301 raw body$truncated=" + shown.joinToString("") { "%02x".format(it) })
    }

    // ---- Frame send/receive ----

    /**
     * MFi challenge response: try in turn the lengths "the peer can accept"; each failure (0xAA04 / silent timeout / link drop)
     * moves to the next one.
     * The base order and windows were decided by two-ended measurement (2026-10-09/10, none can be dropped):
     *  - **32B first**: test bench DiPlay (Carlinkit AuthV3 EC identity) **accepts exactly 32B only**,
     *    a wrong length drops the link at once (measured dropping within 70~350ms); its answer is fast (~0.2s) and a 3s window is enough.
     *  - **20B second**: the real-car Audi MIB3 answers 20B at once (same as catplay/extractor real-car recordings);
     *    its handling of 32B showed both shapes: 10-09 silent without dropping the link, 10-11 tearing it down.
     *  - **16B as a last resort**: some old accessories.
     *
     * The order **follows the per-head-unit persistent memory** ([MfiLengthMemory]; the baseline is only for cold start and as a fallback):
     * Audi MMI 3315 measured 2026-10-19: 20B silent for 6 seconds, 32B answered in 0.64s; with the baseline alone
     * ("real cars all 20B first") every connection waited 6 seconds for nothing. After a memory hit the last successful length goes first.
     * When nothing is answered we return without throwing: we skip signature verification, and whether 0x5702 gets answered
     * decides whether the peer lets us through (measured on a real car: 0xaa05 can still advance after no answer).
     */
    private fun runMfiChallenge(
        deadline: Long,
        prefer20First: Boolean = false,
        receiverKey: String,
        /** Fixed length order that ignores [MfiLengthMemory] (adapter mode: 20 B first). */
        forcedOrder: List<Int>? = null,
    ) {
        val baselineLengths =
            if (prefer20First) {
                listOf(MFI_ATTEMPTS[1].first, MFI_ATTEMPTS[0].first, MFI_ATTEMPTS[2].first)
            } else {
                MFI_ATTEMPTS.map { it.first }
            }
        val windowOf = MFI_ATTEMPTS.associate { it.first to it.second }
        val memory = if (forcedOrder != null) MfiLengthMemory.Entry() else MfiLengthMemory.load(context, receiverKey)
        val order = forcedOrder ?: MfiLengthMemory.orderAttempts(baselineLengths, memory)
        if (!memory.isEmpty) {
            log(
                "iap2 MFi per head unit memory ($receiverKey): ${MfiLengthMemory.describe(memory)}" +
                    " -> order this round ${order.joinToString("/")}B",
            )
        }
        for ((position, size) in order.withIndex()) {
            // Shorten the window of a length known to have been silent: it already failed once and need not wait the full time.
            val baseWindow = windowOf[size] ?: MFI_ATTEMPTS.last().second
            val windowMillis =
                if (size in memory.silent && size != memory.succeeded) {
                    minOf(baseWindow, MFI_SILENT_RETRY_WINDOW_MS)
                } else {
                    baseWindow
                }
            val challenge = ByteArray(size).also(random::nextBytes)
            send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
            log("iap2 tx=0xaa02 challenge=${size}B (attempt ${position + 1}, window ${windowMillis}ms)")
            val window = minOf(System.nanoTime() + windowMillis * 1_000_000L, deadline)
            var gotResponse = false
            var gotFailure = false
            while (true) {
                val remaining = (window - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                val frame = recvFrame(remaining)
                if (frame == null) {
                    if (!channel.isAlive) {
                        log(
                            "iap2 MFi link dropped during the challenge (${size}B, the peer does not accept this length); recorded " +
                                "as a rejected length of this head unit",
                        )
                        // Hard rejection: link-level evidence that overturns the memory "this length = last success" (prevents a
                        // misrecorded lock-in)
                        MfiLengthMemory.recordHardRejected(context, receiverKey, size)
                        return
                    }
                    continue
                }
                when (frame.messageId) {
                    Iap2MessageIds.MFI_RESPONSE -> {
                        log("iap2 rx=0xaa03 signature bytes=${frame.body.size} (verification skipped)")
                        gotResponse = true
                    }

                    Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                        log("iap2 rx=0xaa04 challenge ${size}B rejected")
                        gotFailure = true
                    }

                    else ->
                        log(
                            "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                                "(${Iap2MessageIds.nameOf(frame.messageId)})",
                        )
                }
                if (gotResponse || gotFailure) break
            }
            if (gotResponse) {
                MfiLengthMemory.recordSuccess(context, receiverKey, size)
                return
            }
            if (gotFailure) {
                MfiLengthMemory.recordRejected(context, receiverKey, size)
            } else {
                MfiLengthMemory.recordSilent(context, receiverKey, size)
            }
            if (position < order.size - 1) {
                log("iap2 MFi no answer to ${size}B, retrying with ${order[position + 1]}B")
            } else {
                log(
                    "iap2 MFi no answer to any challenge; skipping verification and sending 0xaa05 directly (0x5702 decides whether " +
                        "the head unit lets us through)",
                )
            }
        }
    }

    /** The device status greeting is sent once; with [force] = true it is sent one more round (remedy during the 0x5703 wait). */
    private var greetingsSent = false

    /** 0x1D01 manufacturer contains General Motors (Buick/Cadillac/Chevrolet, B233/E2xx head units). */
    private var gmReceiver = false

    /**
     * Device status greeting: 0x4E0D wireless session identifier / 0x4E0A language / 0x4E09 device name /
     * 0x4E0B device time / 0x4E0C device UUID. Must be sent immediately after 0xAA05.
     */
    private fun sendDeviceStateGreetings(force: Boolean = false) {
        if (greetingsSent && !force) return
        greetingsSent = true
        send(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1))
        send(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, "zh-CN"))
        send(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2Csm.string(0, deviceName))
        val epochSeconds = System.currentTimeMillis() / 1000L
        val timezoneMinutes =
            java.util.TimeZone
                .getDefault()
                .getOffset(System.currentTimeMillis()) / 60_000
        send(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2Csm.param(0, u64Bytes(epochSeconds)) +
                Iap2Csm.u16(1, timezoneMinutes and 0xFFFF) +
                Iap2Csm.u8(2, 0),
        )
        send(
            Iap2MessageIds.DEVICE_UUID_UPDATE,
            Iap2Csm.string(0, deviceUuid()),
        )
        log(
            "iap2 tx=0x4e0d/0x4e0a/0x4e09/0x4e0b/0x4e0c device status greeting" +
                " (name=$deviceName mac=$bluetoothMac)",
        )
    }

    /** Deadline used for staged waits: the smaller of the overall deadline and "wait another millis milliseconds". */
    private fun wifiRescueDeadline(
        deadlineNanos: Long,
        millis: Long,
    ): Long = minOf(deadlineNanos, System.nanoTime() + millis * 1_000_000L)

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
        sendCsmFrame(Iap2Csm.encode(messageId, joined))
    }

    /**
     * Writes one CSM frame in link-sized pieces. Serialized with [sendLock]: now-playing updates are sent from another
     * thread, and two interleaved multi-piece frames would corrupt both.
     */
    private fun sendCsmFrame(frame: ByteArray) {
        val messageId = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        synchronized(sendLock) {
            val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                if (!channel.sendControl(frame.copyOfRange(offset, end))) {
                    throw IOException("iAP2 link closed while sending 0x${messageId.toString(16)}")
                }
                offset = end
            }
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}")
    }

    private val sendLock = Any()

    /** Fire-and-forget variant of [send]: the link does not retransmit/die because the peer does not ACK this message (see 0x4E0E). */
    private fun sendFireAndForget(
        messageId: Int,
        body: ByteArray,
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        synchronized(sendLock) {
            val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                if (!channel.sendControl(frame.copyOfRange(offset, end), awaitAck = false)) {
                    throw IOException("iAP2 link closed while sending 0x${messageId.toString(16)}")
                }
                offset = end
            }
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')} (fire-and-forget)")
    }

    private fun expect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException("timed out waiting for 0x${messageId.toString(16)}")

    /**
     * Same as [expect], but returns null on timeout (for optional messages). Unexpected frames go into the holding area for later
     * consumption.
     *
     * Reads new frames only from [receivedFrames] and **does not re-read frames it just held**: the old implementation shared one
     * queue, so a pushed-back unexpected frame was immediately taken out again by this function, a livelock (see the pendingFrames
     * comment).
     * If the target frame is already in the holding area (arrived early), take it from there first so nothing is missed.
     */
    private fun expectOrNull(
        messageId: Int,
        deadlineNanos: Long,
    ): Iap2Csm.Frame? {
        pendingFrames.firstOrNull { it.messageId == messageId }?.let {
            pendingFrames.remove(it)
            return it
        }
        while (true) {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return null
            val frame = recvNewFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return null
                continue
            }
            if (frame.messageId == messageId) return frame
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}), unexpected, held for later consumption",
            )
            pendingFrames += frame
            if (pendingFrames.size > maxPendingFrames) {
                pendingFrames.removeFirst()
                if (!pendingOverflowLogged) {
                    pendingOverflowLogged = true
                    log(
                        "iap2 unexpected-frame holding area exceeded $maxPendingFrames entries; dropping the oldest (head unit sent " +
                            "too many subscription messages)",
                    )
                }
            }
        }
    }

    private fun awaitOptionalHid(deadline: Long) {
        // If the declaration list has no StartHID, do not wait (DiPlay/Audi measured never sending it; the old flow wasted 2s every round).
        if (accessoryDeclaresSend(Iap2MessageIds.START_HID) == false) {
            log("iap2 head unit does not declare it will send 0x6800 StartHID; skipping the 2s wait")
            return
        }
        val hidWindowNanos = minOf(System.nanoTime() + HID_WAIT_NANOS, deadline)
        while (true) {
            val remaining = (hidWindowNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) {
                log("iap2 0x6800 not received (this receiver does not send StartHID, continuing)")
                return
            }
            val frame = recvFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return // link dead: leave it to the later expect to report a clear error
                continue
            }
            when (frame.messageId) {
                Iap2MessageIds.START_HID -> {
                    log("iap2 rx=0x6800 start-hid bytes=${frame.body.size}")
                    send(
                        Iap2MessageIds.DEVICE_HID_REPORT,
                        Iap2Csm.u16(0, 0),
                        Iap2Csm.param(1, byteArrayOf(0)),
                    )
                    return
                }

                else ->
                    log(
                        "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)})",
                    )
            }
        }
    }

    /**
     * General frame fetch (MFi challenge / HID wait / keep-alive pump): consume held unexpected frames first
     * (keeps arrival order: held > newly parsed), then read new frames.
     */
    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        pendingFrames.firstOrNull()?.let {
            pendingFrames.removeFirst()
            return it
        }
        return recvNewFrame(timeoutMillis)
    }

    /** Take new frames only (already-parsed queue first, then read the socket); never returns frames from the holding area. */
    private fun recvNewFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        receivedFrames.firstOrNull()?.let {
            receivedFrames.removeFirst()
            return it
        }
        if (timeoutMillis <= 0) return null
        val bytes = channel.recvControl(timeoutMillis) ?: return null
        val frames = parser.offer(bytes)
        if (frames.isEmpty()) return null
        frames.forEachIndexed { index, frame -> if (index > 0) receivedFrames += frame }
        return frames.first()
    }

    // ---- Parsing ----

    /** Hotspot credentials parsed from 0x5703 (bind WiFi + advertise _carplay-ctrl immediately after receiving). */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
    )

    private fun parseWifiConfiguration(frame: Iap2Csm.Frame): WifiConfiguration {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return WifiConfiguration(
            ssid = params[1]?.value?.asStringOrEmpty() ?: "",
            passphrase = params[2]?.value?.asStringOrEmpty() ?: "",
            securityType =
                params[3]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
            channel =
                params[4]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
        )
    }

    /**
     * Parse 0x4301. AP information has two sources:
     *  - already obtained via 0x5703 (legacy-protocol head unit) -> use directly;
     *  - the head unit gives it only in the wireless group of 0x4301 (new-protocol head unit, e.g. BMW iD9) -> parse it from the group.
     *
     * The new-protocol wireless group is isomorphic to the CarPlay Addendum AccessoryWiFiConfigurationInformation
     * (0/1=SSID/password, 2=channel, 3=IP, 4=security type), but some implementations number from 1 (our 0x5703
     * parsing is exactly 1/2/3/4), so both are supported: the first two "non-IP" NUL-terminated strings are taken as SSID/password
     * in order,
     * and single-byte fields are classified by value range. Raw bytes are logged every time so real-car measurement can correct it
     * directly.
     */
    private fun parseStartSession(
        frame: Iap2Csm.Frame,
        wifi: WifiConfiguration?,
    ): CarPlayEndpoint {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val wirelessGroup = params[1]?.value
        val wireless = wirelessGroup?.let { Iap2Csm.parseParams(it) }
        val addresses =
            wireless
                ?.filter { it.id == 3 }
                ?.mapNotNull { param -> param.value.asStringOrEmpty()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
        log(
            "iap2 0x4301 top-level params: " +
                params.entries.joinToString(" ") { (id, param) -> "#$id(${param.value.size}B)" },
        )
        if (wireless != null) {
            log(
                "iap2 0x4301 wireless group params: " +
                    wireless.joinToString(" ") { param ->
                        "#${param.id}=" + param.value.joinToString("") { "%02x".format(it) }
                    },
            )
        }
        val fromSession = wireless?.let { parseWirelessAp(it) }
        val ap = wifi ?: fromSession
        if (wifi == null && fromSession != null) {
            log(
                "iap2 0x4301 carries AP: ssid=${fromSession.ssid} channel=${fromSession.channel} " +
                    "security=${fromSession.securityType} psk=${if (fromSession.passphrase.isEmpty()) "(empty)" else "***"}",
            )
        }
        return CarPlayEndpoint(
            ssid = ap?.ssid ?: "",
            passphrase = ap?.passphrase ?: "",
            channel = ap?.channel ?: 0,
            securityType = ap?.securityType ?: 0,
            airPlayPort = params[2]?.value?.asU32() ?: 0,
            deviceIdentifier = params[3]?.value?.asStringOrEmpty(),
            publicKey = params[4]?.value?.asStringOrEmpty(),
            sourceVersion = params[5]?.value?.asStringOrEmpty(),
            ipAddresses = addresses,
        )
    }

    /**
     * Parse AP information from the wireless group of 0x4301 (the only credential source of new-protocol head units).
     *
     * Only take NUL-terminated strings that "do not look like IP addresses": the first is the SSID, the second the password;
     * single-byte fields are classified by value range (channel 1..196, security type 0..4), the channel prefers id=2,
     * then whichever of id=4/id=3 is not the security type. Returns null when nothing can be parsed (the caller reports an error
     * and leaves a log).
     */
    private fun parseWirelessAp(group: List<Iap2Csm.Param>): WifiConfiguration? {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        val strings =
            group
                .sortedBy { it.id }
                .mapNotNull { param -> param.value.asStringOrEmpty() }
                .filter { it.isNotEmpty() && !ipv4.matches(it) && !it.contains(':') }
        val ssid = strings.getOrNull(0)?.takeIf { it.length <= 32 } ?: return null
        val passphrase = strings.getOrNull(1) ?: ""
        val singles = group.filter { it.value.size == 1 }.associate { it.id to (it.value[0].toInt() and 0xFF) }
        val security =
            singles[4]?.takeIf { it in 0..4 }
                ?: singles[3]?.takeIf { it in 0..4 }
                ?: 0
        val channel =
            singles[2]?.takeIf { it in 1..196 }
                ?: singles[4]?.takeIf { it in 1..196 && it != security }
                ?: singles[3]?.takeIf { it in 1..196 && it != security }
                ?: 0
        return WifiConfiguration(ssid = ssid, passphrase = passphrase, channel = channel, securityType = security)
    }

    /**
     * Parse EA protocols from the ExternalAccessoryProtocol components of 0x1D01 (parameter groups 10/11/12):
     * sub-parameter 0 = protocol number (u8), 1 = protocol name. Follows the same construction as DiPlay
     * (u8(0,index)+string(1,name)+u8(2,0)).
     */
    private fun parseEaProtocols(frame: Iap2Csm.Frame): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        for (groupId in intArrayOf(10, 11, 12)) {
            val value = params[groupId]?.value ?: continue
            val sub = Iap2Csm.parseParams(value).associateBy { it.id }
            val protocolId =
                sub[0]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: continue
            val name = sub[1]?.value?.asStringOrEmpty() ?: "?"
            out += protocolId to name
            log("iap2 0x1d01 EA component [$groupId] protocol=$protocolId name=$name")
        }
        return out
    }

    private fun logIdentification(frame: Iap2Csm.Frame) {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val name = params[0]?.value?.asStringOrEmpty()
        val model = params[1]?.value?.asStringOrEmpty()
        val manufacturer = params[2]?.value?.asStringOrEmpty()
        log("iap2 rx=0x1d01 head unit identity name=$name model=$model manufacturer=$manufacturer")
        // Params 6/7 = the lists of message IDs the head unit declares "it will send / it can receive".
        // Param 7 (it can receive) tells us directly whether it recognizes 0x4300; key evidence for real-car debugging.
        val sends = params[6]?.value?.asU16List()
        val receives = params[7]?.value?.asU16List()
        if (sends != null) {
            // Later optional messages (0x6800/0x4301) are judged by this list for whether they will come, avoiding idle waits in
            // fixed windows
            accessorySendIds = sends.toSet()
            log("iap2 0x1d01 head unit send list(${sends.size})=" + sends.joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 optional message declarations: StartHID(0x6800)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.START_HID)?.let { if (it) "will send" else "will not send" } ?: "unknown") +
                    " CarPlayStartSession(0x4301)=" +
                    (
                        accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)?.let {
                            if (it) "will send" else "will not send"
                        } ?: "unknown"
                    ),
            )
        }
        if (receives != null) {
            // Whether the head unit can receive 0x5702 decides whether to take the legacy protocol; if it cannot, we must take
            // 0x4300/0x4301
            // (the BMW iD9 receive list has no 0x5702, so the old flow waited in vain for the 0x5703 timeout every round).
            accessoryReceiveIds = receives.toSet()
            receiveListDeclared = true
            log("iap2 0x1d01 head unit receive list(${receives.size})=" + receives.joinToString(" ") { "0x%04x".format(it) })
            // Head unit profile (signal convergence point): identity + both lists -> one line of era/wifiGen verdict,
            // with the evidence attached; when a verdict is wrong, trace back from here which signal misled (iron rule: by
            // capability, not by brand)
            val profile =
                ReceiverProfile.classify(
                    ReceiverProfile.Signals(
                        name = name,
                        model = model,
                        manufacturer = manufacturer,
                        sendIds = sends?.toSet(),
                        receiveIds = receives.toSet(),
                    ),
                )
            wifiGen = profile.wifiGen
            linkGeneration =
                ReceiverProfile.linkGenerationOf(
                    ReceiverProfile.Signals(
                        name = name,
                        model = model,
                        manufacturer = manufacturer,
                        sendIds = sends?.toSet(),
                        receiveIds = receives.toSet(),
                    ),
                )
            log("IAPLINK: link generation verdict = $linkGeneration (marker strategy of the AirPlay stage dispatches on it)")
            log(ReceiverProfile.describe(profile))
            log(
                "iap2 0x1d01 protocol generation: request WiFi config (0x5702)=" +
                    (
                        accessoryDeclaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)?.let {
                            if (it) "can receive" else "cannot receive"
                        } ?: "unknown"
                    ) +
                    " reply WiFi config (0x5703)=" +
                    (
                        accessoryDeclaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)?.let {
                            if (it) "will send" else "will not send"
                        } ?: "unknown"
                    ) +
                    " -> this round " +
                    if (profile.wifiGen == ReceiverProfile.WifiGen.NEW) {
                        "no 0x5702 (waiting for " +
                            "0x4301)"
                    } else {
                        "send 0x5702 (even with an incomplete list)"
                    },
            )
        }
        log("iap2 0x1d01 raw body=" + frame.body.joinToString("") { "%02x".format(it) })
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
        return remaining.coerceIn(0, 60_000L)
    }

    private fun u64Bytes(value: Long): ByteArray =
        ByteArray(8) { index ->
            (value ushr (8 * (7 - index))).toByte()
        }

    /**
     * Device UUID: derived deterministically from the Bluetooth MAC, stable across sessions (the head unit identifies the device by
     * it).
     */
    private fun deviceUuid(): String =
        java.util.UUID
            .nameUUIDFromBytes(bluetoothMac.toByteArray())
            .toString()
            .uppercase()

    /**
     * NUL-terminated CSM string. **Strip all trailing NULs, not just one**:
     * Audi MMI 3315 was measured padding address fields in the wireless group to fixed length (IPv6 followed by 22 NULs,
     * IPv4 followed by 4), and stripping only one yields `"10.174.189.1\u0000\u0000\u0000\u0000"`.
     * Such a string has `contains('.')` true and `contains(':')` false, passes every "is it IPv4" check,
     * but handed to `InetSocketAddress` it becomes
     * `Unable to resolve host "10.174.189.1"` (NULs are invisible in logs, extremely hard to spot);
     * measured 2026-10-18: AirPlay pairing succeeded by luck with the endpoint refreshed via mDNS, but the video data port and
     * keepAlive still used the NUL-padded address, both failed, and the head unit tore the session down after 10 seconds.
     */
    private fun ByteArray.asStringOrEmpty(): String? {
        if (isEmpty() || last() != 0.toByte()) return null
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOf(end).decodeToString()
    }

    private fun ByteArray.asU32(): Int =
        if (size == 4) {
            ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
        } else {
            0
        }

    /**
     * Message list of params 6/7: a sequence of big-endian u16 (the receive/send capabilities the head unit declares in the
     * identification frame).
     */
    private fun ByteArray.asU16List(): List<Int>? {
        if (size < 2 || size % 2 != 0) return null
        return (0 until size step 2).map {
            ((this[it].toInt() and 0xFF) shl 8) or (this[it + 1].toInt() and 0xFF)
        }
    }
}

/**
 * Fallback endpoint when this round got no AP credentials at all; returns null if not even the SSID is known (the caller reports an error).
 *
 * Why it is needed: the head unit ctrl invitation may take 20+ seconds (real iPhone recording: the
 * `StartSessionHost` came 21 seconds after joining the network), and 0x4301 may not come at all in a round (head units to which
 * 0x5702 already gave the credentials). Tearing the link down then resets the head unit-side wait timer and the next round starts
 * from 0 again.
 * As long as we know which hotspot to join, the controller should keep waiting on the current network.
 *
 * `airPlayPort = 0` + empty address = "endpoint not self-sufficient", so the controller must first wait for the ctrl invitation
 * and then resolve
 * mDNS/gateway, and a late 0x4301 still hot-updates the endpoint.
 */
internal fun credentiallessEndpoint(cachedSsid: String?): Iap2SenderSession.CarPlayEndpoint? {
    val ssid = cachedSsid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return Iap2SenderSession.CarPlayEndpoint(
        ssid = ssid,
        passphrase = "",
        channel = 0,
        securityType = 0,
        airPlayPort = 0,
        deviceIdentifier = null,
        publicKey = null,
        sourceVersion = null,
        ipAddresses = emptyList(),
    )
}
