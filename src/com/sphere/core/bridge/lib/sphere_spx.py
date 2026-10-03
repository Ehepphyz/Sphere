"""sphere_spx -- Sphere Physics eXchange, the Python reader.

Standard library only; numpy is used when it is there, for arrays. Sphere
writes PDF sets, event samples and jets into SPX files; this maps them and
reads them in place. The PDF interpolation is the same arithmetic, in the same
order, as Sphere's Java one and LHAPDF's log-bicubic, so the answers agree to
the bit: ":bridge crosscheck" shows it.

    import sphere_spx as spx
    pdf = spx.pdf("CT18NLO")                # $SPHERE_BRIDGE/CT18NLO.spx
    pdf.xfxQ2(21, 1e-3, 1e4)                # member 0
    pdf.uncertainty(21, 1e-3, 1e4)          # Hessian or replicas
    ev = spx.events("events")               # particles, jets, weights
    spx.publish("pt", edges, counts)        # shows up in Sphere's Plots tab
"""

import math
import mmap
import os
import struct
import sys
import time

try:
    import numpy as _np
except ImportError:  # the reader works without it; only .array() needs it
    _np = None

F64, I64, TEXT = 1, 2, 3
_EPS = 2.220446049250313e-16


def bridge_folder():
    return os.environ.get("SPHERE_BRIDGE", "bridge")


def resolve(name):
    """A name, or a path: a bare name is looked for in the bridge folder."""
    if "/" in name or "\\" in name:
        return name
    if name.endswith(".spx"):
        return os.path.join(bridge_folder(), name)
    return os.path.join(bridge_folder(), name + ".spx")


class SPXFile:
    """A mapped SPX file. The views handed out point into it: no copy."""

    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            self._map = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        self._view = memoryview(self._map)
        b = self._map
        if len(b) < 64 or b[0:8] != b"SPHRSPX1":
            raise ValueError(path + " is not an SPX file")
        version, self.kind, n, _pad, directory = struct.unpack_from("<iiiiq", b, 8)
        if version != 1:
            raise ValueError(path + ": unsupported SPX version")
        self.title = b[40:64].decode("ascii").strip()
        self.sections = {}
        for k in range(n):
            at = directory + 64 * k
            name = b[at:at + 24].decode("ascii").strip()
            stype, _pad, offset, count = struct.unpack_from("<iiqq", b, at + 24)
            size = count if stype == TEXT else 8 * count
            if offset + size > len(b):
                raise ValueError(path + ": section " + name + " runs past the end")
            self.sections[name] = (stype, offset, count)

    def __contains__(self, name):
        return name in self.sections

    def _section(self, name, stype):
        if name not in self.sections:
            raise KeyError(self.path + " has no section " + name)
        t, offset, count = self.sections[name]
        if t != stype:
            raise TypeError("section " + name + " has another type")
        return offset, count

    def f64(self, name):
        """The values as a memoryview of floats over the mapped file."""
        offset, count = self._section(name, F64)
        return self._view[offset:offset + 8 * count].cast("d")

    def i64(self, name):
        offset, count = self._section(name, I64)
        return self._view[offset:offset + 8 * count].cast("q")

    def array(self, name):
        """A numpy array over the mapped file (read-only), for either numeric type."""
        if _np is None:
            raise ImportError("numpy is needed for arrays; f64() and i64() work without it")
        t, offset, count = self.sections[name]
        return _np.frombuffer(self._map, dtype="<f8" if t == F64 else "<i8", count=count, offset=offset)

    def text(self, name):
        offset, count = self._section(name, TEXT)
        return bytes(self._view[offset:offset + count]).decode("utf-8")

    def meta(self):
        out = {}
        if "meta" in self.sections:
            for line in self.text("meta").split("\n"):
                if ":" in line:
                    key, value = line.split(":", 1)
                    out[key.strip()] = value.strip()
        return out


def _below(value, knots, n):
    low, high = 0, n
    while low < high:
        mid = (low + high) >> 1
        if knots[mid] <= value:
            low = mid + 1
        else:
            high = mid
    if low >= n:
        low = n - 1
    return 0 if low == 0 else low - 1


def _hermite(t, vl, vdl, vh, vdh):
    t2 = t * t
    t3 = t * t2
    return (2 * t3 - 3 * t2 + 1) * vl + (t3 - 2 * t2 + t) * vdl + (-2 * t3 + 3 * t2) * vh + (t3 - t2) * vdh


def _linear(x, xl, xh, yl, yh):
    return yl + (x - xl) / (xh - xl) * (yh - yl)


