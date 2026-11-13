"""Independent reference GNSS maths for auditing TPS: written from the ICDs, not from the app.

Broadcast orbits (IS-GPS-200, Galileo OS SIS ICD, GLONASS ICD) from RINEX 3 navigation files,
Klobuchar, Saastamoinen, and a plain weighted least-squares fix. Prints no coordinates.
"""
import math
import numpy as np

C = 299_792_458.0
OMEGA_E = 7.2921151467e-5
MU_GPS = 3.986005e14
MU_GAL = 3.986004418e14
WEEK = 604800.0
F_REL = -4.442807633e-10          # s / sqrt(m), IS-GPS-200
F_REL_GAL = -4.442807309e-10


def f(s):
    s = s.strip()
    return float(s.replace('D', 'e').replace('d', 'e')) if s else 0.0


def gps_seconds(y, mo, d, h, mi, s):
    """Seconds since 1980-01-06 of a calendar time on a continuous scale (no leap seconds)."""
    import datetime
    days = (datetime.date(y, mo, d) - datetime.date(1980, 1, 6)).days
    return days * 86400.0 + h * 3600 + mi * 60 + s


class Nav:
    def __init__(self):
        self.kepler = {}    # ('G'|'E', prn) -> list of dict
        self.glo = {}       # prn -> list of dict
        self.klob = None    # (alpha[4], beta[4])
        self.leap = 18

    def load(self, path):
        with open(path) as fh:
            lines = fh.read().split('\n')
        i = 0
        alpha = beta = None
        while 'END OF HEADER' not in lines[i]:
            l = lines[i]
            if l[60:].startswith('IONOSPHERIC CORR'):
                if l.startswith('GPSA'):
                    alpha = [f(l[5 + 12 * k:17 + 12 * k]) for k in range(4)]
                if l.startswith('GPSB'):
                    beta = [f(l[5 + 12 * k:17 + 12 * k]) for k in range(4)]
            if l[60:].startswith('LEAP SECONDS'):
                self.leap = int(l[:6])
            i += 1
        i += 1
        if alpha and beta and self.klob is None:
            self.klob = (alpha, beta)
        nlines = {'G': 8, 'E': 8, 'C': 8, 'J': 8, 'I': 8, 'R': 4, 'S': 4}
        while i < len(lines):
            l = lines[i]
            if len(l) < 23 or l[0] not in nlines:
                i += 1
                continue
            sysc = l[0]
            n = nlines[sysc]
            block = lines[i:i + n]
            i += n
            try:
                prn = int(l[1:3])
                y, mo, d, h, mi, s = (int(l[4:8]), int(l[9:11]), int(l[12:14]), int(l[15:17]), int(l[18:20]), int(l[21:23]))
                a = [f(l[23:42]), f(l[42:61]), f(l[61:80])]
                v = []
                for b in block[1:]:
                    b = b.ljust(80)
                    v += [f(b[4:23]), f(b[23:42]), f(b[42:61]), f(b[61:80])]
            except ValueError:
                continue
            toc = gps_seconds(y, mo, d, h, mi, s)
            if sysc in ('G', 'E'):
                e = dict(sys=sysc, prn=prn, toc=toc, af0=a[0], af1=a[1], af2=a[2],
                         iode=v[0], crs=v[1], dn=v[2], m0=v[3], cuc=v[4], e=v[5], cus=v[6], sqrta=v[7],
                         toe=v[8], cic=v[9], om0=v[10], cis=v[11], i0=v[12], crc=v[13], w=v[14], omd=v[15],
                         idot=v[16], src=v[17], week=v[18], acc=v[20], health=v[21], tgd=v[22], tgd2=v[23])
                e['toe_abs'] = e['week'] * WEEK + e['toe']
                if sysc == 'E':
                    src = int(e['src'])
                    # I/NAV clock (af0..af2 for E5b,E1) only: what an E1 receiver decodes.
                    if not (src & 0x200):
                        continue
                    e['tgd'] = e['tgd2']      # BGD(E1,E5b)
                self.kepler.setdefault((sysc, prn), []).append(e)
            elif sysc == 'R':
                # Epoch is UTC; store as "GPS-like seconds" of the UTC calendar time.
                e = dict(prn=prn, toe_utc=toc, clk=a[0], gamma=a[1],
                         pos=np.array([v[0], v[4], v[8]]) * 1e3, vel=np.array([v[1], v[5], v[9]]) * 1e3,
                         acc=np.array([v[2], v[6], v[10]]) * 1e3, health=v[3], k=int(v[7]))
                self.glo.setdefault(prn, []).append(e)

    def pick(self, sysc, prn, t_abs):
        """The healthy ephemeris nearest in time (reference time) to GPS seconds t_abs."""
        best = None
        for e in self.kepler.get((sysc, prn), ()):
            if e['health'] != 0 and sysc == 'G':
                continue
            dt = abs(t_abs - e['toe_abs'])
            if best is None or dt < best[0]:
                best = (dt, e)
        return best[1] if best and best[0] < 4 * 3600 else None

    def pick_glo(self, prn, t_utc_abs):
        best = None
        for e in self.glo.get(prn, ()):
            if e['health'] != 0:
                continue
            dt = abs(t_utc_abs - e['toe_utc'])
            if best is None or dt < best[0]:
                best = (dt, e)
        return best[1] if best and best[0] < 1800 else None


