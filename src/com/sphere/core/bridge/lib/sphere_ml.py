"""sphere_ml -- machine learning in Sphere: PyTorch, TensorFlow (Keras), JAX and pyhf, one engine.

Sphere runs this module as its learning engine (python sphere_ml.py --stdio), and any Python
program Sphere starts can import it, its folder being on PYTHONPATH:

    import sphere_ml as ml
    ml.require("torch")                               # run again by a Python that has it, if this one has not
    ml.frameworks()                                   # what this interpreter can learn with
    data = ml.dataset("tagging")                      # $SPHERE_BRIDGE/tagging.spx, from ':ml jets'
    net = ml.fit(data, "particlenet", backend="torch", epochs=20)
    net["metrics"]                                    # AUC, background rejection at 30/50/70 %, sculpting
    ml.predict("tagging_particlenet_torch", data)     # any model of $SPHERE_BRIDGE/models
    ml.stats({"signal": [5], "background": [50], "bkg_uncertainty": [7], "observed": [52]})
                                                      # pyhf: fit, CLs, upper limit, on each of its backends

A network is written once, as a function of its weights over a handful of tensor operations
that numpy, PyTorch and Keras (on TensorFlow, JAX or PyTorch) each provide: the three build the
same computation from the same starting weights, which ':ml crosscheck' shows in float64 to the
last digits a double holds (1e-16), and in float32 to float32's own rounding, which numpy shows
in float32 alongside. fit(precision="float64") trains in double throughout. The zoo holds the networks of jet physics: an MLP on high-level
features, Deep Sets (Particle Flow Networks), ParticleNet's EdgeConv and a Particle Transformer
whose attention is biased by the pairs' kinematics. A trained network is exported as SPX
(models/<name>.snn.spx): its weights, its preprocessing and its architecture, which Sphere's
Java and the C++ header sphere_nn.hpp run without Python, in a FastJet loop or a Geant4 program.
"""

import contextlib
import gc
import json
import math
import os
import queue
import re
import struct
import sys
import threading
import time

try:
    import numpy as np
except ImportError:  # the probe works without it; nothing else does
    np = None

import sphere_spx as spx

VERSION = "1.0"
F64, I64, TEXT = 1, 2, 3


# ---------------------------------------------------------------------------
# What this interpreter can learn with
# ---------------------------------------------------------------------------

def _dist_version(name):
    try:
        from importlib import metadata
        return metadata.version(name)
    except Exception:
        return None


_KERAS_TRAINABLE = ("tensorflow", "jax", "torch")


def _keras_installed(backend):
    if backend == "tensorflow":
        return bool(_dist_version("tensorflow") or _dist_version("tensorflow-cpu") or _dist_version("tensorflow-intel"))
    return bool(_dist_version(backend))


def _keras_json_backend():
    """The "backend" of keras.json ($KERAS_HOME, else ~/.keras), which Keras takes when KERAS_BACKEND is unset."""
    home = os.environ.get("KERAS_HOME")
    if not home:
        base = os.path.expanduser("~")
        home = os.path.join(base if os.access(base, os.W_OK) else "/tmp", ".keras")
    try:
        with open(os.path.join(home, "keras.json"), encoding="utf-8") as fh:
            return json.load(fh).get("backend")
    except (OSError, ValueError, AttributeError):
        return None


def _keras_choice():
    """The backend Keras takes in Sphere and where that comes from: KERAS_BACKEND (ML_KERAS_BACKEND in
    settings.conf), else the first installed of TensorFlow, JAX, PyTorch.

    keras.json is not followed (it is reported, by frameworks()): Keras writes one naming TensorFlow wherever
    it is first imported without one, and a PyTorch named there costs Sphere's networks dearly -- ParticleNet
    through Keras on PyTorch ran 10 times slower than on JAX, whose XLA compiles it, and needed more than
    1.5 GB where JAX took 1.07 and PyTorch itself 0.85: enough to exhaust a 3.5 GB machine. The function is
    the same on every backend (':ml crosscheck' holds them to 1e-16), so the fastest is taken by default."""
    chosen = os.environ.get("KERAS_BACKEND")
    if chosen:
        return chosen, "KERAS_BACKEND"
    for backend in _KERAS_TRAINABLE:
        if _keras_installed(backend):
            return backend, "installed"
    return None, None


def _keras_backend():
    """The backend Keras takes in this process (see _keras_choice)."""
    return _keras_choice()[0]


def frameworks(devices=False):
    """The frameworks installed, by their distributions (nothing is imported unless devices are asked)."""
    tf = _dist_version("tensorflow") or _dist_version("tensorflow-cpu") or _dist_version("tensorflow-intel")
    out = {
        "python": "%d.%d.%d" % sys.version_info[:3],
        "executable": sys.executable,
        "platform": sys.platform,
        "numpy": np.__version__ if np is not None else None,
        "torch": _dist_version("torch"),
        "tensorflow": tf,
        "keras": _dist_version("keras"),
        "keras_backend": _keras_backend() if _dist_version("keras") else None,
        "keras_backend_from": _keras_choice()[1] if _dist_version("keras") else None,
        "keras_json": _keras_json_backend() if _dist_version("keras") else None,
        "jax": _dist_version("jax"),
        "pyhf": _dist_version("pyhf"),
        "sklearn": _dist_version("scikit-learn"),
        "onnx": _dist_version("onnx"),
        "sphere_ml": VERSION,
    }
    if devices:
        out["devices"] = _devices(out)
    return out


def _devices(have):
    found = {}
    if have.get("torch"):
        try:
            import torch
            found["torch"] = ["cpu (%d threads)" % torch.get_num_threads()] + (
                ["cuda:%d %s" % (i, torch.cuda.get_device_name(i)) for i in range(torch.cuda.device_count())]
                if torch.cuda.is_available() else [])
        except Exception as e:
            found["torch"] = ["unusable: %s" % e]
    if have.get("jax"):
        try:
            import jax
            found["jax"] = [str(d) for d in jax.devices()]
        except Exception as e:
            found["jax"] = ["unusable: %s" % e]
    if have.get("tensorflow"):
        try:
            import tensorflow as tf
            found["tensorflow"] = [d.name for d in tf.config.list_physical_devices()]
        except Exception as e:
            found["tensorflow"] = ["unusable: %s" % e]
    return found


def _candidates():
    """The Pythons to try, in Sphere's order: ML_PYTHON, then the virtual environments beside this one's."""
    out = []
    if os.environ.get("ML_PYTHON"):
        out.append(os.environ["ML_PYTHON"])
    venv = os.path.dirname(os.path.dirname(os.path.abspath(sys.executable)))
    folder = os.path.dirname(venv)
    if os.path.isfile(os.path.join(venv, "pyvenv.cfg")) and os.path.isdir(folder):
        for d in sorted(os.listdir(folder)):
            exe = os.path.join(folder, d, "Scripts", "python.exe") if os.name == "nt" else os.path.join(folder, d, "bin", "python")
            if os.path.isfile(os.path.join(folder, d, "pyvenv.cfg")) and os.path.isfile(exe):
                out.append(exe)
    return [p for p in out if os.path.abspath(p) != os.path.abspath(sys.executable)]


def require(*names):
    """Makes sure this script runs on a Python holding these frameworks ("torch", "tensorflow", "keras", "jax",
    "pyhf"). When the one running it lacks one, the script is run again by one that has them all -- ML_PYTHON,
    then the environments beside this one, as Sphere's MlLocator looks -- and this process ends with its exit
    code. So a script started with Sphere's own Python finds the PyTorch of a neighboring environment.
    """
    have = frameworks()
    missing = [n for n in names if not have.get(n)]
    if not missing:
        return
    import subprocess
    script = os.path.abspath(sys.argv[0]) if sys.argv and sys.argv[0] and os.path.isfile(sys.argv[0]) else None
    for py in _candidates():
        try:
            probe = subprocess.run([py, os.path.abspath(__file__), "--probe"], capture_output=True, text=True, timeout=60)
            found = json.loads(probe.stdout.strip().splitlines()[-1])
        except Exception:
            continue
        if all(found.get(n) for n in names):
            if script is None:
                raise ImportError("%s lacks %s; %s has them: run this there (no script to run again from here)"
                                  % (sys.executable, ", ".join(missing), py))
            sys.stderr.write("sphere_ml: %s lacks %s; running %s again with %s\n" % (
                sys.executable, ", ".join(missing), os.path.basename(script), py))
            sys.stderr.flush()
            sys.exit(subprocess.run([py, script] + sys.argv[1:], env=os.environ).returncode)
    raise ImportError("no Python found with %s (':ml locate' in Sphere shows what each holds; pip install %s)"
                      % (", ".join(missing), " ".join(missing)))


def _keras_from(source):
    return " (KERAS_BACKEND)" if source == "KERAS_BACKEND" else ""


def describe():
    f = frameworks()
    parts = []
    if f["torch"]:
        parts.append("torch " + f["torch"])
    if f["tensorflow"]:
        parts.append("tensorflow " + f["tensorflow"])
    if f["keras"]:
        parts.append("keras %s on %s%s" % (f["keras"], f["keras_backend"], _keras_from(f["keras_backend_from"])))
    if f["jax"]:
        parts.append("jax " + f["jax"])
    if f["pyhf"]:
        parts.append("pyhf " + f["pyhf"])
    return "sphere-ml %s (%s; Python %s)" % (VERSION, ", ".join(parts) or "numpy only", f["python"])


# ---------------------------------------------------------------------------
# SPX: datasets in, models and predictions out
# ---------------------------------------------------------------------------

def write_spx(path, title, sections, meta=None, kind=3):
    """Writes numpy arrays (float64 or int64) and texts as SPX, in one pass, without a Python list in between."""
    entries = []
    if meta:
        entries.append(("meta", TEXT, "".join("%s: %s\n" % (k, v) for k, v in meta.items()).encode("utf-8"), None))
    for name, value in sections:
        if len(name) > 24:
            raise ValueError("SPX section names hold 24 characters: " + name)
        if isinstance(value, str):
            entries.append((name, TEXT, value.encode("utf-8"), None))
        else:
            a = np.ascontiguousarray(value)
            if a.dtype.kind in "iub":
                entries.append((name, I64, a.astype("<i8").tobytes(), a.size))
            else:
                entries.append((name, F64, a.astype("<f8").tobytes(), a.size))

    def aligned(v):
        return (v + 63) // 64 * 64

    offsets, at = [], aligned(64 + 64 * len(entries))
    for e in entries:
        offsets.append(at)
        at = aligned(at + len(e[2]))
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path + ".part", "wb") as out:
        head = bytearray(aligned(64 + 64 * len(entries)))
        head[0:8] = b"SPHRSPX1"
        struct.pack_into("<iiiiqq", head, 8, 1, kind, len(entries), 0, 64, at)
        head[40:64] = title.encode("ascii", "replace")[:24].ljust(24, b" ")
        for k, (name, stype, raw, count) in enumerate(entries):
            d = 64 + 64 * k
            head[d:d + 24] = name.encode("ascii")[:24].ljust(24, b" ")
            struct.pack_into("<iiqq", head, d + 24, stype, 0, offsets[k], len(raw) if stype == TEXT else count)
        out.write(head)
        for k, (name, stype, raw, count) in enumerate(entries):
            out.seek(offsets[k])
            out.write(raw)
        out.truncate(at)
    os.replace(path + ".part", path)
    return path


def _resolve(name, folder=""):
    if os.path.isabs(name) or "/" in name or "\\" in name:
        return name
    base = os.path.join(spx.bridge_folder(), folder) if folder else spx.bridge_folder()
    return os.path.join(base, name if name.endswith(".spx") else name + ".spx")


class Dataset:
    """A dataset as ':ml jets' writes it: particles of jets (x, mask), their high-level features (hl),
    labels (y), weights (w) and the jet mass and pt the checks need. Arrays are views of the mapped file."""

    def __init__(self, path):
        self.path = path
        f = spx.SPXFile(path)
        self.file = f
        m = f.meta()
        self.meta = m
        self.name = f.title
        self.classes = [c.strip() for c in m.get("Classes", "background,signal").split(",")]
        self.features = [s for s in m.get("Features", "").split(",") if s]
        self.high_level = [s for s in m.get("HighLevel", "").split(",") if s]
        self.n = int(m.get("Jets", m.get("Rows", "0")))
        self.points = int(m.get("Points", "0"))
        self.coords = [int(v) for v in m.get("Coords", "4,5").split(",")]
        self.pt_index = int(m.get("PtIndex", "0"))
        F, P, N = len(self.features), self.points, self.n
        self.x = f.array("x").reshape(N, P, F) if "x" in f and P and F else None
        self.mask = f.array("mask").reshape(N, P) if "mask" in f and P else None
        self.hl = f.array("hl").reshape(N, len(self.high_level)) if "hl" in f and self.high_level else None
        self.y = f.array("y").astype(np.int64) if "y" in f else None
        self.w = f.array("w") if "w" in f else np.ones(N)
        self.mass = f.array("mass") if "mass" in f else None
        self.pt = f.array("pt") if "pt" in f else None

    def __len__(self):
        return self.n

    def __repr__(self):
        return "<sphere_ml.Dataset %s: %d jets, %d points x %d features, %d high-level, classes %s>" % (
            self.name, self.n, self.points, len(self.features), len(self.high_level), self.classes)

    def summary(self):
        counts = np.bincount(self.y, minlength=len(self.classes)).tolist() if self.y is not None else []
        return {"name": self.name, "path": self.path, "jets": self.n, "points": self.points,
                "features": self.features, "high_level": self.high_level, "classes": self.classes,
                "per_class": counts, "source": self.meta.get("Source", "")}


def dataset(name):
    return Dataset(_resolve(name))


# ---------------------------------------------------------------------------
# Toy jets, to try everything without a sample: two-prong (W-like) against one-prong (QCD-like)
# ---------------------------------------------------------------------------

FEATURES = ["log_pt", "log_e", "log_ptrel", "log_erel", "deta", "dphi", "dr"]


def toy_jets(n=4000, points=32, seed=1, mass_w=80.4, pt=500.0, path=None):
    """Toy jets: a W-like two-prong signal (the prongs sharing the pt as the decay angle spreads them, at the
    angle 2m/sqrt(z(1-z))/pt, sometimes radiating), against QCD-like jets of one prong, a third of them with a
    hard gluon emission (soft, collinear-ish: z ~ Beta(0.6, 4), angle log-uniform), both with underlying-event
    particles over the cone. Not physics, but shaped like it: a task neither trivial nor hopeless, with a
    mass a tagger can learn, and sculpt."""
    rng = np.random.default_rng(seed)
    F = len(FEATURES)
    x = np.zeros((n, points, F))
    mask = np.zeros((n, points))
    y = (np.arange(n) % 2).astype(np.int64)
    rng.shuffle(y)
    mass, jpt = np.zeros(n), np.zeros(n)
    for i in range(n):
        ptj = pt * (1 + 0.1 * rng.standard_normal())
        nhard = int(np.clip(rng.poisson(16 if y[i] else 13), 3, points - 2))
        nsoft = int(np.clip(rng.poisson(4), 0, points - nhard))
        ang = rng.uniform(0, 2 * math.pi)
        if y[i]:
            z = rng.uniform(0.1, 0.9)
            m = mass_w * (1 + 0.08 * rng.standard_normal())
            dr = min(0.75, m / ptj / math.sqrt(z * (1 - z)))
            axes = [(z, (1 - z) * dr * math.cos(ang), (1 - z) * dr * math.sin(ang)),
                    (1 - z, -z * dr * math.cos(ang), -z * dr * math.sin(ang))]
            spread = rng.uniform(0.015, 0.04)
        elif rng.uniform() < 0.33:
            z = min(rng.beta(0.6, 4.0), 0.5)
            dr = math.exp(rng.uniform(math.log(0.05), math.log(0.7)))
            axes = [(1 - z, -z * dr * math.cos(ang), -z * dr * math.sin(ang)),
                    (z, (1 - z) * dr * math.cos(ang), (1 - z) * dr * math.sin(ang))]
            spread = rng.uniform(0.02, 0.06)
        else:
            axes = [(1.0, 0.0, 0.0)]
            spread = rng.uniform(0.02, 0.10)
        frac = rng.exponential(1.0, nhard) ** 1.5
        frac /= frac.sum()
        which = rng.choice(len(axes), size=nhard, p=[a[0] for a in axes])
        deta = np.array([axes[k][1] for k in which]) + spread * rng.standard_normal(nhard)
        dphi = np.array([axes[k][2] for k in which]) + spread * rng.standard_normal(nhard)
        pti = 0.97 * ptj * frac
        # the underlying event: soft particles anywhere in the cone
        r, a = 0.8 * np.sqrt(rng.uniform(size=nsoft)), rng.uniform(0, 2 * math.pi, nsoft)
        deta = np.concatenate([deta, r * np.cos(a)])
        dphi = np.concatenate([dphi, r * np.sin(a)])
        pti = np.concatenate([pti, rng.exponential(2.0, nsoft) + 0.5])
        nconst = nhard + nsoft
        e = pti * np.cosh(deta)
        order = np.argsort(-pti)
        pti, e, deta, dphi = pti[order], e[order], deta[order], dphi[order]
        px, py, pz = (pti * np.cos(dphi)).sum(), (pti * np.sin(dphi)).sum(), (pti * np.sinh(deta)).sum()
        E = e.sum()
        mass[i] = math.sqrt(max(E * E - px * px - py * py - pz * pz, 0.0))
        jpt[i] = math.hypot(px, py)
        x[i, :nconst, 0] = np.log(pti)
        x[i, :nconst, 1] = np.log(e)
        x[i, :nconst, 2] = np.log(pti / jpt[i])
        x[i, :nconst, 3] = np.log(e / E)
        x[i, :nconst, 4] = deta
        x[i, :nconst, 5] = dphi
        x[i, :nconst, 6] = np.hypot(deta, dphi)
        mask[i, :nconst] = 1
    hl = np.stack([jpt, mass, mask.sum(1), (np.exp(x[..., 0]) * x[..., 6] * mask).sum(1) / jpt], axis=1)
    if path:
        write_dataset(path, "toyjets", x, mask, y, hl=hl, high_level=["pt", "mass", "nconst", "width"],
                      mass=mass, pt=jpt, source="sphere_ml.toy_jets(%d, seed=%d)" % (n, seed))
    return x, mask, y, hl, mass, jpt


def write_dataset(path, title, x, mask, y, hl=None, high_level=(), mass=None, pt=None, w=None,
                  features=FEATURES, classes=("background", "signal"), source="", extra=()):
    n, points, _ = x.shape
    meta = {"Kind": "ml-dataset", "Classes": ",".join(classes), "Jets": n, "Points": points,
            "Features": ",".join(features), "HighLevel": ",".join(high_level), "Coords": "4,5", "PtIndex": 0,
            "Source": source}
    sections = [("x", x), ("mask", mask), ("y", np.asarray(y, dtype=np.int64)),
                ("w", np.ones(n) if w is None else w)]
    if hl is not None:
        sections.append(("hl", hl))
    if mass is not None:
        sections.append(("mass", mass))
    if pt is not None:
        sections.append(("pt", pt))
    sections.extend(extra)
    return write_spx(path, title, sections, meta)


