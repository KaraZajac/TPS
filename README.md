# TPS: Terran Positioning System

*Every nation's satellites, one position.*

An open-source Android app that shows where you are and which satellites told it.
A modern phone already fuses the American, Russian, European, Chinese, Japanese and
Indian navigation systems into a single fix. TPS makes that visible: a sky circle with
every satellite your phone can hear, each system in its own colour and shape, and your
position, fix quality and the time from the satellites below it. It can also navigate
you to a list of waypoints.

TPS has **no network permission**, no Google Play services and no background location.
It reads the phone's GNSS chip directly, and nothing leaves the phone.

## Screenshots

<p align="center">
  <img src="docs/img/tps-main.png" width="240" alt="Main screen: sky circle with a green fix ring and a waypoint triangle, the navigation readout, position, geohash and fix quality" />
  &nbsp;&nbsp;
  <img src="docs/img/tps-constellations.png" width="240" alt="Constellations sheet: one card per system with its satellites" />
  &nbsp;&nbsp;
  <img src="docs/img/tps-waypoints.png" width="240" alt="Waypoints: a pasted list with distances and bearings" />
</p>

<p align="center"><sub>Demo sky (simulated satellites over Singapore, where all seven systems are overhead) on an Android 16 emulator.</sub></p>

## What it shows

- **The sky circle.** Up is where the phone points, the edge is the horizon and the centre
  is overhead.
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
- **Position**: coordinates (decimal or degrees-minutes-seconds) and a **geohash**, each
  tap to copy, with height above sea level and speed. It's TPS's own **super position**
  where the phone allows (labelled `TPS`), and the chip's otherwise (`chip`).
- **The super position**: TPS's own fix from every satellite of every system at once. One
  receiver clock, with each system's offset from it learned (or taken from the chip), so
  any mix of satellites counts, not four per system; a Kalman filter that carries
  position, velocity and clock from second to second, so even one to three satellites a
  second keep it going; Doppler for velocity; and each measurement checked against the
  filter's expectation and left out if it doesn't fit. On a test recording it stayed
  within 3 m of the chip's fix typically and 8 m 95% of the time, where TPS's per-second
  fixes wandered 18 m and 79 m. It needs raw measurements and orbits (below). It takes
  over only once settled (10 seconds, ±15 m or better), holds its position through up to
  30 s of lost or jammed signal (saying so), and starts from the chip's fix when there is one.
- **Integrity warnings**: a triangle with `!` on the sky circle, orange for a caution and
  red for an alert, when TPS sees signs of **jamming or spoofing**, with a line saying what
  and the full list in the INTEGRITY card. It checks whether one system disagrees with the
  others (a fake GPS signal is the classic attack; TPS keeps to the honest systems), whether
  the position jumps faster than anything on the ground, whether TPS starts somewhere it
  couldn't have reached since it last trusted its fix (or at a GNSS time the phone's own
  clock disagrees with), whether GNSS time jumps against the phone's steady clock or sits
  far from Android's network time, whether another app is supplying the chip's position (a
  mock location), whether every signal drops at once (jamming, or a blocked sky: only a
  chip that reports its receiver gain can tell them apart) or gets stronger at once,
  whether all signals arrive at the same strength, whether the receiver gain moves (where
  the chip reports it), whether the clock jumps, whether the measurements fit together, and
  whether TPS and the chip agree. A phone can't prove an attack, and a careful spoofer that
  fakes every system consistently can pass every check.
- **Fix line**: 2D or 3D, satellites used out of those in view, time to first fix, HDOP,
  and whether the fix uses two frequencies.
- **Satellite time**: the time from GNSS, in your time zone and in UTC, and how far off
  the phone's own clock is.
- **Waypoints** (the star): paste a list, one per line, a label and a position in either
  order: `Trailhead, 44.46044, -110.82814`, `Car 9xchyjc2v` or
  `44°27'37.6"N 110°49'41.3"W Camp`. Or save where you are and name it. Tap one to
  navigate: a triangle outside the ring points to it, with the distance and bearing below
  the circle.