def tdiff(t, t0):
    """t - t0, where t is absolute seconds or (reference seconds, offset) for full precision."""
    return (t[0] - t0) + t[1] if isinstance(t, tuple) else t - t0


def kepler_E(e, tk):
    mu = MU_GAL if e['sys'] == 'E' else MU_GPS
    a = e['sqrta'] ** 2
    n = math.sqrt(mu / a ** 3) + e['dn']
    m = e['m0'] + n * tk
    ek = m
    for _ in range(40):
        ek2 = m + e['e'] * math.sin(ek)
        if abs(ek2 - ek) < 1e-15:
            ek = ek2
            break
        ek = ek2
    return ek


def kepler_clock(e, t_abs):
    """Satellite clock offset for an L1/E1 single-frequency user, seconds (incl. relativity, minus group delay)."""
    dt = tdiff(t_abs, e['toc'])
    ek = kepler_E(e, tdiff(t_abs, e['toe_abs']))
    rel = (F_REL_GAL if e['sys'] == 'E' else F_REL) * e['e'] * e['sqrta'] * math.sin(ek)
    poly = e['af0'] + e['af1'] * dt + e['af2'] * dt * dt
    return poly + rel - e['tgd'], poly + rel, e['tgd']


def kepler_pos(e, t_abs):
    """ECEF position at GPS seconds t_abs, in the Earth-fixed frame of that instant."""
    tk = tdiff(t_abs, e['toe_abs'])
    a = e['sqrta'] ** 2
    ek = kepler_E(e, tk)
    nu = math.atan2(math.sqrt(1 - e['e'] ** 2) * math.sin(ek), math.cos(ek) - e['e'])
    phi = nu + e['w']
    s2, c2 = math.sin(2 * phi), math.cos(2 * phi)
    u = phi + e['cus'] * s2 + e['cuc'] * c2
    r = a * (1 - e['e'] * math.cos(ek)) + e['crs'] * s2 + e['crc'] * c2
    inc = e['i0'] + e['idot'] * tk + e['cis'] * s2 + e['cic'] * c2
    om = e['om0'] + (e['omd'] - OMEGA_E) * tk - OMEGA_E * e['toe']
    xp, yp = r * math.cos(u), r * math.sin(u)
    return np.array([xp * math.cos(om) - yp * math.cos(inc) * math.sin(om),
                     xp * math.sin(om) + yp * math.cos(inc) * math.cos(om),
                     yp * math.sin(inc)])


GLO_MU = 3.9860044e14
GLO_AE = 6378136.0
GLO_J2 = 1.0826257e-3
GLO_OMEGA = 7.292115e-5


def _glo_deriv(s, acc):
    x, y, z, vx, vy, vz = s
    r2 = x * x + y * y + z * z
    r = math.sqrt(r2)
    a = 1.5 * GLO_J2 * GLO_MU * GLO_AE ** 2 / r ** 5
    b = 5 * z * z / r2
    mu3 = GLO_MU / r ** 3
    w = GLO_OMEGA
    return np.array([vx, vy, vz,
                     (-mu3 - a * (1 - b) + w * w) * x + 2 * w * vy + acc[0],
                     (-mu3 - a * (1 - b) + w * w) * y - 2 * w * vx + acc[1],
                     (-mu3 - a * (3 - b)) * z + acc[2]])


def glo_pos(e, t_utc_abs):
    dt = tdiff(t_utc_abs, e['toe_utc'])
    s = np.concatenate([e['pos'], e['vel']])
    step = 30.0 if dt > 0 else -30.0
    while abs(dt) > 1e-9:
        h = step if abs(dt) > abs(step) else dt
        k1 = _glo_deriv(s, e['acc'])
        k2 = _glo_deriv(s + k1 * h / 2, e['acc'])
        k3 = _glo_deriv(s + k2 * h / 2, e['acc'])
        k4 = _glo_deriv(s + k3 * h, e['acc'])
        s = s + (k1 + 2 * k2 + 2 * k3 + k4) * h / 6
        dt -= h
    return s[:3], s[3:]


def glo_clock(e, t_utc_abs):
    return e['clk'] + e['gamma'] * tdiff(t_utc_abs, e['toe_utc'])


# ---------------------------------------------------------------- geodesy

A_WGS = 6378137.0
F_WGS = 1 / 298.257223563
E2 = F_WGS * (2 - F_WGS)