# ---------------------------------------------------------------------------
# The networks: shapes of their weights, and one forward over any framework's operations
# ---------------------------------------------------------------------------

MODELS = {
    "mlp": "an MLP on the jets' high-level features (pt, mass, N-subjettiness, energy correlators...)",
    "deepsets": "Deep Sets / Particle Flow Network: a network per particle, summed, then one on the sum"
                " (Komiske, Metodiev, Thaler 2019)",
    "efn": "Energy Flow Network: a network of each particle's angles only, weighted by its momentum fraction, summed:"
           " infrared and collinear safe by construction (Komiske, Metodiev, Thaler 2019)",
    "particlenet": "ParticleNet(-Lite): EdgeConv on the k nearest particles in (eta, phi), then in the learned"
                   " features (Qu, Gouskos 2020)",
    "transformer": "Particle Transformer: attention over the particles, biased by the pairs' Delta, kT, z and"
                   " m^2, then a class token (Qu, Li, Qian 2022)",
}


def make_spec(model, data, **kw):
    """What a network is, independent of any framework: its sizes, its inputs, its preprocessing slots."""
    if model not in MODELS:
        raise ValueError("no model %s (%s)" % (model, ", ".join(MODELS)))
    points = min(int(kw.get("points", data.points or 0)), data.points or 0)
    spec = {"model": model, "classes": len(data.classes), "class_names": data.classes,
            "features": len(data.features), "feature_names": data.features, "points": points,
            "high_level": len(data.high_level), "high_level_names": data.high_level,
            "coords": data.coords, "pt_index": data.pt_index, "sphere_ml": VERSION}
    if model == "mlp":
        if not data.high_level:
            raise ValueError(data.name + " has no high-level features: the MLP needs them (':ml jets' writes them)")
        spec["widths"] = kw.get("widths", [128, 128, 64])
    elif model in ("deepsets", "efn"):
        spec["phi"] = kw.get("phi", [64, 64, 128])
        spec["rho"] = kw.get("rho", [128, 64])
    elif model == "particlenet":
        # ParticleNet-Lite's sizes by default (the paper's lighter network); k=16 and 64/128 make the full one
        spec["k"] = int(kw.get("k", 7))
        spec["blocks"] = kw.get("blocks", [[32, 32, 32], [64, 64, 64]])
        spec["fc"] = int(kw.get("fc", 128))
    elif model == "transformer":
        spec["dim"] = int(kw.get("dim", 64))
        spec["heads"] = int(kw.get("heads", 4))
        spec["layers"] = int(kw.get("layers", 2))
        spec["ffn"] = int(kw.get("ffn", 128))
        spec["pair"] = int(kw.get("pair", 16))
    return spec


def param_shapes(spec):
    """Every weight by its name, the name every engine (numpy, PyTorch, Keras, Java, C++) knows it by."""
    s, C, F = {}, spec["classes"], spec["features"]

    def dense(name, n_in, n_out):
        s[name + ".W"] = (n_in, n_out)
        s[name + ".b"] = (n_out,)

    def norm(name, n):
        s[name + ".g"] = (n,)
        s[name + ".b"] = (n,)

    m = spec["model"]
    if m == "mlp":
        n_in = spec["high_level"]
        for i, w in enumerate(spec["widths"] + [C]):
            dense("mlp.%d" % i, n_in, w)
            n_in = w
    elif m in ("deepsets", "efn"):
        n_in = F if m == "deepsets" else len(spec["coords"])   # an EFN sees the angles only
        for i, w in enumerate(spec["phi"]):
            dense("phi.%d" % i, n_in, w)
            n_in = w
        for i, w in enumerate(spec["rho"] + [C]):
            dense("rho.%d" % i, n_in, w)
            n_in = w
    elif m == "particlenet":
        n_in = F
        for b, widths in enumerate(spec["blocks"]):
            e_in = 2 * n_in
            for i, w in enumerate(widths):
                dense("ec%d.%d" % (b, i), e_in, w)
                e_in = w
            dense("ec%d.s" % b, n_in, widths[-1])
            n_in = widths[-1]
        dense("fc.0", n_in, spec["fc"])
        dense("fc.1", spec["fc"], C)
    elif m == "transformer":
        D, H, Pd = spec["dim"], spec["heads"], spec["pair"]
        dense("emb.0", F, D)
        dense("emb.1", D, D)
        dense("pair.0", 4, Pd)
        dense("pair.1", Pd, H)
        for name in ["blk%d" % l for l in range(spec["layers"])] + ["cls"]:
            norm(name + ".n1", D)
            for p in ("q", "k", "v", "o"):
                dense(name + "." + p, D, D)
            norm(name + ".n2", D)
            dense(name + ".f0", D, spec["ffn"])
            dense(name + ".f1", spec["ffn"], D)
        s["cls.token"] = (1, 1, D)
        norm("head.n", D)
        dense("head", D, C)
    return s


def init_params(spec, seed=1):
    """Starting weights, the same whatever framework then trains them: Glorot-uniform kernels, zero biases,
    unit norms, a small class token."""
    rng = np.random.default_rng(seed)
    out = {}
    for name, shape in param_shapes(spec).items():
        if name.endswith(".W"):
            lim = math.sqrt(6.0 / (shape[0] + shape[1]))
            out[name] = rng.uniform(-lim, lim, shape)
        elif name.endswith(".g"):
            out[name] = np.ones(shape)
        elif name == "cls.token":
            out[name] = 0.02 * rng.standard_normal(shape)
        else:
            out[name] = np.zeros(shape)
    return out


class NumpyOps:
    """The operations a network is written in, by numpy: the reference the others are checked against."""
    name = "numpy"

    @staticmethod
    def dense(x, W, b):
        return x @ W + b

    relu = staticmethod(lambda x: np.maximum(x, 0))
    exp, log, sqrt, sin, cos, sinh, cosh, abs = (staticmethod(f) for f in (np.exp, np.log, np.sqrt, np.sin, np.cos,
                                                                             np.sinh, np.cosh, np.abs))
    minimum, maximum = staticmethod(np.minimum), staticmethod(np.maximum)
    concat = staticmethod(lambda xs, axis: np.concatenate(xs, axis=axis))
    stack = staticmethod(lambda xs, axis: np.stack(xs, axis=axis))
    reshape = staticmethod(lambda x, shape: np.reshape(x, shape))
    expand = staticmethod(lambda x, axis: np.expand_dims(x, axis))
    transpose = staticmethod(lambda x, perm: np.transpose(x, perm))
    sum = staticmethod(lambda x, axis, keep=False: np.sum(x, axis=axis, keepdims=keep))
    mean = staticmethod(lambda x, axis, keep=False: np.mean(x, axis=axis, keepdims=keep))
    matmul = staticmethod(np.matmul)
    ones = staticmethod(lambda shape: np.ones(shape))
    eye = staticmethod(lambda n: np.eye(n))
    zeros_like = staticmethod(np.zeros_like)
    ones_like = staticmethod(np.ones_like)
    take_along = staticmethod(lambda x, idx, axis: np.take_along_axis(x, idx, axis=axis))

    @staticmethod
    def smallest(d, k):
        # stable, so that equal distances pick the lower index, as torch.topk and lax.top_k tend to
        return np.argsort(d, axis=-1, kind="stable")[..., :k]

    @staticmethod
    def softmax(x, axis):
        e = np.exp(x - x.max(axis=axis, keepdims=True))
        return e / e.sum(axis=axis, keepdims=True)


class NumpyOps32(NumpyOps):
    """The reference computed in float32 throughout: what float32's rounding alone makes of the function, the
    scale a framework working in float32 is judged by. (Python's numbers are weak in numpy 2: they keep it.)"""
    name = "numpy-float32"
    ones = staticmethod(lambda shape: np.ones(shape, dtype=np.float32))
    eye = staticmethod(lambda n: np.eye(n, dtype=np.float32))


def _torch_ops():
    import torch

    class TorchOps:
        name = "torch"
        dense = staticmethod(lambda x, W, b: torch.matmul(x, W) + b)
        relu = staticmethod(torch.relu)
        exp, log, sqrt, sin, cos, sinh, cosh, abs = (torch.exp, torch.log, torch.sqrt, torch.sin, torch.cos, torch.sinh,
                                                     torch.cosh, torch.abs)
        minimum, maximum = staticmethod(torch.minimum), staticmethod(torch.maximum)
        concat = staticmethod(lambda xs, axis: torch.cat(xs, dim=axis))
        stack = staticmethod(lambda xs, axis: torch.stack(xs, dim=axis))
        reshape = staticmethod(lambda x, shape: torch.reshape(x, shape))
        expand = staticmethod(lambda x, axis: torch.unsqueeze(x, axis))
        transpose = staticmethod(lambda x, perm: x.permute(*perm))
        sum = staticmethod(lambda x, axis, keep=False: torch.sum(x, dim=axis, keepdim=keep))
        mean = staticmethod(lambda x, axis, keep=False: torch.mean(x, dim=axis, keepdim=keep))
        matmul = staticmethod(torch.matmul)
        ones = staticmethod(lambda shape: torch.ones(shape))
        eye = staticmethod(lambda n: torch.eye(n))
        zeros_like = staticmethod(torch.zeros_like)
        ones_like = staticmethod(torch.ones_like)
        take_along = staticmethod(lambda x, idx, axis: torch.take_along_dim(x, idx, dim=axis))
        smallest = staticmethod(lambda d, k: torch.topk(d, k, dim=-1, largest=False, sorted=True).indices)
        softmax = staticmethod(lambda x, axis: torch.softmax(x, dim=axis))

    return TorchOps


def _keras_ops():
    from keras import ops

    class KerasOps:
        name = "keras"
        dense = staticmethod(lambda x, W, b: ops.matmul(x, W) + b)
        relu = staticmethod(ops.relu)
        exp, log, sqrt, sin, cos, sinh, cosh, abs = ops.exp, ops.log, ops.sqrt, ops.sin, ops.cos, ops.sinh, ops.cosh, ops.abs
        minimum, maximum = staticmethod(ops.minimum), staticmethod(ops.maximum)
        concat = staticmethod(lambda xs, axis: ops.concatenate(xs, axis=axis))
        stack = staticmethod(lambda xs, axis: ops.stack(xs, axis=axis))
        reshape = staticmethod(lambda x, shape: ops.reshape(x, shape))
        expand = staticmethod(lambda x, axis: ops.expand_dims(x, axis))
        transpose = staticmethod(lambda x, perm: ops.transpose(x, perm))
        sum = staticmethod(lambda x, axis, keep=False: ops.sum(x, axis=axis, keepdims=keep))
        mean = staticmethod(lambda x, axis, keep=False: ops.mean(x, axis=axis, keepdims=keep))
        matmul = staticmethod(ops.matmul)
        ones = staticmethod(lambda shape: ops.ones(shape))
        eye = staticmethod(lambda n: ops.eye(n))
        zeros_like = staticmethod(ops.zeros_like)
        ones_like = staticmethod(ops.ones_like)
        take_along = staticmethod(lambda x, idx, axis: ops.take_along_axis(x, idx, axis=axis))
        smallest = staticmethod(lambda d, k: ops.top_k(-d, k, sorted=True)[1])
        softmax = staticmethod(lambda x, axis: ops.softmax(x, axis=axis))

    return KerasOps


BIG = 1.0e9


def _mlp(O, P, prefix, x, n, last_linear=True):
    for i in range(n):
        x = O.dense(x, P["%s.%d.W" % (prefix, i)], P["%s.%d.b" % (prefix, i)])
        if i < n - 1 or not last_linear:
            x = O.relu(x)
    return x


def _norm(O, P, name, x, eps=1e-5):
    m = O.mean(x, -1, True)
    v = O.mean((x - m) * (x - m), -1, True)
    return (x - m) / O.sqrt(v + eps) * P[name + ".g"] + P[name + ".b"]


def _masked_mean(O, x, mask):
    m = O.expand(mask, -1)
    return O.sum(x * m, 1) / O.maximum(O.sum(m, 1), O.ones_like(O.sum(m, 1)))


def _edgeconv(O, P, b, pts, x, mask, k, widths, n):
    """One EdgeConv: each particle's k nearest (in pts), the edge features (x_i, x_j - x_i) through an MLP,
    averaged, added to a linear shortcut of x_i. Masked particles are never neighbors of real ones.

    The first layer is linear in the edge, [x_i, x_j - x_i] W = x_i (W_i - W_j) + x_j W_j, so it is applied
    to the particles before their neighbors are gathered: k times fewer products, the same function."""
    diff = O.expand(pts, 2) - O.expand(pts, 1)
    d = O.sum(diff * diff, -1) + (1.0 - O.expand(mask, 1)) * BIG + O.eye(n) * BIG
    idx = O.smallest(d, k)                                     # (B, n, k)
    c = x.shape[-1]
    w0 = P["ec%d.0.W" % b]
    wi, wj = w0[:c], w0[c:]
    own = O.dense(x, wi - wj, P["ec%d.0.b" % b])               # (B, n, c0)
    their = O.matmul(x, wj)
    c0 = widths[0]
    nb = O.take_along(their, O.expand(O.reshape(idx, (-1, n * k)), -1), 1)
    e = O.relu(O.reshape(nb, (-1, n, k, c0)) + O.expand(own, 2))
    for i in range(1, len(widths)):
        e = O.relu(O.dense(e, P["ec%d.%d.W" % (b, i)], P["ec%d.%d.b" % (b, i)]))
    out = O.mean(e, 2) + O.dense(x, P["ec%d.s.W" % b], P["ec%d.s.b" % b])
    return O.relu(out) * O.expand(mask, -1)


def _attention(O, P, name, q_in, kv_in, key_mask, heads, bias=None):
    D = q_in.shape[-1]
    dh = D // heads
    nq, nk = q_in.shape[1], kv_in.shape[1]

    def split(t, n):
        return O.transpose(O.reshape(t, (-1, n, heads, dh)), (0, 2, 1, 3))

    q = split(O.dense(q_in, P[name + ".q.W"], P[name + ".q.b"]), nq)
    k = split(O.dense(kv_in, P[name + ".k.W"], P[name + ".k.b"]), nk)
    v = split(O.dense(kv_in, P[name + ".v.W"], P[name + ".v.b"]), nk)
    s = O.matmul(q, O.transpose(k, (0, 1, 3, 2))) / math.sqrt(dh)
    if bias is not None:
        s = s + bias
    s = s - (1.0 - O.expand(O.expand(key_mask, 1), 1)) * BIG
    a = O.softmax(s, -1)
    o = O.reshape(O.transpose(O.matmul(a, v), (0, 2, 1, 3)), (-1, nq, D))
    return O.dense(o, P[name + ".o.W"], P[name + ".o.b"])


def _block(O, P, name, x, kv, key_mask, heads, bias=None):
    h = _norm(O, P, name + ".n1", x)
    hk = h if kv is None else _norm(O, P, name + ".n1", kv)
    x = x + _attention(O, P, name, h, hk, key_mask, heads, bias)
    h = _norm(O, P, name + ".n2", x)
    f = O.dense(O.relu(O.dense(h, P[name + ".f0.W"], P[name + ".f0.b"])), P[name + ".f1.W"], P[name + ".f1.b"])
    return x + f


def _pairs(O, pt, eta, phi, mask, eps=1e-8):
    """The pairs' kinematics ParT biases attention with: ln Delta, ln kT, ln z, ln m^2 (massless particles).

    m^2 = 2 pt_i pt_j (cosh deta - cos dphi) loses its digits for close particles, the two terms then nearly
    equal: in float32 the frameworks drifted from the float64 reference by 1e-3. Written as
    4 pt_i pt_j (sinh^2(deta/2) + sin^2(dphi/2)), the same quantity has no cancellation left."""
    deta = O.expand(eta, 2) - O.expand(eta, 1)
    dphi = O.expand(phi, 2) - O.expand(phi, 1)
    pti, ptj = O.expand(pt, 2), O.expand(pt, 1)
    delta = O.sqrt(deta * deta + dphi * dphi + eps)
    ptmin = O.minimum(pti, ptj)
    kt = ptmin * delta
    z = ptmin / (pti + ptj + eps)
    sh, sn = O.sinh(0.5 * deta), O.sin(0.5 * dphi)
    m2 = 4.0 * pti * ptj * (sh * sh + sn * sn)
    u = O.stack([O.log(delta + eps), O.log(kt + eps), O.log(z + eps), O.log(O.maximum(m2, O.zeros_like(m2)) + eps)], -1)
    pm = O.expand(O.expand(mask, 2) * O.expand(mask, 1), -1)
    return u * pm


def forward(O, spec, P, inputs):
    """The logits of a batch: inputs are x (standardized particles), mask, pts (their eta, phi), pt (their
    pt in GeV) and hl (standardized high-level features), as prepare() makes them."""
    m = spec["model"]
    C = spec["classes"]
    if m == "mlp":
        return _mlp(O, P, "mlp", inputs["hl"], len(spec["widths"]) + 1)
    x, mask = inputs["x"], inputs["mask"]
    n = spec["points"]
    if m == "deepsets":
        h = _mlp(O, P, "phi", x, len(spec["phi"]), last_linear=False) * O.expand(mask, -1)
        return _mlp(O, P, "rho", O.sum(h, 1), len(spec["rho"]) + 1)
    if m == "efn":
        # sum_i z_i Phi(deta_i, dphi_i): a soft particle (z -> 0) adds nothing, a collinear splitting (z = z1 + z2
        # at one angle) changes nothing -- the infrared and collinear safety the observables of QCD need
        pt = inputs["pt"]
        total = O.sum(pt, 1, True)
        z = pt / O.maximum(total, O.ones_like(total) * 1e-30)
        h = _mlp(O, P, "phi", inputs["pts"], len(spec["phi"]), last_linear=False) * O.expand(z, -1)
        return _mlp(O, P, "rho", O.sum(h, 1), len(spec["rho"]) + 1)
    if m == "particlenet":
        pts, h = inputs["pts"], x
        for b, widths in enumerate(spec["blocks"]):
            h = _edgeconv(O, P, b, pts if b == 0 else h, h, mask, min(spec["k"], n - 1), widths, n)
        g = _masked_mean(O, h, mask)
        return O.dense(O.relu(O.dense(g, P["fc.0.W"], P["fc.0.b"])), P["fc.1.W"], P["fc.1.b"])
    if m == "transformer":
        H = spec["heads"]
        h = _mlp(O, P, "emb", x, 2, last_linear=False) * O.expand(mask, -1)
        u = _pairs(O, inputs["pt"], inputs["pts"][..., 0], inputs["pts"][..., 1], mask)
        bias = O.transpose(_mlp(O, P, "pair", u, 2), (0, 3, 1, 2))   # (B, heads, n, n)
        for l in range(spec["layers"]):
            h = _block(O, P, "blk%d" % l, h, None, mask, H, bias) * O.expand(mask, -1)
        cls = P["cls.token"] + O.zeros_like(h[:, :1, :])
        kv = O.concat([cls, h], 1)
        km = O.concat([O.ones_like(mask[:, :1]), mask], 1)
        cls = _block(O, P, "cls", cls, kv, km, H)
        return O.dense(_norm(O, P, "head.n", cls[:, 0, :]), P["head.W"], P["head.b"])
    raise ValueError("no model " + m + " for " + str(C) + " classes")


