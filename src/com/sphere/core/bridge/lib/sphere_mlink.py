"""sphere_mlink -- TensorFlow, PyTorch, Keras, JAX and pyhf, reachable from every program of this machine.

    C++, a ROOT macro, a Geant4 application   #include "sphere_mlink.hpp"
    Fortran                                   use sphere_mlink
    Julia                                     include(joinpath(ENV["SPHERE_BRIDGE_LIB"], "SphereMLink.jl"))
    Python (PyROOT, a Python without torch)   import sphere_mlink as link
    Java                                      com.sphere.core.ml.MlLink
    Sphere's console                          :ml link

The frameworks live in one Python. The programs that want them are written in other languages, built by other
compilers and often on the other side of WSL, where ROOT and Geant4 usually are. So one process holds the frameworks
(the gateway: 'python sphere_mlink.py --serve <folder>'), and a program reaches it by dropping a file in a folder and
waiting for the file that answers it. No port is opened, nothing has to be linked, and the folder is one path seen
from Windows and from WSL alike. The format is SPX, which every language of Sphere already reads and writes:

    <folder>/in/<id>.spx    a request: its "meta" says what is wanted, its float64 columns are the arrays
    <folder>/out/<id>.spx   the answer, the same way

    meta of a request:  Op (py, predict, pyhf, call, frameworks, ping, stop), Token, Order (C or F),
                        Shape.<column>: 100 7; for py: Code, Session; for predict: Model, Kind, Inputs;
                        for pyhf: Workspace, Xml, Mu, Points, MuMax, Backends; for call: Cmd, Args (JSON)
    meta of an answer:  Ok (1 or 0), Error, Traceback, Seconds, Output (what the code printed), Type (none, number,
                        text, array, dict), Value; R.<a.b.c>: number and S.<a.b.c>: text for a nested result;
                        Array.<column>: its name in the result, Shape.<column>, Dtype.<column>

Values are single lines: a backslash is written \\\\ and a line end \\n. An array of Fortran or Julia says Order: F, and
gets its answer in the same order, so that a(i, j) is a[i-1, j-1] on both sides. Arrays go as float64, which holds
float32 and the integers up to 2^53 exactly; Dtype says what they were.

The gateway answers one request at a time, in order. Beside the two boxes the folder holds gateway.txt (its pid and
the token of this run: a request made for an earlier run, or copied in from elsewhere, is refused), frameworks.txt
(what the interpreter holds: read without starting anything), heartbeat.txt (a counter, so that a program waiting can
tell a long computation from a dead gateway), and start.bat, start.sh (how to start it, from Windows and from WSL, with
the paths each needs: a client that finds no gateway runs one of them, whatever it is written in). It stops after
--idle seconds without a request, when --parent-pid ends, or when a file 'stop' appears.

What is run is arbitrary Python, as in Sphere's own Python console: the gateway serves only this machine's files, and
only a request carrying the current token.

Nothing has to be decided by hand:

  * a client that finds no gateway starts one; one that dies is replaced, and the request sent again (once: a request
    that kills it twice is reported);
  * a framework this Python lacks is served by the Python that has it (routes.txt, written by Sphere): a gateway is
    started there, in a folder of its own, and the request forwarded;
  * a model called again and again is promoted. Its framework's answer on the program's own inputs in hand, the gateway
    makes the model a portable graph, in the background, holds the graph to that answer, and says so in its replies
    (Native: <file>). A client that can run graphs (C++, Python) then runs it itself, in microseconds instead of the
    milliseconds of a round trip, if a measurement says that is faster; on anything unexpected it goes back to asking.
    A TensorFlow SavedModel or frozen graph, a Keras model, a TorchScript or torch.export program and a Sphere graph can be
    promoted; what cannot be made a graph (a condition on the data, an operation it does not have) stays served.
"""

import ast
import contextlib
import hashlib
import importlib
import io
import itertools
import json
import math
import os
import re
import secrets
import struct
import subprocess
import sys
import threading
import time
import traceback
import types

try:
    import numpy as np
except ImportError:  # frameworks.txt can be read without it; arrays cannot be sent
    np = None

PROTOCOL = "1"
PROMOTE_AFTER = 3          # calls of a model before it is worth making a graph of it
F64, I64, TEXT = 1, 2, 3
MAGIC = b"SPHRSPX1"


# ---------------------------------------------------------------------------
# Where the gateway is, and how a path is named on its side
# ---------------------------------------------------------------------------

def folder(given=None):
    """The gateway's folder: as given, else $SPHERE_MLINK, else <the bridge folder>/mlink."""
    if given:
        return given
    return os.environ.get("SPHERE_MLINK") or os.path.join(os.environ.get("SPHERE_BRIDGE", "bridge"), "mlink")


def _in_wsl():
    if sys.platform != "linux":
        return False
    try:
        with open("/proc/version", encoding="ascii", errors="ignore") as fh:
            return "microsoft" in fh.read().lower()
    except OSError:
        return False


def native(path):
    """A path as this Python names it: a program in WSL says /mnt/d/x, one on Windows says D:\\x."""
    if not isinstance(path, str):
        return path
    if os.name == "nt":
        m = re.match(r"^/mnt/([a-zA-Z])(?:/(.*))?$", path)
        if m:
            return m.group(1).upper() + ":\\" + (m.group(2) or "").replace("/", "\\")
    elif _in_wsl():
        m = re.match(r"^([a-zA-Z]):[\\/](.*)$", path)
        if m:
            return "/mnt/" + m.group(1).lower() + "/" + m.group(2).replace("\\", "/")
    return path


# ---------------------------------------------------------------------------
# The envelope: a request or an answer as one SPX file
# ---------------------------------------------------------------------------

def _escape(text):
    return str(text).replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")


def _unescape(text):
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "r": "\r", "\\": "\\"}.get(m.group(1), m.group(0)), text)


def _aligned(v):
    return (v + 63) // 64 * 64


def _replace(part, path):
    """os.replace, waiting out a scanner that has just opened the file (Windows)."""
    for delay in (0.0, 0.002, 0.01, 0.05, 0.2, 0.5, 1.0):
        time.sleep(delay)
        try:
            return os.replace(part, path)
        except PermissionError:
            continue
    os.replace(part, path)


