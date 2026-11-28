# Remote debug bridge (remoteDebug variant + cardeck-bridge)

> Designed and first shipped 2026-10-20 (CarDeck era; the bridge keeps its `cardeck-bridge` name). Purpose: remote
> troubleshooting with car owners. The developer's computer offers a debug service through a tunnel; the owner installs
> the **remoteDebug variant**, enters the address and a pairing code, and the developer (or an AI agent) watches the log
> live, sends whitelisted commands, pushes overlay hints and reinstalls new builds in one step.

## Topology

```
head unit <-> phone [remoteDebug variant .rd] <-WSS/WS-- tunnel -- developer computer
                                                         ├─ WS device side (:8787)
                                                         ├─ MCP agent side (stdio)
                                                         └─ GET /apk/<file> (reinstall downloads)
```

## Key decisions and why

1. **Build isolation with a product flavor, not a new module or repository**: dimension `bridge`, `standard`
   (daily / release, package unchanged, since Shizuku grants are per package) and `remote` (`applicationIdSuffix =
   ".rd"`, installs next to the daily build). All bridge code lives in `app/src/remote/` with
   `remoteDebugImplementation` dependencies, so **not one line reaches the standard build** (verified). The manifest
   overlay (`tools:replace="android:name"`) swaps the Application class for `BridgeApp` and declares
   `REQUEST_INSTALL_PACKAGES` plus the debug-only components.
2. **Only two coupling points with the app code**: `DebugFileLog.tap` (log sequence number + listener hook added in
   main, always null and free in standard) and `BridgeCommands` (calls the existing public APIs
   `SessionController.ensure/startConnection/disconnect/statusLine`). Main knows no bridge type.
3. **Hand-written WS client (~200 lines, an RFC 6455 subset)**: no okhttp, so no dependency reaches the daily build and
   the message shapes stay under our control (text frames / masking / ping-pong / server fragmentation). wss uses the
   system SSLSocketFactory (the tunnel provides a valid certificate); self-signed or bare-IP setups use ws://.
4. **Server in its own repository `cardeck-bridge` (MIT)**: one Node/TS process with two faces, the WS device side +
   the MCP (stdio) agent side + static APK downloads. A ring log of 5000 lines per device, de-duplicated by seq;
   one-time pairing-code handshake; a command whitelist (never arbitrary shell).
5. **Three safeguards for the reinstall loop**: a sha256 check refuses wrong packages; before installing,
   **`SessionController.disconnect` releases every virtual display** (otherwise the system install dialog lands on a
   car-side virtual display, the pitfall in AGENTS.md); a `resumePending` flag written before installing makes the new
   version's first start ask in an overlay "continue in debug mode?" and reconnect.

## Phone-side components (app/src/remote/java/.../bridge/)

| File | Role |
|---|---|
| `BridgeApp` | Entry: installs the tap, auto-reconnect / resume-after-reinstall prompt |
| `BridgePrefs` | Address / pairing code / device ID / auto-connect / resume flag (separate "bridge" prefs) |
| `WsClient` | Minimal RFC 6455 client (reader thread + serial writer + fragment reassembly) |
| `BridgeClient` | Session loop (exponential backoff), pairing, log backfill + 500 ms live batches, message dispatch |
| `BridgeCommands` | Command whitelist: start/stop_connection, get_state, ping |
| `BridgeOverlay` | Draggable overlay hint (TYPE_APPLICATION_OVERLAY, text + optional button) |
| `ReinstallFlow` | Download / verify / prompt / stop the session / PackageInstaller |
| `BridgeSetupActivity` | Second launcher entry: address + pairing code + status + permission guide |
| `InstallReceiver` | Install result: brings the system confirmation intent to the phone's own screen |

## Server (cardeck-bridge, sibling repository)

MCP tools: `bridge_status` / `logs_tail(device,lines)` / `logs_fetch(device,since)` / `cmd(device,name,args)` /
`notify(device,text)` / `offer_apk(device,filePath,version)`. Environment variables `PORT` / `PUBLIC_BASE` /
`BRIDGE_PAIRING`; `.npmrc` pins the public registry.

## Known costs and conventions

- `.rd` is a new package: Shizuku, notification access and projection permission must be **granted once more** in that
  variant (the settings page says so).
- The remote variant stays out of the main GitHub Release line (or is clearly marked experimental), so concerns about
  log exposure do not spread.
- Logs contain hotspot names / Bluetooth addresses; the README and the app tell users so.
- Extending the command whitelist goes through a single review point, `BridgeCommands`; never add arbitrary shell or
  reflection commands.

## Delta updates (shipped 2026-10-24, 72%-97% less traffic measured)

One test round with an owner pushes several builds, and a full 15 MB over cellular is expensive. Byte-level block deltas
(rsync style):

- **Server** (`src/deltadiff.ts`): historical APKs in `public/` are indexed as bases by sha256; the device's hello
  reports the sha256 of its installed APK (`sourceDir`); an offer with a matching base produces a COPY/LITERAL patch
  (FNV-1a 4 KB blocks + greedy extension), skipped when it would save less than 60% of the full size. Measured 15128 KB
  -> 440-4223 KB.
- **Device** (`bridge/DeltaPatch.kt`): downloads the patch -> assembles it with its own `sourceDir` as the base ->
  strict sha256 check of the result -> install flow. Any failure falls back to the full download unconditionally
  (the delta is only an optimisation).
- **Protocol contract (both sides, never change one alone)**: 88-byte header = magic(8) + targetLen(8) +
  targetSha(32) + baseLen(8) + baseSha(32); **every u64 is big-endian** (the device's Kotlin `readLong` reads only
  big-endian; the first version used LE plus a 72 < 88 header buffer that cut baseSha, so the first delta silently fell
  back to the full download until a phone capture showed it).

## Three log channels (2026-10-24)

In-memory ring (live tail, routine high-frequency lines muted) -> on-disk JSONL (`logs_fetch`, everything, across
process generations, 20 MB rotation) -> device file log (`logs_file`, the complete source of truth including RAW
dumps, returned gzipped in one message and stored in `cardeck-bridge/logs/`). Use the last two for past problems (the
stream setup before an install).

## Install confirmation safeguards and the overlay exemption (settled 2026-10-24)

- A PENDING_USER_ACTION system confirmation intent started straight from a background receiver is **silently blocked**
  on Honor (no exception, no UI, no failure result); the exemption is the **overlay permission
  (SYSTEM_ALERT_WINDOW)**. The `.rd` package must get "display over other apps" on first use: install confirmation,
  disconnect overlay hints and the reproduction guide all depend on it.
- Three safeguards (`InstallReceiver` + `BridgeSetupActivity`): (1) start the confirmation intent directly (works within
  the foreground grace period); (2) open the debug setup page and **forward from a foreground Activity** (always
  works); (3) the overlay exemption as a fallback. Even so, a pushed install still needs the owner to be looking at the
  phone at that moment.
- Delta details are shown throughout: the receive log "delta X KB" and the confirmation dialog "delta X KB · full
  Y MB" (that text appears only when the delta really succeeded; a silent fallback shows only the full size).

## Build commands (flavored)

```bash
./gradlew assembleStandardDebug    # daily build (same as the old assembleDebug)
./gradlew assembleRemoteDebug      # remote debug build (.../remote/debug/app-remote-debug.apk)
./gradlew testStandardDebugUnitTest
./gradlew :app:lintStandardDebug   # the pre-commit hook runs the same
```
