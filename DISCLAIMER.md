# Disclaimer

> This file comes from the same source as the disclaimer the app shows on first start (the app shows it in your language).
> It is the repository-level copy, to be handed to users together with any download, distribution or redistribution.

## Unofficial project

This project (the "app") is an **unofficial, personal, experimental project**. It is **not affiliated** with Apple Inc.,
Google LLC, Baidu, the ICCOA alliance, any car manufacturer or any of their affiliates, and has no authorization,
certification or endorsement of any kind.

The app is **not** an MFi (Made for iPhone) product and is not an official implementation of the CarPlay, Android Auto,
CarLife or ICCOA CarLink ecosystems.

## Trademarks

- **CarPlay** and **AirPlay** are trademarks of Apple Inc.
- **Android Auto** is a trademark of Google LLC.
- **CarLife** is a trademark of Baidu.
- The name "DroidCar" contains none of these trademarks. CarPlay, AirPlay, Android Auto, CarLife and CarLink appear in
  this project **only to describe what the app is compatible with** (the app tries to talk to head units that support
  them, in the phone's role). This is descriptive use and implies no affiliation, sponsorship or endorsement.
- The project **does not use** the logos, icons or certification marks of Apple, Google, Baidu or any car maker.
- Other product names and trademarks mentioned belong to their respective owners.

## Driving safety

**The app is not a safety-critical system and does not replace the car's own navigation and instruments.**

- Connect, pair and change settings **only while parked**.
- While driving, only a passenger may operate it; the driver must not hold or operate the phone.
- The wireless link can **drop at any time** (interference, phone sleep, hotspot changes, Bluetooth reconnects): the
  picture freezes or goes black, touch stops working or navigation stops.
- A drop while driving can mean a missed turn, a lost route or distraction. Always keep the car's own navigation as a
  safety fallback.

## What has been tested

The app has been checked only on these combinations:

| Role | Device |
|---|---|
| Phone | Honor Magic7 Pro (Android 17), Samsung Galaxy S24 |
| CarPlay head unit | Audi MIB3 (real car); benches: ChairPlay, WinPlay (Windows), DiPlay on a Redmi K40 (Android 13) |
| Android Auto head unit | Benches only: Google's Desktop Head Unit, AARE's desktop head unit |
| Baidu CarLife head unit | Bench only: carlife_pc_tool |
| ICCOA CarLink head unit | Not tested |

**Other phones, Android versions and cars are not verified.** The app may not work at all, or show a broken picture,
misplaced touches or repeated disconnects. Android Auto, CarLife and ICCOA support is experimental. "Force landscape"
for apps needs Android 14 or later.

## System permissions

To work, the app asks for and uses:

- **Shizuku (shell permission)**: creating trusted virtual displays, injecting touch, writing system compatibility
  settings (such as forcing apps to landscape), reading window and display state. It runs no commands unrelated to the
  app's own functions. Shizuku is an independent open-source project ([website](https://shizuku.rikka.app/) /
  [GitHub](https://github.com/RikkaApps/Shizuku)); you manage what it may do in Shizuku itself. The app gets only what
  the shell identity can do, nothing more. Shizuku is also used to answer / end calls and mute the microphone, to send
  the other party's voice to the car, to inject the car's microphone as the phone's microphone (assistant and calls),
  to capture the phone's audio for the car, and to turn on the app's notification access and navigation accessibility
  service.
- **Notification access**: shows what is playing on the car launcher; reads call notifications (caller name, photo,
  answer / end buttons) for the car's call card; reads Google Maps navigation notifications for turn prompts on the
  cluster; optional notification banners on the car. Notification content is never logged or uploaded.
- **Microphone**: Android requires it to capture the sound the phone plays for the car; during voice or calls the car's
  microphone replaces the phone's. Nothing is recorded to storage or uploaded.
- **Location / nearby Wi-Fi devices**: Android requires them to join the car's Wi-Fi hotspot or Wi-Fi Direct group
  (location on Android 12, nearby devices from 13). The app does not read or store your location.
- **Bluetooth (connect and scan)**: pairing with the car and setting up the connection (pairing only, no Bluetooth audio
  connection); scanning for ICCOA CarLink head units over Bluetooth LE.
- **Screen capture (projection) permission**: only to capture audio when Shizuku cannot.
- **Display over other apps**: starting the connection in the background when the car's Bluetooth connects, and the
  dim cover on the phone screen.
- **Accessibility service (optional, off by default)**: reads only the navigation text and turn / lane arrows on screen
  in Amap and Waze, and only while a car subscribes to navigation, for turn prompts on the cluster.
- **Notifications**: the ongoing notification showing the connection.

Pairing data, target device names, wallpapers, settings and Android Auto certificates stay on the phone and are not
uploaded to any server. The app contains no analytics or advertising SDK. The only network requests the app makes on its
own initiative are the ones you start: the optional Android Auto certificate download (see below).

## Android Auto certificates

Most factory Android Auto head units only accept a certificate issued by Google. DroidCar ships no such certificate.
At your request (a button in the Car page) it can download one from a third-party repository
([iscle/android-auto-certificates](https://github.com/iscle/android-auto-certificates)) that publishes certificates
extracted from the Android Auto app, or you can import your own files. Using such a certificate may break the terms of
the services involved; you decide whether to use it and carry the risk.

## No warranty

The app is provided "as is", without warranty of any kind, express or implied, including but not limited to the
warranties of merchantability, fitness for a particular purpose and non-infringement.

To the maximum extent permitted by applicable law, the authors and contributors are not liable for any direct or indirect
loss from using or being unable to use the app, including but not limited to: dropped connections, interrupted
navigation or calls, data loss, device faults, vehicle system faults, and accidents or losses caused by operating the
app or being distracted while driving.

Whether you use the app, how you configure and operate it and in which situations is entirely your decision and your
risk. If you do not agree with any of the above, stop using the app and uninstall it.

## Third-party code and licenses

- The repository's `reference/` folder (if present) holds **local reference material**, excluded in `.gitignore` and not
  distributed with the repository.
- CarPlay is an independent implementation, written from public protocol formats and public research.
- Some code is ported under its license, with the original notices kept in [NOTICE](NOTICE): Baidu CarLife and ICCOA
  CarLink from FT (Apache-2.0), the Android Auto protocol module from AARE (GPL-3.0) with protocol buffer definitions from
  aasdk (GPL-3.0).
- **Do not redistribute keys, certificates or private material extracted from any firmware or device together with
  this project.**

## License

The app's source code is released under the [GNU General Public License v3.0](LICENSE). The app is based on CarDeck,
released under the MIT license; its license notice is in [NOTICE](NOTICE).

---

*Last updated: 2026-11-28*
