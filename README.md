# Volume++

Volume++ is a native Android app that replaces the stock system volume panel with a fully
custom, themeable one, and adds per-app volume mixing that Android doesn't normally expose to
the user.

## What it does

Android's real volume panel is a single, fixed UI tied to whatever version of Android you're
running, and it only lets you control per-stream volume (media / ring / alarm / notification) —
never individual apps. Volume++ replaces that panel outright and fills in both gaps:

- **A custom volume panel that intercepts the hardware volume keys.** An Accessibility Service
  captures volume-up/volume-down presses before the system does, suppresses the stock panel, and
  draws its own floating overlay instead — with the same press-and-hold ramping behavior as the
  real thing.
- **Faithful recreations of every major Android volume-panel design, Android 7 through 15.**
  The overlay isn't one fixed look — it can render the classic Android 7/8 top sheet, the
  Android 9–11 collapsed-edge-control-plus-"Sound"-sheet layout, Material You's Android 12 panel,
  and the Android 13/14/15 "Sound & vibration" sheet (including Android 15's redesigned
  "Audio will play on" media-output picker). You pick which version's panel you want, independent
  of the Android version actually installed on the device.
- **A live, on-screen WYSIWYG editor** for repositioning every panel component (main panel,
  expanded sheet, output picker) per orientation by dragging it around the screen, and a color
  editor that lets you tap any element of the panel — background, slider fill/track, icons,
  accent, text, buttons — to recolor it, independently for each Android-version skin.
- **Per-app volume mixing**, via [Shizuku](https://github.com/RikkaApps/Shizuku): the panel can
  show one volume slider per currently-playing app (not just per stream) and set that app's
  actual playback volume using a hidden system API, plus toggle whether an app is allowed to take
  audio focus at all — i.e. whether it's allowed to pause everything else when it starts playing,
  which is what makes true audio mixing between two apps possible.
- Per-app volume choices are remembered for the life of a playback session and silently
  re-applied as the app's audio players churn (e.g. across screen off/on or backgrounding), so a
  level you set doesn't reset back to full the next time the app makes a sound — it only resets
  once the app's process is actually gone.

## How it works

- **`VolumeKeyService`** — an `AccessibilityService` that grabs `KEYCODE_VOLUME_UP` /
  `KEYCODE_VOLUME_DOWN` and drives the overlay instead of letting them reach the system panel.
- **`OverlayController`** — builds and animates the floating panel window
  (`SYSTEM_ALERT_WINDOW`) for whichever Android-version skin is selected, including the live
  position/color editor modes.
- **`ShizukuManager` / `UserService`** — Shizuku lets the app spawn a privileged, shell-UID
  helper process without requiring root. That process can run `appops set <pkg> TAKE_AUDIO_FOCUS
  …` and call hidden `AudioManager`/`AudioPlaybackConfiguration` APIs (per-player volume,
  activity, and process liveness) that the app's own UID isn't allowed to touch — that privileged
  bridge is what makes per-app volume and audio-focus control possible at all.
- **`AppRepository`** — enumerates every installed app (not just launchable ones) as candidates
  for the mixing toggle, with known music/video apps sorted to the top.

## What it's built on

- **[Shizuku](https://github.com/RikkaApps/Shizuku)** (`dev.rikka.shizuku`) is the foundation
  that makes per-app volume and audio-focus control possible without root — it's a real
  dependency the app binds to at runtime, not just an inspiration.
- The volume-panel skins are original re-implementations of AOSP/SystemUI's own volume dialog
  designs from each Android release (7 through 15) — built from scratch in Compose/Views to look
  and animate like the real thing, not copied from another app's source.
- Everything else (UI, overlay engine, mixing logic, per-app session persistence) is original
  code written for this project.

## Tech stack

- Kotlin, Jetpack Compose + Material 3 for the in-app UI (volume screen, mixing screen, overlay
  style/editor screen)
- Classic Android views (`WindowManager`, custom drawables/animations) for the overlay panel
  itself, since it has to be drawn outside the app's own window
- AIDL (`IUserService`) for the Shizuku-hosted privileged process
- Kotlin coroutines for the async Shizuku calls and the per-app re-apply polling loop
- `minSdk 24` (Android 7.0) / `targetSdk 36`

## Permissions & requirements

- **Shizuku** must be installed and running (via wireless debugging/ADB, or root) for the Mixing
  tab and per-app sliders to work; without it the app still runs and shows the custom panel with
  system stream volumes only.
- **Accessibility service** — required to intercept the hardware volume keys.
- **Display over other apps** (`SYSTEM_ALERT_WINDOW`) — required to draw the custom panel.
- **Notification policy access** — lets ring/notification volume be changed even under Do Not
  Disturb.
- `QUERY_ALL_PACKAGES` is used to list every installed app for the mixing toggle, which makes
  this an off-Play-Store build (sideload/F-Droid-style distribution only).

## Building

```
./gradlew assembleDebug
```

The APK is a standard Gradle Android project (`app` module); open it in Android Studio or build
from the command line with the wrapper above.

## License

MIT — see [LICENSE](LICENSE).
