# Head-unit microphone uplink: research notes (2026-11-17)

> Status: **WinPlay end to end works (0.1.42.37, 2026-11-17), real-car verification pending**. Protocol facts come from
> DiPlay's source (head-unit side, GPL-3.0, facts only, no code copied) and its PR #898, and catplay issue #15; the
> Android-side permissions were measured on an S24 Ultra (SDK 37). Later work (call voice to the car, Android Auto's
> mic channel) is in AGENTS.md section 11.

## Conclusion

The head-unit microphone -> phone path is **clear at the protocol level and almost mirrors our existing media audio
downlink**; the real difficulty is on Android: handing the received sound to Gemini / calling apps.

| Step | Feasibility | Notes |
|---|---|---|
| Ask the head unit for a mic stream | Clear | The phone (us) sends an audio SETUP with `input=true` |
| Receive and decrypt the car's mic packets | Clear | Same RTP + ChaCha20-Poly1305 as the downlink, opposite direction, different key label |
| Head-unit voice key | Clear | The car sends a `requestSiri` command on the event channel |
| Hand the sound to Gemini / VoIP apps | Possible, unverified at the time | shell has `MODIFY_AUDIO_ROUTING`; try AudioPolicy record injection |
| Regular phone (carrier) calls with the car mic | Not possible | The call uplink is in the baseband / audio HAL; no injection without root |

## Protocol (phone = sender)

### 1. Setting up the mic stream: audio SETUP

Started by the **phone** (the same RTSP request as our media audio SETUP). DiPlay's handling shows the head unit looks
at these fields:

- `type: 100` (main audio)
- `audioType`: `speechRecognition` (voice assistant) / `telephony` (calls): the car **always opens the mic** for these;
  `default` / `compatibility` open it only with `input: true` (or an uplink port), e.g. an app recording a voice message
- `input: true`
- **`dataPort`: a UDP port the phone itself listens on**; the car sends mic packets to "phone address:this port"
- `streamConnectionID`: needed for key derivation (below)
- `audioFormat`: **uplink and downlink share one format** (DiPlay PR #898: limiting only the output format does not
  help, the iPhone picks one that works both ways). Choose from the intersection of `/info` `audioInputFormats` and
  `audioOutputFormats`
- `framesPerPacket` (optional): milliseconds per packet

The reply carries the car's own downlink `dataPort` / `controlPort` as usual (Siri's answer and the far end of a call go
down).

### 2. Packet format (car -> phone, UDP)

Same as the media audio we send to the car, opposite direction:

- 12-byte RTP header **in plaintext**: `0x80`, PT = stream type (100), sequence (big-endian u16), timestamp
  (big-endian u32), SSRC
- Payload ChaCha20-Poly1305; **AAD = header bytes 4..12** (timestamp + SSRC)
- nonce = 4 zero bytes + 8-byte little-endian counter; packet tail = 16-byte tag + the same 8-byte little-endian counter
- The timestamp grows by the sample count per packet; Opus clocks follow the format (48k: +960 per 20 ms, 24k: +480,
  16k: +320)

### 3. Key

`HKDF-SHA512(ikm = pairing shared secret, salt = "DataStream-Salt" + streamConnectionID, info = "DataStream-Input-Encryption-Key", length 32)`

The downlink uses the same formula with info `DataStream-Output-Encryption-Key`, which we already use for media audio
(see `AirPlayClient`).

### 4. Formats

- PCM: **16-bit big-endian** mono (Android records little-endian; the car swaps the byte order, so we swap it back)
- Common values (`audioFormat` bits): `0x100` = LPCM 32k mono, `0x4000` = LPCM 48k mono, `0x154` = combined 8/16/24/32k
  mono PCM; Opus: `0x10000000` 16k, `0x20000000` 24k, `0x40000000` 48k
- Calls usually use vocoder rates (8/16/24/32k); Siri is often seen at 24k
- One head unit in catplay issue #15: MainAudio `audio_input_formats` = PCM 8k/16k/24k mono; telephony and
  speechRecognition `input_latency_micros` = 106000

### 5. Triggers

- **Car voice key -> phone**: the car sends `{"type": "requestSiri", "params": {"siriAction": 2}}` (press) and
  `siriAction: 3` (release) on the event channel. We turn it into "summon Gemini".
- **Phone initiated** (e.g. "Hey Google"): the phone just sends the speechRecognition SETUP and the car opens its mic.
- `/info` `modes` contains `appStates` (`speech` with `speechMode`, `phoneCall`): during voice the speech state may also
  have to be declared through modesChanged so the car switches to its voice UI / ducks media (not verified as required).