def _along_log(x, xa, xb, fa, fb):
    t = (math.log(x) - math.log(xa)) / (math.log(xb) - math.log(xa))
    if fa > 1e-3 and fb > 1e-3:
        return math.exp(math.log(fa) + t * (math.log(fb) - math.log(fa)))
    return fa + t * (fb - fa)


class PDFSet:
    """A PDF set Sphere exported. Members count from 0, the central one."""

    def __init__(self, f):
        if f.kind != 1:
            raise ValueError(f.path + " does not hold a PDF set")
        self.file = f
        self.meta = f.meta()
        self.nf, self.nq, self.nx, self.nm = (int(v) for v in f.i64("shape"))
        self.pids = [int(p) for p in f.i64("pids")]
        self.xs = f.f64("xknots")
        self.q2s = f.f64("q2knots")
        self.logx = f.f64("logx")
        self.logq2 = f.f64("logq2")
        self.xf = f.f64("xf")
        self.weighted = self.meta.get("Accuracy", "lhapdf") == "weighted"
        self._lookup = {}
        for k, p in enumerate(self.pids):
            self._lookup[p] = k
        if 21 in self._lookup:
            self._lookup[0] = self._lookup[21]
        if "as_q2" in f:
            self.asq2, self.aslog, self.asval = f.f64("as_q2"), f.f64("as_logq2"), f.f64("as_val")
            self._pieces = [0] + [k for k in range(1, len(self.asq2)) if abs(self.asq2[k] - self.asq2[k - 1]) < _EPS]
        else:
            self.asq2 = self.aslog = self.asval = []
            self._pieces = []
        self.ashold = self.meta.get("AlphaS_Above", "hold") == "hold"

    def __repr__(self):
        return "PDFSet(%s, %d members, %s)" % (self.name, self.nm, self.error_type)

    @property
    def name(self):
        return self.meta.get("SetName", self.file.title)

    @property
    def error_type(self):
        return self.meta.get("ErrorType", "none")

    def in_range(self, x, q2):
        return self.xs[0] <= x <= self.xs[self.nx - 1] and self.q2s[0] <= q2 <= self.q2s[self.nq - 1]

    def _at(self, base, ix, iq, f):
        return self.xf[base + (ix * self.nq + iq) * self.nf + f]

    def _slope(self, base, ix, iq, f):
        axis, nx, at = self.logx, self.nx, self._at
        if ix != 0 and ix != nx - 1:
            back = axis[ix] - axis[ix - 1]
            ahead = axis[ix + 1] - axis[ix]
            left = (at(base, ix, iq, f) - at(base, ix - 1, iq, f)) / back
            right = (at(base, ix + 1, iq, f) - at(base, ix, iq, f)) / ahead
            if self.weighted:
                return (ahead * left + back * right) / (back + ahead)
            return (left + right) / 2.0
        if ix == 0:
            return (at(base, 1, iq, f) - at(base, 0, iq, f)) / (axis[1] - axis[0])
        return (at(base, nx - 1, iq, f) - at(base, nx - 2, iq, f)) / (axis[nx - 1] - axis[nx - 2])

    def _along_x(self, base, ix, iq, f, t):
        dlogx = self.logx[ix + 1] - self.logx[ix]
        vl = self._at(base, ix, iq, f)
        vh = self._at(base, ix + 1, iq, f)
        vdl = self._slope(base, ix, iq, f) * dlogx
        vdh = self._slope(base, ix + 1, iq, f) * dlogx
        c0 = vdh + vdl - 2.0 * vh + 2.0 * vl
        c1 = 3.0 * vh - 3.0 * vl - 2.0 * vdl - vdh
        c2 = vdl
        c3 = vl
        t2 = t * t
        return c0 * t2 * t + c1 * t2 + c2 * t + c3

    def _interpolate(self, base, f, x, q2):
        nq, q2s, logx, logq2, at = self.nq, self.q2s, self.logx, self.logq2, self._at
        ix = _below(x, self.xs, self.nx)
        iq = _below(q2, q2s, nq)
        lx = math.log(x)
        lq = math.log(q2)
        low_seam = iq == 0 or q2s[iq] == q2s[iq - 1]
        high_seam = iq + 1 == nq - 1 or q2s[iq + 1] == q2s[iq + 2]
        dlogx = logx[ix + 1] - logx[ix]
        tx = (lx - logx[ix]) / dlogx
        dlogq1 = logq2[iq + 1] - logq2[iq]
        tq = (lq - logq2[iq]) / dlogq1
        if low_seam and high_seam:
            fl = _linear(lx, logx[ix], logx[ix + 1], at(base, ix, iq, f), at(base, ix + 1, iq, f))
            fh = _linear(lx, logx[ix], logx[ix + 1], at(base, ix, iq + 1, f), at(base, ix + 1, iq + 1, f))
            return _linear(lq, logq2[iq], logq2[iq + 1], fl, fh)
        vl = self._along_x(base, ix, iq, f, tx)
        vh = self._along_x(base, ix, iq + 1, f, tx)
        if low_seam:
            vdl = vh - vl
            vhh = self._along_x(base, ix, iq + 2, f, tx)
            dlogq2 = 1.0 / (logq2[iq + 2] - logq2[iq + 1])
            vdh = (vdl + (vhh - vh) * dlogq1 * dlogq2) * 0.5
        elif high_seam:
            vdh = vh - vl
            vll = self._along_x(base, ix, iq - 1, f, tx)
            dlogq0 = 1.0 / (logq2[iq] - logq2[iq - 1])
            vdl = (vdh + (vl - vll) * dlogq1 * dlogq0) * 0.5
        else:
            vll = self._along_x(base, ix, iq - 1, f, tx)
            dlogq0 = 1.0 / (logq2[iq] - logq2[iq - 1])
            vdl = ((vh - vl) + (vl - vll) * dlogq1 * dlogq0) * 0.5
            vhh = self._along_x(base, ix, iq + 2, f, tx)
            dlogq2 = 1.0 / (logq2[iq + 2] - logq2[iq + 1])
            vdh = ((vh - vl) + (vhh - vh) * dlogq1 * dlogq2) * 0.5
        return _hermite(tq, vl, vdl, vh, vdh)

    def _continuation(self, base, f, x, q2):
        xs, q2s = self.xs, self.q2s
        x_min, x_min1, x_max = xs[0], xs[1], xs[self.nx - 1]
        q2_min, q2_max1, q2_max = q2s[0], q2s[self.nq - 2], q2s[self.nq - 1]

        def i(xx, qq):
            return self._interpolate(base, f, xx, qq)

        def along_xq(xx, qq):
            return _along_log(xx, x_min, x_min1, i(x_min, qq), i(x_min1, qq))

        if x > x_max:
            return 0.0  # LHAPDF refuses; a momentum fraction above one carries nothing.
        if x < x_min and q2_min <= q2 <= q2_max:
            return along_xq(x, q2)
        if x >= x_min and q2 > q2_max:
            return _along_log(q2, q2_max, q2_max1, i(x, q2_max), i(x, q2_max1))
        if x < x_min and q2 > q2_max:
            at_min = _along_log(q2, q2_max, q2_max1, i(x_min, q2_max), i(x_min, q2_max1))
            at_min1 = _along_log(q2, q2_max, q2_max1, i(x_min1, q2_max), i(x_min1, q2_max1))
            return _along_log(x, x_min, x_min1, at_min, at_min1)
        if q2 < q2_min:
            if x < x_min:
                at_edge, just_above = along_xq(x, q2_min), along_xq(x, 1.01 * q2_min)
            else:
                at_edge, just_above = i(x, q2_min), i(x, 1.01 * q2_min)
            anomalous = max(-2.5, (just_above - at_edge) / at_edge / 0.01) if abs(at_edge) >= 1e-5 else 1.0
            ratio = q2 / q2_min
            return at_edge * math.pow(ratio, anomalous * ratio + 1.0 - ratio)
        return i(x, q2)

    def xfxQ2(self, pid, x, q2, member=0):
        """x f(x, Q^2) of one member; 0 for a flavour the set does not carry."""
        f = self._lookup.get(pid, -1)
        if f < 0 or member < 0 or member >= self.nm or x <= 0.0 or q2 <= 0.0:
            return 0.0
        x, q2 = float(x), float(q2)
        base = member * self.nx * self.nq * self.nf
        if self.in_range(x, q2):
            return self._interpolate(base, f, x, q2)
        return self._continuation(base, f, x, q2)

    def xfxQ(self, pid, x, q, member=0):
        return self.xfxQ2(pid, x, float(q) * float(q), member)

    def member_values(self, pid, x, q2):
        return [self.xfxQ2(pid, x, q2, m) for m in range(self.nm)]

    def uncertainty(self, pid, x=None, q2=None):
        """LHAPDF's PDFSet::uncertainty at the set's own CL: (central, errplus, errminus, errsymm).

        Give (pid, x, q2), or one list of per-member values as the only argument.
        """
        v = self.member_values(pid, x, q2) if x is not None else list(pid)
        n = len(v)
        central = v[0] if n else 0.0
        kind = self.error_type
        if n < 2 or kind == "none":
            return central, 0.0, 0.0, 0.0
        if kind == "replicas":
            nrep = n - 1.0
            mean = sum(v[1:]) / nrep
            mean2 = sum(a * a for a in v[1:]) / nrep
            s = math.sqrt(max(0.0, nrep / (nrep - 1.0) * (mean2 - mean * mean)))
            return mean, s, s, s
        if kind == "symmhessian":
            s = math.sqrt(sum((a - central) * (a - central) for a in v[1:]))
            return central, s, s, s
        up = down = symm = 0.0
        for m in range(1, n - 1, 2):
            a, b = v[m] - central, v[m + 1] - central
            rise, fall = max(max(a, b), 0.0), max(max(-a, -b), 0.0)
            up += rise * rise
            down += fall * fall
            symm += (v[m] - v[m + 1]) * (v[m] - v[m + 1])
        return central, math.sqrt(up), math.sqrt(down), 0.5 * math.sqrt(symm)

    def alphasQ2(self, q2):
        """alpha_s(Q^2) from the table the set carries, interpolated the LHAPDF way."""
        q, a, n = self.asq2, self.asval, len(self.asq2)
        if n == 0 or q2 < 0.0:
            return float("nan")
        q2 = float(q2)
        if q2 < q[0]:
            nxt = 1
            while nxt < n and q[0] == q[nxt]:
                nxt += 1
            if nxt >= n or a[0] <= 0.0 or a[nxt] <= 0.0:
                return a[0]
            slope = math.log10(a[nxt] / a[0]) / math.log10(q[nxt] / q[0])
            return a[0] * math.pow(q2 / q[0], slope)
        if q2 > q[n - 1]:
            if self.ashold or n < 3:
                return a[n - 1]
            lo, hi = a[n - 2], a[n - 1]
            if lo <= 0.0 or hi <= 0.0 or q[n - 1] == q[n - 2]:
                return hi
            slope = math.log(hi / lo) / math.log(q[n - 1] / q[n - 2])
            return hi * math.pow(q2 / q[n - 1], slope)
        pieces = self._pieces
        pc = 0
        while pc + 1 < len(pieces) and q[pieces[pc + 1]] <= q2:
            pc += 1
        s = pieces[pc]
        length = (pieces[pc + 1] if pc + 1 < len(pieces) else n) - s
        pq, pl, pa = q[s:s + length], self.aslog[s:s + length], a[s:s + length]
        i = _below(q2, pq, length)

        def forward(k):
            return (pa[k + 1] - pa[k]) / (pl[k + 1] - pl[k])

        def backward(k):
            return (pa[k] - pa[k - 1]) / (pl[k] - pl[k - 1])

        def central(k):
            return 0.5 * (forward(k) + backward(k))

        if length == 2:
            sl = sh = forward(0)
        elif i == 0:
            sl, sh = forward(i), central(i + 1)
        elif i == length - 2:
            sl, sh = central(i), backward(i + 1)
        else:
            sl, sh = central(i), central(i + 1)
        dlog = pl[i + 1] - pl[i]
        t = (math.log(q2) - pl[i]) / dlog
        out = _hermite(t, pa[i], sl * dlog, pa[i + 1], sh * dlog)
        return out if abs(out) < 2.0 else sys.float_info.max

    def alphasQ(self, q):
        return self.alphasQ2(float(q) * float(q))


