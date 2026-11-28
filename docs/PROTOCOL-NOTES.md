# Wireless CarPlay sender protocol notes

> Project goal: an Android phone plays the iPhone (CarPlay sender) and connects to CarPlay head units (receivers).
> These notes combine four local reference repositories (`reference/`: DiPlay, xcertplay, catplay,
> carplay-wifi-extractor, gitignored) with public reverse-engineering material.
> **The bench receiver actually used was DiPlay** (a derivative of xcertplay, `reference/DiPlay/`); most code reading
> was done in xcertplay (`reference/xcertplay/`), the two share their source and protocol logic.
> "(confirmed)" marks items checked line by line against reference code; "(?)" marks open items.
> Log lines quoted from before 2026-11-21 were Chinese in the original logs; they are given here in English.

## 1. Roles and authentication model

| | Receiver (head-unit role) | Sender (iPhone role, this project) |
|---|---|---|
| Played by | Audi head unit / DiPlay (bench) | this app |
| Needs an Apple-signed identity | yes (MFi certificate / chip, verified by the phone) | no |
| Verifies the peer | no (one-way authentication, confirmed by Oligo at DEF CON 2025) | optional (skipped on the bench) |

**Key conclusion**: iAP2 authentication is one way: the phone verifies the head unit's MFi credentials (the 0xAA00
challenge-response block), and the head unit fully trusts the verification result the phone reports. So the sender
needs **no** Apple signing material; the certificate DiPlay extracted from Carlinkit firmware is useless for the sender
(it is a receiver identity).

## 2. Layers (bottom up)

```
+--------------------------------------------------------+
| AirPlay main session: RTSP-style control + H.264/H.265 |  Phase 2
|   stream + HID; carried over Wi-Fi TCP (head-unit      |
|   AirPlay port, from 0x4301)                           |
+--------------------------------------------------------+
| mDNS/Bonjour: head unit advertises _airplay._tcp;      |
|   phone advertises _carplay-ctrl._tcp (head unit       |
|   connects back to it)                                 |
+--------------------------------------------------------+
| iAP2 control session (CSM): 0x4040 framing + TLV body  |  Phase 1
+--------------------------------------------------------+
| iAP2 link layer: FF55 SYN / FF5A data frames + checksum|  Phase 1
|   carried over Bluetooth RFCOMM (wireless) / USB bulk  |
|   (wired, out of scope)                                |
+--------------------------------------------------------+
```

## 3. Link layer (confirmed against xcertplay Iap2LinkEngine.kt, corrected 2026-10-08)

Data frame header (big-endian, 9 bytes + payload + payload checksum):

```
FF 5A | u16 total length | u8 control | u8 seq | u8 ack | u8 session ID | u8 header checksum | payload | u8 payload checksum
```

**Corrections measured in Phase 1 (differences from early notes)**:
- The handshake is not an "FF55 control frame": both sides send the marker `FF 55 02 00 EE 10`, then a sync packet
  (FF5A frame, control=0x80, payload = parameter negotiation struct `>BBHHHBB` + 3 session descriptors)
- **In wireless mode the phone initiates the sync**: the accessory (openWireless, initiateNegotiation=false) only sends
  markers and waits for the phone's sync packet; both sides ACK a received sync packet, and the negotiating side enters
  NORMAL on the ACK (real cars turned out to send SYN themselves, see 9b)
- **Session IDs are 10 (control) / 11 (EA) / 12 (file transfer)**, not 0/1/2 as in Oligo's notes (that may be the
  Bluetooth variant; 10/11/12 interoperate with DiPlay; Audi uses its own table, see 9b)
- Sequence numbers are u8 and wrap, initial 99; data frames use control=0x40 (piggybacked ACK)
- Control bits: SYNC=0x80, ACK=0x40, EAK=0x20, RST=0x10
- Checksum = two's complement of the byte sum (sum including the checksum mod 256 == 0), one for the header and one for
  the payload
- Wireless parameters (DiPlay / xcertplay WIRELESS_LINK_CONFIG): maxOutgoing=4, controlSessionVersion=2, ACKs kept
  (wired uses zero-ack mode)
- Large CSM frames are split by the peer's negotiated maxLength and reassembled by the receiver's CSM parser

**Code**: `app/src/main/java/.../iap2/Iap2LinkEngine.kt` (full state machine, loopback tested)

## 4. CSM framing and TLV (confirmed, xcertplay `iap2/wire/Iap2CsmFramer.kt`)

```
frame:  0x4040 | u16 total length (incl. 6-byte header) | u16 message ID | body   (big-endian, no checksum)
param:  u16 param length (incl. 4-byte header) | u16 param ID | value             (body = parameter sequence)
string: NUL-terminated UTF-8; nested param = value is a sequence of sub-parameters
```

**Code**: `app/src/main/java/.../iap2/Iap2Csm.kt` (frame codec + incremental parser + TLV builder)

## 5. Wireless control sequence (confirmed, xcertplay `Iap2WirelessControlClient.kt` + extractor README)

After the Bluetooth RFCOMM link is up (the two devices must be paired at system level first):

```
accessory -> phone: FF55 SYN                          (who starts the link handshake: see 3 / 9b)
phone -> accessory: StartIdentification      (0x1D00)
accessory -> phone: IdentificationInformation (0x1D01)   head-unit identity + send / receive message lists
phone -> accessory: IdentificationAccepted    (0x1D02)
accessory -> phone: MFi authentication       (0xAA00 block)  <- the phone may skip verifying it
phone -> accessory: RequestAccessoryWiFiConfiguration (0x5702)   <- **required**: all four independent
                                                   implementations declare it; send it even when the head
                                                   unit forgets to declare it (see 5b / 9j)
accessory -> phone: AccessoryWiFiConfiguration (0x5703)   plaintext TLV: SSID / password / channel / security
   (security: 0=None 1=WEP 2=WPA/WPA2 3=WPA3 transition 4=WPA3)
phone joins the head-unit hotspot -> mDNS resolve _airplay._tcp
phone -> accessory: CarPlayAvailability      (0x4300)
accessory -> phone: CarPlayStartSession      (0x4301)   wireless params + head-unit IP + AirPlay port
                                                    + deviceIdentifier + public key + sourceVersion
phone -> accessory: WirelessCarPlayUpdate    (0x4E0D)   announces wireless CarPlay availability
phone -> accessory: DeviceTransportIdentifier(0x4E0E)   Bluetooth / USB identifier; the accessory then resends 0x5703
accessory -> phone: StartHID                 (0x6800)   Phase 2: touch back channel
phone -> accessory: DeviceHIDReport          (0x6801)   reply
```

0x1D01 identity parameter layout (the sender must parse it; field id -> meaning):
0=name, 1=modelIdentifier, 2=manufacturer, 3=serialNumber, 4=firmwareVersion, 5=hardwareVersion,
6=messages sent (u16[]), 7=messages received (u16[]), 8=power source type, 9=maximum current (mA),
10=EA protocol group, 12=language, 13=language list, 16=USB transport component (wired),
17=Bluetooth transport component (wireless), 18=HID component, 24=wireless CarPlay component (with SSID).

## 5b. 0x5702 is "required but often left undeclared": external evidence from four independent implementations (2026-10-22)

Trigger: 0.1.13 went from "connects" to "does not connect" on an Audi MMI 3315. Commit 8b64147 dispatched the Wi-Fi
credentials as "skip the old protocol if 0x5702 is missing from the head unit's receive list", and Audi's sparse lists
(2 sent / 5 received) do not contain it. So not a single 0x5702 went out in the whole round, the head unit's AirPlay
service never came up (port 7000 `ECONNREFUSED` throughout), and each of 4 sessions tore itself down after 8 s.

**All four independent implementations list 0x5702 as "received by the head unit"**, so the specification requires
it:

| Project | Role | Evidence |
|---|---|---|
| catplay | head unit (rx gadget) | `catplay_carplay_rx_gadget/src/iap2_session.rs`: `(identity.wifi_ssid.is_some(), &[RequestAccessoryWiFiConfigurationInformation::PACKET_ID])` |
| xcertplay | head unit | `Iap2IdentificationClient.kt:315`: `WIRELESS_PHONE_MESSAGES = intArrayOf(0x4e0d, 0x4e0e, 0x5702)` |
| amineross/showcase | head unit (jailbroken iPhone) | `msgs_recv[]` in `source/carplay_bt.m` contains `0x5702 /* RequestAccessoryWiFiConfigInfo */` |
| wiomoc/iap2 | head unit (Python) | `msgs_recv` in `iap2/__main__.py` contains `RequestAccessoryWiFiConfigurationInformation` |

