# Dashboard UX notes

> A change log of the car dashboard (split map / media view), newest first, kept from the CarDeck 0.1.x era. Several
> early rules were replaced later; the current rules are in AGENTS.md section 12 ("Dashboard layout"), which wins where
> they differ. Notably: the fixed 3:1 split is now an adaptive ratio from the pane shape
> (`VirtualLauncher.dashSplitFractionFor`), the "back to playback controls" row became a GL floating button, and
> paging between dashboard and apps follows the 0.1.35 rule.

## 0.1.42 Split-view interaction, default map, battery and voice assistant

- The split map card can be used directly (pan, zoom and taps are injected into the map display) instead of "tap the
  map card -> fullscreen". Gestures that start inside the map card do not trigger the dashboard swipe; to page to the
  apps, swipe from the media card or empty space.
- Default map = the last used map: a successful `switchMap` writes the default; the settings entry "switch current map
  (keep default)" was removed.
- The dock battery is a CarPlay / iOS 16 style battery glyph (`ui/BatteryGlyphView`): the number is cut out inside the
  battery body, green with a bolt while charging, red at <= 20%.
- Page dots are drawn by the compositor above the map / media cards (GridActivity hands a snapshot of the dots to
  `DualDisplayComposer.pageDots`), decided by `VirtualLauncher.pageDotsOnTop()`.
- Voice assistant on the car (experimental, default off): see AGENTS.md.
- Running-app icons in the dock: when the cache misses (launch entry is an activity-alias, as in Amap) it falls back to
  the same-package cache -> system component icon -> application icon; after the desktop scan replaces the table, the
  dock is told to redraw.

## 0.1.39 Connecting goes straight to split view

- A new connection first sets split view as home, then starts the dock and desktop; the default map launches in the
  background, and returning to split view keeps a map that was switched.
- Android ICU rejects an unescaped `}` in the task-parsing regexes, which broke task operations of map, media and
  background management. The regexes use explicit hex escapes; a query or parse error must never block launching an
  app.

## 0.1.38 Map and media regression fixes

- Desktop, dock and split-view touch all accept a lift report with zeroed fields. An empty slot does not affect another
  gesture.
- Opening a map from the desktop switches the view only after it succeeded; returning to split view keeps the current
  map. Usable car app tasks move into the matching map / media area; tasks of the phone's main screen are not moved.
- Leaving the dock's background apps page closes it, and only one management window exists at a time.

## 0.1.37 Dock background apps and map search

- The background apps entry lives in the dock. Only apps opened successfully during this connection are listed; the
  list is cleared on disconnect and does not depend on a system task scan. Closing a window still checks the car tasks,
  so a phone window is never closed.
- "Switch current map" and "choose another installed app" use the same search field, icon rows and single-choice mark;
  the former does not change the default.

## 0.1.36 Background app management

- The apps page has a fixed "background apps" entry; long-pressing dock Home opens it too. Tap an app to switch, close
  one or all; back returns to the apps page.
- The source is the real car task list, parsing Samsung `Display #N` and Honor `Display: mDisplayId=N`; the phone's
  main display, the dock and all DroidCar affinities are excluded.
- Closing removes car windows only, without force-stopping the package; music services may keep playing. Task IDs are
  re-read before and verified after; failures show a retry message.
- Shell queries, close commands and APK icon / label loading all run in the background; the in-memory `runningApps` is
  never taken as the real process state.

## 0.1.35 Paging order (current rule)

Normal page order is dashboard (0) -> app pages (1+). The dashboard only swipes left to go forward; the first app page
swipes right back to the dashboard through the native pager. Boundary interception and forced `onPageSelected` returns
were removed; `dashboardUserDragging` separates user drags from automatic animations, and a user drag must never block
MOVE/UP injection. The GL preview runs during the drag, the mode is committed at IDLE, and a cancelled drag keeps the
mode. This overrides the one-direction and two-direction rules below.

0.1.34: a real-car log showed one right swipe that triggered and several left swipes; the dashboard then returned to
the apps page on either horizontal swipe, and tracking no longer depended on injected pressed state or map taps.

0.1.33: the whole dashboard (including an embedded media app) could swipe right back to the apps page; a MOVE past the
distance triggered it, gesture ownership was kept across areas, remaining events were swallowed after the trigger, and a
lift at 0,0 was supported.