def to_ecef(lat, lon, h):
    la, lo = math.radians(lat), math.radians(lon)
    n = A_WGS / math.sqrt(1 - E2 * math.sin(la) ** 2)
    return np.array([(n + h) * math.cos(la) * math.cos(lo), (n + h) * math.cos(la) * math.sin(lo), (n * (1 - E2) + h) * math.sin(la)])


def to_geodetic(p):
    """Bowring/iterative; returns degrees, degrees, metres."""
    x, y, z = p
    lon = math.atan2(y, x)
    r = math.hypot(x, y)
    lat = math.atan2(z, r * (1 - E2))
    for _ in range(10):
        n = A_WGS / math.sqrt(1 - E2 * math.sin(lat) ** 2)
        h = r / math.cos(lat) - n
        lat = math.atan2(z, r * (1 - E2 * n / (n + h)))
    n = A_WGS / math.sqrt(1 - E2 * math.sin(lat) ** 2)
    h = r / math.cos(lat) - n
    return math.degrees(lat), math.degrees(lon), h


def enu_matrix(lat, lon):
    la, lo = math.radians(lat), math.radians(lon)
    return np.array([[-math.sin(lo), math.cos(lo), 0],
                     [-math.sin(la) * math.cos(lo), -math.sin(la) * math.sin(lo), math.cos(la)],
                     [math.cos(la) * math.cos(lo), math.cos(la) * math.sin(lo), math.sin(la)]])


def az_el(rx, sat):
    lat, lon, _ = to_geodetic(rx)
    d = enu_matrix(lat, lon) @ (sat - rx)
    return math.atan2(d[0], d[1]) % (2 * math.pi), math.atan2(d[2], math.hypot(d[0], d[1]))


def klobuchar(alpha, beta, lat, lon, az, el, tow):
    """IS-GPS-200 20.3.3.5.2.5, metres on L1."""
    e = el / math.pi
    psi = 0.0137 / (e + 0.11) - 0.022
    phi = lat / 180 + psi * math.cos(az)
    phi = max(-0.416, min(0.416, phi))
    lam = lon / 180 + psi * math.sin(az) / math.cos(phi * math.pi)
    phim = phi + 0.064 * math.cos((lam - 1.617) * math.pi)
    t = (4.32e4 * lam + tow) % 86400
    fs = 1 + 16 * (0.53 - e) ** 3
    amp = max(0.0, sum(alpha[k] * phim ** k for k in range(4)))
    per = max(72000.0, sum(beta[k] * phim ** k for k in range(4)))
    x = 2 * math.pi * (t - 50400) / per
    d = fs * (5e-9 + amp * (1 - x * x / 2 + x ** 4 / 24)) if abs(x) < 1.57 else fs * 5e-9
    return d * C


def saastamoinen(lat, h, el, humidity=0.7):
    """RTKLIB's tropmodel: standard atmosphere, metres."""
    if h < -100 or h > 1e4 or el <= 0:
        return 0.0
    hgt = max(h, 0.0)
    pres = 1013.25 * (1 - 2.2557e-5 * hgt) ** 5.2568
    temp = 15.0 - 6.5e-3 * hgt + 273.16
    e = 6.108 * humidity * math.exp((17.15 * temp - 4684.0) / (temp - 38.45))
    z = math.pi / 2 - el
    trph = 0.0022768 * pres / (1 - 0.00266 * math.cos(2 * math.radians(lat)) - 0.00028 * hgt / 1e3) / math.cos(z)
    trpw = 0.002277 * (1255.0 / temp + 0.05) * e / math.cos(z)
    return trph + trpw


def geometric_range(sat, rx):
    """Range with the Earth-rotation (Sagnac) term, for a satellite position in the frame of transmit time."""
    return np.linalg.norm(sat - rx) + OMEGA_E * (sat[0] * rx[1] - sat[1] * rx[0]) / C


def wls(sats, prs, sig, groups, x0, iters=10):
    """Weighted least squares. groups: clock index per measurement. Returns (pos, clocks, residuals, cov)."""
    ng = max(groups) + 1
    x = np.concatenate([np.array(x0, float), np.zeros(ng)])
    w = 1 / np.asarray(sig) ** 2
    for _ in range(iters):
        H = np.zeros((len(prs), 3 + ng))
        v = np.zeros(len(prs))
        for k, (s, pr, g) in enumerate(zip(sats, prs, groups)):
            d = s - x[:3]
            H[k, :3] = -d / np.linalg.norm(d)
            H[k, 3 + g] = 1
            v[k] = pr - (geometric_range(s, x[:3]) + x[3 + g])
        N = H.T @ (H * w[:, None])
        try:
            dx = np.linalg.solve(N, H.T @ (w * v))
        except np.linalg.LinAlgError:
            return None
        x += dx
        if np.linalg.norm(dx[:3]) < 1e-4:
            break
    v = np.array([pr - (geometric_range(s, x[:3]) + x[3 + g]) for s, pr, g in zip(sats, prs, groups)])
    return x[:3], x[3:], v, np.linalg.inv(N)