- When DiPlay opens the mic: for calls it waits for the first downlink packet (the far end is the echo-cancellation
  reference); for Siri / voice messages it opens anyway if no downlink arrives within 500 ms.

## Android side: handing the sound to apps

Measured on the S24 Ultra, `com.android.shell` (our Shizuku UserService) holds: `MODIFY_AUDIO_ROUTING`,
`CAPTURE_AUDIO_OUTPUT`, `CAPTURE_AUDIO_HOTWORD`, `MODIFY_PHONE_STATE`, `MODIFY_AUDIO_SETTINGS_PRIVILEGED`.

- **Candidate approach (then unverified)**: register an `AudioPolicy` through hidden APIs in the UserService with an
  `AudioMix` using `ROUTE_FLAG_LOOP_BACK` + `RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET` (MIC / VOICE_RECOGNITION /
  VOICE_COMMUNICATION), get an AudioTrack from `createAudioTrackSource` and write the car mic PCM into it; the system
  treats it as the input of those capture sources and hands it to whichever app records. This is the platform's own
  record injection mechanism.
- Risks to check:
  1. Gemini's hotword detection (`src:HOTWORD`) mostly runs on the DSP and cannot be injected; dictation after waking
     may use a normal capture source and could work.
  2. The carrier-call uplink is in the baseband, injection does nothing; VoIP (WhatsApp, LINE, Telegram) mostly uses
     VOICE_COMMUNICATION and might work.
  3. While injecting, the phone's own microphone is replaced; the policy must be unregistered at the end (like virtual
     displays: think about leftovers and the three cleanup layers).
  4. ROMs (Honor / Samsung) restrict AudioPolicy differently.

## Experiment 1: record injection (2026-11-17, S24 Ultra / SDK 37) - passed

A standalone dex run through `app_process` as shell (uid 2000), without touching the app: `AudioMixingRule`
(`setTargetMixRole(MIX_ROLE_INJECTOR=1)` + `addRule(attrs, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET=2)` for each of
MIC / VOICE_RECOGNITION / VOICE_COMMUNICATION / DEFAULT / UNPROCESSED) -> `AudioMix` (48k mono `CHANNEL_OUT_MONO`,
`ROUTE_FLAG_LOOP_BACK=2`) -> `AudioManager.registerAudioPolicy` returns 0 -> `createAudioTrackSource(mix)` writes a
1 kHz sine.

| Capture source | Before injection | During injection |
|---|---|---|
| MIC | rms 507, 1k share 0.000 (real room sound) | rms 8485, 1k share 1.000 |
| VOICE_RECOGNITION / VOICE_COMMUNICATION / DEFAULT / UNPROCESSED | - | all 1k share 1.000 |

Result: the real microphone is **fully replaced** while injecting and comes back after unregistering. Pitfalls:

- Samsung's `CompatSandbox` reads `ActivityThread.getConfiguration()`; before building a system context
  `mConfigurationController` must be filled in (otherwise NPE; scrcpy does the same).
- The binder thread pool does not exit with app_process; call `System.exit(0)`.
- This run recorded **in the same program**; whether other apps (recorder, Gemini) are injected was left to
  experiment 2.

## Experiment 2: are other apps injected? (2026-11-17) - passed, except the hotword

While `MicInject hold 60` injected 1 kHz, the user operated the phone; the `APM_AudioPolicyManager` log shows which
device each capture was routed to (`0x80000100` = `AUDIO_DEVICE_IN_REMOTE_SUBMIX`, i.e. injected; `0x80000004` =
`AUDIO_DEVICE_IN_BUILTIN_MIC`):

| App / use | Capture source | Routed to | Result |
|---|---|---|---|
| Samsung voice recorder | MIC (1) | `0x80000100` REMOTE_SUBMIX | injected |
| Gemini dictation after waking | VOICE_RECOGNITION (6) | `0x80000100` REMOTE_SUBMIX | injected |
| Gemini "Hey Google" hotword | HOTWORD (1999) | `0x80000004` built-in mic (SoundTrigger / DSP) | not affected |

The user confirmed on the device: the recorder played back the injected beep, and Gemini dictation heard no real voice.

Result: **voice input after waking can use the car microphone**; hotword detection runs on the chip's SoundTrigger and
cannot be injected, so "Hey Google" still has to be spoken to the phone (or Gemini is summoned directly with the car's
voice key `requestSiri`). While injecting, **the phone's own microphone is completely unavailable to these apps**, so
the policy may only be registered while a voice / call stream is alive and must be unregistered right after.

## Suggested verification order (as planned)

1. **Android-side injection first** (no protocol): register the injection AudioPolicy in the UserService, feed a test
   tone (e.g. 1 kHz sine) and check whether the system recorder / Gemini dictation hears it. If this fails, the rest is
   pointless.
