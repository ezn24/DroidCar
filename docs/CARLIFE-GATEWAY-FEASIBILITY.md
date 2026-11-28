# CarLife / Carlink -> CarPlay gateway: feasibility study

> **Superseded (2026-11)**: DroidCar no longer plans to be a CarLife / ICCOA *receiver* gateway. It now speaks these
> protocols as the phone (sender) side itself; see [MULTI-PROTOCOL.md](MULTI-PROTOCOL.md). This study is kept for its
> research notes.

> Study date: 2026-10-08. Goal at the time: add a CarLife / ICCOA Carlink **receiver** to the app (a CarPlay sender), so
> the phone's car-connect app / Amap projects into our app, which forwards it over CarPlay to the Audi head unit:
> upgrading from "self-drawn launcher" to "a real projection content gateway".

## Summary

| Aspect | Assessment |
|---|---|
| Architectural precedent | Yes: Carlinkit ICCOA boxes / Tboxes bridge "connect-protocol receiver -> CarPlay", mature products on sale |
| CarLife openness | Yes: Baidu officially open-sourced a complete head-unit side in 2018 (apollo-DuerOS, Apache 2.0, surviving forks) |
| Open reference implementation | Yes: CarLifeWirelessBox (MIT, active in 2026, works with Xiaomi CarWith's CarLife connection) |
| Video forwarding cost | Low: CarLife is already H.264 Annex B; CarPlay needs only a remux, no transcoding |
| **Full Amap projection** | No: **the CarLife channel does not support Amap** (only Baidu apps); Amap's deep integration uses Carlink |
| ICCOA Carlink openness | No: alliance membership, no open source, authenticated encryption; reverse engineering only (large effort, compliance risk) |
| Same-phone loopback (127.0.0.1) | Uncertain: no data-path obstacle; the risk is whether the sender app enters listening state without USB / head unit, needs testing |

## 1. CarLife protocol: almost fully public

Baidu open-sourced a complete head-unit implementation on GitHub in ApolloAuto/apollo-DuerOS in 2018 (the original
repository is gone, 81 forks survive, e.g. milesking/apollo-DuerOS, Apache 2.0, legally reusable).

- **Transport**: 7 TCP channels; the phone listens on 7240 (CMD) / 8240 (VIDEO) / 9240 (MEDIA) / 9241 (TTS) / 9242 (VR)
  / 9340 (TOUCH) / 9440 (CTRL); Wi-Fi mode = the receiver connects to "phone IP:port" directly over TCP (no ADB; only USB
  mode uses ADB forward / AOA)
- **Messages**: Protobuf + a custom header; known message IDs: version negotiation 98305/65538, authentication
  98380/65611, heartbeat 131074, touch 425985, hard key 425992; an 8-stage handshake
- **Media**: video = H.264/AVC Annex B elementary stream encoded by the sender (around 720p/30 fps); audio PCM/AAC;
  control over protobuf
- **Touch back channel**: a separate touch channel, coordinates scaled to the projection resolution, which fits our
  CarPlay HID touch points

## 2. The key gap: which channel Amap uses

- **CarLife+ does not support Amap projection by default** (only Baidu apps). Workarounds: CarLife+ 8.0 "project phone
  apps" mirrors the full screen (Amap included), or the USB-unlocked "super projection"; mirror mode is limited
- **Full deep Amap projection is on the CarWith / ICCOA Carlink channel** (lane-level, cruise, the full phone UI); its
  specification is for alliance members only, there is no open implementation, and it uses authenticated encryption;
  the community can only reverse the CarWith / Jovi phone side or head-unit APKs circulating on Enshan forums
- **Xiaomi CarWith keeps a "CarLife connection" entry** (bundled com.baidu.carlife component), so a CarLife receiver
  alone already covers CarWith's compatibility mode; Honor's car-connect app is CarLife compatible too
- A newer pitfall: CarLife app >= 7.2 shows black on third-party head units; reliable senders are CarWith's CarLife
  connection, old CarLife apps (<= 6.7.2) and vendors' compatibility modes (needs testing)

## 3. Same-phone loopback (the core idea and the core risk)

- In CarLife Wi-Fi mode the data path is a direct TCP connection to the phone's IP, so 127.0.0.1 should work in theory
  (the Android sandbox allows localhost between apps, no adb forward needed; without root self-forwarding is impossible
  anyway)
- **Nobody has publicly verified same-phone loopback.** The whole risk is the sender's state machine: the sender app
  (car-connect / CarLife / Amap's embedded module) may require "USB plugged in" or "joined the head-unit hotspot" before
  starting its listener or accepting connections
- Test (go / no-go): install an old CarLife app -> open its connection screen -> connect to 127.0.0.1:7240 from a
  browser / another app and see whether something listens; or first verify the phone sender against carlife_pc_tool on
  a PC, then move it onto the same phone
- **Fallback architecture** (if loopback fails): a spare phone with the complete app (CarLife receiver + CarPlay sender)
  stays in the car = a self-made Tbox (CarLifeWirelessBox has verified this topology; we would add the CarPlay sender),
  which is exactly the commercial product's shape

## 4. Integration with the existing project (had it been built)

```
+------------------- the same Honor phone -------------------+
| Honor car-connect / CarLife app (sender)                    |
|        <-> 127.0.0.1:7240... (CarLife Wi-Fi mode)           |
| carlife/ module: CarLife receiver (new)                     |
|   - handshake / auth / heartbeat (protobuf)                 |
|   - receive H.264 Annex B -> remux (no transcoding)         |
|   - touch 425985 <- CarPlay HID touch coordinates scaled    |
| bridge/: feed the remuxed stream to the CarPlay sender      |
|        <-> Bluetooth RFCOMM + Wi-Fi + AirPlay main session  |
+---------------------> Audi head-unit screen <---------------+
```

Suggested order at the time: loopback go / no-go experiment (1-2 days) -> CarLife receiver (reuse the apollo-DuerOS C++
library or the CarLifeWirelessBox Kotlin implementation, 2-3 weeks) -> remux bridge (1 week) -> connect to the Phase 2
CarPlay sender.

## 5. Compared with alternative routes

| Route | Amap experience | Effort | Risk | Latency |
|---|---|---|---|---|
| A. Amap navigation SDK in a self-drawn UI | professional navigation, controllable UI, but not "full Amap" | small (individuals can get a key) | low | single stage, lowest |
| B. CarLife gateway (this study) | Baidu apps + Amap in mirror mode | medium | medium (sender compatibility) | two stages, ~200-400 ms |
| C. Carlink gateway (reverse engineered) | full deep Amap integration (best) | large | high (closed, encrypted, fragmented) | two stages |
| D. Buy a Tbox | full (the box has B + C) | none | low | box level |

**Recommendation at the time**: do A right away as the baseline (it does not conflict with B / C and is part of the
Phase 2 self-drawn UI anyway); start B as an enhancement after Phase 2 (loopback experiment first); evaluate C only if
strong demand remains after B.

## 6. Reference implementations

| Project | Notes |
|---|---|
| milesking/apollo-DuerOS (fork) | Baidu's official open-source head-unit side, Apache 2.0, C++ library + Android app |
| pitate7777777/CarLifeWirelessBox | MIT, Kotlin, HU + MD roles, works with CarWith wireless CarLife |
| MeshHun/carlife_pc_tool | PC head-unit simulator, protocol constants from decompilation, active 2026-09 |
| aa112901/CarProjection | Phone as a CarLife head-unit screen, Android 10+, no root |
| eytb2.github.io CarLife training notes | Official training material: connection modes / channels / protobuf structure |
| Enshan ICCOA Carlink update collection | Source of circulating head-unit APKs (research reference only, do not distribute) |

## 7. Compliance

- apollo-DuerOS (Apache 2.0) and CarLifeWirelessBox (MIT) may be reused legally with their copyright headers kept
- Reversing CarWith / Carlink APKs or APKs circulating on Enshan: personal research only, never redistribute
- "CarLife" is a Baidu trademark; commercial use needs authorisation; personal projects should avoid it in their name