0.1.32: media apps got the full area height; "back to playback controls" became a floating arrow at the bottom right.
A right swipe on the map card or playback controls returned to the apps page with the same page animation as Home;
swipes inside media apps kept working.

## 0.1.32 Display fixes

- The Home page animation handles only the Home switch; syncing the desktop position when opening an app / fullscreen
  map no longer rewrites the display layer.
- An app already running in the media area, tapped again from the desktop or dock, returns to the media area (a
  singleTask app reusing its old display used to switch to a blank screen).
- The session orientation compatibility override is applied before a map first opens, avoiding black bars from fixed
  portrait; it is undone on disconnect.
- On a real car, Amap was FIXED_ORIENTATION before the override; after applying it and restarting the map that
  letterbox reason was gone.

## Dashboard, language and default map (0.1.30 era)

### Interaction (since replaced, see the note at the top)

- The dashboard used a fixed 3:1 ratio: map left and media right on landscape cars, map on top and media below on
  portrait cars; card margins and the centre gap were not counted.
- No drag adjustment; the old `dashSplitFraction` preference is no longer applied.
- Tapping the map card opened the fullscreen map; the map on the dashboard was an entry point and touches did not reach
  the map app (replaced in 0.1.42 by direct control).
- Tapping the media card or cover opens the current player inside the media area, without leaving split view or going
  fullscreen. With no media session it uses the previously chosen app, otherwise tries Spotify, YouTube Music, Apple
  Music; if none is available it offers an installed-app picker.
- Previous, play / pause and next each handle their own taps and never trigger fullscreen.
- The "back to playback controls" row at the top of the media app closed the app and restored the control card. Dock
  Home switches dashboard / apps page; Home inside a fullscreen app / map returns to the dashboard.

### Language

Settings -> Display offers follow system, Traditional Chinese, Simplified Chinese and English. On Android 13+ the
system's per-app language setting works too. A language change recreates the UI through AppCompat; the connection is
held by the process-wide `SessionController`.

The UI, media states, menus, connection hints and common dialogs use Android string resources; diagnostic logs are in
English (converted 2026-11-21); third-party apps decide their own language. When adding a language, update
`values/strings.xml`, `values-en/strings.xml`, `values-b+zh+Hant/strings.xml`, `xml/locales_config.xml` and the
language list in settings.

### Maps

The default list added Google Maps, Google Maps Go, Waze, HERE WeGo, OsmAnd, Organic Maps, MAPS.ME, Sygic and TomTom,
next to Amap, Baidu and Tencent. "Choose another installed app" can make any app with a launch entry the default map.
A manually chosen package is routed to the map virtual display; after it is uninstalled the default priority list picks
an available map. Being selectable does not mean every app was verified in a car; whether a third-party app supports
landscape, window resizing and virtual displays is up to that app.

### Animation and coordinates

`LayoutMotion` provides a 320 ms smoothstep curve. The GL compositor updates rectangles and corners by real time and
composes at the target frame rate during the animation; reversing midway continues from the current progress. The map
interpolates between fullscreen and card; music apps use the separate CarPlayMedia virtual display, sized like the
media card; display creation, drawing and touch share the same integer rectangle. This is a compositor transition; a
third-party app's cold start or configuration recreation can still add waiting time. Map display resizes run on a
background executor and the consumer SurfaceTexture buffer is updated with them. Touch injection pauses during the
transition so static coordinates never tap moving content.

### Build and verification

Use JDK 17 or 21 and Android SDK Platform 34 or later. Set `ANDROID_HOME` or create `local.properties` in the project
root:

```properties
sdk.dir=D:/Android/Sdk
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleStandardDebug
.\gradlew.bat testStandardDebugUnitTest :app:lintStandardDebug
.\gradlew.bat spotlessCheck
```

The APK is at `app/build/outputs/apk/standard/debug/app-standard-debug.apk`. Release builds use
`assembleStandardRelease` with the existing signing setup; a debug-signed build cannot install over a release-signed
one.

Car checks:

1. Reopen the phone settings and car desktop in Traditional Chinese, English and the system language.
2. Pick Google Maps / Waze / a custom map and confirm split view and fullscreen show the same navigation task.
3. Expand from the map card and collapse with Home; tap quickly and reverse midway and check nothing is misplaced or
   mis-tapped.
4. Play / pause Spotify once each; without cover art, tapping the media card must still open the player.
5. Tap each media control and confirm none opens the player.
6. Test landscape and portrait cars, 100% and other resolution scales, and check buttons and coordinates.