def write_envelope(path, meta, arrays=(), order="C"):
    """Writes the file in one piece, beside its name, and moves it there: a reader sees all of it or none."""
    text = "".join("%s: %s\n" % (k, _escape(v)) for k, v in meta.items()).encode("utf-8")
    entries = [("meta", TEXT, text, len(text))]
    for name, a in arrays:
        a = np.asarray(a, dtype=np.float64)
        entries.append((name, F64, a.astype("<f8").tobytes(order=order), a.size))
    head = _aligned(64 + 64 * len(entries))
    offsets, at = [], head
    for e in entries:
        offsets.append(at)
        at = _aligned(at + len(e[2]))
    block = bytearray(head)
    block[0:8] = MAGIC
    struct.pack_into("<iiiiqq", block, 8, 1, 3, len(entries), 0, 64, at)
    block[40:64] = b"mlink".ljust(24)
    for k, (name, stype, _raw, count) in enumerate(entries):
        d = 64 + 64 * k
        block[d:d + 24] = name.encode("ascii").ljust(24)
        struct.pack_into("<iiqq", block, d + 24, stype, 0, offsets[k], count)
    part = path + ".part"
    with open(part, "wb") as out:
        out.write(block)
        for k, e in enumerate(entries):
            out.seek(offsets[k])
            out.write(e[2])
        out.truncate(at)
    _replace(part, path)
    return path


def read_envelope(path):
    """(meta, {column: writable float64 array}), or None while the file is not all there: Fortran writes in place,
    the header stating the final size first. The bytes are read rather than mapped, so that the file can be removed."""
    size = os.path.getsize(path)
    if size < 64:
        return None
    data = bytearray(size)
    with open(path, "rb") as fh:
        got = fh.readinto(data)
    declared = struct.unpack_from("<q", data, 32)[0]
    if got < size or declared > size:
        return None
    if bytes(data[:8]) != MAGIC:
        raise ValueError("not an SPX file")
    n = struct.unpack_from("<i", data, 16)[0]
    directory = struct.unpack_from("<q", data, 24)[0]
    meta, columns = {}, {}
    for k in range(n):
        at = directory + 64 * k
        name = bytes(data[at:at + 24]).decode("ascii").strip()
        stype, _pad, offset, count = struct.unpack_from("<iiqq", data, at + 24)
        if stype == TEXT:
            if name == "meta":
                for line in bytes(data[offset:offset + count]).decode("utf-8").split("\n"):
                    if ":" in line:
                        key, value = line.split(":", 1)
                        meta[key.strip()] = _unescape(value.strip())
        else:
            columns[name] = np.frombuffer(data, dtype="<f8" if stype == F64 else "<i8", count=count, offset=offset)
    return meta, columns


def _settled(path):
    """read_envelope of a file that has just appeared: on Windows a scanner may hold it for a moment."""
    for attempt in range(500):
        try:
            envelope = read_envelope(path)
            if envelope is not None:
                return envelope
        except OSError:
            if attempt == 499:
                raise
        time.sleep(0.01)
    raise OSError("unreadable answer: " + path)


def _shaped(column, text, order):
    return column.reshape([int(v) for v in text.split()], order=order) if text else column


# ---------------------------------------------------------------------------
# A result as an answer
# ---------------------------------------------------------------------------

def _to_numpy(v):
    """The numbers of a numpy array, a torch, TensorFlow or JAX tensor, a pyhf tensor; None for anything else."""
    if isinstance(v, np.ndarray):
        return v
    if isinstance(v, np.generic):
        return np.asarray(v)
    if hasattr(v, "detach") and hasattr(v, "cpu"):
        return v.detach().cpu().numpy()
    if hasattr(v, "numpy") and callable(v.numpy):
        try:
            return np.asarray(v.numpy())
        except Exception:
            return None
    if hasattr(v, "__array__"):
        return np.asarray(v)
    return None


def _number(x):
    return repr(float(x))


class _Encoder:
    def __init__(self):
        self.meta, self.arrays = {}, []

    def array(self, key, a):
        name = "a%d" % len(self.arrays)
        self.meta["Array." + name] = key
        self.meta["Shape." + name] = " ".join(str(s) for s in a.shape)
        self.meta["Dtype." + name] = a.dtype.name
        self.arrays.append((name, a))

    def walk(self, key, v):
        if v is None:
            return
        a = _to_numpy(v)
        if a is not None and a.dtype.kind in "biuf":
            if a.ndim == 0:
                self.meta["R." + key] = _number(a.item())
            else:
                self.array(key, a)
        elif isinstance(v, (bool, int, float)):
            self.meta["R." + key] = _number(v)
        elif isinstance(v, str):
            self.meta["S." + key] = v
        elif isinstance(v, dict):
            for k, x in v.items():
                self.walk("%s.%s" % (key, k) if key else str(k), x)
        elif isinstance(v, (list, tuple)):
            a = _numeric_list(v)
            if a is not None:
                self.array(key, a)
            else:
                for i, x in enumerate(v):
                    self.walk("%s.%d" % (key, i) if key else str(i), x)
        else:
            self.meta["S." + key] = repr(v)


def _numeric_list(v):
    if not v:
        return None
    try:
        a = np.asarray(v)
    except Exception:
        return None
    return a if a.dtype.kind in "biuf" and a.ndim > 0 else None


def encode(value):
    """(meta, arrays) of a result, whatever Python handed back."""
    e = _Encoder()
    a = _to_numpy(value) if value is not None else None
    if value is None:
        e.meta["Type"] = "none"
    elif a is not None and a.dtype.kind in "biuf":
        if a.ndim == 0:
            e.meta.update({"Type": "number", "Value": _number(a.item())})
        else:
            e.meta["Type"] = "array"
            e.array("result", a)
    elif isinstance(value, (bool, int, float)):
        e.meta.update({"Type": "number", "Value": _number(value)})
    elif isinstance(value, str):
        e.meta.update({"Type": "text", "Value": value})
    elif isinstance(value, (list, tuple)) and _numeric_list(value) is not None:
        e.meta["Type"] = "array"
        e.array("result", _numeric_list(value))
    elif isinstance(value, (dict, list, tuple)):
        e.meta["Type"] = "dict"
        e.walk("", value)
    else:
        e.meta.update({"Type": "text", "Value": repr(value)})
    return e.meta, e.arrays


class Reply:
    """An answer: .ok, .error, .output, .seconds; .value() decodes it back into numbers, arrays and dicts."""

    def __init__(self, meta, columns):
        self.meta, self.columns = meta, columns
        self.ok = meta.get("Ok") == "1"
        self.error = meta.get("Error", "")
        self.traceback = meta.get("Traceback", "")
        self.output = meta.get("Output", "")
        self.seconds = float(meta.get("Seconds", 0) or 0)

    def _array(self, column):
        return _shaped(self.columns[column], self.meta.get("Shape." + column, ""), "C")

    def value(self):
        kind = self.meta.get("Type", "none")
        if kind == "number":
            return float(self.meta["Value"])
        if kind == "text":
            return self.meta.get("Value", "")
        if kind == "array":
            return self._array("a0")
        if kind == "dict":
            out = {}

            def put(path, v):
                d = out
                keys = path.split(".")
                for k in keys[:-1]:
                    d = d.setdefault(k, {})
                d[keys[-1]] = v

            for k, v in self.meta.items():
                if k.startswith("R."):
                    put(k[2:], float(v))
                elif k.startswith("S."):
                    put(k[2:], v)
                elif k.startswith("Array."):
                    put(v, self._array(k[6:]))
            return out
        return None


