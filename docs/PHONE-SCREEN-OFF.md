# Real phone screen-off + virtual displays that stay on

> 2026-10-23. Goal: phone dark in the pocket, the car side keeps running at 60 fps. Code:
> `shizuku/CarInputServiceImpl.setPhysicalScreenPower` + `SessionController.enterPhoneScreenOff`. Lock-screen
> streaming through VirtualDeviceManager (later) is described in AGENTS.md section 16.

## 1. Why not "press the power key"

The power key / `PowerManager.goToSleep` enters **keyguard + Doze**:

- the keyguard covers the virtual displays (the car shows the lock screen or black);
- the CPU throttles or suspends, dragging down the encoder and protocol threads.

scrcpy's `-S/--turn-screen-off` takes another route: **the system logically stays "screen on and unlocked"** and only
the **physical panel's power** is cut at the SurfaceFlinger level. DroidCar does the same.

## 2. Three key points

### 2.1 Virtual displays need their own display group

Trusted virtual displays (app / appHi / map) declare two extra @hide flags:

| Flag | Value | Effect | Extra permission |
|---|---|---|---|
| `OWN_DISPLAY_GROUP` | `1<<11 = 0x800` | the display gets its own DisplayGroup and does not turn OFF when the main display sleeps / loses power | `ADD_TRUSTED_DISPLAY` (trusted displays need it anyway) |
| `ALWAYS_UNLOCKED` | `1<<12 = 0x1000` | no keyguard on that display while locked | `ADD_ALWAYS_UNLOCKED_DISPLAY` (**some ROMs do not give it to shell**) |

`DisplayManagerService.createVirtualDisplayInternal` checks both explicitly and throws `SecurityException` without the
permission. So `createDisplay` falls back in **three levels**: `own group + ALWAYS_UNLOCKED` -> `own group` -> base
flags; every failed level is logged and the next is tried, so the display never fails as a whole.

Note that `PUBLIC` implicitly adds `AUTO_MIRROR`, and `AUTO_MIRROR` clears `OWN_DISPLAY_GROUP`; we also declare
`OWN_CONTENT_ONLY` (which clears AUTO_MIRROR), so the own display group survives.

**The dock display needs its own group too** (measured 2026-10-23): the dock is an **untrusted** display and used to
get base flags only, so it sat in the default display group; when the physical panel lost power the whole group went
dark, seen on the car as "app display fine, dock strip black, back after unlocking". The fix gives the dock
`OWN_DISPLAY_GROUP` as well (with fallback): AOSP clears the flag only with `AUTO_MIRROR`, which is set only together
with `PUBLIC`, and the dock does not declare `PUBLIC`, so the flag stays; `OWN_DISPLAY_GROUP` only needs
`ADD_TRUSTED_DISPLAY` and does not change the dock's "untrusted" meaning (third-party tasks still cannot go there).
The dock later also got `ALWAYS_UNLOCKED` (AGENTS.md section 12).

### 2.2 Cutting the physical panel: `SurfaceControl.setDisplayPowerMode`

Call `setDisplayPowerMode(token, 0)` on the **physical main display token** (0 = OFF, 2 = NORMAL). The framework still
thinks the screen is on -> no lock, no Doze.

**How to get the token changed in Android 14** (the easiest trap):

- SDK <= 33: `SurfaceControl.getInternalDisplayToken()`, fallback `getPhysicalDisplayIds()` +
  `getPhysicalDisplayToken(id)`;
- SDK >= 34: those methods **were removed from SurfaceControl**; only `com.android.server.display.DisplayControl`
  remains. It lives in `services.jar`, invisible to the normal class loader, so a class loader has to be built with
  `ClassLoaderFactory.createClassLoader(SYSTEMSERVERCLASSPATH, ...)` and the JNI methods registered with
  `Runtime.loadLibrary0(cls, "android_servers")` (the scrcpy approach). On Honor (Android 17)
  `SurfaceControl.getInternalDisplayToken` is still the one that works, so all three are tried.

### 2.3 Staying awake and the screen timeout

- `CarPlayService` already holds a `PARTIAL_WAKE_LOCK` + Wi-Fi low-latency lock during a session; nothing new needed.
- `ScreenDimmer` already raises `screen_off_timeout` to the maximum during a session and restores it afterwards, so the
  framework does not turn the screen off by itself (a group state change would light the panel again).