def pdf(name):
    """A set Sphere exported, by name or by path."""
    return PDFSet(SPXFile(resolve(name)))


class Events:
    """An event sample: particles (px, py, pz, E), PDG codes, weights, and the jets if clustered."""

    def __init__(self, f):
        if f.kind != 2:
            raise ValueError(f.path + " does not hold events")
        self.file = f
        self.meta = f.meta()
        self.offset = f.i64("evt_offset")
        self.p4 = f.f64("p4")
        self.pdg = f.i64("pdg")
        self.weight = f.f64("weight")
        self.incoming_ = f.f64("incoming") if "incoming" in f else None
        self.has_jets = "jet_offset" in f
        if self.has_jets:
            self.jet_offset, self.jet_p4, self.jet_of_ = f.i64("jet_offset"), f.f64("jet_p4"), f.i64("jet_of")

    def __len__(self):
        return len(self.offset) - 1

    def __repr__(self):
        jets = ", %d jets" % self.jet_offset[-1] if self.has_jets else ""
        return "Events(%d events, %d particles%s)" % (len(self), len(self.pdg), jets)

    def particles(self, e):
        """The particles of event e as a list of (px, py, pz, E)."""
        a, b = self.offset[e], self.offset[e + 1]
        return [tuple(self.p4[4 * i:4 * i + 4]) for i in range(a, b)]

    def jets(self, e):
        a, b = self.jet_offset[e], self.jet_offset[e + 1]
        return [tuple(self.jet_p4[4 * k:4 * k + 4]) for k in range(a, b)]

    def jet_of(self, e):
        """The jet each particle of event e ended up in, -1 when none."""
        return list(self.jet_of_[self.offset[e]:self.offset[e + 1]])

    def incoming(self, e):
        v = self.incoming_[5 * e:5 * e + 5]
        return int(v[0]), int(v[1]), v[2], v[3], v[4]


