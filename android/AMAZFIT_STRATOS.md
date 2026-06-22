# WatchEmu on the Amazfit Stratos (APM / Android 5.1)

This branch adapts the native Wear OS build so it installs and runs on the
**Amazfit Stratos**, which is **not** a Wear OS device — it runs a customized
**Android 5.1.1 (API level 22)** on a round **320 × 300** transflective display.

## What changed vs. the `wear-os-app` branch

| Area | Wear OS build | Amazfit Stratos build |
|------|---------------|------------------------|
| `minSdk` | 26 | **22** (Android 5.1.1) |
| Manifest `uses-feature` watch | required | `required="false"` so it installs on non-Wear Android |
| Manifest `uses-library` `com.google.android.wearable` | `required="true"` | `required="false"` (library absent on the Stratos) |
| App theme | `Theme.DeviceDefault` | `Theme.DeviceDefault.NoActionBar.Fullscreen` (full screen, no title bar) |
| UI toolkit | Wear Compose (`androidx.wear.compose:*`, minSdk 25) | **Regular Jetpack Compose Material** (`androidx.compose.material:material`, minSdk 21) |
| ROM picker / save dialog | `ScalingLazyColumn`, `Chip`, `SwipeToRevealChip`, `Scaffold`, `TimeText` | `LazyColumn` + custom `Surface` rows; **tap to play, trash icon to delete** |
| Font loading | `Resources.getFont()` (API 26+) | `ResourcesCompat.getFont()` (API 16+) — the old call crashed on API 22 |

The emulator core (`nes/`), audio-driven game loop, save states, ROM scanning,
Bluetooth receive, and save states are unchanged. The round-watch control
layout (floating d-pad on the bottom half, radial SELECT/START/B/A on the top
half, long-press to reveal areas) still applies because the Stratos is round.

## Robustness changes for this hardware

- **Game rendering on a `TextureView`** (`GameView`), driven from the emulation
  thread, instead of recomposing a Compose `Canvas` every frame. Each frame is
  now just a cached-shell blit plus one scaled bitmap draw — far lighter on the
  Stratos's SoC. The Compose layer only paints the transient control overlay.
- **Audio failure no longer freezes the game.** The emulation is audio-driven,
  so a missing/broken audio path would otherwise stop it. Now the `AudioTrack`
  is created defensively and, if it fails, the APU runs a silent wall-clock
  loop. A watchdog in `MainActivity` also detects a stalled audio thread (no
  frame for 1.5 s) and restarts emulation silently, so the game keeps running.
- **Physical buttons**: the side buttons are mapped as NES inputs where their
  key codes are known (volume keys → A/B, center/enter → Start; Back still
  exits). Unrecognised key codes are logged to `WatchEmu` so the exact mapping
  can be confirmed with `adb logcat -s WatchEmu` and added.
- **Bigger touch d-pad** tuned for the 320×300 screen.
- The AGSL fisheye/CRT shader was removed (it needed API 33, unavailable here).

## Building

A GitHub Actions workflow (`.github/workflows/android-build.yml`) builds the
debug APK on every push to the Stratos branch and uploads it as the
`watchemu-stratos-debug` artifact — download it from the run's **Artifacts**
section, no local toolchain needed.

To build locally instead:

```bash
cd android
./gradlew assembleDebug   # or assembleRelease
```

Output: `android/app/build/outputs/apk/`.

## Installing on the watch

The Stratos exposes ADB over its USB charging dock (the dock has data pins).

```bash
adb devices            # confirm the watch is listed
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The stock Stratos launcher hides third-party apps. Use a launcher/manager such
as **Tools & Amazfit** or **AmazMod** to start WatchEmu, or launch it once via:

```bash
adb shell am start -n com.watchemu.app/.MainActivity
```

ROMs can be pushed straight to storage and will be picked up by the in-app
scanner:

```bash
adb push game.nes /sdcard/Download/
```

## Known limitations on this hardware

- **Performance**: the Stratos SoC is modest. Even with the TextureView
  rendering path, full-speed NES emulation may not hold a steady 60 fps in
  heavier games.
- **Audio latency**: sound (when a Bluetooth headset is connected) is routed via
  A2DP, which adds noticeable delay. Game speed stays correct; only the audio
  lags. With no audio device the game runs silently.
- **Physical button mapping is provisional**: it assumes the common Stratos key
  codes. If a side button does nothing, check `adb logcat -s WatchEmu` for the
  logged `Unmapped keyCode=...` and report it so the mapping can be finalised.
