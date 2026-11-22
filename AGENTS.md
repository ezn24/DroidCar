# AGENTS.md - Guide for AI coding assistants and developers

This file is the working memory of the project: build rules, architecture, protocol facts and every pitfall that has already cost a debugging session. Read the section that covers the code you are about to change before changing it. Items marked "(unverified on a real car)" were only tested on a bench receiver or not at all.

## 1. Overview

- DroidCar turns a non-rooted Android phone into a **wireless CarPlay sender** (the iPhone role). Reference device: Honor Magic7 Pro. Bench receivers: DiPlay on a Redmi K40, WinPlay (Windows), CarPlayStation (Windows). Real cars: Audi MIB3 (MMI 3315, 6318, 9310, 4xxx), BMW, GM, VW MIB3, Ford, Cadillac CT5.
- The phone draws its own head-unit launcher on virtual displays, encodes it (H.264, optionally HEVC) and streams it to the head unit; head-unit touch is injected back into the virtual displays through Shizuku (shell uid 2000).
- Protocol chain: Bluetooth iAP2 (RFCOMM) -> head-unit Wi-Fi hotspot credentials -> Wi-Fi join -> mDNS (`_carplay-ctrl._tcp`, `_airplay._tcp`) -> AirPlay (pairing, encrypted RTSP control, event channel, stream setup) -> video + audio streams (+ optional microphone uplink).
- Shizuku is mandatory. Without it there are no virtual displays, no touch injection and no window/display state.
- Hard fork of upstream `shaonialife/cardeck` (MIT), renamed DroidCar on 2026-11-23 and relicensed GPL-3.0 (`LICENSE`); the upstream MIT notices must stay in `NOTICE`. Repo: `ezn24/cardeck-neo` (name kept), working branch `neo`. Wireless-adapter mode was ported from `zz85/cardeck` (MIT). Upstream fixes can still be cherry-picked (MIT into GPL is allowed); our changes cannot flow back under MIT.

### Package and identifiers

- Source root: `app/src/main/java/io/github/ezn24/droidcar/`. Package `io.github.ezn24.droidcar` equals the `applicationId` (installs side by side with upstream CarDeck and the earlier CarDeck Neo builds).
- Remote-debug variant `applicationId`: `io.github.ezn24.droidcar.rd`.
- Always build component names as `packageName + "/" + X::class.java.name`. Never hard-code package strings or use relative class names (see Remote debug bridge). The external `cardeck-bridge` repo must follow the same package if it references it.
- A new package name is a new app: Shizuku permission, notification-listener access, overlay permission and appops grants must all be granted again.

### Language conventions

- Code comments and log messages: English (converted from Chinese 2026-11-21; logs exported before that are Chinese, so log parsers such as `tools/extract_car_fixtures.py` match both). Comments explain why and the measured evidence, not what the code does. A few hard-coded UI strings in Kotlin are still Chinese and should move to string resources.
- User-facing UI text: Android string resources only. Default `values/` is Simplified Chinese, `values-b+zh+Hant/` Traditional Chinese, `values-en/` English. Keep all three in sync. Settings menus must resolve text dynamically, never cache labels in static lists (language switch).
- `reference/` holds GPL reference repos (gitignored). Read for protocol facts only; never copy code into this project and never redistribute.

## 2. Build, test, release

### Commands

```bash
./gradlew assembleStandardDebug          # daily build (remote-debug build: assembleRemoteDebug)
./gradlew testStandardDebugUnitTest      # unit tests (mostly protocol / codecs)
./gradlew :app:lintStandardDebug         # Android Lint; existing issues are baselined, new ones fail
./gradlew spotlessApply                  # formatting (ktlint official, rules in .editorconfig)
adb -s <serial> shell am force-stop io.github.ezn24.droidcar   # always before install
adb -s <serial> install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

- Flavor dimension `bridge`: `standard` (daily/release, normal package) and `remote` (`.rd` suffix, debug bridge code only in `app/src/remote/`, dependencies via `remoteDebugImplementation`; nothing reaches standard builds). Task names are flavored (`assembleStandardDebug`, `lintStandardDebug`, `testStandardDebugUnitTest`).
- Do not start emulators. Real-car verification is done by the user. Do not claim a build or test passed unless it was actually run.

### Installing on the phone

- **Force-stop before install.** During a session the system install confirmation dialog lands on a car-side virtual display where nobody can tap it (`User rejected` / hang).
- If it is still rejected, run `input keyevent KEYCODE_WAKEUP` first: with the screen off the unanswered dialog is auto-rejected (`INSTALL_FAILED_ABORTED`, reproduced three times 2026-10-15).
- **Release then debug fails**: `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. Either `adb uninstall` (loses data and Shizuku grant) or, preferred, verify with `./gradlew :app:assembleStandardRelease` output. Release builds are not debuggable, so `run-as` fails; use the log-export broadcast instead.
- **Uninstall does not free virtual displays** (2026-10-18). They are owned by the Shizuku UserService child process (`io.github.ezn24.droidcar:car_input`, uid shell), which survives uninstall. Installing then hangs (dialog on a virtual display) and every retry stacks another `PackageInstallerActivity` (11 observed). Recovery, in order:
  1. `adb shell ps -A -o PID,NAME | grep car_input`
  2. `adb shell kill <PID>` (displays are released immediately)
  3. `adb shell am force-stop com.android.packageinstaller`
  4. `adb install` again.
- `adb install --user 0` or `-g` does not bypass Honor's install confirmation.
- Check leftover displays: `dumpsys display | grep -oE '"CarPlay[A-Za-z]+", displayId [0-9]+'`.
- Wireless adb streaming install can hang silently on large APKs; use `adb push` + `pm install`.

### Versioning, CI and signing

- `appVersionName` lives in `app/build.gradle.kts`; `versionCode` is derived.
- Build number = commit count: pass `-Pdroidcar.buildNumber=$(git rev-list --count HEAD)` (replaced `run_number` on 2026-11-18). Result: `versionName=<appVersionName>.<N>`, `versionCode=base*10000+N`. **Local release builds must pass the same value**, otherwise the local APK and CI APK versions diverge and CI builds may refuse to install over it. Without `-P` the version is the bare base (e.g. `1_130_000`).
- CI: `.github/workflows/android-ci.yml` builds a properly signed APK on every push, artifact `DroidCar-v<version>-release.apk`, uploaded as Actions artifact only (`permissions: contents: read`).
- Signing secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, optional `EXPECTED_CERT_SHA256`. Missing `KEYSTORE_BASE64` fails the pipeline; Gradle `check` also blocks "build number set but no `keystore.properties`" so a debug-signed "release" cannot escape.
- Local release signing needs `keystore.properties` + `.jks` in the repo root (gitignored; template `keystore.properties.example`).
- Signing is irreversible: users who installed a debug-signed build must uninstall to move to the real certificate. The first public build must use the real keystore. A leaked key lets anyone ship updates; a lost key ends updates for existing users. Keep keystore and passwords only locally / in a password manager.
- Release steps:
  1. Bump `appVersionName`.
  2. `./gradlew :app:assembleStandardRelease -Pdroidcar.buildNumber=<N>`.
  3. `apksigner verify --print-certs app/build/outputs/apk/standard/release/app-standard-release.apk`: CN must not be `Android Debug`.
  4. Rename to `DroidCar-v<version>.apk`, tag, `gh release create ... --latest`. Release notes include SHA-256 and certificate fingerprint. GitHub Release notes list what changed and are generated from the commit messages, so write commit messages as user-readable change descriptions.

### Commits

- Workflow: finish the feature, install for the user to verify, commit only when the user says "commit". Push only on explicit request; until then change local files only.
- Hooks: `.githooks/pre-commit` (enable once per clone: `git config core.hooksPath .githooks`) formats staged kt/kts and runs lint; failures block. Baseline `app/lint-baseline.xml`; fixed entries expire automatically; intentional waivers via `./gradlew :app:updateLintBaseline` after review.
- **Author/committer email must be a GitHub noreply address.** For this repo: `62716651+ezn24@users.noreply.github.com`. (Upstream `shaonialife/cardeck` uses `16135960+shaonialife@users.noreply.github.com`; that belongs to upstream.) Never a company or private address: pushed commits are permanently indexed. Defenses: repo-local `.git/config` email overriding the global one; `pre-commit` rejects non-whitelisted `user.email`; `pre-push` scans `%ae`/`%ce` of every pushed commit (covers `--no-verify`, cherry-picks, rebases, pulled history); history rewrite with `git filter-branch --env-filter` as last resort.
- `git reflog expire --expire=now --all` also empties the stash reflog (`git stash list` shows nothing though `refs/stash` remains). Restore with `git update-ref --create-reflog refs/stash <sha>`.
- In shell scripts, a variable followed by a CJK or full-width character must be written `${var}`, otherwise the character becomes part of the name.

### Debugging and logs

- logcat is restricted (HKS) on Honor. Runtime log goes to `files/main_debug.log` via `SessionController.appendLog` (any thread): `adb shell "run-as io.github.ezn24.droidcar tail files/main_debug.log"` (debug builds only).
- Release builds: `am broadcast -a io.github.ezn24.droidcar.EXPORT_LOG -p <package> --ez all true`; the result data is the exported file path.
- Receiver screen check: `adb -s <bench-serial> exec-out screencap -p`.
- Export (`util/LogExporter`) writes to `Download/DroidCar/` (`Download/CarDeck/` before the rename) through `MediaStore.Downloads` (no storage permission, visible to file managers and share sheets). `filesDir` and `Android/data/<pkg>` are hidden from Android 11+ file managers. The export dialog (`DarkMessageDialog`) shows absolute path and size; a snackbar disappears too fast.
- Default export = current process only: `SenderApp.onCreate` calls `DebugFileLog.markSessionStart`, writing `SESSION_MARKER` into the file; export takes everything after the last marker. Long-press export = `Scope.ALL`. The marker must be in the file (the 2 MB truncation invalidates memory offsets) and must be re-written after truncation. No marker (old files) falls back to the full file.
- `appendLog` replaces `\u0000` with a visible symbol; NUL bytes made exported logs unreadable binaries.
- Honor ROM directory opening: implicit `ACTION_VIEW` + `vnd.android.document/directory` resolves to random apps (NetEase Music observed). Use explicit `ComponentName("com.android.documentsui", "com.android.documentsui.files.FilesActivity")` with `document/primary%3ADownload%2FDroidCar`, then fall back to implicit, storage root, then text instructions. Never rely on implicit intents for system UI.

### Car replay regression tests

- Pipeline: `tools/extract_car_fixtures.py` extracts `RAW rx/tx` (Bluetooth link) and `IAPLINK rx` (tunnel CSM) from logs into JSONL fixtures under `app/src/test/resources/carplay/fixtures/` (**gitignored by default**: they contain real addresses and SSIDs; adding one requires line-by-line review and redaction).
- `replay/BtLinkReplayTest` replays through the real `Iap2LinkChannel` + `Iap2SenderSession`; `replay/TunnelReplayTest` replays the `VirtualIap2Link` control session. Assertion: recorded responses must be an ordered prefix/subsequence of replayed responses.
- Run the extractor after every real-car debugging session.
- RAW dump budget is per byte (4096 B per direction; 512 B did not even cover the identification frame).
- A complete frame length does not mean intact bytes: truncation can splice tails into a fake frame of correct length. The extractor validates exactly like the engine (header 9 bytes sum to 0, payload + trailing checksum sum to 0) before trimming.
- Assertion tiers (`rxHasIdent`): full assertions only if the identification frame survived, otherwise link layer only (SYN, adoption, 0x1D00). Tunnel layer uses ordered subsequence (allows intentional additions such as the MIB3 burst).

## 3. Architecture

```
SessionController   Process-wide session manager: link lifecycle, pairing, Wi-Fi join, cancellation
SenderApp           Application; log session marker
MainActivity        Phone-side console UI (hero card state machine, 1 s polling)
PhoneSettingsActivity, AutomationActivity
bt/                 Bluetooth iAP2: Iap2BluetoothServer (bench calls back), client (real car, outbound),
                    BtSessionGate, BtProfiles, AdapterMode, CarBluetoothReceiver
iap2/               iAP2 link layer, CSM, sender session (0x5703 hotspot creds, 0x4301 AirPlay endpoint),
                    ReceiverProfile, MfiLengthMemory
transport/          Iap2LinkChannel, DuplexStream
net/                Wi-Fi join (WifiCarPlayConnector), ctrl mDNS advertiser, ctrl-int HTTP, TcpLiveness, EndpointHosts
airplay/            SRP pairing, encrypted RTSP control, event channel, stream negotiation, ChaCha20,
                    VirtualIap2Link (Wi-Fi tunnel), AirPlayClient
video/              MediaCodec Surface input H.264/HEVC, 128-byte header + ChaCha20 framing over TCP, HevcConfig
audio/              AudioPlaybackCapture / REMOTE_SUBMIX -> RTP/ChaCha20 over UDP; SilentProjectionActivity; mic uplink
capture/            HiddenDisplaySource, ScreenSource
launcher/           Hidden-display launcher (core): virtual displays, VirtualLauncher, DockActivity, GridActivity,
                    dashboard; launcher/settings/ settings panels shared by car two-pane UI and phone submenus
render/             DualDisplayComposer: GL composition of the virtual displays into the encoder Surface (frame pacing)
input/              TouchRouter: routes head-unit touch to dock/app/map displays; HID decoders
shizuku/            Shell (cmd execution), CarInputClient (touch injection, display create/resize, AIDL UserService)
service/            CarPlayService (foreground service)
media/              Notification listener -> media session state (music card)
notifications/      Notification banner controller/policy
assist/             Voice assistant overlay handling
ui/                 ScreenDimmer, WallpaperStore, PanelWindow, DarkMessageDialog
legal/              Disclaimer gate
util/               Bg, DebugFileLog, DeviceIdentity, LogExporter, SessionHooks, CarScreenshot
```

### Virtual displays (all created through the Shizuku shell)

- **CarPlayDock**: dock strip (left on landscape, bottom on portrait cars), own `DockActivity`, untrusted.
- **CarPlayApp**: native app display.
- **CarPlayAppHi**: supersampled app display (letterbox relief for portrait-locked apps). Not present on portrait cars.
- **CarPlayMap**: map display, dynamically resized between dashboard tile and fullscreen.
- **CarPlayMedia**: separate media display for the dashboard media pane.

### Coordinate systems