def events(name):
    return Events(SPXFile(resolve(name)))


def load(name):
    """Any SPX file, as what it holds: a PDFSet, Events, or a dict of columns for a table."""
    f = SPXFile(resolve(name))
    if f.kind == 1:
        return PDFSet(f)
    if f.kind == 2:
        return Events(f)
    out = {}
    for key, (t, _o, _c) in f.sections.items():
        out[key] = f.text(key) if t == TEXT else (f.array(key) if _np is not None else
                                                    (f.f64(key) if t == F64 else f.i64(key)))
    return out


def write_table(path, title, columns, meta=""):
    """Writes named columns (lists of floats, or of ints for int64) as an SPX table."""
    entries = [("meta", TEXT, ("Title: " + title + "\n" + meta).encode("utf-8"))]
    for name, values in columns:
        values = list(values)
        if values and all(isinstance(v, int) for v in values):
            entries.append((name, I64, struct.pack("<%dq" % len(values), *values)))
        else:
            entries.append((name, F64, struct.pack("<%dd" % len(values), *[float(v) for v in values])))

    def aligned(v):
        return (v + 63) // 64 * 64

    offsets, at = [], aligned(64 + 64 * len(entries))
    for e in entries:
        offsets.append(at)
        at = aligned(at + len(e[2]))
    buf = bytearray(at)
    buf[0:8] = b"SPHRSPX1"
    struct.pack_into("<iiiiqq", buf, 8, 1, 3, len(entries), 0, 64, at)
    buf[40:64] = title.encode("ascii", "replace")[:24].ljust(24, b" ")
    for k, (name, stype, raw) in enumerate(entries):
        d = 64 + 64 * k
        buf[d:d + 24] = name.encode("ascii")[:24].ljust(24, b" ")
        struct.pack_into("<iiqq", buf, d + 24, stype, 0, offsets[k], len(raw) if stype == TEXT else len(raw) // 8)
        buf[offsets[k]:offsets[k] + len(raw)] = raw
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path + ".part", "wb") as out:
        out.write(buf)
    os.replace(path + ".part", path)
    return path


