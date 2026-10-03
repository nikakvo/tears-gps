# Tears GPS

An LSPosed module that sets the device location to any point you choose, and can move it: with a floating
joystick, along routes you record, or along a real road route from A to B by car, bike or on foot.
OpenStreetMap maps, no Google services, no accounts, no API keys.

Tears GPS is a revived and extended version of **GPS Setter**, abandoned since March 2025 on the legacy
Xposed API. It was ported to the modern **libxposed API 102**, rebuilt on a current toolchain and extended
with new features.

## Requirements

- Rooted device (Magisk, KernelSU, SukiSU, …) with Zygisk
- **LSPosed with libxposed API 102** support (LSPosed 2.x)
- Android 8.1 or newer, arm64

Tested on a Poco F6 Pro with HyperOS (Xiaomi.eu), SukiSU-Ultra, ReZygisk and LSPosed 2.2.0.

## Installation

1. Install the APK from [Releases](https://github.com/nikakvo/tears-gps/releases).
2. In LSPosed, enable **Tears GPS FOSS** and tick **System Framework** in its scope.
3. Reboot.
4. Open the app, tap the map to choose a point and press ▶.

## Features

- **System-wide spoofing** on Android 14+: every location fix in the system is replaced (live updates
  included), or only the apps ticked in the LSPosed scope — switchable live, no reboot.
- **Live settings**: start, stop and moving the point apply immediately.
- **Joystick**: floating overlay that walks the point (walk, run, bike or car speed).
- **Routes**: record with the joystick and replay with the original timing; play once or in a loop.
- **Road routes A → B** (car, bike, foot) calculated on OpenStreetMap roads, with realistic timing per
  road segment (slower in town, faster on open roads).
- **Round trip**: A → B, stay at B (1 min to 24 h), then come back along a real way back.
- **Map picking**: tap shows coordinates (copy), long-press sets route start or destination.
- **Keeps running** with the screen off and continues after the app was killed.
- Raw GNSS data blocked while spoofing; mock-location flag cleared.
- Built-in **Help** page in the app.

## Building

Requires JDK 17 and the Android SDK (platform `android-37.0`, build-tools 36.0.0).

```
./gradlew assembleFossRelease
```

The APK is in `app/build/outputs/apk/foss/release/`. For release signing, put `storeFile`,
`storePassword`, `keyAlias` and `keyPassword` in `local.properties` (not tracked); without them the
release build is signed with the debug key.

`build.sh` wraps the build: it signs, copies the APK to an output folder and can set the launcher icon
from an image in `./icons` (needs ImageMagick):

```
./build.sh                 # asks which icon to use
./build.sh --icon 2        # icon number 2 from ./icons
./build.sh --keep-icon     # keep the current icon
```

## Toolchain

Gradle 9.5.0 · Android Gradle Plugin 9.3.1 (built-in Kotlin) · Kotlin 2.4.20 · KSP 2.3.12 · Hilt 2.60.1 ·
compileSdk 37 · libxposed api/service 102.0.0 · MapLibre 13.6.1 · microG location 0.3.14 · Room 2.8.5 ·
AndroidX Lifecycle 2.11.0 · Material 1.14.0 · Retrofit 3.0.0.

## Privacy

No accounts, analytics or ads. Creating an A → B route sends the start and end points to the OSRM
routing server of FOSSGIS (routing.openstreetmap.de). Searching a place may send the text to OpenStreetMap
Nominatim when the phone's geocoder finds nothing. Saved routes stay on the device.

## Credits

- **Android1500** — original [GpsSetter](https://github.com/Android1500/GpsSetter) (2022–2023)
- **jqssun** — [android-gps-setter](https://github.com/jqssun/android-gps-setter), Android 14+ support
  (2024–2025). Tears GPS is based on its last version (commit of 5 March 2025).
- **Tears Burn (nikakvo)** — Tears GPS: libxposed API 102 port, system-wide live spoofing, joystick,
  routes, toolchain modernization (2026)

Built with [LSPosed](https://github.com/LSPosed/LSPosed) and libxposed ·
map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL) ·
[OpenFreeMap](https://openfreemap.org) · [MapLibre](https://maplibre.org) ·
routing by [OSRM](https://project-osrm.org) on [FOSSGIS](https://routing.openstreetmap.de) servers ·
search by [Nominatim](https://nominatim.org) · [microG](https://microg.org) ·
virtual-joystick-android (controlwear, lukkass222 fork) · AndroidHiddenApiBypass (LSPosed) ·
AndroidX, Material Components, Dagger/Hilt, Retrofit, Timber.

## Disclaimer

Changing your location can break the rules of some apps, games and services. Use it responsibly; you are
responsible for how you use it.

## License

[GNU General Public License v3.0](LICENSE), like the projects it is based on.