- **The circle turns with the phone** (heading-up), so the triangle points the way to
  walk. Turn **Turn with the phone** off in Settings for north-up.
- **Own fixes** (in progress, v0.3): on phones that hand apps raw measurements, TPS
  computes its own positions: the super position, plus each second on its own per system
  and from all of them, plotted around the chip's fix. A different answer from the chip, or from another system, is a cross-check
  against spoofing. TPS gets the orbits by decoding the satellites' navigation messages
  itself (GPS and Galileo so far). On phones that don't pass those on, it uses the
  satellite positions the chip computes: Android makes the getter system-only, but every
  measurement carries the data in its public serialized form, and TPS reads it from there.
- **CHIP card**: the GNSS hardware, what it can do (raw measurements, navigation
  messages, satellite positions, carrier phase…), the signals it supports, and a
  **raw-data recorder** that writes Google's GnssLogger format (plus the chip's satellite
  positions) and saves the file to `Download/TPS`.
- **Constellations sheet** (tap the circle): all seven systems, each with who runs it,
  satellites in view and used, its bands, and a row per satellite. A POSITION card has
  the rest: both coordinate formats, geohash, ellipsoid and sea-level height,
  accuracies, DOP, GNSS time against the phone clock, and the fix's age.

## How it works

- TPS starts listening as soon as it's on screen and stops when it leaves. It asks
  `LocationManager` for `GPS_PROVIDER` fixes directly. The fused provider would hide which
  satellites made a fix, and Android only runs the GNSS engine while someone holds such a
  request.
- **Two fixes.** The phone's GNSS chip computes one, combining every constellation it
  receives (Android doesn't let apps choose which). TPS computes its own from the chip's
  raw measurements (pseudoranges and Doppler) and the satellites' orbits, and shows it
  once it has settled, falling back to the chip's in demo sky, on phones without raw
  data, and whenever its own isn't current.
- Satellites come from `GnssStatus`. A dual-frequency satellite arrives once per band,
  and TPS merges the entries. Band names come from the carrier frequency, as each system
  names it (L5 is E5a on Galileo and B2a on BeiDou).
- DOP and 2D/3D come from the chip's NMEA `GSA` sentence. Phones that don't send one get
  DOP computed from the geometry of the satellites in the fix, and an inferred 2D/3D,
  labelled as such. Time to first fix comes from the chip, or from TPS's own clock if
  GNSS was already running when TPS started.
- Satellite time is the UTC each fix carries, carried forward between fixes by the
  phone's monotonic clock. TPS shows it but can't set the phone's clock.
- Height above sea level comes from Android (API 34+), Android's bundled geoid model, or
  the geoid separation in the chip's NMEA `GGA` sentence, in that order.
- Heading-up uses the rotation-vector sensor, corrected to true north with Android's
  built-in World Magnetic Model (offline). It only runs in that mode, on screen.

The design and the research behind it are in [PLAN.md](PLAN.md).

## Install

Android 8.0 or newer. Releases are on
[GitHub Releases](https://github.com/KaraZajac/TPS/releases) as a debug-signed APK to
sideload. Every push also builds one (Actions, then the run, then `tps-apk`).

Allow **precise** location when asked. With approximate location Android sends no
satellite data at all.

## Build

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # GNSS model, NMEA, DOP, geohash, waypoints, compass math, formats
```

JDK 17 or newer and the Android SDK (compileSdk 37). The emulator's GNSS invents a few
GPS satellites with no position, so turn on **Settings → Demo sky** to see a full sky
there.

## Privacy

TPS requests location and nothing else. CI checks every APK against that allowlist
([`scripts/check-permissions.sh`](scripts/check-permissions.sh)) and fails the build if a
dependency adds a network permission. Waypoints, settings and the last position TPS
trusted (for its start-up check) stay on the phone.

## License

BSD-3-Clause; see [LICENSE](LICENSE). The typeface is
[Kibernetyk Mono](https://pavlokilko.github.io/KibernetykMono/) by Pavlo Kilko, under the
SIL Open Font License 1.1 (`app/src/main/assets/licenses/KibernetykMono-OFL.txt`). The
icons are from Material Icons (Apache License 2.0).