def publish(name, edges, values, err_plus=None, err_minus=None, xlabel=None):
    """Hands a histogram to Sphere: it appears in the Plots tab by itself."""
    def floats(v):
        return [float(a) for a in v]

    cols = [("edges", floats(edges)), ("values", floats(values))]
    if err_plus is not None:
        cols.append(("err_plus", floats(err_plus)))
    if err_minus is not None:
        cols.append(("err_minus", floats(err_minus)))
    return write_table(os.path.join(bridge_folder(), "outbox", name + ".spx"), name, cols,
                       "Kind: histogram\nXLabel: %s\nEngine: Python\n" % (xlabel or name))


def crosscheck(pdf_path, points_path, out_path):
    p = PDFSet(SPXFile(pdf_path))
    pts = SPXFile(points_path)
    pid = [int(v) for v in pts.f64("pid")]
    x, q2, mem = list(pts.f64("x")), list(pts.f64("q2")), [int(v) for v in pts.f64("member")]
    aq2 = list(pts.f64("as_q2"))
    n = len(pid)
    xf = [p.xfxQ2(pid[k], x[k], q2[k], mem[k]) for k in range(n)]
    als = [p.alphasQ2(v) for v in aq2]
    rounds, sink, start = 0, 0.0, time.perf_counter()
    while True:
        for k in range(n):
            sink += p.xfxQ2(pid[k], x[k], q2[k], mem[k])
        rounds += 1
        if time.perf_counter() - start > 0.2 or rounds >= 1000:
            break
    elapsed = (time.perf_counter() - start) * 1e9 / (n * rounds)
    write_table(out_path, "crosscheck", [("xf", xf), ("as", als), ("ns_per_eval", [elapsed])],
                "Engine: Python %d.%d\nRounds: %d\n" % (sys.version_info[0], sys.version_info[1], rounds))


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "crosscheck":
        crosscheck(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        print("usage: python sphere_spx.py crosscheck <pdf.spx> <points.spx> <out.spx>")