2. Protocol: like the media audio downlink, add a speechRecognition + `input=true` SETUP, open a UDP listener and
   decrypt, first saving the received PCM to a file (testable on a computer once WinPlay supports the mic uplink).
3. Wire it up: Gemini summoned (or `requestSiri` received) -> open the mic stream -> inject -> clean up at the end.

## v1 implementation (2026-11-17)

Only the **car voice key** path (`requestSiri`); "Hey Google" still used the phone microphone.

- `shizuku/CarInputServiceImpl.startMicInjection(sampleRate)` / `stopMicInjection()` (AIDL appended at the end):
  registers the injection AudioPolicy and returns the write end of a pipe; pipe EOF (the app died) unregisters
  automatically, so the phone microphone is never held forever.
- `airplay/CarMic.kt`: format negotiation (speechRecognition tier, input intersect output, PCM mono
  24k > 16k > 48k > 32k > 8k), packet decryption (big-endian to little-endian), Input key derivation; unit test
  `CarMicTest`.
- `AirPlayClient.runMicStream` / `teardownMicStream`: SETUP with `input=true` + the local UDP `dataPort`, then one
  more RECORD.
- `audio/CarMicController.kt`: car voice key -> open UDP, start injection, SETUP -> wait for the first packet
  (<= 1.5 s) -> `cmd voiceinteraction show` to summon the assistant (refused on the S24, now falls back to
  `KEYCODE_ASSIST`) -> poll `isAssistantListening` every 500 ms: after the mic was opened, 2 s of closed mic / 10 s
  without opening / more than 2 min ends it (close the socket, remove injection, TEARDOWN). Pressing the voice key again
  ends it and closes the assistant. Both session retirement (`retireSession`) and stream end (`onEnded`) stop it.
- Any failed step falls back to "just summon the assistant, using the phone microphone".

To verify, look at the `CARMIC:` log lines: `microphone stream SETUP round trip ...`, `OK car microphone arrived (Nms)`,
`ended (reason) received=N bad=N`. Known risk: if media audio is also type=100 (PCM path), a head unit whose TEARDOWN
looks only at the type may tear the media stream down as well (the log warns).

## v2 (2026-11-17): automatic switching + downlink silence

- **Experiment 3 (registering the injection while a recording is already running) passed**: the same AudioRecord
  switches to the injected sound within 1 s, the other app does not need to restart recording. -> Made it
  **automatic**: during a session, every 400 ms the UserService `activeMicApps()` (AppOps `startWatchingActive`
  RECORD_AUDIO) says who records; if another app records, the car microphone opens, and it is removed after 2 s without
  a recorder. The voice key path stays (10 s grace).
- **WinPlay source reading** (`resources/app.asar.unpacked/build/engine/protocol/cpStack.js`): it declares PCM mono |
  Opus input formats for `speechRecognition`, and **opens the mic only after the first downlink packet of that stream**
  (mic runs while audio flows), so v1, which only received, never got a mic on WinPlay. v2 sends a silent downlink
  packet every 20 ms while the mic stream runs.
- Cost: the watch is one local binder call (reads a table, no shell process); the mic stream exists only while someone
  records, about 50 packets/s each way (24k mono PCM, about 0.4 Mbps).

## WinPlay end to end (0.1.42.37) - working

Samsung recorder records -> car microphone opens automatically -> first packet after 145 ms -> 422 packets in 8 s,
0 bad -> playback is the computer's microphone. Pitfalls on the way (each one gives "everything looks right but the
recording is empty"):

1. **The UserService was refused the injection track** (`Cannot create AudioTrack`): `AudioPolicy.createAudioTrackSource`
   uses the process opPackageName (DroidCar's package) + uid 2000, an identity mismatch. Now builds
   `AudioTrack.Builder().setContext(shell)` itself as AOSP does (API 34+; 12/13 fall back to the system method).
2. **The track tags must start with `addr=`**: APM only matches the start; an extra `fixedVolume;` kept the track out of
   the mix and the recording got silence.
3. **Receive-only -> WinPlay does not open the mic**: it calls `openMicUplink` only after the first downlink packet of
   the stream, so a silent downlink must keep flowing.
4. **WinPlay mic header bug**: `h.writeUInt16BE(m.seq)` misses the offset, so the sequence overwrites bytes 0..1
   (`0x80` / PT are lost). We no longer check the RTP version bits; Poly1305 decides authenticity (the AAD covers only
   4..12 and is unaffected); pinned by a unit test.
5. Connection IDs are kept below 2^53 (a JS receiver reads plist integers as Number and rounds them, so the salt would
   not match). Not the cause here, kept as a precaution.