- Head-unit touch: advertised view coordinates (e.g. 1160x540). Stream/virtual display: scaled size (e.g. 1450x674). `TouchRouter.setCoordinateScale` maps between them at session setup; do not break this mapping. Map display coordinates equal its current (resized) content.
- `DualDisplayComposer` vertices and clip rects use **GL coordinates (origin bottom, y up)**; touch/stream use top origin. The rounded clip rect (`uClipRect`) must be passed in the same system as the vertices. A flip bug stayed hidden because full-height tiles are symmetric; half-height tiles (portrait) were discarded entirely (invisible map card, 2026-10-14). If a region is partly invisible, check vertex/clip coordinate consistency first.
- Stream dimensions must be even (H.264 4:2:0). An odd height put the encoder into an error state on the first frame and was silently swallowed. Aligned in `SessionController` with a fallback in `VideoSender`.

## 4. General engineering rules

### Threads

- Short one-off tasks: `Bg.run("name") { }` (`util/Bg.kt`, cached pool). Long-lived loops (GL composer, link reader/pacer, accept loops, watchdogs) keep dedicated named `Thread`s with interrupt and lifecycle ownership.
- Main thread: no I/O, no decoding (cover art uses a dedicated executor), no cold app scans. 2026-11-02 ANR: `GridActivity.reload` called `loadLabel/loadIcon` on the main thread for 10 s+. Cold paths through `cachedApps` on the main thread are blocked by a check. Cached icons are capped at 144 px.
- **Never call `Shell.exec` or Shizuku on the UI thread.** Each call is `newProcess` + wait (~100 ms, timeouts up to several seconds). Two real ANRs came from `VirtualLauncher.detach` (6 calls) and `AppOrientationCompat.setEnabled` (2 calls with 4 s timeouts). Runtime guard: `Shell.exec` counts `mainThreadViolations` and logs a main-thread shell warning with the command (wired in `SessionController.ensure`). That log line means new code is on the UI thread; move it to `Bg.run`. `AppOrientationCompat.setEnabledAsync` is the UI entry (serial executor keeps tap order).
- `BtProfiles.proxy()` uses `getProfileProxy`, whose callback is posted to the main Handler. Calling it on the main thread always waits the full 1.2 s timeout and returns null (explicit guard + warning). Any API that blocks waiting for a main-thread callback is background-only.
- Shell, task-close commands, APK icon and label loading always run in the background.

### Shell commands

- Quote arguments with `shellQuote`.
- **Read back every shell write.** `cmd window set-ignore-orientation-request` accepts only `true|1|false|0`; `reset` prints an error to stderr only and `Shell.exec` does not read stderr, so the orientation policy silently never applied for a long time. Strict commands like `cmd window` are the main risk.
- Android ICU rejects unescaped `}` in task-parsing regexes, which broke map, media and background-task operations. Use explicit hex escapes. A query or parse error must never block launching an app.

### Session lifecycle hooks

- Any side effect that should exist only during a session must be attached to both "stream started successfully" and `VideoSender.onEnded`. A car-initiated disconnect goes through `onEnded`, not `retireSession`; hooking only one end always leaks.
- `VideoSender.onEnded(byPeer)`: local `stop()` (round change, disconnect) also reaches `onEnded`; `byPeer` distinguishes them.
- Pattern for system writes (settings/cmd): apply in attach, reset in `retireSession`/`onEnded`, plus a startup cleanup for crashes.
- Anything owned by a process (virtual displays, etc.) needs a cross-version release path: process reaping is the only reliable fallback; `force-stop` cannot be relied on.
- New AIDL methods go at the end of the interface, keeping old transaction numbers, so an old Shizuku service during an update does not misinterpret touch calls.

## 5. iAP2 and Bluetooth

### Link layer

