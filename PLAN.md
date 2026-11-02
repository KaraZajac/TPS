# TPS: Terran Positioning System (plan)

*Drafted 2026-11-02, and the decisions in §1 were made the same day. This file is the
plan and the research behind it. Screenshots of the built app are in the
[README](README.md).*

**What it is:** an open-source Android app that shows where you are and which satellites
told it. The OVERWATCH circle becomes a **sky circle**: every navigation satellite your
phone can hear, from every nation, is plotted where it sits in the sky and coloured by
constellation. Below the circle are your position and fix quality. Tap the circle and a
sheet lists each constellation and its satellites, the way OVERWATCH lists its detection
sources.

**Tagline:** *Every nation's satellites, one position.* A modern phone already fuses
the US, Russian, European, Chinese, Japanese and Indian systems into a single fix.
TPS makes that visible. It fits the TERRAN idea of "one people without nations".

---

## 1. Decisions (made 2026-11-02)

| # | Question | Decision |
|---|---|---|
| 1 | **"Tarran" or "Terran"?** | **Terran**, as in TERRAN / the Terran Charter. The folder and repo are `TPS`. |
| 2 | **License** | **BSD-3-Clause**, like OVERWATCH. |
| 3 | **Forgejo mirror** | **No.** GitHub only. |
| 4 | **Scope of v0.1** | **As planned:** foreground only (no background service), no network at all, no Google Play services. |

## 2. What the research found

### What an Android app can and cannot do with GNSS

- **Every constellation comes through one callback.** `GnssStatus` (API 24) reports each
  satellite signal the chip is tracking. Each one carries:
  - the constellation: `GPS`, `SBAS`, `GLONASS`, `QZSS`, `BEIDOU`, `GALILEO` or `IRNSS` (= NavIC).
    `IRNSS` only exists from API 29, so with minSdk 26 TPS also handles `UNKNOWN`.
  - the SVID
  - C/N0 (signal strength)
  - elevation and azimuth
  - whether it was `usedInFix`
  - whether ephemeris and almanac data are present
  - the carrier frequency (API 26), which tells you the band (L1 vs L5 and so on)
  - the baseband C/N0 (API 30)

  A **dual-frequency** satellite shows up **twice**, once per band. Some chipsets also
  list satellites they only **predict** from the almanac, with C/N0 = 0. They aren't
  being received, so TPS shows them as "not tracked" and leaves them out of the counts.
- **The chip does the fusing, and apps can't change what it uses.** There is no public API
  to turn constellations on or off. The satellite blocklist is `@SystemApi`. TPS shows what
  the chip actually uses. It can't add a constellation the OEM turned off.
- **"Everything the chip can do" is mostly something TPS can ask about:**
  - `getGnssHardwareModelName()` and `getGnssYearOfHardware()` (API 28)
  - `getGnssCapabilities()` (API 30). On API 30 it only reports antenna info. Raw
    measurements, navigation messages and antenna info are API 31. Satellite PVT,
    accumulated delta range (a tri-state `Int`, not a Boolean) and the power and
    correction flags are API 34.
  - **`getGnssSignalTypes()` (API 34)**, which lists every constellation and band the chipset
    supports. This lets TPS tell "supported but not in view right now" apart from "this
    phone can't receive it". The GNSS HAL fills it in, and HALs older than Android 14's
    leave it empty, so an empty list means "unknown", not "supports nothing".
- **Fix quality.** `Location` gives accuracy, speed, bearing and their accuracies.
  **DOP (PDOP/HDOP/VDOP) and 2D/3D fix type are not in the Location API.** They come from
  the NMEA `GSA` sentence through `OnNmeaMessageListener`. Not every phone sends `GSA`, so
  TPS falls back to computing DOP from the geometry of the satellites used in the fix
  and infers 2D/3D from the fix itself. Time-to-first-fix comes from
  `GnssStatus.Callback.onFirstFix(ttffMillis)`, which only fires when the engine starts
  while TPS is listening. If another app already has GNSS running, it never comes, and
  TPS shows its own time from START to the first fix instead, labelled as such.
- **Altitude above sea level.** On API 34+, `Location.getMslAltitudeMeters()` is used
  when the platform fills it in. Otherwise `AltitudeConverter.addMslAltitudeToLocation()`
  (API 34, worker thread only) converts it, or the geoid separation from the NMEA `GGA`
  sentence is used. If none of those exist, the height is labelled as WGS 84 ellipsoid
  height. API 35 adds `tryAddMslAltitudeToLocation()`, which doesn't block.
