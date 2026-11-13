"""Writes a synthetic TPS recording whose truth is known, from an independent forward model.

Satellites: the public broadcast orbits of a real day (IS-GPS-200 / Galileo / GLONASS ICD maths in
gnss.py). Signals: exact light-time solution with the Earth turning during the flight (no Sagnac
shortcut), satellite clocks, group delays, Klobuchar and Saastamoinen delays, a drifting receiver
oscillator and the Android raw-measurement conventions (FullBiasNanos, ReceivedSvTimeNanos, GLONASS
time of day). The receiver stands at, and drives from, a public landmark; the "Fix" lines carry the
truth, and so do the "Truth" lines (to the measurements' own moment), which TruthReplayTest reads.

gen.py brdc.rnx out.txt [options]
  --at 2026-11-03T18:10:00   the first second, GPS time (a day the navigation file covers)
  --seconds N                how long (300)
  --still                    standing instead of the drive (60 s at rest, up to 15 m/s, a turn, a climb)
  --sats N                   a narrower sky: N of the satellites in view, picked by the seed
  --seed N
  --isb                      5 m (GLONASS) and -2 m (Galileo) of system bias the chip doesn't report
  --jump                     the chip's clock steps 2 ms half-way, reported as a discontinuity
  --noatmo, --notropo        the chip gives no ionosphere and troposphere delays, or no troposphere
  --vacuumrate               the Doppler without the atmosphere's own rate of change
  --motion STILL|QUIET|MOVING   what the motion sensors say (else what the truth is doing)
  --noise                    white noise: 5 m / sin(elevation) on the code, 0.15 m/s on the Doppler
  --matched                  weak signals with white noise drawn from TPS's own noise model, and with it:
  --corr SHARE TAU             SHARE of the code noise's sd hangs together over TAU seconds (reflections)
  --satbias SD                 each satellite off by a constant, SD metres (orbit and clock errors)
"""
import sys, math, random
import numpy as np
sys.path.insert(0, __file__.rsplit('/', 1)[0])
import gnss

C = gnss.C
def R(x):
    return repr(float(x))
args = sys.argv[1:]
flags = {a for a in args if a.startswith('--') and not a[2:].isdigit()}
def opts(name, n):
    return [float(x) for x in args[args.index(name) + 1:args.index(name) + 1 + n]] if name in args else None
def opt(name, default):
    return type(default)(args[args.index(name) + 1]) if name in args else default
NOISE, ISB_ERR, JUMP, NOATMO, STILL = ('--noise' in flags), ('--isb' in flags), ('--jump' in flags), ('--noatmo' in flags), ('--still' in flags)
SECONDS = opt('--seconds', 300)
rng = random.Random(opt('--seed', 1))
brdc, out = args[0], args[1]

nav = gnss.Nav(); nav.load(brdc)
import datetime
_at = datetime.datetime.strptime(opt('--at', '2026-11-03T18:10:00'), '%Y-%m-%dT%H:%M:%S')
TREF = gnss.gps_seconds(_at.year, _at.month, _at.day, _at.hour, _at.minute, _at.second)      # GPS time, an exact whole second
FRAC = 0.42001                                        # measurements this far into each second, like the phone
OSC = 7.5e-9                                          # the oscillator's frequency error
LEAP = nav.leap
# Old Faithful, Yellowstone (public landmark).
LAT0, LON0, H0 = 44.4605, -110.8281, 2240.0
ORIGIN = gnss.to_ecef(LAT0, LON0, H0)
M = gnss.enu_matrix(LAT0, LON0)

def S(x):
    x = min(1.0, max(0.0, x)); return 3 * x * x - 2 * x ** 3

def vel_enu(t):
    if STILL: return np.zeros(3)
    speed = 15 * S((t - 60) / 20) * (1 - S((t - 240) / 20))
    heading = math.radians(45 + 90 * S((t - 160) / 20))
    climb = 0.5 * S((t - 90) / 5) * (1 - S((t - 130) / 5))
    return np.array([speed * math.sin(heading), speed * math.cos(heading), climb])

HSTEP = 0.01
_grid = [np.zeros(3)]
for i in range(int((SECONDS + 5) / HSTEP) + 2):
    t0 = i * HSTEP
    _grid.append(_grid[-1] + HSTEP / 6 * (vel_enu(t0) + 4 * vel_enu(t0 + HSTEP / 2) + vel_enu(t0 + HSTEP)))

