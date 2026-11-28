# Head-unit HID input: steering-wheel media keys + iDrive knob (BMW iD8.5)

> 2026-10-23. Goal: skip / pause from the steering wheel (global media keys), iDrive knob rotation and four-way nudges
> (focus navigation on the virtual displays). Later knob work (knob focus on DroidCar's own screens, Home / Back,
> descriptor field decoding) is described in AGENTS.md, section "Touch and HID input".

## 1. Pipeline

```
head unit /info -> hidDevices[] (uuid + hidDescriptor)
  -> at session setup hidSetInputMode(mode=0) for EVERY uuid
  -> the head unit sends hidSendReport (event channel) / 0x6802 AccessoryHIDReport (iAP2 tunnel)
  -> routed by DEVICE DESCRIPTOR (never by uuid or head-unit model):
       Consumer page 0x0C       -> media key (AudioManager global dispatch, no focus needed)
       Digitizer page 0x0D      -> touch (TouchRouter coordinate injection)
       Generic Desktop 0x01     -> knob / four-way (DPAD injected into the virtual display)
```

**Do not switch to "guess the device by uuid / name"**: `uuid` is arbitrary per head unit (Audi sends `"0"/"1"`,
DiPlay integers); the descriptor is the only stable signal (`ConsumerKeys.parseDescriptor` /
`HidNavigation.isNavigationDevice`).

## 2. Media keys

`ConsumerKeys` parses the descriptor into its Usage array and decodes both HID semantics separately (Array: the byte is
an index; Variable: one bit per usage), then maps to `KEYCODE_MEDIA_*`, dispatched globally by
`SessionController.dispatchMediaKey` -> `AudioManager.dispatchMediaKeyEvent` (works wherever the focus is, so tracks
change even while the car screen shows navigation).

## 3. Knob / four-way

`input/HidNavigation` treats a device as navigation when its descriptor has the Generic Desktop page 0x01 with
**Wheel (0x38) or Hat (0x39)** and is not a Digitizer (touch screens excluded).

Report parsing (fallback for 2-4 byte reports when the descriptor is missing):

| Position | Meaning |
|---|---|
| Last byte | **Signed** knob steps (positive = clockwise -> DPAD_DOWN, negative -> DPAD_UP, at most 5 steps per frame) |
| Bytes before it | Button bitmap, fired on the **rising edge**: bit0 = confirm, 1 = up, 2 = down, 3 = left, 4 = right, 5 = back |
| Hat device (0x39, 1 byte) | Low 4 bits = 8-way hat (0 up / 2 right / 4 down / 6 left, anything else = released) |

Injection goes through `ICarInput.keyEvent(displayId, keyCode)` (the UserService's `injectInputEvent`,
`KeyEvent.setDisplayId` aimed at the virtual display, milliseconds); on failure it falls back to
`input -d <id> keyevent <code>`. Target display = `VirtualLauncher.activeAppDisplayId()` (desktop grid / the open app).

**Why DPAD cannot be dispatched globally**: DPAD needs the focus system, and a global dispatch lands on the phone's own
screen; only injecting into the car's virtual display makes the car desktop / apps respond.

## 4. Focus highlight (knob visibility)

- `res/drawable/focus_ring.xml`: a 3 dp stroke drawn only in `state_focused`;
- grid cells (`GridActivity.PageHolder.bind`): `isFocusable = true` + `foreground = focus_ring` (the foreground does
  not replace the background set in code);
- key events take the window out of touch mode by themselves (`focusableInTouchMode=false` is enough to get focus).

Limit at the time: the dock is a **separate display** and DPAD does not move focus across displays. AGENTS.md describes
the knob focus layer (`input/KnobFocus`) that later solved this for DroidCar's own screens.

## 5. Key calibration (needed in each car)

Report layout / bit order may differ between iD8.5 firmwares (`S18A-23-xx` vs `24-xx`), and keys may come over the
event channel instead of 0x6802. To calibrate:

1. Settings -> Display -> "**HID raw log**" -> on;
2. press steering-wheel next / previous / play-pause, turn the knob left and right, press it, nudge all four ways;
3. read `files/main_debug.log`:

```
HID-RAW: uuid=0 bytes=05 desc=27B
EVENT: head-unit media key next (uuid=0 usage=0xb5 -> keyCode=87)
HID-RAW: uuid=1 bytes=00 02 desc=34B
EVENT: knob/navigation key down+down (uuid=1)
```

4. "Next" logs `usage=0xb5` but nothing happens -> the problem is dispatch, not mapping; the logged bytes differ from
   what you expected -> adjust the `ConsumerKeys.keyUsage` table or the `HidNavigation` bit layout to the real bytes;
   no `HID-RAW` line at all -> the head unit sent nothing (usually `hidSetInputMode` did not reach that uuid, or the car
   sends its keys over iAP2 `0x6802`).

## 6. Quick troubleshooting

| Symptom | Check first |
|---|---|
| No log at all | Does `/info` list hidDevices? Was `hidSetInputMode` sent for every uuid? |
| HID-RAW but no reaction | Was the descriptor recognized as Consumer / navigation (`desc=` byte count + parse result in the log)? |
| Knob logs but focus does not move | Does the virtual display have focusable views (grid cells `isFocusable`)? Is the injected displayId right? |
| Steering-wheel skip does nothing | Is `dispatchMediaKey` called? Is there a current media session on the phone? |