**Inference**: an absent 0x5702 only shows the list is incomplete; it does not prove "unsupported". Real head units
often leave it out (the Audi MMI 3315 does) and still handle undeclared messages: in the same day's log
`0x4e0d/0x4e0b/0x4e0e` were not in the list, yet the head unit ACKed each one (seq 107->ack=107, 110->ack=110,
113->ack=113).

Current dispatch uses only `ReceiverProfile.wifiGen`: only `NEW` (both lists total >= 10 entries and still no 0x57xx,
the BMW iD9 form) skips 0x5702; `LEGACY` and `UNKNOWN` always send it, with a 1.5 s wait window (eight Audi rounds
answered in 122/136/166/166/198/246/266/368 ms).

### Two moments to send 0x5703 (before / after the transport notification)

`send_accessory_wifi_config(phase)` in `amineross/showcase` separates pre- and post-transport:
`mfi_auth_succeeded -> wireless_carplay_connecting_seen -> transport_notification_seen
-> wifi_config_sent_post_transport` (resent up to 5 times). Its comments record a counter-example: on an iPhone8,1 /
iOS 15.8.3 the peer requested the Wi-Fi configuration **before** 0x4E0E, the old code therefore sent 0x4301 first, and
the network phase never started.

So the order itself is not necessarily fatal (in our 10-19 / 10-20 Audi logs 0x5703 arrived before 0x4e0d/0x4e0e and
everything worked), but **timing sensitivity is real**. Our keep-alive pump answers a repeated 0x5702 again (the `wifi`
cache in `Iap2SenderSession`), matching showcase's resend semantics.

### Two endpoint discovery flows (Apple's official description)

`docs/research/mhi2-native-wireless-2026-10/11_CONNECTION_FLOW_VARIANTS.md` in `harman-f/mhi2_altscreen_carplay`
summarises Apple's two designs:

```
A. Classic flow (WWDC 2017):
   pairing -> BT iAP2 (identification + MFi + transport identifier) -> credential exchange -> WLAN association
   -> Bonjour browse/resolve -> Controller Connect -> session -> iAP2 handover -> ACTIVE

B. Simplified flow (WWDC 2023, iOS 14+):
   exchange IP endpoint + TCP port directly in iAP2 -> session directly -> security + transport handover -> ACTIVE
   (**no Bonjour needed**, supports WPA3-only)
```

The same document stresses Apple's explicit warning: **after WLAN association, Bluetooth / iAP2 must not be torn down
before the CarPlay / iAP handover reaches its expected state**. That is the theoretical basis of "tearing the link down
every 8 s = never receiving the invite": in a real iPhone trace the head unit sends `StartSessionHost` about 21 s after
joining.

In practice: the Audi MMI 3315's TXT `features=0x4040280,0x61` (bit 37 CARPLAY_CONTROL and bit 38 HK_PAIRING both set)
and `srcvers=450.14.2`; it actually mixes A (mDNS) and B (0x4301).

### A counter-example tied to iOS 18 (does not apply to Audi, but worth knowing)

`amineross/showcase` deliberately **does not declare** 0x4301, and its comments explain why: an iPhone 16 Pro on
iOS 18 rejects identification if 0x4301 is in msgs_sent, while 0x4300 cannot be added to msgs_recv either (the iPhone
checks 0x4300/0x4301 as a mandatory pair and rejects a missing 0x4301), so the whole 0x43xx path is closed on iOS 18+.

Audi is not limited by this: its send list clearly contains `0x4301` (log: head-unit send list(2)=0x4301 0xea03).
**Instead it declares 0x4301 and leaves out 0x5702**, more evidence that the lists are incomplete, not that the
capability is missing. When changing this code, do not treat showcase's "iOS 18 limit" as a rule for every head unit.

## 6. mDNS/Bonjour (confirmed, xcertplay `network/CarPlayBonjour.kt`)

The **head unit** advertises `_airplay._tcp` with TXT records:

```
deviceid=AA:BB:CC:DD:EE:FF   features=0x44540380,0x61   flags=0x4
model=...  srcvers=...  protovers=1.1
pi=<pairing ID>  pk=<ed25519 public key hex>
```

The **phone** advertises `_carplay-ctrl._tcp` (TXT: `id=<Bluetooth MAC>`); the head unit resolves it and **connects
back** to that port as the control channel (the carrier that moves the iAP2 control session from Bluetooth to Wi-Fi
TCP). The bench (DiPlay, following xcertplay) prefers link-local IPv6 and falls back to IPv4: the ctrl probe was seen
coming from `fe80::...%wlan0`.

## 7. Bluetooth RFCOMM (confirmed, xcertplay `CarPlayController.kt:1591,1963`)

- The iPhone (sender) listens on SDP service UUID `00000000-deca-fade-deca-deafdecacafe`
- Accessory UUID: `00000000-deca-fade-deca-deafdecacaff`
- The bench (DiPlay) connects by scanning **bonded** `PHONE_SMART` devices and trying the UUID above on each
- So the bench only needs: system-level pairing of both phones -> this app listens -> the spare phone with DiPlay
  connects back

## 8. AirPlay main session (Phase 2, index only)

- Reference implementation: catplay `carplay/catplay_carplay/src/carplay_tx/` (sender, 1959 lines) + `screen/tx/`
  (video send session) + `rtp/record/` (sender RTP) + `pairing/pairing_helper_tx.rs`
- Crypto: ed25519 PairSetup / PairVerify (HomeKit style) + RTP AES / ChaCha (receiver references in xcertplay / DiPlay
  `airplay/PairSetup.kt` / `PairVerify.kt` / `Srp6a.kt`)
- Video: H.264/H.265, AVCC / Annex B conversion (catplay `video/`)
- Clock: NTP-style timing port + RTCP (catplay `clock/`)
- (?) FairPlay / stream encryption details have no public specification; rely on catplay's source and ludwig-v's
  decrypted firmware

## 8b. Audio channel (implemented: phone -> head-unit main audio)

**Negotiation**: `SETUP` on the encrypted RTSP session, body = `streams:[{type:100 (main audio), streamConnectionID,
audioFormat:<bitmap>, audioType:"media"}]`; the receiver answers
`{type:100, dataPort:<UDP>, controlPort:<UDP>, streamConnectionID}`.
- Audio format bitmap (catplay `msg/streams.rs` AudioFormat): PCM 48k stereo = `1<<15`, AAC-LC 48k stereo = `1<<23`;
  the tier and rate are chosen from `/info` (AGENTS.md section 11)
- Stream key follows the video rule: `HKDF(shared, "DataStream-Salt<audio connId>", "DataStream-Output-Encryption-Key")`
- The audio stream has its own ID (its own connId), independent of video