def pos_enu(t):
    if t <= 0: return np.zeros(3)
    i = int(t / HSTEP); t0 = i * HSTEP; d = t - t0
    return _grid[i] + d / 6 * (vel_enu(t0) + 4 * vel_enu(t0 + d / 2) + vel_enu(t))

def truth(t):
    """ECEF position and velocity at t seconds after TREF."""
    return ORIGIN + M.T @ pos_enu(t), M.T @ vel_enu(t)

class Sat:
    pass

def rot(p, theta):
    c, s = math.cos(theta), math.sin(theta)
    return np.array([c * p[0] + s * p[1], -s * p[0] + c * p[1], p[2]])

def sat_state(sat, t):
    """ECEF position (frame of that instant) and clock for the L1/E1/G1 signal, at t seconds after TREF (GPS scale)."""
    if sat.sys in 'GE':
        full, iono_free, tgd = gnss.kepler_clock(sat.eph, (TREF, t))
        return gnss.kepler_pos(sat.eph, (TREF, t)), full, iono_free, tgd
    tu = (TREF - LEAP, t)
    clk = gnss.glo_clock(sat.eph, tu)
    return gnss.glo_pos(sat.eph, tu)[0], clk, clk, 0.0

def observe(sat, t, rx):
    """The signal arriving at true time t at rx: everything the forward model knows."""
    lat, lon, h = gnss.to_geodetic(rx)
    tau = 0.075
    for _ in range(8):
        p, clk, iono_free, tgd = sat_state(sat, t - tau)
        pr = rot(p, gnss.OMEGA_E * tau)
        rho = np.linalg.norm(pr - rx)
        az, el = gnss.az_el(rx, pr)
        iono = gnss.klobuchar(nav.klob[0], nav.klob[1], lat, lon, az, el, (TREF + t) % 604800) * (1575.42e6 / sat.carrier) ** 2
        tropo = gnss.saastamoinen(lat, h, el)
        tau = (rho + iono + tropo) / C
    o = Sat()
    o.t_tx, o.pos, o.clk, o.iono_free, o.tgd, o.rho, o.iono, o.tropo, o.az, o.el = t - tau, p, clk, iono_free, tgd, rho, iono, tropo, az, el
    o.flight = rho + iono + tropo - C * clk       # what the pseudorange holds besides the receiver's clock
    return o

# The satellites above 5 degrees mid-way.
sats = []
mid = SECONDS / 2
for (sysc, prn), ephs in sorted(nav.kepler.items()):
    e = nav.pick(sysc, prn, TREF + mid)
    if e is None or e['health'] != 0: continue
    s = Sat(); s.sys, s.prn, s.eph, s.carrier, s.ct = sysc, prn, e, 1575.42e6, 1 if sysc == 'G' else 6
    sats.append(s)
for prn in sorted(nav.glo):
    e = nav.pick_glo(prn, TREF - LEAP + mid)
    if e is None: continue
    s = Sat(); s.sys, s.prn, s.eph, s.carrier, s.ct = 'R', prn, e, 1602e6 + e['k'] * 562.5e3, 3
    sats.append(s)
sats = [s for s in sats if observe(s, mid, truth(mid)[0]).el > math.radians(5)]
if '--sats' in args:      # a narrower sky: this many of them, picked by the seed
    sats = sorted(random.Random(opt('--seed', 1) + 1000).sample(sats, min(len(sats), opt('--sats', 12))), key=lambda s: (s.sys, s.prn))
STATE = {'G': 16399, 'E': 23567, 'R': 49359}
ISB_REPORTED = {'G': 0.0, 'E': -1467.38, 'R': 3773.29}                 # ns, as the phone reports them
ISB_TRUE = dict(ISB_REPORTED)
if ISB_ERR:
    ISB_TRUE['R'] += 5.0 / C * 1e9; ISB_TRUE['E'] -= 2.0 / C * 1e9      # 5 m and -2 m the chip doesn't know of

WEEK_NS = 604_800_000_000_000; DAY_NS = 86_400_000_000_000
TREF_NS = int(TREF) * 1_000_000_000
N0 = 7_300_000_000_000
BOOT_MS = 1_700_000_000_000 - 7_300_000        # wall clock minus elapsed realtime
RAW = ('utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos,'
       'DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,'
       'ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,'
       'PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,'
       'AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,'
       'MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos,'
       'FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,'
       'CodeType,ChipsetElapsedRealtimeNanos')
