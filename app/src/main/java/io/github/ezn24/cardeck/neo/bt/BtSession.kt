package io.github.ezn24.cardeck.neo.bt

import android.content.Context
import io.github.ezn24.cardeck.neo.iap2.Iap2SenderSession
import io.github.ezn24.cardeck.neo.iap2.ReceiverProfile
import io.github.ezn24.cardeck.neo.transport.Iap2LinkChannel
import io.github.ezn24.cardeck.neo.util.DeviceIdentity

/**
 * Core flow of the BT control session (shared by Server/Client, previously two near-copies):
 * build the sender session -> Phase 1 deliver endpoint -> keep-alive pump (blocks until the link drops).
 * Socket wrapping / activeChannel registration / lifecycle teardown are handled by each of the two paths.
 */
internal fun Iap2LinkChannel.runSenderSession(
    context: Context,
    timeoutMillis: Long,
    log: (String) -> Unit,
    onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit,
    onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onLinkClosed: () -> Unit = {},
    /** Car hotspot SSID known to this process (reused when no new credentials arrive, avoids tearing down every round). */
    knownCarSsid: () -> String? = { null },
    /** Link generation (CLASSIC_MIB3/EVENT_LINK) decided from the 0x1D01 profile; called back after Phase 1 completes. */
    onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
) {
    var wifiHandedOff = false
    val session =
        Iap2SenderSession(
            this,
            { line -> log(line) },
            resolveBluetoothMac(context, log),
            DeviceIdentity.deviceName(context),
            { wifi ->
                wifiHandedOff = true
                onWifiCredentials(wifi)
            },
            onEndpointUpdate,
            knownCarSsid,
            // MFi challenge length is persisted per head unit identity (saves the 6s silent probe of the 20B attempt)
            context.applicationContext,
        )
    val endpoint = session.run(timeoutMillis)
    onGeneration(session.linkGeneration)
    onEndpoint(endpoint)
    // Phase 2 transition: keep the control channel alive and log accessory messages; AirPlay pairing is started by the onEndpoint callback
    session.pumpIncomingForever()
    if (adapterMode && wifiHandedOff) {
        // The box drops (and re-dials) Bluetooth itself once Wi-Fi is handed over (reference field
        // logs); that's the normal course here, not a dead round to release.
        log("ADAPTER: Bluetooth link closed by the adapter after the Wi-Fi handoff - pairing round left running")
        return
    }
    // Link dropped (keep-alive pump exited): tell the controller to release "session in progress". Otherwise the new endpoint
    // sent when the car reconnects after tearing the link would be blocked by a zombie round (Audi 3315, 2026-10-22, see
    // SessionController.onSenderLinkClosed)
    onLinkClosed()
}

/**
 * Resolve the real Bluetooth MAC at session start (used by 0x4300 / 0x4E0E).
 *
 * **Must forceRetry**: `DeviceIdentity` has a 10s quiet window after a failed resolve, and the BT session is often built in
 * the same millisecond Shizuku becomes ready. After the first resolve fails (Shizuku not ready yet), every call in the
 * next 10s returns the placeholder `02:00:00:00:00:00`. The Audi cross-checks the 0x4300 transport identifier against the
 * RFCOMM peer, and a placeholder makes it **silently drop** the message (link layer still ACKs, CSM layer stops answering).
 * Audi 0.1.7 log, 2026-10-19: the whole round used the placeholder and the car never sent 0x4301.
 */
private fun resolveBluetoothMac(
    context: Context,
    log: (String) -> Unit,
): String {
    val cached = DeviceIdentity.cachedBluetoothMac()
    if (cached != null) return cached
    val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
    if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
        log(
            "BT: WARNING could not resolve the real Bluetooth MAC (still the placeholder); the car checks the 0x4300 transport " +
                "identifier, " +
                "so the placeholder may stop it from sending 0x4301. Make sure Shizuku is running and authorized",
        )
    } else {
        log("BT: local Bluetooth MAC=$mac (same source as 0x4300/0x4E0E and the ctrl advertisement TXT id)")
    }
    return mac
}