**Wire format** (RTP over UDP; confirmed by DiPlay's `AudioStream.kt` decryption path):
```
[RTP header 12B][ChaCha20-Poly1305 ciphertext + 16B tag][8B little-endian nonce]
header: v=2 | M=0 PT=<stream type=100> | seq u16BE | timestamp u32BE (cumulative sample frames) | ssrc u32BE
AAD = header bytes 4..12 (BE timestamp + ssrc, catplay "modern AAD")
nonce (12B) = 4 zero bytes + trailing 8-byte little-endian counter (incremented per packet)
```
- Originally 512 sample frames per packet (48000 Hz stereo 16-bit = 2048 B payload, ~2084 B per packet; the receiver's
  UDP buffer is 4096 B, safe); now 352 frames per packet following catplay's real-car values (AGENTS.md section 11)
- Capture: `AudioPlaybackCapture` (Android 10+, needs MediaProjection consent + RECORD_AUDIO), or REMOTE_SUBMIX through
  Shizuku
- At the time one way only (media / navigation to the car); the microphone uplink came later
  ([MIC-RESEARCH.md](MIC-RESEARCH.md))

**Code**: `audio/AudioSender.kt` (capture + packetising + UDP), `airplay/AirPlayClient.runAudioStream()`
(negotiation), unit test `AudioSenderTest` (decryption loopback)

## 9. Real car (Audi MIB3) Phase 3 risk list (as of 2026-10-08)

1. catplay's compatibility table records Audi / Porsche HARMAN MIB2 "cutting power to and restarting the dongle after
   role switch", but that is wired USB; the wireless direction was unknown
2. The user's head unit is MIB3 (2020 and later), with no public compatibility record
3. First pairing in a real car: needs system-level Bluetooth pairing; Audi head units often require a USB plug-in
   before opening wireless pairing ((?) whether Bluetooth alone can trigger it; DiPlay has no such limit). Answered in
   9b: Bluetooth pairing alone works
4. WifiNetworkSpecifier cannot take a password programmatically without a system dialog, so joining silently with the
   0x5703 password needs the user's confirmation each time ((?) impact in Phase 3; see WIFI-JOIN.md)

## 9b. Audi MIB3 measurements (2026-10-09, Audi_MMI_6318 / MHI3_AS_AU_P3801)

The head unit identifies as `AUDI 3260 / DELPHI / CL37_MIB3H_AU_AS_G4x_2200910PROD`. Its Bluetooth SDP advertises the
accessory iAP2 service `...decacaff` (connectable after system-level Bluetooth pairing, no USB trigger needed).

**Passed (after byte-by-byte comparison and fixes)**:

| Step | Result | Key point |
|---|---|---|
| RFCOMM both ways | ok | the head unit also connects back by itself (about one round per 60 s); both directions work |
| Link negotiation | ok | the head unit sends a SYN declaring the session table `{1:control, 2:EA}` -> the phone must **ACK and echo that table verbatim**; CSM then uses **session 1** (frames on session 10 are dropped silently while the link layer still ACKs them: very misleading) |
| Identification | ok | 0x1D01 received |
| MFi | ok | **20-byte challenge** (32 B is silent but does not break the link); 0xAA03 signature 132 B |
| Wi-Fi credentials | ok | 0x5702 -> 0x5703 ssid=Audi_MMI_6318_5GHz ch=149 psk in plaintext |
| ctrl invite | ok | after discovering the phone's `_carplay-ctrl._tcp` on Wi-Fi the head unit sends `GET /ctrl-int/1/connect` (deadlock key: the phone must bind Wi-Fi **the moment 0x5703 arrives** and advertise a ctrl carrying the **real Bluetooth MAC**) |
| mDNS endpoint | ok | head-unit `_airplay._tcp` at 10.174.189.1:7000, TXT: `features=0x4040280,0x61 srcvers=320.17.7 flags=0x4 model=AUDI 3260 deviceid=54:64:DE:5E:51:B0` (**no pk field**) |
| AirPlay /info | ok | RTSP 200/455 (online) |
| pair-setup | 403 | the head unit does not accept anonymous SRP pairing (iAP2 already established MFi trust) |
| pair-verify fast path | pending | after 403 switch to "paired device" pair-verify (solved in 9c) |

**2026-10-11 retest (two more confirmed pitfalls)**:

1. **Audi's reaction to a 32 B MFi challenge is not stable**: on 10-09 it was "silent, link kept", on 10-11 the head
   unit **dropped the link** ~0.4 s after the 32 B challenge (the 912 B 0xAA01 certificate had arrived normally, so
   identification was fully right). Starting every session at 32 B would drop the link repeatedly. Fix:
   `Iap2SenderSession` remembers "rejected lengths" and skips them for later sessions in the process (now persisted by
   `MfiLengthMemory`).
2. **Leaked client threads confuse the head unit** (on 10-11 three `iap2-bt-client` threads were found running at
   once): `BluetoothSocket.connect()` blocks, the old code registered the socket only after success, so `stop()` could
   not close a socket still connecting -> the thread lingered; when the user pressed "Connect" again the old thread came
   back to life through the shared `running=true` -> several RFCOMM sessions hit the head unit concurrently, seen as
   random link drops / timing confusion. Fix: a generation number (incremented on start/stop, stale threads exit) +
   registering the socket before connect (so stop() can close and interrupt the blocking call).

**Differences between Audi and the bench (DiPlay)**:
1. 0x4301 **depends on the head-unit model / firmware and cannot be generalised as "Audi"**:
   - **Audi MMI 6318 (the one fully working 2026-10-09/11)**: does not send 0x4301; the identification frame declares
     receiving `0x4e0c 0x4e09 0x4e0d 0x4e0e 0x5702 0xea00 0xea01` and sending `0x5703 0xea03`; the AirPlay endpoint
     comes only from Wi-Fi mDNS `_airplay._tcp` (10.174.189.1:7000)
   - **Audi MMI 9310 (AUDI 5810 / DELPHI, user measurement 2026-10-18)**: the identification frame **explicitly
     declares sending 0x4301** (head-unit send list(2)=0x4301 0xea03), but actually sends it **2 min 35 s** after
     Phase 1 (when the car's CarPlay window just opened); by then our 3 s wait window, 4 s ctrl invite window and 120 s
     pairing window **had all expired**. Dropping it as a heartbeat made the head unit wait 15 s for a connection and
     then tear down RFCOMM -> the user saw "disconnects by itself after connecting". Also: that car's hotspot gateway is
     `10.174.189.254` (not .1), and the gateway fallback port 7000 was still refused; whether the port differs was not
     settled (mDNS never found `_airplay._tcp` that round); the next log's raw 0x4301 body would show whether it
     carries IPv4 / port.
   - Conclusion: **0x4301 must be handled as a lifecycle event** (parse it and start pairing even when late), and
     timeout logs must never say "this head unit does not send it".
2. The head unit **sends SYN itself** (the bench waits for the phone); session table {1,2}, which must be echoed
3. **Supporting both MFi challenge lengths (an important pitfall, hit on 2026-10-10)**:
   - DiPlay (Carlinkit AuthV3 EC identity): **accepts exactly 32 B only**, and **drops the link** on 20 B (70-350 ms;
     seen as "cannot connect to DiPlay + link down during the challenge"); answers in ~0.2 s
   - Audi MIB3: silent on 32 B (link kept) or drops the link (both seen on 10-11), answers 20 B immediately
   - Conclusion: the order must fall back **32B (3 s) -> 20B (6 s) -> 16B (3 s)**. The reverse (20 B first) kills
     DiPlay; Audi's 32 B drops heal through the "rejected length" memory (see the 2026-10-11 note above)
4. `pair-setup` gets 403 (the bench accepts SRP with 3939)
5. The AirPlay port opens in a window (briefly after the ctrl invite is accepted; Connection refused when not invited)
6. **Do not drop the BT control session** (the bench hack relies on DiPlay rebuilding its stack; Audi tears down the
   whole AirPlay window after a link drop)

**Device identity (for 0x4300 / 0x4E0E / ctrl TXT id)**: the real Bluetooth MAC must be resolved at runtime (on
Android 11+ the app layer only gets the placeholder `02:00:00:00:00:00`). From the Shizuku shell:
`content query --uri content://settings/secure/bluetooth_address`.

## 9c. Audi MIB3 fully working (2026-10-11, Phase 3-C acceptance)

iAP2 -> AirPlay pairing -> video streaming -> touch back channel -> virtual display injection, the whole chain
interactive. The last protocol differences solved that day on top of 9b:

| Step | Root cause | Fix |
|---|---|---|
| pair-setup / pair-verify 403 | the pairing requests lacked Apple identification headers | `Content-Type: application/pairing+tlv8` + `X-Apple-HKP: 2` (pair-verify also `X-Apple-PD: 1`); M1 adds Method=None(0) + State(1) TLVs |
| Screen stream SETUP 400 | (1) timingPort=0 (2) features offered only videoPlayback (the receiver intersects and gets nothing) (3) no resource declaration (4) identity reported with the head-unit version | TimingServer (32 B RTCP NTP, pt=0xD2 request / 0xD3 reply, echo t3 + local monotonic clock); full CONTROLLER_FEATURES list (uiContext / viewAreas / ..., 14 entries); modesChanged before SETUP (resourceID 1/2 entity=1); UA / sourceVersion = iPhone's 566.25.21 + osName / osBuild and all other fields |
| Session torn down after ~10 s | missing both heartbeats | keepAlive UDP 4 B ping -> head-unit keepAlivePort (10 s, later 5 s, see 9e); POST /feedback with empty body (1 s). Each has its own watchdog |
| No touch reports (1) | wrong command path | commands must go as POST /command on the **event channel socket** (Events key, own CSeq, fire-and-forget); a 200 on the RTSP control connection is a fake acknowledgement |
| No touch reports (2) | HID not armed | after SETUP send hidSetInputMode {hidInputMode:0, uuid} for each /info hidDevices[].uuid; **Audi's uuids are the strings "0"/"1"** (DiPlay uses integers) |
| No touch reports (3) | the event channel is bidirectional RTSP | answer every incoming request immediately with an encrypted RTSP/1.0 200 OK; without answers the head unit queues 3 and closes the channel ("can swipe once") |
| Touch coordinates | Audi HID descriptor | uuid=1 eso_touchScreen: 5 bytes `[tip 1bit][X u16LE][Y u16LE]`, X<=1412 / Y<=654 = absolute screen coordinates; uuid=0 is Consumer media keys (not touch) |
| Virtual iAP2 link | iAPSendMessage semantics | event channel iAPSendMessage.data = link-layer byte stream; marker = DETECT, the head unit (client) resends it every 200 ms and sends SYN after the server answers one DETECT; a server that keeps resending DETECT knocks the client back to Detecting |
| 0x4E0E kills the BT link | the head unit accepts it but does not ACK | sendControl(awaitAck=false), fire and forget; 0x4300 the same |
| mDNS picks the wrong target | phone hotspot sharing | filter `_airplay._tcp` results by the local IP /24 prefix; when mDNS fails, fall back to the gateway (gateway = head unit, port 7000) |

**Debugging workflow pitfalls**: `adb install -r` does not kill the old process (the old dex keeps running, the fix
"does nothing"); NsdManager unregisterService is asynchronous (reusing the listener reports "already in use", create a
new one for every registration); ctrl mDNS registration must happen **after** bindProcessToNetwork (multicast follows
the process's bound network).

## 9d. WinPlay (Windows receiver) measurements (2026-10-19)

WinPlay is an open-source Windows wireless CarPlay receiver (`reference/WinPlay`, GPL-3.0, Node + TS, receiver role).
The user connected to it with the Honor as sender (log `logs/cardeck/xiaomi-winplay.log`): iAP2 -> Wi-Fi -> mDNS ->
pairing all worked, stuck at the session-level SETUP.

**Root cause: the session-level SETUP `deviceID` must equal the Bluetooth MAC of the iPhone selected on the
receiver.**

- At start WinPlay sets a filter from `cfg.targetBluetooth` (the paired iPhone the user picked on Windows):
  `stack.deviceFilter = id => normalize(id) === normalize(cfg.targetBluetooth)`
  (`reference/WinPlay/src/engine/run.ts:64`).
- A mismatching (or missing) `dict.deviceID` in the session-level SETUP gets **403** straight away
  (`src/engine/protocol/cpStack.ts:696-699`); `RECORD` also gets 403 before the device is accepted.
- We used to send catplay's hard-coded fake `ff:ee:dd:cc:bb:aa` (the old comment "head units do not check it" held
  only for Audi / DiPlay).
- The status line `RTSP/1.0 403 OK` is WinPlay's fingerprint: its `STATUS_TEXT` table has no 403 entry and
  `buildResponse` falls back to `OK` (`src/engine/protocol/rtspMessage.ts`). On "403 OK" suspect the device filter
  first, not a generic RTSP difference.
- The visible symptom is "disconnects about 45 s after connecting": the sender sends nothing more and the receiver tears
  down the link after its video deadline (`armVideoDeadline` 45 s). The sender should close actively on a non-200
  SETUP instead of waiting silently.

Audi / DiPlay check neither, which is why the field went unnoticed for so long. The project now reuses the **real local
Bluetooth MAC** resolved by `DeviceIdentity` (the same as 0x4300 / 0x4E0E); the placeholder `02:00:00:00:00:00` is only
a fallback while Shizuku is not ready, and the 403 log says explicitly "select this address on the receiver".

**Other WinPlay traits useful to us**:

- Fixed TCP port 17000; its `_airplay._tcp` TXT matches an iPhone's (`srcvers=950.7.1 features=0x44540380,0x61
  flags=0x4`).
- The session-level SETUP opens a keepAlive port only when `keepAliveLowPower` is true.
- Event channel / stream SETUP / audio negotiation largely match DiPlay (`_setupScreen` / `_setupAudio`), with stream
  keys derived from `DataStream-Salt<id>` the same way: a third cross-check of receiver behaviour.

**A bplist encoding bug fixed along the way**: `BinaryPlist.encodeInt` chose the width by **unsigned** range, but bplist
integers are **signed**: 35449 (0x8A79) was written in 2 bytes and Apple parsers read it as int16 -30087. Every
ephemeral port (timingPort / eventPort / dataPort often fall in 32768..65535) hits it; WinPlay reads with an unsigned
BigInt and is unaffected; whether Audi was affected is unknown (video had worked, but it may have ignored a wrong
timingPort). The width is now chosen by signed `Byte/Short/Int` ranges; regression tests cover
0/127/128/255/256/32767/32768/35449/65535/65536.

## 9e. Audi MMI 3315 session torn down after 10 s: all three heartbeats are needed (2026-10-19)

User log `logs/cardeck/CarDeck-log-20261019-081700.txt`: iAP2 -> MFi -> hotspot -> pairing -> SETUP -> RECORD -> stable
30 fps video, then **the whole round is reset 10.17-10.23 s after RECORD**, with two rounds matching to the millisecond
(not a random network problem).

**Root cause one: the UDP keepAlive interval equals the head unit's watchdog.** We sent one every 10 s (catplay
`KeepAliveClient::PING_INTERVAL`), and the head unit's watchdog is also 10 s. In the log ping #2 came exactly 10.001 s
after #1 and the teardown followed within 213-247 ms: millisecond jitter of `Thread.sleep` made the heartbeat always
1 ms late. catplay's 10 s only suits its own receiver; real cars need margin. Changed to **5 s**
(`AirPlayClient.KEEPALIVE_INTERVAL_MS`).

**Root cause two: no keepAlive frame inside the screen stream.** catplay writes a keepAlive frame into the screen data
port **every second even while video flows** (`ScreenTransmitSession::create_keep_alive`, `DEFAULT_KEEP_ALIVE = 1s`):
not a backup for video traffic but a separate liveness signal on the receiver's screen. We had never sent one. Frame
format (`reference/catplay/.../screen/screen_frame.rs`):

```
128B plaintext header: u32LE body length | u8 opcode | 3B small params | 15 x 8B params
opcode 2 = KeepAlive (empty body)
opcode 5 = KeepAliveWithBody (when /info.keepAliveSendStatsAsBody=true;
           body = bplist{txUsageAvg, encoderCurrentFPS, sentFramesAvg,
                        queuedFramesAvg, lossAvg},
           last 4 bytes of params[14] = body length as f32 LE)
```

**KeepAlive frames are plaintext**: catplay's `ScreenFrameCodec::encode_composite` encrypts only
`opcode == VideoFrame`, so keepAlives do not consume a ChaCha frame nonce. We do the same and send them throttled to
1 s from `VideoSender`'s encoder output loop.

**The three heartbeats are independent**: UDP keepAlive (5 s, keepAlivePort) + RTSP /feedback (1 s, control channel) +
screen-stream keepAlive frame (1 s, video data port). Only the first two existed before; the "two heartbeats" in 9c
were enough only on the MMI 6318.

**Audio capture all zero (unsolved then, unrelated to the teardown)**: "capture samples non-zero 0% peak=0" for two
rounds. Candidate causes by likelihood: nothing was playing (only one old entry before the session), audio routed to the
car's Bluetooth A2DP (PlaybackCapture cannot capture Bluetooth output), or the target app forbids playback capture.

**MFi challenge length per head unit (measured on this car)**: the MMI 3315 is **silent for 6.0 s** on 20 B (sent at
15.605 -> no answer judged at 21.604), and answers 32 B with a signature in 0.64 s (0xAA03 received at 22.247). Those
6 s were a fixed cost on every connection unless the preference per car is remembered. Now implemented as
`MfiLengthMemory`: persists "success / silent / rejected" per 0x1D01 identity (name + model + manufacturer), a remembered
success length goes first, silent lengths retry with a 1.5 s window. This does not contradict 9b/9c: **head units of
different years / firmware prefer opposite lengths**; the MMI 6318 wants 20 B and drops the link on 32 B, so the
baseline order is still chosen by receiver type and the memory only overrides it.

## 9f. Old head units (CarPlay 210.81 / Audi 4xxx firmware) cannot connect: six differences aligned with catplay (2026-10-19)

The user reported "Audi software versions starting with 4 (e.g. 4851) do not connect". That generation is a
**pre-HomeKit receiver** in catplay's classification (README compatibility table: Audi / Porsche ~2020 HARMAN
MIB2/MHI2 = CarPlay 210.81). Checking against catplay upstream (the local copy was at 9-30, updated to v0.17.0, 39
commits) item by item found six differences, all fixed to match catplay:

**(1) The bplist root object must be at byte 8 (catplay 9ad3a7e).** CarPlay 210.81 ignores the trailer's topObject and
**takes the root at offset 8**; our encoder encoded children first and appended the root container last, so the root
was at the end. Symptom: the head unit receives a valid bplist but cannot parse a dictionary (`SETUP plist decode
failed` / 400). Fix: `BinaryPlist.encode` always ends with `makeRootFirst`, reordering and fixing the offset table
(catplay's algorithm); unit test `StreamFrameCipherTest.encodedPlistRootObjectStartsAtByteEight` pins it with a
multi-byte offset table of > 255 objects (small plists have a one-byte table and cannot show the bug).

**(2) The initial SETUP uses the relative URI `/setup`** (as catplay's `initial_setup`). The old code sent the
absolute `rtsp://host:port/`. Old firmware is sensitive to absolute URIs.

**(3) Unpaired sessions must send `et=1` (NONE), and the screen stream uses AES-128-CTR with a zero key.** catplay's
`ScreenFrameCodec::new` comment says there was never support for unencrypted screen sessions: ET = None maps to an AES
session whose session key before derivation is all zeroes. So `et=NONE` is not plaintext video but **AES-128-CTR with a
16-byte all-zero session key**, key / IV = `SHA512("AirPlayStreamKey"/"AirPlayStreamIV" + connId (decimal) +
sessionKey)[0..16]`. The event channel is **plaintext** in unpaired sessions too (catplay `EventsServer::unencrypted`).
With `et=NONE` audio has no shared key to derive the DataStream output key, so it is skipped.

**(4) Two encryption forms of screen data frames.** Paired (pair-verify) -> ChaCha20-Poly1305; unpaired -> AES-128-CTR
(no tag, same length, keystream **continuous across frames**: creating a new instance per frame garbles everything from
the second frame). Only `opcode=VideoFrame` is encrypted; keepAlive / config are always plaintext.

**(5) The control channel ciphertext MTU is 1440** (catplay `AIRPLAY_CIPHER_MTU_MAX`): plaintext limit per frame =
1440 - 2 B AAD - 16 B tag = **1422**. The old 0x4000 would be a protocol violation on strict head units (catplay's
decoder raises `PayloadTooBig` for `len > 1422`).

**(6) The "has an IPv4 address" test was too narrow.** Audi 4xxx `_airplay._tcp` / 0x4301 often give only **IPv6
link-local** (`fe80::...%wlan0`). The old code used `contains('.')` everywhere to check for an address, so such
endpoints counted as incomplete -> waited for a ctrl invite in vain -> fell back to "gateway:7000" (the 4xxx gateway is
not necessarily `.1`, nor the port 7000) -> never connected. Now everything goes through `net/EndpointHosts`: IPv4
first -> global IPv6 -> link-local IPv6 (with scope), and **video / audio / event channels reuse the address pairing
actually reached** (`AirPlayClient.activeHost`) instead of each picking IPv4 again.

**Also: tell the head-unit era from TXT features.** `_airplay._tcp` `features=low32,high32`, bit 38 =
`HK_PAIRING_AND_ENCRYPT`. Old head units do not set it -> `skipPairing` straight to a plaintext session instead of
idling through the 120 s pairing window (which is exactly what "never connects" looked like). Missing / unparsable TXT
always counts as "unknown" and keeps the old path, never a wrong downgrade. If a plaintext SETUP is still rejected (a
misjudgement), pairing is tried once more (catplay `handle_setup_result`'s crypto upgrade path, window cut to 30 s).

**Aligned at the same time (later reverted, see 9i)**: the `_carplay-ctrl._tcp` TXT `srcvers` was changed to the
sender version (566.25.21, previously the receiver's 950.7.1) and the service name to iPhone; `_device-info._tcp` was
advertised too (model=D84AP, catplay `DeviceInfoBonjourEntry`); `GET /info` carries an empty bplist dictionary body +
plist Content-Type.

## 9g. Audi 4851 in a real car, "connected but the head unit does not react": two VideoConfig header fields missing (2026-10-19)

An owner's log `logs/cardeck/CarDeck-log-20261019-101353-all.txt` (Xiaomi 24129PN74C / Android 16, app **0.1.4**;
head unit `Audi_MMI_3315` / `AUDI 5163` / DELPHI, `srcvers=450.14.2`, CarPlay `name=CarPlay`). Two findings:

**A. In this log every round is reset after 10.2 s** (all six rounds, same cause as 9e): event channel up -> exactly
+10.2 s `EVENT: read ended - Connection reset`. That version lacked the screen-stream keepAlive frame and still sent
UDP keepAlive every 10 s (equal to the watchdog). Both were fixed in **0.1.6** (9e), so nothing more was needed here,
but the owner had to update to 0.1.6 to get a full session.

**B. The direct cause of "app shows connected, head unit does not react": missing VideoConfig header fields.** Before
0.1.6 the VideoConfig frame wrote only `body_size` + `opcode`, while catplay's `ScreenFrame::config` writes two more,
and strict decoders like Audi's show **a black screen** without them:

| Field | Header position | Value | Purpose |
|---|---|---|---|
| `small_param[1]` | offset 6 | `ScreenFlag::Encrypted (0x04)` | declares that following VideoFrames are encrypted; without it they are decoded as plaintext NALs -> decode failure |
| `params[1]` | offset 16 | `(width, height)` as two **f32 LE** | the head unit builds its decoder canvas from it |
| `params[0]` | offset 8..16 | all zero | config carries no PTS (only VideoFrame writes it) |

**Why the benches never showed it**: DiPlay's `ScreenStream.onMessage` reads only `body` (opcode 0/1) and takes the
resolution from avcC, and WinPlay's `screenStream.ts` also looks only at opcode and body: neither checks these header
fields. The real car (Audi DELPHI) builds its decoder from them and shows black without them, while **session, touch
and heartbeats all work** and the log has no error line: the hardest kind of bug to trace.

**How A and B relate**: A keeps the session from living past 10 s ("flashes and drops"); B keeps the screen black while
the session lives ("shows connected but the head unit does not react"). They are independent and both must be fixed.

**Diagnostic log (added then)**: the `VIDEO: VideoConfig sent ... flags=0x07 1412x654` line is followed by a hex dump of
the first 24 header bytes, so the next report can be checked field by field.

## 9h. BMW 87866 stuck in iAP2 link negotiation: duplicate SYN-ACK sequence (2026-10-19)

Log `logs/cardeck/CarDeck-log-20261019-103436.txt` (OnePlus PKR110 / Android 16, app 0.1.6, head unit `BMW 87866` /
MAC `9C:F5:5F:D0:44:09`). **All 14 sessions stuck at the link layer**: 0 `iap2 link ready`, 0 `0x1D00` sent, 0 `0x1D01`
received.

**Frame counts (the decisive evidence)**:

| Direction | Frame type | Count |
|---|---|---|
| BMW -> phone | `0x80` SYN | **133** |
| BMW -> phone | `0x40` ACK / data | **0** |
| phone -> BMW | `0x40` bare ACK | 350 |
| phone -> BMW | `0xC0` SYN\|ACK | 14 |

**Root cause one: SYN-ACK reused the SYN's sequence number.** Raw bytes:

```
our SYN    : ff 5a 00 1d 80 63 ...      seq = 0x63 (99)
our SYN|ACK: ff 5a 00 1a c0 63 a1 ...   seq = 0x63 (99)   <- collision
```

`sentSequence` advanced only when `drainSendWindow()` sent data frames, never during negotiation. The peer checks the
reply against `SYN.seq + 1` (catplay `verify_neg_seq_ack`: `packet.seq == peer_seq + 1`) and drops a repeated number as
a duplicate. catplay's `NegotiationTx::SynAck` takes a new number with `take_own_seq()`. Fix:
`sendSynchronizationEcho` consumes a new sequence number; **a byte-level resend of the same SYN stays idempotent**
(replays the previous frame without advancing; DiPlay's tunnel mode resends the same SYN every 500 ms).

**Root cause two: only the first SYN got a SYN|ACK.** The old code did `if (firstAdoption) sendSynchronizationEcho(...)`
and answered every later SYN with a bare ACK. BMW **reopens negotiation with a new sequence number on every retry**
(161->191->40..., up to 36 SYNs in one round), so it never got the echo it wanted. catplay's `on_recv_neg_host` sends
SYN+ACK for every SYN. Fix: **every pure SYN (ACK bit not set) gets one SYN|ACK**.

**Pitfall: bare ACKs collide with SYN|ACK numbers.** A bare ACK uses the current `sentSequence`, and SYN|ACK advances
it, so the next bare ACK reuses the previous SYN|ACK's number. The host path therefore **answers with SYN|ACK only**
(it carries the ACK bit and ack field, a bare ACK is redundant anyway; catplay's host path also sends only SYN+ACK).
Cost: the "bare ACK + SYN|ACK" frame order measured on Audi becomes a single SYN|ACK: still valid, but **to be
re-verified on Audi** (the final ACK answers 100 instead of 99; the one-step offset is normal).

**Pitfall: do not answer every frame with the SYN bit with SYN|ACK.** The first version did not separate SYN from
SYN|ACK, and two engines feeding each other ping-ponged forever (unit test `windowBacklogFlushesAfterAcknowledgement`
hung and spun the CPU for 55 s; only `jstack` revealed it). The test must be `control and ACK == 0`.

**Secondary problems (not changed in that round, fixed later)**:
- Outbound client and inbound session ran in parallel: 16 times "BMW is connected over Bluetooth (HFP/A2DP) -
  disconnected", the 3 s retry thread kept tearing down HFP/A2DP, which the car saw as constant Bluetooth disturbance;
- with no target chosen it tried Bluetooth earphones one by one (`HUAWEI FreeBuds 4` / `FreeClip 2`); candidates should
  be only devices that declare a CarPlay / iAP2 service (now `Iap2BluetoothClient.awaitIap2Service`);
- a bare ACK every 500 ms (350 of them), while BMW's LSP declares `ackTimeout=3000ms`; follow the peer's value.

## 9i. 0.1.7 regression on Audi MMI 3315: ctrl advertisement identity fields changed (2026-10-19)

Log `logs/cardeck/CarDeck-log-20261019-141711.txt` (Xiaomi 15 / Android 16, 0.1.7; the same car and phone connected
fine on 0.1.6). Symptom: **iAP2 fully works but the head unit does not open its AirPlay window**.

**Working**: RFCOMM -> `iap2 link ready` -> 0x1D01 -> MFi (32 B, first by memory, 0.58 s) -> 0x5703
(`Audi_MMI_3315_5GHz` ch149) -> app-scoped network joined. The head unit's iAP2 layer was alive (it later sent 0xAE00).

**Stuck point (all four checks at zero)**:

| Check | 0.1.7 (this log) | 0.1.6 (works in the same car) |
|---|---|---|
| 0x4301 arrived | **0 times** | **0.65 s** after 0x4300 |
| head-unit `GET /ctrl-int/1/connect` | **0 times** | yes |
| mDNS `_airplay._tcp` | **0 times** | `CarPlay -> 10.174.189.1:7000` |
| gateway `:7000` | 54 s of `ECONNREFUSED` | connectable at once |

**Root cause: the ctrl advertisement's TXT `srcvers` and instance name had been changed to catplay's values.** 0.1.6
used `name=sender` + `srcvers=950.7.1` (accepted by the car); 0.1.7 changed to `name=iPhone` + `srcvers=566.25.21`
(catplay's sender SDK version), and the head unit **stopped even probing**. These two are the only phone identity the
head unit sees on Wi-Fi; changing them amounts to being a different device. -> Reverted to the 0.1.6 values and pinned by
the test `CarPlayCtrlAdvertiserTest` (never "copy from catplay" here again).

**`_device-info._tcp` advertising removed as well**: Android's `NsdServiceInfo` does not accept port=0, and every real
device reported `The port is missing` (4 times in the log). It was an extra advertisement with no proven benefit, and
its failure only adds noise: removed rather than fixed, since nothing shows this car needs it.

**Other problems fixed along the way**:

1. **0x4300 / 0x4E0E used the placeholder MAC**: the whole log shows the device-status greeting with
   `mac=02:00:00:00:00:00`. `DeviceIdentity` had a 10 s quiet window after a failed resolve, and the BT session was
   often built in the same millisecond Shizuku became ready: after the first failure everything within 10 s returned
   the placeholder (in 0.1.6's log rounds 2 and 3 had the real MAC). Audi compares the 0x4300 transport identifier with
   the RFCOMM peer and silently drops a placeholder. -> `BtSession` resolves with `forceRetry` at session start and logs
   it.
2. **The ctrl advertisement TXT was never logged**: so this failure could not be attributed directly (only by comparing
   versions). -> Every registration logs `name= / id= / srcvers=`, with a warning mark for a placeholder MAC.
3. **The fallback phase no longer announced or looked at mDNS**: after the 4 s invite window it only spun against the
   gateway for 54 s. -> `AirPlayClient` gained a `retryTick` (every 10 retries, about 20 s): force-announce ctrl again
   and log "head-unit invite count + whether mDNS found it".

**Lesson**: moving "the reference implementation's value" straight into production can cost a whole round of a car not
working. Every field that affects **how the head unit recognises us** (TXT, service name, sourceVersion, deviceID) must
be A/B tested in a real car first, and the change must be attributable from the log at a glance.

## 9j. BMW iD8.5 (MGU-86) drops the link 5.4 s after taking 0x5702: the greeting was sent too late (2026-10-23)

Log `F:\Downloads\CarDeck-log-20261021-202434.txt` (**the user sent two copies with identical SHA-256**; 0.1.12, Xiaomi
2106118C / Android 14). Head unit `BMW 31726` / `MGU-86-BMW` (iDrive 8.5).

**Symptom**: all 6 sessions fail, alternating between two errors: "SESSION failed: no head-unit hotspot credentials"
and "SESSION failed: iAP2 link closed while sending 0x4e0d".

**Working** (in every round): RFCOMM **connected by the head unit** (our outbound `BT-C` failed every 3 s) -> link
SYN/SYN|ACK negotiation -> `0x1D01` identity (BMW Group / CinemoHIDComponent) -> MFi (`0xAA01` 611 B certificate,
`0xAA02` 32 B challenge, `0xAA03` 68 B signature, all round trips successful).

**Key structure: the head unit alternates between two capability profiles** (almost every round switches):

| Profile | Send list | EA protocol | 0x5702/0x5703 |
|---|---|---|---|
| A (14 entries) | no 0x5703 | `de.bmw.a4a.bt` protocol=7 | receive=no / send=no |
| B (16 entries) | contains 0x5703, 0xaa06 | `com.bmw.carplay` protocol=8 | **receive=yes / send=yes** |

Only profile B carries wireless CarPlay (with an extra `BMW 31726 Wifi` identity record). Under profile A we skipped the
old protocol per the capability lists and went via 0x4300/0x4301, but this car **neither declares nor sends 0x4301**, so
profile A was bound to fail. Profile B is the one that should succeed.

**Where profile B dies (millisecond timeline, two rounds identical)**:

```
T+0.9s  tx 0xea00 startEASession protocol=8 com.bmw.carplay     <- head unit does not respond
T+2.9s  tx 0x5702 (empty body)                                   <- we request the Wi-Fi configuration
T+3.0s  rx link ACK (len=0)                                      <- after this the head unit sends nothing more
T+8.3s  LINK closed: bt socket closed, read return: -1           <- peer drops the link
T+8.3s  sending 0x4e0d failed -> SESSION failed
```

The gap is a stable **5.39-5.45 s after 0x5702**, never with a 0x5703.

**Decisive evidence: in the wireless profile the device-status greeting was never sent.** `tx=0x4e0*` appears only in
profile A rounds, while a real iPhone (section 12 step 4) sends the burst `0x4E0C/0x4E0A/0x4E0B/0x4E09...` **right after
0xAA05**, before subscriptions / requests. Our code put the greeting **after the 0x5703 wait** (Wi-Fi exchange ->
greeting -> 0x4300 in `Iap2SenderSession`), so BMW waited for the greeting, we waited for 0x5703, and after 5 s the head
unit decided the accessory was not ready -> link dropped.

**A second log (sent by the user 2026-10-22, `CarDeck-log-20261022-135133.txt`) shows it reproduces**: `BMW 17713` /
`MGU-86` (another iD8.5), Xiaomi 23127PN0CC / Android 16, app **0.1.17-debug**. **5 sessions -> 5 tx 0x5702 -> 0 rx
0x5703 -> 5 LINK closed**, each round `0x5702 -> only a link ACK -> head unit drops RFCOMM 5.37 s later -> sending 0x4e0d
fails`. This car had **the 16-entry list every round (always declaring it sends 0x5703)**, without the A/B alternation
of the first log: the head unit kept trying wireless CarPlay in earnest, and we simply did not answer in the order it
wanted. (0.1.17-debug was newer than the local HEAD's 0.1.13, so the build came from another branch / upstream, but the
failure signature is the same.)

**Third piece of evidence: the head unit declares sending `0xEA03` (EA session status) but never sends one.** The
`0x1D01` send list has `0xea03` and the receive list `0xea00`, so it supports EA sessions; but the `0xEA00` body we sent
was:

```
param 0: id=0, u8  = protocolId          <- correct
param 1: id=1, u16 = protocolId          <- wrong: the specification wants the **EA protocol name string**
             here, i.e. "com.bmw.carplay"
```

The old code sent the protocol number as an integer for param 1 (while the log printed `name=com.bmw.carplay`, so the
log hid the problem completely). The head unit got a malformed EA request -> no `0xEA03` -> the CarPlay data channel
never opened -> link dropped after the 8 s budget. Audi's EA protocol never answered `0xea03` either, so the bug existed
for every head unit; Audi just does not depend on it to move on.

**What changed (that round, Audi unaffected)**:

1. **`0xEA00` param 1 carries the protocol name** (`Iap2Csm.string(1, name)`), followed by an 800 ms probe for
   `0xEA03` that is logged, so whether it answered is visible at a glance;
2. the device-status greeting goes out **right after `0xAA05`** (real iPhone order; it used to wait for 0x5703);
3. `0x5703` is awaited in segments: nothing after 1.5 s -> **force the greeting once more** (`force=true`);
4. nothing after another 1.3 s -> resend `0x5702` once;
5. then keep waiting with the old window; on timeout still go via 0x4300/0x4301.

The greeting stays "once per round" (`greetingsSent` is idempotent, only force resends); Audi answers 0x5703 within
milliseconds, never reaches either rescue, and behaves as before.

**Still open** (check in this order if the next real-car try still fails):

1. Whether `0x5702` must carry parameters (we send an **empty body**; Audi accepts it, BMW may not): needs a **real
   iPhone capture in the same car** to settle the parameter numbers;
2. The link has **no periodic keep-alive in NORMAL** (`Iap2LinkEngine` only sends SYN during negotiation); whether 5 s
   of total RFCOMM silence in the wait window triggers BMW's idle teardown: if (1) does not help, add a 1 s bare ACK
   keep-alive next;
3. Our outbound `BT-C` to the head unit's iAP2 service fails every 3 s (we rely entirely on the head unit connecting
   back); the SDP channel selection deserves a review.

## 9j. 0.1.13 cannot connect to the Audi MMI 3315: lists used as a hard gate + self-teardown every round (2026-10-22)

Log: `CarDeck-log-20261022-185112.txt` (0.1.13, Xiaomi 15 / Android 16 / Audi_MMI_3315_5GHz).

### Chain of events (by the millisecond)

```
18:47:59.934  0x1D01: Audi_MMI_3315 / AUDI 5163 / DELPHI
              send list(2)=0x4301 0xea03   receive list(5)=0x4e0c 0x4e09 0x4300 0xea00 0xea01
18:48:00.634  warning "head unit does not declare 0x5702/0x5703 - skipping the old protocol"
              -> [tx=0x5702] appears 0 times in the whole log
18:48:03.72   0x4301 arrives once (with SSID / PSK / fe80::900c:... / 10.174.189.1:7000)
18:48:05->49:29  specifier join, 84 s without callbacks -> onUnavailable
18:49:37.885  second join succeeds (1.9 s, bindProcessToNetwork=true)
18:49:37->51:07  45 connects to 7000, all ECONNREFUSED; _airplay._tcp not found; head-unit invites = 0
from 18:49:55, 5 rounds  each 0x1D00->0x1D01->MFi->0x4300 -> no 0x4301 in 8 s
                 -> "SESSION failed: no head-unit hotspot credentials" -> self-teardown
```

### Compared with earlier versions (same car, same hotspot)

| Log | 0x5702 | Result |
|---|---|---|
| `CarDeck-log-20261019-101353-all.txt` | sent (every round) | 0x5703 answered in 122-368 ms; `TCP connected 10.174.189.1:7000` 30 ms after joining; SETUP / RECORD all fine |
| `CarDeck-log-20261020-103031.txt` | sent (every round) | same, `0x5703` 166 ms, join 1.9 s, port usable at once |
| `CarDeck-log-20261022-185112.txt` (0.1.13) | **0 times** | port 7000 **RST throughout**, all 4 rounds dead |

**Key inference**: this Audi starts its AirPlay service only after receiving 0x5702. Without it the head unit emits one
lone 0x4301 and then stays silent for the whole round, with nothing listening on the port.

### Two root causes

1. **The lists used as a hard gate**: `legacyWifiExchange = declaresReceiveWifi != false && declaresSendWifi != false`.
   Audi provides lists without 0x5702 -> skipped. Yet in the same log the `PROFILE` line computed `wifiGen=UNKNOWN`
   (reason "conservatively use the old protocol"): **the two decisions were not wired together**, the log said one
   thing and the code did another.
2. **Self-teardown every 8 s**: `wifiFromExchange == null && no 0x4301` threw an `IOException`. In a real iPhone trace
   (13a) the head unit sends the ctrl invite **21 s** after joining; restarting every round reset the head unit's wait
   timer over and over, so it never arrived.

### Fix (0.1.14)

- Dispatch uses only `ReceiverProfile.wifiGen`: only `NEW` skips 0x5702; `LEGACY` / `UNKNOWN` always send it; 1.5 s
  wait window (4x the Audi measurement).
- Without credentials but with a known hotspot SSID, synthesise an `airPlayPort=0` endpoint and continue
  (`credentiallessEndpoint`); a late 0x4301 still hot-updates through `onLateEndpoint`.
- The 0x4301 path also writes `lastWifiPsk` / `lastCarWifiSsid` (previously only the 0x5703 path did, so the manual
  fallback button wrongly reported "no hotspot password yet").
- The ctrl advertisement port is fixed (re-registration only swaps the mDNS registration and reuses the socket; the old
  code changed the port every time, measured 43857->33383->33125->38151->40697->38015, with gaps of up to 1.2 s).
- Long-lived connections get `TcpLiveness` (control / event / video, idle=10 s / count=3 / userTimeout=20 s).
- Unit tests pin both directions: `sparseDeclarationsStillSendWifiRequest` (the Audi 3315 form must send),
  `fullDeclarationsWithoutOldProtocolSkipWifiRequest` (the BMW iD9 form does not),
  `knownCarSsidAvoidsCredentiallessAbort` / `noCredentialAndNoKnownSsidStillFails`.

### How to read similar problems next time

- A log with "skipping 0x5702" followed by **`ECONNREFUSED` (not a timeout / unreachable)** = the head unit is on the
  network but nothing listens on 7000: suspect first that the head unit's CarPlay stack was never started, not the
  network or address.
- A `[tx=0x5702]` count of 0 was the decisive evidence here; count it first for any "wireless does not connect".
- The matching rhythm on the iPhone sysdiagnose side: join -> invite after **21 s** -> first MFi 403 (normal) -> wait
  **another 18-20 s** until the head unit re-invites by itself -> success. Any client strategy of "start over after a
  dozen seconds without progress" collides with this rhythm.

## 10. Reference file index

> Paths are the **code-reading sources** under `reference/` (xcertplay and DiPlay share their source and receiver
> logic, either works); **the bench actually ran DiPlay** (`reference/DiPlay/`).

| Content | Location |
|---|---|
| Link-layer byte format / checksum | `reference/carplay-wifi-extractor/example/iap2/link_layer.py` |
| RFCOMM transport / UUID | same, `transport/bluetooth.py`; xcertplay / DiPlay `CarPlayController.kt` |
| CSM framing / TLV | `reference/xcertplay/.../iap2/wire/Iap2CsmFramer.kt`, `Iap2WireCodec.kt` |
| Wireless control sequence | `reference/xcertplay/.../transport/Iap2WirelessControlClient.kt` |
| 0x1D01 identity fields | `reference/xcertplay/.../transport/Iap2IdentificationClient.kt` |
| Message endpoint catalogue | `reference/xcertplay/.../iap2/catalog/Iap2Endpoint.kt` (931 lines, all parameter layouts) |
| Bonjour details | `reference/xcertplay/.../network/CarPlayBonjour.kt` |
| Receiver AirPlay session | `reference/xcertplay/.../airplay/AirPlaySession.kt` + `PairVerify.kt` |
| Bench (the version actually run) | `reference/DiPlay/` (with real-car diagnostics / audio improvements; same source as xcertplay) |
| Complete sender reference | `reference/catplay/carplay/catplay_carplay/src/carplay_tx/` and others |
| Windows receiver (WinPlay) | `reference/WinPlay/` (TS receiver; device filter in 9d) |
| MFi authentication (receiver side) | `reference/xcertplay/.../mfi/`; Oligo's blog (evidence of one-way authentication) |
| Decrypted firmware (read only) | ludwig-v/wireless-carplay-dongle-reverse-engineering (not cloned) |

## 12. MIB3 Wi-Fi tunnel (a second iAP2 link on top of the AirPlay session, 2026-10-19)

> **Second correction 2026-10-23 (this block wins)**: this section's conclusion "MIB3 needs the tunnel or it drops the
> link after 10-12 s" **holds**; the correction earlier that day, "CLASSIC_MIB3 ignores markers and never
> negotiates", **was disproved**. The unified model (full chain of evidence):
> (1) **The link must be negotiated to NORMAL**: the head unit's markers mean it is waiting for our session table, and
>   it resends them at 1 Hz **forever** (an owner's 0.1.17 log showed far more than the old "2-5"); without an answer
>   the head unit tears down the whole wireless stack ~10 s after RECORD (0.1.9: 10.17 s, 0.1.17: 10.18 s, regardless
>   of healthy heartbeats). "Audi entered CarPlay normally when 0.1.9 ignored markers" was a misreport: 0.1.9's own log
>   shows the 10.17 s teardown. A real iPhone's `iAPChannel FeatureKey=0` means the negotiation **completed** with zero
>   tunnels, not "did not take part in negotiation".
> (2) **Switching the screen needs modesChanged re-asserted after negotiation**: in 082446 (0.1.14) negotiation / MFi /
>   subscriptions all succeeded and the session was healthy for 35 s+, yet the head unit did not switch the picture:
>   the second control session reset the head unit's resource arbitration, invalidating the modesChanged from early in
>   the session. The timing verified by AADisplay r17 long runs: after auth + burst **first at +1.5 s, re-asserted at
>   +4/+12/+30 s** (never during 0xAA00/0xAA05: competing with iAP POSTs on the control channel kills the Audi at
>   ~12 s). 0.1.12's "picture for 3 s then drop" was negotiation hitting the just-started HMI, the same timing issue.
> Current implementation: marker-driven negotiation is the same for every generation;
> `VirtualIap2Link.onControlSessionComplete` -> `SessionController.scheduleModesReclaim`. The "ignore markers" dispatch
> written from the disproved correction was deleted.

> Source of facts: `reference/AADisplay` (branch AADisplay-Split-Lite-Carplay, **GPL-3.0, read-only reference**). They
> captured a real handshake with Frida hooked into a jailbroken iPhone's mediaserverd; this repository implements
> `airplay/VirtualIap2Link.kt` from it (protocol facts taken over, code written independently).

**Why it is needed (original conclusion)**: after the AirPlay session is up, MIB3 also requires the phone to open a
second iAP2 link on the **event channel** and run the control session again; answering with link ACKs only (empty
echoes) makes MIB3 tear everything down after about 10-12 s (reproduced at 10.2 s in two real-car rounds in this
repository on 2026-10-19; previously misdiagnosed as missing heartbeats, and the three heartbeat fixes in 9e do not save
this car). AADisplay's source comment says an empty ACK alone makes MIB3 tear AirPlay control down in ~12 s with no
picture.

**Asymmetric transport** (key):
- head unit -> phone: link bytes come as `POST /command` on the **event channel** (TCP 5001), bplist
  `{"type":"iAPSendMessage","params":{"data":<link frame>}}`;
- phone -> head unit: `POST /command` on the **RTSP control channel** (7000) (the same iAPSendMessage envelope, ChaCha
  framed); written on the event channel, MIB3 **does not read it** (DiPlay is lenient, Audi is not).

**Sequence** (Frida, real device):
1. After the screen stream SETUP 200 (dataPort received), the phone sends a DETECT marker **once** (over the control
   channel): sent earlier it is flushed during the SETUP exchange; later head-unit markers are probe resends and are
   ignored;
2. the head unit answers with a SYN (event channel, pure SYN with LSP) -> the phone answers SYN|ACK (control channel).
   The LSP declares **our own** parameters: maxOutgoing=0x7F (echoing the peer's table limits us to the peer's small
   window and bursts back up), maxLength keeps the peer's value; the session table follows the peer's (a later version
   may add file transfer 0x0C, needed for cluster cover art);
3. after link NORMAL the phone sends 0x1D00 -> head unit 0x1D01 -> phone 0x1D02 + 0xAA00 -> (head unit 0xAA06 auth
   serial -> phone sends 0xAA00 again) -> head unit 0xAA01 certificate -> phone 0xAA02 challenge -> head unit 0xAA03
   -> phone 0xAA05 (accepted without verifying the signature). **The challenge length cannot be looked up from any
   version** (it depends on the generation of the head unit's MFi chip, which is in no identity / version field): the
   Frida-captured tunnel used 20 B, DiPlay CARLINKIT accepts only 32 B, and the same MMI 3315 wants 32 B over
   Bluetooth; so the tunnel path uses `MfiLengthMemory` (key = identity string + " tunnel", separate from the Bluetooth
   link), cold-start baseline 20 B first, switching length after 6 s / 3 s of silence, switching at once on rejection
   (0xAA04), and still sending 0xAA05 if everything was silent;
4. after 0xAA05 the phone sends a **burst** (before any head-unit subscription): 0x4E0C wireless session identifier
   (random uppercase UUID), 0xA100 (param 6 empty), 0x4E0A language "zh", 0x4E0B device time (u64 seconds + s16 zone
   minutes + u8 DST), 0x4E09 device name "iPhone", 0xFFFA StartLocationInformation, 0xFFF0 GPRMC status; the last two
   are byte constants from Frida (`VirtualIap2Link` companion);
5. answers to head-unit subscriptions: 0x5000->0x5001 NowPlaying (**the data path for cluster song info**, now fed by
   `media/CarNowPlaying`), 0x4157->0x4158 communications empty state, 0x4154->0x4155 no call, 0x4170->0x4171 empty
   list; 0xEA02 (requestAppLaunch) / 0xA101 (VehicleStatus) / **0xFFFB (PASCD location samples, ~1 Hz) get only a link
   ACK; a missing ACK also makes the head unit close the control channel after ~10 s**.

**Engine changes that came with it** (`iap2/Iap2LinkEngine.kt`, all unit tested):
- an initiator receiving SYN|ACK (not a pure SYN) also adopts the session table and advances the in-order mark (adopt
  only, no echo);
- a responder that echoed SYN|ACK treats negotiation as complete (the real device sends 0x1D00 without waiting for an
  ACK);
- the echo uses ownSyncOf (maxOutgoing=0x7F), and the send window follows **our declared value**.

**AADisplay reference index**: `carplay/protocol/WifiIap2Link.kt` (sequence + constants),
`CarPlayRtspSession.kt:642` (how to write control channel POST /command), `docs/AUDI_CARPLAY_CONNECT_ANALYSIS.md`.

## 13. Real iPhone sysdiagnose comparison (2026-10-21/17, two cars, three captures)

Source: the unified log (airplayd / mDNSResponder) of iOS 24A446 (18.x) sysdiagnoses, read with
`/usr/bin/log show --archive system_logs.logarchive --predicate ...` (zsh's built-in `log` shadows it, so the full path
is required).

### 13a. Audi MMI 3315_5GHz: "the first connection is always refused, the re-invite works"

Both captures (same car, 14:24 / 14:32) show the same timeline:

1. Joined automatically through the BT provisioning chain (airplayd logs a `CarPlay UUID`) -> about **21 s later** the
   head unit's first ctrl invite (`HandleControlServerEvent command 'connect'`);
2. connects to `[fe80::...%en0]:7000` (**IPv6 link-local**, 1022 ms) -> pair-verify M1-M4 **succeeds in 32 ms**;
3. **the MFi step is refused with 403** (`MFi failed: 200403 Forbidden -> Stage 1 failed -> StartupFailed`): **a real
   iPhone is refused on the first connection too**, so it is the head unit's own state, not a client problem;
4. **the head unit re-invites by itself 18-20 s later**, the second verify takes 16 ms, MFi (challenge 64 B /
   certificate 607 B) passes, Stage 1/2 -> authorised -> streaming. Throughout, **Bluetooth stays up and the network does
   not change**;
5. in the successful session the **`iAPChannel` FeatureKey negotiates to 0** (no Wi-Fi iAP2 tunnel): this contradicts
   our "MIB3 must have a tunnel" conclusion, so A/B test in a real car before changing the tunnel strategy (see the
   correction in section 12);
6. misc: the SETUP reply may lack streamID (the iPhone fills in 0); RECORD round trip 17 ms; stream-level heartbeat
   Standard 2017 ms; session-level keepAlive 10 s (LowPower).

Implemented (2026-10-22): `AirPlayClient.pairingForbidden` (set on a 403 at any step) -> the controller no longer takes
the "drop BT, phase two" path and no longer downgrades to et=NONE; the pairing loop backs off 5 s after a 403 (the head
unit's ready window is ~20 s, and rapid retries may keep resetting its state machine).

### 13b. FAW-Volkswagen Tayron 2023 (VW WLAN): "joining by hand never works"

- 2.4 GHz `VW WLAN` joined by hand x5 rounds: `CarPlay UUID: N/A`, **zero mDNS answers for `_airplay._tcp`
  throughout**, no invite; the iPhone leaves the network after 67 s, in a loop;
- after the BT provisioning chain is ready it joins `VW WLAN_5GHz` automatically (with a UUID) -> **77 ms later** the
  head unit invites -> `fe80::...:7000` (1831 ms) -> verify 21 ms -> MFi succeeds -> normal streaming for 3 minutes (no
  403);
- the head unit's SETUP reply carries viewAreas / initialViewArea structures without declaring the feature (the iPhone
  only warns and tolerates it).

Conclusion: this model activates its CarPlay stack only for "the join that the iAP2 provisioning chain brought along";
a car hotspot saved in the system or joined by hand blocks the connection (our "same SSID already saved" cleanup prompt
is a key step for this car), and the SSID from 0x5703 must be used exactly (dual-band SSIDs; the successful path is
`_5GHz`).
