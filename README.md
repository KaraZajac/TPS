# TPS: Terran Positioning System

*Every nation's satellites, one position.*

An open-source Android app that shows where you are and which satellites told it.
A modern phone already fuses the American, Russian, European, Chinese, Japanese and
Indian navigation systems into a single fix. TPS makes that visible: a sky circle with
every satellite your phone can hear, each system in its own colour and shape, and your
position and fix quality below it.

TPS has **no network permission**, no Google Play services and no background location.
It reads the phone's GNSS chip directly, and nothing leaves the phone.

## Screenshots

<p align="center">
  <img src="docs/img/tps-main.png" width="240" alt="Main screen: sky circle with a green fix ring, position and fix quality" />
  &nbsp;&nbsp;
  <img src="docs/img/tps-constellations.png" width="240" alt="Constellations sheet: one card per system with its satellites" />
  &nbsp;&nbsp;
  <img src="docs/img/tps-settings.png" width="240" alt="Settings" />
</p>

<p align="center"><sub>Demo sky (simulated satellites over Singapore, where all seven systems are overhead) on an Android 16 emulator, v0.1.0.</sub></p>

## What it shows

- **The sky circle.** North is up, the edge is the horizon and the centre is overhead.
  Every satellite the chip reports is drawn where it is:
  - **colour and shape** per system: GPS blue circle, Galileo gold triangle, BeiDou violet
    diamond, GLONASS red square, QZSS pink star, NavIC cyan inverted triangle, SBAS grey
    cross. The shapes are on by default because GPS blue and BeiDou violet look the same
    with red-green colour blindness.
  - **filled** = used in the fix, **hollow** = received but not used, **faint** = predicted
    from the almanac but not received
  - **size** = signal strength (C/N0); a **thin ring** = received on two frequencies
- **The ring** around it is fix quality: red and pulsing while searching, orange for a 2D
  fix or accuracy worse than 15 m, yellow for 15 m or better, green for 5 m or better.
- **Position**: coordinates (decimal or degrees-minutes-seconds; tap to copy), height
  above sea level and speed.
- **Fix line**: 2D or 3D, satellites used out of those in view, time to first fix, HDOP,
  and whether the fix uses two frequencies.
- **Constellations sheet** (tap the circle): all seven systems, each with who runs it,
  satellites in view and used, its bands, and a row per satellite. A POSITION card has
  the rest: both coordinate formats, ellipsoid and sea-level height, accuracies, DOP,
  GNSS time against the phone clock, and the fix's age.

## How it works

- TPS asks `LocationManager` for `GPS_PROVIDER` fixes directly. The fused provider would
  hide which satellites made a fix, and Android only runs the GNSS engine while someone
  holds such a request.
- Satellites come from `GnssStatus`. A dual-frequency satellite arrives once per band,
  and TPS merges the entries. Band names come from the carrier frequency, as each system
  names it (L5 is E5a on Galileo and B2a on BeiDou).
- DOP and 2D/3D come from the chip's NMEA `GSA` sentence. Phones that don't send one get
  DOP computed from the geometry of the satellites in the fix, and an inferred 2D/3D,
  labelled as such. Time to first fix comes from the chip, or from TPS's own clock if
  GNSS was already running when TPS started.
- Height above sea level comes from Android (API 34+), Android's bundled geoid model, or
  the geoid separation in the chip's NMEA `GGA` sentence, in that order.
- GNSS runs only while TPS is on screen and START is on.

The design and the research behind it are in [PLAN.md](PLAN.md).

## Install

Android 8.0 or newer. Tagged versions are on [Releases](https://github.com/KaraZajac/TPS/releases)
as a debug-signed APK to sideload. Every push also builds one (Actions, then the run, then
`tps-apk`).

Allow **precise** location when asked. With approximate location Android sends no
satellite data at all.

## Build

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # the GNSS model, NMEA, DOP, formatting, demo sky
```

JDK 17 or newer and the Android SDK (compileSdk 37). The emulator's GNSS invents a few
GPS satellites with no position, so turn on **Settings → Demo sky** to see a full sky
there.

## Privacy

TPS requests location and nothing else. CI checks every APK against that allowlist
([`scripts/check-permissions.sh`](scripts/check-permissions.sh)) and fails the build if a
dependency adds a network permission.

## License

BSD-3-Clause; see [LICENSE](LICENSE). The gear and back-arrow icons are from Material
Icons (Apache License 2.0).