# ---------------------------------------------------------------------------
# Preparing a dataset for a network
# ---------------------------------------------------------------------------

def fit_norm(spec, data, rows):
    """Means and spreads of the particles' features (real particles only) and of the high-level features,
    over the training rows: the network sees them standardized, and so do Java and C++ through the export."""
    norm = {}
    if data.x is not None and spec["model"] != "mlp":
        n = spec["points"]
        xs, ms = data.x[rows, :n, :], data.mask[rows, :n] > 0
        v = xs[ms]
        norm["x_mean"] = v.mean(0).tolist()
        norm["x_std"] = np.maximum(v.std(0), 1e-6).tolist()
    if data.hl is not None and spec["model"] == "mlp":
        h = data.hl[rows]
        norm["hl_mean"] = h.mean(0).tolist()
        norm["hl_std"] = np.maximum(h.std(0), 1e-6).tolist()
    return norm


def prepare(spec, norm, x=None, mask=None, hl=None, dtype=None):
    """The network's inputs from raw rows: standardized, truncated to its points, float32 by default."""
    dtype = dtype or np.float32
    out = {}
    if spec["model"] == "mlp":
        out["hl"] = ((hl - np.asarray(norm["hl_mean"])) / np.asarray(norm["hl_std"])).astype(dtype)
        return out
    n = spec["points"]
    raw, m = x[:, :n, :], mask[:, :n]
    out["x"] = ((raw - np.asarray(norm["x_mean"])) / np.asarray(norm["x_std"]) * m[..., None]).astype(dtype)
    out["mask"] = m.astype(dtype)
    c = spec["coords"]
    out["pts"] = raw[..., c].astype(dtype)
    out["pt"] = (np.exp(raw[..., spec["pt_index"]]) * m).astype(dtype)
    return out


def _split(n, seed, val, test):
    rng = np.random.default_rng(seed)
    order = rng.permutation(n)
    nt, nv = int(round(n * test)), int(round(n * val))
    return order[nt + nv:], order[nt:nt + nv], order[:nt]


def _rows(data, rows):
    return dict(x=data.x[rows] if data.x is not None else None, mask=data.mask[rows] if data.mask is not None else None,
                hl=data.hl[rows] if data.hl is not None else None)


def _take(inputs, idx):
    return {k: v[idx] for k, v in inputs.items()}


# ---------------------------------------------------------------------------
# Physics metrics
# ---------------------------------------------------------------------------

def roc(score, y, w=None, signal=1):
    """Signal and background efficiencies as the cut on the score falls, weighted."""
    w = np.ones(len(score)) if w is None else np.asarray(w, dtype=float)
    order = np.argsort(-score, kind="stable")
    s = np.cumsum(np.where(y[order] == signal, w[order], 0.0))
    b = np.cumsum(np.where(y[order] != signal, w[order], 0.0))
    return np.concatenate([[0.0], s / max(s[-1], 1e-300)]), np.concatenate([[0.0], b / max(b[-1], 1e-300)]), score[order]


def metrics(prob, y, w=None, mass=None, signal=1):
    """AUC, accuracy, the background rejection 1/eps_B at 30, 50 and 70 % signal efficiency, and how much a
    cut on the score sculpts the background's mass (1/JSD between before and after a 50 % cut).

    A rejection comes with its binomial error (rejection_50_err), from the effective number of background
    jets of the test set, (sum w)^2 / sum w^2; where no background jet passes, it is infinite and bounded
    instead: eps_B < 3 / N at 95 % CL, so 1/eps_B > N / 3 (rejection_50_min)."""
    score = prob[:, signal]
    eff_s, eff_b, ordered = roc(score, y, w, signal)
    auc = float(np.trapezoid(eff_s, eff_b)) if hasattr(np, "trapezoid") else float(np.trapz(eff_s, eff_b))
    out = {"auc": auc, "accuracy": float(np.mean(np.argmax(prob, 1) == y)), "jets": int(len(y))}
    wb = (np.ones(len(y)) if w is None else np.asarray(w, dtype=float))[y != signal]
    n_eff = float(wb.sum() ** 2 / (wb * wb).sum()) if len(wb) and (wb * wb).sum() > 0 else 0.0
    out["background_jets_effective"] = n_eff
    for target in (0.3, 0.5, 0.7):
        k = int(np.searchsorted(eff_s, target))
        eb = eff_b[min(k, len(eff_b) - 1)]
        key = "rejection_%d" % int(target * 100)
        if eb > 0:
            out[key] = float(1.0 / eb)
            out[key + "_err"] = float(math.sqrt(eb * (1.0 - eb) / max(n_eff, 1.0)) / (eb * eb))
        else:
            out[key] = float("inf")
            out[key + "_min"] = n_eff / 3.0
    if mass is not None:
        k = int(np.searchsorted(eff_s, 0.5))
        cut = ordered[min(max(k - 1, 0), len(ordered) - 1)]
        bkg = y != signal
        lo, hi = np.percentile(mass[bkg], [1, 99])
        edges = np.linspace(lo, hi, 31)
        h_all, _ = np.histogram(mass[bkg], edges)
        h_cut, _ = np.histogram(mass[bkg & (score >= cut)], edges)
        if h_cut.sum() > 0:
            p, q = h_all / h_all.sum(), h_cut / h_cut.sum()
            mid = 0.5 * (p + q)

            def kl(a, b):
                nz = a > 0
                return float(np.sum(a[nz] * np.log2(a[nz] / b[nz])))

            jsd = 0.5 * kl(p, mid) + 0.5 * kl(q, mid)
            out["sculpting_jsd"] = jsd
            out["sculpting_1_over_jsd"] = float(1.0 / jsd) if jsd > 0 else float("inf")
    return out


def _softmax_np(z):
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def _dcorr(O, x, y, w):
    """The squared distance correlation of x and y over a batch, weighted by w (a zero weight takes an entry
    out): 0 exactly when they are independent, 1 when one fixes the other, and differentiable in any framework
    -- the term DisCo adds to the loss, so that a tagger's score keeps no trace of the background's mass and a
    cut on it sculpts no bump (Kasieczka, Shih, PRL 125 (2020) 122001)."""
    nw = w / O.maximum(O.mean(w, 0, True), O.ones_like(O.mean(w, 0, True)) * 1e-12)   # weights of mean 1

    def centered(m):
        avg = O.mean(m * O.expand(nw, 0), 1)                 # avg_i = mean_j m_ij w_j
        return m - O.expand(avg, 0) - O.expand(avg, 1) + O.mean(avg * nw, 0, True)

    def mean2(m):
        return O.mean(O.mean(m * O.expand(nw, 0), 1) * nw, 0)

    a = centered(O.abs(O.expand(x, 1) - O.expand(x, 0)))
    b = centered(O.abs(O.expand(y, 1) - O.expand(y, 0)))
    return mean2(a * b) / O.sqrt(mean2(a * a) * mean2(b * b) + 1e-30)


def _decorrelation(text):
    """'mass' or 'mass:0.3': the variable the score is to forget, and DisCo's lambda (1 unless told: no lambda
    suits every dataset -- ':ml pareto' finds the one a dataset wants)."""
    if not text:
        return None, 0.0
    var, _, lam = str(text).partition(":")
    if var != "mass":
        raise ValueError("decorrelate: from the jet's mass, 'mass' or 'mass:lambda' (got %s)" % text)
    return var, float(lam) if lam else 1.0


# ---------------------------------------------------------------------------
# Memory: what a training may take of this machine
# ---------------------------------------------------------------------------

