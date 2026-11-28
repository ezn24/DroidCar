# Joining the head-unit hotspot: silent command-line join + getting a handle without a specifier

> 2026-10-22. **Partly superseded**: the default path is now the app-scoped network (`WifiNetworkSpecifier`,
> `WifiCarPlayConnector.joinCarPlayNetwork`), visible only to this process, not saved, not the default network; the
> silent `cmd wifi connect-network` join described here is only the deep fallback after the specifier windows. Current
> rules and timing: AGENTS.md section 7. This file keeps why the command-line path works the way it does, why
> `bindProcessToNetwork` is still needed, and the framework / command pitfalls. Code:
> `SessionController.joinCarWifiSilently` and `net/WifiCarPlayConnector.bindCurrentWifi`.

## 1. Two join paths

| | Silent command-line path | App-scoped network (now the default) |
|---|---|---|
| Trigger | Shizuku `cmd wifi connect-network` | `WifiNetworkSpecifier` + `requestNetwork` |
| System dialog | **none** | a confirmation the first time for that AP |
| Network ownership | system-saved network (visible to everything) | this process only (`Ephemeral`) |
| Needs Shizuku | yes | no |
| Needs location permission | no | Android 12/12L (13+ uses NEARBY_WIFI_DEVICES) |

Both paths end with the same step: get a `Network` handle -> `bindProcessToNetwork` -> re-register the ctrl mDNS.

## 2. Why not use only `WifiNetworkSpecifier` (the reasoning in 2026-10)

It ties "make the system join Wi-Fi" to "return a Network to the app": once a request carries a specifier,
`WifiNetworkFactory` runs `isAccessPointApprovedForActiveRequest` before approving, and when every approval source
(CompanionDevice association, the internal approved-AP list, shell pre-approval) is false it **shows a system
confirmation**; no app API turns it off.

Two common misunderstandings:

- **The approval is persisted**: approved APs are stored per package in the wifi config store
  (`NetworkRequestStoreData`), so "the same hotspot" only asks the first time.
- **Shell pre-approval does not work**: `cmd wifi network-requests-set-user-approved <pkg> yes` adds the package to
  `mApprovedApp` (after which specifier requests pass without a dialog; but it is one global value, approving this app
  displaces others), yet the command **is not in `WifiShellCommand.NON_PRIVILEGED_COMMANDS`**, so uid 2000 gets
  `SecurityException: Uid 2000 does not have access to ...` (root only). Shizuku cannot use it.

Conclusion at the time: to stay at zero dialogs after a new car, reinstall or cleared data, the specifier had to be
avoided and the system made to join by itself. Later measurements (Honor, Redmi K40, DiPlay) showed the app-scoped
network costs no lasting dialog in practice and keeps the system's saved networks and default network untouched, so it
became the default.

## 3. Two practical pitfalls of the command-line path

### 3.1 `connect-network` exit 0 does not mean connected

`WifiShellCommand.connect-network` hands the config to `WifiService.connect(...)` and `return 0`s without even waiting
for `IActionListener`. So `cmd wifi status` **must** be polled until it shows `Wifi is connected to "<SSID>"`
(disconnect old network -> scan -> associate -> DHCP measured at 2-10 s, longer on 5 GHz). The wait is interruptible by
"cancel connection".

### 3.2 `removeCapability(NET_CAPABILITY_INTERNET)` is not required

`NetworkRequest.Builder()` defaults to `NOT_RESTRICTED / TRUSTED / NOT_VPN` only, without `INTERNET`; matching is by
subset, so declaring less only widens the match and never excludes networks without internet. Writing it has no side
effect and is kept for clarity. **What avoids the dialog is "no specifier".**

### 3.3 Check the SSID with `cmd wifi status`, not `WifiInfo`

`WifiInfo.ssid` in `NetworkCapabilities.transportInfo` returns `<unknown ssid>` without precise location permission (or
with location services off), and needs `FLAG_INCLUDE_LOCATION_INFO` and API branches; whether the head-unit hotspot is
the connected one is a single shell query anyway. `shellWifiStatus()` (one `cmd wifi status` + a regex) needs no
permission and rules out "the phone is still on the home router" before binding.

## 4. Why `bindProcessToNetwork` is still required

The intuition "just pass the `Network` to NsdManager / `socketFactory`" does not work here, for three reasons:

1. **mDNS advertising has no per-network API**: `NsdManager.registerService(NsdServiceInfo, int,
   RegistrationListener)` has no `Network` parameter (an `AdvertisingRequest` overload arrived only in API 36).
   Registration follows the **process default network**: without binding the process, `_carplay-ctrl._tcp` goes out
   over cellular, the head unit never discovers the phone, and neither `GET /ctrl-int/1/connect` nor 0x4301 arrive.
   (Discovery has a newer `discoverServices(..., NetworkRequest, ...)` overload; advertising does not.)
2. **Many plain sockets**: AirPlay control / event, video TCP, audio UDP, the timing server and the ctrl server are
   created with `Socket()` / `DatagramSocket()`. Car sockets are now also pinned with `Network.bindSocket`
   (`net/CarNetwork`, so a VPN does not swallow them), but the process binding stays.
3. **It affects only this process**: `bindProcessToNetwork` applies to the calling process's sockets (same UID), not
   the system. Navigation and music on the car's virtual displays are **separate processes** and keep using the
   default network (cellular). The only cost is that DroidCar's own internet requests (media cover URLs) also go over
   the car Wi-Fi during a session.

## 5. `cmd wifi set-wifi-watchdog-enabled false` does not exist

AOSP `WifiShellCommand` only has:

```
set-wifi-watchdog <true|false>     # controls WifiLastResortWatchdog (self-recovery after repeated join failures)
get-wifi-watchdog
```

Neither is in `NON_PRIVILEGED_COMMANDS`; shell (uid 2000) gets a SecurityException, only root can run them. And they
control "recover after failing to connect", not "drop networks without internet". Turning off "switch away when there
is no internet" means the OEM's smart network switch (Honor / Xiaomi / OPPO each have their own); there is no generic
shell switch. The project logs the reason and shows it on the hero card, and the user decides whether to use the manual
path.

## 6. Cost of a system-saved network (a design trade-off, not a bug)

- The hotspot becomes a system-saved network. The fallback always passes `-d` (`WifiConnectCommand.AUTOJOIN_DISABLED`)
  so it does not auto-join later, records the SSID in `wifi/appSavedSsids` the moment the command returns, and removes
  it again at session end. Networks the user saved themselves are never removed silently.
- The default network stays cellular: that Wi-Fi is not `VALIDATED`, so `Active default network` does not change and
  other apps keep using 4G/5G.
- Some ROMs show "this WLAN has no internet access" or even a "switch back to mobile data" button; that is vendor UI
  that shell cannot turn off.
- To undo by hand: "Forget" the hotspot in system WLAN settings, or find its netId with `cmd wifi list-networks` and run
  `cmd wifi forget-network <id>` (it accepts only the netId, never the SSID).

## 7. Log keywords for troubleshooting

```
WiFi: joined app-specific network "..."
WiFi: first window 5s with zero callbacks; specifier may be hung, resending the network request
WIFI: no callback in either specifier window - deep fallback connect-network to "..."
WIFI: connect-network failed (exit=...)
WIFI: switched to head-unit hotspot "..."
WIFI: phone is already on head-unit hotspot "..." ...
WiFi: bound current WiFi network ... bindProcessToNetwork=true
```