- **Raw measurements** (`GnssMeasurementsEvent`) include **AGC per band** (API 33) and an
  `isFullTracking` flag (API 34). There is no public jamming indicator; AGC is the proxy.
  Android duty-cycles measurements unless the developer option "Force full GNSS
  measurements" is on, or the app asks for full tracking itself with
  `GnssMeasurementRequest.Builder.setFullTracking()` (API 31). This is for later
  milestones, not v0.1.
- **The GNSS engine only runs while someone requests GPS location.** TPS must hold a
  `GPS_PROVIDER` location request, or no `GnssStatus` arrives. TPS uses `LocationManager`
  directly and **no Play services**. Fused location hides which satellites produced a
  fix, and staying off Play services means it works on GrapheneOS and CalyxOS and can go
  on F-Droid.

All API levels above were checked against `platforms/android-37.0/data/api-versions.xml`.

### Prior art

- **GPSTest** (`barbeau/gpstest`, Apache-2.0, v3.10.6 released 2026-08-29, ~2.4k stars, still
  active) is the reference open-source GNSS app. It has a sky view, a status list,
  accuracy, a map and raw logging. TPS shouldn't try to out-feature it. TPS stands apart
  by:
  - an OVERWATCH-style single screen built around one circle
  - constellations as first-class cards
  - multi-band at a glance
  - "what your chip can do" next to "what's in view"
  - zero network and zero Play services
- **GnssLogger** (`google/gps-measurement-tools`, Apache-2.0) is the reference for handling
  raw measurements.
- **SatStat** (GPL-3.0) was archived in 2019.
- **GNSSAlarm** (CU Boulder, "Detecting GNSS Jamming and Spoofing on Android Devices")
  detects jamming and spoofing from data a phone already has:
  - GNSS position compared with network position
  - the mock-location flag
  - GNSS time compared with system time
  - AGC and C/N0

  It's the reference for the optional interference idea in v0.3.

### What the sky looks like (November 2026)

- **Global:** GPS (US), GLONASS (Russia), Galileo (EU) and BeiDou (China). Galileo got
  two more first-generation satellites on Ariane 6 L14 (2025-12-17). Its second
  generation has slipped.
- **Regional (Asia-Oceania): QZSS (Japan).** QZS-5 was lost in the H3 failure of
  2025-12-22. QZS-7 launched in August 2026, so the 7-satellite constellation is still
  incomplete.
- **Regional (India): NavIC.** As of July 2026, only 3 satellites provide service, and
  standalone positioning needs 4. NVS-03 launched on GSLV-F18 on 2026-10-30 and is
  raising its orbit; as of 2026-11-02 it isn't in service yet. (NVS-02 got stuck at
  this stage in 2025.) The NVS satellites add an L1 signal.
- **SBAS (WAAS, EGNOS and others)** is augmentation, not a constellation. It shows up as
  satellites and provides corrections.
- **From Arlington** you'll see GPS, GLONASS, Galileo, BeiDou and WAAS. QZSS and NavIC
  are over Asia, so in the US they'll only ever appear as "supported, not in view".

### Build and test environment (checked on this machine)

- Local builds work. `~/Android/Sdk` has platforms 35, 36 and 37.0 and build-tools
  34–36. Gradle runs on `~/.jdks/jdk-21.0.2` through `~/.gradle/gradle.properties`.
  OVERWATCH built a debug APK here on 2026-11-01. CI builds the releases.
- OVERWATCH has no unit tests, and its CI only runs on `v*` tags. TPS's CI (unit tests
  on every push, plus a check that the APK never requests `INTERNET`) is new.
- **The emulator can't test the sky circle.** AOSP's emulator GNSS HAL
  (`device/generic/goldfish/hals/gnss/GnssHwListener.cpp`) makes up N **GPS-only**
  satellites from the NMEA GGA count. All of them sit at **elevation 0°, azimuth 0°**,
  C/N0 30, none are used in the fix, and their carrier frequency is a GLONASS G1 one
  (so TPS only names a band when the system actually broadcasts on it). So TPS gets a **demo sky** mode for layout work
  and emulator screenshots. It simulates the real orbits as seen from Singapore, one of
  the places where all seven systems are overhead. The real test is a real phone
  outdoors.

## 3. Design

### Main screen: same layout as OVERWATCH, element for element

