package io.github.ezn24.droidcar.iap2

/**
 * Message catalog from the sender (iPhone role) point of view in a wireless CarPlay session.
 *
 * Full wireless sequence (cross-checked with reference/xcertplay Iap2WirelessControlClient and the
 * capture flow in the reference/carplay-wifi-extractor README):
 *
 *  1. Bluetooth RFCOMM link (the two devices must first complete system-level Bluetooth pairing)
 *  2. Link layer handshake: peer sends FF55 SYN -> we send SYN-ACK -> both sides negotiate FF5A parameters
 *  3. phone -> accessory: StartIdentification(0x1D00)
 *     accessory -> phone: IdentificationInformation(0x1D01)   // head unit identity + capability lists
 *     phone -> accessory: IdentificationAccepted(0x1D02)
 *  4. MFi authentication (0xAA00 range, the accessory proves itself; the phone side may skip verification)
 *  5. phone -> accessory: RequestAccessoryWiFiConfiguration(0x5702)
 *     accessory -> phone: AccessoryWiFiConfiguration(0x5703)   // plaintext TLV: SSID/password/channel
 *  6. The phone joins the hotspot and resolves the accessory's _airplay._tcp via mDNS
 *  7. phone -> accessory: CarPlayAvailability(0x4300)
 *     accessory -> phone: CarPlayStartSession(0x4301)          // AirPlay port/address/public key
 *  8. phone -> accessory: WirelessCarPlayUpdate(0x4E0D), DeviceTransportIdentifier(0x4E0E)
 *  9. AirPlay main session (RTSP-style + H.264 stream + HID touch feedback), Phase 2
 */
object Iap2MessageIds {
    // ---- Identification ----
    const val START_IDENTIFICATION = 0x1D00 // phone -> accessory
    const val IDENTIFICATION_INFORMATION = 0x1D01 // accessory -> phone
    const val IDENTIFICATION_ACCEPTED = 0x1D02 // phone -> accessory
    const val IDENTIFICATION_REJECTED = 0x1D03 // accessory -> phone

    // ---- Wireless CarPlay ----
    const val CARPLAY_AVAILABILITY = 0x4300 // phone -> accessory: ask whether we can start
    const val CARPLAY_START_SESSION = 0x4301 // accessory -> phone: wireless parameters + AirPlay endpoint
    const val WIRELESS_CARPLAY_UPDATE = 0x4E0D // phone -> accessory: wireless availability notice
    const val DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E // phone -> accessory: Bluetooth/USB identifier notice

    // ---- Device status greeting (a batch a real iPhone sends before 0x4300) ----
    const val DEVICE_INFORMATION_UPDATE = 0x4E09 // phone -> accessory: device name
    const val DEVICE_LANGUAGE_UPDATE = 0x4E0A // phone -> accessory: language
    const val DEVICE_TIME_UPDATE = 0x4E0B // phone -> accessory: time (u64 epoch + u16 tz + u8 dst)
    const val DEVICE_UUID_UPDATE = 0x4E0C // phone -> accessory: UUID

    // ---- WiFi configuration exchange ----
    const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702 // phone -> accessory
    const val ACCESSORY_WIFI_CONFIGURATION = 0x5703 // accessory -> phone

    // ---- HID (Phase 2: head unit touch feedback) ----
    const val START_HID = 0x6800 // accessory -> phone
    const val DEVICE_HID_REPORT = 0x6801 // phone -> accessory (reply)
    const val ACCESSORY_HID_REPORT = 0x6802 // accessory -> phone (touch/knob events)
    const val STOP_HID = 0x6803

    // ---- MFi authentication (one-way: the phone verifies the accessory; the sender may skip verification and self-report success) ----
    const val MFI_REQUEST_CERTIFICATE = 0xAA00 // phone -> accessory
    const val MFI_CERTIFICATE = 0xAA01 // accessory -> phone (param 0 = certificate)
    const val MFI_REQUEST_CHALLENGE = 0xAA02 // phone -> accessory (param 0 = challenge, 16B)
    const val MFI_RESPONSE = 0xAA03 // accessory -> phone (param 0 = signature)
    const val MFI_AUTHENTICATION_FAILED = 0xAA04 // phone -> accessory
    const val MFI_AUTHENTICATION_SUCCEEDED = 0xAA05 // phone -> accessory (self-reported result)