lines = ['# TPS synthetic recording: independent forward model, truth in the Fix lines (GnssLogger format)',
         '# Raw,' + RAW,
         '# Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps,AccuracyMeters,BearingDegrees,UnixTimeMillis,SpeedAccuracyMps,BearingAccuracyDegrees,elapsedRealtimeNanos,VerticalAccuracyMeters',
         '# Status,UnixTimeMillis,SignalCount,SignalIndex,ConstellationType,Svid,CarrierFrequencyHz,Cn0DbHz,AzimuthDegrees,ElevationDegrees,UsedInFix,HasAlmanacData,HasEphemerisData,BasebandCn0DbHz',
         '# SatPvt,utcTimeMillis,TimeNanos,Svid,ConstellationType,CarrierFrequencyHz,Flags,XEcefMeters,YEcefMeters,ZEcefMeters,UreMeters,VxEcefMps,VyEcefMps,VzEcefMps,HardwareCodeBiasMeters,TimeCorrectionMeters,ClockDriftMps,IonoDelayMeters,TropoDelayMeters,TimeOfClockSeconds,TimeOfEphemerisSeconds,IssueOfDataClock,IssueOfDataEphemeris,EphemerisSource']
lines.append('# Truth,TimeNanos,XEcefMeters,YEcefMeters,ZEcefMeters,VxEcefMps,VyEcefMps,VzEcefMps,ClockDriftMps,GalileoBiasMeters,GlonassBiasMeters')
MATCHED = '--matched' in flags
# Part of the code noise that hangs together in time (multipath, tracking): its share of the standard deviation and its memory, s.
CORR = opts('--corr', 2)
# What each satellite's orbit and clock are off by, for the whole recording, metres (sd).
SATBIAS = (opts('--satbias', 1) or [0.0])[0]
MOTION = args[args.index('--motion') + 1] if '--motion' in args else None
FIGURES = {'STILL': '0.01,0.004', 'QUIET': '0.15,0.03', 'MOVING': '1.0,0.3'}
for s_ in sats:
    s_.bias = rng.gauss(0, SATBIAS) if SATBIAS else 0.0
    s_.gm = rng.gauss(0, 1)
