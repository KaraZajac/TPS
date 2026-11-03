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
| 5 | **v0.1.0 release** | Tested outdoors on Kara's phone (GPS + Galileo + GLONASS fix) and released 2026-11-02. |
| 6 | **START/STOP** | **Removed (v0.2).** TPS starts finding a fix as soon as it opens, and stops when it leaves the screen. |
| 7 | **Navigation view** | **Heading-up by default** (the circle turns with the phone), with a setting for north-up. Changed from north-up-default after the v0.2.0 test. |
| 8 | **Typeface** | **Kibernetyk Mono** by Pavlo Kilko, everywhere (SIL OFL 1.1; the license ships in the APK). Its glyphs are 0.72 em wide, and it has no ± or ▲, which Android draws from the system font. |
| 9 | **Waypoints button** | A **star**. **Add where I am** asks for a label in a dialog. |
| 10 | **Orbits on phones without navigation messages** | The **chip's own satellite positions** (`SatellitePvt`), read from each measurement's public serialized form (§8). Chosen by Kara after the CHIP card showed their phone sends raw measurements but no navigation messages. The alternative, importing a daily orbit file downloaded in the browser, stays possible later. |
| 11 | **The position on the main screen** | **TPS's own super position** (§8) when it has a current one, the chip's otherwise, labelled `TPS` or `chip`. Chosen by Kara after a prototype on their recording stayed within ~3 m of the chip's fix. |
| 12 | **Jamming and spoofing warnings** | A **triangle with `!`** on the sky circle (orange: caution, red: alert), a line under the fix, and an INTEGRITY card with every check (§8). Kara's request. |

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
| Title `OVERWATCH` + gear | Title `TPS` (Kibernetyk Mono, letter-spaced), waypoints star on the left, gear on the right |
| Map circle, ring = threat tier | **Sky circle**, ring = **fix quality** |
| Legend: ALPR / speed / cam / police / air | Legend: the constellations **in view right now** |
| "tap circle for source details" | "tap circle for constellation details" |
| "show within 1200 m" slider | **Position readout**: `position … ±3.2 m`, coordinates, alt / speed. Tap to copy. |
| "Surveillance camera @ 91m • 43" | `3D fix • 26 of 39 sats` |
| "Max score: 43" | `TTFF 6.8 s • HDOP 0.6 • dual-frequency` |
| START / STOP | **None** (v0.2): TPS listens whenever it's on screen. The bottom holds only notices with one button (allow location, turn on location). |
| | v0.2: `geohash 9xchyjc2v` under the coordinates (tap to copy) |
| | v0.2: **satellite time**: local and UTC time from GNSS, and how far off the phone's clock is |
| | v0.2: **navigating to**: the target's label, distance and bearing, right under the circle |

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
- The edge is the horizon and the centre is overhead. There are rings at 30° and 60° and
  N/E/S/W labels. Up is where the phone points (**Turn with the phone**, on by default
  since v0.2.1): the compass (rotation-vector sensor, corrected to true north with
  Android's offline World Magnetic Model) turns the sky, the letters and the waypoints.
  With it off, north is up.
- Waypoints (v0.2) sit just outside the ring at their bearing from you: the one being
  navigated to as a triangle pointing outwards, the rest as small dots.
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

### Satellite time (v0.2)

Every GNSS fix carries UTC time from the satellites (`Location.getTime()`), stamped on
the phone's monotonic clock (`elapsedRealtimeNanos`). Between fixes, and after a fix is
lost, the monotonic clock carries it forward, so the satellite clock stays right. It's
shown in the phone's time zone and in UTC, with the phone clock's offset from it. TPS
can't set the phone's clock: that needs a system permission. A later version could use
`GnssClock` from raw measurements for sub-millisecond time.

### Geohash (v0.2)

Geohash is plain arithmetic on the coordinates, so it's offline. It's shown under the
coordinates at 9 characters (a cell about 4.8 m across) by default, and Settings offers
none, 7, 8, 9 or 10.

### Waypoints and navigation (v0.2)

- **Waypoints screen** (the star): paste or type a list, one per line, with a label and
  a position in either order. Decimal degrees (signed, or with N/S/E/W), degrees-minutes(-
  seconds) and geohashes all work; lines that can't be read are reported, not dropped
  silently. **Add where I am** asks for a label in a dialog (pre-filled with the time)
  and saves the position from the moment of the tap.
  **Copy all** exports the list in a format the parser reads back.
- **Navigating:** tap a waypoint. Its triangle sits outside the ring at its bearing, and
  the readout gives the distance and bearing (great-circle, on the mean Earth radius). It
  reads "you're there" within the fix's accuracy, or 8 m.
- Stored on the phone in SharedPreferences, like everything else in TPS.

### Settings

- Theme: system / dark / light, as in OVERWATCH
- Units: metric / imperial
- Coordinate format: decimal / DMS, with MGRS later
- Geohash: none / 7 / 8 / 9 / 10 characters (v0.2)
- Satellite labels on or off
- Turn with the phone (heading-up compass), on by default (v0.2.1)
- Keep screen on
- Demo sky
- v0.3 adds: cold start (clear aiding data) to measure TTFF
- About: version, license, source link, and "TPS has no network permission"

## 4. Architecture

```
org.soulstone.tps
├── MainActivity.kt        starts listening on screen, permission flow, edge-to-edge, MAIN/SETTINGS/WAYPOINTS routing
├── gnss/
│   ├── GnssEngine.kt      LocationManager GPS_PROVIDER + GnssStatus + NMEA (or DemoSky) → one StateFlow
│   ├── Sky.kt             Android-free model: Signal → Satellite (bands merged) → ConstellationSummary; sky projection
│   ├── Constellation.kt   id ↔ name/operator/colour/shape/prefix; SVID labels (GLONASS FCN, QZSS J##); SBAS PRN → system
│   ├── Bands.kt           carrier Hz → band, including GLONASS FDMA channels, and which systems use which band
│   ├── Nmea.kt            GSA (fix type, PDOP/HDOP/VDOP, NMEA 4.10 system id), GGA (quality, geoid sep.)
│   ├── Dop.kt             DOP from geometry (one clock term per system), for chips without GSA
│   ├── Fix.kt             Android-free fix, fix tiers, 2D/3D inference
│   ├── DemoSky.kt         simulated orbits seen from Singapore, for the emulator and screenshots
│   └── ChipInfo.kt        (v0.3) model/year, GnssCapabilities, getGnssSignalTypes() on API 34+
├── nav/
│   ├── Geohash.kt         encode/decode, cell sizes
│   ├── Geodesy.kt         distance, bearing, compass points
│   ├── Waypoint.kt        the waypoint list parser (decimal, DMS, geohash) and its storage format
│   ├── WaypointStore.kt   the list and the target, in SharedPreferences
│   ├── Heading.kt         heading from a rotation matrix (top edge flat, camera upright), smoothing
│   └── Compass.kt         rotation-vector sensor + declination → heading-up
├── data/Settings.kt       SharedPreferences + StateFlows (OVERWATCH pattern)
└── ui/
    ├── MainScreen.kt      OVERWATCH layout, position + geohash, satellite clock, navigation readout
    ├── ConstellationsSheet.kt  the per-system cards and the POSITION card
    ├── WaypointsScreen.kt
    ├── SkyCircle.kt       Compose Canvas sky plot and the constellation marks
    ├── PulseVisuals.kt    idle/searching pulse and the fix ring (from OVERWATCH)
    ├── Format.kt          coordinates, units, durations (Locale.ROOT)
    ├── SettingsScreen.kt
    ├── theme/Theme.kt     OVERWATCH colours, fix-ring tiers, sky colours
    └── theme/Type.kt      Kibernetyk Mono, its glyph width, and the type scale
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
  `ACCESS_LOCATION_EXTRA_COMMANDS` (a normal permission) arrives in v0.3 for cold start.
  The compass needs no permission.
  TPS has **no INTERNET permission**, no notifications and no background location.
- **Lifecycle:**
  - GNSS runs only while TPS is on screen, starting on its own (v0.2). On first launch
    TPS asks for location straight away; if that's refused, it says why and offers to ask
    again.
  - Leaving the app stops it, and coming back starts it again. A rotation doesn't.
  - The compass runs only in heading-up mode, and only on screen. Its heading is read in
    the draw phase, so turning the phone redraws the circle without recomposing.
  - There is no foreground service until a feature like track logging needs one.
  - Recomposition is kept in leaf composables, OVERWATCH's PulseVisuals lesson, so the
    pulse never redraws the whole circle.

## 5. Milestones

| Version | Contents |
|---|---|
| **v0.0** ✓ 2026-11-02 | Scaffold: Gradle (copied from OVERWATCH), LICENSE, README, `.gitignore`, CI (build + unit tests on every push; APK attached to a Release on `v*` tags). `gh repo create KaraZajac/TPS --public`. First green build. |
| **v0.1.0** ✓ released 2026-11-02 | The sky circle, the Constellations sheet, position readout, START/STOP, permission flow, settings (theme/units/coordinates), MSL height, demo sky, launcher icon (sky-plot glyph on OVERWATCH's dark background), unit tests. Tested on Kara's phone. |
| **v0.2.0** built 2026-11-02, tested on Kara's phone | Starts on its own (no START/STOP), satellite time, geohash, waypoints (paste a list, add where you are) and navigation to them on the ring, heading-up compass as a setting. |
| **v0.2.1** built 2026-11-02, awaiting the phone test | Kibernetyk Mono everywhere, heading-up by default, a star for the waypoints, a label dialog for Add where I am. |
| **v0.3.0** step 1 built 2026-11-02 (0.3.0-dev); own fixes on Kara's phone from the chip's satellite positions (0.3.0-dev.2, tested outdoors); saving recordings (0.3.0-dev.3); the super position as the main position (0.3.0-dev.4) | **Own fixes, step 1** (§8): CHIP card with the capabilities and live raw-data status, a raw-data recorder (GnssLogger format, shared from the phone), pseudoranges, GPS and Galileo navigation-message decoding, and per-constellation fixes next to the chip's. "Unsupported vs not in view" on the cards. Kara's CHIP card (Sony XQ-CT62, Android 14, Qualcomm): raw measurements yes, navigation messages no, satellite PVT yes. So **0.3.0-dev.2** adds own fixes from the chip's satellite positions, which covers GPS, Galileo and GLONASS on that phone. |
| **v0.4.0** | **Own fixes, step 2:** GLONASS and BeiDou decoding, the "each nation's answer" plot, and the **integrity check** (§8): own fixes vs the chip's, AGC per band, C/N0 flattening, clock and position jumps, with honest limits. Then per-band detail, satellite trails, cold start / TTFF test. |
| **Later** | tps.netslum.io site, F-Droid submission, GPX/NMEA logging. |

## 6. Testing

- **Unit tests** run in CI. They cover:
  - band classification, including GLONASS channels −7…+6
  - SVID labels
  - merging dual-band signals
  - NMEA GSA/GGA parsing, including checksums
  - sky projection, including heading rotation
  - fix tiers
  - geohash encode/decode, distance and bearing, the waypoint list parser, heading from a
    rotation matrix, and the clock and distance formats (v0.2)
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

## 8. Own fixes and integrity (v0.3–v0.4)

**Goal.** TPS computes its own position from the satellites' signals, once per
constellation (GPS-only, Galileo-only, …) and once for all of them, and shows each next
to the chip's fused fix. That's the TERRAN idea made literal, *each nation's answer*, and a
cross-check: a spoofer has to fool every system consistently, and TPS's own solution
doesn't share the chip's filtering or assistance data.

**What it needs from the phone** (shown on the CHIP card):
- **Raw measurements** (`GnssMeasurementsEvent`, API 24; capability flag API 31). From
  `GnssClock` and each `GnssMeasurement`: receive time `TimeNanos − (FullBiasNanos +
  BiasNanos) + TimeOffsetNanos`, transmit time `ReceivedSvTimeNanos` (time of week; BeiDou
  is 14 s behind GPS time, GLONASS gives time of day in Moscow time), and their difference
  × c is the pseudorange. Usable only with the time-of-week (or GLONASS time-of-day)
  decoded or known, per the `State` bits.
- **Where each satellite is.** TPS has no network for downloaded orbits. Two sources, in
  this order:
  1. **Broadcast navigation messages** TPS decodes itself (`GnssNavigationMessage`, API 24):
     GPS and QZSS L1 C/A subframes 1–3 (with the IS-GPS-200 parity check), Galileo I/NAV
     word types 1–5 (CRC-24Q), then GLONASS strings 1–4 (state vectors, integrated with
     RK4) and BeiDou D1.
  2. **The chip's own satellite positions** (`SatellitePvt`, Android 12+): position and
     velocity at the signal's transmit time, the satellite clock correction (time
     correction minus hardware code bias, in metres), the chip's ionosphere and
     troposphere estimates, and from Android 13 the orbit's reference times, issue-of-data
     numbers and source (broadcast, assistance server or long-term prediction). It's a
     system API, but Android 14's `GnssMeasurementsProvider` delivers the same event to every
     app with precise location, and the measurement's public `writeToParcel` writes it. So
     TPS serializes each measurement and parses it out (`ChipPvtParser`): it finds the
     class name, then reads the flags, three nested objects, two delays and five Android 13
     fields (AOSP 14, 16 and 17 are identical; checked against the android17-release and main
     sources, where `getSatellitePvt` is still a system API). It uses only public APIs: no reflection, no
     hidden-API exemptions. An instrumented test (`ChipPvtDeviceTest`) has Android's own
     `GnssMeasurement.CREATOR` read the layout back on Android 14 and 16 emulators. This is
     how phones without navigation messages, like most Qualcomm ones, get own fixes, GLONASS
     included.
  - The chip's positions are less independent than decoded or imported orbits: they come
    from the chip's own orbit data, so a spoofer broadcasting false orbits would fool
    both. Comparing constellations still catches a spoofer that fakes only some of them.
  - Not chosen for now: importing a daily orbit file. BKG's IGS data centre publishes a
    multi-GNSS RINEX 3.05 file openly, about 1.2 MB, rebuilt every 15 minutes. It would
    need a browser download before each outing, and would still need no network
    permission.
- Every orbit, decoded or from the chip, is checked against the chip's sky: the
  satellite's azimuth and elevation computed from it must match `GnssStatus` within 3°,
  or it's dropped. The chip's positions must also be physically possible (medium Earth
  to geostationary orbit, sane clock and delays).

**The super position (0.3.0-dev.4).** An extended Kalman filter over every satellite of
every system: position, velocity, one receiver clock and its drift, and an offset per
system (inter-system bias). The chip's own inter-system biases (`FullInterSignalBiasNanos`)
are applied first when it reports them (on Kara's phone they matched TPS's own estimates to
within 6 m); the filter learns what's left, or the whole offset when the chip doesn't say.
So any mix of satellites counts. Pseudoranges are re-referenced to the chip's clock offset
at the start, so the clock simply drifts, the drift the Doppler measures: this works
whether or not the chip steers its clock. Doppler (pseudorange rate) measures velocity and
drift. Each measurement is gated at 5σ against the prediction; a common jump of all
pseudoranges (or all rates) is taken as a clock jump and absorbed; after three rounds with
most pseudoranges rejected, or a clock discontinuity, or 30 s without data, it restarts
from a snapshot fix. Motion noise follows the smoothed horizontal speed (standing, walking,
driving). It's shown once it has had three updates, its last within 5 s, and claims 100 m
or better. On Kara's recording (2 minutes, GPS + Galileo + GLONASS, 2–15 signals a
second) it stayed within 3.3 m of the chip's fix typically and 8.0 m 95% of the time,
against 18 m and 79 m for the per-second snapshot; after 30 s, one to three satellites a
second held it within about 10 m. `RecordingReplayTest` replays a recording through the
real pipeline (pass one with `-Ptps.recording=`; recordings never enter the repo).

**The solver.** Iterative weighted least squares on the pseudoranges: position plus a
clock term per constellation; satellite clock (af0–af2, relativity, group delay), Earth
rotation during the signal's flight, Klobuchar ionosphere (from GPS subframe 4), a
standard troposphere, a 10° mask, C/N0 weights. At least 4 satellites per constellation.
Results: position, offset from the chip's fix (east/north/up), residuals, DOP.

**Robustness (0.3.0-dev.5)**, from Kara's recordings: far less vertical than horizontal
motion noise (5%), so a noisy vertical speed can't carry the height away (it had shown
"−30 m MSL"); a cold start from a snapshot is redone from the chip's fix once the chip is
within 20 m (weak-signal snapshots were 50–80 m off sideways and 100–145 m in height); with
the chip's inter-system biases the start uses one clock and the leftover offsets begin at 0;
pseudorange noise calibrated so the innovations' NIS averages ~1 (it was 0.35: TPS had been
claiming ±6–9 m when its spread said ±4–5); shown only once settled (10 updates, ±15 m),
then held through up to 30 s without measurements (±50 m); a hardware clock reset re-learns
the clock without losing the position. Replays: 3.9 / 2.5 / 5.4 m typical from the chip's
fix on three recordings, 95% within 7.7 / 7.2 / 9.7 m, no false alarms. Height is the weak
part: 3–26 m from the chip's. In Kara's basement (18 dB-Hz, the chip passing on about 2
satellites a round, its own fix ±33–139 m) TPS rightly stays off: the few snapshots there
were ~400 m out, and nothing was raised.

**Integrity (0.3.0-dev.5, extended in dev.7).** `IntegrityMonitor`, each check worded with
what else it could be, findings held 20 s (caution) or 30 s (alert) after their cause:
- *systems agree* (alert): one system's pseudoranges mostly rejected by the super position
  for 3 rounds while others give at least 4: a fake signal for one system;
- *position jumps* (alert): a restart moving TPS's shown position more than 200 m + 50 m/s ×
  the gap, or the chip's fix jumping more than 500 m + 50 m/s × the gap;
- *since last time* (alert, held 2 min, dev.7): TPS remembers where and when it last
  trusted its fix (every 15 s while every check passes; one record in SharedPreferences,
  backups off). At the next session's first position (1 km or better): within the same
  boot, GNSS time must match what the phone's monotonic clock counted, to 1 s + 100 ppm
  of the gap (a sleeping phone's crystal); after a reboot, GNSS time mustn't run
  backwards; and the position mustn't have moved more than 2 km beyond its accuracy
  faster than 400 m/s (an airliner with a jet stream). This catches an attack that was
  already running when TPS opened, which the in-session checks can't;
- *GNSS time* (alert): the chip's fix time against the phone's monotonic clock moving more
  than 0.5 s (it holds to ~15 ms on Kara's phone), or more than 5 s off Android's network
  time (API 33); caution beyond 2 s off network time (not 1 s: Android refreshes network
  time every 18–24 h and counts on with the phone's clock in between, and some chips
  stamp their fix a few hundred ms late), or beyond 60 s off the wall clock without one;
- *position source* (alert, dev.7): Android marks the chip's fix as a mock location
  (another app supplies it). A mock fix also no longer starts or checks TPS's own position;
- *signal strength*: every system's C/N0 down 10 dB at once (or half the satellites lost)
  for 3 rounds. With the receiver gain reported: gain off by 3 dB in 2 of the last 3
  rounds, alert (interference); within 3 dB in all 3, no warning (the sky got blocked: no
  extra power in the band); anything between, caution, as without gain (dev.8: on Kara's
  Sony single-round dips reach 2.97 dB, so one round mustn't decide). Without gain:
  caution, jamming or a blocked sky, and after 30 rounds the new level
  becomes normal (indoors now), so the caution lasts about a minute, not as long as the
  phone stays indoors; interference the gain confirms never becomes normal. Every signal
  up 6 dB at once: alert with the gain moved; else caution only at a median of 42 dB-Hz
  or more, an overpowering transmitter (walking out of a building raises every signal
  too, but only to the ~30–38 dB-Hz phones get from orbit);
- *receiver gain* (when reported): 6 dB from its baseline, either way (phones disagree on
  the sign). Read from the API 33 list, or, when the chip leaves that empty, from the
  deprecated per-measurement field Android names as the fallback (grouped by band:
  GLONASS's FDMA carriers share one). Kara's Sony fills that field (dev.7 recording,
  indoors): one value per system and band, shared by every satellite in it, steady to
  ±0.7 dB over 5 minutes; GPS L1 about −64 dB and Galileo E1 about −68 dB on the same
  frequency, so the key is system and band, not frequency alone. In the basement 20
  minutes later (2 satellites tracked a second instead of 6, no fix from the chip) the
  gain was within 0.6 dB of those values (GLONASS 1.3 dB, from 6 readings): blocking the
  sky doesn't move it, which is what lets steady gain mean "blocked", not "jammed";
- *signal pattern*: 8+ signals within ±2 dB for 10 rounds, or a median above 48 dB-Hz;
- *receiver clock*: a common jump of every pseudorange without a reported reset;
- *measurements fit*: 30% of pseudoranges rejected for 5 rounds;
- *TPS vs chip*: further apart than 50 m and four times their combined accuracy for 5 rounds.
Checked against staged attacks on a synthetic sky (`IntegrityTest`: a fake GPS signal is
caught within 2 s and TPS stays with Galileo and GLONASS; a whole-sky takeover shows as a
5 km jump; a 3 s time shift, jamming with and without AGC, with the gain moving either
way, a blocked sky with steady gain, an overpowering or one-antenna transmitter, a clock
jump, a start 500 km from ten minutes ago, GNSS time 30 s off the phone's count, time
running backwards across a reboot, network time 3 s and 8 s off, a mock location; a clean
sky and a normal restart stay quiet) and on Kara's recordings (no false alarms). On an
Android 14 emulator the start-up alert and the mock-location alert show end to end. On
Kara's indoor recording with real gain readings, a staged 10 dB drop with the gain steady
raises nothing, and the same drop with the gain moved 8 dB raises the interference alert.
Phones are not survey receivers: this catches crude spoofing and jamming, not a careful
attacker.

**Noise calibration (0.3.0-dev.7).** NIS per system on Kara's three outdoor recordings
showed GPS ~1, Galileo 0.07–0.24 (its BOC signal ranges better than the chip's reported
uncertainty says) and GLONASS 1.3–2.3 at the old 1.5×; Doppler NIS 0.26–0.73. Pseudorange
noise is now 0.5× for Galileo (measured 0.4, held back) and 2× for GLONASS, relative to
GPS; Doppler noise is 0.65× the chip's uncertainty, floored at 0.2 m/s. NIS now: GPS
0.85–1.23, Galileo 0.21–0.60, GLONASS 0.72–1.32, Doppler 0.45–1.04. Signals the chip flags
for multipath get 2× noise (down-weighted, not dropped); the Sony never sets the flag (0 of
3,383 measurements). A code-against-Doppler check (the slope of each satellite's
pseudorange minus its integrated Doppler, pooled per system: `FusedFix.codeMinusDopplerMps`)
reads within ±0.25 m/s on four recordings, so the Sony's Doppler needs no correction.
Replays from the chip's fix, median / 95% / max: 4.2 / 6.4 / 7.3 m, 2.3 / 5.9 / 7.6 m and
3.9 / 10.1 / 12.1 m (before: 3.9 / 7.7 / 9.2, 2.5 / 7.2 / 7.5, 5.4 / 9.7 / 14.0). Indoors
(dev.7 recording: 17–20 dB-Hz, usually 2 satellites a second) TPS settled on the rounds
with 4 or more and showed its position for 114 of 274 s, 10 m from the chip's typically
and 19 m at most, claiming ±21 m (the chip ±28 m). The replay test now checks, for every
recording, that whatever TPS shows stays within twice its own and the chip's combined
claims (it held in every round of all six recordings). Height
moved by a few metres, within its noise; it is still the weak part. Without ground truth
these are distances from the chip, not errors.

**Resisting, not just detecting (0.3.0-dev.9).**
- *Robust update*: within the 5σ gate, pseudoranges and Doppler more than 1.5σ off the
  solution count for less (Huber weights, k/|e|), re-solved from the same prediction up to
  three times. Convex, so it can't run away. Synthetic sky with every fourth satellite 8 m
  long: mean error 2.89 → 2.28 m; a clean sky loses nothing (0.41 → 0.35 m). Kara's
  recordings: median distance from the chip 3.9 / 2.2 / 3.3 m (was 4.2 / 2.3 / 3.9), worst
  within a metre either way; about one measurement a second weighed less outdoors, none
  indoors (where the noise model is about 3× too cautious).
- *Motion sensors* (`MotionDetector`): accelerometer and gyroscope at 50 Hz, no permission.
  Still: for 2 s, every accelerometer axis within 0.05 m/s² of its mean and rotation under
  0.02 rad/s. Per axis, because a smooth acceleration tilts gravity without changing its size.
  Recordings get `Motion` lines.
- *Static mode*: still, with the signals barely moving (under 0.5 m/s), the velocity is held
  at zero (σ 0.05 m/s). Synthetic height RMS 1.14 → 0.25 m. At rest the position also holds
  through a loss of signal with no 30 s limit.
- *Holding, out of reach*: when most measurements stop fitting, the filter restarts only at a
  place within reach: 100 m + 3σ + 50 m/s for each second the phone may have moved since the
  last good update (none while the sensors feel still; all of them without sensors). Else it
  holds: the position stays (shown while ±100 m or better), only the clock follows the
  signals, and the hold ends when they fit the held position again (3 rounds, 3 in 4 inside
  the gate, median NIS ≤ 2) or the new place comes within reach. TPS then takes it, with a
  5-minute caution that it may be false.
- *Holding, at rest*: where the phone came to rest (sensors still, signals under 0.5 m/s,
  TPS's position shown) is kept across restarts until the sensors feel motion or a 30 s gap.
  The filter mustn't wander from it beyond max(25 m, 3σ then), nor the signals' own snapshot
  beyond max(25 m, 3σ then, 3 × its HDOP × residual RMS); otherwise TPS goes back there and
  holds. A disagreement within that margin just re-centres it there.
- Warnings: *motion agrees* (new): caution below 200 m, which reflections can manage indoors,
  alert beyond; *position jumps*: out of reach (alert beyond 200 m), and the caution when a
  hold ends by following. The main screen says "position · TPS · holding".
- Synthetic results (`SuperPositionTest`, `IntegrityTest`): a 5 km whole-sky takeover is held
  for as long as the phone lies still; without motion sensors it's held ~90 s, then followed
  with the caution; a 1 m/s drag at rest pulls TPS at most ~25 m before it snaps back and
  holds (caution, alert past 200 m); a spoofer that stops ends the hold quietly; picked up
  mid-hold, TPS lets go once the place is within reach; a smooth 20 m/s ride with the
  sensors feeling still triggers nothing, because rest needs the signals to agree first.
- First real motion data (Kara's dev.9 bedroom recording, 160 s, 24 dB-Hz, about 5
  satellites a second): at rest the Sony's accelerometer reads 0.010–0.013 m/s² (largest
  axis) and its gyro about 0.002 rad/s, so the thresholds sit about five times above that.
  "At rest" in 136 of 160 s (moving when the phone was set down, and at one bump); static mode
  on in 115 of the 125 rounds TPS was shown, vertical speed 0.0 m/s; TPS 7.4 m from the
  chip's fix typically (16 m at most, the chip claiming ±16 m), within its claims throughout;
  no hold, no warning. The replay test now counts "shown" from TPS's first fix and scales its
  accuracy limits to the chip's own claims.
- Limits: a drag that starts while the phone moves can't be told from real motion; a very
  smooth start with the phone perfectly still (a ship) could read as a drag, and the warning
  says so; hand-held and pocket readings haven't been recorded yet.

**Barometer (0.3.0-dev.10).** Where the phone has one (`TYPE_PRESSURE`, no permission, 5 Hz
averaged each second), TPS's height is the barometer's standard-atmosphere height plus an
offset: the running mean of TPS's height minus the barometer's over up to 10 minutes, taken
while TPS trusts its fix and weighted by its vertical uncertainty (`BaroHeight`, kept apart from
the super position). Every change in height comes from the barometer within a second; the GNSS
height's reflection errors, which last minutes, average down; the weather moves the offset and
the mean follows minutes behind. A difference more than 5σ (at least 30 m) from the mean for 3
rounds (a plane's cabin, not a climb) starts the mean over, and no barometer height shows
meanwhile. Its ± comes from how much TPS's height actually wanders around the mean, because
TPS's own vertical claim knows nothing of minute-long reflections (on the synthetic sky with
them it claims ±1–2 m while 10 m off).
- Tried first and dropped: the offset as a state of the super position. That filter treats GNSS
  errors as independent from second to second, so it grew confident within seconds and locked in
  that moment's reflection error, 10–18 m off for minutes on the synthetic sky.
- Synthetic results, barometer height against TPS's own in the same runs (`BarometerTest`,
  `SuperPositionTest`): weak signals with minute-long reflections, a 30 m climb 2.0 m RMS against
  8.4 m, stairs 3.1 against 8.4 m; standing on a clean or weak sky, about half the spread;
  standing in heavy reflections, as good as TPS's height averaged over minutes (better after
  ~10 minutes, no better at first); a 30 hPa jump shows no height until a fresh start; a 3 hPa/h
  front costs ~2 m.
- The main screen's height and its ± are the barometer's where there is one; the CHIP card says
  whether the phone has a barometer and whether it's steadying TPS's height; OWN FIXES adds
  "baro"; recordings get `Baro` lines and the replay test feeds them back. Whether Kara's Sony
  has a barometer, the CHIP card will say.

**Recording.** A recorder writes the raw data (GnssLogger's text format: `Raw`, `Nav`,
`Fix`, `Status` lines); **Save** copies it to `Download/TPS` (MediaStore, no permission;
the system's "Save as" picker on Android 8–9). TPS adds `SatPvt`
lines, the chip's satellite positions, which GnssLogger doesn't record; the receiver gain
(the per-measurement `AgcDb` column on every Android version, the API 33 list as `Agc`
lines); a note when full tracking turns on or off (Android 14+); and `Integrity` lines
when TPS's warning changes, so a recording shows what the screen said. It's how the
decoders get checked against real phones. The file contains your position, and TPS says so.

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
