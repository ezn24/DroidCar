# Multi-protocol head units

Goal (user decision 2026-11-26): DroidCar should drive every head-unit projection protocol it can, not only wireless CarPlay.
The launcher, virtual displays, compositor, encoder and touch routing are protocol-neutral and are reused; each protocol only
adds its own transport, handshake and stream framing.

## Survey (2026-11-26)

| Protocol | Who uses it | Phone-side reference | License | Status for DroidCar |
| --- | --- | --- | --- | --- |
| Apple CarPlay (wireless) | most cars, adapters | own implementation | - | done |
| Baidu CarLife | Chinese-market cars (Toyota, Honda, VW China ...) | [FT](https://github.com/OneAboveAll1964/ft-app) `app.ft.carlife` | Apache-2.0 | experimental: wireless session implemented (`carlife/`), not yet tried on a head unit |
| ICCOA CarLink (Xiaomi CarWith, OPPO, vivo phones) | newer Chinese cars | FT `app.ft.iccoa` (experimental, BLE + PIN + P-256) | Apache-2.0 | experimental: ported (`iccoa/`, FT's 103 unit tests pass), not yet tried on a head unit |
| Android Auto | most cars | [AARE](https://github.com/prototux/AARE) (Kotlin, phone side of AAP) | GPL-3.0 (compatible) | experimental: AARE's AAP module ported (`:aap`, its 222 tests pass), USB, wireless and adb bench; not yet tried on a head unit |
| SmartDeviceLink / Ford AppLink | Ford, Lincoln, some Toyota | [sdl_java_suite](https://github.com/smartdevicelink/sdl_java_suite) (official) | BSD-3 | candidate: projection apps stream video; production head units may need an app policy / OEM approval |
| Huawei HiCar | Chinese cars | none public | - | not possible without a spec |
| WeLink / EasyConnection / Carbit | aftermarket head units | none public | - | not possible without a spec |
| MirrorLink | old cars | CCC discontinued it (2021) | - | not pursued |

## Architecture

- `SessionController` today owns one protocol (CarPlay over AirPlay). New protocols get their own session object per protocol
  (`carlife/CarLifeSession`, later `aa/`, `iccoa/`), started from the connection page by the head unit's advertised
  service (CarLife: Bluetooth RFCOMM UUID / UDP 7999; AA: Bluetooth / USB AOA; ICCOA: BLE GATT service).
- Video: the session asks `VirtualLauncher.attach` for the composed canvas exactly like CarPlay, with its own encoder sink
  (CarLife: H.264 frames as `VIDEO_DATA` on channel 2, AES-128/ECB once the key exchange finished).
- Touch: protocol touch events are mapped to `TouchRouter.onContact` contacts in stream pixels.
- Audio: the same Shizuku REMOTE_SUBMIX capture; CarLife sends PCM on the media channel (`MEDIA_INIT` / `MEDIA_DATA`).

## ICCOA CarLink in DroidCar (2026-11-26)

- Ported from FT: BLE discovery (`IccoaDiscovery`), GATT bootstrap + PIN + AP join + signed P-256 auth on tcp 57209
  (`IccoaSession`, `IccoaAuth`, `IccoaControl`), lanes 57219-57259 with heartbeat, WFD-style RTSP on 7236
  (`IccoaRtsp`), RTP/MPEG-TS video + AAC audio (`IccoaMediaTransport`, `IccoaTsMuxer`, `IccoaAacEncoder`), UIBC input
  on 4321 (`IccoaInputServer`). FT's 19 matching test classes (103 tests) came along and pass. Only FT's DiagLog,
  CarTouch and vehicle-profile id are shimmed (`IccoaShims.kt`); the status strings are resources in all three
  languages.
- Network types (OpenCarLink manual: 1001 Wi-Fi Direct with the car as group owner, 1002 SoftAP, 1003 both). FT took
  only 1002; DroidCar also joins 1001 with `WifiP2pConfig.Builder().setNetworkName/setPassphrase/
  setGroupOperatingFrequency` (Android 10+, name must be DIRECT-xy-...), the same "fast connection" a ColorOS phone
  makes on p2p0. That path has no `Network`, so sessions run on `IccoaLink` (`Wifi(network)` binds through the
  Network, `Direct(localAddress)` binds sockets to the phone's p2p address). 1003 uses SoftAP. (unverified)
- Channel directions match OpenCarLink's table: the car listens on 57209 (auth) and the RTP port from RTSP SETUP
  (15550 there); the phone listens on 57219 control, 7236 RTSP, 4321 UIBC, 57229 media, 57239 sensor, 57249 cert.
- Not ported: FT's remembered-car recovery tiers, GPS / gear sensors, car microphone and navigation over ICCOA.
- `IccoaController` (DroidCar's own): starts with the shared connect switch, picks the remembered car or the
  strongest connectable receiver with ICCOA evidence, the phone shows a PIN dialog when the car shows its 6-digit
  code (`MainActivity.maybeAskIccoaPin`), the car's PLAY builds `ProjectionEncoder` (H.264 Baseline, CBR, 8 Mbps,
  <= 30 fps, capped to 1920x1080) + `HiddenDisplaySource`, music through `SystemAudioTap` (48 kHz stereo PCM -> AAC
  in the transport). UIBC keys arrive as Android key codes (home / back / D-pad -> knob routing, media keys, search
  -> voice). The car address is remembered after the first projection.
- FT's own ICCOA was not confirmed past "lane close" on a Toyota; this port is unverified the same way. Bench:
  OpenCarLink's Android receiver reaches AUTH only, its PC receiver is USB AOA (not supported by DroidCar yet).
- First protocol wins applies to all three: CarPlay, CarLife and ICCOA stop the others' waits when they start
  projecting, and none takes over another's running projection.

## Android Auto in DroidCar (2026-11-27)

- Protocol: AARE's `aap` module as the Gradle module `:aap` (pure JVM, protobuf-javalite 4.36.2 with the protobuf
  Gradle plugin 0.9.4, kotlinx-coroutines 1.9.0 for Kotlin 2.0). Ported unchanged apart from replacing fully
  qualified `aap_protobuf.` names with imports, which Kotlin 2.0 does not resolve inside `net.prototux.aare.aap`.
  AARE tried it in 59 cars (25 fine, 19 with issues).
- Ways in (`aa/AndroidAutoController`):
  - USB: the head unit switches the phone into accessory mode; `aa/UsbAccessoryActivity` (filter
    `res/xml/aa_accessory_filter.xml`, models "Android Auto" / "Android Open Automotive Protocol") hands the
    accessory over. Starts even without the connect switch, since plugging in is the user's action.
  - Wireless: with the shared connect switch, a 5 s loop looks for a connected bonded head unit offering the
    wireless Android Auto service, then AARE's `WirelessConnector` (RFCOMM, `WirelessBootstrap`, the car's Wi-Fi,
    TCP to its AAP server).
  - Bench: AARE's developer head-unit server on `@aare_hu` (adb peers only), so AARE's desktop head unit works from
    Windows: `adb forward tcp:5277 localabstract:aare_hu`, then AARE's `./headunit.sh` / `:headunit` module.
    Google's Desktop Head Unit (Android Studio SDK) works too, but its usual instruction `adb forward tcp:5277
    tcp:5277` targets the official app's TCP server, which DroidCar does not run: forward to
    `localabstract:aare_hu` instead. The server only exists while the connect switch is on, and a car-side end of a
    projection ends the whole connection (`SessionController.onOtherProtocolEnded`, unless "wait for reconnect").
- Channels opened: main video, PCM audio sinks except telephony (calls stay on the car's Bluetooth like with the
  official phone), input, sensors (ignored), Wi-Fi projection. Not yet: instrument cluster video,
  navigation status, media playback metadata, Bluetooth pairing channel.
- Video: the chosen configuration is the largest visible picture with a short side up to 1080 (AARE's rule without
  its per-car quirks). Frame = resolution, visible area = resolution minus margins, centred; the launcher is drawn
  at the visible size and `render/InsetSurface` places it in the frame. H.264 Baseline, bitrate w*h*fps/8 clamped
  2-12 Mbps. Frames need 10 bytes of headroom (message id + timestamp).
- Touch: reported over the visible area in the declared panel's units; a panel larger than the visible area with
  the same shape means visible-area pixels, a 0x0 panel means visible-area pixels (AARE's TouchMapper rules).
  Keys use Android key codes (home / back / D-pad -> knob routing, media keys, search -> voice).
- Car microphone: the head unit's microphone channel is opened while any other app records (polled every 400 ms
  through the Shizuku service's `activeMicApps`, like CarPlay's `CarMicController`); its 16-bit little-endian PCM goes
  straight into `startMicInjection`, closed 2 s after the last recorder stops. (verify on DHU / a car)
- Audio: `SystemAudioTap` 48 kHz stereo PCM into the media sink; focus GAIN and stream start with the first
  non-silent packet, stop after 1 s of silence.
- Certificates (`aa/cert`, AARE's store): a generated self-signed one always, plus imported PEM files or the
  Google-issued certificate downloaded from iscle/android-auto-certificates (user decision 2026-11-27; the user
  taps "Download" in the Car page's "Android Auto certificates" row; AARE's checks of issuer and key match apply).
  A refusal moves the next attempt to the next certificate.

## Bench receivers (head-unit side, for testing DroidCar as the phone)

| Tool | Protocol | What it is | License | Use |
| --- | --- | --- | --- | --- |
| [carlife_pc_tool](https://github.com/MeshHun/carlife_pc_tool) | CarLife | Python head unit (Windows), Wi-Fi via udp 7999 + 7 TCP channels, or USB AOA; written from Baidu's vehicle SDK | none stated | CarLife bench; facts only |
| [OpenCarLink PC](https://github.com/xhcoding1998/Carlink) | ICCOA CarLink | Python head unit over USB AOA (AUTH/CONTROL mux, P-256 ECDH/ECDSA, PIN HMAC, HKDF, AES-GCM, RTSP/WFD, RTP PT33 MPEG-TS, UIBC), plus an Android receiver APK that gets as far as AUTH over Wi-Fi Direct + BLE | none stated | ICCOA bench; facts only |
| [MiAuto](https://github.com/PiggyBackBayonet/MiAuto) | ICCOA CarLink | Android head-unit app on a closed vendor ICCOA SDK (`icar-sdk.dex`) | AGPL-3.0 | real-phone behaviour notes (state codes, reconnect backoff); no protocol source |

carlife_pc_tool finding (2026-11-26): its state machine, built from Baidu's vehicle SDK, sends `VIDEO_ENCODER_INIT`
only after the phone's `MD_AUTHEN_RESULT`, also on protocol 4.0. FT skipped that message on 3.2 / 4.x cars, so DroidCar
now always sends it after `STATISTIC_INFO`. The tool also opens a 7th channel (car data, tcp 9440) that DroidCar does
not listen on yet; the tool carries on without it.

## CarLife wire facts (from FT)

- Six channels: 1 CMD, 2 VIDEO, 3 MEDIA, 4 TTS, 5 VR, 6 CTRL. Command header 8 bytes (u16 length, u32 service id at 4),
  stream header 12 bytes (u32 length, u32 timestamp, u32 service id). Payloads are protobuf.
- Encryption: the head unit sends an RSA public key (`HU_RSA_PUBLIC_KEY_RESPONSE`); the phone wraps a random 16-character
  AES key with RSA/PKCS1 (`MD_AES_KEY_SEND_REQUEST`), then payloads are AES/ECB/PKCS5.
- Wireless bootstrap: Bluetooth RFCOMM, wireless info requests, Wi-Fi Direct (car is group owner), phone IP over Bluetooth,
  UDP discovery, then the car connects to the phone's TCP listeners. FT's docs say the full wireless spec is not public.

## CarLife in DroidCar (2026-11-26)

- No switch of its own (user decision 2026-11-26): CarLife listens while the normal connection runs.
  `SessionController.startConnection` starts `CarLifeController` next to the CarPlay flow and `disconnect` stops it
  (an internal CarPlay teardown leaves a running CarLife projection alone). Needs BLUETOOTH_CONNECT and
  NEARBY_WIFI_DEVICES (13+) / fine location; without them it logs once and does not listen.
- First protocol wins: when CarLife negotiates video it calls `SessionController.stopCarPlayForOtherProtocol` (CarPlay
  wait ends before CarLife attaches its displays, since that teardown resets touch and the dim cover); when CarPlay's
  displays come up it calls `CarLifeController.stop()`.
- The UDP beacon goes to hotspot / Wi-Fi Direct interfaces every 1.5 s and to an ordinary Wi-Fi network every 6 s,
  never while CarPlay streams.
- `CarLifeBluetooth`: RFCOMM server on the CarLife UUID and SPP, sends `MD_WIRELESS_INFO_REQUEST`, takes the car's
  Wi-Fi Direct name from `HU_TARGET_INFO`, answers `HU_IP_REQUEST` with `MD_WIFI_IP` once the phone has a p2p address
  (never while CMD is connected: the car SDK restarts its TCP client on every `MD_WIFI_IP`).
- `CarLifeWifiDirect`: joins the group by name (push-button WPS, owner intent 0); FT's multi-tier recovery is not
  carried over. `CarLifeDiscovery`: "CarlifeHost" beacon on udp 7999 every 1.5 s and the ready answer on udp 8999, so a
  head unit on the phone's hotspot also finds it.
- `CarLifeLink`: TCP listeners 7240 / 8240 / 9240 / 9241 / 9242 / 9340 (CMD, VIDEO, MEDIA, TTS, VR, CTRL), one writer
  thread per channel; a new CMD connection drops the whole old group.
- `CarLifeSession`: version match, MD_INFO, module status, encryption handshake (on request or 2.5 s after the match
  without VIDEO_ENCODER_INIT), video init / start / pause / reset / frame rate, touch (stream pixels), hardware keys,
  music module control. Car keys follow Baidu's key table (1 home, 14 back, 9 media, 15 / 16 seek, 20-24 OK and
  directions, 2 / 3 call keys, 33 voice; FT's mapping of 14 as play / pause was wrong for that table).
- Cluster / HUD: `CarLifeNavigation` sends `NAVI_NEXT_TURN_INFO` (action, Baidu turn code, road, total, remain, PNG
  arrow from `AltScreenCard.maneuverIcon`) from `CarRouteGuidance.listen`, so it needs the cluster navigation setting
  like CarPlay. Navigation is offered in the data-subscription reply; a car that never sends a subscription request
  (carlife_pc_tool) gets turn info by default. Song info is not provided yet.
- Video: `CarLifeEncoder` (H.264 Baseline, Annex B, key frame every second, Baidu's bitrates per width) feeds
  `CarLifeVideoFeed`; stream size follows Baidu's table (`CarLifeSession.streamSize`), only protocol 3.2 / 4.x cars get
  1920 wide. `CarLifeController` attaches the same `HiddenDisplaySource` launcher as CarPlay to the encoder Surface.
- Audio: Shizuku REMOTE_SUBMIX 48 kHz stereo PCM, 20 ms packets (`CARLIFE: music packets sent` in the log);
  carlife_pc_tool only drains the media channel and never plays it; all-zero packets are skipped so the media stream
  pauses after 750 ms of silence and the car's own sources can play.
- CarPlay and CarLife exclude each other (`startConnection` refuses while CarLife streams, CarLife refuses video while
  CarPlay streams).

None of this is verified on a CarLife head unit by DroidCar yet.