SIGMA_NS = 30 if MATCHED else 17 if NOISE else 3          # the chip's claim for its code measurement
RATE_SIGMA = 0.4 if MATCHED else 0.15 if NOISE else 0.05
count = 0
for k in range(SECONDS):
    t = FRAC + k * (1 - OSC)                              # true time of the measurements
    rx, _ = truth(t)
    eps = 25e-9 + 4e-9 * math.sin(0.3 * k)                # the chip's own GPS-time error
    jump = 2_000_000 if (JUMP and k >= 150) else 0        # its clock stepped 2 ms (a hardware discontinuity)
    time_nanos = N0 + k * 1_000_000_000 + jump
    g = (t + eps) * 1e9                                   # the chip's GPS time, ns after TREF
    full_bias = (time_nanos - TREF_NS) - math.ceil(g)
    bias = math.ceil(g) - g
    disc = 3 + (1 if jump else 0)
    fix_t = 0.420 + k
    fix_ms = (int(TREF) + k) * 1000 + 420 + 315_964_800_000 - LEAP * 1000
    wall_ms = fix_ms + 600
    elapsed_ns = (wall_ms - BOOT_MS) * 1_000_000
    p, v = truth(fix_t)
    lat, lon, h = gnss.to_geodetic(p)
    ve = M @ v
    speed = math.hypot(ve[0], ve[1])
    bearing = math.degrees(math.atan2(ve[0], ve[1])) % 360
    lines.append(f'Fix,GPS,{R(lat)},{R(lon)},{R(h)},{R(speed)},1.0,{R(bearing)},{fix_ms},0.1,1.0,{(fix_ms - BOOT_MS) * 1_000_000},1.0')
    lines.append(f'Truth,{time_nanos},{R(rx[0])},{R(rx[1])},{R(rx[2])},' + ','.join(R(x) for x in truth(t)[1]) + f',{R(C * OSC)},'
                 f'{R((ISB_TRUE["E"] - ISB_REPORTED["E"]) * 1e-9 * C)},{R((ISB_TRUE["R"] - ISB_REPORTED["R"]) * 1e-9 * C)}')
    name = MOTION or ('STILL' if STILL else 'MOVING' if np.linalg.norm(truth(t)[1]) > 0.05 else 'QUIET')
    if name in FIGURES: lines.append(f'Motion,{wall_ms},{name},{FIGURES[name]}')
    tow_gps = (time_nanos - full_bias) % WEEK_NS
    tod_glo = (time_nanos - full_bias + 3 * 3_600_000_000_000 - LEAP * 1_000_000_000) % DAY_NS
    rows = []
    for s in sats:
        o = observe(s, t, rx)
        if o.el < math.radians(5): continue
        h_ = 0.05
        a, b = observe(s, t + h_, truth(t + h_)[0]), observe(s, t - h_, truth(t - h_)[0])
        rate = (a.flight - b.flight) / (2 * h_) + C * OSC
        if '--vacuumrate' in flags:        # the Doppler without the atmosphere's own rate of change
            rate -= ((a.iono + a.tropo) - (b.iono + b.tropo)) / (2 * h_)
        pr = o.flight + C * eps + ISB_TRUE[s.sys] * 1e-9 * C
        cn0 = 42.0 if o.el > math.radians(30) else 36.0
        if NOISE:
            sd = 5.0 / max(math.sin(o.el), 0.3)
            pr += rng.gauss(0, sd); rate += rng.gauss(0, 0.15)
        if MATCHED:
            # White noise drawn from TPS's own noise model, on signals as weak as a phone's: is the filter then honest about its ±?
            cn0 = round(22 + 12 * math.sin(o.el) + rng.uniform(-2, 2), 1)
            sd = max(SIGMA_NS * 1e-9 * C, 3 / max(math.sin(o.el), 0.1)) * 10 ** (max(0, 30 - cn0) / 20) * {'G': 1.0, 'E': 0.5, 'R': 2.0}[s.sys] * 0.6
            if CORR:
                rho = math.exp(-1.0 / CORR[1])
                s.gm = rho * s.gm + math.sqrt(1 - rho * rho) * rng.gauss(0, 1)
                pr += sd * (math.sqrt(1 - CORR[0] ** 2) * rng.gauss(0, 1) + CORR[0] * s.gm)
            else:
                pr += rng.gauss(0, sd)
            pr += s.bias
            rate += rng.gauss(0, max(RATE_SIGMA * 0.65, 0.2))
        y = bias + pr / C * 1e9
        r = round(y)
        rx_sv = (tod_glo if s.sys == 'R' else tow_gps) - r
        offs = y - r
        vel = (sat_state(s, o.t_tx + 0.5)[0] - sat_state(s, o.t_tx - 0.5)[0])
        drift = (sat_state(s, o.t_tx + 0.5)[1] - sat_state(s, o.t_tx - 0.5)[1]) * C
        rows.append((s, o, rate, rx_sv, offs, cn0, vel, drift))
    for i, (s, o, rate, rx_sv, offs, cn0, vel, drift) in enumerate(rows):
        lines.append(f'Status,{fix_ms},{len(rows)},{i},{s.ct},{s.prn},{R(s.carrier)},{cn0},{R(math.degrees(o.az))},{R(math.degrees(o.el))},1,1,1,{cn0 - 3}')
    for s, o, rate, rx_sv, offs, cn0, vel, drift in rows:
        lines.append(f'Raw,{wall_ms},{time_nanos},{LEAP},0.0,{full_bias},{R(bias)},50.0,{R(OSC * 1e9)},10.0,{disc},{s.prn},{R(offs)},{STATE[s.sys]},'
                     f'{rx_sv},{SIGMA_NS},{cn0},{R(rate)},{RATE_SIGMA},0,0.0,0.0,{R(s.carrier)},,,,0,,{s.ct},,{cn0 - 3},{R(ISB_REPORTED[s.sys])},5.0,,,C,{elapsed_ns}')
        count += 1
    for s, o, rate, rx_sv, offs, cn0, vel, drift in rows:
        iono = '' if NOATMO else R(o.iono); tropo = '' if (NOATMO or '--notropo' in flags) else R(o.tropo)
        fl = 127 - (2 if iono == '' else 0) - (4 if tropo == '' else 0)
        lines.append(f'SatPvt,{wall_ms},{time_nanos},{s.prn},{s.ct},{R(s.carrier)},{fl},{R(o.pos[0])},{R(o.pos[1])},{R(o.pos[2])},2.0,'
                     f'{R(vel[0])},{R(vel[1])},{R(vel[2])},{R(o.tgd * C)},{R(o.iono_free * C)},{R(drift)},{iono},{tropo},0,0,0,0,3')
open(out, 'w').write('\n'.join(lines) + '\n')
print(f'{out.rsplit("/", 1)[-1]}: {SECONDS} s, {len(sats)} satellites in view ({sum(s.sys == "G" for s in sats)} GPS, {sum(s.sys == "E" for s in sats)} Galileo, {sum(s.sys == "R" for s in sats)} GLONASS), {count} measurements')