def _memory():
    """This process's private bytes ("mine"), the machine's available and total physical memory ("avail",
    "total") and its commit ("commit_avail", "commit_total": what RAM and page file can still back -- what
    ran out when a JVM could not get 300 KB): GlobalMemoryStatusEx and GetProcessMemoryInfo on Windows, /proc
    on Linux (WSL), psutil elsewhere. None where nothing answers -- the governor then stands aside."""
    try:
        if sys.platform == "win32":
            import ctypes
            from ctypes import wintypes

            class Status(ctypes.Structure):
                _fields_ = [("length", wintypes.DWORD), ("load", wintypes.DWORD)] + [
                    (f, ctypes.c_ulonglong) for f in ("total", "avail", "page_total", "page_avail", "virtual_total",
                                                      "virtual_avail", "extended")]

            class Counters(ctypes.Structure):
                _fields_ = [("cb", wintypes.DWORD), ("faults", wintypes.DWORD)] + [
                    (f, ctypes.c_size_t) for f in ("peak_ws", "ws", "peak_paged", "paged", "peak_nonpaged", "nonpaged",
                                                   "pagefile", "peak_pagefile", "private")]

            k32 = ctypes.windll.kernel32
            k32.GetCurrentProcess.restype = wintypes.HANDLE
            k32.K32GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
            status, counters = Status(), Counters()
            status.length, counters.cb = ctypes.sizeof(Status), ctypes.sizeof(Counters)
            if not k32.GlobalMemoryStatusEx(ctypes.byref(status)) or not k32.K32GetProcessMemoryInfo(
                    k32.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
                return None
            return {"mine": float(counters.private), "avail": float(status.avail), "total": float(status.total),
                    "commit_avail": float(status.page_avail), "commit_total": float(status.page_total)}
        if sys.platform.startswith("linux"):
            with open("/proc/meminfo") as fh:
                info = {l.split(":")[0]: float(l.split()[1]) * 1024 for l in fh if ":" in l and len(l.split()) > 1}
            with open("/proc/self/status") as fh:
                rss = next(float(l.split()[1]) * 1024 for l in fh if l.startswith("VmRSS"))
            avail = info.get("MemAvailable", info.get("MemFree", 0.0))
            return {"mine": rss, "avail": avail, "total": info.get("MemTotal", 0.0),
                    "commit_avail": avail + info.get("SwapFree", 0.0),
                    "commit_total": info.get("MemTotal", 0.0) + info.get("SwapTotal", 0.0)}
        import psutil
        vm, sw, mi = psutil.virtual_memory(), psutil.swap_memory(), psutil.Process().memory_info()
        return {"mine": float(mi.rss), "avail": float(vm.available), "total": float(vm.total),
                "commit_avail": float(vm.available + sw.free), "commit_total": float(vm.total + sw.total)}
    except Exception:
        return None


def _activations_per_jet(spec):
    """The numbers one jet's forward pass leaves in memory, counted from the architecture: what a training
    keeps for its backward pass (before the framework's own factor) and a prediction passes through."""
    m, n = spec["model"], max(2, int(spec.get("points") or 2))
    if m == "mlp":
        return float(spec["high_level"] + 2 * sum(spec["widths"]) + spec["classes"])
    if m in ("deepsets", "efn"):
        return float(n * (spec["features"] + 2 * sum(spec["phi"])) + 2 * sum(spec["rho"]) + spec["classes"])
    if m == "particlenet":
        k, total = min(spec["k"], n - 1), 0.0
        for widths in spec["blocks"]:
            total += n * n + n * k + 2 * n * widths[0]                 # distances, neighbors, the first layer's halves
            total += n * k * (2 * widths[0] + 2 * sum(widths[1:]))   # the edges: gathered, each layer before and after ReLU
            total += 4 * n * widths[-1]                              # their mean, the shortcut, the sum, the mask
        return total + 2 * spec["fc"] + spec["classes"]
    if m == "transformer":
        d, H, L, f, p = spec["dim"], spec["heads"], spec["layers"], spec["ffn"], spec["pair"]
        pairs = n * n * (4 + 2 * p + H)                              # the pairs' kinematics through their MLP: the bias
        layer = 3 * H * n * n + n * (8 * d + 2 * f)                  # scores, softmax, weighted sum; q, k, v, norms, ffn
        return float(2 * n * d + pairs + (L + 1) * layer)
    return 1.0e4


def _learned_path():
    return os.path.join(spx.bridge_folder(), "ml", "memory.json")


def _learned():
    try:
        with open(_learned_path(), encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


class MemoryExceeded(MemoryError):
    """Raised into a training's step by the memory governor, the machine about to run out."""


class MemoryGovernor:
    """What a training may take of this machine, watched while it runs, so that it never takes the machine
    down -- as a Keras training on PyTorch did, its memory growing by the step until a 3.5 GB machine had none.

    That growth was no tensor kept: each Keras step on PyTorch leaves some 2200 objects in reference cycles,
    which hold the step's tensors until Python's collector frees them -- and the collector counts objects,
    not the megabytes they hold, so it came too late (+2.8 MB a step for a small model, +65 MB for
    ParticleNet). At each step's end the governor collects the young generation: a few milliseconds, and
    the memory stays flat (a full collection did as well, ten times slower).

    Budget: the process may grow to its size now, plus what the machine has available (free and standby
    physical memory) minus a reserve for the rest (12 % of the RAM, 400 MB at least) -- and at least a quarter
    of the RAM, which the system gives by paging out what other programs left idle (slower, not fatal): a
    machine whose RAM other programs fill would otherwise refuse a 10 MB network. Always within the commit
    (RAM and page file) the machine still has, minus a tenth of it: commit exhausted is what kills processes.
    ML_MEMORY_MB in settings.conf caps it.

    Plan: a jet in a batch costs its activations (counted from the architecture) times the framework's
    factor -- or what was measured last time for this network, backend and precision (bridge/ml/memory.json):
    the batch is the largest, up to 256, whose peak stays under 90 % of the budget. A batch asked for is kept,
    and guarded all the same.

    Guard: a thread reads the process every 0.1 s. Above 90 % of the budget the training halves its batch at
    the next step; above the budget (or the commit's reserve reached) it halves it still, and stops at the
    smallest, keeping its best epoch. A step cannot be stopped from outside, so should one alone go half a
    reserve past the budget, or the commit down to half its reserve, or the free RAM to 64 MB, the thread
    raises MemoryExceeded into it (an asynchronous exception, between two operations of the step); the
    training catches it and ends as above. What a jet really cost is measured on the way, and learned for
    the next training."""

    RESERVE_FRACTION, RESERVE_MIN, PRESSURE, SMALLEST = 0.12, 400.0 * 2 ** 20, 0.9, 8
    FLOOR, COMMIT_FRACTION, COMMIT_MIN, THRASHING = 0.25, 0.1, 1024.0 * 2 ** 20, 64.0 * 2 ** 20
    # A training step costs OVERHEAD once (optimizer state, the framework's caches) and, per jet, its activation
    # numbers times their size times FACTOR (what autograd saves, the gradients, the temporaries), as measured
    # here on ParticleNet-Lite: PyTorch 450 MB at batch 256; Keras on PyTorch 48 MB + 1.07 MB a jet; Keras on
    # JAX about PyTorch's a jet, but some 300 MB to compile its step, at any batch. A prediction costs INFER
    # times its activations: Keras on PyTorch 1.3 MB a jet at 300 (2.3 times), PyTorch itself 0.1 (it frees as
    # it goes), numpy keeping its temporaries.
    OVERHEAD = {"torch": 96.0 * 2 ** 20, "keras/torch": 96.0 * 2 ** 20, "keras/jax": 320.0 * 2 ** 20,
                "keras/tensorflow": 256.0 * 2 ** 20}
    FACTOR = {"torch": 3.5, "keras/jax": 3.5, "keras/tensorflow": 4.0, "keras/torch": 4.0}
    INFER = {"numpy": 2.0, "torch": 1.0, "keras/jax": 2.0, "keras/tensorflow": 2.0, "keras/torch": 4.5}

    def __init__(self, spec, backend, precision, note=None):
        self.note = note or (lambda message: None)
        self.key = "%s|%s|%s|%d|%d" % (spec["model"], backend, precision, int(spec.get("points") or 0),
                                       int(spec.get("k") or spec.get("dim") or 0))
        m = _memory()
        self.active = m is not None
        m = m or {"mine": 0.0, "avail": 0.0, "total": 0.0, "commit_avail": 0.0, "commit_total": 0.0}
        self.start, self.total, self.free = m["mine"], m["total"], m["avail"]
        self.reserve = max(self.RESERVE_MIN, self.RESERVE_FRACTION * self.total)
        self.commit_reserve = max(self.COMMIT_MIN, self.COMMIT_FRACTION * m["commit_total"])
        free_room = m["avail"] - self.reserve
        room = min(max(free_room, self.FLOOR * self.total), m["commit_avail"] - self.commit_reserve)
        self.paging = room > free_room + 2 ** 20   # beyond the free RAM: the system pages out what others left idle
        self.budget = self.start + max(0.0, room)
        cap = float(os.environ.get("SPHERE_ML_MEMORY_MB") or 0) * 2 ** 20
        self.capped = bool(cap) and cap < self.budget
        if self.capped:
            self.budget, self.paging = cap, False
        self.overhead = self.OVERHEAD.get(backend, 256.0 * 2 ** 20)
        learned = _learned().get(self.key)
        estimate = _activations_per_jet(spec) * (8 if precision == "float64" else 4) * self.FACTOR.get(backend, 4.0)
        self.per_jet, self.source = (float(learned), "measured before") if learned else (estimate, "estimated")
        self.peak, self.pressure, self.over, self.events = self.start, False, False, []
        self.batch_now, self._measured, self._measured_batch = None, 0.0, 0
        self.trace, self._step_peak = [], self.start   # per step: (where, peak during it, held after it), MB
        self._stop, self._thread, self._main, self._raised = threading.Event(), None, None, False

    def plan(self, wanted, asked=False):
        """The batch to start with -- the one asked for, else the largest up to `wanted` that fits -- and a
        sentence on it."""
        wanted = int(wanted)
        if not self.active:
            self.batch_now = wanted
            return wanted, "memory: not measurable here; batch %d" % wanted
        # a measured cost holds the overhead already, spread over the batch it was measured at
        room = self.PRESSURE * self.budget - self.start - (0.0 if self.source == "measured before" else self.overhead)
        fits = int(room // self.per_jet) if self.per_jet > 0 else wanted
        self.batch_now = wanted if asked else max(self.SMALLEST, min(wanted, fits))
        mb = 2.0 ** 20
        if self.capped:
            why = "ML_MEMORY_MB"
        elif self.paging:
            why = ("the machine has %.0f MB free: the rest is had by paging out what other programs left idle, slower"
                   " but within the commit, %.0f MB of which stays untouched" % (self.free / mb, self.commit_reserve / mb))
        else:
            why = "what the machine spares, keeping %.0f MB for the rest" % (self.reserve / mb)
        text = ("memory: this training may take %.0f MB (it holds %.0f MB, %.0f MB more: %s); a jet costs %.2f MB (%s):"
                " batch %d" % (self.budget / mb, self.start / mb, max(0.0, self.budget - self.start) / mb, why,
                               self.per_jet / mb, self.source, self.batch_now))
        if asked and fits < wanted:
            text += " as asked, where %d would fit: the guard halves it if need be" % max(fits, 0)
        elif self.batch_now < wanted:
            text += " instead of %d, for the peak to stay under %.0f%%" % (wanted, 100 * self.PRESSURE)
        if fits < self.SMALLEST:
            text += " -- even that may not fit: the guard stops the training before the machine runs out"
        return self.batch_now, text

    def __enter__(self):
        if self.active:
            self._main = threading.get_ident()
            self._thread = threading.Thread(target=self._watch, name="sphere-ml-memory", daemon=True)
            self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(1.0)
            if self._raised:   # raised as the training ended, not yet delivered: withdrawn
                import ctypes
                ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(self._main), None)
        self._learn()
        return False

    def _watch(self):
        while not self._stop.wait(0.1):
            m = _memory()
            if m is None:
                return
            mine, avail, commit = m["mine"], m["avail"], m["commit_avail"]
            self.peak = max(self.peak, mine)
            self._step_peak = max(self._step_peak, mine)
            self.pressure = mine > self.PRESSURE * self.budget or commit < 1.5 * self.commit_reserve
            if mine > self.budget or commit < self.commit_reserve:
                self.over = True
            if (mine > self.budget + 0.5 * self.reserve or commit < 0.5 * self.commit_reserve
                    or avail < self.THRASHING) and not self._raised:
                import ctypes
                self._raised = True
                mb = 2.0 ** 20
                self.events.append("step interrupted: the process at %.0f MB, the machine down to %.0f MB free and"
                                   " %.0f MB of commit" % (mine / mb, avail / mb, commit / mb))
                ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(self._main), ctypes.py_object(MemoryExceeded))

    def regime(self, batch):
        """A batch size starts (the steps' cost is measured at it)."""
        self.batch_now = int(batch)

    def relieve(self, where):
        """At a step's end: the step's cycles collected, and the batch to go on with -- halved under pressure,
        or over the budget while it can still be -- or 0 to stop now: over the budget at the smallest batch,
        or the machine itself down to half its reserve."""
        gc.collect(0)
        if not self.active:
            return self.batch_now
        mb = 2.0 ** 20
        m = _memory()
        if m is not None:
            # what a jet cost: this step's peak over the memory the plan started from, as the plan reckons --
            # the steps only (a validation between epochs is the prediction's business), and at the largest
            # batch run, where the fixed part weighs least: after a halving, the memory the heap kept from the
            # larger steps would be charged to fewer jets
            if self.batch_now and self.batch_now >= self._measured_batch:
                cost = (self._step_peak - self.start) / self.batch_now
                self._measured = cost if self.batch_now > self._measured_batch else max(self._measured, cost)
                self._measured_batch = self.batch_now
            if len(self.trace) < 4000:
                self.trace.append((where, round(self._step_peak / mb), round(m["mine"] / mb)))
            self._step_peak = m["mine"]
        short = m is not None and (m["commit_avail"] < self.commit_reserve or m["avail"] < 2 * self.THRASHING)
        if self.over and (self.batch_now <= self.SMALLEST or short):
            self.events.append("stopped %s: the process reached %.0f MB of the %.0f MB it may take%s" % (
                where, self.peak / mb, self.budget / mb, ", the machine down to %.0f MB free and %.0f MB of commit"
                % (m["avail"] / mb, m["commit_avail"] / mb) if short else ""))
            self.note("memory: " + self.events[-1] + "; the training stops there")
            return 0
        if (self.pressure or self.over) and self.batch_now > self.SMALLEST:
            gc.collect()
            old = self.batch_now
            self.regime(max(self.SMALLEST, old // 2))
            self.pressure = self.over = False
            self.events.append("batch %d -> %d %s (%.0f MB of %.0f)" % (old, self.batch_now, where, self.peak / mb,
                                                                        self.budget / mb))
            self.note("memory: " + self.events[-1])
        return self.batch_now

    def _learn(self):
        if not self.active or self._measured <= 0:
            return
        table = _learned()
        table[self.key] = self._measured
        try:
            os.makedirs(os.path.dirname(_learned_path()), exist_ok=True)
            with open(_learned_path(), "w", encoding="utf-8") as fh:
                json.dump(table, fh, indent=1, sort_keys=True)
        except OSError:
            pass   # learning is a courtesy

    def summary(self):
        mb = 2.0 ** 20
        return {"budget_mb": self.budget / mb, "start_mb": self.start / mb, "peak_mb": self.peak / mb,
                "per_jet_mb": self.per_jet / mb, "per_jet_source": self.source, "measured_per_jet_mb": self._measured / mb,
                "batch": self.batch_now, "events": self.events, "paging": self.paging,
                "stopped": any(e.startswith(("stopped", "step interrupted")) for e in self.events),
                "active": self.active}


def _release():
    """What a training left to the frameworks -- Keras's session, JAX's compiled steps, the cycles -- given
    back, so that the engine's next command starts lighter: after a ParticleNet that failed on JAX the engine
    held 1014 MB and refused a 10 MB MLP."""
    gc.collect()
    if _keras_mod is not None:
        try:
            _keras_mod.backend.clear_session(free_memory=True)
        except TypeError:
            _keras_mod.backend.clear_session()
        except Exception:
            pass
    if "jax" in sys.modules:
        try:
            sys.modules["jax"].clear_caches()
        except Exception:
            pass
    gc.collect()


def _predict_batch(spec, precision, backend="numpy", most=1024):
    """The largest batch of jets whose forward pass, no gradient kept, takes at most half of what a training
    could (the governor's room: the free RAM beyond its reserve, a tenth of the RAM at least, within the
    commit), at the cost this backend's predictions have (INFER)."""
    m = _memory()
    if m is None:
        return most
    g = MemoryGovernor
    reserve = max(g.RESERVE_MIN, g.RESERVE_FRACTION * m["total"])
    commit_reserve = max(g.COMMIT_MIN, g.COMMIT_FRACTION * m["commit_total"])
    room = 0.5 * max(0.0, min(max(m["avail"] - reserve, 0.1 * m["total"]), m["commit_avail"] - commit_reserve))
    per_jet = _activations_per_jet(spec) * (8 if precision == "float64" else 4) * g.INFER.get(backend, 3.0)
    return int(max(16, min(most, room // max(per_jet, 1.0))))


def _forward_np(ops, spec, P, inputs):
    """The numpy forward, jet batch by jet batch: numpy keeps every activation of a batch at once."""
    n = len(next(iter(inputs.values())))
    if not n:
        return np.zeros((0, spec["classes"]))
    b = _predict_batch(spec, "float64", "numpy")
    return np.concatenate([forward(ops, spec, P, _take(inputs, np.arange(a, min(a + b, n)))) for a in range(0, n, b)])


# ---------------------------------------------------------------------------
# PyTorch
# ---------------------------------------------------------------------------

def _san(name):
    return name.replace(".", "_")


def _precision(p):
    """float32 (what training uses by default) or float64, from the names people give them."""
    p = str(p or "float32").lower()
    if p in ("float64", "f64", "64", "double", "fp64"):
        return "float64"
    if p in ("float32", "f32", "32", "single", "float", "fp32"):
        return "float32"
    raise ValueError("precision %s: float32 or float64" % p)


def _np_dtype(precision):
    return np.float64 if precision == "float64" else np.float32


def _torch_net(spec, params, precision="float32"):
    import torch

    O = _torch_ops()
    dtype = torch.float64 if precision == "float64" else torch.float32

    class Net(torch.nn.Module):
        memory = (spec, precision, "torch")   # what its predictions batch by

        def __init__(self):
            super().__init__()
            self.names = list(params)
            self.p = torch.nn.ParameterDict({_san(k): torch.nn.Parameter(torch.tensor(v, dtype=dtype))
                                             for k, v in params.items()})

        def forward(self, inputs):
            P = {k: self.p[_san(k)] for k in self.names}
            return forward(O, spec, P, inputs)

        def weights(self):
            return {k: self.p[_san(k)].detach().cpu().double().numpy() for k in self.names}

    return Net()


def _torch_predict(net, inputs, batch=None):
    import torch
    out = []
    net.eval()
    batch = batch or _predict_batch(*net.memory)
    with torch.no_grad():
        n = len(next(iter(inputs.values())))
        for a in range(0, n, batch):
            t = {k: torch.from_numpy(np.ascontiguousarray(v[a:a + batch])) for k, v in inputs.items()}
            out.append(net(t).double().numpy())
    return np.concatenate(out) if out else np.zeros((0, 1))


def _lr_at(lr, seen, total):
    """The learning rate after `seen` of `total` samples: from 5 % of it to all of it over the first 5 %,
    then a cosine to zero. Counted in samples rather than steps, so that a batch halved for memory keeps the
    rate on course -- and PyTorch and Keras follow the same one."""
    p = min(1.0, seen / max(1.0, total))
    return lr * min(1.0, 0.05 + 19.0 * p) * 0.5 * (1.0 + math.cos(math.pi * p))


def _train_torch(spec, params, tr, va, opts, report, canceled, governor):
    import torch
    torch.manual_seed(opts["seed"])
    net = _torch_net(spec, params, opts["precision"])
    opt = torch.optim.AdamW(net.parameters(), lr=_lr_at(opts["lr"], 0, 1), weight_decay=opts["weight_decay"])
    n = len(tr["y"])
    total_samples = float(opts["epochs"] * n)
    rng = np.random.default_rng(opts["seed"])
    tin = {k: torch.from_numpy(np.ascontiguousarray(v)) for k, v in tr["inputs"].items()}
    ty, tw = torch.from_numpy(tr["y"]), torch.from_numpy(tr["w"].astype(_np_dtype(opts["precision"])))
    best, best_loss, wait, history, done = None, float("inf"), 0, [], 0
    lam = opts.get("decorrelate", 0.0)
    if lam:   # DisCo: the score's distance correlation to the background's mass, added to the loss
        TO = _torch_ops()
        tm = torch.from_numpy(tr["mass"].astype(_np_dtype(opts["precision"])))
        tb = tw * (ty == 0)
    batch = opts["batch"]
    governor.regime(batch)
    try:
        warm = _disco_warm(opts)
        for epoch in range(opts["epochs"]):
            net.train()
            order = torch.from_numpy(rng.permutation(n))
            total, seen, t0, a = 0.0, 0, time.perf_counter(), 0
            decorrelating = lam and epoch >= warm
            if lam and epoch == warm:   # decorrelating from here: the best is chosen among these epochs
                best, best_loss, wait = None, float("inf"), 0
            while a < n and batch and not canceled():
                idx = order[a:a + batch]
                for g in opt.param_groups:
                    g["lr"] = _lr_at(opts["lr"], done, total_samples)
                logits = net({k: v[idx] for k, v in tin.items()})
                loss = (torch.nn.functional.cross_entropy(logits, ty[idx], reduction="none") * tw[idx]).sum() \
                    / tw[idx].sum()
                if decorrelating:
                    loss = loss + lam * _dcorr(TO, torch.softmax(logits, -1)[:, 1], tm[idx], tb[idx])
                opt.zero_grad(set_to_none=True)
                loss.backward()
                opt.step()
                total += loss.detach().item() * len(idx)
                seen += len(idx)
                done += len(idx)
                a += len(idx)
                del logits, loss
                batch = governor.relieve("in epoch %d" % (epoch + 1))
            if canceled():
                history.append({"epoch": epoch + 1, "canceled": True})
                break
            if not batch:
                history.append({"epoch": epoch + 1, "stopped": "memory"})
                break
            val = _evaluate(_torch_predict(net, va["inputs"]), va)
            gc.collect(0)
            val["epoch"], val["loss"], val["seconds"] = epoch + 1, total / max(seen, 1), time.perf_counter() - t0
            history.append(val)
            report(epoch + 1, val)
            score = _selection(val, lam if decorrelating else 0.0)
            if score < best_loss - 1e-4:
                best_loss, wait = score, 0
                best = {k: v.detach().clone() for k, v in net.p.items()}
            elif epoch >= warm:
                wait += 1
                if wait >= opts["patience"]:
                    break
    except MemoryExceeded:
        gc.collect()
        history.append({"epoch": len(history) + 1, "stopped": "memory"})
        governor.note("memory: " + governor.events[-1] + "; the training stops there")
    if best is not None:
        with torch.no_grad():
            for k, v in best.items():
                net.p[k].copy_(v)
    return net, history


def _evaluate(logits, part):
    p = _softmax_np(logits)
    y, w = part["y"], part["w"]
    loss = float(-np.sum(w * np.log(np.maximum(p[np.arange(len(y)), y], 1e-12))) / max(w.sum(), 1e-300))
    m = metrics(p, y, w) if len(set(y.tolist())) > 1 else {}
    out = {"val_loss": loss, "val_auc": m.get("auc", float("nan")), "val_accuracy": m.get("accuracy", float("nan"))}
    if "mass" in part and p.shape[1] > 1:   # what DisCo minimizes, on the validation's background
        out["val_dcorr"] = float(_dcorr(NumpyOps, p[:, 1], part["mass"], w * (y == 0)))
    return out


def _disco_warm(opts):
    """The epochs DisCo leaves to the cross-entropy alone, the first half: decorrelated from the first step, a
    network sees its first features -- which follow the mass -- punished before it has any other, and settled
    for a constant on the toy jets at every lambda tried; trained first, it gives its power up against the mass
    as lambda grows -- the trade-off ':ml pareto' measures."""
    return opts["epochs"] // 2 if opts.get("decorrelate") else 0


def _selection(val, lam):
    """What the best epoch minimizes on the validation: its loss, plus DisCo's term once decorrelating."""
    return val["val_loss"] + (lam * val.get("val_dcorr", 0.0) if lam else 0.0)


# ---------------------------------------------------------------------------
# Keras (on TensorFlow, JAX or PyTorch)
# ---------------------------------------------------------------------------

_keras_mod = None


def _keras(require_tensorflow=False):
    """Keras, on the backend this process gave it first. TensorFlow asked for where it is not installed is
    refused with what to do, rather than Keras quietly running on something else."""
    global _keras_mod
    if require_tensorflow and not frameworks()["tensorflow"]:
        raise RuntimeError("TensorFlow is not installed in %s (Python %d.%d): no TensorFlow wheel exists for every"
                           " Python and platform (none for 3.14 on Windows). The same Keras models run here on %s"
                           " with backend 'keras'; or give Sphere a Python with tensorflow (ML_PYTHON in settings.conf,"
                           " a Python 3.10-3.13 environment, or WSL, as TensorFlow advises on Windows)"
                           % (sys.executable, sys.version_info[0], sys.version_info[1], _keras_backend()))
    if _keras_mod is None:
        if require_tensorflow:
            os.environ.setdefault("KERAS_BACKEND", "tensorflow")
        else:
            backend = _keras_backend()
            if backend:
                os.environ.setdefault("KERAS_BACKEND", backend)
        os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
        import keras
        if hasattr(keras.config, "set_x64_mode") and keras.config.backend() in ("jax", "torch", "numpy"):
            try:   # Keras 3.16+: a float64 input gives a float64 result; float32 work is left as it is
                keras.config.set_x64_mode("explicit_x64")
            except Exception:
                pass   # then _x64_scope lifts the cap itself
        _keras_mod = keras
    if require_tensorflow and _keras_mod.backend.backend() != "tensorflow":
        raise RuntimeError("Keras already runs on %s in this engine: restart it (':ml stop') to use TensorFlow"
                           % _keras_mod.backend.backend())
    return _keras_mod


def _x64_scope(on):
    """Where Keras's float64 work is float64. Keras caps every 64-bit result to 32 bits on JAX and PyTorch
    (its type promotion copies JAX's default), and says nothing: a float64 network, its weights and inputs
    float64, then computes its products in float32 -- 2.8e-6 off on a 30x20 matmul, where torch.matmul is
    exact. Keras 3.16 keeps float64 in its explicit x64 mode, which _keras() turns on; below 3.16 the cap is
    lifted for this work only, and JAX, float32 unless told, is put in its 64-bit mode for it as well."""
    if not on:
        return contextlib.nullcontext()
    keras = _keras()
    scope = contextlib.ExitStack()
    if getattr(keras.config, "x64_mode", lambda: None)() != "explicit_x64":
        scope.enter_context(_keras_float64_uncapped())
        if keras.backend.backend() == "jax":
            import jax
            if hasattr(jax, "enable_x64"):
                scope.enter_context(jax.enable_x64(True))
            else:
                from jax.experimental import enable_x64   # JAX before 0.5
                scope.enter_context(enable_x64())
    return scope


@contextlib.contextmanager
def _keras_float64_uncapped():
    """Keras before 3.16: its table of 64-to-32-bit caps, with float64 kept, for the time of a float64 work."""
    from keras.src.backend.common import dtypes as kd
    table = getattr(kd, "BIT64_TO_BIT32_DTYPE", None)
    if not isinstance(table, dict) or table.get("float64", "float64") == "float64":
        yield   # TensorFlow keeps float64 already, or the table is gone (a Keras that keeps it its own way)
        return
    was = table["float64"]
    table["float64"] = "float64"
    try:
        yield
    finally:
        table["float64"] = was


def _keras_net(spec, params, precision="float32"):
    keras = _keras()
    O = _keras_ops()

    class Net(keras.Model):
        memory = (spec, precision, "keras/" + keras.backend.backend())   # what its predictions batch by

        def __init__(self):
            super().__init__(dtype=precision)   # its policy: inputs cast to it, not to float32
            self.names = list(params)
            self.v = {}
            for k, val in params.items():
                self.v[k] = self.add_weight(shape=val.shape, initializer=keras.initializers.Constant(val),
                                            trainable=True, name=_san(k), dtype=precision)

        def call(self, inputs, training=False):
            return forward(O, spec, self.v, inputs)

        def weights_np(self):
            return {k: np.asarray(keras.ops.convert_to_numpy(self.v[k]), dtype=np.float64) for k in self.names}

        def load(self, values):
            for k, val in values.items():
                self.v[k].assign(np.asarray(val, dtype=_np_dtype(precision)))

    return Net()


def _inference():
    """No gradient taken: on PyTorch, Keras 3.13's predict_on_batch keeps autograd on -- a graph built for
    every prediction, 60 MB of it kept per 100 ParticleNet jets, a validation's worth more at every epoch."""
    if _keras_mod is not None and _keras_mod.backend.backend() == "torch":
        import torch
        return torch.no_grad()
    return contextlib.nullcontext()


def _keras_predict(net, inputs, batch=None):
    """The logits by Keras's compiled predict step (jit on JAX: twice as fast as calling the model)."""
    out = []
    batch = batch or _predict_batch(*net.memory)
    n = len(next(iter(inputs.values())))
    with _inference():
        for a in range(0, n, batch):
            out.append(np.asarray(net.predict_on_batch({k: v[a:a + batch] for k, v in inputs.items()}),
                                  dtype=np.float64))
    return np.concatenate(out) if out else np.zeros((0, 1))


def _train_keras(spec, params, tr, va, opts, report, canceled, governor, tensorflow=False):
    """Keras's fit, with what Sphere's own PyTorch loop has: the best epoch kept (patience on the validation
    loss), the learning rate of _lr_at, and a batch the memory governor may halve -- the epoch it cut short
    is then trained again at the new batch, from where the optimizer was."""
    keras = _keras(require_tensorflow=tensorflow)
    keras.utils.set_random_seed(opts["seed"])
    net = _keras_net(spec, params, opts["precision"])
    wdtype = _np_dtype(opts["precision"])
    n = len(tr["y"])
    rate = {"seen": 0.0, "step": 0.0, "batch": float(opts["batch"]), "total": float(opts["epochs"] * n)}

    class Rate(keras.optimizers.schedules.LearningRateSchedule):
        """_lr_at over the samples seen, from the optimizer's step count and the batch of the time."""

        def __call__(self, step):
            seen = rate["seen"] + (keras.ops.cast(step, "float32") - rate["step"]) * rate["batch"]
            p = keras.ops.minimum(seen / rate["total"], 1.0)
            return opts["lr"] * keras.ops.minimum(1.0, 0.05 + 19.0 * p) * 0.5 * (1.0 + keras.ops.cos(math.pi * p))

        def get_config(self):
            return {}

    optimizer = keras.optimizers.AdamW(learning_rate=Rate(), weight_decay=opts["weight_decay"])
    lam = opts.get("decorrelate", 0.0)
    warm = _disco_warm(opts)
    if lam:
        # DisCo, as in Sphere's PyTorch loop: Keras hands a loss (y_true, y_pred) only, so y_true carries the
        # label, the mass, and the weight the background has in the distance correlation
        K = _keras_ops()

        def plain(y_true, y_pred):
            return keras.losses.sparse_categorical_crossentropy(keras.ops.cast(y_true[:, 0], "int32"), y_pred,
                                                                from_logits=True)

        def disco(y_true, y_pred):
            return plain(y_true, y_pred) + lam * _dcorr(K, keras.ops.softmax(y_pred, axis=-1)[:, 1], y_true[:, 1],
                                                        y_true[:, 2])

        y_fit = np.stack([tr["y"], tr["mass"], tr["w"] * (tr["y"] == 0)], 1).astype(wdtype)
    else:
        plain = disco = keras.losses.SparseCategoricalCrossentropy(from_logits=True)
        y_fit = tr["y"]
    history = []
    state = {"best": None, "best_loss": float("inf"), "wait": 0, "over": False, "cut": False}

    class Progress(keras.callbacks.Callback):
        def on_train_batch_end(self, batch, logs=None):
            if canceled():
                self.model.stop_training = True
                return
            b = governor.relieve("in epoch %d" % (len(history) + 1))
            if b != rate["batch"]:
                state["cut"], state["over"] = True, b == 0
                self.model.stop_training = True

        def on_epoch_end(self, epoch, logs=None):
            if state["cut"] or canceled():
                return   # an epoch cut short is not one: trained again at the new batch, or the training is over
            gc.collect(0)
            if keras.backend.backend() == "torch":
                # the same function by Sphere's own PyTorch forward, from Keras's weights (':ml crosscheck'
                # holds the two to float32's rounding): Keras's predictions on PyTorch cost ten times the memory,
                # and the heap kept what each validation took -- 150 to 200 MB more held at every epoch
                logits = _torch_predict(_torch_net(spec, self.model.weights_np(), opts["precision"]), va["inputs"])
            else:
                logits = _keras_predict(self.model, va["inputs"])
            val = _evaluate(logits, va)
            gc.collect()   # once an epoch: what the validation left in cycles, of every generation
            val["epoch"], val["loss"] = epoch + 1, float((logs or {}).get("loss", float("nan")))
            history.append(val)
            report(epoch + 1, val)
            if lam and epoch == warm:   # decorrelating from here: the best is chosen among these epochs
                state.update(best=None, best_loss=float("inf"), wait=0)
            score = _selection(val, lam if epoch >= warm else 0.0)
            if score < state["best_loss"] - 1e-4:
                state.update(best=self.model.weights_np(), best_loss=score, wait=0)
            elif epoch >= warm:
                state["wait"] += 1
                if state["wait"] >= opts["patience"]:
                    state["over"] = True
                    self.model.stop_training = True

    net(_take(tr["inputs"], np.arange(min(2, n))))   # built on a real batch: its shapes are the data's
    governor.regime(int(rate["batch"]))
    while len(history) < opts["epochs"] and not state["over"] and not canceled():
        # compiled afresh at each batch (a new train step, whose schedule reads the batch of the time), and when
        # DisCo's term joins the loss
        decorrelating = len(history) >= warm
        net.compile(optimizer=optimizer, loss=disco if decorrelating else plain)
        state["cut"] = False
        step0 = float(keras.ops.convert_to_numpy(optimizer.iterations))
        try:
            net.fit(tr["inputs"], y_fit, sample_weight=tr["w"].astype(wdtype),
                    epochs=opts["epochs"] if decorrelating else warm, initial_epoch=len(history),
                    batch_size=int(rate["batch"]), shuffle=True, verbose=0, callbacks=[Progress()])
        except MemoryExceeded:
            gc.collect()
            history.append({"epoch": len(history) + 1, "stopped": "memory"})
            governor.note("memory: " + governor.events[-1] + "; the training stops there")
            break
        if not state["cut"]:
            if decorrelating:
                break
            rate.update(seen=rate["seen"] + (float(keras.ops.convert_to_numpy(optimizer.iterations)) - step0)
                        * rate["batch"], step=float(keras.ops.convert_to_numpy(optimizer.iterations)))
            continue   # the features learned: now decorrelated
        steps = float(keras.ops.convert_to_numpy(optimizer.iterations)) - step0
        rate.update(seen=rate["seen"] + steps * rate["batch"], step=step0 + steps, batch=float(governor.batch_now))
        if state["over"]:
            history.append({"epoch": len(history) + 1, "stopped": "memory"})
    if canceled():
        history.append({"epoch": len(history) + 1, "canceled": True})
    if state["best"] is not None:
        net.load(state["best"])
    return net, history


# ---------------------------------------------------------------------------
# Training, prediction, export
# ---------------------------------------------------------------------------

def models_folder():
    return os.path.join(spx.bridge_folder(), "models")


def fit(data, model="deepsets", backend="torch", epochs=20, batch=None, lr=1e-3, seed=7, val=0.2, test=0.2,
        patience=5, weight_decay=1e-4, name=None, balance=True, report=None, canceled=None, precision="float32",
        note=None, decorrelate=None, **kw):
    """Trains a network of the zoo on a dataset and exports it. Classes are balanced by weights unless told;
    the test rows, never seen in training, give the metrics.

    decorrelate="mass" (or "mass:lambda") adds DisCo's term, lambda times the score's distance correlation to
    the background's mass, so that a cut on the tagger leaves the background's mass as it was: no bump made
    for a bump hunt to find. The sculpting the metrics report measures what it achieved.

    The batch is the memory governor's (MemoryGovernor): the largest up to 256 that fits what the machine
    can spare, halved if the training comes near it, the training stopped before the machine runs out; a
    batch asked for is kept, under the same guard. note(text) hears what it decides.

    precision="float64" trains in double precision throughout -- weights, inputs, gradients, optimizer --
    a few times slower: the network is then the same function to 1e-16 in every framework, Java and C++,
    with no float32 rounding left between the training and the exported weights."""
    if isinstance(data, str):
        data = dataset(data)
    report = report or (lambda epoch, logs: None)
    canceled = canceled or (lambda: False)
    note = note or (lambda text: None)
    precision = _precision(precision)
    variable, lam = _decorrelation(decorrelate)
    if lam and data.mass is None:
        raise ValueError(data.name + " has no jet masses: DisCo decorrelates the score from them")
    spec = make_spec(model, data, **kw)
    tr_rows, va_rows, te_rows = _split(len(data), seed, val, test)
    norm = fit_norm(spec, data, tr_rows)
    spec["norm"] = norm
    w = np.asarray(data.w, dtype=np.float64).copy()
    if balance:
        for c in range(spec["classes"]):
            sel = data.y == c
            if sel.any():
                w[sel] *= len(data.y) / (spec["classes"] * w[sel].sum())

    def part(rows):
        p = {"inputs": prepare(spec, norm, dtype=_np_dtype(precision), **_rows(data, rows)), "y": data.y[rows],
             "w": w[rows]}
        if lam:
            p["mass"] = np.asarray(data.mass[rows], dtype=np.float64)
        return p

    tr, va, te = part(tr_rows), part(va_rows), part(te_rows)
    params = init_params(spec, seed)
    opts = {"epochs": int(epochs), "lr": float(lr), "seed": int(seed), "patience": int(patience),
            "weight_decay": float(weight_decay), "precision": precision, "decorrelate": lam}
    if lam:
        spec["decorrelate"] = {"variable": variable, "lambda": lam}
    if backend not in ("torch", "keras", "tensorflow"):
        raise ValueError("backend %s: torch, keras or tensorflow" % backend)
    if backend == "torch":
        import torch  # noqa: F401 -- imported before the plan: its memory is the process's, not the training's
        on = "torch"
    else:
        on = "keras/" + _keras(require_tensorflow=backend == "tensorflow").backend.backend()
    governor = MemoryGovernor(spec, on, precision, note)
    opts["batch"], plan = governor.plan(256 if batch is None else int(batch), asked=batch is not None)
    note(plan)
    t0 = time.perf_counter()
    f = frameworks()
    float64_keras = precision == "float64" and backend == "keras"
    try:
        with governor:
            if backend == "torch":
                net, history = _train_torch(spec, params, tr, va, opts, report, canceled, governor)
            else:
                # trained and read in JAX's 64-bit mode when asked: outside it a float64 variable reads as float32
                with _x64_scope(float64_keras):
                    net, history = _train_keras(spec, params, tr, va, opts, report, canceled, governor,
                                                tensorflow=backend == "tensorflow")
        gc.collect()   # what the training left in cycles, before the predictions
        ended = [h for h in history if "val_loss" in h]
        if any(h.get("stopped") == "memory" for h in history):
            if not ended:
                mb = 2.0 ** 20
                raise RuntimeError("no epoch could end within the %.0f MB this machine can spare to the training (it"
                                   " reached %.0f MB): a smaller batch (--batch), a smaller network (--points, --k), or"
                                   " more memory free" % (governor.budget / mb, governor.peak / mb))
            best = min(ended, key=lambda h: h["val_loss"])
            note("memory: the network kept is epoch %d's, the best of the %d that ended" % (best["epoch"], len(ended)))
        seconds = time.perf_counter() - t0
        if backend == "torch":
            logits, weights = _torch_predict(net, te["inputs"]), net.weights()
            engine = "torch " + f["torch"]
        else:
            with _x64_scope(float64_keras):
                logits, weights = _keras_predict(net, te["inputs"]), net.weights_np()
            engine = "keras %s on %s" % (f["keras"], _keras().backend.backend())
        if precision == "float64":
            engine += " in float64"
        if lam:
            engine += ", DisCo lambda %g" % lam
        spec["precision"] = precision
        prob = _softmax_np(logits)
        m = metrics(prob, te["y"], te["w"], None if data.mass is None else data.mass[te_rows])
        t1 = time.perf_counter()
        ref = _forward_np(NumpyOps, spec, weights, _take(te["inputs"], np.arange(min(256, len(te["y"])))))
        m["numpy_vs_" + backend] = float(np.max(np.abs(ref - logits[:len(ref)]))) if len(ref) else 0.0
        m["inference_us_per_jet"] = (time.perf_counter() - t1) * 1e6 / max(1, len(ref))
        # a file's name on every system: "efn_mass:3" is no name on Windows
        name = re.sub(r'[<>:"/\\|?*\s]+', "_", name or "%s_%s_%s" % (data.name, model, backend)).strip("._") or "network"
        memory = governor.summary()
        spec.update({"name": name, "trained_with": engine, "dataset": data.name, "seed": int(seed),
                     "split": [float(val), float(test)],
                     "epochs_run": len(history), "seconds": seconds, "metrics": m, "batch": opts["batch"], "memory": memory})
        path = export(name, spec, weights)
        eff_s, eff_b, _ = roc(prob[:, 1], te["y"], te["w"])
        _publish_roc(name, eff_s, eff_b)
        return {"name": name, "path": path, "model": model, "backend": backend, "engine": engine, "metrics": m,
                "history": history, "parameters": int(sum(v.size for v in weights.values())), "seconds": seconds,
                "test_jets": int(len(te["y"])), "batch": opts["batch"], "memory": memory}
    finally:
        _release()   # what the frameworks kept of this training, given back


def path(name):
    """Where a dataset of that name is: $SPHERE_BRIDGE/<name>.spx (a path is kept as it is)."""
    return _resolve(name)


def publish_roc(name, score, y, w=None, signal=1):
    """A tagger's ROC to Sphere's Plots tab, from its scores and the true classes: what a hand-written
    network in PyTorch, Keras or JAX shows as the zoo's do."""
    eff_s, eff_b, _ = roc(np.asarray(score, dtype=float), np.asarray(y), w, signal)
    _publish_roc(name, eff_s, eff_b)


def _publish_roc(name, eff_s, eff_b):
    """The ROC curve to Sphere's Plots tab: background rejection 1/eps_B against the signal efficiency."""
    grid = np.linspace(0.05, 0.95, 19)
    rej = []
    for g in grid:
        k = min(int(np.searchsorted(eff_s, g)), len(eff_b) - 1)
        rej.append(1.0 / eff_b[k] if eff_b[k] > 0 else 0.0)
    edges = np.concatenate([grid - 0.025, [grid[-1] + 0.025]])
    try:
        spx.publish("roc_" + name, edges.tolist(), rej, xlabel="signal efficiency (1/eps_B)")
    except Exception:
        pass   # a plot is a courtesy


def export(name, spec, weights):
    """The network as SPX, models/<name>.snn.spx: every weight as float64 under its name, the preprocessing
    (norm.x_mean...), the architecture as plain "Key: value" lines (what Java's NeuralNet and C++'s
    sphere_nn.hpp read, no JSON parser needed), and the whole spec, metrics included, as JSON for Python."""
    shapes = {k: list(np.shape(v)) for k, v in weights.items()}
    spec = dict(spec)
    spec["shapes"] = shapes

    def ints(v):
        return ",".join(str(int(a)) for a in v)

    meta = {"Kind": "sphere-nn", "Model": spec["model"], "Name": spec.get("name", name),
            "Classes": ",".join(spec.get("class_names", [])), "NClasses": spec["classes"],
            "Features": spec["features"], "Points": spec["points"], "HighLevel": spec["high_level"],
            "Coords": ints(spec["coords"]), "PtIndex": spec["pt_index"], "TrainedWith": spec.get("trained_with", "")}
    m = spec["model"]
    if m == "mlp":
        meta["Widths"] = ints(spec["widths"])
    elif m in ("deepsets", "efn"):
        meta["Phi"], meta["Rho"] = ints(spec["phi"]), ints(spec["rho"])
    elif m == "particlenet":
        meta["K"], meta["Fc"] = spec["k"], spec["fc"]
        meta["Blocks"] = ";".join(ints(b) for b in spec["blocks"])
    elif m == "transformer":
        for key in ("dim", "heads", "layers", "ffn", "pair"):
            meta[key.capitalize()] = spec[key]
    sections = [("spec", json.dumps(_finite(spec), allow_nan=False))]
    for key, values in spec.get("norm", {}).items():
        sections.append(("norm." + key, np.asarray(values, dtype=np.float64)))
    c = spec.get("calibration") or {}
    if c.get("method") == "temperature":   # what NeuralNet and sphere_nn.hpp apply in probabilities()
        meta["Calibration"], meta["Temperature"] = "temperature", repr(float(c["temperature"]))
    elif c.get("method") == "isotonic":
        meta["Calibration"] = "isotonic"
        sections += [("calib.x", np.asarray(c["x"], dtype=np.float64)), ("calib.y", np.asarray(c["y"], dtype=np.float64))]
    for k in param_shapes(spec):
        sections.append((k, np.asarray(weights[k], dtype=np.float64).ravel()))
    return write_spx(os.path.join(models_folder(), name + ".snn.spx"), name, sections, meta)


def load(name):
    """A network exported by export(): its spec and its weights (float64)."""
    path = name if name.endswith(".spx") else os.path.join(models_folder(), name + ".snn.spx")
    f = spx.SPXFile(path)
    spec = json.loads(f.text("spec"))
    weights = {k: np.array(f.array(k)).reshape(spec["shapes"][k]) for k in param_shapes(spec)}
    return spec, weights


def models():
    folder = models_folder()
    out = []
    if os.path.isdir(folder):
        for fn in sorted(os.listdir(folder)):
            if fn.endswith(".snn.spx"):
                try:
                    spec = json.loads(spx.SPXFile(os.path.join(folder, fn)).text("spec"))
                    out.append({"name": fn[:-8], "model": spec["model"], "trained_with": spec.get("trained_with", ""),
                                "dataset": spec.get("dataset", ""), "auc": spec.get("metrics", {}).get("auc"),
                                "calibration": (spec.get("calibration") or {}).get("method", ""),
                                "path": os.path.join(folder, fn)})
                except Exception as e:
                    out.append({"name": fn[:-8], "error": str(e)})
    return out


def predict(model, data, engine="numpy", out=None, precision=None):
    """The class probabilities of every row of a dataset, by the numpy reference or a framework, through the
    network's calibration if ':ml calibrate' gave it one; written as SPX (prob, y) when asked, with the
    metrics when the dataset has labels. numpy computes in float64, the frameworks in float32 unless
    precision="float64"."""
    spec, weights = load(model) if isinstance(model, str) else model
    if isinstance(data, str):
        data = dataset(data)
    precision = _precision(precision or ("float64" if engine == "numpy" else "float32"))
    inputs = prepare(spec, spec["norm"], x=data.x, mask=data.mask, hl=data.hl, dtype=_np_dtype(precision))
    if engine == "numpy":
        ops = NumpyOps if precision == "float64" else NumpyOps32
        P = weights if precision == "float64" else {k: v.astype(np.float32) for k, v in weights.items()}
        logits = _forward_np(ops, spec, P, inputs)
    elif engine == "torch":
        logits = _torch_predict(_torch_net(spec, weights, precision), inputs)
    else:
        with _x64_scope(precision == "float64"):
            logits = _keras_predict(_keras_net(spec, weights, precision), inputs)
    prob = calibrated(spec, np.asarray(logits, dtype=np.float64))
    result = {"rows": int(len(prob)), "engine": engine, "precision": precision,
              "calibration": (spec.get("calibration") or {}).get("method", "")}
    if data.y is not None and len(set(data.y.tolist())) > 1:
        result["metrics"] = metrics(prob, data.y, data.w, data.mass)
    if out:
        write_spx(out, "prediction", [("prob", prob), ("y", data.y if data.y is not None else np.zeros(len(prob)))],
                  {"Kind": "ml-prediction", "Model": spec.get("name", ""), "Classes": spec["classes"], "Engine": engine})
        result["path"] = out
    return prob, result


# A difference within this many times the rounding numpy itself shows at the same precision is that rounding,
# only the order of the sums apart; another function, or a flipped neighbor, lies orders of magnitude beyond.
ROUNDING_FACTOR = 64


def crosscheck(model, data, rows=256):
    """One network, its weights copied into each framework, which compute the logits of the same jets in
    float64 and in float32, held to numpy in float64.

    A difference is judged against the rounding of the precision it was made in. numpy run in float32
    throughout shows float32's own (noise float32); rounding errors scale with the machine epsilon, so
    float64's is that times 2**-29 (noise float64). Within ROUNDING_FACTOR times its noise, an engine
    computes the same function, its last digits apart by the order of its sums only. Beyond it in float64,
    the function differs: a bug. Beyond it in float32 only, float64 agreeing, a discrete choice flips with
    the rounding on some jets -- a near-tie among ParticleNet's nearest neighbors. The float64 reference is
    written out, for Java and C++ to be held to it as well."""
    spec, weights = load(model)
    if isinstance(data, str):
        data = dataset(data)
    n = min(rows, len(data))
    inputs64 = prepare(spec, spec["norm"], x=None if data.x is None else data.x[:n],
                       mask=None if data.mask is None else data.mask[:n], hl=None if data.hl is None else data.hl[:n],
                       dtype=np.float64)
    ref = _forward_np(NumpyOps, spec, weights, inputs64)
    inputs = {"float64": inputs64, "float32": {k: v.astype(np.float32) for k, v in inputs64.items()}}
    ref32 = _forward_np(NumpyOps32, spec, {k: v.astype(np.float32) for k, v in weights.items()}, inputs["float32"])
    scale = float(np.max(np.abs(ref))) if n else 0.0
    eps32, eps64 = float(np.finfo(np.float32).eps), float(np.finfo(np.float64).eps)
    floor = max(scale, 1e-30)
    noise32 = max(float(np.max(np.abs(ref32 - ref))) if n else 0.0, eps32 * floor)
    noise = {"float32": noise32, "float64": max(noise32 * eps64 / eps32, eps64 * floor)}
    out = {"rows": int(n), "model": spec["model"], "scale": scale, "noise": noise, "factor": ROUNDING_FACTOR,
           "trained_in": spec.get("precision", "float32"), "engines": {}}

    def held(name, make, run, scope):
        entry = {}
        for p in ("float64", "float32"):
            try:
                with scope(p == "float64"):
                    net = make(p)
                    b = _predict_batch(*net.memory)   # one batch for both calls: JAX compiles once per shape
                    run(net, inputs[p], b)   # untimed: the first call compiles or allocates
                    t0 = time.perf_counter()
                    logits = np.asarray(run(net, inputs[p], b), dtype=np.float64)
                    us = (time.perf_counter() - t0) * 1e6 / max(1, n)
                d = np.max(np.abs(logits - ref), axis=1) if n else np.zeros(0)
                worst = float(d.max()) if n else 0.0
                entry[p] = {"max_abs": worst, "us_per_jet": us, "noise_units": worst / noise[p],
                            "beyond": int(np.sum(d > ROUNDING_FACTOR * noise[p]))}
            except Exception as e:
                entry[p] = {"error": "%s: %s" % (type(e).__name__, e)}
        entry["verdict"] = verdict(entry)
        out["engines"][name] = entry

    f = frameworks()
    if f["torch"]:
        held("torch " + f["torch"], lambda p: _torch_net(spec, weights, p), _torch_predict,
             lambda on: contextlib.nullcontext())
    if f["keras"]:
        try:
            held("keras %s on %s" % (f["keras"], _keras().backend.backend()), lambda p: _keras_net(spec, weights, p),
                 _keras_predict, _x64_scope)
        except Exception as e:
            out["engines"]["keras"] = {"error": "%s: %s" % (type(e).__name__, e)}
    path = os.path.join(spx.bridge_folder(), "ml", spec.get("name", "model") + ".reference.spx")
    write_spx(path, "reference", [("logits", ref), ("rows", np.arange(n))],
              {"Kind": "ml-reference", "Model": spec.get("name", ""), "Rows": n, "Dataset": data.path})
    out["reference"] = path
    return out


def verdict(entry):
    """What an engine's float64 and float32 differences to the reference say, each in units of the rounding
    of its precision (crosscheck's noise_units)."""
    d64, d32 = entry.get("float64", {}), entry.get("float32", {})
    if "noise_units" not in d64:
        return "float64 failed: " + d64.get("error", "?")
    if d64["noise_units"] > ROUNDING_FACTOR:
        return "another function: %d jet(s) beyond float64's rounding" % d64["beyond"]
    if "noise_units" not in d32 or d32["noise_units"] <= ROUNDING_FACTOR:
        return "same function"
    return ("same function; in float32 a discrete choice flips with the rounding on %d jet(s) (a near-tie)"
            % d32["beyond"])


# ---------------------------------------------------------------------------
# Infrared and collinear safety: what a network makes of a soft emission and a collinear splitting
# ---------------------------------------------------------------------------

IRC_EPS = (1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6)


def _jets_back(data, spec, rows):
    """The particles of a dataset's jets as kinematics -- pt, E, delta eta and delta phi to the jet's axis --
    with the jet's own pt and E, from the features ':ml jets' and toy_jets write; jets with a free slot for
    one more particle only (the probes add one)."""
    names = data.features
    need = ("log_pt", "log_e", "log_ptrel", "log_erel")
    if any(f not in names for f in need) or len(spec["coords"]) != 2:
        raise ValueError("the probe rebuilds a jet from its particles' log_pt, log_e, log_ptrel, log_erel and two"
                         " angles: %s has %s" % (data.name, ", ".join(names)))
    ip, ie, ipr, ier = (names.index(f) for f in need)
    ia, ib = spec["coords"]
    n_cap = spec["points"]
    jets = []
    for j in range(len(data)):
        k = int(data.mask[j].sum())
        if k < 2 or k >= n_cap:
            continue
        x = np.asarray(data.x[j, :k], dtype=np.float64)
        pt, e = np.exp(x[:, ip]), np.exp(x[:, ie])
        jets.append({"pt": pt, "e": e, "deta": x[:, ia].copy(), "dphi": x[:, ib].copy(),
                     "ptj": float(np.exp(x[0, ip] - x[0, ipr])), "ej": float(np.exp(x[0, ie] - x[0, ier]))})
        if len(jets) == rows:
            break
    return jets


def _jets_forth(jets, data, n_cap):
    """The dataset's features of those jets again, as a batch of the network's points (x, mask)."""
    names = data.features
    x = np.zeros((len(jets), n_cap, len(names)))
    mask = np.zeros((len(jets), n_cap))
    for j, jet in enumerate(jets):
        k = len(jet["pt"])
        cols = {"log_pt": np.log(jet["pt"]), "log_e": np.log(jet["e"]), "log_ptrel": np.log(jet["pt"] / jet["ptj"]),
                "log_erel": np.log(jet["e"] / jet["ej"]), "deta": jet["deta"], "dphi": jet["dphi"],
                "dr": np.hypot(jet["deta"], jet["dphi"])}
        for f, name in enumerate(names):
            x[j, :k, f] = cols.get(name, 0.0)
        mask[j, :k] = 1
    return x, mask


def _soft(jet, eps, angle, r=0.3):
    """The jet with one more particle, of eps of its pt, at distance r from its axis: the jet's pt and E grow
    by it, and its axis moves toward it by eps r, as an E-scheme jet's does to first order."""
    pts = eps * jet["ptj"]
    da, db = r * math.cos(angle), r * math.sin(angle)
    ptj = jet["ptj"] + pts
    shift = pts / ptj
    return {"pt": np.append(jet["pt"], pts), "e": np.append(jet["e"], pts * jet["ej"] / jet["ptj"]),
            "deta": np.append(jet["deta"], da) - shift * da, "dphi": np.append(jet["dphi"], db) - shift * db,
            "ptj": ptj, "ej": jet["ej"] + pts * jet["ej"] / jet["ptj"]}


def _splitting(jet, delta, angle, share=0.5):
    """The jet with its hardest particle split in two, sharing its momentum, delta apart in angle around where
    it was (its pt-weighted center kept): the jet itself unchanged, to first order."""
    h = int(np.argmax(jet["pt"]))
    ua, ub = math.cos(angle), math.sin(angle)
    out = {k: (v.copy() if isinstance(v, np.ndarray) else v) for k, v in jet.items()}
    pt, e, a, b = jet["pt"][h], jet["e"][h], jet["deta"][h], jet["dphi"][h]
    out["pt"][h], out["e"][h] = share * pt, share * e
    out["deta"][h], out["dphi"][h] = a + (1 - share) * delta * ua, b + (1 - share) * delta * ub
    out["pt"] = np.append(out["pt"], (1 - share) * pt)
    out["e"] = np.append(out["e"], (1 - share) * e)
    out["deta"] = np.append(out["deta"], a - share * delta * ua)
    out["dphi"] = np.append(out["dphi"], b - share * delta * ub)
    return out


def _irc_verdict(eps, median):
    """Safe if the change vanishes as eps does; unsafe if it stays."""
    small = np.asarray(median[-3:], dtype=float)
    if np.max(small) < 1e-9:
        return 0.0, "safe: no change at all (%.1e)" % np.max(small)
    slope = float(np.polyfit(np.log(np.asarray(eps[-3:])), np.log(np.maximum(small, 1e-300)), 1)[0])
    if slope >= 0.7:
        return slope, "safe: the change vanishes as eps^%.2f (%.1e at eps = %g)" % (slope, small[-1], eps[-1])
    if slope <= 0.3:
        return slope, "UNSAFE: the change stays (%.1e at eps = %g, as eps^%.2f)" % (small[-1], eps[-1], slope)
    return slope, "marginal: the change falls as eps^%.2f only (%.1e at eps = %g)" % (slope, small[-1], eps[-1])


def irc(model, data, rows=64, eps=IRC_EPS, seed=1):
    """Infrared and collinear safety, measured: what the network's probabilities do when each jet gains a
    particle of eps of its pt (soft), and when its hardest particle splits in two, eps apart (collinear), as
    eps goes to 0. An IRC-safe observable -- what perturbative QCD can predict, what is robust to the
    hadronization's soft particles and the detector's splittings -- changes by O(eps): the change vanishes. A
    network that sees each particle's own pt or count does not: a particle of 1e-6 of the jet moves it all
    the same. Energy Flow Networks are safe by construction; this shows which networks are, numerically,
    with numpy's float64 reference of the network."""
    spec, weights = load(model) if isinstance(model, str) else model
    if isinstance(data, str):
        data = dataset(data)
    if spec["model"] == "mlp":
        raise ValueError("the MLP sees high-level features computed from the particles (Sphere's Java, FastJet"
                         " contrib): probe a network of the particles (deepsets, efn, particlenet, transformer)")
    jets = _jets_back(data, spec, int(rows))
    if not jets:
        raise ValueError("no jet of %s has a free slot for one more particle (the network takes %d)" % (
            data.name, spec["points"]))
    rng = np.random.default_rng(seed)
    angles = rng.uniform(0, 2 * math.pi, len(jets))

    def probabilities(batch):
        x, mask = _jets_forth(batch, data, spec["points"])
        inputs = prepare(spec, spec["norm"], x=x, mask=mask, dtype=np.float64)
        return _softmax_np(_forward_np(NumpyOps, spec, weights, inputs))

    p0 = probabilities(jets)
    out = {"model": spec["model"], "name": spec.get("name", ""), "jets": len(jets), "eps": list(eps)}
    for probe, change in (("soft", _soft), ("collinear", _splitting)):
        median, high = [], []
        for e in eps:
            d = 0.5 * np.abs(probabilities([change(j, e, a) for j, a in zip(jets, angles)]) - p0).sum(axis=1)
            median.append(float(np.median(d)))
            high.append(float(np.quantile(d, 0.9)))
        slope, text = _irc_verdict(eps, median)
        out[probe] = {"median": median, "p90": high, "slope": slope, "verdict": text}
    out["safe"] = not any(out[p]["verdict"].startswith(("UNSAFE", "marginal")) for p in ("soft", "collinear"))
    try:
        edges = np.log10(np.asarray(eps, dtype=float))[::-1]
        edges = np.concatenate([edges - 0.5, [edges[-1] + 0.5]])
        for probe in ("soft", "collinear"):
            spx.publish("irc_%s_%s" % (probe, spec.get("name", "model")), edges.tolist(),
                        np.log10(np.maximum(out[probe]["median"][::-1], 1e-17)).tolist(),
                        xlabel="log10 eps (log10 of the median change of the probabilities)")
    except Exception:
        pass   # a plot is a courtesy
    return out


# ---------------------------------------------------------------------------
# The audit: a trained tagger refereed as a physics analysis would
# ---------------------------------------------------------------------------

def _auc_error(auc, n_signal, n_background):
    """The statistical error of an AUC from n_signal and n_background jets (Hanley, McNeil, Radiology 143
    (1982) 29)."""
    a = min(max(auc, 1e-9), 1 - 1e-9)
    q1, q2 = a / (2 - a), 2 * a * a / (1 + a)
    var = (a * (1 - a) + (n_signal - 1) * (q1 - a * a) + (n_background - 1) * (q2 - a * a)) / max(
        1, n_signal * n_background)
    return math.sqrt(max(var, 0.0))


def _auc_at_fixed_mass(p, y, mass):
    """The AUC of jets whose mass is in the signal's central half (its 25 % and 75 % quantiles), and its
    statistical error: what the tagger separates once the mass is nearly the same -- its power beyond a mass
    cut. (None, None) if too few jets."""
    lo, hi = np.quantile(mass[y == 1], [0.25, 0.75])
    s = (mass >= lo) & (mass <= hi)
    ns, nb = int(np.sum(y[s] == 1)), int(np.sum(y[s] == 0))
    if ns < 10 or nb < 10:
        return None, None
    auc = metrics(np.stack([1 - p[s], p[s]], 1), y[s])["auc"]
    return auc, _auc_error(auc, ns, nb)


def _significant(auc, err):
    return auc is not None and auc > 0.5 + 3 * err


def _calibration(p, y, w, bins=10):
    """The expected calibration error of P(signal): over bins of it, how far the mean probability is from the
    signal fraction there, weighted by the bins' share (0: P = 0.8 means 80 %)."""
    idx = np.clip((p * bins).astype(int), 0, bins - 1)
    total, ece = w.sum(), 0.0
    for b in range(bins):
        s = idx == b
        if w[s].sum() > 0:
            ece += w[s].sum() / total * abs(np.average(p[s], weights=w[s]) - np.average(y[s] == 1, weights=w[s]))
    return float(ece)


def _temperature(logits, y, w):
    """The temperature T whose softmax(logits / T) has the least weighted log loss on these jets: the one
    number that calibrates a network without changing its ranking (Guo et al. 2017), by golden section."""
    def nll(t):
        z = logits / t
        z = z - z.max(1, keepdims=True)
        logp = z - np.log(np.exp(z).sum(1, keepdims=True))
        return -float(np.sum(w * logp[np.arange(len(y)), y]) / w.sum())

    a, b = math.log(0.05), math.log(20.0)
    g = (math.sqrt(5) - 1) / 2
    c, d = b - g * (b - a), a + g * (b - a)
    for _ in range(60):
        if nll(math.exp(c)) < nll(math.exp(d)):
            b, d = d, c
            c = b - g * (b - a)
        else:
            a, c = c, d
            d = a + g * (b - a)
    return math.exp((a + b) / 2)


def _isotonic(x, y, w):
    """The weighted isotonic regression of y on x -- the non-decreasing function nearest to y in least
    squares -- by pool-adjacent-violators: the knots (each pooled block's mean x) and their levels (its mean y)."""
    order = np.argsort(x, kind="stable")
    sw, swx, swy = [], [], []
    for xi, yi, wi in zip(x[order], y[order], w[order]):
        sw.append(wi)
        swx.append(wi * xi)
        swy.append(wi * yi)
        while len(sw) > 1 and swy[-2] * sw[-1] >= swy[-1] * sw[-2]:   # a level not above the previous: pooled
            w1, x1, y1 = sw.pop(), swx.pop(), swy.pop()
            sw[-1] += w1
            swx[-1] += x1
            swy[-1] += y1
    sw = np.asarray(sw)
    return np.asarray(swx) / sw, np.asarray(swy) / sw


def calibrated(spec, logits):
    """The class probabilities of these logits through the network's calibration, if ':ml calibrate' gave it
    one: softmax(logits / T) for a temperature; P(signal) through the isotonic map -- linear between its knots,
    its end levels beyond -- for isotonic. What Java's NeuralNet.probabilities and C++'s
    Network::probabilities compute too, with np.interp's formula."""
    c = spec.get("calibration") or {}
    if c.get("method") == "temperature":
        return _softmax_np(np.asarray(logits) / c["temperature"])
    p = _softmax_np(np.asarray(logits))
    if c.get("method") == "isotonic" and p.shape[1] == 2:
        p1 = np.clip(np.interp(p[:, 1], c["x"], c["y"]), 0.0, 1.0)
        return np.stack([1.0 - p1, p1], 1)
    return p


def _brier(p1, y, w):
    """The weighted Brier score: a proper score, lowest for the true probabilities, bounded."""
    return float(np.sum(w * (p1 - (y == 1)) ** 2) / np.sum(w))


def _balanced(y, rows, seed):
    """As many signal as background jets of these rows, drawn: P(signal) is a probability in the training's
    50/50 world."""
    rng = np.random.default_rng(seed)
    s, b = rows[y[rows] == 1], rows[y[rows] == 0]
    k = min(len(s), len(b))
    pick = np.concatenate([rng.choice(s, k, replace=False), rng.choice(b, k, replace=False)])
    rng.shuffle(pick)
    return pick


def _ece_floor(p1, seed=11, draws=32):
    """The calibration error a perfectly calibrated network shows on jets of these probabilities, its labels
    drawn from them: the statistics' floor."""
    rng = np.random.default_rng(seed)
    ones = np.ones(len(p1))
    return float(np.mean([_calibration(p1, (rng.uniform(size=len(p1)) < p1).astype(int), ones) for _ in range(draws)]))


def calibrate(model, data, method="auto"):
    """P(signal) made a probability, and written into the network's export for every engine to apply.

    Fitted on jets the training never learned: the training dataset's validation rows (they chose the epoch,
    no more), judged on its test rows; another dataset is split in halves. The candidates are no calibration,
    a temperature (one number: softmax(logits / T), the ranking kept) and an isotonic map (monotone, any
    shape, the ranking kept but for ties); "auto" keeps the one of lowest Brier score on the judging jets,
    a more elaborate one only if it does 1 % better -- a calibrated network is left as it is."""
    spec, weights = load(model) if isinstance(model, str) else model
    if isinstance(data, str):
        data = dataset(data)
    if spec["classes"] != 2:
        raise ValueError("calibrating P(signal) needs two classes; %s has %d" % (spec.get("name"), spec["classes"]))
    if method not in ("auto", "isotonic", "temperature", "none"):
        raise ValueError("method %s: auto, isotonic, temperature or none" % method)
    spec.pop("calibration", None)   # calibrated afresh from the network's own logits
    y = np.asarray(data.y)
    if spec.get("dataset") == data.name and "seed" in spec:
        _, va, te = _split(len(data), int(spec["seed"]), *spec.get("split", [0.2, 0.2]))
        fit_rows, judge_rows = np.sort(va), np.sort(te)
        where = ("its %d validation jets (they chose the epoch, never trained on)" % len(fit_rows),
                 "its %d test jets" % len(judge_rows))
    else:
        order = np.random.default_rng(3).permutation(len(data))
        fit_rows, judge_rows = np.sort(order[: len(data) // 2]), np.sort(order[len(data) // 2:])
        where = ("half of %s (%d jets)" % (data.name, len(fit_rows)), "the other half (%d jets)" % len(judge_rows))

    def logits_of(rows):
        inputs = prepare(spec, spec["norm"], x=None if data.x is None else data.x[rows],
                         mask=None if data.mask is None else data.mask[rows],
                         hl=None if data.hl is None else data.hl[rows], dtype=np.float64)
        return _forward_np(NumpyOps, spec, weights, inputs)

    fit_rows, judge_rows = _balanced(y, fit_rows, 5), _balanced(y, judge_rows, 7)
    lf, lj = logits_of(fit_rows), logits_of(judge_rows)
    yf, yj = y[fit_rows], y[judge_rows]
    ones_f, ones_j = np.ones(len(yf)), np.ones(len(yj))
    t = _temperature(lf, yf, ones_f)
    kx, ky = _isotonic(_softmax_np(lf)[:, 1], (yf == 1).astype(float), ones_f)
    keep = np.concatenate([[True], np.diff(kx) > 0])   # knots strictly increasing, as interpolation needs
    kx, ky = kx[keep], ky[keep]
    maps = {"none": None, "temperature": {"method": "temperature", "temperature": t},
            "isotonic": {"method": "isotonic", "x": kx.tolist(), "y": ky.tolist()}}
    table = {}
    for name, c in maps.items():
        p1 = calibrated({"calibration": c}, lj)[:, 1]
        table[name] = {"brier": _brier(p1, yj, ones_j), "ece": _calibration(p1, yj, ones_j)}
    if method == "auto":
        chosen = "none"
        for name in ("temperature", "isotonic"):
            if table[name]["brier"] < 0.99 * table[chosen]["brier"]:
                chosen = name
    else:
        chosen = method
    if maps[chosen]:
        spec["calibration"] = dict(maps[chosen], fitted_on=len(fit_rows), judged_on=len(judge_rows),
                                   ece=table[chosen]["ece"], brier=table[chosen]["brier"])
    path = export(spec["name"], spec, weights)
    p_chosen = calibrated(spec, lj)
    floor = _ece_floor(p_chosen[:, 1])
    # the judging jets with what numpy makes of them, for Java and C++ to be held to it
    reference = os.path.join(spx.bridge_folder(), "ml", spec["name"] + ".calibration.spx")
    os.makedirs(os.path.dirname(reference), exist_ok=True)
    write_dataset(reference, "calibration", np.asarray(data.x[judge_rows]) if data.x is not None else
                  np.zeros((len(judge_rows), 0, 0)), np.asarray(data.mask[judge_rows]) if data.mask is not None else
                  np.zeros((len(judge_rows), 0)), yj, hl=None if data.hl is None else np.asarray(data.hl[judge_rows]),
                  high_level=data.high_level, features=data.features, extra=[("prob", p_chosen)])
    return {"name": spec["name"], "model": spec["model"], "path": path, "fitted_on": where[0], "judged_on": where[1],
            "candidates": table, "chosen": chosen, "temperature": t, "knots": int(len(kx)), "floor": floor,
            "reference": reference, "jets": int(len(judge_rows))}


def _scaled(data, x, hl, s):
    """The jets with every particle's momentum and energy scaled by s -- a jet energy scale moved: the
    fractions and angles stay, log pt and log E shift by log s; of the high-level features, those with a
    momentum's dimension (pt, mass, soft-drop mass) scale."""
    x2 = None if x is None else np.array(x, dtype=np.float64)
    if x2 is not None:
        for f, name in enumerate(data.features):
            if name in ("log_pt", "log_e"):
                x2[..., f] += math.log(s)
    hl2 = None if hl is None else np.array(hl, dtype=np.float64)
    if hl2 is not None:
        for f, name in enumerate(data.high_level):
            if name in ("pt", "mass", "sdmass"):
                hl2[:, f] *= s
    return x2, hl2


def audit(model, data, rows=2000, irc_rows=64, jes=0.02):
    """A trained tagger refereed as a physics analysis would, each check with its verdict and what to do:

      infrared and collinear safety -- irc()'s soft and collinear probes (particle networks);
      mass sculpting -- the background's mass before and after a cut keeping half the signal (JSD): a cut that
                        reshapes it makes a bump for a bump hunt to find;
      calibration -- does P(signal) = 0.8 mean 80 %: the expected calibration error, and the temperature that
                     fixes it, fitted on half the jets and judged on the other half;
      jet energy scale -- the change of the probabilities when every momentum moves by jes (2 %, a jet energy
                          scale's uncertainty): the systematic the tagger carries.

    The jets are the dataset's test rows when it is the one the network was trained on, else its first rows.
    The checks use numpy's float64 reference of the network -- what Sphere's Java and C++ compute too."""
    spec, weights = load(model) if isinstance(model, str) else model
    if isinstance(data, str):
        data = dataset(data)
    if data.y is None or len(set(np.asarray(data.y).tolist())) < 2:
        raise ValueError(data.name + " needs labeled jets of two classes for an audit")
    if spec.get("dataset") == data.name and "seed" in spec:
        val, test = spec.get("split", [0.2, 0.2])
        sel = np.sort(_split(len(data), int(spec["seed"]), val, test)[2])
        where = "its %d test jets (never trained on)" % len(sel)
    else:
        sel = np.arange(len(data))
        where = "%d jets of %s" % (len(sel), data.name)
    sel = sel[:int(rows)]
    x = None if data.x is None else np.asarray(data.x[sel], dtype=np.float64)
    mask = None if data.mask is None else np.asarray(data.mask[sel], dtype=np.float64)
    hl = None if data.hl is None else np.asarray(data.hl[sel], dtype=np.float64)
    y, mass = np.asarray(data.y[sel]), None if data.mass is None else np.asarray(data.mass[sel])
    w = np.ones(len(sel))
    for c in set(y.tolist()):   # each class weighted as the training balanced them
        w[y == c] = len(y) / (2.0 * np.sum(y == c))

    def logits_of(xx, hh):
        return _forward_np(NumpyOps, spec, weights, prepare(spec, spec["norm"], x=xx, mask=mask, hl=hh,
                                                              dtype=np.float64))

    logits = logits_of(x, hl)
    prob = _softmax_np(logits)
    m = metrics(prob, y, w, mass)
    checks = []

    # discrimination first: a tagger that separates nothing passes every other check, trivially
    ns, nb = int(np.sum(y == 1)), int(np.sum(y == 0))
    auc, err = m["auc"], _auc_error(m["auc"], ns, nb)
    fixed, fixed_err = _auc_at_fixed_mass(prob[:, 1], y, mass) if mass is not None else (None, None)
    beyond = "" if fixed is None else "; %.3f +- %.3f at fixed mass (the signal's central half of masses): %s" % (
        fixed, fixed_err, "its power is not the mass's alone" if _significant(fixed, fixed_err)
        else "what it separates is the mass")
    if auc < 0.5 - 3 * err:
        grade, finding = "fail", "AUC %.3f +- %.3f: below 0.5, the classes inverted (a label or sign mixed up?)" % (auc, err)
    elif auc < 0.5 + 3 * err:
        grade, finding = "fail", "AUC %.3f +- %.3f: no better than a coin%s" % (auc, err, beyond)
    else:
        grade, finding = "pass", "AUC %.3f +- %.3f, %.0f standard deviations above a coin%s" % (
            auc, err, (auc - 0.5) / max(err, 1e-12), beyond)
    blind = grade == "fail"
    checks.append({"check": "discrimination", "grade": grade, "finding": finding,
                   "advice": "nothing below means much until it separates: more epochs, a smaller DisCo lambda"
                             " (':ml pareto' measures the trade-off), another network" if blind else ""})

    # infrared and collinear safety
    if spec["model"] == "mlp":
        checks.append({"check": "infrared and collinear safety", "grade": "n/a",
                       "finding": "the MLP sees high-level features: as safe as they are (mass, N-subjettiness ratios"
                                  " are; the constituents' count is not)", "advice": ""})
        ircr = None
    else:
        ircr = irc((spec, weights), data, irc_rows)
        bad = [p for p in ("soft", "collinear") if not ircr[p]["verdict"].startswith("safe")]
        checks.append({"check": "infrared and collinear safety", "grade": "fail" if bad else "pass",
                       "finding": "soft: %s; collinear: %s" % (ircr["soft"]["verdict"], ircr["collinear"]["verdict"]),
                       "advice": "an 'efn' is safe by construction (':ml train <data> efn'); else carry the soft and"
                                 " collinear modeling (hadronization, detector splitting) as a systematic"
                                 if bad else ""})

    # mass sculpting
    if "sculpting_jsd" in m:
        jsd = m["sculpting_jsd"]
        grade = "pass" if jsd < 0.05 else "warn" if jsd < 0.15 else "fail"
        dc = spec.get("decorrelate")
        checks.append({"check": "mass sculpting", "grade": grade,
                       "finding": "a cut keeping half the signal reshapes the background's mass by JSD %.3f%s" % (
                           jsd, " (trained with DisCo, lambda %g)" % dc["lambda"] if dc else ""),
                       "advice": "" if grade == "pass" else "retrain with '--decorrelate mass' (DisCo%s) before a"
                                 " bump hunt" % (", a larger lambda than %g" % dc["lambda"] if dc else "")})
    else:
        checks.append({"check": "mass sculpting", "grade": "n/a", "finding": "the dataset has no jet masses",
                       "advice": ""})

    # calibration, on as many signal as background jets (P(signal) is a probability in the training's 50/50
    # world): T fitted on half of them, the error judged on the other half against the floor a perfectly
    # calibrated network would show there, its labels drawn from its own probabilities
    rng = np.random.default_rng(5)
    i_s, i_b = np.flatnonzero(y == 1), np.flatnonzero(y == 0)
    k = min(len(i_s), len(i_b))
    pick = np.concatenate([rng.choice(i_s, k, replace=False), rng.choice(i_b, k, replace=False)])
    rng.shuffle(pick)
    fit_on, judge = pick[: len(pick) // 2], pick[len(pick) // 2:]
    ones = np.ones(len(judge))
    cal = (spec.get("calibration") or {}).get("method")
    t = _temperature(logits[fit_on], y[fit_on], np.ones(len(fit_on)))
    p1 = (calibrated(spec, logits[judge]) if cal else prob[judge])[:, 1]
    before = _calibration(p1, y[judge], ones)
    after = _calibration(_softmax_np(logits[judge] / t)[:, 1], y[judge], ones)
    floor = float(np.mean([_calibration(p1, (rng.uniform(size=len(p1)) < p1).astype(int), ones) for _ in range(32)]))
    grade = "pass" if before <= floor + 0.02 else "warn" if before <= floor + 0.06 else "fail"
    if grade == "pass":
        advice = ""
    elif cal:
        advice = "recalibrate on more held-out jets (':ml calibrate')"
    elif after <= floor + 0.02 or after < 0.75 * before:
        advice = ("':ml calibrate' writes it into the network, for every engine to apply (here a temperature T = %.3f"
                  " would do)" % t)
    else:
        advice = ("not a matter of confidence, which a temperature would fix: ':ml calibrate' fits an isotonic map"
                  " on held-out jets and writes it into the network, for every engine to apply")
    checks.append({"check": "calibration", "grade": grade,
                   "finding": ("P(signal), through its %s calibration, is off by %.3f on average (%d jets), where a"
                               " perfectly calibrated network would show %.3f" % (cal, before, len(judge), floor)) if cal
                   else ("P(signal) is off by %.3f on average (expected calibration error, %d jets), where a perfectly"
                         " calibrated network would show %.3f; with the logits divided by T = %.3f, %.3f"
                         % (before, len(judge), floor, t, after)), "advice": advice})

    # jet energy scale
    changes = []
    for s in (1 - jes, 1 + jes):
        xs, hs = _scaled(data, x, hl, s)
        changes.append(0.5 * np.abs(_softmax_np(logits_of(xs, hs)) - prob).sum(1))
    d = np.concatenate(changes)
    med, p90 = float(np.median(d)), float(np.quantile(d, 0.9))
    grade = "pass" if med < 0.005 else "warn" if med < 0.02 else "fail"
    checks.append({"check": "jet energy scale (%+.0f %%)" % (100 * jes), "grade": grade,
                   "finding": "the probabilities move by %.1e (median), %.1e (90 %% of jets)" % (med, p90),
                   "advice": "" if grade == "pass" else "carry the jet energy scale through the tagger as a"
                             " systematic (scale by 1 +- %g and refit), or feed it fractions and angles (an 'efn'"
                             " moves by nothing)" % jes})
    if blind:   # a constant sculpts nothing, is calibrated and feels no energy scale: those passes say nothing
        for c in checks:
            if c["grade"] == "pass" and c["check"] != "infrared and collinear safety" and c["check"] != "discrimination":
                c["grade"] = "n/a"
                c["finding"] += " -- which says nothing of a tagger that separates nothing"
    passed = sum(c["grade"] == "pass" for c in checks)
    graded = sum(c["grade"] != "n/a" for c in checks)
    return {"model": spec["model"], "name": spec.get("name", ""), "trained_with": spec.get("trained_with", ""),
            "where": where, "jets": int(len(y)), "auc": m["auc"], "auc_error": err, "auc_fixed_mass": fixed,
            "rejection_50": m.get("rejection_50"), "checks": checks, "passed": passed, "graded": graded, "irc": ircr,
            "calibration": {"ece": before, "temperature": t, "ece_scaled": after, "ece_floor": floor},
            "jes": {"shift": jes, "median": med, "p90": p90}}


def pareto(data, model="efn", backend="torch", lambdas=(0.0, 0.1, 0.3, 1.0, 3.0), epochs=12, note=None,
           report=None, canceled=None, **kw):
    """DisCo's trade-off, measured as its paper charts it: the network trained at each lambda, its background
    rejection at half the signal (R50) against how little a cut there reshapes the background's mass (1/JSD),
    with its AUC at fixed mass -- and the trainings no other beats on both (the Pareto front). No lambda suits
    every dataset: this finds the one a dataset wants. Suggested is the front's largest R50 x 1/JSD."""
    if isinstance(data, str):
        data = dataset(data)
    if data.mass is None:
        raise ValueError(data.name + " has no jet masses: there is no trade-off with them to measure")
    note = note or (lambda text: None)
    points = []
    for lam in lambdas:
        if canceled is not None and canceled():
            break
        note("pareto: lambda %g (%d of %d)" % (lam, len(points) + 1, len(lambdas)))
        name = "%s_%s_pareto_%s" % (data.name, model, ("%g" % lam).replace(".", "p"))
        r = fit(data, model, backend, epochs=epochs, decorrelate="mass:%g" % lam if lam else None, name=name,
                note=note, report=report, canceled=canceled, **kw)
        m = r["metrics"]
        spec, weights = load(r["name"])
        te = np.sort(_split(len(data), spec["seed"], *spec.get("split", [0.2, 0.2]))[2])
        inputs = prepare(spec, spec["norm"], x=None if data.x is None else data.x[te],
                         mask=None if data.mask is None else data.mask[te], hl=None if data.hl is None else data.hl[te],
                         dtype=np.float64)
        p = _softmax_np(_forward_np(NumpyOps, spec, weights, inputs))[:, 1]
        y_te = np.asarray(data.y[te])
        fixed, fixed_err = _auc_at_fixed_mass(p, y_te, np.asarray(data.mass[te]))
        jsd = m.get("sculpting_jsd")
        r50 = m.get("rejection_50")
        # a tagger that separates nothing keeps the mass's shape too, and a random cut rejects half the
        # background: only those that still separate, overall and at fixed mass, count on the front
        separates = _significant(m["auc"], _auc_error(m["auc"], int(np.sum(y_te == 1)), int(np.sum(y_te == 0))))
        beyond = fixed is None or _significant(fixed, fixed_err)
        points.append({"lambda": float(lam), "name": r["name"], "auc": m["auc"], "auc_fixed_mass": fixed,
                       "r50": r50 if r50 is not None and math.isfinite(r50) else None,
                       "r50_min": m.get("rejection_50_min"), "jsd": jsd,
                       "inv_jsd": (1.0 / jsd) if jsd else None, "separates": separates, "beyond_mass": beyond,
                       "significant": separates and beyond})

    def merit(q):   # an infinite rejection counts as its lower bound
        r = q["r50"] if q["r50"] is not None else (q["r50_min"] or 0.0)
        return r, q["inv_jsd"] or 0.0

    live = [q for q in points if q["significant"]]
    for q in points:
        r, j = merit(q)
        q["front"] = q["significant"] and not any(merit(o)[0] >= r and merit(o)[1] >= j and merit(o) != (r, j)
                                                  for o in live)
    front = [q for q in points if q["front"]]
    best = max(front, key=lambda q: merit(q)[0] * merit(q)[1]) if front else None
    lost = [q["lambda"] for q in points if not q["significant"]]
    kept = [q["jsd"] for q in live if q["jsd"] is not None]
    if best is None:
        verdict = "no training separates the classes significantly: more epochs, or smaller lambdas"
    elif best["lambda"] == 0 and any(q["lambda"] > 0 for q in points):
        verdict = ("on %s decorrelation and power exclude each other: the networks that still separate keep the"
                   " sculpting (JSD %.2f to %.2f)%s -- what separates its classes follows the background's mass. Keep"
                   " lambda = 0, and model the sculpted background shape in the fit"
                   % (data.name, min(kept), max(kept), ", those that forget the mass (from lambda = %g) separate"
                      " nothing beyond it" % min(lost) if lost else ""))
    else:
        verdict = "lambda = %g: the front's best R50 x 1/JSD among the networks that still separate" % best["lambda"]
    try:
        edges = list(np.arange(len(points) + 1) - 0.5)
        spx.publish("pareto_r50_%s" % model, edges, [merit(q)[0] for q in points],
                    xlabel="lambda " + ", ".join("%g" % q["lambda"] for q in points) + " (R50)")
        spx.publish("pareto_invjsd_%s" % model, edges, [merit(q)[1] for q in points],
                    xlabel="lambda " + ", ".join("%g" % q["lambda"] for q in points) + " (1/JSD)")
    except Exception:
        pass   # a plot is a courtesy
    return {"model": model, "backend": backend, "dataset": data.name, "points": points, "verdict": verdict,
            "suggested": best["name"] if best else None, "suggested_lambda": best["lambda"] if best else None}


# ---------------------------------------------------------------------------
# pyhf: the same likelihood on numpy, PyTorch, JAX or TensorFlow
# ---------------------------------------------------------------------------

def pyhf_backends():
    """The backends the installed pyhf has and this Python can run: numpy always; JAX; PyTorch and TensorFlow
    only up to pyhf 0.7 -- pyhf 0.8 removed them, keeping NumPy and JAX (automatic differentiation by JAX)."""
    import importlib.util
    have = frameworks()
    out = ["numpy"]
    for backend, module, dist in (("pytorch", "pyhf.tensor.pytorch_backend", "torch"),
                                  ("jax", "pyhf.tensor.jax_backend", "jax"),
                                  ("tensorflow", "pyhf.tensor.tensorflow_backend", "tensorflow")):
        try:
            present = importlib.util.find_spec(module) is not None
        except ImportError:
            present = False
        if present and have.get(dist):
            out.append(backend)
    return out


def stats(spec, backends=None, mu_max=None, points=41, report=None):
    """A HistFactory model -- a pyhf JSON workspace, or one channel of counts (signal, background, its
    uncertainty, observed) -- fitted on each backend asked (by default every one pyhf_backends() finds):
    the best-fit signal strength, CLs at mu = 1 (observed and the expected band) and the 95 % CL upper
    limit. The backends' answers are compared: the same statistics on other engines, the agreement says
    how far to trust each."""
    import pyhf
    report = report or (lambda message: None)
    available = pyhf_backends()
    converted = None
    if "xml" in spec:
        # a HistFactory analysis as RooStats' hist2workspace takes it: the top XML, the channels' XML, the ROOT
        # histograms, read by pyhf.readxml (uproot); paths in the XML are relative to the folder above config/
        import pyhf.readxml
        xml = os.path.abspath(spec["xml"])
        here = os.path.dirname(xml)
        root = spec.get("rootdir") or (os.path.dirname(here) if os.path.basename(here) == "config" else here)
        report("reading the HistFactory XML " + os.path.basename(xml))
        ws = pyhf.Workspace(pyhf.readxml.parse(xml, root, track_progress=False))
        # kept as pyhf JSON, the form HEPData publishes likelihoods in
        converted = os.path.join(spx.bridge_folder(), "ml", os.path.splitext(os.path.basename(xml))[0] + ".workspace.json")
        os.makedirs(os.path.dirname(converted), exist_ok=True)
        with open(converted, "w") as fh:
            json.dump(dict(ws), fh, indent=1)
    elif "workspace" in spec:
        with open(spec["workspace"]) as fh:
            ws = pyhf.Workspace(json.load(fh))
    else:
        pyhf.set_backend("numpy")
        m = pyhf.simplemodels.uncorrelated_background(signal=list(map(float, spec["signal"])),
                                                       bkg=list(map(float, spec["background"])),
                                                       bkg_uncertainty=list(map(float, spec["bkg_uncertainty"])))
        ws = None
    have = frameworks()
    if backends is None:
        backends = available
    results = {}
    for b in backends:
        report("pyhf on " + b)
        if b not in available:
            gone = b in ("pytorch", "tensorflow") and not pyhf.__version__.startswith("0.7")
            results[b] = {"error": "pyhf %s has no %s backend here%s" % (pyhf.__version__, b,
                          ": pyhf 0.8 removed PyTorch and TensorFlow, keeping NumPy and JAX" if gone
                          else " (its framework is not installed in this Python)")}
            continue
        try:
            pyhf.set_backend(b, precision="64b")
            if ws is not None:
                model = ws.model()
                data = ws.data(model)
            else:
                model = pyhf.simplemodels.uncorrelated_background(
                    signal=list(map(float, spec["signal"])), bkg=list(map(float, spec["background"])),
                    bkg_uncertainty=list(map(float, spec["bkg_uncertainty"])))
                data = list(map(float, spec["observed"])) + list(map(float, model.config.auxdata))
            top = float(mu_max) if mu_max else 5.0
            scan = np.linspace(0, top, int(points))
            t0 = time.perf_counter()
            best = pyhf.infer.mle.fit(data, model)
            mu_hat = float(pyhf.tensorlib.to_numpy(best)[model.config.poi_index])
            cls_obs, cls_exp = pyhf.infer.hypotest(1.0, data, model, test_stat="qtilde", return_expected_set=True)
            # pyhf's grid scan (linear_grid_scan), done here: in pyhf 0.7 it reverses the CLs with [::-1], which
            # a PyTorch tensor refuses ("step must be greater than zero"). The same hypotests, the same np.interp
            obs_scan, exp_scan = [], []
            for mu in scan:
                o, e = pyhf.infer.hypotest(float(mu), data, model, test_stat="qtilde", return_expected_set=True)
                obs_scan.append(float(o))
                exp_scan.append([float(v) for v in e])
            obs_scan, exp_scan = np.asarray(obs_scan), np.asarray(exp_scan)
            obs = np.interp(0.05, obs_scan[::-1], scan[::-1])
            exp_band = [np.interp(0.05, exp_scan[::-1, i], scan[::-1]) for i in range(5)]
            results[b] = {"mu_hat": mu_hat, "cls_obs": float(cls_obs), "cls_exp": [float(v) for v in cls_exp],
                          "upper_limit": float(obs), "expected_limits": [float(v) for v in exp_band],
                          "seconds": time.perf_counter() - t0,
                          "cls_scan": obs_scan.tolist(), "scan": scan.tolist()}
        except Exception as e:
            results[b] = {"error": "%s: %s" % (type(e).__name__, e)}
    good = [r for r in results.values() if "error" not in r]
    agreement = {}
    if len(good) > 1:
        for key in ("mu_hat", "cls_obs", "upper_limit"):
            vals = [r[key] for r in good]
            agreement[key] = float((max(vals) - min(vals)) / max(abs(np.mean(vals)), 1e-300))
    pyhf.set_backend("numpy")
    if good:
        r = good[0]
        sc = np.asarray(r["scan"])
        edges = np.concatenate([[sc[0] - 0.5 * (sc[1] - sc[0])], 0.5 * (sc[1:] + sc[:-1]), [sc[-1] + 0.5 * (sc[1] - sc[0])]])
        try:
            spx.publish("cls_scan", edges.tolist(), r["cls_scan"], xlabel="mu (CLs, observed)")
        except Exception:
            pass
    out = {"backends": results, "agreement": agreement, "pyhf": have.get("pyhf"), "available": available}
    if converted:
        out["workspace_json"] = converted
        out["channels"] = list(ws.channels)
        out["poi"] = ws.get_measurement()["config"]["poi"]
    return out


# ---------------------------------------------------------------------------
# Graphs: a model of PyTorch, or of Keras on its torch backend, made the portable graph of sphere_graph that
# Sphere runs in Java (NeuralGraph) and C++ (sphere_graph.hpp) without either
# ---------------------------------------------------------------------------

def graph_status():
    """PyTorch here -- its version, threads, torch.export -- and LibTorch, the C++ library its wheel carries
    (headers, libraries, CMake files); Keras and the backends it could run on; TensorFlow, if this Python has
    it (looked for, not imported: Keras's backend is fixed at its import); sphere_graph's operations."""
    import importlib.metadata
    import importlib.util
    out = {"python": sys.version.split()[0], "executable": sys.executable}

    def version(dist):
        try:
            return importlib.metadata.version(dist)
        except importlib.metadata.PackageNotFoundError:
            return None

    try:
        import torch
        base = os.path.dirname(torch.__file__)
        lib, inc, cmake = os.path.join(base, "lib"), os.path.join(base, "include"), os.path.join(base, "share", "cmake", "Torch")
        libs = {}
        if os.path.isdir(lib):
            for f in sorted(os.listdir(lib)):
                if f.lower().startswith(("torch", "c10")) and f.lower().endswith((".lib", ".dll", ".so", ".dylib", ".a")):
                    libs[f] = os.path.getsize(os.path.join(lib, f))
        out.update({"torch": torch.__version__, "threads": torch.get_num_threads(), "cuda": bool(torch.cuda.is_available()),
                    "export": hasattr(torch, "export"),
                    "libtorch": {"include": inc if os.path.isdir(os.path.join(inc, "torch")) else "",
                                 "lib": lib if libs else "", "libraries": libs,
                                 "cmake": cmake if os.path.isdir(cmake) else ""}})
    except Exception as e:
        out.update({"torch": None, "torch_error": "%s: %s" % (type(e).__name__, e)})
    out["keras"] = version("keras")
    out["tensorflow"] = version("tensorflow") or version("tensorflow-cpu") or version("tensorflow-intel")
    out["backends"] = {name: importlib.util.find_spec(mod) is not None for name, mod in
                       (("tensorflow", "tensorflow"), ("jax", "jax"), ("torch", "torch"))}
    import sphere_graph
    out["graph_ops"] = len(sphere_graph.OPS)
    out["graph_format"] = sphere_graph.FORMAT
    return out


GRAPH_EXPORT_FLOOR = 150.0 * 2 ** 20   # below this much available memory (or commit), an export is stopped


def graph_export(a, note=None):
    """A model made a graph in a Python of its own: Keras there on the torch backend whatever this engine's is,
    the export's memory given back to the system when it is done, a failure kept from the engine. a holds kind
    "script" (a script defining model and example -- and, if it likes, feeds, name, framework) or "keras" (a
    model file, .keras or .h5, with the shapes of its inputs' rows when the file does not say them). The graph
    goes to bridge/models/<name>.sng.spx, numpy's outputs on the example's first rows to bridge/ml for Java's
    and C++'s checks."""
    import subprocess
    folder = os.path.join(spx.bridge_folder(), "ml")
    os.makedirs(folder, exist_ok=True)
    job = dict(a)
    stem = os.path.join(folder, "graphjob_%d_%d" % (os.getpid(), int(time.time() * 1000)))
    job["result"] = stem + ".result.json"
    with open(stem + ".json", "w", encoding="utf-8") as fh:
        json.dump(job, fh)
    env = dict(os.environ, KERAS_BACKEND="torch", SPHERE_BRIDGE=spx.bridge_folder(), PYTHONDONTWRITEBYTECODE="1",
               PYTHONWARNINGS="ignore")
    lib = os.path.dirname(os.path.abspath(__file__))
    where = os.path.dirname(os.path.abspath(a.get("script") or a.get("path") or stem))
    t0, peak, tail = time.time(), 0.0, []
    proc = subprocess.Popen([sys.executable, os.path.join(lib, "sphere_graph.py"), "job", stem + ".json"], cwd=where, env=env,
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            errors="replace")

    def read():
        for line in proc.stdout:
            tail.append(line.rstrip())
            del tail[:-40]
            if note and line.strip() and "Warning" not in line:
                note(line.rstrip()[:200])

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    try:
        import psutil
        watched = psutil.Process(proc.pid)
    except Exception:
        watched = None
    starved = None
    while proc.poll() is None:
        if watched is not None:   # with its children: a venv's python.exe on Windows only launches the real one
            try:
                tree = [watched] + watched.children(recursive=True)
                held = 0.0
                for p in tree:
                    info = p.memory_info()
                    held += float(getattr(info, "private", 0.0) or info.rss)
                peak = max(peak, held)
                m = _memory()
                if m and min(m["avail"], m["commit_avail"]) < GRAPH_EXPORT_FLOOR:   # the machine itself at its end
                    starved = m
                    for p in reversed(tree):
                        p.kill()
            except Exception:
                pass
        time.sleep(0.2)
    reader.join(5)
    try:
        with open(job["result"], encoding="utf-8") as fh:
            result = json.load(fh)
    except (OSError, ValueError):
        result = {"error": "the export's Python stopped (exit %s) before its result:\n%s" % (proc.returncode, "\n".join(tail[-15:]))}
    for f in (stem + ".json", job["result"]):
        try:
            os.remove(f)
        except OSError:
            pass
    if starved is not None:
        raise MemoryError("the export was stopped at %.0f MB, the machine down to %.0f MB available and %.0f MB of commit: "
                          "close something, or export a smaller model (its training apart, in a script of its own)"
                          % (peak / 2 ** 20, starved["avail"] / 2 ** 20, starved["commit_avail"] / 2 ** 20))
    if "error" in result:
        raise RuntimeError(result["error"])
    result["seconds"] = time.time() - t0
    result["peak_mb"] = peak / 2 ** 20
    return result


# ---------------------------------------------------------------------------
# The engine: JSON lines on stdin and stdout, as geant4-bridge's
# ---------------------------------------------------------------------------

class Server:
    """Requests {"id", "cmd", "args"} one at a time, in order; replies {"id", "ok", "result"} or {"id", "ok":
    false, "error"}; progress {"id", "progress", "message"} while a command runs. {"cancel": id} stops a
    training after its current batch. stdout carries the protocol only: whatever the frameworks print goes
    to stderr, Sphere's log of the engine."""

    def __init__(self):
        self.out = sys.stdout
        sys.stdout = sys.stderr
        self.lock = threading.Lock()
        self.requests = queue.Queue()
        self.canceled = set()

    def send(self, obj):
        # JSON has no Infinity nor NaN (a rejection with no background left is infinite): they go as null
        line = json.dumps(_finite(obj), allow_nan=False, default=_jsonable)
        with self.lock:
            self.out.write(line + "\n")
            self.out.flush()

    def reader(self):
        for line in _stdin_lines():
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                self.send({"id": 0, "ok": False, "error": "bad request: " + line[:80]})
                continue
            if "cancel" in msg:
                self.canceled.add(msg["cancel"])
            else:
                self.requests.put(msg)
        self.requests.put(None)   # stdin closed: Sphere is gone

    def serve(self):
        self.send({"ready": True, "engine": describe(), "transport": "stdio"})
        threading.Thread(target=self.reader, daemon=True).start()
        while True:
            msg = self.requests.get()
            if msg is None:
                return
            rid = msg.get("id", 0)
            try:
                result = self.dispatch(msg.get("cmd", ""), msg.get("args") or {}, rid)
                self.send({"id": rid, "ok": True, "result": result})
            except SystemExit:
                self.send({"id": rid, "ok": True, "result": {"bye": True}})
                return
            except Exception as e:
                self.send({"id": rid, "ok": False, "error": "%s: %s" % (type(e).__name__, e)})
            self.canceled.discard(rid)

    def dispatch(self, cmd, a, rid):
        def progress(fraction, message):
            self.send({"id": rid, "progress": fraction, "message": message})

        def canceled():
            return rid in self.canceled

        if cmd == "ping":
            return {"pong": True, "engine": describe()}
        if cmd == "frameworks":
            return frameworks(devices=bool(a.get("devices", True)))
        if cmd == "dataset":
            return dataset(a["path"]).summary()
        if cmd == "toy":
            path = a.get("path") or _resolve("toyjets")
            toy_jets(int(a.get("jets", 4000)), int(a.get("points", 32)), int(a.get("seed", 1)), path=path)
            return dataset(path).summary()
        if cmd == "models":
            return {"models": models(), "zoo": MODELS}
        if cmd == "train":
            epochs = int(a.get("epochs", 20))

            def report(epoch, logs):
                progress(epoch / max(1, epochs), "epoch %d: loss %.4f, val_loss %.4f, val_auc %.4f%s" % (
                    epoch, logs.get("loss", float("nan")), logs.get("val_loss", float("nan")),
                    logs.get("val_auc", float("nan")),
                    ", val_dcorr %.4f (mass)" % logs["val_dcorr"] if "val_dcorr" in logs else ""))

            kw = {k: v for k, v in a.items() if k not in ("data", "model", "backend", "report", "canceled", "note")}
            return fit(a["data"], a.get("model", "deepsets"), a.get("backend", "torch"), report=report,
                       canceled=canceled, note=lambda text: progress(0.0, text), **kw)
        if cmd == "predict":
            _, result = predict(a["model"], a["data"], a.get("engine", "numpy"), a.get("out"), a.get("precision"))
            return result
        if cmd == "crosscheck":
            return crosscheck(a["model"], a["data"], int(a.get("rows", 256)))
        if cmd == "irc":
            return irc(a["model"], a["data"], int(a.get("rows", 64)))
        if cmd == "audit":
            return audit(a["model"], a["data"], int(a.get("rows", 2000)), int(a.get("irc_rows", 64)),
                         float(a.get("jes", 0.02)))
        if cmd == "calibrate":
            return calibrate(a["model"], a["data"], a.get("method", "auto"))
        if cmd == "pareto":
            epochs = int(a.get("epochs", 12))

            def report(epoch, logs):
                progress(epoch / max(1, epochs), "  epoch %d: val_auc %.4f%s" % (
                    epoch, logs.get("val_auc", float("nan")),
                    ", val_dcorr %.4f" % logs["val_dcorr"] if "val_dcorr" in logs else ""))

            kw = {k: v for k, v in a.items() if k in ("points", "k", "seed", "batch", "lr", "precision")}
            return pareto(a["data"], a.get("model", "efn"), a.get("backend", "torch"),
                          [float(v) for v in a.get("lambdas", [0.0, 0.1, 0.3, 1.0, 3.0])], epochs,
                          note=lambda text: progress(0.0, text), report=report, canceled=canceled, **kw)
        if cmd == "stats":
            return stats(a, a.get("backends"), a.get("mu_max"), int(a.get("points", 41)),
                         report=lambda m: progress(0.0, m))
        if cmd == "graph_status":
            return graph_status()
        if cmd == "graph_export":
            return graph_export(a, note=lambda text: progress(0.0, text))
        if cmd == "commands":
            return {"commands": ["ping", "frameworks", "dataset", "toy", "models", "train", "predict", "crosscheck",
                                 "irc", "audit", "calibrate", "pareto", "stats", "graph_status", "graph_export", "quit"]}
        if cmd == "quit":
            raise SystemExit
        raise ValueError("unknown command '%s'; 'commands' lists them" % cmd)


def _stdin_lines():
    """The lines of standard input as they come. On Windows a thread blocked reading a pipe holds it until a
    line arrives, and whoever else queries that pipe meanwhile waits as long: the Fortran runtime of a DLL
    being loaded does (scipy's BLAS, which `import keras` reaches through JAX, and pyhf through scipy), so the
    import would wait for Sphere's next request -- for ever, while a training is what Sphere waits for. There
    the reader waits by looking at the pipe (PeekNamedPipe) and reads only what has arrived."""
    if sys.platform != "win32":
        yield from sys.stdin
        return
    import msvcrt
    import _winapi
    fd = sys.stdin.fileno()
    try:
        handle = msvcrt.get_osfhandle(fd)
        _winapi.PeekNamedPipe(handle, 0)
    except OSError:
        yield from sys.stdin   # a console or a file, not a pipe
        return
    pending = b""
    while True:
        try:
            available = _winapi.PeekNamedPipe(handle, 0)[0]
        except OSError:        # closed: Sphere is gone
            break
        if not available:
            time.sleep(0.02)
            continue
        chunk = os.read(fd, available)
        if not chunk:
            break
        *lines, pending = (pending + chunk).split(b"\n")
        for line in lines:
            yield line.decode("utf-8", "replace")
    if pending.strip():
        yield pending.decode("utf-8", "replace")


def _finite(o):
    """A reply with its infinite and undefined numbers as None, which JSON can say and Java's reader reads."""
    if isinstance(o, dict):
        return {k: _finite(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_finite(v) for v in o]
    if isinstance(o, float) or (np is not None and isinstance(o, np.floating)):
        return float(o) if math.isfinite(o) else None
    return o


def _jsonable(o):
    if np is not None and isinstance(o, np.generic):
        return o.item()
    if np is not None and isinstance(o, np.ndarray):
        return o.tolist()
    return str(o)


def main(argv):
    if "--probe" in argv:
        print(json.dumps(frameworks(devices="--devices" in argv)))
        return 0
    if "--stdio" in argv:
        Server().serve()
        return 0
    print(describe())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