| OVERWATCH | TPS |
|---|---|
| Title `OVERWATCH` + gear | Title `TPS` (same letter-spaced monospace) + gear |
| Map circle, ring = threat tier | **Sky circle**, ring = **fix quality** |
| Legend: ALPR / speed / cam / police / air | Legend: the constellations **in view right now** |
| "tap circle for source details" | "tap circle for constellation details" |
| "show within 1200 m" slider | **Position readout**: `position … ±3.2 m`, coordinates, alt / speed. Tap to copy. |
| "Surveillance camera @ 91m • 43" | `3D fix • 26 of 39 sats` |
| "Max score: 43" | `TTFF 6.8 s • HDOP 0.6 • dual-frequency` |
| START / STOP | START / STOP (GNSS listening) |

**Ring colour.** It uses OVERWATCH's four tier colours, but here green means good:

| Ring | Meaning |
|---|---|
| Grey | Idle |
| Red, pulsing | Searching, no fix yet |
| Orange | 2D fix, or accuracy worse than 15 m |
| Yellow | Accuracy of 15 m or better |
| Green | Accuracy of 5 m or better |

While idle or before the first satellite arrives, the circle shows OVERWATCH's pulsing
disc with `IDLE` or `SEARCHING`.

**Sky circle encoding:**
- North is up. The edge is the horizon and the centre is overhead. There are rings at 30°
  and 60° and N/E/S/W labels.
- Colour and shape show the constellation (see below).
- **Filled** means used in the fix. **Hollow** means tracked but not used. A faint
  outline means predicted but not tracked (C/N0 = 0).
- Dot size shows C/N0.
- A **thin outer ring** means dual-frequency.
- Satellites with no known position (el = az = 0) are listed but **not plotted**.
  Plotting them would pile fake dots at north on the horizon. A real satellite at
  exactly 0.0°/0.0° is rare enough that the ephemeris and almanac flags aren't needed
  to tell them apart.

**Constellation colours and shapes.** The colours are kept clear of the ring's green.
A colour-blindness simulation found that GPS blue and BeiDou violet look almost
identical with red-green colour blindness (CIEDE2000 2.1 for deuteranopia and 4.1 for
protanopia; small dots need about 15). So every constellation also has its own shape,
on by default. Settings can switch back to plain dots.

| Constellation | Colour | Shape |
|---|---|---|
| GPS | blue `#2196F3` | circle |
| GLONASS | red `#E5484D` | square |
| Galileo | gold `#F4C20D` | triangle |
| BeiDou | violet `#9D7BFF` | diamond |
| QZSS | pink `#FF6FB5` | star |
| NavIC | cyan `#26C6DA` | inverted triangle |
| SBAS | grey `#9E9E9E` | cross |

### Constellations sheet (in place of "Detection sources")

- **One card per constellation, always all seven:** GPS, GALILEO, BEIDOU, GLONASS, QZSS,
  NAVIC and SBAS, the same card style as OVERWATCH. The status dot shows the
  constellation colour if any of its satellites are used in the fix, grey if they're only
  tracked, and hollow if none are in view. If the chip can't receive the constellation,
  the card says "not supported by this phone".
- **Each card's header** shows who runs it, how many satellites are in view and used, and
  the bands, e.g. `United States · 11 in view · 7 used · L1 L5`.
- **Rows** look like `G18 • 44 dB-Hz • el 71° az 65° • L1+L5 • used`. A card shows the
  top three, then "+N more". Tapping a card expands the full list.
- **POSITION card:**
  - decimal and DMS coordinates
  - ellipsoid and MSL altitude
  - horizontal and vertical accuracy
  - speed and bearing, each with its accuracy
  - GNSS time against the phone clock
  - fix age
- **CHIP card:** hardware model and year, the supported signal list, and capability flags.

### Settings

- Theme: system / dark / light, as in OVERWATCH
- Units: metric / imperial
- Coordinate format: decimal / DMS, with MGRS later
- Satellite labels on or off
- Keep screen on while running
- v0.2 adds: heading-up compass mode, and cold start (clear aiding data) to measure TTFF
- About: version, license, source link, and "TPS has no network permission"

## 4. Architecture