- SYN|ACK must use a **new own sequence number** and **every pure SYN** (`control and ACK == 0`) gets a SYN|ACK. Root cause of BMW 87866 never reaching `iap2 link ready` (14 sessions, 133 SYNs, 0 ACKs, 2026-10-19): `sentSequence` advanced only on data frames, so SYN and SYN|ACK both used seq 99 and the peer dropped it as duplicate; and only the first adoption was echoed while BMW reopens negotiation with new numbers on each retry. Retransmission of the same SYN is byte-identical (no sequence advance).
- Do not send a bare ACK on the host path (it collides with SYN|ACK numbers). Do not reply to every frame with the SYN bit (infinite ping-pong with the peer's SYN|ACK; a unit test spun 55 s, only `jstack` revealed it).
- Bluetooth head-unit links (`Iap2LinkChannel(carLink = true)`) use a random initial sequence (`Iap2LinkEngine.randomInitialSequence()`): Ford SYNC+ 2.0 ignores SYN with fixed 99 and answers immediately with another value (upstream #25). Unit tests and replay keep 99; adapters and the Wi-Fi tunnel are not randomized. (unverified on a real car)
- Consecutive markers after NORMAL = peer asks for renegotiation (`renegotiateOnPeerMarker`, Bluetooth head-unit links only): two markers within 3 s -> reply marker + SYN with new sequence, echo the peer's new SYN within a 5 s window. Closed loop on Cadillac CT5 (upstream #27), but that car still returns SETUP 500: necessary, not sufficient.

### Receive queue

- `expectOrNull` parks non-target frames in `pendingFrames`. `recvFrame` (generic, prefers pending) and `recvNewFrame` (new frames only) are separate; `expectOrNull` uses only the latter and first checks pending for the target. Pending is capped at 256. Sharing one queue caused a livelock (same frame taken, logged, put back, ~1000 log lines/s) when 0x5000 and 0x5703 arrived together. Regression test in `Iap2SenderSessionTest` inserts a late 0x5000.
- `pumpIncomingForever` rate-limits consecutive identical message IDs in the log (log over 2 MB is deleted).
- Lifecycle messages arriving in the keep-alive pump must be dispatched by message ID, not just logged. Ask "what happens if it arrives late" for every new lifecycle message (0x5702, 0x6800, 0x4301, ...).

### Session messages

- **0x4301 is a live event.** Audi MMI 9310 declared `0x4301 0xea03` in its send list, but 0x4301 arrived 2 min 35 s after Phase 1; the car tore down RFCOMM 15 s after no AirPlay connection. Current handling: `Iap2SenderSession` recognizes 0x4301 in the keep-alive pump before rate limiting -> `parseStartSession` (using cached 0x5703) -> `onEndpointUpdate` -> `SessionController.onLateEndpoint`, which: ignores while streaming; hot-updates `AirPlayClient.updateEndpoint` during pairing; parks in `pendingEndpointUpdate` (drained when the client is created) if handoff has not created a client; restarts `onCarPlayEndpoint` if the previous round failed. `AirPlayClient` calls `endpointRefresher` before each pairing retry. `resolveEndpointHost` waits only 3 s; never log that the car "does not send" it.
- **0x4301 address fields are fixed-width NUL-padded**: IPv6 padded to 47 bytes, IPv4 to 17 bytes (Audi MMI 3315). Strip **all** trailing NULs (`rstrip(0)`). A single-NUL strip left `"10.174.189.1\0\0\0\0\0"`, which passes `contains('.')` checks but Android `getaddrinfo` rejects it (`No address associated with hostname`); pairing succeeded via a clean mDNS address while video/keepAlive used the padded one and the car tore down after ~10 s. Test `nulPaddedAddressesAreStrippedCompletely`.
- **0x5702 (old Wi-Fi request)**: capability lists prove presence, never absence. Audi 3315 receive list has 5 entries (`0x4e0c 0x4e09 0x4300 0xea00 0xea01`) without 0x5702, yet requires it: commit 8b64147 skipped it and AirPlay never came up (45 `ECONNREFUSED`, release 0.1.13 regression). With 0x5702 the car answers 0x5703 in 122-368 ms and port 7000 opens 30 ms after join. 0x4e0d/0x4e0b/0x4e0e are also undeclared yet ACKed. Current dispatch uses only `ReceiverProfile.wifiGen`: `NEW` (both lists total >= 10 entries and no 0x57xx, BMW iD9 form) skips 0x5702; `LEGACY` and `UNKNOWN` always send. 0x5703 wait window 1.5 s (4x margin), timeout only degrades. Tests `sparseDeclarationsStillSendWifiRequest` and `fullDeclarationsWithoutOldProtocolSkipWifiRequest` pin both directions. External evidence in `docs/PROTOCOL-NOTES.md` §5b, the regression in §9j.
- Skip StartHID (2 s) and 0x4301 (10 s) waits when 0x1D01 parameter 6 (head-unit send list) does not declare them. Without a list, use conservative windows (0x4301 3 s).
- No new credentials is not a reason to tear down: if neither 0x5703 nor 0x4301 arrived within 8 s and `knownCarSsid()` (`boundCarSsid` or `lastCarWifiSsid`) is known, synthesize an endpoint with `airPlayPort=0` and continue via mDNS/gateway. A real iPhone trace shows Audi sends the ctrl invite ~21 s after Wi-Fi join; restarting every 8 s resets that window forever. The 0x4301 path also writes `lastWifiPsk`/`lastCarWifiSsid`.
- 0x4300/0x4E0E Bluetooth identifier must be the real MAC at session start: `BtSession` uses `DeviceIdentity.bluetoothMac(forceRetry = true)` and logs it. A 10 s quiet window in `DeviceIdentity` once returned the placeholder `02:00:00:00:00:00` for a whole session; Audi compares it with the RFCOMM peer and silently drops mismatches. Check the device-status greeting `mac=` log line first.
- 0x4E0E keeps "MAC + UDID" format; upstream #25 proposals "u8 type + MAC" for 0x4E0E and "sparse list means NEW" were rejected (they conflict with adapters/real iPhone and with Audi 3315 needing 0x5702).
- Redundant endpoints/credentials during streaming are ignored (`onCarPlayEndpoint`, `onWifiCredentials`: if `videoSender.isRunning`, ignore or only refresh the PSK cache). DiPlay resends 0x5703/0x4301 after we start streaming; treating it as a new session made `retireSession` kill a healthy session (`Pending dequeue output buffer request cancelled`). These would also trigger Wi-Fi rebind and ctrl mDNS re-registration.
- **Now playing (0x5000/0x5001/0x5002)** for the cluster / media widget: `media/CarNowPlaying` keeps one subscription per link (Bluetooth control session in `pumpIncomingForever`, MIB3 tunnel in `VirtualIap2Link`), replies to 0x5000 at once, then pushes 0x5001 on track/metadata/play-state/app changes and on seeks (>2 s off the extrapolated position; debounce 300 ms). Field layout is decoded from the captured iPhone frame (`iap2/NowPlayingUpdate`); with nothing playing it encodes to `MIB3_NOW_PLAYING_UPDATE_IDLE` byte for byte (test `emptyStateMatchesCapturedIphoneFrameByteForByte`). Bluetooth sends only the attributes the car requested; the tunnel keeps the captured set. Genre/composer are empty and artwork is not transferred (needs iAP2 file transfer). Log prefix `MEDIA-CAR:`. (unverified on a real car)
- Now-playing **artwork** goes over the iAP2 file-transfer link session (kind 1; ours and WinPlay's table: id 12): 0x5001 attribute 26 names the transfer (0x80 = none, then 0x81..0xFF rotating), then `[id][0x04 Setup][size u64][type u16 0x0002]` -> car `[id][0x01 Start]` -> data `0xC0` single / `0x80`,`0x00`..,`0x40` -> car `[id][0x05 Success]` (`iap2/ArtworkTransfer`, 320 px JPEG ~20 KB, 4 KB pieces). `Iap2LinkEngine.sendFileTransfer` / `onFileTransferData` share the link sequence space; the handler runs inside the engine parse with the link lock held, so answers are posted to the feed thread. WinPlay answers Success (2026-11-21). WinPlay subscribes to 0x5000 on the Wi-Fi tunnel (its Bluetooth is released after RECORD), so non-MIB3 tunnels answer subscriptions too.
- **Route guidance (0x5200/0x5201/0x5202/0x5203)** for the cluster / HUD: `nav/CarRouteGuidance` (per-link subscriptions, components from 0x5200 param 0 echoed as param 0), encoder `iap2/RouteGuidanceUpdate` (field IDs/types from WaylandAce/mib2q-carplay-rgi; distances in meters + display string + unit; ETA UTC epoch s; unknown maneuver type is left out, never guessed). Sources: Google Maps ongoing notification (`nav/MapsNotificationSource`, via `MediaListenerService`), Waze and Amap on-screen text via `nav/NavAccessibilityService` (user enables it in system Accessibility; limited to those two packages; reads only while a car is subscribed; windows from all displays because maps run on CarPlayMap). Waze has stable view IDs (`navBarDistance`, `navBarStreetLine`, `lblArrivalTime`, `lblTimeToDestination`, `lblDistanceToDestination`) but its turn arrow is an image (type unknown). Amap's notification says only "navigating". Text parsing (EN + zh) in `nav/NavText`. On MIB3 the 0x5200 subscription gets no initial "no route" reply (bring-up was captured without one). Map a new layout with `am broadcast -a io.github.ezn24.droidcar.DUMP_NAV_UI -p io.github.ezn24.droidcar` (writes `NAV-DUMP:` lines; contains on-screen road names, so it is adb-only). `uiautomator dump` is killed on the S24 and cannot read virtual displays. `am force-stop` (our install routine) removes the service from `enabled_accessibility_services` on the S24 (2026-11-21): so it is granted through Shizuku (`nav/NavAccessibilityAccess`, opt-in setting `nav_cluster`, default off; the phone home page has its Quick settings switch) and re-checked at every session attach next to notification access. Only our own entry in the `:`-joined `enabled_accessibility_services` list is added or removed. Turn type when the app draws it only as a picture (Amap, Waze; Google Maps when the wording does not say): `nav/ManeuverIcon` classifies the arrow geometrically (head = chamfer-distance maximum, heading = stem ring behind the head -> angle bands; enclosed hole = roundabout, JunctionType param 9 = 1). Pixels for arrows and lanes (`NavAccessibilityService.capture`): on the car's map display only the needed rectangle (union of arrow, lane cells or Google strip + card point) is read from `DualDisplayComposer.requestMapRegion` (off-screen FBO draw of the map texture + `glReadPixels` before the swap, max 300k px, >= 300 ms apart; replaced whole-display `takeScreenshot` there: 3.5 MB copy, system limit ~1/s); a map on the phone screen falls back to `AccessibilityService.takeScreenshot` (config `canTakeScreenshot`, >= 1.1 s apart). A sampled fingerprint of the region skips re-classifying an unchanged arrow / lane row. Polling is 4 s while the next maneuver is > 1 km away, else 2 s. The map texture only refreshes while the map is drawn, so with the map hidden readings repeat the last frame. Arrow = `ImageView` nearest the distance label; cached per maneuver, refreshed every 4 s. The active dashboard map is the guidance source when selected. The head unit draws its own icons from the ManeuverType, JunctionType and directional exit angle. Text can identify roundabouts, ramps, highway transitions and ferries. The maneuver crop is used for turn recognition when the app provides no spoken or accessible instruction. Amap lane guidance detects the row by its narrow separators and reads each arrowhead from the same capture as the maneuver icon. Separate directions are encoded in 0x5204, and 0x5201 fields 0x10-0x12 activate or hide the row. Rows with unrecognized cells are omitted. Waze and Google Maps lane layouts still need source samples; Apple-style road badges are separate. NAV-DUMP also saves arrow crops for 10 min to `Android/data/<pkg>/files/nav-icons/` (file name = type/angle) for threshold tuning. Google Maps (Android 16 promoted ongoing, ProgressStyle): distance to the maneuver is `android.shortCriticalText` (the title omits it on "head toward X" legs), Samsung adds `android.ongoingActivityNoti.secondaryInfo` (exit / road after) and `progressMax - progress` = remaining route meters; its lane hint exists only as title text ("走左側 2 條車道"). A notification source is exempt from the 10 s staleness (`publish(untilRemoved = true)`): Maps posts only on change. Waze: its map is a SurfaceView and its card raised no accessibility events in a 10 min drive, so the service polls every 2 s from the moment a car subscribes (`onRouteGuidanceWanted`); lanes are the ImageViews inside `laneGuidanceView` (read by `LaneIcon`, verified on a real Waze crop: [0,-90] / [0,90] preferred 90) and replace the arrow near junctions, so the turn type comes from the single preferred lane direction; current road = text tagged `INSIGHT_TITLE`. A Waze junction-hop capture (2026-11-21, 28 dumps, 9 distinct rows incl. two recommended lanes and small combined left+right icons) read every lane correctly with the unchanged `LaneIcon`; only Waze's fade-in animation frames read as nothing, which withholds that row. Amap pitfalls seen on WinPlay's cluster view (2026-11-21): the tunnel-entrance double arch was read as a U-turn, so a left-right symmetric shape (mirror IoU >= 0.88, checked before the head search, no hole) is straight; a bus-lane sign cell was read as a left turn, so `LaneIcon` requires a stem (any vertical run whose lower end lies in the bottom 40% and that reaches 35% of the cell; every run per column counts, because a bus drawn under the stem is its own short run below it, and checking only the lowest run sent bus lanes as unread grey straights; the straight-head check likewise looks for stem 0.55 of the cell below the tip, not 0.62, because the bus shortens the arrow; regression over 99 unique saved real crops: only 4 bus cells changed, all from unread to correct) and an unreadable cell is dropped alone instead of the whole row. Combined lane icons read correctly (left + U-turn = [-90, -180], test `leftPlusUTurnKeepsBothArrows`); WinPlay draws all angles of a lane from one stem, so a left arrow disappears under the U-turn's hook there: a receiver drawing limit, not a reading error; collection mode now saves every lane cell crop (`*_lane_dirs..._pref....png`) and retries a full frame rejected for interval (error 3) after 1.2 s. Google Maps lanes (2026-11-21): the row is one custom `View` `com.google.android.apps.maps:id/lane_guidance_list` under `step_instruction_container`, no children, no description, so it is read from pixels (see capture above): the strip counts only if its median color matches the turn card (else the map shows through and gets sliced into fake lanes), `LaneIcon.splitStrip` cuts lanes by column (separators <= 2 px and the rounded ends ignored, runs within 6 px = one lane with a shared stem), each cell is cropped to its arrow rows (`glyphRows`; LaneIcon proportions were tuned on Amap cells the arrows fill) and read by `LaneIcon`; lanes join the notification source via `CarRouteGuidance.updateLanes` (6 s TTL). On 8 real strips 6 were exact incl. combined arrows; diagonal axes (-45/+45, step lengths scaled by 1/sqrt 2, tip in the upper 55%, ignored next to an existing head and when opposite a full turn in the same lane) bring it to 7 of 8; the curved "bear left" icon with a hooked stem is still unread, so that row is withheld rather than sent wrong. The same screen also exposes `distance_text`, `top_cue_text`, maneuver words in `step_instruction_container`'s description, remaining distance/ETA and the speed limit (description "速限 每小時 50 公里"). Capturing samples: trigger NAV-DUMP every 3 s from adb (each saves a frame) while a mock drive hops to junctions. iAP2 route guidance has no speed-limit field (0x5201 params 0-26 per CPRouteGuidance; the car shows its own sign recognition). Mock drives for testing: `cmd location providers add-test-provider gps|network` + `set-test-provider-location` (shell needs `appops set com.android.shell android:mock_location allow`, reset to `default` after); the S24 drops test providers after a few minutes, so a driver script must re-add them. Log prefix `NAV-CAR:`. (unverified on a real car)

### ReceiverProfile

- `iap2/ReceiverProfile` is a pure function aggregating 0x1D01 identity + receive/send lists and mDNS features/srcvers into `era` (three eras), `wifiGen` and `LinkGeneration`. Reasons are logged on `PROFILE:` lines. Wired in `Iap2SenderSession.logIdentification` and after endpoint diagnostics in `SessionController.onCarPlayEndpoint`.
- Branch on capability, not version number or brand. srcvers is only a prior.
- `identityKey` keeps raw text; `MfiLengthMemory.receiverKey` sanitizes for persistence.
- `linkGenerationOf`: DELPHI manufacturer + AUDI model + total 1-9 list entries -> `CLASSIC_MIB3`, else `EVENT_LINK`. Now log-only: marker negotiation is unified across generations (an evening-of decision to ignore markers on CLASSIC was disproved by owner logs; do not freeze single-car observations into dispatch logic). Cache cleared on disconnect. Tests `ReceiverProfileTest`.

### MFi challenge length

- Persisted per head unit by `MfiLengthMemory` (key from 0x1D01 name+model+manufacturer): success / silent / rejected. `orderAttempts` (pure) puts the last successful length first; silent/rejected only demote (all rejected -> try all again). Known-silent lengths get a 1.5 s window.
- A tunnel MFi window that runs out after the AirPlay session closed is not recorded as silent (upstream 21192ad: a dropped round demoted every length).
- Preferences differ: DiPlay 32 B only (disconnects on mismatch); MMI 6318 20 B (32 B dropped the link in 0.4 s); MMI 3315 32 B over Bluetooth (20 B silent for 6.0 s) but 20 B over the tunnel (Frida). Tunnel has its own key.

### Bluetooth connection policy

- **Pair only, do not stay connected.** With HFP/A2DP connected, Audi MIB3 does not open the wireless CarPlay iAP2 window (`BT-C` connection failures every 3 s). `bt/BtProfiles` disconnects profiles to the target before iAP2 connect and twice after pairing (immediately and +1.5 s).
- Only disconnect devices that advertise iAP2 (SDP `...caff`) or are the user-selected target (`Iap2BluetoothClient.awaitIap2Service`): read cached uuids, else `fetchUuidsWithSdp` with a 2.5 s window; cache per address until client stop. Earphones/watches must not be disconnected.
- `BluetoothHeadset/A2dp/LeAudio.disconnect(BluetoothDevice)` is `@SystemApi`: call by reflection (needs only `BLUETOOTH_CONNECT`). Queries use public `getProfileProxy` + `connectedDevices` (main Looper). Do not move this into the Shizuku UserService: `BluetoothManager.getAdapter()` returns null there. Diagnostic: `BT-PROFILE:` line.
- **Never drop the BT control session during pairing/streaming.** DiPlay treats RFCOMM loss before tunnel iAP2 is up (no `disableBluetooth`) as `wireless bring-up failed` and tears down the whole wireless stack, including a streaming AirPlay session (connect -> drop after ~3 s loop, 2026-10-16). Detach only in `disconnect()` (user terminal state), never in `retireSession()`. The "phase two hack" in `SessionController` (drop BT after pairing failure) costs one stack rebuild on DiPlay; it is disabled when `pairingForbidden`.
- **Zombie round after BT drop** (Audi 3315, 2026-10-22): the car's 15 s AirPlay watchdog drops the link and immediately reconnects with a new endpoint, but a stuck round (Wi-Fi join) kept `sessionStarting` and blocked it. Fixes: `onSenderLinkClosed` releases the mutex and cancels the round if not streaming (stuck thread exits <= 200 ms) without stopping BT listening; a new round clears `connectCancelled`; Wi-Fi join uses segmented re-requests. Signature: log "a session is already in progress, ignoring this endpoint" followed only by `ff 55 02 00 ee 10` heartbeats.
- During streaming, ignore head-unit ACL disconnect: receivers release Bluetooth after Wi-Fi handoff (WinPlay ~3 s after RECORD). Auto-connect's `ACL_DISCONNECTED` used to kill healthy sessions. Real departure is handled by Wi-Fi `onLost` / `onEnded`. While still connecting, ACL loss has a 60 s grace (`CarPlayService.AUTO_STOP_GRACE_MILLIS`).

### Target head-unit selection

- `TargetDevicePrefs` stores address + name snapshot (case-insensitive). With a target: outbound connects only to it, inbound rejects others ("ignoring non-target device"). "Auto": outbound tries all bonded candidates, inbound accepts anyone. `targetProvider` reads dynamically (no reconnect needed).
- Only bonded devices are listed (no scanning). If the target disappears from bonded list, UI asks to reselect. `btnPickTarget` sits in the "Bluetooth pairing" title row, shown only when bonded > 0.

### Auto-connect on ACL (details in `docs/AUTO-CONNECT.md`)

- `bt/CarBluetoothReceiver` is a manifest receiver for `ACTION_ACL_CONNECTED/DISCONNECTED` (implicit broadcast exceptions; cold-starts a killed process).
- Android 12+ background FGS start works only because the app holds `SYSTEM_ALERT_WINDOW`; without it `ForegroundServiceStartNotAllowedException` is logged, not crashed.
- FGS type `connectedDevice` (the 2-argument `startForeground` adds mediaProjection and Android 14 crashes on the PROJECT_MEDIA appop check).
- In a cold process call `SessionController.ensure(applicationContext)` before `startConnection(...)` (BT client and Wi-Fi connector are created in `ensureSessionObjects`).
- Audio without a dialog: `audio/SilentProjectionActivity`. `appops set <pkg> PROJECT_MEDIA allow` (persistent; clear with `appops set ... default`) makes SystemUI return RESULT_OK; shell launches the 1x1 transparent, exported, `noHistory` trampoline (shell has START_ACTIVITIES_FROM_BACKGROUND, not START_ANY_ACTIVITY). Android 14: call `upgradeToProjection()` to add the mediaProjection FGS type before `getMediaProjection`, and keep that type on repeated `startForeground` while `mediaProjection != null`.
- Manual disconnect or cancel holds off car-entry auto-connect for 10 min (`SessionController.autoConnectSuppressedForMillis`): closing RFCOMM makes the car ACL drop and reconnect, which restarted a session ~1 s after "Disconnect" (upstream 1d3afc8). Leaving the car (AUTO_STOP) does not arm it; any `startConnection` clears it.
- Toggle: Settings > Display > auto-connect (`AutoConnectPrefs`, default on). Departure via `ACTION_AUTO_STOP_SESSION` -> `disconnect()`, delivered with `startService`.
- Permission prompts when Shizuku is not ready: record audio then projection, sequentially (two simultaneous system dialogs push each other around). With Shizuku: `SilentProjectionActivity`.
- "Car audio" switch (`DisplayPrefs.carAudio`, default on) replaced the pre-connection audio dialog; auto-connect obeys it.

## 6. Wireless CarPlay adapters (smartBox / Carlinkit)

Ported from `zz85/cardeck` be86bb9 / a03afde (tested on smartBox-136F) by cherry-pick. The Bigme ROM fix in a03afde (no `OWN_DISPLAY_GROUP`) was intentionally not merged. (unverified on our devices)

- Gate `bt/AdapterMode.active`: set by `startConnection` when the target name looks like an adapter (smartBox, CarLink, Carlinkit, AutoKit, CPC200); with target "Auto" set by `Iap2BluetoothServer` when the box dials in on `...cafe` (must happen before `Iap2LinkChannel` is constructed). When false, nothing changes for head units.
- Differences from head units: the box is the RFCOMM client and sends SYN first (we echo its parameters); the Wi-Fi tunnel waits for the box to send first (`AdapterIapLink`); send `disableBluetooth` after RECORD; if the mDNS SRV host is `(none).local.` use DHCP server / inviter / gateway; H.264 Baseline always; resend VideoConfig + IDR on requestUI/forceKeyFrame (`RePrimeGate`); a box that already knows the phone sends only the Wi-Fi invite, which opens the session directly.

## 7. Wi-Fi joining (details in `docs/WIFI-JOIN.md`)

- Default: app-scoped network via `WifiNetworkSpecifier` (`WifiCarPlayConnector.joinCarPlayNetwork`). Visible only to this process (`Ephemeral: true`, `OwnerUid` = app), not saved, not the default network; other apps keep cellular. Verified on Honor + Redmi K40 + DiPlay: `cmd wifi list-networks` shows no SSID, default network stays cellular, `ping` works during streaming. No Shizuku needed, no system watchdog flipping back, ROM does not kill mobile data.
- Android 12/12L need location permission and location services for specifiers (13+ use NEARBY_WIFI_DEVICES); missing permission only shows as "failed to join". Pre-check in `ensureWifiLocationReady`. Manifest must declare FINE and COARSE together (lint `CoarseFineLocation`).
- `onLost` must be handled (`WifiCarPlayConnector.onNetworkLost`). `bindCurrentWifi`'s onLost must forward to `onNetworkLost` too, otherwise the hero card stays "connected" after ignition off.
- Existing saved network with the same SSID (old versions used `cmd wifi connect-network`): check `cmd wifi list-networks` first; if found, **ask the user** whether to forget it, never delete silently (keeping is legitimate). Asked once per SSID per process (`forgetPromptHandled`); no answer = keep. Parsing via pure `WifiSavedNetworks.idsOf` (tests cover spaces, non-ASCII, duplicate rows, malformed rows; prefer missing over wrong deletion).
- Fallback timing is budgeted backward from the head-unit window (Audi 3315, 2026-10-23): from 0x4301 the car drops BT after ~15 s and the AirPlay window closes after ~21 s (late = `ECONNREFUSED` on 7000); from 0x5703 there are only ~9-14 s. Sequence: specifier first window 5 s (normal join 0.03-3 s; 5 s with zero callbacks = stuck) -> quick probe (system already connected -> bind, <= 3 s) -> **re-issue specifier** (release + re-request joined in 1.6 s; must follow the probe immediately), 20 s window -> `connect-network` deep fallback (only to prepare the next round). Putting the fallback after a 20 s first window meant it never ran.
- "System already saved the SSID + Android 16" can make the specifier request pending forever with zero callbacks. If join fails while the system is already on the target SSID, `bindCurrentWifi` directly.
- `bindCurrentWifi` requests by transport and **does not check SSID**: confirm the SSID is the car hotspot before binding. Its release + rebind unbinds the process network, so a fast path is used after binding the same hotspot, and a lock serializes both binding paths.
- `connect-network` fallback cleanup: record the SSID in prefs `wifi/appSavedSsids` the instant the command returns ok (not after bind, or cancel/timeout/bind failure leak it); clean up on both `disconnect` and `onEnded`; do not record SSIDs that existed before this round (user chose to keep). `cmd wifi forget-network` accepts only netId, never SSID.
- `cmd wifi connect-network` always with `-d` (`WifiConnectCommand.AUTOJOIN_DISABLED`): the command overwrites a same-name saved network and without `-d` re-enables auto-join every time (WinPlay). Car hotspots have no internet.
- `connect-network` exit 0 does not mean connected: poll `cmd wifi status` for the SSID.
- WEP cannot be expressed as a specifier -> `JoinResult.Unsupported` -> `legacyJoinCarWifi`.
- A password wins over the declared security type: some head units send 0x5703 security 0 (NONE) with a real password, and joining as open never associates. Both the specifier and `connect-network` then use WPA2 (upstream 0.1.29 port). (unverified on a real car)
- `joinCarWifiSpecifier` first runs the quick probe (system already on the car SSID -> bind it directly, upstream 9d31800): with a saved network some ROMs leave the specifier pending for its whole 5 s first window. Without Shizuku or on another SSID the probe returns null at once.
- NSD has no Network parameter; mDNS follows the process default network, so after binding the car hotspot call `bindProcessToNetwork`, or advertisements leave via cellular.
- Head-unit sockets (control, event, /info probe, iAP tunnel, video, audio RTP/sync, timing, mic) are additionally pinned with `Network.bindSocket` via `net/CarNetwork` (chengshl T47). A VPN app makes unpinned sockets follow the VPN, so car traffic went into the tunnel and out over cellular. A VPN without `allowBypass` (the default) rejects the pin with EPERM and Android offers no way around it: `NET: could not pin` is logged once per session plus the `hint_vpn_blocks_car` UI hint (exclude CarDeck in the VPN app or bypass LAN). New car sockets must call `CarNetwork.bind` before `connect`. (unverified with a VPN on a real car)
- `cmd wifi set-wifi-watchdog-enabled` does not exist (AOSP has `set-wifi-watchdog`, root only).
- Use one `cmd wifi status` to read Wi-Fi state and SSID. "Any Wi-Fi = success" misjudges when the phone is on another network.
- `manualJoinCarWifi` saves the hotspot to the system; the log and dialog state that cost.
- Bind timeout: 15 s, sliced and cancellable. A 5 s latch gave up 116 ms before `onAvailable` and cost a full BT reconnect (+13 s). The 5 s watchdog in `WifiCarPlayConnector` only warns.
- Explorer/Tango-style cars (and Audi) only accept Wi-Fi joined through the iAP2 credential flow; a manually/system-saved car hotspot gets no invite and no mDNS. With dual-band SSIDs use the one 0x5703 specifies.

## 8. Connection sequencing, mDNS and cancellation

- Order (from catplay): ctrl advertisement first, invite-driven, no blind waits.
  1. `_carplay-ctrl._tcp` is published at `startConnection`; after 0x5703/bind only `ensureForNetwork` re-registers.
  2. After endpoint handoff wait for the invite with `awaitCtrlInvite` (invite counter event; force-announce at 2 s if absent; 4 s fallback; a discovered `_airplay._tcp` also releases it).
  3. `_airplay._tcp` discoverer runs permanently once bound; lookups read its cache.
  4. Endpoint resolution happens after the invite and not on the network callback thread.
- Early advertising may register before Shizuku is ready, writing a placeholder MAC into TXT `id` (car never invites). `DeviceIdentity.bluetoothMac(forceRetry)` + `CarPlayCtrlAdvertiser.repairPlaceholderMac` (retry every 2 s, re-register on success) is required.
- **ctrl port is fixed for the advertiser lifetime.** Android NSD has no `reannounce()`; re-announce = unregister + register via `unregisterServiceOnly()`, keeping the socket and accept thread (`acceptRunning` independent of `registered`). Previously `ServerSocket(0)` changed the port on every re-announce with up to 1.2 s gaps.
- **Do not copy identity fields from catplay.** Changing ctrl TXT `srcvers` from `950.7.1` to `566.25.21` and instance name `sender` to `iPhone` made Audi MMI 3315 stop sending `GET /ctrl-int/1/connect` entirely (release 0.1.7). Reverted, pinned by `CarPlayCtrlAdvertiserTest`. Same class of fields: `sourceVersion`, `deviceID`, service name, `model`, TXT `id`: A/B on a real car first and make the value visible in logs.
- `_device-info._tcp` removed (NSD rejects port 0: `The port is missing`).
- Connection time budget (Audi, 2026-10-18): of 4.9 s from invite to RECORD, `/info` takes ~3.0 s because the car does not answer until its window is ready (not optimizable). `AppOrientationCompat.resetAll` (~1.6 s) and `applyAll` (~2.0 s) were moved off the critical path onto one serial executor (`resetAllAsync`, `applyAllAsync`).
- **Cancellation must be interruptible everywhere.** `sessionStarting` is reset only when the pairing thread reaches `finally`; any uninterruptible wait leaves the hero card in "connecting" forever. Defenses: detach the BT session socket on disconnect/cancel (terminal states only), `AirPlayClient.cancelled` ends pairing retries, `cancelConnecting` forces `sessionStarting.set(false)`. Every new long wait must respect `connectCancelled`; test "start then cancel immediately".
- Shizuku not ready must hard-block connecting, in both `ensureShizukuReady` (CTA precheck, before Bluetooth) and at the top of `startConnectionNow` (delayed paths). Otherwise the result is "BT connected, car screen black, touch lost". `ShizukuGate.guidanceOf` gives three distinct actions: not installed (explanation only), not running (open Shizuku), not authorized (request and auto-continue via `pendingShizukuThenConnect`). The readiness checklist counts Shizuku as item 1 (`readyCount` x/4). Unregister `Shizuku.OnRequestPermissionResultListener` on Activity destroy.

## 9. AirPlay session

### Eras and pairing

- Classify receivers by catplay's three eras (`docs/PROTOCOL-NOTES.md` §9f). Old head units (CarPlay 210.81, Audi 4xxx) are pre-HomeKit (`_airplay._tcp` features without bit 38 `HK_PAIRING_AND_ENCRYPT`): plaintext control (`et=NONE`), AES-128-CTR zero-key screen stream, `pair-setup` always fails. Rules:
  1. bplist root object must be at byte 8 (210.81 ignores trailer `topObject`); encoder uses `makeRootFirst` everywhere.
  2. Initial SETUP uses relative URI `/setup`.
  3. Unpaired sessions send `et=1` explicitly; event channel in plaintext.
  4. Unpaired screen frames use AES-128-CTR with a keystream continuous across frames (one instance per stream).
  5. Control ciphertext MTU 1440 -> max 1422 plaintext bytes per frame (0x4000 was rejected as PayloadTooBig).
  6. Never test "has address" with `contains('.')`: Audi 4xxx often sends only IPv6 link-local. `net/EndpointHosts` orders IPv4 -> global IPv6 -> link-local IPv6; video, audio and event channels reuse the pairing's `activeHost`.
- Missing TXT features = unknown: keep the old path, do not assume "no pairing".
- Real iPhone sysdiagnose comparison (`docs/PROTOCOL-NOTES.md` §13, iOS 18, read with `log show --archive`):
  - Audi MMI 3315_5GHz rejects the first MFi step with 403 even for a real iPhone after pair-verify, then re-invites after 18-20 s and succeeds. Correct recovery: keep BT and retry with backoff (`pairingForbidden` disables the BT-drop hack and plaintext downgrade; 403 backoff 5 s).
  - That Audi's successful session negotiates iAPChannel = 0 (real iPhone did not open a Wi-Fi tunnel). Treat "MIB3 must have a tunnel" as uncertain and A/B before changing the tunnel.
  - Successful paths on both cars: IPv6 link-local :7000, pair-verify + MFi (standard modern form). SETUP responses may lack streamID (we read only dataPort).
- Session-level SETUP `deviceID` must be the phone's real Bluetooth MAC from `DeviceIdentity` (same source as 0x4300/0x4E0E). WinPlay filters by selected iPhone address and answers `403 OK` (its status table has no 403 text; that combination means device filter). The old fake `ff:ee:dd:cc:bb:aa` caused "disconnects after ~45 s". The 403 log prints the value sent. On non-200 SETUP, close the control channel actively.
- `SetupInfo.hevc` requires both an empty `hevcInfo` dict in `/info` and `"hevc"` in the SETUP response `enabledFeatures` (see Video).
- Video/audio/keepAlive hosts always use `client.currentEndpoint` (the address pairing actually reached, refreshed via mDNS), not the stale `effectiveEndpoint`.

### Alternate (cluster) screen

- A receiver may list a second display in `/info displays[]` with `type=111` (ChairPlay AltScreen: 1280x480@30, `features=0`) and enable `altScreen` in the session SETUP reply. Protocol facts from ChairPlay / xcertplay (GPL-3.0, read only): the alt screen is a second screen stream, same SETUP body as the main one with `type=111` and the alt display's `uuid`, same frame format and key derivation, its own data port. `SessionController.startAltScreen` sets it up after the audio SETUP (the second RECORD covers both), H.264 only, and `DualDisplayComposer.renderAlt` draws into its encoder at the alt frame rate; map frames keep the composer awake while the alt stream runs. The receiver picks the content with event commands for the alt uuid: `showUI` url `maps:/car/instrumentcluster/map` = map, `.../instructioncard` = turn card only, `maps:/car/instrumentcluster?maneuverLayout=leftAligned|rightAligned|topAligned` = map plus turn card on that side; `updateViewArea` viewAreaIndex = which /info `viewAreas` entry the content fills (`render/AltScreenOutput`). The map is fitted whole into the area, never cropped (cropping cut Google Maps' own turn banner off the top). The turn card is our own bitmap (`render/AltScreenCard`: arrow from ManeuverType / exit angle, distance, road after) fed by `CarRouteGuidance.onInfo`, so it needs the cluster navigation setting on. ChairPlay's "HUD" content is drawn by ChairPlay itself from iAP2 route / now-playing / call data and needs no stream; its Map, Map + card and Turn card modes need this stream. (ChairPlay bench, unverified on a real car)
- Route guidance distance display strings carry the number only ("809", "1.2"): receivers append the unit field themselves (ChairPlay's HUD showed "809 m m"). ChairPlay's HUD font has no CJK glyphs, so Chinese road names show as "NO GLYPH" boxes there: a receiver limit.

### Stream setup order

- Screen SETUP -> connect data port -> RECORD -> start video -> audio SETUP -> **RECORD again**. The car has a ~2 s activation watchdog after screen SETUP and audio SETUP takes 2.0-2.7 s; putting it before RECORD always triggered `EVENT read ended - Connection reset` in the same millisecond (5/5). An audio stream added after RECORD needs a second RECORD.
- If the data port cannot be connected (`openDataPortWithRetry`: one retry after 300 ms, only when the first attempt failed within 1.5 s, i.e. refused rather than timed out), the round ends without RECORD (`failDataPort`: TEARDOWN + close, BT kept). Previously RECORD went out anyway and the car showed black until its ~10 s watchdog fired (chengshl ec70bb80).

### RTSP control channel

- Asynchronous reads: a dedicated reader thread decrypts and dispatches by CSeq; writers only serialize "allocate CSeq + encrypt + write". `soTimeout=0`; timeouts via the pending-response poll. A synchronous per-connection lock stalled feedback during slow requests (Audi audio SETUP 2 s+) and the car's ~3 s feedback watchdog killed the session.
- On reconnect close the old socket (two readers would race). When the cipher switches from null to active, discard plaintext leftovers or frame boundaries misalign.
- Session end sends a bounded RTSP `TEARDOWN` first (`AirPlayClient.teardownSession`, 800 ms, chengshl T42), only if a session-level SETUP succeeded on that connection; `retireSession` runs it in the background from the main thread and inline on pairing threads (old connection closed before the next round connects). (unverified on a real car)

### Heartbeats (three independent ones, all required)

1. UDP keepAlive 4-byte ping to keepAlivePort, every 5 s (catplay's 10 s equals the watchdog; sleep jitter of 1 ms times out).
2. RTSP `POST /feedback` with empty body, every 1 s.
3. Keep-alive frame inside the screen data port (128-byte plaintext header, opcode 2; opcode 5 with a bplist stats body when `/info` has `keepAliveSendStatsAsBody=true`), every 1 s, **also while video flows**. Not encrypted (only VideoFrame opcode is encrypted), does not consume a ChaCha nonce.
- With only 1 and 2 the session dies 10.2 s after RECORD with all channels looking healthy. All three are necessary but not sufficient: MMI 3315's 10 s teardown was finally the missing Wi-Fi tunnel (below).

### TCP liveness

- `net/TcpLiveness`: keepalive idle 10 s, interval 3 s, count 3, `TCP_USER_TIMEOUT` 20 s (19 s death detection, deliberately above the car's 15 s watchdog). Applied to control, event and video data sockets. Default kernel keepalive starts after 2 h; cars that power off send no RST and readers hang in `read()`.
- `ParcelFileDescriptor.fromSocket` before API 29 wraps the socket's own fd (closing it closes the socket): use it only on 29+, use reflection on `Socket.getFileDescriptor$()` on 26-28.

### Wi-Fi tunnel (second iAP2 link) and MIB3

- MIB3 needs a second iAP2 link through AirPlay; answering only with ACKs gets the session dropped ~10 s after RECORD (0.1.9 = 10.17 s, 0.1.17 = 10.18 s). Head-unit markers repeat at 1 Hz until the session table is answered. Implementation: `airplay/VirtualIap2Link.kt` (DiPlay tunnel, MIB3, legacy echo). Byte-level constants and Frida traces in `docs/PROTOCOL-NOTES.md` §12.
- Sequence: after screen SETUP succeeds, send DETECT once through control channel `POST /command` (iAPSendMessage envelope) -> car SYN (event channel) -> phone SYN|ACK (control channel) -> NORMAL -> control session: 0x1D00 -> 0x1D01 -> 0x1D02 + 0xAA00 -> 0xAA06 -> 0xAA02 challenge (length from memory, tunnel key, cold start 20 B first) -> 0xAA03 -> 0xAA05 -> burst 0x4E0C/0xA100/0x4E0A/0x4E0B/0x4E09/0xFFFA/0xFFF0 -> answer subscriptions 0x5000/0x4157/0x4154/0x4170; 0xFFFB gets only a link ACK.
- Constraints: phone-to-car link frames only via control channel POST (MIB3 does not read event-channel outbound); DETECT exactly once after screen SETUP (earlier is flushed); our echoed LSP declares our own maxOutgoing 0x7F (echoing the peer's table throttles us); 0xFFFA/0xFFF0/0x4158 are byte constants, never re-encode (0x5001 is encoded, but pinned to its capture by a test); `reference/AADisplay` is GPL-3.0: facts only.
- **Car home button** (launcher cell from /info `oemIcons`, largest size, label `oemIconLabel`; upstream 9686391 + f1e762d, Audi-verified there): `AirPlayClient.handScreenToCar` posts `requestUI` on the control channel and, once modesChanged was accepted this session, modesChanged screen entity/permanent 2/2 + main audio 2/1 (car, permanent phone). The video stream keeps running. While `screenHandedToCar`, `assertModes` and the reclaim schedule skip, otherwise they take the screen straight back. Inbound `requestUI` (car's CarPlay button) or `changeModes` -> `takeScreenBack` (modesChanged 1/1). WinPlay answers `requestUI` by opening its settings window. Transparent car glyphs get a contrast plate (`AppIcons.plated`; Audi's logo is white). (unverified on a real car)
- **Screen switch requires modesChanged re-assertion** (2026-10-23, compared with AADisplay): the second control session resets the car's resource arbitration, invalidating the earlier modesChanged. `VirtualIap2Link.onControlSessionComplete` -> `SessionController.scheduleModesReclaim`: first at +1.5 s after auth + burst, re-assert at +4/+12/+30 s. Sending during 0xAA00/0xAA05 competes with iAP POSTs and Audi dies at ~12 s. "Picture for 3 s then disconnect" (0.1.12) had the same cause.
- Teardown attribution guard: if MIB3 was armed but the control session never completed, it is the link watchdog; never record it as an audio-tier incompatibility (that blacklists the media path and leaves the session silent). Only inside the 20 s audio probe window: WinPlay never runs the tunnel control session, so a normal disconnect after minutes of streaming was logged as the watchdog.
- `sendRawCsmLocked` logs the message ID from `frame[4..5]`; `[2..3]` is the length.

## 10. Video encoding and composition

### Frame format

- VideoConfig header must carry the encrypted flag and canvas size (Audi 4851, 2026-10-19, `docs/PROTOCOL-NOTES.md` §9g): `small_param[1] |= 0x04` (Encrypted) at offset 6; `params[1]` at offset 16 = width and height as two f32 LE; `params[0]` (8..16) zero. Audi/DELPHI build the decoder from these; without them the screen is black while session and touch work. DiPlay/WinPlay ignore them, so the bench cannot catch it. For "app connected, car black", check the `VIDEO: VideoConfig ... flags=` log line (prints the first 24 header bytes). Compare new header fields with catplay `screen_frame_ext.rs`.
- `annexBToLengthPrefixed` scans once into a fixed-size array (unit tested against the old byte-wise implementation); AES in place; socket wrapped in `BufferedOutputStream`, header and body sent together.
- HEVC (experimental, Settings > Display "HEVC encoding", default off; facts from CarPlayStation, GPL-3.0, read only): VideoConfig body `[u32 BE box length]["hvcC"][HEVCDecoderConfigurationRecord]`, header offset 6 adds 0x08; frames remain 4-byte length-prefixed; do not apply the H.264 SPS level patch (`annexBToLengthPrefixed(patchH264Level=false)`). Assembly in `video/HevcConfig`. WinPlay has no `hevcInfo`. CarPlayStation accepts self-signed P-256 MFi (we skip 0xAA03 verification). (unverified on a real car)

### Rate control and quality (upstream #26, VW MIB3 blurrier than native) (unverified on a real car)

- Bitrate `encoderBitrate()`: pixels x 8, clamped 4-12 Mbps, 60 fps x 1.5, total cap 12 Mbps; HEVC uses 65%. At 16.6 Mbps moving maps peaked at 19-20 Mbps and WinPlay backpressured.
- CBR when the encoder supports it (Qualcomm VBR 10 s average overshot the target by 75-80%; stability preferred).
- QP floors I 20 / P 22 (`KEY_VIDEO_QP_*_MIN`): without them Qualcomm spends the whole budget; map location-dot animation went from 16-30 KB per frame to 1-3 KB.
- Settings > Display "Clarity first" (`DisplayPrefs.clarityFirst`, default off) requests High profile: High + L4 -> L4 -> auto fallback. Adapters always Baseline. Some encoders silently ignore `KEY_PROFILE` (like `KEY_LEVEL`); the SPS is authoritative. Actual profile in the VideoConfig log line (66/77/100); one-line `VIDEO:` quality summary; keepAlive lines show actual bitrate.
- Video socket `sendBufferSize=256KB` and encoder low-latency hints (`KEY_PRIORITY`, `KEY_LATENCY`, `KEY_OPERATING_RATE`).

### Frame pacing and keyframes

- Composer loop is event driven: new input frames, overlays and layout animations call `wake()`; no fixed sleep polling. Reuse `posBuf/texBuf`; no direct buffer allocation in draw.
- New frames throttle to `1000/targetFps`. A "new frame" counts only inputs that are drawn into the output (dock + active app display + map when its tile is visible).
- Idle downshift (`composeIntervalMs`, replaced the old 33 ms idle heartbeat): within 1 s of the last change still 33 ms, afterwards 1 fps. The 1 fps floor guards against the historic "no frames -> receiver freezes". Based on WinPlay `diagnostics.json` (`%APPDATA%/winplay/data/`, a "video runtime stats" entry every 10 s) showing a real iPhone at 0-0.4 fps and ~0 Mbps when static, peaks ~13 Mbps. (unverified on a real car)
- Periodic I-frame 30 s (`VideoSender.KEYFRAME_INTERVAL_S`; was 3 s). I-frames ~550 KB vs P 5-20 KB. Head-unit keyframe requests go through `forceKeyFrame` -> `requestKeyFrame` immediately.
- Do not log per frame or per I-frame.

## 11. Audio and microphone

### Downlink audio

- `audioType` must be `media`. `/info` `audioFormats` lists type=100 tiers (Audi: compatibility, alert, default, media, telephony, speechRecognition), each with a different bitmap. Media into compatibility is accepted (SETUP ok, dataPort, packets received) but not routed to playback. Parse all tiers (an old `take(200)` log truncation caused a wrong conclusion). `AirPlayClient.negotiateAudio()`: media tier + PCM 48 kHz stereo (bit 15), else 44.1 kHz (bit 11); latency from that tier's `outputLatencyMicros`; without `/info` fall back to media/48k/250 ms.
- RTP per catplay real-car values (`rtp_recorder.rs`, `rtp_cipher_chacha.rs`): SSRC 0, 352 frames per packet, timestamp increments by frames, PT 100, big-endian s16, AAD = header bytes 4..12, trailing 8-byte little-endian nonce. These are wire format.
- Capture limited to REMOTE_SUBMIX output; never the microphone for downlink. Output pipeline, AudioRecord and media session are released together.
- Audio sync packets (RTP<->NTP anchor, 1 s) go only to the stream `controlPort` and stop at the first send error. Upstream tried retrying and a `dataPort` fallback and reverted both on 2026-11-18 (the build that played on the Audi stopped there); do not re-add them. The anchor is the capture time of that ts (`captureTimeOf`), not the send time: a send-time anchor puts the queue + encoder delay into the car's playback schedule (upstream 2c59047). `AirPlayClient.release` (not `close`, which reconnects reuse) stops the per-client `TimingServer`; each round builds a new client, so it leaked a thread + UDP socket per reconnect.
- Downlink audio is paced (upstream 90a3b6b..a813244): capture only queues (cap 16, drop oldest), the `audio-pacer` thread sends one packet per wall-time tick and fills an empty queue with silence (AAC: one frame from a throwaway encoder, since the capture encoder is single-threaded). Without it every capture/encoder stall left ts permanently behind wall time and the car's delay grew in steps. Rules carried over from upstream's car logs: extra packets that drain a queue deeper than 2 take future ticks (draining for free grew the car buffer +15 ms/s); the tick follows the measured capture rate in 10 s windows, clamped +-3% (REMOTE_SUBMIX ran ~1.4% fast); a stall over 1 s skips ahead instead of bursting late packets. The 30 s `AUDIO: streaming` line shows queue, silence-filled, dropped, rate and tick. (unverified on a real car)
- SETUP 200 plus steady UDP does not mean audio works: check PCM non-zero ratio. 2026-11-02 Samsung/Spotify: both captures were all zero initially; after skipping track, shell REMOTE_SUBMIX probe got 97,989/114,688 non-zero samples (peak 8,628) while playback capture stayed zero. WinPlay end-to-end with the new source still pending.
- No-sound triage: `AUDIO:` capture non-zero % and peak (0% = not captured: app forbids capture or audio routed to Bluetooth) -> `audio negotiation -> audioType=` -> the connId sampleTime in car `/feedback` advancing.

### Microphone uplink (v1, details in `docs/MIC-RESEARCH.md`) (unverified on a real car; WinPlay end-to-end verified 2026-11-17)

- Phone sends SETUP `type=100 audioType=speechRecognition input=true` with `dataPort` = the phone's own UDP port. Car packets mirror our downlink format; key label `DataStream-Input-Encryption-Key`; PCM is big-endian.
- Injection via shell `MODIFY_AUDIO_ROUTING` AudioPolicy record injection (`startMicInjection`). While injecting, the phone's own mic is unavailable to all apps, so it exists only during a voice interaction. Hotword (HOTWORD/SoundTrigger) cannot be injected.
- Trigger: any app recording during the session (UserService `activeMicApps`, AppOps watch) switches automatically; head-unit voice key `requestSiri` also launches the assistant.
- Both session retirement and `onEnded` must call `CarMicController.detach`.
- WinPlay pitfalls: injected track needs `setContext(shell)`; tags must start with `addr=`; downlink (silence) must be sent during mic streaming or the receiver does not open the mic; do not validate the RTP version bits of mic packets (WinPlay header bug).

### Phone calls (2026-11-21)

- Car call card (unverified): `call/CallState` reads call notifications in `MediaListenerService` (CallStyle extras `android.callType` 1 incoming / 2 ongoing / 3 screening, `android.answerIntent`, `android.declineIntent`, `android.hangUpIntent`, `android.callPerson`; a plain `CATEGORY_CALL` notification also counts). `GridActivity` swaps the dashboard music card for a call card (caller, app, duration from the notification chronometer; ringing: decline / answer, connected: mute / hang up), and a new ringing call calls `VirtualLauncher.showDashboardForCall` (closes the media pane player, leaves fullscreen map or app). `call/CallControl` fires the notification's own PendingIntents (background-activity start allowed on API 34+), falling back to `ICarInput.callControl` (ITelecomService `acceptRingingCall` / `endCall` as shell). Mute uses `AudioManager.isMicrophoneMute`, read back and undone when the call ends. Logs never contain the caller (`CALL:` lines carry app, phase and avatar icon type only). Telegram (2026-11-21) rings with CallStyle but shows the connected call as a plain ongoing foreground-service notification (no category, one "end call" action): `CallState.adoptOngoing` takes an ongoing FGS notification of an app that rang within 20 s (or is still ringing) as its connected call, single action = hang up, caller/photo carried over. Tapping the card outside its buttons during a call opens the notification's content intent on the media display (`VirtualLauncher.openCallAppInMediaPane`, moved over from the phone if the system reused the phone task); the pane closes when the call ends. WhatsApp's call photo is its generic avatar when it has none for the contact. WeChat (measured 2026-11-21): ringing = `CATEGORY_CALL` notification on its voip ringtone channel with a full-screen intent and a custom view but no actions; connected = ongoing foreground-service notification on `reminder_channel_id` without actions (adopted like Telegram); audio = it sets `MODE_IN_COMMUNICATION` itself (not Telecom), plays `USAGE_VOICE_COMMUNICATION`, records `VOICE_COMMUNICATION`. Outgoing WeChat / Telegram calls never ring, so `CarMicController`'s 400 ms recorder poll feeds `CallState.onRecorders`: an app recording while `AudioManager.mode` is IN_COMMUNICATION / IN_CALL is in a call, its ongoing foreground notification is adopted, a removed / re-posted adopted notification (WeChat flickered ACTIVE/none) keeps the card while the app stays in the call, and leaving call mode drops it. WeChat also rings with no call notification at all while its call screen is up or the app is in the foreground (capture 2026-11-22: `plugin.voip.ui.VideoActivity` resumed + a `USAGE_NOTIFICATION_RINGTONE` player, no `category=call` notification; 3 of 6 calls), so the same poll asks the Shizuku service `ringingApps()` (app uids playing a ringtone; shell holds MODIFY_AUDIO_ROUTING so client uids are real) and `CallState.onRingers` shows a ringing card without caller details for an app with no call of its own (`fromRingtone`, key `ringtone:<pkg>`); its buttons move the app's tasks from the phone to the media display (`openCallAppInMediaPane` with no intent, `via=moved`), and a real call notification replaces it. Caller photo and name of WeChat come from its custom ringing view (`call/CallAvatar`, inflated with the application context: an AppCompat Activity inflater makes `AppCompatImageView`, on which RemoteViews refuses `setImageBitmap` and the apply fails; name = largest text, used when the title is empty or the app label; the log records only `photo=` / `name from view=` presence). Card buttons for a call without the matching action open the app's own call screen (full-screen intent, else content intent) in the media pane so the driver presses the app's button; Telecom remains the last fallback.

- Call voice to the car (VoIP, unverified): while `CallState` is ACTIVE, `CarMicController.callAudioLoop` asks the Shizuku service for `startCallCapture` (AudioPolicy loopback mix, `RULE_MATCH_ATTRIBUTE_USAGE` VOICE_COMMUNICATION + `voiceCommunicationCaptureAllowed`, record sink built by hand with `setInternalCapturePreset(REMOTE_SUBMIX)` + `addr=` tag + `setContext(shell)`), and the mic stream's downlink sends that voice (byte-swapped to s16be, 200 ms buffer cap) instead of silence. The mic stream still announces `audioType=speechRecognition`; switching to `telephony` + `modesChanged` phoneCall is open. A WhatsApp call already used the car mic for the uplink (2130 packets in 42 s, 2026-11-21).
- Today: 0x4154/0x4157 get a fixed "no call" reply (MIB3 tunnel, adapters), call audio stays on the phone, and HFP to the car is disconnected on purpose (section 5), so the car cannot take the call over Bluetooth either.
- Receiver side (WinPlay source, mirrors CarPlay): call audio = the type-100 bidirectional main audio stream with `audioType=telephony` (PCM mono / Opus), i.e. the same path as the speechRecognition mic uplink; "in a call" = `modesChanged` appStates `appStateID 2` with `entity 1`; steering-wheel answer/hang-up = telephony-page HID reports (Hook Switch index 1, Drop index 3) that our HID parser currently ignores.
- S24 (SM-S9280, SDK 37): shell holds `CALL_AUDIO_INTERCEPTION`, `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, `MODIFY_AUDIO_ROUTING`, `MODIFY_PHONE_STATE`, `ANSWER_PHONE_CALLS`; the audio policy lists `AUDIO_DEVICE_OUT_TELEPHONY_TX` and `AUDIO_DEVICE_IN_TELEPHONY_RX` as available devices, the prerequisite for Android 12+ PSTN call audio interception (`AudioPolicy.createAudioRecordSink` / `createAudioTrackSource` on those devices). Not yet tried with a real call.
- VoIP apps, measured on a WhatsApp call (dumpsys audio / media.audio_policy, 2026-11-21): audio mode `MODE_IN_COMMUNICATION` owned by Telecom (self-managed call); far-end voice = OpenSL ES player `USAGE_VOICE_COMMUNICATION`, flags 0x0 (no `FLAG_NO_SYSTEM_CAPTURE`), on the phone speaker; near-end = recording `src:VOICE_COMMUNICATION`. Uplink: `MIC_INJECT_PRESETS` already includes VOICE_COMMUNICATION (7), so the existing injection should cover it. Downlink: app-side playback capture covers only media, game, unknown, navigation, sonification and the app lacks `CAPTURE_VOICE_COMMUNICATION_OUTPUT`; the shell capture reads REMOTE_SUBMIX. Planned route: a shell-side AudioPolicy loopback mix matching `USAGE_VOICE_COMMUNICATION` (shell holds the permission), which also takes the voice off the phone speaker. Unverified.

## 12. Virtual displays and launcher

### Display creation flags (`CarInputServiceImpl.createDisplay`)

- Trusted displays add `VIRTUAL_DISPLAY_FLAG_PUBLIC`. Private displays (owner shell uid) reject third-party IME windows: `WMS.addWindow` -> `Display.hasAccess` returns `ADD_INVALID_DISPLAY(-9)`, `SoftInputWindow.show()` enters `REJECTED_AT_LEAST_ONCE` and never retries. With `OWN_CONTENT_ONLY` set, PUBLIC does not auto-mirror. The dock display stays private.
- IME policy must be set to LOCAL after creating each trusted display: shell reflection `IWindowManager.setDisplayImePolicy(id, LOCAL)`. The default for virtual displays is `DISPLAY_IME_POLICY_FALLBACK_DISPLAY` (`mShouldShowIme=null` in `dumpsys window displays`), which routes the keyboard to the phone. Read `WindowManager.DISPLAY_IME_POLICY_LOCAL` reflectively (ROMs may change enum values; fall back to 0 and log). Get the proxy via `ServiceManager.getService("window")` + `IWindowManager$Stub.asInterface` (`WindowManagerGlobal.getWindowManagerService` kills the process on this ROM). Needs `INTERNAL_SYSTEM_WINDOW` (shell has it). Policy is per display instance, so reapply on every create (idempotent); trusted displays only. Both fixes are required (policy alone: `imeDisplayId` moves but `mImeWindow=null`; PUBLIC alone: keyboard on phone). Diagnostic tool: `/data/local/tmp/const.jar ImeConst <ids...>`; compare `mViewsCreated` with `mImeWindow` in dumpsys.
- `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED` (1<<12, @hide) together with `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` (1<<11) on trusted displays **and the dock**: keyguard no longer sends sleep tokens (even with PIN lock), and the displays leave the default display group so a powered-off physical panel does not blank them. Without the group flag the system strips ALWAYS_UNLOCKED silently. Without the pair on the dock, the dock went black during panel-off. Requires `ADD_ALWAYS_UNLOCKED_DISPLAY` / `ADD_TRUSTED_DISPLAY` (shell has them on Honor). Some ROMs throw SecurityException: three-level fallback (group + unlocked -> group -> base flags). Read back `display.flags` and log in `/data/local/tmp/carinput_svc.log`.
- `DESTROY_CONTENT_ON_REMOVAL` (1<<8) only finishes activities; tasks stay in Recents. On detach first `am stack remove` car-display tasks (also removes Recents), then release displays, sequentially in one background task (detach may be on the main thread).
- Car task IDs are persisted in prefs. Disconnect removes other car tasks and preserves the active map's package and task ID. The active map stays on its virtual display with its surface detached, so disconnect does not bring the map to the phone foreground. Residual cleanup skips that display. Reconnect moves the exact task to the new car display before releasing the parked display. The map's orientation overrides use non-killing writes at disconnect and reconnect to preserve in-progress navigation.

### Fallback form (app-side PUBLIC displays)

- EMUI 12 (Huawei Mate30 Pro, 2026-10-20) returns null silently from shell `createVirtualDisplay`. The fallback branch must also create the map display. Resize: trusted form via shell `svc.resizeDisplay`; fallback form via app-side `VirtualDisplay.resize()` (shell cannot resize displays it does not own). Log which display failed (-1) individually.

### Resize and orientation

- After `VirtualDisplay.resize` call `SurfaceTexture.setDefaultBufferSize(newSize)`, otherwise content is drawn into the old buffer's top-left. Resizes run in the background.
- Displays must ignore app orientation requests: on attach, for app / appHi / map / media, `cmd window set-ignore-orientation-request -d <id> true`, read back (`failed to make app displays ignore orientation requests` warning). On detach set false **before** releasing (else it persists by uniqueId). Without it, portrait apps (Toutiao) rotate the whole display (`rotation 0->3`, `2251x1152 -> 1152x2251`), breaking aspect, touch mapping and the desktop.
- Fixed-portrait maps need the orientation compat override in place before launch; `session_maps` persists packages to revert; restore on disconnect; do not touch the user's own force-landscape prefs.

### App force-landscape (`AppOrientationCompat`)

- Requires Android 14+ (SDK 34). ChangeIds `265451093` (OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR) and `265464455` (OVERRIDE_ANY_ORIENTATION) are standard AOSP from Android 14 (`ActivityInfo`, consumed by `LetterboxUiController`; moved to `AppCompatOrientationOverrides` in Android 16). Android 13 has none (Honor A17/SDK 37: 392 IDs including both; Redmi K40 A13/SDK 33: 173 IDs, neither). Not Shizuku related.
- User builds only allow overrides on debuggable apps unless the ID is registered; writing to an uninstalled package falsely succeeds (only `rawOverrides`). Verify with dumpsys `name=` fields.
- Code: `MIN_SUPPORTED_SDK = 34`, then `changeIdsRegistered()` (one dumpsys, ~30 ms, cached; each ID needs its own `name=`). `WriteResult.UNSUPPORTED` (permanent: pref not saved) vs `FAILED` (temporary: keep intent). `applyAll` skips entirely if the probe fails. Panel shows a yellow banner and greys out.
- `am compat enable/disable` kills the target app immediately (pid gone). Do not design "is it running / restart?" prompts. `applyAll` also kills running listed apps, including on the phone.
- Writes are queued at pairing start (endpoint handling), not attach; attach only `awaitPending()` (~0 ms). Blocking attach delays the first screen keep-alive frame and eats the ~10 s RECORD budget. `awaitPending` timeout (6 s) must log. `VirtualLauncher.launchApp` calls `awaitPending()` first.
- State source: `dumpsys platform_compat` (no `cmd platform_compat`; full 30 ms/36 KB, grep to ~3 KB), read once when the panel opens. Parse both `packageOverrides` and `rawOverrides` (uninstalled packages only in raw; legacy dirty keys like `force_xxx/component`). UI: grey "normal" text, red "system leftover, tap to clear" (warns it restarts the app), a top banner for out-of-list leftovers.
- Startup leftover handling: `resetAll` is tied to "leftover displays detected" (`MainActivity.refreshShellState` startupCheck). Restore before cleanup; pre-binding the UserService (reaps old process) after the leftover scan. Test: write `force_<pkg>=true` into `shared_prefs/app_orientation.xml`, force-stop, restart, expect the "ORIENTATION: restored N landscape override(s)" log.
- Override written while the app runs: the restored task may keep the old config (`displayChangedWithoutRestart=true`, `overrideOrientation=PORTRAIT`, `letterboxReason=FIXED_ORIENTATION`). Only force-stop + `am start` fixes it. Repro with `am compat enable --no-kill` (test only). `LetterboxHintController`: event-driven probe (connect, app launch, map fullscreen, restart) parses `bounds=` and `mMaxBounds=` from `dumpsys activity activities`; shows an `OverlayCard` (composer 2D texture layer) on the left, else right, else nothing, with a "force restart" button. Self-heal: if the pref is on but the system override is missing, rewrite it and relaunch the app on its display; if still portrait, show a final card. Probes must pass `displayIdHint`.

### Leftover displays across versions

- Shizuku reuses the shell process per component + process name + version (APK `lastUpdateTime`). After reinstall the old process survives (`am force-stop` does not kill it), and only the creating process can release its displays. Old process detection: `/proc/<pid>/maps` base.apk marked deleted. Three layers: `CarInputServiceImpl` self-exits when `lastUpdateTime` changes (<= 15 s, measured 5 s); `CarInputClient.reapStaleUserServices` kills other `:car_input` pids after binding; startup cleanup runs automatically, dialog only if it fails.

### Dashboard layout

- Order: dashboard (page 0) -> app pages (1+). Dashboard only left-swipes forward; the first app page right-swipes back via the native pager. Boundary interception and forced `onPageSelected` returns were removed (release 0.1.35, supersedes earlier one-direction and both-direction rules). `dashboardUserDragging` distinguishes user drags from animations; user drags must never block MOVE/UP injection. GL previews during drag; mode is committed at IDLE; cancel keeps the mode.
- Left swipe = finger-following fakeDrag: `DashboardSwipeGesture` withholds injection until classified. Left drag -> `beginDashboardDragOut` + pager `fakeDragBy` (GL map layer follows `onPageScrolled`); tap -> replay down+up at origin; vertical/right -> replay down then inject normally. The media card and music app never see paging gestures (prevents accidental taps).
- Mode is driven by the Dock; ViewPager2 selection must not write mode or hide the map early. Non-Home position sync must not rewrite the GL layer; IDLE finalization only applies when `dashboardPageAnimating` and uses current `mapFullscreen`/`dashboardMode`. No adapter reload during the transition.
- New connection: set split view as home first, then start Dock and launcher; the default map launches in the background; returning to split keeps the switched map.
- Layout (`DashLayout`): `dockAtBottom` and `splitVertical` are separate. SIDE_BY_SIDE, MUSIC_BOTTOM (dock left, map top, music strip bottom), DOCK_BOTTOM (Android Auto style, dock thickness x0.72), PORTRAIT. Preference in Settings > Display > dashboard layout (next connection). Layout preference only applies to narrow landscape (aspect < 1.9); wide is always side by side; narrow defaults to dock at bottom unless "side by side" is explicit (old "auto"/"music bottom" values map to default). Decide whether new geometry code needs dock position or split direction; settings "narrow" uses `isPortraitCanvas()`.
- Music strip layout does not embed media apps (`launchMediaApp` -> normal `launchApp`); card `view_dashboard_strip.xml` with the same IDs as the vertical card. `PaneBounds` vertical-split branch starts x at dockWidth (0 for portrait cars).
- Dock-bottom landscape uses the supersampled app display: TouchRouter dockAtBottom branch uses `activeAppDisplayId()` + `activeAppScale()`.
- Split ratio from pane shape (`VirtualLauncher.dashSplitFractionFor`, test `DashSplitAdaptiveTest`): media card width/height ~0.62 (portrait height/width ~0.45), map takes the rest, clamped 0.55-0.8. 1920x720 -> 0.75; 1280x720 -> ~0.60. Log `LAUNCHER: dashboard layout canvas=... scale=... map ratio=...`. The old fixed 3:1 and `dashSplitFraction` drag preference are no longer read; do not re-enable the drag handle. (History: drag split 2026-10-22 used visual gap vs touch width constants, draw-only during drag, sticky routing; `VirtualLauncher.dashSplitGeom` and `DashSplitMathTest` remain the geometry source.)
- Stream pixel constants (`CARD_MARGIN_PX`, `DASH_GAP_PX`, `DASH_HANDLE_TOUCH_PX`, `TILE_CORNER_RADIUS`) scale with `DashMetrics.scale` (stream short side / 720); identical at 1920x720. `DashMetrics.configure` must run before any geometry in attach. Multiply new stream pixel sizes by scale.
- Map and media display density = 200 x `DashMetrics.scale` (min 120) (`MAP_DISPLAY_DPI`). Fixed 200 dpi at 800x480 left Google Maps a sliver (~534x271 dp). Card content in `GridActivity` uses dp (app display density already scales with height, ~432 dp tall).
- `PaneBounds` is the single geometry source for display creation, GL and touch; swapping positions only translates. Media apps use `mediaAppBounds` = full `mediaPaneBounds`; the back control is a GL floating button (no reserved top row, release 0.1.32).
- The CarPlayMedia display is supersampled `VirtualLauncher.mediaSupersample` (chosen at attach so the pane is >= 320 dp wide in the phone density, 2..4x pixels and density; S24 600 dpi = 3x; compositor input and `TouchRouter` media coordinates scale with it): normal apps keep their dp size, while WeChat, which lays out with the phone's density on any display, had its call screen blown up and cut off in the 427x688 pane (2026-11-22). The factor must be set before `composer.start`: its media input buffer is sized from the argument, while the display is created later in `onSurfacesReady`; deciding it inside the callback gave a 2x buffer for a 3x display and only the top-left of the pane showed (Spotify cut off, WeChat call screen shifted). (unverified on the bench after the change)
- Media apps run on the separate CarPlayMedia display; never call plain `launchApp` that exits the dashboard. Release, old-display cleanup, orientation policy and GL input include the media display. A singleTask app already on the media display relaunched from the launcher returns to the dashboard media pane.
- Map touch: map card taps in the front `PaneBounds` hit test own the event; tile taps only expand (no injection into the background map), no static coordinates during transition. Later legacy routing only handles `mapFullscreen`. Split-map direct control: map display resized to `mapPaneBounds`, touch only translated; gestures starting inside the card must not start the dashboard swipe. Media press gestures crossing into the map stay owned by media and finish correctly.
- GL animates rects/corners for 320 ms.
- `activeMapComponent` records the session's foreground map; returning to the dashboard does not force the default. Switching maps reuses the same-display task; do not kill the old navigation process.
- Default map: any installed launchable app; manual picks go through map-display routing. `DefaultMapPrefs.resolve()` uses pure `resolveDefaultMap()` (explicit -> priority scan), tests `DefaultMapPrefsTest`. A former cycle (`resolve` -> `VirtualLauncher.defaultMapComponent` -> `resolve`) overflowed the stack when prefs were empty (after reinstall), swallowed by `Bg.run`. `VirtualLauncher.defaultMapComponent` may only forward one way.
- Background apps: entered only from the Dock (long-press Dock Home also opens it; the apps page has a fixed entry). List = apps successfully launched this connection (including the auto-launched default map), cleared on disconnect. Tap switches, close one or all, back to apps page. Closing removes car windows only (no force-stop; music services may keep playing); task IDs re-read before closing and verified after; failures show a retry message. Task parsing handles Samsung `Display #N` and Honor `Display: mDisplayId=N`; exclude phone display, Dock, all DroidCar affinities. Do not treat in-memory `runningApps` as truth.
- "Switch current map" and "choose another installed app" share a searchable picker with icons. Switching the current map does not write the default preference.
- Build/interaction notes for the dashboard: `docs/DASHBOARD-UX.md`.

### Dock and launcher grid

- Dock dimensions scale with canvas resolution. Design base (`design/launcher-proto.html`, 1160x540 @ 420 dpi, dock 48 dp = 126 px, clock 22 px). `SessionController` sets dock density `420 x min(streamW, streamH)/540` (cap 1680); Audi base = 1.0, 2320x1080 bench = 840 dpi. Rules: dock layouts (`activity_dock_sidebar/bar.xml`) only dp (design px / 2.625); `DockActivity` sets font sizes in PX as design px x designScale (`densityDpi/420`), not sp; blur radii and `fitClockText` steps also x designScale. Dock and desktop keep their fixed dark design.
- Portrait grid rows adapt (`GridActivity.computeCellMetrics()`): rows = `usableH / (cellW x 1.25)` clamped 3-6. Landscape still 3; portrait 648x1241 -> 6 rows (~154x193 cells). Page capacity = `COLUMNS x rows` (runtime field). Verify with "Show layout bounds" screenshots.
- Grid cells are `isFocusable=true` with `foreground=focus_ring` for DPAD.

### Voice assistant (Gemini)

- Gemini overlay is a normal Activity (`googlequicksearchbox/...FloatyActivity`, `ty=BASE_APPLICATION`) opened on the focused display. "Hey Google" usually lands on the phone; `am display move-stack <task> <gridDisplay>` moves it to the car (native, touchable). Mirror cropping was tried and dropped.
- Mic detection only via AppOps: Google app `RECORD_AUDIO` / `RECORD_AUDIO_HOTWORD` (UserService `isAssistantListening`, shell has WATCH_APPOPS). `AudioRecordingCallback` and shell `getActiveRecordingConfigurations` do not see `src:HOTWORD` (S24).
- Close with `am stack remove`, not BACK (BACK collapses it back to the phone with stale text). Tapping blank space on its display lets Gemini end normally.
- Dashboard / fullscreen map: move the overlay onto the map display (map stays visible, overlay receives taps); otherwise onto the active app display. `tilesSuppressed` (hiding the map tile) was rejected by the user and is only a fallback when moving fails.
- Gemini may show a portrait screenshot of the phone as screen context; that is Gemini behavior.
- During a session, `getRealMetrics` for display 0 may return the virtual display size (S24: 2628x1080); use `wm size` for the real phone size.

### Chat mode and density quirks

- React Native apps (Discord) measure text with phone density and draw with display density, clipping text when they differ. `ChatMode.matchPhone` logically enlarges the app display via `wm size` so dp stays the same at phone density; `activeAppScale()` uses the same factor. The launcher is on the AppHi display; do not change that.
- Chat mode excludes WeChat and Discord (runtime density change breaks them: duplicated titles, garbled glyphs).
- Tablet layouts: ChatGPT and YouTube switch at 188 dpi. WeChat is single-column at any density (150 dpi sw768, logical 2920x1200@240 with cold start: identical); it cannot be forced into two-pane externally.

### Notification banners

- Default off, at most one held, drawing and other-APK resource loading in the background; system prompt cards have priority; cleared on disconnect; notification content is never logged.
- Banner animation only moves the `OverlayCard`; the composer re-uploads textures based on bitmap identity.

## 13. Touch and HID input

- Multi-touch (upstream 3a83dc6 approach, own implementation): `ICarInput.touchDown/Move/Up(slot, ...)` (appended). The service keeps one stream; the first finger is the old single-pointer obtain (pointer id 0), a second finger on the same display switches the stream to multi-pointer events (POINTER_DOWN, MOVE with all pointers, POINTER_UP; toolType FINGER, non-zero contact size, public 14-argument obtain). The lifting pointer stays in its UP event and is removed after (a single-pointer UP in a multi stream left the other pointer stuck upstream). `TouchRouter`: the first finger keeps every single-finger rule; others join only while it is injected, mapped with its transform (`primaryScale`), are lifted when it lifts and then swallowed until they lift (otherwise the finger left after a pinch tapped the map). Audi MIB3 reports one contact only; WinPlay's descriptor has 2 contacts but its UI sends one (mouse), so the bench for this is DiPlay. (unverified on a real car)
- Touch injection must be ASYNC (mode 0). The old mode 2 (WAIT_FOR_FINISH) waited for each MOVE; Google Maps queued events ("cannot drag, map keeps spinning after release").
- HID capability is decided by the descriptor only, never by model, uuid or report length (`docs/HID-KNOB.md`). `/info` `hidDevices`: send `hidSetInputMode` to every uuid (Audi uses "0"/"1", DiPlay integers).
- Consumer page (0x0C) via `hidSendReport`: the 1-byte value is an **index into the Usage array** (HID Array, LogicalMax = length-1), not a Usage ID. Audi "next" sends 0x05 -> usages[5] = 0xB5. `ConsumerKeys.parseDescriptor()` builds the mapping; Array and Variable semantics are decoded separately. Dispatch via `AudioManager.dispatchMediaKeyEvent` (same path as the music card).
- Touch: Digitizer page (0x0D) with 16-bit X/Y. Single finger (Audi 5 bytes `[tip][X u16LE][Y u16LE]`) vs multi-slot (DiPlay 12 bytes `[slot][down][X][Y] x2`) decided by the count of Contact Identifier (0x09 0x22). DiPlay knob (4 B) and telephony (1 B) overlap media key lengths; telephony usage 0xB3 would be misread as fast-forward. Length heuristics only when the descriptor is missing. Tests `ConsumerKeysTest`, `TouchReportsTest`.
- Generic Desktop (0x01) Wheel (0x38) / Hat (0x39) -> `HidNavigation` -> `ICarInput.keyEvent(displayId, DPAD_*)` on `VirtualLauncher.activeAppDisplayId()` (KeyEvent `setDisplayId`; fallback `input -d`). Never dispatch DPAD globally (lands on the phone). The dock is a separate display; DPAD does not cross displays.
- Knob descriptors with Generic Desktop X/Y or Consumer AC Home (0x223) / AC Back (0x224) are decoded field by field from the descriptor (`HidNavigation.fieldsOf`; ChairPlay / xcertplay knob = `[select|home|back bits][X s8][Y s8][wheel s8]`): stick = one DPAD key per new direction, Home = `VirtualLauncher.onHomePressed` (never Android HOME, which lands on the phone), Back = KEYCODE_BACK. Descriptors without them keep the old bitmap layout (BMW iD8.5 calibration). Consumer 0xCD = Play/Pause. Telephony page devices (`ConsumerKeys.parseDescriptor(d, USAGE_PAGE_TELEPHONY)`): Hook Switch 0x20 answers a ringing call, Drop 0x26 declines / hangs up, Phone Mute 0x2F toggles the mic. Tests `HidKnobFieldsTest`. (ChairPlay bench, unverified on a real car)
- `cmd voiceinteraction show` is refused to shell on the S24 (Android 17, SecurityException); `CarMicController.onVoiceButton` then sends `input keyevent KEYCODE_ASSIST`.
- Head-unit voice key -> `AssistOverlayController.onAssistRequested`: the newer Gemini bar opens without the mic, so the mic watch never fired and the bar stayed on the desktop display under the dashboard map; the request keeps the window check running for 6 s. Head-unit Back (HID) goes through `AssistiveDot.back()` (fullscreen map / last touched dashboard pane / active app), not straight to the active app display.
- Knob focus (`input/KnobFocus`): on DroidCar's own screens (dashboard, desktop, car settings, background apps) DPAD / Confirm frame and pick targets in stream coordinates: dock items (`DockActivity.focusViews`), desktop cells and the dashboard media card + prev/play/next (`GridActivity`), the map card (`mapPaneBounds`) or media pane, and every visible clickable view of `SettingsActivity` / `RecentAppsActivity` (registered via `onOwnScreenResumed`; a clickable row hides clickables inside it; no target further up/down scrolls the screen's scrollable and frames the next row). Directions pick the nearest target that way, the composer draws a white frame (`focusRing` overlay slot), Confirm clicks. Only the dashboard's map card and media pane are entered with Confirm (two apps share that screen): keys go to that display, the frame turns green, Home steps back out, Back goes to that app. A third-party app or the fullscreen map gets the keys directly with no frame (user decision 2026-11-23). The media controls are their own row: Down from the card enters it, Left / Right inside, Up leaves, so Left / Right on the card keep moving to the map and the dock. Back outside an app follows the assistive dot routing, with the framed map / media card counting as the last used pane. Right past the last dashboard target slides to the desktop with the swipe animation (`GridActivity.animateDashboardToDesktop`: `beginDashboardDragOut` + an animated fake drag), Right / Left past a desktop page edge turns the page. Back inside a dashboard app that leaves the pane empty (`restorePaneAfterBack` returns true: it was at its root) steps out to its card like Home. An app opened from the dock returns to the dashboard when backed out of (`returnToDashboardOnExit`, checked in `GridActivity.onResume`, cleared by any explicit mode change); one opened from the desktop returns to the desktop, and the knob frames the item that opened it. The frame is system blue (white vanished on the light-mode settings), green inside an app. Any car touch hides the frame. Geometry: grid-display views map to the stream with `gridScale` plus the dock offset; dock views add `appHeightPx` when the dock is at the bottom. (ChairPlay bench, unverified on a real car)
- Raw HID logging: Settings > Display "HID raw log" (`DisplayPrefs.hidRawLog` -> `HidNavigation.rawLogEnabled`, lines `HID-RAW: uuid=... bytes=...`).
- During an overlay card or handle gesture, routing is sticky for the whole stream (do not re-route per frame by coordinates).

## 14. Head-unit compatibility notes

Protocol differences are authoritative only from logs; see `docs/PROTOCOL-NOTES.md` §9 before changing protocol code.

- **Audi MIB3 (DELPHI)**
  - MMI 3315: needs 0x5702 despite a sparse list; MFi 32 B over BT, 20 B tunnel; first-connection 403 is normal (re-invite in 18-20 s); NUL-padded 0x4301 addresses; BT drop at ~15 s, AirPlay window ~21 s after 0x4301; needs the Wi-Fi tunnel and modesChanged re-assertion; sensitive to ctrl `srcvers`/instance name.
  - MMI 6318: MFi 20 B.
  - MMI 9310: very late 0x4301 (2 min 35 s).
  - 4xxx / CarPlay 210.81: pre-HomeKit era, plaintext + AES-CTR, IPv6 link-local only. 4851: needs VideoConfig flags and canvas size.
  - Pair only; profiles connected block the iAP2 window.
- **BMW**: 87866 needs fresh-sequence SYN|ACK for every SYN. iD9: `wifiGen=NEW` (full lists without 0x57xx), skips 0x5702. BMW also produced the `connect-network` / NSD / watchdog command-line findings.
- **GM (Buick, Cadillac)**: Cadillac CT5 sends consecutive markers to renegotiate (handled) but SETUP still returns 500 (open). GM 0x4300 format adjustments landed with commit 3993d4d. (unverified on a real car)
- **VW MIB3**: blurrier than native; addressed by bitrate/CBR/QP/High profile changes. (unverified on a real car)
- **Ford SYNC+ 2.0 / Lincoln**: no answer to SYN with sequence 99; random initial sequence. (unverified on a real car)
- **WinPlay (Windows bench)**: the cluster dashboard used for route guidance is our own test build (`F:/.codex/2026-11-01/x20-f-dev-cardeck/outputs/WinPlay-Cluster-Test-r2`, unpacked JS); lane arrows are drawn by `arrow()`/`laneShape()` in `resources/app/src/ui/cluster.js` (combined lanes stagger branches: turns leave mid-stem when the lane also goes straight, a U-turn becomes a low hook; reopen the dashboard window to load changes). Filters by `deviceID` (403 OK); releases BT ~3 s after RECORD; reads only body/avcC (misses header bugs); no `hevcInfo`; `diagnostics.json` is the reference for real iPhone bitrate behavior; mic quirks in section 11; overwrites auto-join via `connect-network` without `-d`.
- **DiPlay (Redmi K40 bench)**: MFi 32 B only; tears down the whole wireless stack on early RFCOMM loss; resends 0x5703/0x4301 after streaming starts; 12-byte multi-slot touch; ignores VideoConfig header fields.
- **CarPlayStation (Windows, GPL-3.0, reference only)**: source of HEVC facts; accepts self-signed P-256 MFi.
- **Wireless adapters**: see section 6.
- **Huawei EMUI 12**: shell virtual display creation returns null; fallback form.
- **Samsung S24 / Spotify**: playback capture zero, REMOTE_SUBMIX works; Gemini HOTWORD invisible to recording callbacks.
- **Bigme ROM**: zz85's fix (no `OWN_DISPLAY_GROUP`) not merged.

## 15. Phone UI and settings

- Colors: `m3_*` tokens in `values/` (light) and `values-night/` (dark) take system Material You palettes (`system_accent*/neutral*`, minSdk 31); four surface tones in `res/color(-night)/` with `android:lStar`. Edit the `colors_gen` table. Car settings (`SettingsActivity`, forwarded `PhoneSettings`/`SettingsContent`) follow phone dark/light too (user request). Dock and desktop stay fixed dark.
- Settings panel colors via theme attrs `panel*` (`attrs.xml`): Launcher theme = legacy iOS dark values; App and Settings themes = m3 tokens. Never hard-code hex colors in panel layouts/drawables/code; use `?attr/panel...` or `SettingsRows.color()`.
- Wallpapers: dark/light slots (`WallpaperStore.Slot`) follow phone theme (`attach` listens for config changes; `markChanged` only if slots differ); light defaults to "same as dark". Built-in patterns `WallpaperPresets` are code-drawn gradients (original).
- `MainActivity` declares `configChanges` `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden` (stateful console), so `layout-land/` never applies. `applyContentLayout(landscape)` moves the hero card out of the ScrollView into `contentArea` on landscape (right column `mainScroll` uses left padding) and back to `contentRow` on portrait; rebuild layoutParams on parent change (LinearLayout vs FrameLayout); landscape height `WRAP_CONTENT`; also call in `onCreate`. Landscape budget ~284 dp of ~300 dp; hub plate 44 dp, CARPLAY label hidden; `SignalWavesView` scales by min(width ratio, height ratio); `hubPlate`/`hubCaption` centered by gravity. Hero card width = screen width minus page padding, not `contentArea.width` (stale during rotation).
- Dialogs: size via `ui/PanelWindow` (92% width, optional height ratio; weak list of showing windows, `resizeShowing(this)` in `onConfigurationChanged`). Prompts via `ui/DarkMessageDialog` (plain `AlertDialog` is light under `Theme.AppCompat.DayNight`): 1-2 actions horizontal right-aligned, 3+ vertical equal width with pill backgrounds on secondary buttons; `Emphasis` primary/text/danger; `dismissOnClick=false` keeps the dialog for retry.
- Disclaimer gate: `legal/DisclaimerPrefs.CURRENT_VERSION` is a version number; bump it whenever `DisclaimerContent` changes. Accept requires all of: 10 s countdown, scrolled to bottom if scrollable, not declined. "Decline" is immediately available and calls `finishAndRemoveTask()`. Decide scrollability only after layout (`scroll.height > 0`). Countdown stores an absolute `elapsedRealtime` deadline through `onSaveInstanceState`. Dialog `setCancelable(false)` + `setCanceledOnTouchOutside(false)`; the menu entry is read-only.
- Connection page "wait for reconnect after car disconnect" (`DisplayPrefs.waitForReconnect`, default off): when off, only peer-initiated ends trigger full `disconnect`.
- Settings > Display panel is wrapped in a ScrollView (car content area ~200 dp).

### Music card and notification access

- "Music plays but card shows nothing" = no notification listener access: `getActiveSessions()` still returns sessions with valid `PlaybackState` but `metadata` null (`dumpsys media_session` shows metadata).
- `media/NotificationAccess` grants via Shizuku at app start, after Shizuku authorization and after session attach (`VirtualLauncher.attach`), idempotently. Do not trust `cmd notification allow_listener` exit codes, and verify with 4 x 250 ms retries (settings writes lag). Compare entries of `enabled_notification_listeners` exactly (split by `:`), not `contains(package)`.
- Granted but not bound: after reinstall the entry exists but `onListenerConnected` never fires and `dumpsys notification` "Live notification listeners" lacks the component. `NotificationAccess.rebindIfNotConnected`: if `serviceConnected` is false, read `dumpsys notification --noredact`, trust only the Live section, and if missing run `disallow_listener` + `allow_listener` and poll.
- After a package rename, restart the Shizuku service if authorization requests do nothing.
- On listener disconnect/destroy publish `MediaSessionsState.publish(null)`. Static card subtitle is neutral ("Media").
- Card states: no access, session paused, no session (last two both "not playing"). Metadata arrives in stages (title first, art/duration later): empty results retry with short backoff instead of caching. Position = anchor + speed extrapolation, UI refresh every second.
- `MediaListenerService` polls `getActiveSessions` every second only during a session; outside it relies on callbacks.

## 16. Power, screen off and dimming

Details in `docs/PHONE-SCREEN-OFF.md`.

- Two sleep paths blank virtual displays when the phone locks: keyguard sleep tokens (fixed by ALWAYS_UNLOCKED + OWN_DISPLAY_GROUP, section 12) and display-off tokens (PUBLIC virtual displays lack `FLAG_NEVER_BLANK` and follow the default panel power state). A display created while the screen is off is born asleep; product creation happens while the user operates the phone.
- Pressing the power button = real sleep: window surfaces are stripped and re-attachment is unreliable on this ROM (activity RESUMED but `mHasSurface=false`). Users should tap the dim overlay, not press power.
- Fake screen-off: `ICarInput.setScreenPower(on)` in the UserService reflects `SurfaceControl.setDisplayPowerMode(token, POWER_MODE_OFF/NORMAL)` (needs ACCESS_SURFACE_FLINGER; shell has it). The system stays interactive (`dumpsys power` wakefulness Awake); verify with the `powerMode=` header of `dumpsys SurfaceFlinger`. Token lookup: `SurfaceControl.getPhysicalDisplayIds/getPhysicalDisplayToken` (Android 11-14; removed SDK 34+) -> `com.android.server.display.DisplayControl` loaded via `ClassLoaderFactory` + `SYSTEMSERVERCLASSPATH` with `Runtime.loadLibrary0(cls, "android_servers")` (scrcpy approach) -> `SurfaceControl.getInternalDisplayToken` (the only one working on Honor A17). All fail -> returns false and the dimmer stays an overlay.
- "Real screen off while connected" (Settings > Display, `DisplayPrefs.screenOffOnConnect`, key `screen_off_on_connect`) is **default off** (user request; supersedes the earlier default-on note). Power-off is carried by the dim overlay (`enterDimNow`); the Honor touch IC keeps working with the panel off, so tapping wakes it. `SessionController.applyScreenOffPreference` applies changes mid-session.
- Timing: `enterPhoneScreenOff()` after streaming starts; `onEnded` -> `restorePhoneScreen(sleep=true)` (stay dark, KEYCODE_SLEEP); `retireSession`/manual disconnect -> `restorePhoneScreen(false)`. Three recovery layers are mandatory: session end, UserService self-exit, next app start (otherwise a crash leaves the phone black).
- SCREEN_OFF broadcast (real sleep) must clear `panelOffByUs`, remove the dim overlay and stop the timer, otherwise the overlay sits over the lock screen. USER_PRESENT reschedules the timer (fingerprint/face unlock produces no touch events).
- Auto-dim is session driven, not app-start driven: `shouldAutoDim` (pure, tested) = `enabled && sessionActive && attached`. The flag is set only after `attach` succeeds in `VideoSender.start`'s onSurfaceReady and cleared in `onEnded`, `retireSession` and `detach()`. Manual `enterDimNow` uses the `force` path. The dim overlay is also the pocket touch shield while the panel is off.
- The phone home screen is usually invisible during sessions but its Activity is foreground: every looping animation or timed refresh must pause on `ScreenDimmer.isCovering()` / `addCoverListener` and onPause (`MainActivity.syncPhoneUiActivity`). The ripple animation made RenderThread the top CPU thread (~7%).

- Lock-screen streaming (VirtualDeviceManager, ported from upstream d760a40; unverified on the S24): `CarInputServiceImpl.createDisplay` first tries a virtual device (`shizuku/VirtualDeviceSession`): shell associates its own package with the car's Bluetooth address (`BtSessionGate.lastPeerAddress`, set on every outbound / inbound iAP2 connect) under the COMPANION_DEVICE_APP_STREAMING profile (`cmd companiondevice associate <user> com.android.shell <mac> <profile> false`), holds that role (`cmd role add-role-holder`), creates a device with the unlocked lock state, and creates the displays on it (flag candidates in `VirtualDeviceRoute`: trusted displays PUBLIC first; the dock trusted so it joins the device group, because upstream saw a device display outside the group freeze while locked). Any failure falls back to the legacy flags for the rest of the session. The device closes after detach, deferred while the parked navigation map is still on it (`closeWhenEmpty`); a new car address closes the old one. An association created by the session is removed on close; an existing one is reused. Upstream also added a 3 s display-power watchdog (`requestDisplayPower(ON)`) for the Honor ROM; not ported, add it if the S24 still blanks. The S24 prints role holders separated by semicolons. Virtual device displays request their own 60 Hz (`VirtualDisplayConfig.setRequestedRefreshRate`): suspected cause of the lower frame rate while locked is the virtual displays following the phone panel vsync into AOD (unconfirmed; `COMPOSER: input fps ...` lines every 30 s show each input display rate to check it). While locked with the virtual device, `dumpsys power` still reports Awake because the device display group stays awake; the default group is dozing (`dream:doze` wake lock).

## 17. Remote debug bridge

Design in `docs/REMOTE-DEBUG-DESIGN.md`. Server lives in the sibling repo `../cardeck-bridge` (MIT, Node/TS, WebSocket + MCP in one process; `.npmrc` pins the public registry).

- App coupling is limited to `DebugFileLog.tap` (log sequence + hook) and `BridgeCommands` (whitelisted commands calling public `SessionController` APIs). Extend the whitelist only in `BridgeCommands` with review; never arbitrary shell.
- The `.rd` package needs its own Shizuku and notification grants, and the overlay permission (Honor silently blocks install-confirm intents from background receivers unless SYSTEM_ALERT_WINDOW; foreground fallback InstallReceiver -> BridgeSetupActivity).
- Call `SessionController.disconnect` before reinstalling (frees virtual displays).
- Remote variant disables auto-dim (`ScreenDimmer.autoDimAllowed=false`); keep-awake and manual dim remain. Manifest enables `usesCleartextTraffic`.
- Delta updates (72-97% traffic saved): all header u64 values are big-endian (device `readLong`; LE plus a short header buffer caused silent fallback to full); any device-side check failure falls back to the full APK.
- Pitfalls (2026-10-21):
  1. Self-launched components must be `applicationId/absoluteClassName`; relative names expand against the applicationId and do not exist in `.rd` builds (black car screen, 0 fps).
  2. During a session the process network is bound to the car hotspot (no internet). `InternetRoute` (remote) tracks cellular and opens sockets/DNS on it (`Network.socketFactory`, `getAllByName`), but prefers the default network when it is VALIDATED.
  3. Dead WebSocket connections without FIN: 25 s ping + 75 s soTimeout.
  4. Handshake is device-driven: device sends hello (with pairing code) first, server answers welcome.
  5. The bridge's stdin must be a FIFO, not `/dev/null`, or MCP over stdio receives nothing (`~/bin/cardeck-debug-up.sh`).
- Honor PackageInstaller: `createSession` then `openSession` (session 0 throws); status receiver needs an intent-filter in the manifest (else broadcasts never arrive, sessions stay Active; `abandonStaleSessions` at startup); same versionCode installs report success without changing anything, so pushes must bump the version; a byte-identical APK installed via `adb install -r` also does not count. Use `DarkMessageDialog` for confirmations (overlays are ignored).
- During a session, `am start` without `--display 0` lands on the focused virtual display; launch phone-side activities with `--display 0`.

## 18. Performance rules

- Pause phone-UI animations while covered (section 16).
- Periodic I-frame 30 s; keyframes on demand.
- Logging is asynchronous (`DebugFileLog`, single writer thread + queue). No synchronous file I/O on video, touch or audio threads; no per-frame logs.
- Composer is event driven, reuses buffers, idles at 1 fps after 1 s.
- Video packing is single pass, in-place encryption, buffered socket writes.
- Media polling only during sessions.
- Shell calls never on the main thread; cold app scans in the background; icon cache 144 px.

## 19. Known open issues

- Cadillac CT5: SETUP returns 500 after successful link renegotiation.
- Real-car verification pending: random initial sequence (Ford), renegotiation, bitrate/CBR/QP/High profile (VW), HEVC, idle 1 fps downshift and 30 s I-frames, microphone uplink, wireless adapter mode, GM 0x4300 format, Audi MIB3 Bluetooth SYN|ACK frame order change (single SYN|ACK instead of ACK + SYN|ACK).
- Samsung audio: new REMOTE_SUBMIX source needs WinPlay end-to-end verification.
- Real sleep (power button) can still freeze the car picture; physical-touch wake with the panel off is verified only on Honor.
- Whether MIB3 strictly requires the Wi-Fi tunnel is uncertain (real iPhone negotiated iAPChannel = 0 on Audi 3315).
- First connection pays a dialog (~1.7 s) and a possible MFi 20 B silent wait (6 s) until the length memory exists.

## 20. Documentation conventions

- Record findings in this file as they happen: measured performance results, pitfalls, design decisions, known issues, abandoned approaches. Place them in the matching topic section, not as a dated log.
- Keep one-off debugging details in code comments. Put here only what a future session would otherwise re-discover or break again.
- When a rule replaces an older one, update the existing bullet and note briefly what it replaced.
- Protocol deep dives belong in `docs/PROTOCOL-NOTES.md`; feature docs: `docs/AUTO-CONNECT.md`, `docs/WIFI-JOIN.md`, `docs/PHONE-SCREEN-OFF.md`, `docs/HID-KNOB.md`, `docs/MIC-RESEARCH.md`, `docs/DASHBOARD-UX.md`, `docs/REMOTE-DEBUG-DESIGN.md`.
- Docs must match the code. Build instructions that were not actually run must not be described as verified.