# ---------------------------------------------------------------------------
# The gateway
# ---------------------------------------------------------------------------

# The names code may use without importing them: brought in when the code uses them, not before (TensorFlow alone
# takes seconds to import and half a gigabyte to hold).
AUTO = {"np": "numpy", "numpy": "numpy", "torch": "torch", "tf": "tensorflow", "tensorflow": "tensorflow", "keras": "keras",
        "jax": "jax", "jnp": "jax.numpy", "pyhf": "pyhf", "scipy": "scipy", "ml": "sphere_ml", "spx": "sphere_spx",
        "sg": "sphere_graph", "dana": "sphere_dana", "xg": "sphere_xgrad", "xf": "sphere_xf"}


def _names(code):
    out, stack = set(), [code]
    while stack:
        c = stack.pop()
        out.update(c.co_names)
        stack.extend(k for k in c.co_consts if isinstance(k, types.CodeType))
    return out


def _write_text(path, text):
    part = "%s.%d.part" % (path, os.getpid())
    with open(part, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    _replace(part, path)


def _lock(path):
    """Held by this process for as long as it lives, so that a second gateway on the folder steps aside; None if taken."""
    fh = open(path, "a+b")
    try:
        if os.name == "nt":
            import msvcrt
            fh.seek(0)
            msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fh
    except OSError:
        fh.close()
        return None


def _alive(pid):
    if os.name == "nt":
        import ctypes
        handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, int(pid))   # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        code = ctypes.c_ulong()
        ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
        ctypes.windll.kernel32.CloseHandle(handle)
        return code.value == 259     # STILL_ACTIVE
    try:
        os.kill(int(pid), 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def frameworks_text(found):
    """frameworks.txt: 'Key: value' lines, what a program can read with no gateway running."""
    lines = ["python: " + str(found.get("executable", sys.executable))]
    for key in ("torch", "tensorflow", "keras", "keras_backend", "jax", "pyhf", "numpy", "sklearn", "onnx", "sphere_ml"):
        if found.get(key):
            lines.append("%s: %s" % (key, found[key]))
    lines.append("python_version: " + str(found.get("python", "")))
    return "\n".join(lines) + "\n"


def _trace(error):
    """The traceback from the first frame that is not the gateway's: what the code asked, and where it failed."""
    tb = error.__traceback__
    here = os.path.abspath(__file__)
    while tb is not None and os.path.abspath(tb.tb_frame.f_code.co_filename) == here:
        tb = tb.tb_next
    return "".join(traceback.format_exception(type(error), error, tb, limit=8))


class Gateway:
    def __init__(self, spool, idle=600.0, parent=None):
        self.spool, self.idle, self.parent = spool, float(idle), parent
        self.inbox = os.path.join(spool, "in")
        self.outbox = os.path.join(spool, "out")
        self.token = secrets.token_hex(16)
        self.stopping = False
        self.sessions = {}
        self.models = {}
        self.leftovers = set()
        self.served = 0
        self.hot = {}              # what is known of each model in use: calls, its graph, why it has none
        self.extra = {}            # what the running request adds to its answer
        self.vias = {}             # the gateways of the other Pythons, by folder
        import sphere_ml as ml
        self.ml = ml
        self.have = ml.frameworks()
        self.server = ml.Server()     # its commands -- train, stats, ... -- are the gateway's too; stdout now goes to the log
        self.server.send = self._progress

    # ---- files ----------------------------------------------------------

    def _progress(self, obj):
        if "progress" in obj and obj.get("id"):
            try:
                with open(os.path.join(self.outbox, "%s.progress" % obj["id"]), "a", encoding="utf-8") as fh:
                    fh.write("%s %s\n" % (obj["progress"], _escape(obj.get("message", ""))))
            except OSError:
                pass

    def _announce(self):
        found = self.ml.frameworks()
        _write_text(os.path.join(self.spool, "frameworks.txt"), frameworks_text(found))
        _write_text(os.path.join(self.spool, "gateway.txt"), "".join("%s: %s\n" % kv for kv in (
            ("Pid", os.getpid()), ("Token", self.token), ("Python", sys.executable), ("Protocol", PROTOCOL),
            ("Started", time.time()), ("Engine", self.ml.describe()))))

    def _heart(self):
        beat = 0
        stop_file = os.path.join(self.spool, "stop")
        while not self.stopping:
            beat += 1
            try:
                _write_text(os.path.join(self.spool, "heartbeat.txt"), "%d\n" % beat)
                for name in os.listdir(self.inbox):
                    if name.endswith(".cancel"):
                        self.server.canceled.add(name[:-7])
                        os.remove(os.path.join(self.inbox, name))
                if os.path.exists(stop_file):
                    os.remove(stop_file)
                    self.stopping = True
            except OSError:
                pass
            if self.parent and not _alive(self.parent):
                self.stopping = True
            time.sleep(1.0)

    def _remove(self, path):
        for delay in (0.0, 0.002, 0.01):
            time.sleep(delay)
            try:
                os.remove(path)
                return True
            except FileNotFoundError:
                return True
            except OSError:
                continue
        return False

    # ---- the loop -------------------------------------------------------

    def run(self):
        self._announce()
        threading.Thread(target=self._heart, daemon=True).start()
        last = time.monotonic()
        while not self.stopping:
            served = False
            for name in sorted(os.listdir(self.inbox)):
                path = os.path.join(self.inbox, name)
                if path in self.leftovers:    # answered, but its writer still held it open
                    if self._remove(path):
                        self.leftovers.discard(path)
                elif name.endswith(".spx"):
                    served = self._handle(path, name[:-4]) or served
            now = time.monotonic()
            if served:
                last = now
            elif now - last > self.idle:
                break
            quiet = now - last
            time.sleep(0.0005 if quiet < 0.5 else 0.005 if quiet < 30 else 0.05)
        self.stopping = True
        self._remove(os.path.join(self.spool, "gateway.txt"))

    def _handle(self, path, rid):
        try:
            envelope = read_envelope(path)
        except OSError:
            return False                   # still being written
        except (ValueError, struct.error) as e:
            envelope = ({"Op": "bad"}, {})
            sys.stderr.write("mlink: %s: %s\n" % (os.path.basename(path), e))
        if envelope is None:
            return False
        if not self._remove(path):
            self.leftovers.add(path)
        meta, columns = envelope
        order = "F" if meta.get("Order") == "F" else "C"
        out = os.path.join(self.outbox, rid + ".spx")
        t0 = time.perf_counter()
        head, arrays = {"Ok": "1"}, []
        try:
            if meta.get("Token") != self.token:
                head.update({"Ok": "0", "Error": "the gateway has restarted: this request was made for another run", "Retry": "1"})
            else:
                arrays_in = {k: _shaped(v, meta.get("Shape." + k, ""), order) for k, v in columns.items()}
                forwarded = self._forward(meta.get("Op", ""), meta, arrays_in)
                if forwarded is not None:
                    body, arrays = forwarded
                    head.update(body)
                else:
                    value, printed = self._execute(meta.get("Op", ""), meta, arrays_in, rid)
                    body, arrays = encode(value)
                    head.update(body)
                    head.update(self.extra)
                    if printed:
                        head["Output"] = printed
        except BaseException as e:      # SystemExit included: the code asked for it, the gateway stays
            if isinstance(e, KeyboardInterrupt):
                raise
            head = {"Ok": "0", "Error": "%s: %s" % (type(e).__name__, e), "Traceback": _trace(e)}
            arrays = []
        self.extra = {}
        head["Seconds"] = "%.6f" % (time.perf_counter() - t0)
        head["Order"] = order
        self.served += 1
        self.server.canceled.discard(rid)
        with contextlib.suppress(OSError):
            os.remove(os.path.join(self.outbox, rid + ".progress"))
        try:
            write_envelope(out, head, arrays, order)
        except Exception as e:          # what it returned cannot be written (too long a name, a shape it cannot say)
            write_envelope(out, {"Ok": "0", "Error": "the answer could not be written: %s: %s" % (type(e).__name__, e),
                                 "Seconds": head["Seconds"], "Order": order})
        return True

    # ---- what a request can ask -----------------------------------------

    def _execute(self, op, meta, a, rid):
        if op == "ping":
            return {"pong": 1, "engine": self.ml.describe(), "pid": os.getpid(), "served": self.served,
                    "python": sys.executable}, ""
        if op == "py":
            return self._python(meta, a)
        if op == "predict":
            return self._predict(meta, a), ""
        if op == "pyhf":
            return self._pyhf(meta, a), ""
        if op == "frameworks":
            return self.ml.frameworks(devices=meta.get("Devices", "0") == "1"), ""
        if op == "call":
            args = json.loads(meta.get("Args") or "{}")
            for k in ("data", "model", "path", "graph", "script", "workspace", "xml", "out", "folder"):
                if isinstance(args.get(k), str):
                    args[k] = native(args[k])
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                value = self.server.dispatch(meta.get("Cmd", ""), args, rid)
            return value, buf.getvalue()
        if op == "stop":
            self.stopping = True
            return {"stopping": 1}, ""
        raise ValueError("unknown Op '%s' (py, predict, pyhf, call, frameworks, ping, stop)" % op)

    # ---- the Python that can serve it ------------------------------------

    _FRAMEWORK = {"torch": "torch", "tf": "tensorflow", "tensorflow": "tensorflow", "keras": "keras", "jax": "jax", "jnp": "jax",
                  "pyhf": "pyhf"}

    def _wanted(self, op, meta):
        """The frameworks a request will use."""
        if op == "py":
            names = _names(compile(meta.get("Code", ""), "<mlink>", "exec"))
            return {self._FRAMEWORK[n] for n in names & set(self._FRAMEWORK)}
        if op == "pyhf":
            return {"pyhf"}
        if op == "predict":
            kind = meta.get("Kind") or self._kind(native(meta.get("Model", "")))
            return {"torchscript": {"torch"}, "torch.export": {"torch"}, "keras": {"keras"}, "tensorflow": {"tensorflow"}}.get(kind, set())
        return set()

    def _forward(self, op, meta, arrays):
        """The answer of the Python that holds what this one lacks; None when this one can answer (or say why not)."""
        try:
            missing = sorted(f for f in self._wanted(op, meta) if not self.have.get(f))
        except Exception:      # a syntax error, a file that is not there: the request's own failure, reported in its place
            return None
        if not missing:
            return None
        routes = {}
        for line in read_lines(os.path.join(self.spool, "routes.txt")):
            framework, _, command = line.partition("\t")
            routes[framework] = command.split("\t")
        target = next((routes[f] for f in missing if f in routes), None)
        if target is None:
            return None
        fields = {k: v for k, v in meta.items() if k not in ("Op", "Token", "Protocol", "Order", "Title") and not k.startswith("Shape.")}
        reply = self._via(target).request(op, fields, arrays)
        head = {k: v for k, v in reply.meta.items() if k not in ("Order", "Seconds")}
        head["Via"] = target[-1]
        return head, [(name, reply._array(name)) for name in reply.columns]

    def _via(self, command):
        """The gateway of another Python, in its own folder, started the first time it is needed."""
        sub = os.path.join(self.spool, "via", hashlib.sha1("\0".join(command).encode("utf-8")).hexdigest()[:12])
        if sub not in self.vias:
            script, where = os.path.abspath(__file__), sub
            if os.path.basename(command[0]).lower().startswith("wsl"):
                script, where = _as_wsl(script), _as_wsl(sub)
            os.makedirs(sub, exist_ok=True)
            subprocess.run(command + [script, "--install", where], check=True, timeout=180, stdin=subprocess.DEVNULL,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.vias[sub] = Link(sub, timeout=180.0)
        return self.vias[sub]

    # ---- what is imported, and how --------------------------------------

    def _module(self, name):
        """A framework, imported the way that works: PyTorch before TensorFlow on Windows (the other order fails to
        initialize torch's DLLs on some machines), JAX in float64, Keras on the backend Sphere chooses for it."""
        os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
        if name in ("tensorflow", "keras") and os.name == "nt" and "torch" not in sys.modules and self.ml._dist_version("torch"):
            importlib.import_module("torch")
        if name == "keras":
            return self.ml._keras()
        try:
            module = importlib.import_module(name)
        except ImportError as e:
            raise ImportError("%s is not installed in %s: 'pip install %s' there; ':ml locate' shows what each Python holds (%s)"
                              % (name.split(".")[0], sys.executable, name.split(".")[0], e)) from e
        if name.split(".")[0] == "jax":
            importlib.import_module("jax").config.update("jax_enable_x64", True)
        return module

    def _python(self, meta, arrays):
        name = meta.get("Session") or "default"
        ns = self.sessions.setdefault(name, {"__name__": "__mlink__"})
        ns.update(arrays)
        tree = ast.parse(meta.get("Code", ""), "<mlink>", "exec")
        last = ast.Expression(tree.body.pop().value) if tree.body and isinstance(tree.body[-1], ast.Expr) else None
        body = compile(tree, "<mlink>", "exec")
        tail = compile(last, "<mlink>", "eval") if last is not None else None
        for used in (_names(body) | (_names(tail) if tail else set())) & set(AUTO):
            if used not in ns:
                ns[used] = self._module(AUTO[used])
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            exec(body, ns)
            value = eval(tail, ns) if tail else None
        return value, buf.getvalue()

    # ---- models ---------------------------------------------------------

    @staticmethod
    def _kind(path):
        low = path.lower()
        if os.path.isdir(path):
            return "tensorflow" if os.path.exists(os.path.join(path, "saved_model.pb")) else "keras"
        for ext, kind in ((".snn.spx", "network"), (".sng.spx", "graph"), (".keras", "keras"), (".h5", "keras"),
                          (".pt2", "torch.export"), (".pt", "torchscript"), (".pth", "torchscript"), (".ts", "torchscript")):
            if low.endswith(ext):
                return kind
        raise ValueError("cannot tell what kind of model %s is: give Kind (network, graph, torchscript, torch.export, keras,"
                         " tensorflow)" % path)

    def _load(self, path, kind):
        path = native(path)
        if not os.path.exists(path):
            raise FileNotFoundError(path)
        kind = kind or self._kind(path)
        key = (path, kind, os.path.getmtime(path))
        if key in self.models:
            return kind, self.models[key]
        if kind == "network":
            spec, weights = self.ml.load(path)
            model = (spec, {k: np.asarray(v, dtype=np.float64) for k, v in weights.items()})
        elif kind == "graph":
            model = self._module("sphere_graph").load(path)
        elif kind == "torchscript":
            model = self._module("torch").jit.load(path, map_location="cpu").eval()
        elif kind == "torch.export":
            model = self._module("torch").export.load(path).module()
        elif kind == "keras":
            model = self._module("keras").saving.load_model(path, compile=False)
        elif kind == "tensorflow":
            loaded = self._module("tensorflow").saved_model.load(path)
            model = (loaded, loaded.signatures.get("serving_default"))
        else:
            raise ValueError("unknown Kind '%s'" % kind)
        self.models[key] = model
        return kind, model

    def _predict(self, meta, arrays):
        kind, model = self._load(meta.get("Model", ""), meta.get("Kind", ""))
        names = [n.strip() for n in meta.get("Inputs", "").split(",") if n.strip()] or list(arrays)
        inputs = [arrays[n] for n in names]
        out = self._infer(kind, model, names, inputs, arrays)
        self._tier(native(meta.get("Model", "")), kind, inputs, out)
        return out

    # ---- a model in use becomes native code -----------------------------

    def _tier(self, path, kind, inputs, out):
        if kind == "network":          # sphere_nn.hpp runs those: another runtime, and already in the program's hands
            return
        hot = self.hot.setdefault((path, os.path.getmtime(path)), {"calls": 0, "graph": None, "busy": False, "why": ""})
        hot["calls"] += 1
        if kind == "graph":
            hot["graph"] = path
        if hot["graph"]:
            self.extra["Native"] = hot["graph"]
        elif hot["calls"] >= PROMOTE_AFTER and not hot["busy"] and not hot["why"]:
            answer = _single_array(out)
            if answer is None:
                hot["why"] = "several outputs"
                return
            hot["busy"] = True
            threading.Thread(target=self._promote, args=(hot, path, kind, [np.array(i, dtype=np.float64) for i in inputs], np.array(answer)),
                             daemon=True, name="mlink-promote").start()

    def _promote(self, hot, path, kind, inputs, want):
        """The model as a graph, kept only if it gives what the framework gave on the program's own inputs."""
        try:
            import sphere_graph as sg
            folder = os.path.join(self.spool, "native")
            os.makedirs(folder, exist_ok=True)
            name = "auto_" + hashlib.sha1(("%s|%r" % (path, os.path.getmtime(path))).encode("utf-8")).hexdigest()[:16]
            file = os.path.join(folder, name + ".sng.spx")
            if not os.path.exists(file):
                if kind == "tensorflow":
                    self._module("sphere_tf").load(path, name=name, folder=folder)
                elif kind == "keras":
                    self.ml.graph_export({"kind": "keras", "path": path, "name": name, "folder": folder,
                                          "shapes": [list(i.shape[1:]) for i in inputs]})
                elif kind == "torchscript":
                    self.ml.graph_export({"kind": "torchscript", "path": path, "name": name, "folder": folder,
                                          "shapes": [list(i.shape[1:]) for i in inputs]})
                elif kind == "torch.export":
                    torch = self._module("torch")
                    sg.export_program(torch.export.load(path), tuple(torch.as_tensor(i, dtype=torch.float32) for i in inputs), name, folder=folder)
                else:
                    raise ValueError(kind + " cannot be read as a graph")
            got = sg.run(sg.load(file), inputs)
            got = np.asarray(got[0] if isinstance(got, list) else got, dtype=np.float64)
            eps = np.finfo(want.dtype).eps if want.dtype.kind == "f" else np.finfo(np.float64).eps
            limit = 64.0 * eps * max(1.0, float(np.max(np.abs(want))) if want.size else 1.0)
            if got.shape != want.shape:
                raise ValueError("the graph gives shape %s, the framework %s" % (got.shape, want.shape))
            worst = float(np.max(np.abs(got - want))) if want.size else 0.0
            if worst > limit:
                raise ValueError("the graph differs by %.3g from the framework (allowed %.3g)" % (worst, limit))
            hot["graph"] = file
            sys.stderr.write("mlink: %s promoted to a graph (within %.2g of %s)\n" % (os.path.basename(path), worst, kind))
        except BaseException as e:
            hot["why"] = "%s: %s" % (type(e).__name__, e)
            sys.stderr.write("mlink: %s stays served: %s\n" % (os.path.basename(path), hot["why"][:400]))
        finally:
            hot["busy"] = False

    def _infer(self, kind, model, names, inputs, arrays):
        if kind == "network":
            spec, weights = model
            feed = {k: arrays.get(k) for k in ("x", "mask", "hl")}
            ready = self.ml.prepare(spec, spec["norm"], x=feed["x"], mask=feed["mask"], hl=feed["hl"], dtype=np.float64)
            logits = self.ml._forward_np(self.ml.NumpyOps, spec, weights, ready)
            return self.ml.calibrated(spec, np.asarray(logits, dtype=np.float64))
        if kind == "graph":
            return self._module("sphere_graph").run(model, inputs)
        if kind in ("torchscript", "torch.export"):
            torch = self._module("torch")
            parameter = next(itertools.chain(getattr(model, "parameters", lambda: [])(),
                                             getattr(model, "buffers", lambda: [])()), None)
            dtype = parameter.dtype if parameter is not None and parameter.is_floating_point() else torch.float32
            with torch.no_grad():
                return model(*[torch.as_tensor(v, dtype=dtype) for v in inputs])
        if kind == "keras":
            return model(inputs[0] if len(inputs) == 1 else inputs, training=False)
        tf = self._module("tensorflow")
        loaded, signature = model
        if signature is None:
            return loaded(*[tf.constant(v, dtype=tf.float32) for v in inputs])
        wanted = signature.structured_input_signature[1]
        order = names if set(names) == set(wanted) else list(wanted)
        if len(order) != len(inputs):
            raise ValueError("the SavedModel takes %s, not %d array(s)" % (list(wanted), len(inputs)))
        given = dict(zip(order, inputs))
        return signature(**{k: tf.constant(given[k], dtype=wanted[k].dtype) for k in order})

    # ---- pyhf -----------------------------------------------------------

    def _pyhf(self, meta, a):
        spec = {}
        if meta.get("Workspace"):
            spec["workspace"] = native(meta["Workspace"])
        elif meta.get("Xml"):
            spec["xml"] = native(meta["Xml"])
        else:
            for key in ("signal", "background", "bkg_uncertainty", "observed"):
                if key not in a:
                    raise ValueError("pyhf needs the arrays signal, background, bkg_uncertainty, observed, or a Workspace")
                spec[key] = np.ravel(a[key]).tolist()
        points = int(meta.get("Points", 41))
        if points > 0:
            backends = None if meta.get("Backends") == "all" else [b.strip() for b in meta.get("Backends", "numpy").split(",")]
            return self.ml.stats(spec, backends, float(meta["MuMax"]) if meta.get("MuMax") else None, points)
        return self._cls(spec, float(meta.get("Mu", 1.0)), meta.get("Backends", "numpy"))

    def _cls(self, spec, mu, backend):
        """CLs at one signal strength, without a scan: the call a program makes in a loop."""
        import pyhf
        with self.ml.framework_state():
            pyhf.set_backend(backend, precision="64b")
            try:
                if "workspace" in spec:
                    with open(spec["workspace"]) as fh:
                        ws = pyhf.Workspace(json.load(fh))
                    model, data = ws.model(), ws.data(ws.model())
                else:
                    model = pyhf.simplemodels.uncorrelated_background(spec["signal"], spec["background"], spec["bkg_uncertainty"])
                    data = list(spec["observed"]) + list(model.config.auxdata)
                best = pyhf.infer.mle.fit(data, model)
                observed, band = pyhf.infer.hypotest(mu, data, model, test_stat="qtilde", return_expected_set=True)
                return {"backends": {backend: {"mu_hat": float(pyhf.tensorlib.to_numpy(best)[model.config.poi_index]),
                                               "cls_obs": float(observed), "cls_exp": [float(v) for v in band]}},
                        "pyhf": pyhf.__version__}
            finally:
                pyhf.set_backend("numpy")


def serve(spool, idle=600.0, parent=None):
    spool = native(spool)
    for sub in ("in", "out"):
        os.makedirs(os.path.join(spool, sub), exist_ok=True)
    held = _lock(os.path.join(spool, "gateway.lock"))
    if held is None:                   # another gateway has the folder: nothing to do, and no error
        return 0
    os.environ.setdefault("SPHERE_BRIDGE", os.path.dirname(os.path.abspath(spool)))
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    for box in ("in", "out"):          # what an earlier run left: nobody waits for it any more
        for name in os.listdir(os.path.join(spool, box)):
            with contextlib.suppress(OSError):
                os.remove(os.path.join(spool, box, name))
    with contextlib.suppress(OSError):
        os.remove(os.path.join(spool, "stop"))
    Gateway(spool, idle, parent).run()
    held.close()
    return 0


def spawn(spool, idle=600.0, parent=None, env=()):
    """Starts the gateway apart from whoever asked, and returns. Its handles are its own: a gateway that inherited a
    program's output pipe would keep that program looking unfinished to whoever reads the pipe, for as long as it lives."""
    spool = native(spool)
    os.makedirs(spool, exist_ok=True)
    args = [sys.executable, os.path.abspath(__file__), "--serve", spool, "--idle", str(idle)]
    if parent:
        args += ["--parent-pid", str(parent)]
    environment = dict(os.environ, PYTHONUNBUFFERED="1", PYTHONDONTWRITEBYTECODE="1")
    environment.update(kv.split("=", 1) for kv in env)
    log_file = os.path.join(spool, "gateway.log")
    with open(log_file, "wb" if os.path.exists(log_file) and os.path.getsize(log_file) > 8 << 20 else "ab") as log:   # bounded
        options = {"creationflags": 0x00000008 | 0x00000200} if os.name == "nt" else {"start_new_session": True}   # DETACHED, NEW_GROUP
        subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log, close_fds=True, cwd=spool, env=environment, **options)
    return 0


def install(spool, env=(), idle=None):
    """Writes start.bat and start.sh, and frameworks.txt, for this interpreter: how any client, on either side of WSL,
    starts the gateway, and what it will find there."""
    import sphere_ml as ml
    spool = native(os.path.abspath(native(spool)))
    os.makedirs(spool, exist_ok=True)
    me, py = os.path.abspath(__file__), sys.executable
    extra = "".join(" --env %s" % kv for kv in env) + (" --idle %s" % idle if idle else "")
    win = ""
    sh = "#!/bin/sh\nexec '%s' '%s' --spawn '%s'%s >/dev/null 2>&1\n" % (py, me, spool, extra)
    if os.name == "nt":
        win = '@echo off\r\n"%s" "%s" --spawn "%s"%s >nul 2>&1\r\n' % (py, me, spool, extra)
        wsl = re.sub(r"^([A-Za-z]):", lambda m: "/mnt/" + m.group(1).lower(), py.replace("\\", "/"))
        sh = "#!/bin/sh\nexec '%s' '%s' --spawn '%s'%s >/dev/null 2>&1\n" % (wsl, me, spool, extra)
    elif _in_wsl():
        distro = os.environ.get("WSL_DISTRO_NAME", "")
        win = '@echo off\r\nwsl.exe %s-- "%s" "%s" --spawn "%s"%s >nul 2>&1\r\n' % ("-d %s " % distro if distro else "", py, me, spool, extra)
    if win:
        with open(os.path.join(spool, "start.bat"), "w", encoding="utf-8", newline="") as fh:
            fh.write(win)
    with open(os.path.join(spool, "start.sh"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(sh)
    _write_text(os.path.join(spool, "frameworks.txt"), frameworks_text(ml.frameworks()))
    return 0


# ---------------------------------------------------------------------------
# The client, for a Python that wants the frameworks of another (PyROOT's, Geant4's, one with no torch)
# ---------------------------------------------------------------------------

def read_keys(path):
    out = {}
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                if ":" in line:
                    k, v = line.split(":", 1)
                    out[k.strip()] = v.strip()
    except OSError:
        pass
    return out


def detect(spool=None):
    """What the gateway's Python holds, read from frameworks.txt: {"python": ..., "torch": "2.11.0", ...}, a framework
    being absent when it is not there. Starts nothing."""
    found = read_keys(os.path.join(folder(spool), "frameworks.txt"))
    if not found and os.environ.get("SPHERE_ML_FRAMEWORKS"):
        found = dict(kv.split("=", 1) for kv in os.environ["SPHERE_ML_FRAMEWORKS"].split(";") if "=" in kv)
        if os.environ.get("SPHERE_ML_PYTHON"):
            found["python"] = os.environ["SPHERE_ML_PYTHON"]
    return found


class LinkError(RuntimeError):
    pass


def read_lines(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return [line.rstrip("\r\n") for line in fh if line.strip()]
    except OSError:
        return []


def _as_wsl(path):
    return re.sub(r"^([A-Za-z]):", lambda m: "/mnt/" + m.group(1).lower(), os.path.abspath(path).replace("\\", "/"))


def _single_array(value):
    """The one array of an answer that has one: a tensor, or a dict or list of a single tensor; None otherwise."""
    if isinstance(value, dict) and len(value) == 1:
        value = next(iter(value.values()))
    elif isinstance(value, (list, tuple)) and len(value) == 1:
        value = value[0]
    a = _to_numpy(value)
    return a if a is not None and a.dtype.kind in "biuf" and a.ndim > 0 else None


class Link:
    """A connection to the gateway, which is started if none is running."""

    _ids = itertools.count(1)

    def __init__(self, spool=None, start=True, timeout=120.0):
        self.spool = native(folder(spool))
        self.nonce = secrets.token_hex(3)
        self.token = ""
        self.natives = {}                 # model file -> (its date, its graph or None, when it was last looked at)
        self._connect(start, timeout)

    def _connect(self, start, timeout):
        deadline, started = time.monotonic() + timeout, False
        while True:
            self.token = read_keys(os.path.join(self.spool, "gateway.txt")).get("Token", "")
            if self.token:
                try:
                    if self.request("ping", timeout=2.0, retry=False).ok:
                        return
                except LinkError:
                    pass
                before = _read_beat(self.spool)     # busy with someone's long computation: it will come to this one
                time.sleep(1.5)
                if before and before != _read_beat(self.spool):
                    return
            if start and not started:
                self._start()
                started = True
            if time.monotonic() > deadline:
                raise LinkError("no gateway answers in %s. In Sphere: ':ml link start'; elsewhere: python sphere_mlink.py "
                                "--serve <folder>; its log is gateway.log there" % self.spool)
            time.sleep(0.05)

    def _start(self):
        script = os.path.join(self.spool, "start.bat" if os.name == "nt" else "start.sh")
        if os.path.exists(script):
            subprocess.run(["cmd", "/c", script] if os.name == "nt" else ["sh", script], check=False, stdin=subprocess.DEVNULL,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        elif np is not None:
            spawn(self.spool)

    def request(self, op, meta=None, arrays=None, timeout=None, retry=True):
        if retry:                        # the gateway stops when idle: the next call starts it again
            self.token = read_keys(os.path.join(self.spool, "gateway.txt")).get("Token", "")
            if not self.token:
                self._connect(True, 120.0)
        rid = "%d-%d-%s" % (os.getpid(), next(Link._ids), self.nonce)
        head = {"Op": op, "Token": self.token, "Protocol": PROTOCOL}
        head.update(meta or {})
        columns = []
        for name, value in (arrays or {}).items():
            if len(name) > 24:
                raise ValueError("an array's name holds 24 characters: " + name)
            a = np.asarray(value, dtype=np.float64)
            head["Shape." + name] = " ".join(str(s) for s in a.shape)
            columns.append((name, a))
        os.makedirs(os.path.join(self.spool, "in"), exist_ok=True)
        write_envelope(os.path.join(self.spool, "in", rid + ".spx"), head, columns)
        path = os.path.join(self.spool, "out", rid + ".spx")
        started, pause, beat, beat_seen, kicked = time.monotonic(), 0.0002, None, time.monotonic(), False
        while not os.path.exists(path):
            time.sleep(pause)
            pause = min(pause * 1.5, 0.02)
            now = time.monotonic()
            if timeout and now - started > timeout:
                with contextlib.suppress(OSError):
                    open(os.path.join(self.spool, "in", rid + ".cancel"), "w").close()
                raise LinkError("no answer to '%s' after %g s" % (op, timeout))
            if now - beat_seen > 5.0:
                current = _read_beat(self.spool)
                if retry and read_keys(os.path.join(self.spool, "gateway.txt")).get("Token", "") != self.token:
                    self._connect(True, 120.0)   # gone, or another run: nobody will answer what was left for this one
                    return self.request(op, meta, arrays, timeout, retry=False)
                if current != beat:
                    beat, beat_seen, kicked = current, now, False
                else:
                    if retry and not kicked and now - beat_seen > 15.0:   # dead, it is replaced; alive, its lock turns the new one away
                        self._start()
                        kicked = True
                    if now - beat_seen > (90.0 if retry else 30.0):   # already sent twice: this request is what stops it
                        raise LinkError("the gateway has stopped answering (see gateway.log in %s)" % self.spool)
        meta_back, cols = _settled(path)
        with contextlib.suppress(OSError):
            os.remove(path)
        reply = Reply(meta_back, cols)
        if reply.meta.get("Retry") == "1" and retry:
            self.token = read_keys(os.path.join(self.spool, "gateway.txt")).get("Token", self.token)
            return self.request(op, meta, arrays, timeout, retry=False)
        return reply

    def _value(self, reply):
        if not reply.ok:
            raise LinkError("%s\n%s" % (reply.error, reply.traceback))
        if reply.output:
            sys.stdout.write(reply.output)
        return reply.value()

    def eval(self, code, session="default", timeout=None, **arrays):
        """Runs Python in the gateway (numpy as np, torch, tf, keras, jax, jnp, pyhf are there when used) with the arrays
        given as variables; the value of its last expression comes back: numbers, arrays and tensors, dicts of them."""
        return self._value(self.request("py", {"Code": code, "Session": session}, arrays, timeout))

    def predict(self, model, *inputs, kind="", timeout=None, fast=True, **named):
        """A saved model on arrays: TorchScript, torch.export, Keras, a SavedModel, a Sphere network or graph. Once the
        gateway has made a hot model a graph, and if that is faster here, it runs in this process (fast=False: never)."""
        arrays = dict(named)
        arrays.update(("i%d" % k, v) for k, v in enumerate(inputs))
        path = os.path.abspath(model)
        graph = self._graph_of(path) if fast else None
        if graph is not None:
            try:
                return _ran(graph, arrays)
            except Exception:             # a batch or a shape the graph was not made for: back to asking
                self.natives[path] = (self.natives[path][0], None, time.monotonic())
        t0 = time.perf_counter()
        reply = self.request("predict", {"Model": path, "Kind": kind, "Inputs": ",".join(arrays)}, arrays, timeout)
        value = self._value(reply)
        if fast and reply.meta.get("Native"):
            self._adopt(path, reply.meta["Native"], arrays, value, time.perf_counter() - t0)
        return value

    def _graph_of(self, path):
        entry = self.natives.get(path)
        if entry is None or entry[1] is None:
            return None
        if time.monotonic() - entry[2] > 1.0:     # a model saved again is not the graph of the old one
            if not os.path.exists(path) or os.path.getmtime(path) != entry[0]:
                del self.natives[path]
                return None
            self.natives[path] = (entry[0], entry[1], time.monotonic())
        return entry[1]

    def _adopt(self, path, where, arrays, want, asked_seconds):
        stamp = os.path.getmtime(path)
        if path in self.natives and self.natives[path][0] == stamp:
            return
        self.natives[path] = (stamp, None, time.monotonic())   # decided once for this version of the model
        try:
            import sphere_graph as sg
            graph = sg.load(native(where))
            first = _ran(graph, arrays)
            t0 = time.perf_counter()
            _ran(graph, arrays)
            seconds = time.perf_counter() - t0
            answer = _single_array(want)
            if answer is not None and np.allclose(first, answer, atol=1e-5, rtol=1e-5) and seconds < asked_seconds:
                self.natives[path] = (stamp, graph, time.monotonic())
        except Exception as e:
            sys.stderr.write("sphere_mlink: staying with the gateway for %s: %s\n" % (os.path.basename(path), e))

    def pyhf(self, signal=None, background=None, bkg_uncertainty=None, observed=None, workspace=None, mu=1.0, points=0,
             mu_max=None, backends="numpy", timeout=None):
        """HistFactory by pyhf: points=0 asks CLs at mu only, points>0 scans mu for the 95 % CL upper limit as well."""
        meta = {"Mu": mu, "Points": points, "Backends": backends}
        if mu_max:
            meta["MuMax"] = mu_max
        arrays = {}
        if workspace:
            meta["Workspace"] = os.path.abspath(workspace)
        else:
            arrays = {"signal": signal, "background": background, "bkg_uncertainty": bkg_uncertainty, "observed": observed}
        return self._value(self.request("pyhf", meta, arrays, timeout))

    def call(self, cmd, timeout=None, **args):
        """One of the engine's commands (train, predict, stats, ...: the 'commands' command lists them)."""
        return self._value(self.request("call", {"Cmd": cmd, "Args": json.dumps(args)}, None, timeout))

    def frameworks(self, devices=False):
        return self._value(self.request("frameworks", {"Devices": "1" if devices else "0"}))

    def ping(self):
        return self._value(self.request("ping"))

    def stop(self):
        return self._value(self.request("stop", timeout=10.0))


def _ran(graph, arrays):
    import sphere_graph as sg
    out = sg.run(graph, [np.asarray(v, dtype=np.float64) for v in arrays.values()])
    return np.asarray(out[0] if isinstance(out, list) else out, dtype=np.float64)


def crosscheck(out):
    """What ':ml link check' asks of every language: the same requests, the same numbers, written to out."""
    k = link()
    x = np.array([[1.0, 2.0, 3.0], [4.0, 5.0, 6.5]])
    values = [k.eval("torch.linalg.norm(torch.as_tensor(x)).item()", x=x), k.eval("float(tf.reduce_sum(tf.constant(x) ** 2))", x=x)]
    p = k.pyhf([5.0, 10.0], [50.0, 60.0], [7.0, 8.0], [53.0, 65.0])["backends"]["numpy"]
    values += [p["cls_obs"], p["mu_hat"]] + k.eval("np.sin(x).ravel()", x=x).tolist()
    started = time.perf_counter()
    for _ in range(20):
        k.eval("1+1")
    ms = (time.perf_counter() - started) / 20 * 1e3
    return write_envelope(out, {"Title": "mlink check", "Engine": "Python"}, [("values", values), ("ms", [ms])])


def _read_beat(spool):
    try:
        with open(os.path.join(spool, "heartbeat.txt"), encoding="ascii") as fh:
            return fh.read().strip()
    except OSError:
        return None


_shared = None


def link(spool=None):
    """The connection of this process, made once."""
    global _shared
    if _shared is None:
        _shared = Link(spool)
    return _shared


def main(argv):
    opts, env = {}, []
    i = 0
    action, spool = None, None
    while i < len(argv):
        a = argv[i]
        if a == "--crosscheck":
            return 0 if crosscheck(argv[i + 1]) else 1
        if a in ("--serve", "--spawn", "--install", "--status", "--stop"):
            action, spool = a[2:], argv[i + 1] if i + 1 < len(argv) and not argv[i + 1].startswith("--") else None
            i += 2 if spool else 1
        elif a == "--env":
            env.append(argv[i + 1])
            i += 2
        elif a in ("--idle", "--parent-pid"):
            opts[a[2:]] = argv[i + 1]
            i += 2
        else:
            i += 1
    spool = native(folder(spool))
    idle, parent = float(opts.get("idle", 600.0)), opts.get("parent-pid")
    for kv in env:
        k, v = kv.split("=", 1)
        os.environ[k] = v
    if action == "serve":
        return serve(spool, idle, parent)
    if action == "spawn":
        return spawn(spool, idle, parent, env)
    if action == "install":
        return install(spool, env, opts.get("idle"))
    if action == "stop":
        with open(os.path.join(spool, "stop"), "w"):
            pass
        return 0
    state = read_keys(os.path.join(spool, "gateway.txt"))
    running = bool(state) and _alive(state.get("Pid", 0))
    print("gateway %s (%s)" % ("running, pid " + state["Pid"] if running else "not running", spool))
    print(json.dumps(detect(spool), indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