## 3. Sequence

```
streaming starts (virtual displays exist)
  -> "Real screen off while connected" off: keep only the dim cover, skip the rest
  -> phone asleep: input keyevent KEYCODE_WAKEUP -> wait 400 ms -> wm dismiss-keyguard (best effort)
  -> setPhysicalScreenPower(false)        // panel power off
  -> phoneScreenOff = true

car-side disconnect (VideoSender.onEnded)
  -> restorePhoneScreen(sleep=true)       // keep the panel dark, send KEYCODE_SLEEP
user disconnect / session retired (retireSession)
  -> restorePhoneScreen(sleep=false)      // light the panel (the person is at the phone)
```

## 3.1 Setting

**Settings -> Display -> "Real screen off while connected" (on / off, default off, user request 2026-10-24)**, stored
in `DisplayPrefs.screenOffOnConnect` (`settings` prefs, key `screen_off_on_connect`).

### 3.2 Touch detection is carried by the dim cover (settled by user measurement 2026-10-24)

With the panel powered off, **the touch IC keeps working** (Honor Magic7: tapping a settings button on the dark screen
reached the UI and lit it programmatically). So the automatic path does not just cut power; it reuses the dim cover's
manual fake screen-off (`ScreenDimmer.enterDimNow`): show the cover -> cut panel power -> **tapping the cover wakes it**
(onUserInteraction -> power restored + cover removed). The cover is also the **pocket touch shield**: without it,
touches in the pocket would hit the foreground app (such as "Disconnect"). The power key still triggers a real sleep
(keyguard / surface stripping is unreliable, the car picture froze in a 2026-10-24 test); the right way is to tap the
cover, not press power. Without the cover (no overlay permission) it degrades to a bare power cut, with no touch wake-up:
only the power key brings it back.

- When off: the session no longer cuts panel power, only the usual 45 s dim cover + stay-awake remain.
- **Changes apply at once** (`SessionController.applyScreenOffPreference`): even with the panel dark, it can be turned
  off in the settings panel **on the car screen** and the phone lights up immediately, without waiting for the next
  connection.
- With no session, toggling does nothing.

## 4. Known limits (inherent costs of the mechanism, not bugs)

1. **The power key needs two presses**: the framework believes the screen is on, so the first press "turns it off" and
   the second turns it on. Same as scrcpy; no risk of bricking.
2. **A secure lock (PIN / pattern) cannot be dismissed**: `wm dismiss-keyguard` does nothing for secure locks. The
   virtual displays are kept free of the keyguard by `ALWAYS_UNLOCKED`, but opening apps / answering calls on the
   phone still needs a manual unlock.
3. **App-side fallback displays (EMUI and similar) are not guaranteed**: they have no own display group, so whether
   they keep producing frames after screen-off depends on the ROM; the log shows the physical screen as powered off but
   the car picture may stop. Turn the feature off on such ROMs.
4. **Process crash**: the UserService is a long-lived shell process and its power state does not vanish with the app
   process. Three recovery layers: restore at session end, restore before the stale service exits, restore once at the
   next app start.
5. **Some ROMs do not give shell `ADD_ALWAYS_UNLOCKED_DISPLAY`**: the shell log shows the `ALWAYS_UNLOCKED` level
   failing and falling back to the own group; the feature still works, but the lock screen may appear on the virtual
   displays while locked.

## 5. Troubleshooting

The shell process log is `/data/local/tmp/carinput_svc.log`:

```
createDisplay(CarPlayApp) <label> flags=0x...      (one line per level tried)
DisplayControl id=...
setPhysicalScreenPower(false) ok=true
setPhysicalScreenPower(true) ok=true
```

App side (`files/main_debug.log`):

```
SCREEN: real screen-off entered (dim cover carries touch detection - tap the cover to wake, no power key needed)
SCREEN: physical screen power-off failed (ROM gave the shell no permission / display token unavailable)
SCREEN: head-unit side disconnected - phone put to sleep (panel kept dark)
SCREEN: physical screen restored to lit ok=true
```

## 6. How to roll back

1. **As a user**: Settings -> Display -> "Real screen off while connected" -> off (applies at once, only the dim cover
   remains).
2. To roll back the flags too: remove `FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED` (the first level) in
   `CarInputServiceImpl.createDisplay`; the fallback chain then uses the base flags.
