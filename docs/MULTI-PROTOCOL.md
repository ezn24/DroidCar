# Multi-protocol head units

Goal (user decision 2026-11-26): DroidCar should drive every head-unit projection protocol it can, not only wireless CarPlay.
The launcher, virtual displays, compositor, encoder and touch routing are protocol-neutral and are reused; each protocol only
adds its own transport, handshake and stream framing.

## Survey (2026-11-26)

| Protocol | Who uses it | Phone-side reference | License | Status for DroidCar |
| --- | --- | --- | --- | --- |
| Apple CarPlay (wireless) | most cars, adapters | own implementation | - | done |
| Baidu CarLife | Chinese-market cars (Toyota, Honda, VW China ...) | [FT](https://github.com/OneAboveAll1964/ft-app) `app.ft.carlife` | Apache-2.0 | in progress: protocol core ported (`carlife/`) |
| ICCOA CarLink (Xiaomi CarWith, OPPO, vivo phones) | newer Chinese cars | FT `app.ft.iccoa` (experimental, BLE + PIN + P-256) | Apache-2.0 | next after CarLife; FT itself is not confirmed past "lane close" on a Toyota |
| Android Auto | most cars | [AARE](https://github.com/prototux/AARE) (Kotlin, phone side of AAP) | GPL-3.0 (compatible) | planned; head units with Google's receiver check a Google-issued certificate, AARE's self-signed one works only on some |
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

## CarLife wire facts (from FT)

- Six channels: 1 CMD, 2 VIDEO, 3 MEDIA, 4 TTS, 5 VR, 6 CTRL. Command header 8 bytes (u16 length, u32 service id at 4),
  stream header 12 bytes (u32 length, u32 timestamp, u32 service id). Payloads are protobuf.
- Encryption: the head unit sends an RSA public key (`HU_RSA_PUBLIC_KEY_RESPONSE`); the phone wraps a random 16-character
  AES key with RSA/PKCS1 (`MD_AES_KEY_SEND_REQUEST`), then payloads are AES/ECB/PKCS5.
- Wireless bootstrap: Bluetooth RFCOMM, wireless info requests, Wi-Fi Direct (car is group owner), phone IP over Bluetooth,
  UDP discovery, then the car connects to the phone's TCP listeners. FT's docs say the full wireless spec is not public.

None of this is verified on a CarLife head unit by DroidCar yet.