```
org.soulstone.tps
├── MainActivity.kt        permission flow, edge-to-edge, MAIN/SETTINGS routing (from OVERWATCH)
├── gnss/
│   ├── GnssEngine.kt      LocationManager GPS_PROVIDER + GnssStatus + NMEA (or DemoSky) → one StateFlow
│   ├── Sky.kt             Android-free model: Signal → Satellite (bands merged) → ConstellationSummary; sky projection
│   ├── Constellation.kt   id ↔ name/operator/colour/shape/prefix; SVID labels (GLONASS FCN, QZSS J##); SBAS PRN → system
│   ├── Bands.kt           carrier Hz → band, including GLONASS FDMA channels, and which systems use which band
│   ├── Nmea.kt            GSA (fix type, PDOP/HDOP/VDOP, NMEA 4.10 system id), GGA (quality, geoid sep.)
│   ├── Dop.kt             DOP from geometry (one clock term per system), for chips without GSA
│   ├── Fix.kt             Android-free fix, fix tiers, 2D/3D inference
│   ├── DemoSky.kt         simulated orbits seen from Singapore, for the emulator and screenshots
│   └── ChipInfo.kt        (v0.2) model/year, GnssCapabilities, getGnssSignalTypes() on API 34+
├── data/Settings.kt       SharedPreferences + StateFlows (OVERWATCH pattern)
└── ui/
    ├── MainScreen.kt      OVERWATCH layout + Constellations sheet + POSITION card
    ├── SkyCircle.kt       Compose Canvas sky plot and the constellation marks
    ├── PulseVisuals.kt    idle/searching pulse and the fix ring (from OVERWATCH)
    ├── Format.kt          coordinates, units, durations (Locale.ROOT)
    ├── SettingsScreen.kt
    └── theme/Theme.kt     OVERWATCH colours, fix-ring tiers, sky colours
```

- **Toolchain:** the same as OVERWATCH today: AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.1,
  Compose BOM 2026.09.00, compileSdk 37, minSdk 26 and JDK 17. TPS targets SDK 36
  (OVERWATCH targets 35). Package `org.soulstone.tps`. The debug keystore is committed
  with password `android`, so CI and local builds sign the same way.
- **Dependencies:** core-ktx, lifecycle, activity-compose, Compose and Material 3. Nothing
  else: no Play services, osmdroid or protobuf, and the two icons are vector drawables
  rather than an icons library. JUnit is a test-only dependency. `LocationManagerCompat`
  from androidx.core smooths over the API 26–30 differences.
- **Permissions:** `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`. If the user grants
  only approximate location, no GNSS status arrives, and TPS explains why. If location is
  switched off, nothing arrives either, so TPS says so and opens the setting.
  `ACCESS_LOCATION_EXTRA_COMMANDS` (a normal permission) arrives in v0.2 for cold start.
  TPS has **no INTERNET permission**, no notifications and no background location.
- **Lifecycle:**
  - GNSS runs only while TPS is on screen and START is on.
  - Leaving the app pauses it, and coming back resumes it.
  - There is no foreground service until a feature like track logging needs one.
  - Recomposition is kept in leaf composables, OVERWATCH's PulseVisuals lesson, so the
    pulse never redraws the whole circle.

## 5. Milestones

