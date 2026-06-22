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
Bluetooth receive, and the in-game `WatchScreen` rendering are unchanged. The
round-watch control layout (floating d-pad on the bottom half, radial
SELECT/START/B/A on the top half, long-press to reveal areas) still applies
because the Stratos is also a round display.

## Building

```bash
cd android
./gradlew assembleRelease   # or assembleDebug
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

- **Performance**: the Stratos SoC is modest. Jetpack Compose plus full-speed
  NES emulation may not hold a steady 60 fps in heavier games.
- **Fisheye/CRT shader**: the AGSL barrel-distortion effect needs API 33 and is
  automatically disabled here (the picture renders flat).
- **Physical buttons**: gameplay is via the touchscreen overlay. The side
  buttons keep their system roles (e.g. back/exit); on-screen controls drive the
  NES inputs.
