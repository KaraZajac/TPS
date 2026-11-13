# A second opinion on TPS's maths

TPS computes its own position, so its arithmetic needs checking against something that isn't
TPS. This is that something: a small forward model written from the interface documents
(IS-GPS-200, the Galileo OS SIS ICD, the GLONASS ICD), sharing no code with the app. It makes
up a recording whose truth is known to the millimetre; TPS replays it through its real
pipeline and says how far it landed from that truth.

It's a developer's tool. It isn't part of the app and CI doesn't run it. It needs Python 3
with numpy, and a day's broadcast orbits, which the app itself never downloads.

- `gnss.py`: the reference maths. Broadcast orbits and clocks of GPS, Galileo and GLONASS from
  a RINEX 3 navigation file, Klobuchar, Saastamoinen, geodesy.
- `gen.py`: writes the recording, in the format TPS records (GnssLogger's, with `SatPvt` lines)
  plus a `Truth` line for each round. The receiver stands at Old Faithful and drives off.

What `gen.py` does differently from the app, on purpose: it solves each signal's flight time
with the Earth turning underneath (the app uses the Sagnac shortcut), takes the Doppler as the
numerical rate of the whole pseudorange (the app differentiates its model), and builds the
Android fields (`FullBiasNanos`, `ReceivedSvTimeNanos`, GLONASS time of day) forwards from
true time, where the app works backwards from them.

## Running it

A merged broadcast navigation file for the day, from the IGS (for example
`BRDC00IGS_R_20263070000_01D_MN.rnx.gz` for 3 November 2026, under
`https://igs.bkg.bund.de/root_ftp/IGS/BRDC/2026/307/`), unpacked. Any day does, with `--at`.

```sh
python3 tools/reference/gen.py BRDC00IGS_R_20263070000_01D_MN.rnx /tmp/exact.txt
python3 tools/reference/gen.py BRDC00IGS_R_20263070000_01D_MN.rnx /tmp/real.txt \
    --matched --corr 0.5 40 --satbias 2 --isb --sats 12 --seed 12 --seconds 600
./gradlew :app:testDebugUnitTest --tests '*TruthReplayTest*' -Ptps.truth=/tmp/exact.txt:/tmp/real.txt
```

The report is the test's standard output (`app/build/test-results/testDebugUnitTest/`). Each
recording is replayed three ways: with the chip's fix at the truth, 60 m off it, and absent.
`gen.py`'s own header lists the options.

## What it found (November 2026, 0.3.0-dev.12)

- **No noise** (the first command): each second's own fix lands 2 mm from the truth (4 mm at
  most), the super position 4 mm (4 cm at most; 2 cm in height), ground speed within 1 mm/s
  typically. A system offset the chip doesn't report (5 m, −2 m) is learned to the millimetre;
  a 2 ms clock step, reported as a discontinuity, changes nothing.
- **One model error, fixed**: the Doppler model left out the Earth-rotation term's own rate, up
  to 2 mm/s on the velocity, always the same way (`SuperPositionTest.cleanSignalsAtRestLeaveNoSpeed`).
- **The ±**: with white noise drawn from TPS's own noise model the filter's covariance is
  honest. With errors that stay for minutes (the second command: reflections, 2 m of orbit and
  clock error per satellite) TPS was 2 to 3 m off where the covariance said ±1 m. What TPS
  shows now has those errors added (`SuperPosition.SYSTEMATIC_HORIZONTAL_M`); the report's
  "truth within 1×" line is the check.
- **No ionosphere model at all, fixed**: a phone whose chip gives satellite positions but no
  ionosphere delay, and no navigation messages either (`--noatmo`), left TPS with no model:
  5.0 m too high in the morning, 7.6 m in the afternoon (`--at 2026-11-03T21:30:00`), 2.5 m at
  night (`--at 2026-11-03T09:00:00`). TPS now takes a typical set of the broadcast model's
  numbers until it has better (`Klobuchar.TYPICAL`): 0.3 m, 0.5 m and 2 cm. That flatters it,
  since the truth here is the same model with that day's numbers; against the real ionosphere
  the broadcast model itself takes out about half.
