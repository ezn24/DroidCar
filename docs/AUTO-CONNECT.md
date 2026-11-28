# Auto-connect on entering the car (woken by the car's Bluetooth ACL broadcast)

> 2026-10-23. Goal: phone in the pocket, open the car door -> the car's Bluetooth connects -> DroidCar is started and
> begins a CarPlay session by itself. Code: `bt/CarBluetoothReceiver` + `CarPlayService.ACTION_AUTO_START_SESSION`.
> The same switch now also starts the experimental protocols (CarLife, ICCOA, Android Auto); the first to connect wins
> (`docs/MULTI-PROTOCOL.md`).

## 1. Broadcast: why a manifest receiver can start a killed process

`BluetoothDevice.ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` are on the
[implicit broadcast exceptions list](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
(Android 8+ restricts implicit broadcasts to manifest receivers; these two are exempt). So whenever a device connects,
the system delivers

```
<receiver android:name=".bt.CarBluetoothReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
        <action android:name="android.bluetooth.device.action.ACL_DISCONNECTED" />
    </intent-filter>
</receiver>
```

into a **cold-started process**, without any foreground Activity.

## 2. Foreground service: the exemption does not come from Bluetooth

**Android 12+'s list of background FGS start exemptions has no "Bluetooth broadcast" entry** (official list:
[Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).
Calling `startForegroundService` straight from the ACL broadcast is stopped with
`ForegroundServiceStartNotAllowedException`, **unless** the app meets one of the listed conditions. This project meets:

> **Your app holds the SYSTEM_ALERT_WINDOW permission.**

The dim cover (`ScreenDimmer`) and the touch probe already need "display over other apps", so the exemption comes for
free (targetSdk 34 only requires the permission to be held; Android 15+ additionally requires a **visible** overlay at
that moment, which does not apply at targetSdk 34).

Consequences:

- Without the overlay permission, auto-start fails -> `CarBluetoothReceiver` catches the exception and logs it (opening
  the app and connecting once by hand works, since that time there is a visible Activity).
- The FGS type must be `connectedDevice` (do not use the two-argument `startForeground` that also adds
  `mediaProjection`: Android 14 checks the PROJECT_MEDIA app-op for that and crashes).
  `CarPlayService.onStartCommand` always uses `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`.

## 3. Target filter (so earphones and watches do not trigger it)

`CarBluetoothReceiver.isCarDevice`:

1. The user picked a **target head unit** under "Bluetooth pairing" (`TargetDevicePrefs`) -> only that MAC counts.
2. None picked -> fall back to keywords in the Bluetooth name (BMW / MINI / AUDI / PORSCHE / MERCEDES / BENZ / VOLVO /
   CARPLAY).

The ACL broadcast carries only the `BluetoothDevice` (`address` and `name` both need `BLUETOOTH_CONNECT`; without it the
receiver gives up, since it cannot tell whether it is a car).

## 3.1 Switch

**Settings -> Display -> "Auto-connect on entering the car" (on / off, default on)**, stored in
`AutoConnectPrefs.enabled` (`settings` prefs, key `auto_connect_on_bt`). The receiver reads it on every ACL broadcast
(the process may have just cold-started, with no cache). When off, ACL broadcasts still arrive but return immediately
without starting any service.

## 4. Sequence

```
car Bluetooth ACL connects
  -> CarBluetoothReceiver (manifest, the process can be cold-started)
  -> startForegroundService(ACTION_AUTO_START_SESSION)
  -> CarPlayService.onStartCommand: startForeground(connectedDevice) within 5 s + wake lock
  -> SessionController.ensure(applicationContext)   // in a cold process, create the session objects first!
  -> take MediaProjection silently (see 4.1) -> audioEnabledForSession = whether that worked
  -> SessionController.startConnection(applicationContext)  // same path as "Connect" on the home screen

car Bluetooth ACL disconnects
  -> ACTION_AUTO_STOP_SESSION (startService: allowed, the process is already in foreground-service state)
  -> SessionController.disconnect() (retire the session, release virtual displays, restore screen / timeout, stop
     the service)
```

While still connecting, an ACL loss gets a 60 s grace (`CarPlayService.AUTO_STOP_GRACE_MILLIS`); during streaming it
is ignored, because receivers release Bluetooth after the Wi-Fi handoff (AGENTS.md section 5).

## 4.1 Taking MediaProjection silently (the "sound" of auto-connect)

AudioPlaybackCapture needs a MediaProjection instance, and the token is **single use** (it expires when capture stops);
nobody is there to tap "Start now" during auto-connect. Two system gates and how they are passed
(`audio/SilentProjectionActivity`):

1. **Consent dialog** -> Shizuku runs `appops set <pkg> PROJECT_MEDIA allow`: SystemUI's
   `MediaProjectionPermissionActivity` sees the app-op and returns `RESULT_OK` without a dialog (the same approach
   Tasker documents).
2. **Background activity start (BAL)** -> Shizuku runs `am start -n <pkg>/<activity> --activity-no-animation`: shell
   holds `START_ACTIVITIES_FROM_BACKGROUND` and is not subject to app-side BAL limits (so the Activity must be
   `exported=true`: shell does not have `START_ANY_ACTIVITY`); on failure it falls back to an in-app `startActivity`
   (needs `SYSTEM_ALERT_WINDOW`; if missing, granted first with `appops`).

The page itself: 1x1, not touchable or focusable, no animation, `noHistory` + `excludeFromRecents` + `taskAffinity=""`;
`setShowWhenLocked/setTurnScreenOn` let the callback finish with the phone locked in a pocket.

One **Android 14 rule** for the order: after the grant the FGS type must first be **upgraded to include
`mediaProjection`**, only then may `getMediaProjection()` be called; the code reuses
`pendingProjectionResult -> upgradeToProjection() -> takeProjection()`. Also, when `CarPlayService.onStartCommand` calls
`startForeground` again while `mediaProjection != null`, it must keep that type, otherwise the type is dropped and the
system stops the projection.

**Side effect**: `appops set PROJECT_MEDIA allow` is **persistent** (appops.xml), so the app can project silently from
then on; to undo it: `adb shell appops set <pkg> PROJECT_MEDIA default`.

## 5. Known limits

1. **Sound depends on Shizuku**: the silent token needs `appops` + `am start`; without Shizuku, auto-connect still
   streams video (audio is given up without a dialog). If `RECORD_AUDIO` was never granted it first tries
   `appops set RECORD_AUDIO allow`.
2. **Shizuku must be running**: virtual displays and touch injection depend on shell permission; after a phone reboot
   with Shizuku not started, auto-connect is hard-blocked before Bluetooth (AGENTS.md section 8) instead of ending in
   "Bluetooth connected, car screen black".
3. **The first try may fail**: right after the head unit powers up its iAP2 window may not be ready; the Bluetooth client
   retries by itself. If it keeps failing, look at the `BT-C:` connection failure lines.
4. **Leaving the car**: `ACL_DISCONNECTED` -> `disconnect()`. If streaming, `onEnded` and `disconnect` both run (the
   `phoneScreenOff` flag makes the screen restore happen once).
5. Manual disconnect or cancel holds off auto-connect for 10 min (`SessionController.autoConnectSuppressedForMillis`),
   because closing RFCOMM makes the car drop and re-establish ACL.
6. Auto-start shows no UI; to see what happened, look at the notification or the log.

## 6. Troubleshooting

App log (`files/main_debug.log`):

```
AUTO: car Bluetooth connected <name> (<address>) - starting a session
AUTO: ignoring non-car Bluetooth <name> (...)
AUTO: auto-start blocked by the system (ForegroundServiceStartNotAllowedException: ...)
SVC: silent projection token ready (auto-connect with audio)
AUTO: car Bluetooth disconnected ... - ending the session
```

Command line:

```bash
# is the receiver registered (manifest receivers appear in the package dump)
adb shell dumpsys package io.github.ezn24.droidcar | grep -i -A3 CarBluetoothReceiver
# power-cycle the car's Bluetooth once and look for the AUTO: / SVC: lines
```

> **Do not** simulate with `am broadcast -a ...ACL_CONNECTED -n <pkg>/.bt.CarBluetoothReceiver`: `EXTRA_DEVICE` is a
> Parcelable that `am` cannot pass, so the receiver gets no device and returns, which looks like "it did nothing".