## 2026-11-02 update

- "Manage desktop apps" got search by name / package name, with case and full-width normalisation and several keywords;
  searching does not change existing checks.
- "Choose another map" uses the same dark rounded list rows as the other options, with icons and arrows; the picker is
  always dark.
- After opening another map app from the desktop or dock, returning to the dashboard keeps that choice instead of going
  back to the default map.
- While connected, Settings -> Default map gained "switch current map (keep default)", switching the app on the same
  map display without reconnecting; the persisted default and the session choice are separate (removed again in
  0.1.42).
- Settings -> Display "swap map and music positions" applies at once and is saved. It only swaps positions and keeps
  the ratio; dragging did not come back.
- Self-drawn media control buttons grew to 56 dp with less icon padding and space. Third-party music apps decide their
  own layout.

### WinPlay sound diagnosis

The phone log showed WinPlay's audio SETUP answered 200 and data flowing, but the PCM samples were all zero. Restoring
the phone volume still gave zeros, so the phone being muted was not the whole story.

This version first tries capturing system output (REMOTE_SUBMIX) through the authorised Shizuku UserService and pipes
the PCM into the existing AAC/PCM encoding and AirPlay sending; the microphone is never used. If system capture cannot
start, it falls back to the original MediaProjection playback capture. Platform references:
[Android REMOTE_SUBMIX](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#REMOTE_SUBMIX),
[scrcpy audio source notes](https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md#source).

Zeroing the whole STREAM_MUSIC volume by default is avoided. The explicit mute option stays in Settings -> Display,
applies only to the playback-capture fallback and is off by default; some phones may still play out loud.

Device probe: at first both captures were all zero; after skipping to the next track in Spotify, system output capture
got 114,688 samples, 97,989 non-zero, peak 8,628, while playback capture stayed zero. The new source is verified to get
Spotify's sound; the final playback of the new APK on WinPlay over AirPlay still needs an end-to-end test. If a newly
created system capture is still all zero, skipping a track rebuilds the source track.

New checks: in both position layouts the media app must be usable, map taps expand correctly and the dock is
unaffected; after switching Amap / Google Maps, returning to the dashboard keeps the choice; no CarPlayMedia display or
system audio recording may remain after disconnect.

2026-11-02 ratio tweak: map / music fixed at 3:1 (music shrank from about 33% to 25%, card gap not counted); swapping
positions keeps each share. Media display size, GL rectangles and touch areas use the same ratio; a reconnect applies
the new virtual display sizes. (Replaced later by the adaptive ratio.)

## 0.1.31 ANR, navigation and touch fixes

Two ANR stacks from the device's Dropbox (01:15:47 and 01:16:56, 0.1.30) pointed at the same problem: the main thread
read other APKs' labels / icons in `GridActivity.onResume -> reload -> VirtualLauncher.scanLaunchableApps`, and APK
resource loading took more than 10 s under memory pressure.

Fix: cold scans moved to a background job with one shared cache and serialised cold scans; the icon cache is
uniformly 144 px bitmaps instead of keeping many original adaptive icons. The media app picker also reads its list in
the background, with a loading indicator, without blocking the home page.

- Media apps got a "back to playback controls" row at the top, not covered by the GL media layer (later replaced by the
  floating button).
- Dock Home always switches apps page / dashboard with the native ViewPager2 horizontal animation. Map, media app and
  control card share the page scroll progress; when leaving, layers hide only after the slide reaches its end, and list
  rebinding waits until the animation ends.
- The dashboard did not allow swiping to the apps page, and the first app page could not swipe past its edge to the
  dashboard (replaced by the 0.1.35 rule). Paging inside the apps pages still works.
- The old TouchRouter split "left / top is always the map" was removed; dashboard taps are judged only by the real
  `PaneBounds`, and direct map injection is only for fullscreen. Tests cover landscape / portrait and both swapped
  positions.
- Car notification banners: enabled in the phone home page -> Quick settings, default off, need the existing
  notification access. They show the app, title and text for 5 s; tap opens the app, the top-right closes. Own,
  ongoing, group-summary and media notifications are filtered; no message preview while locked. Content is never
  logged or stored.

Verification: new tests for touch areas, page boundaries, animation layer progress and notification filtering; Android
build, unit tests and Lint. Continuous animation on the WinPlay desktop could not be checked by the tools, so it still
needs the user's test.