| Version | Contents |
|---|---|
| **v0.0** ✓ 2026-11-02 | Scaffold: Gradle (copied from OVERWATCH), LICENSE, README, `.gitignore`, CI (build + unit tests on every push; APK attached to a Release on `v*` tags). `gh repo create KaraZajac/TPS --public`. First green build. |
| **v0.1.0** built 2026-11-02, awaiting the outdoor phone test | The sky circle, the Constellations sheet, position readout, START/STOP, permission flow, settings (theme/units/coordinates), MSL height, demo sky, launcher icon (sky-plot glyph on OVERWATCH's dark background), unit tests. Tested on Kara's phone. |
| **v0.2.0** | **Everything the chip can do:** CHIP card, "unsupported vs not in view", per-band detail, heading-up compass, satellite trails, cold start / TTFF test. |
| **v0.3.0** (optional) | Raw measurements: signal codes and AGC per band. **Interference watch**, OVERWATCH-family: AGC drops, C/N0 flattening, GNSS clock vs phone clock, position jumps, with honest limits. Research stretch: **one fix per constellation** (GPS-only vs Galileo-only vs BeiDou-only dots from satellite PVT + pseudoranges), so each nation's answer appears next to the fused one. |
| **Later** | tps.netslum.io site, F-Droid submission, GPX/NMEA logging. |

## 6. Testing

- **Unit tests** run in CI. They cover:
  - band classification, including GLONASS channels −7…+6
  - SVID labels
  - merging dual-band signals
  - NMEA GSA/GGA parsing, including checksums
  - sky projection, including heading rotation
  - fix tiers
- **On the emulator** (e.g. the `ow36` AVD), demo sky only: layout checks and labelled
  screenshots.
- **On a real phone outdoors:** the actual test. Kara sideloads the CI APK. Check that
  the counts and positions match GPSTest on the same phone.

## 7. Technical reference

**Bands (MHz).** Match by carrier frequency, ±1 MHz except GLONASS FDMA.

| Frequency | Signals |
|---|---|
| 1575.42 | GPS L1, Galileo E1, BeiDou B1C, QZSS L1, NavIC L1 (NVS satellites), SBAS L1 |
| 1561.098 | BeiDou B1I |
| 1602 + k·0.5625, k = −7…+6 | GLONASS G1 |
| 1227.60 | GPS/QZSS L2 |
| 1246 + k·0.4375 | GLONASS G2 |
| 1176.45 | GPS L5, Galileo E5a, BeiDou B2a, QZSS L5, NavIC L5, SBAS L5 |
| 1207.14 | Galileo E5b, BeiDou B2I/B2b |
| 1278.75 | Galileo E6, QZSS L6 |
| 1268.52 | BeiDou B3I |
| 2492.028 | NavIC S |

**SVID ranges** (AOSP `GnssStatus.getSvid` docs):

| Constellation | SVIDs | Label |
|---|---|---|
| GPS | 1–32 | G## |
| SBAS | 120–151, 183–192 | S### + system name from the GPS.gov L1 C/A PRN table (Jan 2026 edition); 183–192 are QZSS's SLAS signals |
| GLONASS | 1–25 (orbital slot) | R## |
| GLONASS | 93–106 (FCN + 100, slot unknown) | "R k=±n" |
| QZSS | 183–206 (193–206 = J01–J14; 183–192 are the L1S signals of J01–J10) | J## |
| Galileo | 1–36 | E## |
| BeiDou | 1–63 | C## |
| NavIC (IRNSS) | 1–14 | I## |

**Gotchas to code for**

1. No `GPS_PROVIDER` request means no `GnssStatus`.
2. Approximate-only permission means no `GnssStatus`.
3. el = az = 0 means the position is unknown, so don't plot it.
4. Dual-frequency means two entries per satellite. Merge them for the dot and keep both
   for the rows.
5. DOP and 2D/3D come only from NMEA, and some chips emit one `GSA` line per
   constellation.
6. `AltitudeConverter` blocks, so call it off the main thread.
7. `getGnssSignalTypes()` is API 34+. On older phones, "supported" can only be inferred
   from what has been seen.
8. The emulator's satellites are fake (see §2).
9. C/N0 = 0 means predicted, not tracked. Don't count it as in view.
10. NMEA may never send `GSA`, and `onFirstFix` may never fire. Show "—" and use the
    fallbacks in §2.
11. Location switched off means no `GnssStatus` at all.
12. An empty `getGnssSignalTypes()` list means unknown, not unsupported.

## Sources

- AOSP `frameworks/base/location/java/android/location/`: `GnssStatus.java`,
  `GnssCapabilities.java`, `GnssSignalType.java`, `LocationManager.java`,
  `altitude/AltitudeConverter.java` (main, fetched 2026-11-02)
- AOSP `device/generic/goldfish/hals/gnss/GnssHwListener.cpp` (emulator GNSS HAL)
- SDK `platforms/android-37.0/data/api-versions.xml` (API levels)
- GPSTest: https://github.com/barbeau/gpstest · GnssLogger: https://github.com/google/gps-measurement-tools · SatStat: https://github.com/mvglasow/satstat
- GNSSAlarm paper: https://scholar.colorado.edu/concern/articles/c534fq668
- QZSS: https://en.wikipedia.org/wiki/Quasi-Zenith_Satellite_System · https://www.techradar.com/pro/japan-launches-sovereign-satellite-navigation-network-as-gps-cannot-reach-all-of-its-country
- NavIC: https://www.business-standard.com/india-news/isro-nvs-03-satellite-launch-navic-navigation-system-126102900487_1.html · https://www.theweek.in/news/sci-tech/2026/09/29/navic-satellite-constellation-importance.html
- Galileo: https://insidegnss.com/ariane-6-launches-impact-for-galileo/ · https://www.euspa.europa.eu/pressroom/press-releases/euspa-signs-new-ariane-6-launch-contract-galileo-second-generation
- NVS-03 launch: https://www.space.com/space-exploration/launches-spacecraft/india-launches-nvs-03-navigation-satellite-gslv-rocket · https://www.deccanchronicle.com/southern-states/andhra-pradesh/isro-successfully-launches-gslv-f18-carrying-nvs-03-navigation-satellite-2000444
- SBAS and QZSS PRNs: GPS.gov L1 C/A PRN code assignments, Jan 2026 edition, https://www.gps.gov/sites/default/files/2026-02/L1CA-PRN-code-assignments-2026-Jan_2.pdf