    // ---- Subscription requests sent by the accessory during bring-up (receive and ignore) ----
    const val START_NOW_PLAYING_UPDATES = 0x5000
    const val NOW_PLAYING_UPDATE = 0x5001 // phone -> accessory
    const val STOP_NOW_PLAYING_UPDATES = 0x5002
    const val START_ROUTE_GUIDANCE_UPDATES = 0x5200
    const val ROUTE_GUIDANCE_UPDATE = 0x5201 // phone -> accessory
    const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202 // phone -> accessory
    const val STOP_ROUTE_GUIDANCE_UPDATES = 0x5203
    const val ROUTE_GUIDANCE_LANE_GUIDANCE = 0x5204 // phone -> accessory
    const val START_POWER_UPDATES = 0xAE00
    const val START_COMMUNICATIONS_UPDATES = 0x4157
    const val START_CALL_STATE_UPDATES = 0x4154

    // ---- ExternalAccessory session (link session 2 carries EA data; the likely carrier of touch HID) ----
    const val EA_OPEN_SESSION = 0xEA00 // phone -> accessory (explicitly in the Audi receive list)
    const val EA_CLOSE_SESSION = 0xEA01 // phone -> accessory
    const val EA_SESSION_STATUS = 0xEA03 // accessory -> phone (explicitly declared in the Audi send list)

    fun nameOf(messageId: Int): String =
        when (messageId) {
            START_IDENTIFICATION -> "StartIdentification"
            IDENTIFICATION_INFORMATION -> "IdentificationInformation"
            IDENTIFICATION_ACCEPTED -> "IdentificationAccepted"
            IDENTIFICATION_REJECTED -> "IdentificationRejected"
            CARPLAY_AVAILABILITY -> "CarPlayAvailability"
            CARPLAY_START_SESSION -> "CarPlayStartSession"
            WIRELESS_CARPLAY_UPDATE -> "WirelessCarPlayUpdate"
            DEVICE_TRANSPORT_IDENTIFIER -> "DeviceTransportIdentifier"
            REQUEST_ACCESSORY_WIFI_CONFIGURATION -> "RequestAccessoryWiFiConfiguration"
            ACCESSORY_WIFI_CONFIGURATION -> "AccessoryWiFiConfiguration"
            START_HID -> "StartHID"
            DEVICE_HID_REPORT -> "DeviceHIDReport"
            ACCESSORY_HID_REPORT -> "AccessoryHIDReport"
            STOP_HID -> "StopHID"
            EA_OPEN_SESSION -> "StartEASession"
            EA_CLOSE_SESSION -> "StopEASession"
            EA_SESSION_STATUS -> "EASessionStatus"
            START_NOW_PLAYING_UPDATES -> "StartNowPlayingUpdates"
            NOW_PLAYING_UPDATE -> "NowPlayingUpdate"
            STOP_NOW_PLAYING_UPDATES -> "StopNowPlayingUpdates"
            START_ROUTE_GUIDANCE_UPDATES -> "StartRouteGuidanceUpdates"
            ROUTE_GUIDANCE_UPDATE -> "RouteGuidanceUpdate"
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> "RouteGuidanceManeuverUpdate"
            STOP_ROUTE_GUIDANCE_UPDATES -> "StopRouteGuidanceUpdates"
            ROUTE_GUIDANCE_LANE_GUIDANCE -> "RouteGuidanceLaneGuidanceInformation"
            START_POWER_UPDATES -> "StartPowerUpdates"
            START_COMMUNICATIONS_UPDATES -> "StartCommunicationsUpdates"
            START_CALL_STATE_UPDATES -> "StartCallStateUpdates"
            else -> "0x%04X".format(messageId)
        }
}
